package com.BossAi.bossAi.service.podcast;

import com.BossAi.bossAi.service.podcast.model.DiarizedTranscript;
import com.BossAi.bossAi.service.podcast.model.SpeakerTurn;
import com.BossAi.bossAi.service.podcast.model.TranscriptWord;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SpeakerTurnSegmenterTest {

    private final SpeakerTurnSegmenter segmenter = new SpeakerTurnSegmenter();

    @Test
    @DisplayName("groups consecutive words by speaker into turns")
    void groupsBySpeaker() {
        List<SpeakerTurn> turns = segmenter.segment(TranscriptFixture.episode());

        assertEquals(3, turns.size());
        assertEquals(TranscriptFixture.S0, turns.get(0).speaker());
        assertEquals(TranscriptFixture.S1, turns.get(1).speaker());
        assertEquals(TranscriptFixture.S0, turns.get(2).speaker());

        assertEquals("This is the big idea.", turns.get(0).text());
        assertEquals(0, turns.get(0).firstWordIndex());
        assertEquals(4, turns.get(0).lastWordIndex());
        assertEquals(5, turns.get(1).firstWordIndex());
        assertEquals(8, turns.get(1).lastWordIndex());
    }

    @Test
    @DisplayName("a short backchannel interjection is merged into the surrounding speech")
    void mergesShortInterjection() {
        // Long S0 monologue, a 400ms "Yeah." from S1, then S0 continues.
        List<TranscriptWord> words = new ArrayList<>();
        // S0: 0..3999 (4s)
        for (int i = 0; i < 8; i++) {
            words.add(new TranscriptWord("w" + i, i * 500, i * 500 + 400, "S0"));
        }
        // S1 single short word at 4000..4400
        words.add(new TranscriptWord("Yeah.", 4000, 4400, "S1"));
        // S0 continues 4500..8499
        for (int i = 9; i < 17; i++) {
            words.add(new TranscriptWord("x" + i, i * 500, i * 500 + 400, "S0"));
        }
        DiarizedTranscript t = new DiarizedTranscript(words, "en", words.get(words.size() - 1).endMs());

        List<SpeakerTurn> turns = segmenter.segment(t);

        // The 400ms S1 interjection is below the 1500ms floor → absorbed. The two
        // S0 stretches around it coalesce into a single dominant turn.
        assertEquals(1, turns.size());
        assertEquals("S0", turns.get(0).speaker());
        assertEquals(0, turns.get(0).firstWordIndex());
        assertEquals(words.size() - 1, turns.get(0).lastWordIndex());
    }

    @Test
    @DisplayName("empty transcript yields no turns")
    void emptyTranscript() {
        assertTrue(segmenter.segment(new DiarizedTranscript(List.of(), "en", 0)).isEmpty());
    }
}
