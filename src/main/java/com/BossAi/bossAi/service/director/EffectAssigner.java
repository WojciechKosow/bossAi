package com.BossAi.bossAi.service.director;

import com.BossAi.bossAi.entity.VideoStyle;
import org.springframework.stereotype.Component;

@Component
public class EffectAssigner {
    public void applyEffects(DirectorPlan plan, VideoStyle style) {

        for (SceneDirection scene : plan.getScenes()) {
            for (Cut cut : scene.getCuts()) {

                cut.setEffect(resolveEffect(cut, style));
            }
        }
    }

    private EffectType resolveEffect(Cut cut, VideoStyle style) {

        String energy = cut.getEnergy();

        if ("high".equalsIgnoreCase(energy)) {
            return EffectType.FAST_ZOOM;
        }

        if ("medium".equalsIgnoreCase(energy)) {
            return EffectType.ZOOM_IN;
        }

        return EffectType.NONE;
    }
}
