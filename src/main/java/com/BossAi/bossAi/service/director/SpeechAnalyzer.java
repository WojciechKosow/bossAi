package com.BossAi.bossAi.service.director;

import com.BossAi.bossAi.service.SubtitleService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Warstwa B — ANALIZA TIMINGÓW MOWY z WhisperX.
 *
 * Analizuje per-word timestampy z WhisperX i wykrywa:
 *   - Pauzy > 300ms (breath), > 500ms (sentence), > 800ms (dramatic)
 *   - Granice zdań (interpunkcja + pauza)
 *   - Tempo mowy w oknach 2-sekundowych
 *   - Zmiany tempa (przyspieszenie/zwolnienie)
 *
 * Te dane mówią WHERE ciąć:
 *   - Na pauzie = bezpieczny punkt cięcia (film grammar)
 *   - Na końcu zdania = naturalny punkt zmiany kadru
 *   - Zmiana tempa = potencjalna zmiana energii wizualnej
 *
 * NIGDY nie tnij w środku słowa ani w środku myśli.
 */
@Slf4j
@Service
public class SpeechAnalyzer {

    private static final Set<Character> SENTENCE_ENDS = Set.of('.', '!', '?');
    private static final Set<Character> ENUM_BREAKS = Set.of(',', ';');

    /** Minimalna pauza uznawana za "oddech" */
    private static final int BREATH_PAUSE_MS = 300;

    /** Pauza uznawana za koniec zdania */
    private static final int SENTENCE_PAUSE_MS = 500;

    /** Pauza uznawana za dramatyczną */
    private static final int DRAMATIC_PAUSE_MS = 800;

    /** Rozmiar okna do analizy tempa (ms) */
    private static final int TEMPO_WINDOW_MS = 2000;

    /**
     * Analizuje timestampy słów i zwraca SpeechTimingAnalysis.
     */
    public SpeechTimingAnalysis analyze(List<SubtitleService.WordTiming> wordTimings) {
        if (wordTimings == null || wordTimings.isEmpty()) {
            log.info("[SpeechAnalyzer] No word timings available — returning empty analysis");
            return SpeechTimingAnalysis.builder()
                    .pauses(List.of())
                    .sentenceBoundaryWordIndices(List.of())
                    .tempoWindows(List.of())
                    .averageTempo(0.0)
                    .totalDurationMs(0)
                    .totalWords(0)
                    .build();
        }

        log.info("[SpeechAnalyzer] Analyzing {} words", wordTimings.size());

        List<SpeechTimingAnalysis.SpeechPause> pauses = detectPauses(wordTimings);
        List<Integer> sentenceBoundaries = detectSentenceBoundaries(wordTimings, pauses);
        List<SpeechTimingAnalysis.TempoWindow> tempoWindows = analyzeTempoWindows(wordTimings);
        double avgTempo = calculateAverageTempo(wordTimings);

        int totalDuration = wordTimings.get(wordTimings.size() - 1).endMs() - wordTimings.get(0).startMs();

        SpeechTimingAnalysis analysis = SpeechTimingAnalysis.builder()
                .pauses(pauses)
                .sentenceBoundaryWordIndices(sentenceBoundaries)
                .tempoWindows(tempoWindows)
                .averageTempo(avgTempo)
                .totalDurationMs(totalDuration)
                .totalWords(wordTimings.size())
                .build();

        log.info("[SpeechAnalyzer] Analysis complete — {} pauses, {} sentence boundaries, " +
                        "{} tempo windows, avg tempo: {} wps",
                pauses.size(), sentenceBoundaries.size(), tempoWindows.size(),
                String.format("%.1f", avgTempo));

        return analysis;
    }

    // =========================================================================
    // PAUSE DETECTION
    // =========================================================================

