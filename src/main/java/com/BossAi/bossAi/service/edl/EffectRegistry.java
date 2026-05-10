package com.BossAi.bossAi.service.edl;

import com.BossAi.bossAi.dto.edl.EdlEffect;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Set;

/**
 * Registry efektow dostepnych w Remotion renderer.
 *
 * Mapuje nazwy efektow na domyslne parametry.
 * Uzywany przez EdlGeneratorService do walidacji i uzupelniania parametrow.
 *
 * REMOTION_EFFECTS — efekty aktualnie zaimplementowane w Remotion renderer.
 * Nowe efekty TikTok-native sa w pelnym rejestrze, ale mapowane na Remotion-safe
 * odpowiedniki az do wdrozenia na remotion-branch.
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
    public static final String BOUNCE = "bounce";
    public static final String DRIFT = "drift";
    public static final String ZOOM_IN_OFFSET = "zoom_in_offset";

    // ─── Efekty TikTok-native (nowe) ─────────────────────────────────

    /** Snap zoom ekstremalny — stop-scroll w <100ms, używany wyłącznie na hooku */
    public static final String SMASH_ZOOM = "smash_zoom";
    /** Gaussian blur na końcu segmentu przed cięciem — płynność między klipami */
    public static final String BLUR_TRANSITION = "blur_transition";
    /** Skok jasności +0.4 przez ~120ms — punch na bicie lub reveal */
    public static final String BRIGHTNESS_BURST = "brightness_burst";
    /** Ekstremalny pan 40-60% z motion blur — sygnatura przejścia sceny */
    public static final String WHIP_PAN = "whip_pan";
    /** Skok saturacji +0.3 — reveal produktu, CTA */
    public static final String COLOR_POP = "color_pop";
    /** Szybkie wzmocnienie vignette na dropie */
    public static final String VIGNETTE_PULSE = "vignette_pulse";

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

    /**
     * Efekty aktualnie zaimplementowane w Remotion renderer.
     * Aby dodać nowy efekt: zaimplementuj komponent w remotion-branch, dodaj tu.
     */
    private static final Set<String> REMOTION_EFFECTS = Set.of(
            ZOOM_IN, ZOOM_OUT, FAST_ZOOM,
            PAN_LEFT, PAN_RIGHT, PAN_UP, PAN_DOWN,
            SHAKE, SLOW_MOTION, SPEED_RAMP, ZOOM_PULSE,
            KEN_BURNS, GLITCH, FLASH, BOUNCE, DRIFT, ZOOM_IN_OFFSET,
            // TikTok-native — wdrożone w remotion-branch (commit 792fdc6)
            SMASH_ZOOM, BLUR_TRANSITION, BRIGHTNESS_BURST,
            WHIP_PAN, COLOR_POP, VIGNETTE_PULSE
    );

    /**
     * Fallback mapping dla efektów jeszcze nie zaimplementowanych w Remotion.
     * Aktualnie pusty — wszystkie efekty są obsługiwane.
     */
    private static final Map<String, String> REMOTION_FALLBACKS = Map.of();

    private static final Map<String, Map<String, Object>> EFFECT_DEFAULTS = Map.ofEntries(
            Map.entry(ZOOM_IN, Map.of("scale_from", 1.0, "scale_to", 1.3, "easing", "easeInOut")),
            Map.entry(ZOOM_OUT, Map.of("scale_from", 1.3, "scale_to", 1.0, "easing", "easeInOut")),
            // easeOut = szybki start, wolny koniec → snap/punch feel zamiast opóźnionego easeIn
            Map.entry(FAST_ZOOM, Map.of("scale_from", 1.0, "scale_to", 1.6, "easing", "easeOut", "duration_ms", 150)),
            Map.entry(PAN_LEFT, Map.of("direction", "left", "distance_percent", 13, "easing", "linear")),
            Map.entry(PAN_RIGHT, Map.of("direction", "right", "distance_percent", 13, "easing", "linear")),
            Map.entry(PAN_UP, Map.of("direction", "up", "distance_percent", 10, "easing", "linear")),
            Map.entry(PAN_DOWN, Map.of("direction", "down", "distance_percent", 10, "easing", "linear")),
            Map.entry(SHAKE, Map.of("amplitude", 10, "frequency", 20)),
            Map.entry(SLOW_MOTION, Map.of("speed", 0.5)),
            Map.entry(SPEED_RAMP, Map.of("speed_from", 1.0, "speed_to", 2.0, "easing", "easeInOut")),
            Map.entry(ZOOM_PULSE, Map.of("scale", 1.08, "frequency_bpm", 120)),
            Map.entry(KEN_BURNS, Map.of("scale_from", 1.0, "scale_to", 1.25, "pan_direction", "left")),
            Map.entry(GLITCH, Map.of("intensity", 0.5, "frequency", 3)),
            Map.entry(FLASH, Map.of("opacity", 0.85, "duration_ms", 80)),
            Map.entry(BOUNCE, Map.of("scale_peak", 1.15, "easing", "easeOut")),
            Map.entry(DRIFT, Map.of("direction", "diagonal", "distance_percent", 13, "easing", "linear")),
            Map.entry(ZOOM_IN_OFFSET, Map.of("scale_from", 1.0, "scale_to", 1.3, "offset_x", 0.3, "offset_y", 0.4, "easing", "easeOut")),
            // ─── TikTok-native effects ─────────────────────────────────────
            Map.entry(SMASH_ZOOM, Map.of("scale_from", 1.0, "scale_to", 2.2, "easing", "easeOut", "duration_ms", 90)),
            Map.entry(BLUR_TRANSITION, Map.of("blur_amount", 18, "duration_ms", 200, "phase", "outro")),
            Map.entry(BRIGHTNESS_BURST, Map.of("brightness_delta", 0.45, "duration_ms", 120, "easing", "easeOut")),
            Map.entry(WHIP_PAN, Map.of("direction", "right", "distance_percent", 55, "blur_amount", 22, "duration_ms", 140)),
            Map.entry(COLOR_POP, Map.of("saturation_boost", 0.35, "duration_ms", 200, "easing", "easeOut")),
            Map.entry(VIGNETTE_PULSE, Map.of("vignette_delta", 0.4, "duration_ms", 150, "easing", "easeOut"))
    );

    private static final Map<String, Map<String, Object>> TRANSITION_DEFAULTS = Map.of(
            TRANSITION_CUT, Map.of(),
            TRANSITION_FADE, Map.of("duration_ms", 180),
            TRANSITION_FADE_WHITE, Map.of("duration_ms", 120),
            TRANSITION_FADE_BLACK, Map.of("duration_ms", 200),
            TRANSITION_DISSOLVE, Map.of("duration_ms", 250),
            TRANSITION_WIPE_LEFT, Map.of("duration_ms", 200),
            TRANSITION_WIPE_RIGHT, Map.of("duration_ms", 200),
            TRANSITION_SLIDE_LEFT, Map.of("duration_ms", 180),
            TRANSITION_SLIDE_RIGHT, Map.of("duration_ms", 180)
    );

    public Map<String, Object> getEffectDefaults(String effectType) {
        return EFFECT_DEFAULTS.get(effectType);
    }

    public Map<String, Object> getTransitionDefaults(String transitionType) {
        return TRANSITION_DEFAULTS.get(transitionType);
    }

    public boolean isValidEffect(String effectType) {
        return EFFECT_DEFAULTS.containsKey(effectType);
    }

    public boolean isValidTransition(String transitionType) {
        return TRANSITION_DEFAULTS.containsKey(transitionType);
    }

    /** True jeśli Remotion renderer aktualnie obsługuje ten efekt. */
    public boolean isRemotionSupportedEffect(String effectType) {
        return REMOTION_EFFECTS.contains(effectType);
    }

    /**
     * Zwraca Remotion-safe odpowiednik efektu.
     * Jeśli efekt jest już obsługiwany — zwraca go bez zmian.
     * Nowe efekty TikTok-native → fallback na najbliższy Remotion equivalent.
     */
    public String mapToRemotionSafeEffect(String effectType) {
        if (isRemotionSupportedEffect(effectType)) return effectType;
        return REMOTION_FALLBACKS.getOrDefault(effectType, ZOOM_IN);
    }

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
