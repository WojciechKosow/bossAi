package com.BossAi.bossAi.service.edl;

import com.BossAi.bossAi.dto.edl.EdlEffect;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Registry efektow dostepnych w Remotion renderer.
 *
 * Mapuje nazwy efektow na domyslne parametry.
 * Uzywany przez EdlGeneratorService do walidacji i uzupelniania parametrow.
 */
@Component
public class EffectRegistry {

    // ─── Efekty wizualne ───────────────────────────────────────────────

    public static final String ZOOM_IN = "zoom_in";
    public static final String ZOOM_OUT = "zoom_out";
    public static final String FAST_ZOOM = "fast_zoom";
    public static final String PAN_LEFT = "pan_left";
    public static final String PAN_RIGHT = "pan_right";
    public static final String PAN_UP = "pan_up";
    public static final String PAN_DOWN = "pan_down";
    public static final String SHAKE = "shake";
    public static final String SLOW_MOTION = "slow_motion";
    public static final String SPEED_RAMP = "speed_ramp";
    public static final String ZOOM_PULSE = "zoom_pulse";
    public static final String KEN_BURNS = "ken_burns";
    public static final String GLITCH = "glitch";
    public static final String FLASH = "flash";

    // ─── Przejscia ────────────────────────────────────────────────────

    public static final String TRANSITION_CUT = "cut";
    public static final String TRANSITION_FADE = "fade";
    public static final String TRANSITION_FADE_WHITE = "fade_white";
    public static final String TRANSITION_FADE_BLACK = "fade_black";
    public static final String TRANSITION_DISSOLVE = "dissolve";
    public static final String TRANSITION_WIPE_LEFT = "wipe_left";
    public static final String TRANSITION_WIPE_RIGHT = "wipe_right";
    public static final String TRANSITION_SLIDE_LEFT = "slide_left";
    public static final String TRANSITION_SLIDE_RIGHT = "slide_right";

    // ─── Animacje tekstu ──────────────────────────────────────────────

    public static final String TEXT_ANIM_FADE_IN = "fade_in";
    public static final String TEXT_ANIM_SLIDE_UP = "slide_up";
    public static final String TEXT_ANIM_TYPEWRITER = "typewriter";
    public static final String TEXT_ANIM_BOUNCE = "bounce";
    public static final String TEXT_ANIM_WORD_BY_WORD = "word_by_word";
    public static final String TEXT_ANIM_KARAOKE = "karaoke";

    private static final Map<String, Map<String, Object>> EFFECT_DEFAULTS = Map.ofEntries(
            Map.entry(ZOOM_IN, Map.of("scale_from", 1.0, "scale_to", 1.3, "easing", "easeInOut")),
            Map.entry(ZOOM_OUT, Map.of("scale_from", 1.3, "scale_to", 1.0, "easing", "easeInOut")),
            Map.entry(FAST_ZOOM, Map.of("scale_from", 1.0, "scale_to", 1.5, "easing", "easeIn", "duration_ms", 200)),
            Map.entry(PAN_LEFT, Map.of("direction", "left", "distance_percent", 15, "easing", "linear")),
            Map.entry(PAN_RIGHT, Map.of("direction", "right", "distance_percent", 15, "easing", "linear")),
            Map.entry(PAN_UP, Map.of("direction", "up", "distance_percent", 10, "easing", "linear")),
            Map.entry(PAN_DOWN, Map.of("direction", "down", "distance_percent", 10, "easing", "linear")),
            Map.entry(SHAKE, Map.of("amplitude", 5, "frequency", 15)),
            Map.entry(SLOW_MOTION, Map.of("speed", 0.5)),
            Map.entry(SPEED_RAMP, Map.of("speed_from", 1.0, "speed_to", 2.0, "easing", "easeInOut")),
            Map.entry(ZOOM_PULSE, Map.of("scale", 1.05, "frequency_bpm", 120)),
            Map.entry(KEN_BURNS, Map.of("scale_from", 1.0, "scale_to", 1.2, "pan_direction", "left")),
            Map.entry(GLITCH, Map.of("intensity", 0.5, "frequency", 3)),
            Map.entry(FLASH, Map.of("opacity", 0.8, "duration_ms", 100))
    );

    private static final Map<String, Map<String, Object>> TRANSITION_DEFAULTS = Map.of(
            TRANSITION_CUT, Map.of(),
            TRANSITION_FADE, Map.of("duration_ms", 300),
            TRANSITION_FADE_WHITE, Map.of("duration_ms", 300),
            TRANSITION_FADE_BLACK, Map.of("duration_ms", 400),
            TRANSITION_DISSOLVE, Map.of("duration_ms", 500),
            TRANSITION_WIPE_LEFT, Map.of("duration_ms", 400),
            TRANSITION_WIPE_RIGHT, Map.of("duration_ms", 400),
            TRANSITION_SLIDE_LEFT, Map.of("duration_ms", 350),
            TRANSITION_SLIDE_RIGHT, Map.of("duration_ms", 350)
    );

    /**
     * Zwraca domyslne parametry efektu.
     * Null jesli efekt nie istnieje w rejestrze.
     */
    public Map<String, Object> getEffectDefaults(String effectType) {
        return EFFECT_DEFAULTS.get(effectType);
    }

    /**
     * Zwraca domyslne parametry przejscia.
     * Null jesli przejscie nie istnieje w rejestrze.
     */
    public Map<String, Object> getTransitionDefaults(String transitionType) {
        return TRANSITION_DEFAULTS.get(transitionType);
    }

    public boolean isValidEffect(String effectType) {
        return EFFECT_DEFAULTS.containsKey(effectType);
    }

    public boolean isValidTransition(String transitionType) {
        return TRANSITION_DEFAULTS.containsKey(transitionType);
    }

    /**
     * Tworzy EdlEffect z domyslnymi parametrami, nadpisanymi przez custom params.
     */
    public EdlEffect createEffect(String type, double intensity, Map<String, Object> customParams) {
        Map<String, Object> defaults = EFFECT_DEFAULTS.getOrDefault(type, Map.of());
        java.util.Map<String, Object> merged = new java.util.HashMap<>(defaults);
        if (customParams != null) {
            merged.putAll(customParams);
        }

        return EdlEffect.builder()
                .type(type)
                .intensity(intensity)
                .params(merged)
                .build();
    }
}
