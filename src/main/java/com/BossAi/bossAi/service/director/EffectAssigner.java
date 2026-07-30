package com.BossAi.bossAi.service.director;

import com.BossAi.bossAi.entity.VideoStyle;
import com.BossAi.bossAi.service.audio.AudioAnalysisResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Assigns effects and transitions to cuts.
 *
 * 3 TikTok effects:
 *   - SMASH_ZOOM  — scene 0 (hook), snap zoom 1.0→1.6 over 8 frames
 *   - ZOOM_IN     — even scenes + the last one, smooth zoom 1.0→1.20
 *   - WHIP_PAN    — odd scenes, horizontal pan with motion blur
 *
 * Transition between scenes: fade_white (scene 0→1) + fade (the rest).
 */
@Slf4j
@Component
public class EffectAssigner {

    // =========================================================================
    // PUBLIC API
    // =========================================================================

    public void applyEffects(DirectorPlan plan, VideoStyle style, String contentType) {
        applyEffects(plan, style, contentType, null);
    }

    public void applyEffects(DirectorPlan plan, VideoStyle style, String contentType,
                             AudioAnalysisResponse audioAnalysis) {
        List<SceneDirection> scenes = plan.getScenes();
        int lastIdx = scenes.size() - 1;

        for (int s = 0; s < scenes.size(); s++) {
            EffectType effect = pickEffectForScene(s, lastIdx, style);
            for (Cut cut : scenes.get(s).getCuts()) {
                cut.setEffect(effect);
            }
        }

        log.info("[EffectAssigner] 3-effect TikTok assignment — {} scenes, style: {}", scenes.size(), style);
    }

    public void applyTransitions(DirectorPlan plan, VideoStyle style, String contentType) {
        applyTransitions(plan, style, contentType, null);
    }

    public void applyTransitions(DirectorPlan plan, VideoStyle style, String contentType,
                                 AudioAnalysisResponse audioAnalysis) {
        List<SceneDirection> scenes = plan.getScenes();

        for (int i = 0; i < scenes.size(); i++) {
            SceneDirection scene = scenes.get(i);
            if (i == scenes.size() - 1) {
                scene.setTransitionToNext("cut");
                continue;
            }
            // After the hook (scene 0): a white flash — grabs attention. Then: fade.
            scene.setTransitionToNext(i == 0 ? "fade_white" : "fade");
        }
    }

    // =========================================================================
    // EFFECT SELECTION
    // =========================================================================

    private EffectType pickEffectForScene(int sceneIndex, int lastIndex, VideoStyle style) {
        // Cinematic/Luxury: always a smooth zoom
        if (style == VideoStyle.CINEMATIC || style == VideoStyle.LUXURY_AD) {
            return EffectType.ZOOM_IN;
        }
        // Hook (scene 0): snap zoom
        if (sceneIndex == 0) return EffectType.SMASH_ZOOM;
        // Last scene (CTA): stable zoom
        if (sceneIndex == lastIndex) return EffectType.ZOOM_IN;
        // Alternation: even → ZOOM_IN, odd → WHIP_PAN
        return sceneIndex % 2 == 0 ? EffectType.ZOOM_IN : EffectType.WHIP_PAN;
    }
}
