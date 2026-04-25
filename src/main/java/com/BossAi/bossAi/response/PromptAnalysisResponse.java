package com.BossAi.bossAi.response;

import com.BossAi.bossAi.entity.AssetType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.UUID;

/**
 * Wynik POST /api/generations/analyze-prompt — propozycja scen po dry-run
 * UserIntentParser + ScriptStep. Klient (lub frontend) może na tej podstawie
 * pokazać userowi scenariusz i poprosić o przypisanie assetów do scen
 * (Phase 2 z CLAUDE.md).
 *
 * NIE tworzy Generation ani VideoProject — żaden stan nie jest persystowany.
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

        /** Z UserEditIntent.placement (jeśli user opisał tę scenę). */
        private String suggestedRole;
        private String suggestedMood;
        private String sceneDirection;

        /** Pre-suggested asset (asset, którego user zaproponował dla tej sceny w prompcie). */
        private UUID suggestedAssetId;

        /** Multi-layer composition (Phase 1) — jeśli user opisał warstwy. */
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
