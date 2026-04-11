package com.BossAi.bossAi.service.director;

import lombok.*;

import java.util.List;

/**
 * Analiza timingów mowy z WhisperX — wykrywa pauzy, granice zdań, tempo.
 *
 * Warstwa B systemu cięć:
 *   - Dokładne timestampy słów (z WhisperX, <20ms dokładności)
 *   - Wykrywanie pauz > 300-500ms
 *   - Wykrywanie końców zdań (interpunkcja + pauza)
 *   - Tempo mowy (słowa/sekundę) w oknach czasowych
 *
 * To dane do CutEngine — mówią WHERE ciąć (na pauzie, na końcu zdania),
 * nie WHY (to daje NarrationAnalysis).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SpeechTimingAnalysis {

    /** Wykryte pauzy w mowie */
    private List<SpeechPause> pauses;

    /** Indeksy słów na których kończą się zdania */
    private List<Integer> sentenceBoundaryWordIndices;

    /** Tempo mowy w oknach czasowych */
    private List<TempoWindow> tempoWindows;

    /** Średnie tempo mowy (słowa/sekundę) */
    private double averageTempo;

    /** Całkowity czas trwania mowy w ms */
    private int totalDurationMs;

    /** Łączna liczba słów */
    private int totalWords;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SpeechPause {

        /** Indeks słowa PO którym jest pauza (0-based) */
        private int afterWordIndex;

        /** Czas trwania pauzy w ms */
        private int durationMs;

        /** Timestamp początku pauzy (ms) */
        private int startMs;

        /** Timestamp końca pauzy (ms) */
        private int endMs;

        /**
         * Typ pauzy:
         *   sentence_end — po interpunkcji kończącej zdanie (. ! ? ;)
         *   enumeration — po przecinku/średniku w wyliczeniu
         *   breath — naturalna pauza oddechowa (bez interpunkcji)
         *   dramatic — długa pauza (>800ms) — dramatyczna
         *   topic_shift — pauza przy zmianie tematu (koreluje z NarrationAnalysis)
         */
        private String type;

        /** Czy na tej pauzie jest granica zdania */
        private boolean sentenceBoundary;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class TempoWindow {

        /** Początek okna (ms) */
        private int startMs;

        /** Koniec okna (ms) */
        private int endMs;

        /** Tempo w tym oknie (słowa/sekundę) */
        private double wordsPerSecond;

        /**
         * Klasyfikacja tempa:
         *   slow — < 2.0 wps
         *   normal — 2.0-3.5 wps
         *   fast — > 3.5 wps
         */
        private String classification;
    }
}
