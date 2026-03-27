package com.BossAi.bossAi.service.director;

import com.BossAi.bossAi.entity.VideoStyle;
import org.springframework.stereotype.Component;

@Component
public class EffectAssigner {

    /**
     * Przypisuje efekty do cutów uwzględniając contentType.
     *
     * EDUCATIONAL → Ken Burns (PAN_LEFT / PAN_RIGHT / ZOOM_OUT) zamiast agresywnych
     * zoom-in/fast-zoom. Stabilne efekty pozwalają widzowi czytać tekst overlay.
     *
     * Pozostałe typy → klasyczny FAST_ZOOM / ZOOM_IN oparty na energy.
     *
     * @param contentType wartość z ScriptResult.contentType() (może być null)
     */
    public void applyEffects(DirectorPlan plan, VideoStyle style, String contentType) {
        boolean isEducational = "EDUCATIONAL".equalsIgnoreCase(contentType);

        for (SceneDirection scene : plan.getScenes()) {
            for (Cut cut : scene.getCuts()) {
                cut.setEffect(resolveEffect(cut, style, isEducational, scene.getSceneIndex()));
            }
        }
    }

    private EffectType resolveEffect(Cut cut, VideoStyle style, boolean isEducational, int sceneIndex) {
        String energy = cut.getEnergy();

        if (isEducational) {
            // Ken Burns rotuje per scena — alternating daje poczucie ruchu bez chaosu
            return switch (energy != null ? energy.toLowerCase() : "low") {
                case "high"   -> EffectType.ZOOM_OUT;                                          // hook
                case "medium" -> sceneIndex % 2 == 0 ? EffectType.PAN_LEFT : EffectType.PAN_RIGHT;
                default       -> sceneIndex % 2 == 0 ? EffectType.PAN_RIGHT : EffectType.PAN_LEFT;
            };
        }

        if ("high".equalsIgnoreCase(energy))   return EffectType.FAST_ZOOM;
        if ("medium".equalsIgnoreCase(energy)) return EffectType.ZOOM_IN;
        return EffectType.NONE;
    }
}
