package com.BossAi.bossAi.service.podcast.model;

import java.util.List;

/**
 * The full word-level, speaker-diarized transcript of a source episode,
 * as produced by the WhisperX audio-analysis service.
 *
 * @param words       all words in spoken order, with absolute timings
 * @param language    detected/forced language code (e.g. "en")
 * @param durationMs  total audio duration in milliseconds
 */
public record DiarizedTranscript(List<TranscriptWord> words, String language, int durationMs) {

    public DiarizedTranscript {
        words = words == null ? List.of() : List.copyOf(words);
    }

    public boolean isEmpty() {
        return words.isEmpty();
    }

    /** Number of distinct diarized speakers (ignoring null labels). */
    public long distinctSpeakers() {
        return words.stream()
                .map(TranscriptWord::speaker)
                .filter(s -> s != null && !s.isBlank())
                .distinct()
                .count();
    }

    /** Reconstructs the plain-text transcript by joining word tokens with spaces. */
    public String plainText() {
        StringBuilder sb = new StringBuilder();
        for (TranscriptWord w : words) {
            if (w.word() == null || w.word().isBlank()) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append(' ');
            }
            sb.append(w.word().strip());
        }
        return sb.toString();
    }
}
