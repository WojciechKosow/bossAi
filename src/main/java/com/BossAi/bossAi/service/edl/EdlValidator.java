package com.BossAi.bossAi.service.edl;

import com.BossAi.bossAi.dto.edl.*;
import com.BossAi.bossAi.entity.ProjectAsset;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Waliduje EDL przed zapisem i przed wyslaniem do Remotion renderer.
 *
 * Two modes:
 *   - lenient (pipeline): auto-generated EDLs — structural errors only, timing
 *     roughness and unknown effects stay warnings (the pipeline self-heals via
 *     stripUnknownEffects/sanitizeTransitions).
 *   - strict (editor PUT /timeline): user-modified EDLs must fail fast at save
 *     time, not at render time — unknown assets, out-of-range trims, timeline
 *     gaps/overlaps and broken whisper words are rejected with structured,
 *     per-field errors the frontend can pin to the offending segment.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class EdlValidator {

    /** Same-layer gap below this is normal cut roughness; above it = visible freeze/black. */
    private static final int GAP_WARN_MS = 100;
    private static final int GAP_ERROR_MS = 500;
    /** Whisper words may touch; overlap beyond this means broken timings. */
    private static final int WORD_OVERLAP_TOLERANCE_MS = 150;

    private final EffectRegistry effectRegistry;

    /** One validation finding, addressable by the editor UI. */
    public record ValidationIssue(String scope, Integer index, String field, String message) {
        public String format() {
            StringBuilder sb = new StringBuilder(scope);
            if (index != null) sb.append('[').append(index).append(']');
            if (field != null) sb.append('.').append(field);
            return sb.append(": ").append(message).toString();
        }
    }

    public record ValidationResult(boolean valid,
                                   List<ValidationIssue> errorIssues,
                                   List<ValidationIssue> warningIssues) {
        public static ValidationResult ok() {
            return new ValidationResult(true, List.of(), List.of());
        }

        /** Formatted error strings — backward-compatible accessor. */
        public List<String> errors() {
            return errorIssues.stream().map(ValidationIssue::format).toList();
        }

        /** Formatted warning strings — backward-compatible accessor. */
        public List<String> warnings() {
            return warningIssues.stream().map(ValidationIssue::format).toList();
        }
    }

    private static final class Issues {
        final List<ValidationIssue> errors = new ArrayList<>();
        final List<ValidationIssue> warnings = new ArrayList<>();

        void error(String scope, Integer index, String field, String message) {
            errors.add(new ValidationIssue(scope, index, field, message));
        }

        void warn(String scope, Integer index, String field, String message) {
            warnings.add(new ValidationIssue(scope, index, field, message));
        }

        /** Strict mode promotes the finding to an error; lenient keeps a warning. */
        void strictError(boolean strict, String scope, Integer index, String field, String message) {
            if (strict) error(scope, index, field, message);
            else warn(scope, index, field, message);
        }
    }

    /** Lenient validation without asset awareness (legacy pipeline callers). */
    public ValidationResult validate(EdlDto edl) {
        return validate(edl, null, false);
    }

    /**
     * Full validation.
     *
     * @param projectAssets when non-null, asset references and trim bounds are
     *                      checked against the project's real assets
     * @param strict        editor mode — reject what the pipeline would only warn about
     */
    public ValidationResult validate(EdlDto edl, List<ProjectAsset> projectAssets, boolean strict) {
        if (edl == null) {
            return new ValidationResult(false,
                    List.of(new ValidationIssue("edl", null, null, "EDL is null")), List.of());
        }

        Issues issues = new Issues();
        Map<String, ProjectAsset> assetById = projectAssets == null ? null
                : projectAssets.stream().collect(Collectors.toMap(
                        a -> a.getId().toString(), Function.identity(), (a, b) -> a));

        validateMetadata(edl, issues);
        validateSegments(edl, issues, assetById, strict);
        validateAudioTracks(edl, issues, assetById);
        validateTextOverlays(edl, issues);
        validateWhisperWords(edl, issues, strict);
        validateTimeline(edl, issues, strict);

        boolean valid = issues.errors.isEmpty();
        if (!valid) {
            log.warn("[EdlValidator] Validation failed ({} mode) — {} errors, {} warnings",
                    strict ? "strict" : "lenient", issues.errors.size(), issues.warnings.size());
            issues.errors.forEach(e -> log.warn("[EdlValidator]   ERROR: {}", e.format()));
        }
        issues.warnings.forEach(w -> log.info("[EdlValidator]   WARN: {}", w.format()));

        return new ValidationResult(valid, List.copyOf(issues.errors), List.copyOf(issues.warnings));
    }

    private void validateMetadata(EdlDto edl, Issues issues) {
        if (edl.getVersion() == null || edl.getVersion().isBlank()) {
            issues.error("edl", null, "version", "EDL version is required");
        }

        EdlMetadata meta = edl.getMetadata();
        if (meta == null) {
            issues.error("edl", null, "metadata", "EDL metadata is required");
            return;
        }

        if (meta.getTotalDurationMs() <= 0) {
            issues.error("metadata", null, "total_duration_ms", "must be positive");
        }

        if (meta.getTotalDurationMs() > 180_000) {
            issues.warn("metadata", null, "total_duration_ms",
                    "video exceeds 3 minutes (" + meta.getTotalDurationMs() + "ms) — may not be optimal for TikTok");
        }

        if (meta.getWidth() <= 0 || meta.getHeight() <= 0) {
            issues.warn("metadata", null, "width", "width/height not set, will use defaults");
        }

        if (meta.getFps() <= 0 || meta.getFps() > 60) {
            issues.warn("metadata", null, "fps", "unusual FPS value: " + meta.getFps());
        }
    }

    private void validateSegments(EdlDto edl, Issues issues,
                                  Map<String, ProjectAsset> assetById, boolean strict) {
        if (edl.getSegments() == null || edl.getSegments().isEmpty()) {
            issues.error("edl", null, "segments", "EDL must have at least one segment");
            return;
        }

        Set<String> segmentIds = new HashSet<>();
        for (int i = 0; i < edl.getSegments().size(); i++) {
            EdlSegment seg = edl.getSegments().get(i);

            if (seg.getId() != null && !segmentIds.add(seg.getId())) {
                issues.error("segments", i, "id", "duplicate id: " + seg.getId());
            }

            if (seg.getAssetId() == null || seg.getAssetId().isBlank()) {
                issues.error("segments", i, "asset_id", "asset_id is required");
            } else if (assetById != null && !assetById.containsKey(seg.getAssetId())) {
                issues.error("segments", i, "asset_id",
                        "asset does not exist in this project: " + seg.getAssetId());
            }

            if (seg.getStartMs() < 0) {
                issues.error("segments", i, "start_ms", "cannot be negative");
            }

            if (seg.getEndMs() <= seg.getStartMs()) {
                issues.error("segments", i, "end_ms", "must be greater than start_ms");
            }

            validateSegmentTrims(seg, i, issues, assetById, strict);

            if (seg.getEffects() != null) {
                for (int j = 0; j < seg.getEffects().size(); j++) {
                    EdlEffect effect = seg.getEffects().get(j);
                    if (!effectRegistry.isValidEffect(effect.getType())) {
                        issues.strictError(strict, "segments", i, "effects[" + j + "].type",
                                "unknown effect type: " + effect.getType());
                    }
                }
            }

            if (seg.getTransition() != null
                    && !effectRegistry.isValidTransition(seg.getTransition().getType())) {
                issues.strictError(strict, "segments", i, "transition.type",
                        "unknown transition type: " + seg.getTransition().getType());
            }
        }
    }

    /**
     * Trim bounds against the real source duration — a trim outside the asset
     * renders as a frozen or black frame, so reject it at save time.
     */
    private void validateSegmentTrims(EdlSegment seg, int i, Issues issues,
                                      Map<String, ProjectAsset> assetById, boolean strict) {
        if (seg.getTrimInMs() < 0) {
            issues.error("segments", i, "trim_in_ms", "cannot be negative");
        }
        if (seg.getTrimOutMs() != null && seg.getTrimOutMs() <= seg.getTrimInMs()) {
            issues.error("segments", i, "trim_out_ms", "must be greater than trim_in_ms");
        }

        if (assetById == null || seg.getAssetId() == null) return;
        ProjectAsset asset = assetById.get(seg.getAssetId());
        if (asset == null || !"VIDEO".equals(asset.getType().name())) return;
        Double durationSeconds = asset.getDurationSeconds();
        if (durationSeconds == null || durationSeconds <= 0) return;

        int assetDurationMs = (int) (durationSeconds * 1000);
        if (seg.getTrimInMs() >= assetDurationMs) {
            issues.error("segments", i, "trim_in_ms",
                    "beyond end of source (" + seg.getTrimInMs() + "ms >= " + assetDurationMs + "ms)");
            return;
        }
        if (seg.getTrimOutMs() != null && seg.getTrimOutMs() > assetDurationMs) {
            issues.error("segments", i, "trim_out_ms",
                    "beyond end of source (" + seg.getTrimOutMs() + "ms > " + assetDurationMs + "ms)");
        }
        int playedMs = seg.getTrimOutMs() != null
                ? Math.min(seg.getTrimOutMs(), assetDurationMs) - seg.getTrimInMs()
                : assetDurationMs - seg.getTrimInMs();
        if (seg.getDurationMs() > playedMs) {
            issues.strictError(strict, "segments", i, "trim_in_ms",
                    "segment (" + seg.getDurationMs() + "ms) plays past end of trimmed source ("
                            + playedMs + "ms available)");
        }
    }

    private void validateAudioTracks(EdlDto edl, Issues issues, Map<String, ProjectAsset> assetById) {
        if (edl.getAudioTracks() == null) return;

        for (int i = 0; i < edl.getAudioTracks().size(); i++) {
            EdlAudioTrack track = edl.getAudioTracks().get(i);

            if (track.getAssetId() == null || track.getAssetId().isBlank()) {
                issues.error("audio_tracks", i, "asset_id", "asset_id is required");
            } else if (assetById != null && !assetById.containsKey(track.getAssetId())) {
                issues.error("audio_tracks", i, "asset_id",
                        "asset does not exist in this project: " + track.getAssetId());
            }

            if (track.getVolume() < 0 || track.getVolume() > 2.0) {
                issues.warn("audio_tracks", i, "volume", "unusual volume: " + track.getVolume());
            }

            if (track.getTrimOutMs() != null && track.getTrimOutMs() <= track.getTrimInMs()) {
                issues.error("audio_tracks", i, "trim_out_ms", "must be greater than trim_in_ms");
            }
        }
    }

    private void validateTextOverlays(EdlDto edl, Issues issues) {
        if (edl.getTextOverlays() == null) return;

        for (int i = 0; i < edl.getTextOverlays().size(); i++) {
            EdlTextOverlay overlay = edl.getTextOverlays().get(i);

            if (overlay.getText() == null || overlay.getText().isBlank()) {
                issues.error("text_overlays", i, "text", "text is required");
            }

            if (overlay.getEndMs() <= overlay.getStartMs()) {
                issues.error("text_overlays", i, "end_ms", "must be greater than start_ms");
            }
        }
    }

    /**
     * Whisper words are user-editable in the timeline editor (typo/timing fixes)
     * — broken timings would corrupt karaoke subtitles and music ducking.
     */
    private void validateWhisperWords(EdlDto edl, Issues issues, boolean strict) {
        List<EdlWhisperWord> words = edl.getWhisperWords();
        if (words == null || words.isEmpty()) return;

        Integer voiceEndMs = edl.getAudioTracks() == null ? null : edl.getAudioTracks().stream()
                .filter(t -> "voiceover".equals(t.getType()) && t.getEndMs() != null)
                .map(EdlAudioTrack::getEndMs)
                .max(Integer::compareTo).orElse(null);

        int prevStart = Integer.MIN_VALUE;
        int prevEnd = 0;
        for (int i = 0; i < words.size(); i++) {
            EdlWhisperWord w = words.get(i);

            if (w.getWord() == null || w.getWord().isBlank()) {
                issues.error("whisper_words", i, "word", "word text is required");
            }
            if (w.getStartMs() < 0) {
                issues.error("whisper_words", i, "start_ms", "cannot be negative");
            }
            if (w.getEndMs() <= w.getStartMs()) {
                issues.error("whisper_words", i, "end_ms", "must be greater than start_ms");
            }
            if (w.getStartMs() < prevStart) {
                issues.error("whisper_words", i, "start_ms",
                        "words must be in chronological order");
            } else if (prevEnd - w.getStartMs() > WORD_OVERLAP_TOLERANCE_MS) {
                issues.strictError(strict, "whisper_words", i, "start_ms",
                        "overlaps previous word by " + (prevEnd - w.getStartMs()) + "ms");
            }
            if (voiceEndMs != null && w.getEndMs() > voiceEndMs + 500) {
                issues.warn("whisper_words", i, "end_ms",
                        "word ends after the voiceover track (" + w.getEndMs() + "ms > " + voiceEndMs + "ms)");
            }

            prevStart = w.getStartMs();
            prevEnd = Math.max(prevEnd, w.getEndMs());
        }
    }

    /**
     * Sprawdza spojnosc timeline — gaps i nakladanie sie segmentow per layer.
     * In strict mode a primary-layer gap > 500ms (black hole in the video) and
     * same-layer overlap without a transition are rejected.
     */
    private void validateTimeline(EdlDto edl, Issues issues, boolean strict) {
        if (edl.getSegments() == null || edl.getSegments().size() < 2) return;

        Map<Integer, List<EdlSegment>> byLayer = new TreeMap<>();
        for (EdlSegment seg : edl.getSegments()) {
            byLayer.computeIfAbsent(seg.getLayer(), k -> new ArrayList<>()).add(seg);
        }

        for (Map.Entry<Integer, List<EdlSegment>> entry : byLayer.entrySet()) {
            int layer = entry.getKey();
            List<EdlSegment> layerSegments = entry.getValue();
            layerSegments.sort(Comparator.comparingInt(EdlSegment::getStartMs));

            for (int i = 1; i < layerSegments.size(); i++) {
                EdlSegment prev = layerSegments.get(i - 1);
                EdlSegment curr = layerSegments.get(i);

                if (curr.getStartMs() < prev.getEndMs()) {
                    int overlap = prev.getEndMs() - curr.getStartMs();
                    if (prev.getTransition() == null) {
                        issues.strictError(strict, "timeline", null, "layer_" + layer,
                                "segments overlap by " + overlap + "ms at " + curr.getStartMs()
                                        + "ms without transition");
                    }
                    continue;
                }

                int gap = curr.getStartMs() - prev.getEndMs();
                if (layer == 0 && gap > GAP_ERROR_MS) {
                    issues.strictError(strict, "timeline", null, "layer_" + layer,
                            "gap of " + gap + "ms between segments at " + prev.getEndMs()
                                    + "ms — would render as black/frozen frames");
                } else if (gap > GAP_WARN_MS) {
                    issues.warn("timeline", null, "layer_" + layer,
                            "gap of " + gap + "ms between segments at " + prev.getEndMs() + "ms");
                }
            }
        }
    }
}
