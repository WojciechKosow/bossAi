package com.BossAi.bossAi.service.podcast.model;

import java.util.List;

/**
 * A contiguous run of speech by a single speaker — the structural unit fed to
 * the LLM moment director. Giving the director speaker turns (rather than a flat
 * transcript) is most of why multi-speaker clip selection is usually good or
 * bad.
 *
 * @param speaker        diarized speaker label ("SPEAKER_00", or "UNKNOWN")
 * @param startMs        absolute start of the turn in the episode
 * @param endMs          absolute end of the turn in the episode
 * @param firstWordIndex index (inclusive) of the first word in the transcript
 * @param lastWordIndex  index (inclusive) of the last word in the transcript
 * @param text           the spoken text of this turn
 */
public record SpeakerTurn(
        String speaker,
        int startMs,
        int endMs,
        int firstWordIndex,
        int lastWordIndex,
        String text
) {
    public int durationMs() {
        return endMs - startMs;
    }

    public int wordCount() {
        return lastWordIndex - firstWordIndex + 1;
    }

    /** Convenience: the words of this turn sliced out of the full transcript list. */
    public List<TranscriptWord> wordsFrom(List<TranscriptWord> allWords) {
        return allWords.subList(firstWordIndex, lastWordIndex + 1);
    }
}
