package com.BossAi.bossAi.service.generation;

import com.BossAi.bossAi.config.properties.FalAiProperties;
import com.BossAi.bossAi.entity.PlanType;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * ModelSelector — wybiera model AI na podstawie planu użytkownika.
 *
 * To jest jedyne miejsce w kodzie gdzie mapujemy PlanType → model string.
 * Zmiana modelu dla danego planu = zmiana w jednym miejscu.
 *
 * Tier image models:
 *   FREE/TRIAL/STARTER → flux/schnell   ($0.039/img — draft quality)
 *   BASIC              → flux/dev       ($0.08/img  — standard quality)
 *   PRO/CREATOR        → flux-pro/v1.1  ($0.15/img  — premium quality)
 *
 * Tier video models:
 *   FREE/TRIAL/STARTER → ltx-video      ($0.01/s   — storyboard quality)
 *   BASIC              → kling-video/v1/standard ($0.07/s)
 *   PRO/CREATOR        → kling-video/v1.6/pro    ($0.14/s)
 */
@Component
@RequiredArgsConstructor
public class ModelSelector {

    private final FalAiProperties falAiProperties;

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
     * Zwraca identyfikator modelu video generation dla danego planu.
     */
    public String videoModel(PlanType planType) {
        return switch (planType) {
            case PRO, CREATOR -> falAiProperties.getModel().getVideo().getPro();
            case BASIC        -> falAiProperties.getModel().getVideo().getStandard();
            default           -> falAiProperties.getModel().getVideo().getFree();
        };
    }
}