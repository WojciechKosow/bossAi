package com.BossAi.bossAi.response;

import com.BossAi.bossAi.entity.AssetType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.UUID;

/**
 * Result of POST /api/generations/analyze-prompt — a scene proposal after a
 * dry-run of UserIntentParser + ScriptStep. The client (or frontend) can use it
 * to show the user the script and ask them to assign assets to scenes
 * (Phase 2 from CLAUDE.md).
 *
 * Does NOT create a Generation or VideoProject — no state is persisted.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PromptAnalysisResponse {

    private String contentType;
    private String hook;
    private String callToAction;
    private int totalDurationMs;

    private List<ProposedScene> scenes;
    private UserIntentSummary userIntent;
    private List<AssetMeta> availableAssets;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ProposedScene {
        private int index;
        private String imagePrompt;
        private String motionPrompt;
        private String subtitleText;
        private int durationMs;

        /** From UserEditIntent.placement (if the user described this scene). */
        private String suggestedRole;
        private String suggestedMood;
        private String sceneDirection;

        /** Pre-suggested asset (the asset the user proposed for this scene in the prompt). */
        private UUID suggestedAssetId;

        /** Multi-layer composition (Phase 1) — if the user described layers. */
        private List<LayerPreview> layers;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class LayerPreview {
        private int layerIndex;
        private String role;
        private String source;
        private UUID assetId;
        private String generationPrompt;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class UserIntentSummary {
        private String overallGoal;
        private String pacingPreference;
        private String editingStyle;
        private List<String> structureHints;
        private boolean userControlsOrder;
        private boolean hasExplicitInstructions;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class AssetMeta {
        private UUID id;
        private AssetType type;
        private String originalFilename;
        private Integer orderIndex;
        private String description;
    }
}
