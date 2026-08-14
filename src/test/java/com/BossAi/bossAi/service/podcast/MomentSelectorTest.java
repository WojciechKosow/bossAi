package com.BossAi.bossAi.service.podcast;

import com.BossAi.bossAi.service.podcast.model.DiarizedTranscript;
import com.BossAi.bossAi.service.podcast.model.SelectedMoment;
import com.BossAi.bossAi.service.podcast.model.SpeakerTurn;
import com.BossAi.bossAi.service.podcast.model.TranscriptWord;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MomentSelectorTest {

    /**
     * The regression this guards: an undiarized (single-speaker) episode collapses
     * to ONE giant speaker turn. The transcript fed to the director must still span
     * the WHOLE episode as timestamped windows — not just the opening slice — or
     * the director can only ever pick a clip from the very beginning.
     */
    @Test
    void formatsUndiarizedEpisodeAsWindowsSpanningTheWholeDuration() {
        // 10 minutes of speech, one word per second, no diarization (null speaker).
        List<TranscriptWord> words = new ArrayList<>();
        for (int i = 0; i < 600; i++) {
            words.add(new TranscriptWord("word" + i, i * 1000, i * 1000 + 800, null));
        }
        DiarizedTranscript transcript = new DiarizedTranscript(words, "en", 600_000);

        // The segmenter yields a single UNKNOWN-speaker turn for the whole episode.
        List<SpeakerTurn> turns = new SpeakerTurnSegmenter().segment(transcript);
        assertTrue(turns.size() == 1, "expected one collapsed turn, got " + turns.size());

        MomentSelector selector = new MomentSelector(null, null);
        String rendered = selector.formatTranscript(words, turns);

        String[] lines = rendered.strip().split("\n");
        // Many windows, not one truncated line.
        assertTrue(lines.length >= 10, "expected the episode split into many windows, got " + lines.length);
        // Coverage reaches the end: a timecode in the 9th minute must be present.
        assertTrue(rendered.contains("[09:"),
                "transcript windows must reach the end of the episode:\n" + rendered);
        // And it still starts at the beginning.
        assertTrue(lines[0].startsWith("[00:00]"), lines[0]);
    }

    /**
     * Ranking keeps the highest-scoring moments, drops ones that overlap a
     * higher-scored pick, and returns the survivors in chronological order.
     */
    @Test
    void ranksByScoreDropsOverlapsReturnsChronological() {
        MomentSelector selector = new MomentSelector(null, null);

        SelectedMoment a = new SelectedMoment(0, 20_000, "A", "", 90);       // best
        SelectedMoment b = new SelectedMoment(10_000, 25_000, "B", "", 70);  // overlaps A → dropped
        SelectedMoment c = new SelectedMoment(60_000, 80_000, "C", "", 80);  // distinct, high
        SelectedMoment d = new SelectedMoment(120_000, 140_000, "D", "", 40); // distinct, low

        List<SelectedMoment> kept = selector.rankAndDedup(new ArrayList<>(List.of(b, d, a, c)), 3);

        // A (90) and C (80) win; B overlaps A so it's out; D (40) fills the 3rd slot.
        assertEquals(3, kept.size(), kept.toString());
        assertEquals(List.of("A", "C", "D"),
                kept.stream().map(SelectedMoment::title).toList());
        // Chronological order for rendering.
        assertTrue(kept.get(0).approxStartMs() < kept.get(1).approxStartMs()
                && kept.get(1).approxStartMs() < kept.get(2).approxStartMs());
    }
}
