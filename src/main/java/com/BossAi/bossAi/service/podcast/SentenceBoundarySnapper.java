package com.BossAi.bossAi.service.podcast;

import com.BossAi.bossAi.service.podcast.model.DiarizedTranscript;
import com.BossAi.bossAi.service.podcast.model.SelectedMoment;
import com.BossAi.bossAi.service.podcast.model.SnappedClip;
import com.BossAi.bossAi.service.podcast.model.TranscriptWord;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * Snaps the LLM director's approximate clip in/out points to real sentence
 * boundaries taken from the word-level alignment.
 *
 * <p>This is deterministic code, NOT an LLM instruction — the director gives
 * rough timestamps and this expands them outward to whole sentences so a clip
 * never begins or ends mid-sentence. Getting this right is the difference
 * between output that looks amateur and output that doesn't; most competitors
 * get it wrong regularly.
 *
 * <p>Algorithm (expand-outward):
 * <ul>
 *   <li><b>start</b> snaps back to the start of the sentence that is already in
 *       progress at the approximate start — so the first sentence is whole.</li>
 *   <li><b>end</b> snaps forward to the end of the sentence still in progress at
 *       the approximate end — so the last sentence is whole.</li>
 * </ul>
 * Sentence boundaries are derived from terminating punctuation on word tokens
 * ({@link TranscriptWord#endsSentence()}). When a transcript carries no
 * punctuation at all, it falls back to snapping to the nearest word grid.
 *
 * <p>Pure and side-effect free — fully unit-testable without external services.
 */
@Service
public class SentenceBoundarySnapper {

    /**
     * Snaps one moment. Returns null when the transcript is empty or the moment
     * cannot be mapped onto any words (nothing to render).
     */
    public SnappedClip snap(DiarizedTranscript transcript, SelectedMoment moment) {
        if (transcript == null || transcript.isEmpty() || moment == null) {
            return null;
        }
        List<TranscriptWord> words = transcript.words();

        // Sentence-start word indices: index 0, and every index right after a
        // sentence-ending word.
        List<Integer> sentenceStarts = new ArrayList<>();
        sentenceStarts.add(0);
        for (int i = 0; i < words.size() - 1; i++) {
            if (words.get(i).endsSentence()) {
                sentenceStarts.add(i + 1);
            }
        }

        int approxStart = Math.max(0, moment.approxStartMs());
        int approxEnd = Math.max(approxStart + 1, moment.approxEndMs());

        int firstWord = snapStartWord(words, sentenceStarts, approxStart);
        int lastWord = snapEndWord(words, firstWord, approxEnd);

        if (firstWord > lastWord) {
            return null;
        }

        List<TranscriptWord> clipWords = new ArrayList<>(words.subList(firstWord, lastWord + 1));
        return new SnappedClip(
                moment.title(),
                moment.reasoning(),
                words.get(firstWord).startMs(),
                words.get(lastWord).endMs(),
                firstWord,
                lastWord,
                clipWords
        );
    }

    public List<SnappedClip> snapAll(DiarizedTranscript transcript, List<SelectedMoment> moments) {
        if (moments == null) {
            return List.of();
        }
        List<SnappedClip> out = new ArrayList<>(moments.size());
        for (SelectedMoment m : moments) {
            SnappedClip c = snap(transcript, m);
            if (c != null) {
                out.add(c);
            }
        }
        return out;
    }

    /**
     * The sentence-start word at or before the approximate start: the last
     * sentence-start whose word begins no later than {@code approxStartMs}. If
     * the approx start precedes the first word, returns word 0.
     */
    private int snapStartWord(List<TranscriptWord> words, List<Integer> sentenceStarts, int approxStartMs) {
        int chosen = sentenceStarts.get(0);
        for (int idx : sentenceStarts) {
            if (words.get(idx).startMs() <= approxStartMs) {
                chosen = idx;
            } else {
                break; // sentenceStarts is ascending in time
            }
        }
        return chosen;
    }

    /**
     * The sentence-end word at or after the approximate end, but never before
     * {@code firstWord}: the first word from {@code firstWord} onward that ends a
     * sentence AND ends no earlier than {@code approxEndMs}. Falls back to the
     * last word (end of transcript) when no such boundary exists.
     */
    private int snapEndWord(List<TranscriptWord> words, int firstWord, int approxEndMs) {
        for (int i = firstWord; i < words.size(); i++) {
            if (words.get(i).endsSentence() && words.get(i).endMs() >= approxEndMs) {
                return i;
            }
        }
        // No terminating punctuation reached the approx end. Prefer the last
        // sentence boundary in range; otherwise the end of the transcript.
        for (int i = words.size() - 1; i >= firstWord; i--) {
            if (words.get(i).endsSentence()) {
                return Math.max(i, lastWordAtOrAfter(words, firstWord, approxEndMs));
            }
        }
        return lastWordAtOrAfter(words, firstWord, approxEndMs);
    }

    /** Word-grid fallback: the last word whose end is ≤ approxEnd, or firstWord. */
    private int lastWordAtOrAfter(List<TranscriptWord> words, int firstWord, int approxEndMs) {
        int chosen = firstWord;
        for (int i = firstWord; i < words.size(); i++) {
            if (words.get(i).endMs() <= approxEndMs) {
                chosen = i;
            } else {
                break;
            }
        }
        return chosen;
    }
}
