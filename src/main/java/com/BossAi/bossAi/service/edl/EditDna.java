package com.BossAi.bossAi.service.edl;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.*;

import java.util.List;

/**
 * Edit DNA — unikalna "osobowość" montażu generowana przez LLM Director.
 *
 * Generowana RAZ na projekt, PRZED generowaniem EDL.
 * Determinuje jak GPT wygeneruje EDL — jakie efekty, rytm cięć, kolor.
 * Ten sam seed = ten sam edit_dna = ten sam film (powtarzalność).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class EditDna {

    /**
     * Seed do reprodukowalności. Ten sam seed + te same dane = ten sam output.
     */
    private long seed;

    /**
     * Osobowość montażu — opisuje ogólny charakter edycji.
     * Np. "chaotic_precise", "smooth_flowing", "aggressive_minimal", "cinematic_slow_burn"
     */
    @JsonProperty("edit_personality")
    private String editPersonality;

    /**
     * Rytm cięć — jak organizować cięcia w czasie.
     */
    @JsonProperty("cut_rhythm")
    private CutRhythm cutRhythm;

    /**
     * Paleta efektów — jakie efekty dominują, jakie są zabronione.
     */
    @JsonProperty("effect_palette")
    private EffectPalette effectPalette;

    /**
     * Color grading — wizualny charakter projektu.
     */
    @JsonProperty("color_grade")
    private ColorGrade colorGrade;

    /**
     * Strategia hook (pierwsze 2-3 sekundy).
     */
    @JsonProperty("hook_strategy")
    private String hookStrategy;

    /**
     * Krótkie uzasadnienie decyzji LLM (do logów/debugowania).
     */
    @JsonProperty("reasoning")
    private String reasoning;

    // ─── Nested DTOs ────────────────────────────────────────────────

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class CutRhythm {

        /**
         * Tryb cięć:
         * - sparse_with_bursts: długie ujęcia (2-4 beaty), potem seria szybkich na dropie
         * - on_beat_strict: cięcie dokładnie na beat, małe humanize
         * - off_beat_syncopated: cięcia między beatami, nieoczekiwane
         * - breathing: cięcia na pauzy w muzyce, nie na beat
         * - escalating: coraz szybsze cięcia w kierunku dropu
         */
        private String mode;

        /**
         * Co triggeruje burst szybkich cięć (np. "drop", "peak", "chorus").
         */
        @JsonProperty("burst_trigger")
        private String burstTrigger;

        /**
         * Humanize w ms — losowe przesunięcie cięć żeby nie brzmiały maszynowo.
         */
        @JsonProperty("humanize_ms")
        private int humanizeMs;

        /**
         * Minimalny czas ujęcia w ms (nie krótsze od tego).
         */
        @JsonProperty("min_cut_ms")
        private int minCutMs;

        /**
         * Maksymalny czas ujęcia w ms (nie dłuższe od tego).
         */
        @JsonProperty("max_cut_ms")
        private int maxCutMs;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class EffectPalette {

        /**
         * Główny efekt projektu (używany najczęściej).
         */
        private String primary;

        /**
         * Drugi efekt (uzupełnienie primary).
         */
        private String secondary;

        /**
         * Efekt specjalny na drop/peak (sygnatura projektu).
         */
        @JsonProperty("drop_signature")
        private String dropSignature;

        /**
         * Efekty ZAKAZANE w tym projekcie (żeby każdy film był inny).
         */
        private List<String> forbidden;

        /**
         * Preferowana intensywność bazowa (0.0-1.0).
         */
        @JsonProperty("base_intensity")
        private double baseIntensity;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ColorGrade {

        /**
         * Preset koloru:
         * cold_matte, warm_golden, high_contrast, desaturated, vibrant_pop, moody_dark, clean_bright
         */
        private String preset;

        @JsonProperty("contrast_boost")
        private double contrastBoost;

        private double saturation;

        private double brightness;

        private double vignette;
    }
}
