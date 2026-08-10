package com.BossAi.bossAi.service.podcast;

import com.BossAi.bossAi.service.podcast.model.DiarizedTranscript;
import com.BossAi.bossAi.service.podcast.model.SpeakerTurn;
import com.BossAi.bossAi.service.podcast.model.TranscriptWord;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * Groups a diarized transcript into {@link SpeakerTurn}s — contiguous runs of a
 * single speaker. This is the structure handed to the LLM moment director:
 * feeding speaker turns instead of a flat transcript is most of why
 * multi-speaker clip selection is good or bad.
 *
 * <p>Pure, deterministic, side-effect free — fully unit-testable without any
 * external service.
 */
@Service
public class SpeakerTurnSegmenter {

    /** Label used when diarization produced no speaker for a word. */
    static final String UNKNOWN_SPEAKER = "UNKNOWN";

    /**
     * Turns shorter than this are treated as backchannel/interjections and
     * merged into the surrounding turn, so a single "yeah" doesn't fracture a
     * long monologue into three turns.
     */
    static final int DEFAULT_MIN_TURN_MS = 1_500;

    public List<SpeakerTurn> segment(DiarizedTranscript transcript) {
        return segment(transcript, DEFAULT_MIN_TURN_MS);
    }

    /**
     * @param transcript    the diarized transcript
     * @param minTurnMs     turns shorter than this get absorbed into a neighbor
     * @return speaker turns in chronological order (never null; empty if no words)
     */
    public List<SpeakerTurn> segment(DiarizedTranscript transcript, int minTurnMs) {
        if (transcript == null || transcript.isEmpty()) {
            return List.of();
        }
        List<TranscriptWord> words = transcript.words();

        // Pass 1: group consecutive words by speaker. A null/blank speaker
        // continues the current speaker (avoids fragmenting on the occasional
        // unlabeled word); at the very start it becomes UNKNOWN.
        List<int[]> ranges = new ArrayList<>(); // [firstIdx, lastIdx]
        List<String> speakers = new ArrayList<>();
        String current = null;
        int rangeStart = 0;

        for (int i = 0; i < words.size(); i++) {
            String raw = words.get(i).speaker();
            String spk = (raw == null || raw.isBlank()) ? current : raw;
            if (spk == null) {
                spk = UNKNOWN_SPEAKER;
            }
            if (current == null) {
                current = spk;
                rangeStart = i;
            } else if (!spk.equals(current)) {
                ranges.add(new int[]{rangeStart, i - 1});
                speakers.add(current);
                current = spk;
                rangeStart = i;
            }
        }
        ranges.add(new int[]{rangeStart, words.size() - 1});
        speakers.add(current);

        List<SpeakerTurn> turns = buildTurns(words, ranges, speakers);
        turns = mergeShortTurns(words, turns, minTurnMs);
        return coalesceSameSpeaker(words, turns);
    }

    /**
     * Merges adjacent turns that share a speaker. After short-turn absorption the
     * two stretches that surrounded an interjection can end up adjacent under the
     * same label — they are really one turn.
     */
    private List<SpeakerTurn> coalesceSameSpeaker(List<TranscriptWord> words, List<SpeakerTurn> turns) {
        if (turns.size() <= 1) {
            return turns;
        }
        List<SpeakerTurn> out = new ArrayList<>();
        SpeakerTurn run = turns.get(0);
        for (int i = 1; i < turns.size(); i++) {
            SpeakerTurn next = turns.get(i);
            if (next.speaker().equals(run.speaker())) {
                run = toTurn(words, run.speaker(), run.firstWordIndex(), next.lastWordIndex());
            } else {
                out.add(run);
                run = next;
            }
        }
        out.add(run);
        return out;
    }

    private List<SpeakerTurn> buildTurns(List<TranscriptWord> words,
                                         List<int[]> ranges, List<String> speakers) {
        List<SpeakerTurn> turns = new ArrayList<>(ranges.size());
        for (int r = 0; r < ranges.size(); r++) {
            int first = ranges.get(r)[0];
            int last = ranges.get(r)[1];
            turns.add(toTurn(words, speakers.get(r), first, last));
        }
        return turns;
    }

    /**
     * Absorbs turns shorter than {@code minTurnMs} into an adjacent turn. The
     * short turn is merged into whichever neighbor already matches its speaker;
     * otherwise into the longer neighbor. The result keeps chronological order
     * and full word coverage.
     */
    private List<SpeakerTurn> mergeShortTurns(List<TranscriptWord> words,
                                              List<SpeakerTurn> turns, int minTurnMs) {
        if (minTurnMs <= 0 || turns.size() <= 1) {
            return turns;
        }
        boolean merged = true;
        // Iterate to a fixed point: merging can create a new short turn.
        while (merged && turns.size() > 1) {
            merged = false;
            for (int i = 0; i < turns.size(); i++) {
                SpeakerTurn t = turns.get(i);
                if (t.durationMs() >= minTurnMs) {
                    continue;
                }
                int target = chooseMergeTarget(turns, i);
                if (target < 0) {
                    continue; // isolated single turn — nothing to merge into
                }
                int lo = Math.min(i, target);
                int hi = Math.max(i, target);
                // Merge the contiguous pair [lo, hi] into one turn under the
                // dominant (longer) speaker's label.
                SpeakerTurn a = turns.get(lo);
                SpeakerTurn b = turns.get(hi);
                String label = a.durationMs() >= b.durationMs() ? a.speaker() : b.speaker();
                SpeakerTurn combined = toTurn(words, label, a.firstWordIndex(), b.lastWordIndex());
                turns.set(lo, combined);
                turns.remove(hi);
                merged = true;
                break;
            }
        }
        return turns;
    }

    /** Prefer a same-speaker neighbor; else the longer neighbor. Returns index or -1. */
    private int chooseMergeTarget(List<SpeakerTurn> turns, int i) {
        int prev = i - 1;
        int next = i + 1;
        String spk = turns.get(i).speaker();
        boolean prevSame = prev >= 0 && turns.get(prev).speaker().equals(spk);
        boolean nextSame = next < turns.size() && turns.get(next).speaker().equals(spk);
        if (prevSame) return prev;
        if (nextSame) return next;
        if (prev < 0 && next >= turns.size()) return -1;
        if (prev < 0) return next;
        if (next >= turns.size()) return prev;
        return turns.get(prev).durationMs() >= turns.get(next).durationMs() ? prev : next;
    }

    private SpeakerTurn toTurn(List<TranscriptWord> words, String speaker, int first, int last) {
        StringBuilder sb = new StringBuilder();
        for (int i = first; i <= last; i++) {
            String w = words.get(i).word();
            if (w == null || w.isBlank()) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append(' ');
            }
            sb.append(w.strip());
        }
        return new SpeakerTurn(
                speaker,
                words.get(first).startMs(),
                words.get(last).endMs(),
                first,
                last,
                sb.toString()
        );
    }
}
