package com.BossAi.bossAi.service.director.composition;

import com.BossAi.bossAi.entity.AssetSource;
import com.BossAi.bossAi.entity.AssetStatus;
import com.BossAi.bossAi.entity.AssetType;
import com.BossAi.bossAi.entity.ProjectAsset;
import com.BossAi.bossAi.service.OpenAiService;
import com.BossAi.bossAi.service.director.AssetProfile;
import com.BossAi.bossAi.service.director.JustifiedCut;
import com.BossAi.bossAi.service.director.NarrationAnalysis;
import com.BossAi.bossAi.service.dna.DnaPreset;
import com.BossAi.bossAi.service.dna.DnaPresetService;
import com.BossAi.bossAi.service.generation.GenerationContext;
import com.BossAi.bossAi.service.generation.context.SceneAsset;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * The composition gate is data-driven now: any preset that declares
 * composition_rules participates — proven here with BEFORE_AFTER, which the
 * old hardcoded check (`preset != PROBLEM_PAYOFF → return`) used to skip.
 */
class AutonomousCompositionDeciderTest {

    private OpenAiService openAiService;
    private AutonomousCompositionDecider decider;

    @BeforeEach
    void setUp() {
        openAiService = mock(OpenAiService.class);
        ObjectMapper mapper = new ObjectMapper();
        decider = new AutonomousCompositionDecider(openAiService, mapper,
                new DnaPresetService(mapper));
    }

    // ─── fixtures ────────────────────────────────────────────────────────

    private ProjectAsset videoAsset() {
        return ProjectAsset.builder()
                .id(UUID.randomUUID()).type(AssetType.VIDEO)
                .source(AssetSource.USER_UPLOAD).status(AssetStatus.READY)
                .build();
    }

    private AssetProfile profile(int index, String role, boolean canBeBackground,
                                 double visualWeight) {
        return AssetProfile.builder()
                .assetId(UUID.randomUUID()).index(index).assetType("VIDEO")
                .visualDescription("asset " + index + " (" + role + ")")
                .suggestedRole(role).visualWeight(visualWeight)
                .canBeBackground(canBeBackground)
                .tags("testimonial".equals(role) ? List.of("person") : List.of())
                .build();
    }

    /**
     * Context shaped to fire TalkingHeadBg: a talking head in a mid-video beat
     * with a b-roll asset available as background.
     */
    private GenerationContext compositionReadyContext(DnaPreset preset) {
        GenerationContext context = GenerationContext.builder().build();
        context.setDnaPreset(preset);
        context.setAssetProfiles(List.of(
                profile(0, "testimonial", false, 0.9),
                profile(1, "b-roll", true, 0.2)));
        context.setNarrationAnalysis(NarrationAnalysis.builder()
                .segments(List.of(NarrationAnalysis.NarrationSegment.builder()
                        .type("point").energy(0.6).importance(0.6)
                        .text("middle of the story").build()))
                .build());
        // timeline spans 10s (total = last cut end); first cut starts at 40%
        // → beat C in both shipped styles (PP: 26.7–50%, BA: 30–46.7%)
        context.setJustifiedCuts(List.of(
                JustifiedCut.builder()
                        .startMs(4000).endMs(6000)
                        .classification(JustifiedCut.CutClassification.SOFT)
                        .primaryReason(JustifiedCut.CutReason.SENTENCE_END_PAUSE)
                        .assignedAssetIndex(0)
                        .build(),
                JustifiedCut.builder()
                        .startMs(6000).endMs(10_000)
                        .classification(JustifiedCut.CutClassification.SOFT)
                        .primaryReason(JustifiedCut.CutReason.SENTENCE_END_PAUSE)
                        .assignedAssetIndex(1)
                        .build()));
        context.setScenes(new ArrayList<>(List.of(
                SceneAsset.builder().build(),
                SceneAsset.builder().build())));
        return context;
    }

    // ─── tests ───────────────────────────────────────────────────────────

    @Test
    void beforeAfterPresetParticipatesInComposition() {
        GenerationContext context = compositionReadyContext(DnaPreset.BEFORE_AFTER);
        // GPT verification fails → decider falls back to accepting candidates
        when(openAiService.generateDirectorPlan(anyString()))
                .thenThrow(new RuntimeException("offline test"));

        decider.decide(context, List.of(videoAsset(), videoAsset()));

        assertFalse(context.getScenes().get(0).getLayerAssetIds().isEmpty(),
                "BEFORE_AFTER declares composition_rules — the old PROBLEM_PAYOFF "
                        + "hardcode would have skipped it");
        // background layer (pip) → directive index 0
        assertTrue(context.getScenes().get(0).getLayerAssetIds().containsKey(0));
    }

    @Test
    void problemPayoffStillParticipates() {
        GenerationContext context = compositionReadyContext(DnaPreset.PROBLEM_PAYOFF);
        when(openAiService.generateDirectorPlan(anyString()))
                .thenThrow(new RuntimeException("offline test"));

        decider.decide(context, List.of(videoAsset(), videoAsset()));

        assertFalse(context.getScenes().get(0).getLayerAssetIds().isEmpty());
    }

    @Test
    void presetWithoutConfigSkipsCompositionGracefully() {
        GenerationContext context = compositionReadyContext(DnaPreset.TUTORIAL);

        decider.decide(context, List.of(videoAsset(), videoAsset()));

        assertTrue(context.getScenes().get(0).getLayerAssetIds().isEmpty(),
                "unimplemented preset must skip composition, not crash");
        verify(openAiService, never()).generateDirectorPlan(anyString());
    }

    @Test
    void noPresetSkipsComposition() {
        GenerationContext context = compositionReadyContext(null);

        decider.decide(context, List.of(videoAsset(), videoAsset()));

        assertTrue(context.getScenes().get(0).getLayerAssetIds().isEmpty());
        verify(openAiService, never()).generateDirectorPlan(anyString());
    }
}
