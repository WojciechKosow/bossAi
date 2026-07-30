package com.BossAi.bossAi.service.generation;

import com.BossAi.bossAi.config.properties.FalAiProperties;
import com.BossAi.bossAi.entity.PlanType;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * ModelSelector — selects the AI model based on the user's plan.
 *
 * PHASE 1 BUGFIX — video models:
 *
 *   Kling image-to-video requires a DIFFERENT endpoint than text-to-video.
 *   The earlier code used text-to-video endpoints, which meant the image
 *   was ignored and the generator produced random clips.
 *
 *   Poprawne endpointy fal.ai dla Kling image-to-video:
 *     Standard: fal-ai/kling-video/v1/standard/image-to-video
 *     Pro:      fal-ai/kling-video/v1.6/pro/image-to-video
 *
 *   The free tier uses LTX Video — it supports image_url, cheaper, slower.
 *
 * Tier image models:
 *   FREE/TRIAL/STARTER → fal-ai/flux/schnell   ($0.039/img)
 *   BASIC              → fal-ai/flux/dev        ($0.08/img)
 *   PRO/CREATOR        → fal-ai/flux-pro/v1.1   ($0.15/img)
 *
 * Tier video models (image-to-video):
 *   FREE/TRIAL/STARTER → fal-ai/ltx-video                            ($0.01/s)
 *   BASIC              → fal-ai/kling-video/v1/standard/image-to-video ($0.07/s)
 *   PRO/CREATOR        → fal-ai/kling-video/v1.6/pro/image-to-video   ($0.14/s)
 *
 * UWAGA: Endpointy video hardkodowane tutaj (nie w FalAiProperties) bo
 * zmiana image-to-video → text-to-video to zmiana semantyki, nie konfiguracji.
 * If you want to move this to properties — use separate keys:
 *   fal-ai.model.video.free-image-to-video, .standard-image-to-video itd.
 */
@Component
@RequiredArgsConstructor
public class ModelSelector {

    private final FalAiProperties falAiProperties;

    // Hardcoded image-to-video endpoints (correct Kling API paths)
    private static final String VIDEO_MODEL_FREE     = "fal-ai/ltx-video";
    private static final String VIDEO_MODEL_STANDARD = "fal-ai/kling-video/v1/standard/image-to-video";
    private static final String VIDEO_MODEL_PRO      = "fal-ai/kling-video/v1.6/pro/image-to-video";

    /**
     * Returns the image-generation model identifier for a given plan.
     */
    public String imageModel(PlanType planType) {
        return switch (planType) {
            case PRO, CREATOR -> falAiProperties.getModel().getImage().getPro();
            case BASIC        -> falAiProperties.getModel().getImage().getStandard();
            default           -> falAiProperties.getModel().getImage().getFree();
        };
    }

    /**
     * Returns the video-generation (image-to-video) model identifier for a given plan.
     *
     * IMPORTANT: The returned endpoints must be image-to-video, not text-to-video.
     * FalAiService.buildVideoRequestBody() picks the body structure based on
     * rozpoznanego modelu (isKlingModel, isLtxModel itd.).
     */
    public String videoModel(PlanType planType) {
        return switch (planType) {
            case PRO, CREATOR -> VIDEO_MODEL_PRO;
            case BASIC        -> VIDEO_MODEL_STANDARD;
            default           -> VIDEO_MODEL_FREE;
        };
    }
}