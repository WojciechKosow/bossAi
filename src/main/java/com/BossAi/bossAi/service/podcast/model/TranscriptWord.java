package com.BossAi.bossAi.service.podcast.model;

/**
 * A single word from the WhisperX transcription, with precise timing and the
 * diarized speaker label.
 *
 * <p>This is the atomic unit of the podcast → clips pipeline: speaker
 * segmentation, LLM moment selection, and sentence boundary snapping all
 * operate on lists of these. Timings are absolute, in milliseconds, relative to
 * the start of the source episode.
 *
 * @param word     the token text (may carry trailing punctuation, e.g. "world.")
 * @param startMs  absolute start time in the episode, in milliseconds
 * @param endMs    absolute end time in the episode, in milliseconds
 * @param speaker  diarized speaker label (e.g. "SPEAKER_00"); null if diarization
 *                 was unavailable for this word
 */
public record TranscriptWord(String word, int startMs, int endMs, String speaker) {

    /** Sentence-terminating punctuation. Used by the boundary snapper. */
    private static final String SENTENCE_TERMINATORS = ".!?…";

    /**
     * True when this word visibly ends a sentence — i.e. its (trimmed) text ends
     * with sentence-terminating punctuation. Trailing quotes/brackets after the
     * terminator still count (e.g. {@code done."} or {@code right?)}).
     */
    public boolean endsSentence() {
        if (word == null) {
            return false;
        }
        String trimmed = word.strip();
        // Walk back over closing quotes/brackets that can follow a terminator.
        int i = trimmed.length() - 1;
        while (i >= 0 && isClosingWrapper(trimmed.charAt(i))) {
            i--;
        }
        return i >= 0 && SENTENCE_TERMINATORS.indexOf(trimmed.charAt(i)) >= 0;
    }

    private static boolean isClosingWrapper(char c) {
        return c == '"' || c == '\'' || c == ')' || c == ']' || c == '»' || c == '”' || c == '’';
    }

    /** Duration of the spoken word, in milliseconds. */
    public int durationMs() {
        return endMs - startMs;
    }
}
