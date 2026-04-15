package com.BossAi.bossAi.service.director;

import lombok.*;

import java.util.List;

/**
 * Uzasadnione cięcie — każdy cut ma POWÓD.
 *
 * Zamiast schematycznego "ciąć co 2 sekundy" albo "ciąć na każdym beacie",
 * każde cięcie ma konkretne uzasadnienie oparte na:
 *   - NarrationAnalysis (treść, ważność, temat)
 *   - SpeechTimingAnalysis (pauzy, tempo, granice zdań)
 *   - AudioAnalysis (muzyka, beat, energia)
 *   - EditingIntent (intencja montażowa)
 *
 * Film grammar:
 *   NIE rób: cutów w połowie słowa, w środku myśli
 *   RÓB: cut na końcu zdania, na słowie kluczowym, na zmianie kontekstu
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class JustifiedCut {

    /** Początek cięcia (ms) */
    private int startMs;

    /** Koniec cięcia (ms) */
    private int endMs;

    /**
     * Typ cięcia:
     *   HARD — zmiana kadru, nowy temat/ważna informacja
     *   SOFT — lekka zmiana (zoom, pan), koniec zdania
     *   MICRO — dynamiczna przebitka, szybka zmiana w serii
     */
    private CutClassification classification;

    /**
     * Główny powód cięcia — dlaczego TERAZ?
     */
    private CutReason primaryReason;

    /**
     * Dodatkowe powody (mogą się nakładać, np. topic_change + pause + beat)
     */
    private List<CutReason> secondaryReasons;

    /**
     * Pewność decyzji (0.0-1.0). Wyższy = silniejsze uzasadnienie.
     * Cut z confidence < 0.4 może zostać pominięty przez engine.
     */
    private double confidence;

    /** Indeks segmentu narracji (z NarrationAnalysis) w momencie cięcia */
    private int narrationSegmentIndex;

    /** Energia muzyki w momencie cięcia (0.0-1.0), null jeśli brak muzyki */
    private Double musicEnergy;

    /** Czy cięcie trafia na beat muzyczny (±50ms) */
    private boolean onBeat;

    /**
     * Faza łuku montażowego (z EditingIntent.arc) w momencie cięcia.
     * Np. "opening", "climax", "resolution"
     */
    private String editingPhase;

    /**
     * Sugerowany efekt wizualny na tym cięciu (na podstawie kontekstu).
     * CutEngine sugeruje, ale EdlGenerator może nadpisać.
     */
    private String suggestedEffect;

    /**
     * Sugerowane przejście do następnego segmentu.
     */
    private String suggestedTransition;

    /**
     * Jawnie przypisany indeks assetu (z UserEditIntent / warstwy D).
     * -1 = brak przypisania, użyj scene-based fallback.
     * >= 0 = MUST use this asset (user explicitly requested it).
     */
    @Builder.Default
    private int assignedAssetIndex = -1;

    // ─── Enums ──────────────────────────────────────────────────────

    public enum CutClassification {
        /**
         * HARD CUT — pełna zmiana kadru.
         * Triggery: zmiana topic, importance > 0.75, początek hooka, beat drop
         */
        HARD,

        /**
         * SOFT CUT — lekka zmiana (zoom, pan, subtelne przejście).
         * Triggery: koniec zdania + pauza, spadek energii, oddech
         */
        SOFT,

        /**
         * MICRO CUT — dynamiczna przebitka, szybka seria.
         * Triggery: wysoka energia (>0.8), szybkie tempo mowy, drop muzyczny
         */
        MICRO
    }

    public enum CutReason {
        /** Zmiana tematu w narracji */
        TOPIC_CHANGE,

        /** Wysoka ważność segmentu (importance > 0.75) */
        HIGH_IMPORTANCE,

        /** Początek hooka (scroll-stopper) */
        HOOK_START,

        /** Koniec zdania + pauza w mowie */
        SENTENCE_END_PAUSE,

        /** Spadek energii narracji */
        ENERGY_DROP,

        /** Wzrost energii narracji */
        ENERGY_RISE,

        /** Wysoka energia mowy (>0.8) */
        HIGH_ENERGY_BURST,

        /** Zmiana tempa mowy (z szybkiego na wolne lub odwrotnie) */
        TEMPO_SHIFT,

        /** Cięcie na beacie muzycznym */
        MUSIC_BEAT,

        /** Drop w muzyce (wysoka energia muzyczna) */
        MUSIC_DROP,

        /** Słowo kluczowe — cięcie na ważnym słowie */
        KEYWORD_EMPHASIS,

        /** Dramatyczna pauza (>800ms) */
        DRAMATIC_PAUSE,

        /** Wymóg łuku montażowego (gęstość cięć w danej fazie) */
        ARC_DENSITY,

        /** Wymóg minimalnego/maksymalnego czasu ujęcia */
        DURATION_CONSTRAINT,

        /** Call to action — cięcie przed/na CTA */
        CTA_TRANSITION,

        /** Reset uwagi widza — przerwa w monotonii */
        ATTENTION_RESET
    }
}
