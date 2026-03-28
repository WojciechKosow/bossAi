package com.BossAi.bossAi.service.director;

import com.BossAi.bossAi.entity.VideoStyle;
import org.springframework.stereotype.Component;

@Component
public class EffectAssigner {

    /**
     * Przypisuje efekty do cutów uwzględniając contentType.
     */
    public void applyEffects(DirectorPlan plan, VideoStyle style, String contentType) {
        boolean isEducational = "EDUCATIONAL".equalsIgnoreCase(contentType);

        for (SceneDirection scene : plan.getScenes()) {
            for (Cut cut : scene.getCuts()) {
                cut.setEffect(resolveEffect(cut, style, isEducational, scene.getSceneIndex()));
            }
        }
    }

    /**
     * Przypisuje przejścia (transitions) między scenami.
     * Transition names match FFmpeg xfade: fade, fadewhite, fadeblack, dissolve, wipeleft.
     * "cut" = hard cut (brak przejścia).
     */
    public void applyTransitions(DirectorPlan plan, VideoStyle style, String contentType) {
        for (int i = 0; i < plan.getScenes().size(); i++) {
            SceneDirection scene = plan.getScenes().get(i);
            boolean isLast = (i == plan.getScenes().size() - 1);

            if (isLast) {
                scene.setTransitionToNext("cut");
            } else {
                scene.setTransitionToNext(resolveTransition(style, i));
            }
        }
    }

    private String resolveTransition(VideoStyle style, int sceneIndex) {
        if (style == null) return "fade";
        return switch (style) {
            case VIRAL_EDIT -> sceneIndex % 2 == 0 ? "fadewhite" : "wipeleft";
            case UGC_STYLE -> "cut";
            case HIGH_CONVERTING_AD -> "fade";
            case PRODUCT_SHOWCASE -> "dissolve";
            case STORY_MODE -> sceneIndex % 2 == 0 ? "fade" : "fadeblack";
            case CINEMATIC -> "fade";
            case LUXURY_AD -> "dissolve";
            case EDUCATIONAL -> "fade";
            default -> "fade";
        };
    }

    private EffectType resolveEffect(Cut cut, VideoStyle style, boolean isEducational, int sceneIndex) {
        String energy = cut.getEnergy();

        if (isEducational) {
            return switch (energy != null ? energy.toLowerCase() : "low") {
                case "high"   -> EffectType.ZOOM_OUT;
                case "medium" -> sceneIndex % 2 == 0 ? EffectType.PAN_LEFT : EffectType.PAN_RIGHT;
                default       -> sceneIndex % 2 == 0 ? EffectType.PAN_RIGHT : EffectType.PAN_LEFT;
            };
        }

        if (style == VideoStyle.CINEMATIC || style == VideoStyle.LUXURY_AD) {
            if ("high".equalsIgnoreCase(energy))   return EffectType.ZOOM_IN;
            if ("medium".equalsIgnoreCase(energy)) return sceneIndex % 2 == 0 ? EffectType.PAN_LEFT : EffectType.PAN_RIGHT;
            return EffectType.ZOOM_OUT;
        }

        if (style == VideoStyle.VIRAL_EDIT) {
            if ("high".equalsIgnoreCase(energy))   return EffectType.FAST_ZOOM;
            if ("medium".equalsIgnoreCase(energy)) return sceneIndex % 2 == 0 ? EffectType.ZOOM_IN : EffectType.SHAKE;
            return EffectType.ZOOM_IN;
        }

        if ("high".equalsIgnoreCase(energy))   return EffectType.FAST_ZOOM;
        if ("medium".equalsIgnoreCase(energy)) return EffectType.ZOOM_IN;
        return EffectType.NONE;
    }
}
