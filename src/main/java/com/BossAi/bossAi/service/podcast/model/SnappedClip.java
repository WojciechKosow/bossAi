package com.BossAi.bossAi.service.podcast.model;

import java.util.List;

/**
 * A clip whose in/out points have been snapped to real sentence boundaries from
 * the word-level alignment. By construction it never starts or ends
 * mid-sentence.
 *
 * @param title          headline from the LLM director (carried through)
 * @param reasoning      why this moment was chosen (carried through)
 * @param score          director's virality score 0–100 (carried through)
 * @param startMs        sentence-snapped start in the SOURCE episode
 * @param endMs          sentence-snapped end in the SOURCE episode
 * @param firstWordIndex index (inclusive) of the first word, into the full transcript
 * @param lastWordIndex  index (inclusive) of the last word, into the full transcript
 * @param words          the transcript words contained in the clip (source-absolute timings)
 */
public record SnappedClip(
        String title,
        String reasoning,
        int score,
        int startMs,
        int endMs,
        int firstWordIndex,
        int lastWordIndex,
        List<TranscriptWord> words
) {
    public SnappedClip {
        words = words == null ? List.of() : List.copyOf(words);
    }

    public int durationMs() {
        return endMs - startMs;
    }
}
