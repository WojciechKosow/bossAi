package com.BossAi.bossAi.service.director;

import lombok.*;

import java.util.List;

/**
 * Speech timing analysis from WhisperX — detects pauses, sentence boundaries, tempo.
 *
 * Layer B of the cutting system:
 *   - Precise word timestamps (from WhisperX, <20ms accuracy)
 *   - Detection of pauses > 300-500ms
 *   - Detection of sentence endings (punctuation + pause)
 *   - Speech tempo (words/second) in time windows
 *
 * This is data for CutEngine — it tells WHERE to cut (on a pause, at a sentence end),
 * not WHY (that comes from NarrationAnalysis).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SpeechTimingAnalysis {

    /** Detected pauses in the speech */
    private List<SpeechPause> pauses;

    /** Indices of the words at which sentences end */
    private List<Integer> sentenceBoundaryWordIndices;

    /** Speech tempo in time windows */
    private List<TempoWindow> tempoWindows;

    /** Average speech tempo (words/second) */
    private double averageTempo;

    /** Total speech duration in ms */
    private int totalDurationMs;

    /** Total number of words */
    private int totalWords;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SpeechPause {

        /** Index of the word AFTER which the pause occurs (0-based) */
        private int afterWordIndex;

        /** Pause duration in ms */
        private int durationMs;

        /** Timestamp of the pause start (ms) */
        private int startMs;

        /** Timestamp of the pause end (ms) */
        private int endMs;

        /**
         * Pause type:
         *   sentence_end — after sentence-ending punctuation (. ! ? ;)
         *   enumeration — after a comma/semicolon in a list
         *   breath — a natural breathing pause (no punctuation)
         *   dramatic — a long pause (>800ms) — dramatic
         *   topic_shift — a pause on a topic change (correlates with NarrationAnalysis)
         */
        private String type;

        /** Whether this pause coincides with a sentence boundary */
        private boolean sentenceBoundary;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class TempoWindow {

        /** Window start (ms) */
        private int startMs;

        /** Window end (ms) */
        private int endMs;

        /** Tempo in this window (words/second) */
        private double wordsPerSecond;

        /**
         * Tempo classification:
         *   slow — < 2.0 wps
         *   normal — 2.0-3.5 wps
         *   fast — > 3.5 wps
         */
        private String classification;
    }
}
