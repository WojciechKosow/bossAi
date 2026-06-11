package com.BossAi.bossAi.service.edl;

import com.BossAi.bossAi.dto.edl.*;
import com.BossAi.bossAi.entity.AssetType;
import com.BossAi.bossAi.entity.ProjectAsset;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Strict (editor) vs lenient (pipeline) validation — the editor loop must
 * fail fast at save time with structured, per-field errors.
 */
class EdlValidatorTest {

    private EdlValidator validator;

    private final UUID videoAssetId = UUID.randomUUID();
    private final UUID imageAssetId = UUID.randomUUID();
    private final UUID voiceAssetId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        validator = new EdlValidator(new EffectRegistry());
    }

    // ─── fixtures ────────────────────────────────────────────────────────

    private List<ProjectAsset> assets() {
        return List.of(
                ProjectAsset.builder().id(videoAssetId).type(AssetType.VIDEO)
                        .durationSeconds(10.0).build(),
                ProjectAsset.builder().id(imageAssetId).type(AssetType.IMAGE).build(),
                ProjectAsset.builder().id(voiceAssetId).type(AssetType.VOICE)
                        .durationSeconds(8.0).build());
    }

    private EdlSegment.EdlSegmentBuilder segment(String id, int startMs, int endMs) {
        return EdlSegment.builder()
                .id(id).assetId(videoAssetId.toString()).assetUrl("http://x/" + id)
                .assetType("VIDEO").startMs(startMs).endMs(endMs).layer(0);
    }

    private EdlDto validEdl() {
        return EdlDto.builder()
                .version("1.0")
                .metadata(EdlMetadata.builder()
                        .totalDurationMs(8000).width(1080).height(1920).fps(30).build())
                .segments(new ArrayList<>(List.of(
                        segment("s0", 0, 4000).build(),
                        segment("s1", 4000, 8000)
                                .assetId(imageAssetId.toString()).assetType("IMAGE").build())))
                .audioTracks(new ArrayList<>(List.of(EdlAudioTrack.builder()
                        .id("v0").assetId(voiceAssetId.toString()).assetUrl("http://x/v")
                        .type("voiceover").startMs(0).endMs(8000).build())))
                .build();
    }

    private boolean hasError(EdlValidator.ValidationResult result, String scope, String field) {
        return result.errorIssues().stream()
                .anyMatch(i -> i.scope().equals(scope)
                        && (field == null || field.equals(i.field())));
    }

    // ─── happy path ──────────────────────────────────────────────────────

    @Test
    void validEdlPassesBothModes() {
        assertTrue(validator.validate(validEdl(), assets(), false).valid());
        assertTrue(validator.validate(validEdl(), assets(), true).valid());
    }

    @Test
    void validateWithoutAssetsKeepsLegacyBehavior() {
        assertTrue(validator.validate(validEdl()).valid());
    }

    // ─── asset existence ─────────────────────────────────────────────────

    @Test
    void unknownSegmentAssetRejected() {
        EdlDto edl = validEdl();
        edl.getSegments().get(0).setAssetId(UUID.randomUUID().toString());

        EdlValidator.ValidationResult result = validator.validate(edl, assets(), true);

        assertFalse(result.valid());
        assertTrue(hasError(result, "segments", "asset_id"));
        // structured issue is addressable
        EdlValidator.ValidationIssue issue = result.errorIssues().get(0);
        assertEquals(0, issue.index());
    }

    @Test
    void unknownAudioTrackAssetRejected() {
        EdlDto edl = validEdl();
        edl.getAudioTracks().get(0).setAssetId(UUID.randomUUID().toString());

        EdlValidator.ValidationResult result = validator.validate(edl, assets(), true);

        assertFalse(result.valid());
        assertTrue(hasError(result, "audio_tracks", "asset_id"));
    }

    /**
     * Regression: OverlayPlacementEngine emits layer-2 segments referencing raw
     * Asset entities (served via /internal/assets/raw/), which are NOT in the
     * ProjectAsset table. Rejecting them failed whole productions — they must
     * pass with a warning in both modes.
     */
    @Test
    void overlayLayerMayReferenceAssetsOutsideTheProject() {
        EdlDto edl = validEdl();
        EdlSegment overlay = EdlSegment.builder()
                .id("ov0").assetId(UUID.randomUUID().toString())
                .assetUrl("http://x/internal/assets/raw/ov0/file")
                .assetType("IMAGE").startMs(4200).endMs(5700).layer(2)
                .x(0.25f).y(0.3f).width(0.5f).height(0.28f)
                .build();
        edl.getSegments().add(overlay);

        EdlValidator.ValidationResult lenient = validator.validate(edl, assets(), false);
        EdlValidator.ValidationResult strict = validator.validate(edl, assets(), true);

        assertTrue(lenient.valid(), () -> "lenient rejected overlay: " + lenient.errors());
        assertTrue(strict.valid(), () -> "strict rejected overlay: " + strict.errors());
        assertTrue(lenient.warningIssues().stream()
                        .anyMatch(i -> "segments".equals(i.scope()) && "asset_id".equals(i.field())),
                "unknown overlay asset should still surface as a warning");
    }

    // ─── trim bounds ─────────────────────────────────────────────────────

    @Test
    void trimInBeyondSourceRejected() {
        EdlDto edl = validEdl();
        edl.getSegments().get(0).setTrimInMs(12_000); // source is 10s

        EdlValidator.ValidationResult result = validator.validate(edl, assets(), true);

        assertFalse(result.valid());
        assertTrue(hasError(result, "segments", "trim_in_ms"));
    }

    @Test
    void trimOutBeyondSourceRejected() {
        EdlDto edl = validEdl();
        edl.getSegments().get(0).setTrimOutMs(11_000);

        assertFalse(validator.validate(edl, assets(), true).valid());
    }

    @Test
    void trimOutBeforeTrimInRejected() {
        EdlDto edl = validEdl();
        edl.getSegments().get(0).setTrimInMs(2000);
        edl.getSegments().get(0).setTrimOutMs(1000);

        assertFalse(validator.validate(edl, assets(), true).valid());
    }

    @Test
    void segmentPlayingPastSourceEnd_strictErrorLenientWarning() {
        EdlDto edl = validEdl();
        // 4s segment but only 3s of source left after trim (10s - 7s)
        edl.getSegments().get(0).setTrimInMs(7000);

        assertFalse(validator.validate(edl, assets(), true).valid());
        EdlValidator.ValidationResult lenient = validator.validate(edl, assets(), false);
        assertTrue(lenient.valid());
        assertFalse(lenient.warningIssues().isEmpty());
    }

    @Test
    void imageSegmentsIgnoreTrimBounds() {
        EdlDto edl = validEdl();
        edl.getSegments().get(1).setTrimInMs(99_000); // IMAGE — no duration semantics

        assertTrue(validator.validate(edl, assets(), true).valid());
    }

    // ─── timeline continuity ─────────────────────────────────────────────

    @Test
    void primaryLayerGap_strictErrorLenientWarning() {
        EdlDto edl = validEdl();
        edl.getSegments().get(1).setStartMs(4800); // 800ms hole

        assertFalse(validator.validate(edl, assets(), true).valid());
        assertTrue(validator.validate(edl, assets(), false).valid());
    }

    @Test
    void overlapWithoutTransition_strictError_withTransitionOk() {
        EdlDto edl = validEdl();
        edl.getSegments().get(1).setStartMs(3700); // 300ms overlap

        assertFalse(validator.validate(edl, assets(), true).valid());

        edl.getSegments().get(0).setTransition(
                EdlTransition.builder().type("fade").durationMs(300).build());
        assertTrue(validator.validate(edl, assets(), true).valid());
    }

    // ─── effects ─────────────────────────────────────────────────────────

    @Test
    void unknownEffect_strictErrorLenientWarning() {
        EdlDto edl = validEdl();
        edl.getSegments().get(0).setEffects(List.of(
                EdlEffect.builder().type("hologram_spin").build()));

        assertFalse(validator.validate(edl, assets(), true).valid());
        assertTrue(validator.validate(edl, assets(), false).valid());
    }

    // ─── whisper words (editable subtitles) ──────────────────────────────

    @Test
    void blankWhisperWordRejected() {
        EdlDto edl = validEdl();
        edl.setWhisperWords(List.of(
                EdlWhisperWord.builder().word("  ").startMs(0).endMs(300).build()));

        EdlValidator.ValidationResult result = validator.validate(edl, assets(), true);
        assertFalse(result.valid());
        assertTrue(hasError(result, "whisper_words", "word"));
    }

    @Test
    void invertedWordTimingRejected() {
        EdlDto edl = validEdl();
        edl.setWhisperWords(List.of(
                EdlWhisperWord.builder().word("hej").startMs(500).endMs(400).build()));

        assertFalse(validator.validate(edl, assets(), true).valid());
    }

    @Test
    void outOfOrderWordsRejected() {
        EdlDto edl = validEdl();
        edl.setWhisperWords(List.of(
                EdlWhisperWord.builder().word("drugi").startMs(1000).endMs(1300).build(),
                EdlWhisperWord.builder().word("pierwszy").startMs(200).endMs(500).build()));

        assertFalse(validator.validate(edl, assets(), true).valid());
    }

    @Test
    void heavyWordOverlap_strictErrorLenientWarning() {
        EdlDto edl = validEdl();
        edl.setWhisperWords(List.of(
                EdlWhisperWord.builder().word("a").startMs(0).endMs(800).build(),
                EdlWhisperWord.builder().word("b").startMs(200).endMs(1000).build()));

        assertFalse(validator.validate(edl, assets(), true).valid());
        assertTrue(validator.validate(edl, assets(), false).valid());
    }

    @Test
    void touchingWordsAreFine() {
        EdlDto edl = validEdl();
        edl.setWhisperWords(List.of(
                EdlWhisperWord.builder().word("a").startMs(0).endMs(500).build(),
                EdlWhisperWord.builder().word("b").startMs(450).endMs(900).build()));

        assertTrue(validator.validate(edl, assets(), true).valid());
    }

    // ─── error shape ─────────────────────────────────────────────────────

    @Test
    void formattedStringsStayBackwardCompatible() {
        EdlDto edl = validEdl();
        edl.getSegments().get(0).setAssetId(null);

        EdlValidator.ValidationResult result = validator.validate(edl, assets(), true);

        assertFalse(result.valid());
        assertTrue(result.errors().get(0).startsWith("segments[0].asset_id"));
    }
}