    private List<SpeechTimingAnalysis.SpeechPause> detectPauses(List<SubtitleService.WordTiming> words) {
        List<SpeechTimingAnalysis.SpeechPause> pauses = new ArrayList<>();

        for (int i = 0; i < words.size() - 1; i++) {
            SubtitleService.WordTiming current = words.get(i);
            SubtitleService.WordTiming next = words.get(i + 1);

            int gap = next.startMs() - current.endMs();

            if (gap >= BREATH_PAUSE_MS) {
                String word = current.word();
                char lastChar = word.isEmpty() ? ' ' : word.charAt(word.length() - 1);

                boolean isSentenceEnd = SENTENCE_ENDS.contains(lastChar);
                boolean isEnumBreak = ENUM_BREAKS.contains(lastChar);

                String type;
                if (gap >= DRAMATIC_PAUSE_MS) {
                    type = "dramatic";
                } else if (isSentenceEnd) {
                    type = "sentence_end";
                } else if (isEnumBreak) {
                    type = "enumeration";
                } else {
                    type = "breath";
                }

                pauses.add(SpeechTimingAnalysis.SpeechPause.builder()
                        .afterWordIndex(i)
                        .durationMs(gap)
                        .startMs(current.endMs())
                        .endMs(next.startMs())
                        .type(type)
                        .sentenceBoundary(isSentenceEnd || gap >= SENTENCE_PAUSE_MS)
                        .build());
            }
        }

        return pauses;
    }

    // =========================================================================
    // SENTENCE BOUNDARY DETECTION
    // =========================================================================

    private List<Integer> detectSentenceBoundaries(
            List<SubtitleService.WordTiming> words,
            List<SpeechTimingAnalysis.SpeechPause> pauses) {

        List<Integer> boundaries = new ArrayList<>();

        for (int i = 0; i < words.size(); i++) {
            String word = words.get(i).word();
            if (word.isEmpty()) continue;

            char lastChar = word.charAt(word.length() - 1);

            if (SENTENCE_ENDS.contains(lastChar)) {
                boundaries.add(i);
                continue;
            }

            // Check if there's a long pause after this word (even without punctuation)
            for (SpeechTimingAnalysis.SpeechPause pause : pauses) {
                if (pause.getAfterWordIndex() == i && pause.isSentenceBoundary()) {
                    boundaries.add(i);
                    break;
                }
            }
        }

        return boundaries;
    }

    // =========================================================================
    // TEMPO ANALYSIS — words per second in sliding windows
    // =========================================================================

    private List<SpeechTimingAnalysis.TempoWindow> analyzeTempoWindows(
            List<SubtitleService.WordTiming> words) {

        if (words.isEmpty()) return List.of();

        List<SpeechTimingAnalysis.TempoWindow> windows = new ArrayList<>();

        int totalStartMs = words.get(0).startMs();
        int totalEndMs = words.get(words.size() - 1).endMs();

        // Slide window with 50% overlap
        int step = TEMPO_WINDOW_MS / 2;
        for (int windowStart = totalStartMs; windowStart < totalEndMs; windowStart += step) {
            int windowEnd = windowStart + TEMPO_WINDOW_MS;

            // Count words in this window
            int wordCount = 0;
            for (SubtitleService.WordTiming wt : words) {
                // Word is "in" the window if its center falls within
                int wordCenter = (wt.startMs() + wt.endMs()) / 2;
                if (wordCenter >= windowStart && wordCenter < windowEnd) {
                    wordCount++;
                }
            }

            double durationSec = Math.min(windowEnd, totalEndMs) - windowStart;
            durationSec = durationSec / 1000.0;

            if (durationSec > 0) {
                double wps = wordCount / durationSec;
                String classification;
                if (wps < 2.0) classification = "slow";
                else if (wps > 3.5) classification = "fast";
                else classification = "normal";

                windows.add(SpeechTimingAnalysis.TempoWindow.builder()
                        .startMs(windowStart)
                        .endMs(Math.min(windowEnd, totalEndMs))
                        .wordsPerSecond(Math.round(wps * 100.0) / 100.0)
                        .classification(classification)
                        .build());
            }
        }

        return windows;
    }

    private double calculateAverageTempo(List<SubtitleService.WordTiming> words) {
        if (words.size() < 2) return 0.0;
        int totalMs = words.get(words.size() - 1).endMs() - words.get(0).startMs();
        if (totalMs <= 0) return 0.0;
        return Math.round((words.size() / (totalMs / 1000.0)) * 100.0) / 100.0;
    }
}
