package com.BossAi.bossAi.service.director;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.*;

import java.util.List;

/**
 * Analiza narracji — GPT rozkłada scenariusz na segmenty semantyczne.
 *
 * Każdy segment ma:
 *   - text: fragment narracji
 *   - type: rola w narracji (hook, point, transition, cta, emphasis, setup)
 *   - importance: 0.0-1.0 — jak ważny jest ten fragment dla przekazu
 *   - energy: 0.0-1.0 — dynamika/tempo tego fragmentu
 *   - topic: klucz tematyczny (np. "intro", "automation", "summary")
 *   - keyword: najważniejsze słowo w segmencie (na które warto ciąć)
 *
 * To jest FUNDAMENT do uzasadnionych cięć — zamiast "kiedy ciąć"
 * odpowiada na pytanie "DLACZEGO ciąć teraz?"
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class NarrationAnalysis {

    @JsonProperty("segments")
    private List<NarrationSegment> segments;

    @JsonProperty("editing_intent")
    private EditingIntent editingIntent;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class NarrationSegment {

        /** Fragment tekstu narracji */
        @JsonProperty("text")
        private String text;

        /**
         * Rola segmentu:
         *   hook — otwieracz, scroll-stopper
         *   setup — kontekst, wprowadzenie
         *   point — główny punkt/argument
         *   emphasis — podkreślenie, wzmocnienie
         *   transition — przejście między tematami
         *   cta — call to action
         *   climax — punkt kulminacyjny
         *   cooldown — wyciszenie po szczycie
         */
        @JsonProperty("type")
        private String type;

        /** Jak ważny jest ten fragment (0.0-1.0). Wyższy = bardziej wart wizualnego wyróżnienia */
        @JsonProperty("importance")
        private double importance;

        /** Dynamika/tempo tego fragmentu (0.0-1.0). Wyższy = szybsze cięcia */
        @JsonProperty("energy")
        private double energy;

        /** Klucz tematyczny — zmiana topic = potencjalny HARD CUT */
        @JsonProperty("topic")
        private String topic;

        /** Najważniejsze słowo — potencjalny punkt cięcia lub podkreślenia */
        @JsonProperty("keyword")
        private String keyword;

        /** Indeks segmentu w narracji (0-based) */
        @JsonProperty("index")
        private int index;
    }

    /**
     * Intent-Based Editing — GPT decyduje o CHARAKTERZE montażu.
     *
     * Zamiast losowego stylu, GPT analizuje treść + muzykę + nastrój
     * i wybiera konkretną strategię montażową.
     *
     * Przykłady:
     *   intent=build_tension, pattern=slow_to_fast
     *   intent=rhythmic_pulse, pattern=on_beat_consistent
     *   intent=contrast_shock, pattern=long_hold_then_burst
     *   intent=flowing_narrative, pattern=breathing_with_pauses
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class EditingIntent {

        /**
         * Główna intencja montażu:
         *   build_tension — narastające napięcie, coraz szybsze cięcia
         *   rhythmic_pulse — cięcia w rytm muzyki, taneczne
         *   contrast_shock — długie ujęcia przerwane nagłymi cięciami
         *   flowing_narrative — płynne przejścia, oddech między myślami
         *   staccato_energy — szybkie, ostre cięcia na każde zdanie
         *   emotional_wave — cięcia podążające za emocją narratora
         *   reveal_punctuate — długie budowanie do momentu odsłonięcia
         */
        @JsonProperty("intent")
        private String intent;

        /**
         * Wzorzec tempa:
         *   slow_to_fast — zaczyna wolno, przyspiesza
         *   fast_to_slow — otwiera mocno, zwalnia
         *   wave — fale szybko-wolno-szybko
         *   constant_high — ciągle szybko
         *   on_beat_consistent — stałe, w rytm
         *   long_hold_then_burst — długie ujęcia, potem seria szybkich
         *   breathing_with_pauses — naturalne oddechy
         */
        @JsonProperty("pattern")
        private String pattern;

        /**
         * Łuk montażowy — jak zmienia się gęstość cięć w czasie.
         * GPT definiuje fazy filmu z ich nastrojem i gęstością.
         */
        @JsonProperty("arc")
        private List<EditingArc> arc;

        /**
         * Krótkie uzasadnienie dlaczego ten intent/pattern pasuje
         * do tej konkretnej treści + muzyki.
         */
        @JsonProperty("reasoning")
        private String reasoning;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class EditingArc {

        /** Faza filmu: opening, buildup, middle, climax, resolution, outro */
        @JsonProperty("phase")
        private String phase;

        /** Gęstość cięć: very_low, low, medium, high, very_high */
        @JsonProperty("density")
        private String density;

        /** Nastrój fazy: curious, building, intense, euphoric, reflective, urgent */
        @JsonProperty("mood")
        private String mood;

        /** Przybliżony procent filmu, w którym zaczyna się ta faza (0.0-1.0) */
        @JsonProperty("start_pct")
        private double startPct;
    }
}
