package com.BossAi.bossAi.request;

import com.BossAi.bossAi.entity.VideoStyle;
import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.UUID;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class TikTokAdRequest {

    /**
     * Optional — when null/blank and customMediaAssetIds are provided,
     * the pipeline auto-applies PROBLEM_PAYOFF DNA and generates narration
     * from vision analysis of the uploaded assets.
     */
    @Size(max = 2000, message = "Prompt max 2000 chars")
    private String prompt;

    private List<UUID> assetIds;

    @JsonIgnore
    private MultipartFile musicFile;

    @Enumerated(EnumType.STRING)
    private VideoStyle style;

    /**
     * Whether the pipeline should try to reuse previously generated assets
     * (images, videos) thematically matched to the new prompt.
     * Defaults to true — saves credits. Available for plans > BASIC.
     */
    private boolean reuseAssets = true;

    /**
     * TEST ONLY — Forces 100% asset reuse with zero new generation.
     * When true, bypasses GPT matching, plan checks, and minimum thresholds.
     * Assigns existing user assets directly to scenes (round-robin).
     * No fal.ai API calls are made — saves money during testing.
     * DO NOT use in production.
     */
    private boolean forceReuseForTesting = true;

    /**
     * Custom visual assets (images + videos) uploaded by user, ordered by user.
     * Mapped to scenes by orderIndex. Requires PRO+ plan.
     */
    private List<UUID> customMediaAssetIds;

    /**
     * Custom TTS voice-over assets uploaded by user, ordered by user.
     * When provided, pipeline skips AI TTS generation entirely.
     * Audio clips are concatenated in orderIndex order and sent to WhisperX.
     * Requires PRO+ plan.
     */
    private List<UUID> customTtsAssetIds;

    /**
     * If true, GPT decides the optimal order for user-provided custom media assets.
     * If false (default), assets are used in the order defined by their orderIndex.
     */
    private boolean useGptOrdering = false;

    /**
     * Reuse an existing music asset by its ID instead of uploading a new file.
     * The asset must be owned by the user and be of type MUSIC.
     * Takes precedence over musicFile if both are provided.
     */
    private UUID musicAssetId;

    /**
     * Explicit scene → asset mapping (Phase 2 from CLAUDE.md).
     *
     * Each element binds a sceneIndex to an assetId. When the list is non-empty,
     * the pipeline arranges customMediaAssets in scene order according to this map
     * — skipping the default sort by orderIndex (which represents upload order,
     * not the user's intent).
     *
     * Rules:
     *   - sceneIndex in [0, customMediaAssetIds.size())
     *   - assetId must belong to customMediaAssetIds
     *   - empty slots (scenes without an entry) are filled with the remaining assets
     *     in orderIndex order
     *
     * The POST /api/generations/assign-assets endpoint sets this field before
     * passing the request to generateTikTokAd.
     */
    private List<SceneAssignment> sceneAssignments;

    /**
     * Toucan DNA preset ID — drives beat structure, effects, color arc, text overlays.
     * E.g. "PROBLEM_PAYOFF". Null = no preset (legacy behaviour).
     * Requires feature flag dna.presets.enabled=true.
     */
    private String dnaPreset;

    /**
     * When true, pipeline adds an animated GIF overlay (subscribe/follow sticker)
     * on the last scene via Giphy Stickers API.
     * Requires giphy.api-key to be configured.
     */
    private boolean gifOverlaysEnabled = false;

    /**
     * Overlay images to be placed ON TOP of the main video at semantically
     * appropriate moments. These assets are NOT part of the main scene timeline
     * and do NOT affect scene count.
     *
     * Examples: brand logos, Discord/Twitter/Instagram icons, screenshots,
     * to-do lists, product shots, CTA graphics.
     *
     * The pipeline uses GPT Vision to describe each image and then matches it
     * to the narration transcript to decide when and where to show it.
     *
     * Requires PRO+ plan.
     */
    private List<UUID> overlayAssetIds;
}
