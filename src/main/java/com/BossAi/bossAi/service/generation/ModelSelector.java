package com.BossAi.bossAi.service.generation;

import com.BossAi.bossAi.config.properties.FalAiProperties;
import com.BossAi.bossAi.entity.PlanType;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * ModelSelector — wybiera model AI na podstawie planu użytkownika.
 *
 * FAZA 1 BUGFIX — video modele:
 *
 *   Kling image-to-video wymaga INNEGO endpointu niż text-to-video.
 *   Wcześniejszy kod używał endpointów text-to-video, przez co obraz
 *   był ignorowany i generator produkował losowe klipy.
 *
 *   Poprawne endpointy fal.ai dla Kling image-to-video:
 *     Standard: fal-ai/kling-video/v1/standard/image-to-video
 *     Pro:      fal-ai/kling-video/v1.6/pro/image-to-video
 *
 *   Free tier używa LTX Video — obsługuje image_url, tańszy, wolniejszy.
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
 * Jeśli chcesz dać to do properties — użyj osobnych kluczy:
 *   fal-ai.model.video.free-image-to-video, .standard-image-to-video itd.
 */
@Component
@RequiredArgsConstructor
public class ModelSelector {

    private final FalAiProperties falAiProperties;

    // Hardkodowane endpointy image-to-video (poprawne ścieżki Kling API)
    private static final String VIDEO_MODEL_FREE     = "fal-ai/ltx-video";
    private static final String VIDEO_MODEL_STANDARD = "fal-ai/kling-video/v1/standard/image-to-video";
    private static final String VIDEO_MODEL_PRO      = "fal-ai/kling-video/v1.6/pro/image-to-video";

    /**
     * Zwraca identyfikator modelu image generation dla danego planu.
     */
    public String imageModel(PlanType planType) {
        return switch (planType) {
            case PRO, CREATOR -> falAiProperties.getModel().getImage().getPro();
            case BASIC        -> falAiProperties.getModel().getImage().getStandard();
            default           -> falAiProperties.getModel().getImage().getFree();
        };
    }

    /**
     * Zwraca identyfikator modelu video generation (image-to-video) dla danego planu.
     *
     * WAŻNE: Zwracane endpointy muszą być image-to-video, nie text-to-video.
     * FalAiService.buildVideoRequestBody() dobiera strukturę body na podstawie
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