package com.BossAi.bossAi.request;

import com.BossAi.bossAi.entity.VideoStyle;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.UUID;

/**
 * Body POST /api/generations/analyze-prompt.
 *
 * Dry-run prompt analysis: the backend runs UserIntentParser + ScriptStep
 * without creating a Generation/VideoProject and returns the proposed scene
 * breakdown, so the user can view/approve the script before generation
 * and assign assets to specific scenes (Phase 2 from CLAUDE.md).
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class PromptAnalysisRequest {

    @NotBlank(message = "Prompt cannot be blank")
    @Size(min = 10, max = 2000, message = "Prompt has to be from 10 to 2000 chars long")
    private String prompt;

    private VideoStyle style;

    /**
     * Custom media assets (images/videos) — used to determine the scene count
     * (CLAUDE.md: scene count == media.size()) and to enrich the prompt with
     * AssetProfiles. Optional — without them the preview uses AI-generated scenes.
     */
    private List<UUID> customMediaAssetIds;

    /**
     * Whether to run the AssetAnalyzer (vision/text profile per asset). Slower
     * (extra GPT calls), but gives a richer preview. Defaults to false for
     * speed — the frontend can toggle it on in "advanced preview" mode.
     */
    private boolean analyzeAssets = false;
}
