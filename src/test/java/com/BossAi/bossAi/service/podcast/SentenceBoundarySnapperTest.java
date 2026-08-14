package com.BossAi.bossAi.service.podcast;

import com.BossAi.bossAi.service.podcast.model.DiarizedTranscript;
import com.BossAi.bossAi.service.podcast.model.SelectedMoment;
import com.BossAi.bossAi.service.podcast.model.SnappedClip;
import com.BossAi.bossAi.service.podcast.model.TranscriptWord;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The boundary snapper is the core quality feature: a clip must never begin or
 * end mid-sentence. These tests pin that contract.
 */
class SentenceBoundarySnapperTest {

    private final SentenceBoundarySnapper snapper = new SentenceBoundarySnapper();

    @Test
    @DisplayName("approximate in/out points expand outward to whole sentences")
    void snapsToWholeSentences() {
        DiarizedTranscript t = TranscriptFixture.episode();

        // Director eyeballed a start mid-sentence B ("Hold on.") and an end
        // mid-sentence D ("Sure, it works.").
        SelectedMoment moment = new SelectedMoment(2600, 5600, "Title", "why", 80);
        SnappedClip clip = snapper.snap(t, moment);

        assertNotNull(clip);
        // Start snapped back to the start of "Hold" (word 5, t=2500).
        assertEquals(2500, clip.startMs());
        assertEquals(5, clip.firstWordIndex());
        // End snapped forward to the end of "works." (word 11, t=5900).
        assertEquals(5900, clip.endMs());
        assertEquals(11, clip.lastWordIndex());

        // Never starts or ends mid-sentence.
        assertEquals("Hold", clip.words().get(0).word());
        assertTrue(clip.words().get(clip.words().size() - 1).endsSentence());
        assertEquals("works.", clip.words().get(clip.words().size() - 1).word());
    }

    @Test
    @DisplayName("a moment landing inside the first sentence keeps word 0 as the start")
    void snapsStartToBeginning() {
        DiarizedTranscript t = TranscriptFixture.episode();
        SelectedMoment moment = new SelectedMoment(700, 1800, "T", "r", 80);
        SnappedClip clip = snapper.snap(t, moment);

        assertNotNull(clip);
        assertEquals(0, clip.firstWordIndex());
        assertEquals("This", clip.words().get(0).word());
        // End expands to the end of "idea." (word 4).
        assertEquals("idea.", clip.words().get(clip.words().size() - 1).word());
    }

    @Test
    @DisplayName("with no punctuation at all, falls back to the word grid without crashing")
    void noPunctuationFallback() {
        List<TranscriptWord> words = List.of(
                new TranscriptWord("one", 0, 400, "S"),
                new TranscriptWord("two", 500, 900, "S"),
                new TranscriptWord("three", 1000, 1400, "S"),
                new TranscriptWord("four", 1500, 1900, "S")
        );
        DiarizedTranscript t = new DiarizedTranscript(words, "en", 1900);

        SnappedClip clip = snapper.snap(t, new SelectedMoment(400, 1100, "T", "r", 80));
        assertNotNull(clip);
        assertTrue(clip.startMs() <= clip.endMs());
        assertFalse(clip.words().isEmpty());
    }

    @Test
    @DisplayName("a long moment is capped to <=60s on a sentence boundary")
    void capsLongMomentToSentenceBoundary() {
        // 3-minute transcript: one sentence every ~5s (each ends with '.').
        List<TranscriptWord> words = new java.util.ArrayList<>();
        int idx = 0;
        for (int s = 0; s < 36; s++) {           // 36 sentences over 180s
            int base = s * 5000;
            words.add(new TranscriptWord("word" + idx++, base, base + 1500, "S"));
            words.add(new TranscriptWord("word" + idx++, base + 2000, base + 3500, "S"));
            words.add(new TranscriptWord("end" + s + ".", base + 4000, base + 4800, "S")); // sentence end
        }
        DiarizedTranscript t = new DiarizedTranscript(words, "en", 180_000);

        // Director asked for a 3-minute clip.
        SnappedClip clip = snapper.snap(t, new SelectedMoment(0, 180_000, "Long", "r", 80));

        assertNotNull(clip);
        assertTrue(clip.durationMs() <= SentenceBoundarySnapper.MAX_CLIP_MS,
                "clip must be capped to <=60s, was " + clip.durationMs() + "ms");
        // And it still ends on a whole sentence, not mid-sentence.
        assertTrue(clip.words().get(clip.words().size() - 1).endsSentence(),
                "capped clip must end on a sentence boundary");
    }

    @Test
    @DisplayName("null moment or empty transcript yields null, not an exception")
    void nullSafety() {
        assertNull(snapper.snap(TranscriptFixture.episode(), null));
        assertNull(snapper.snap(new DiarizedTranscript(List.of(), "en", 0),
                new SelectedMoment(0, 100, "t", "r", 80)));
    }
}
