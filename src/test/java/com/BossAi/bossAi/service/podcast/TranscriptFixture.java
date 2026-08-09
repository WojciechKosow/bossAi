package com.BossAi.bossAi.service.podcast;

import com.BossAi.bossAi.service.podcast.model.DiarizedTranscript;
import com.BossAi.bossAi.service.podcast.model.TranscriptWord;

import java.util.ArrayList;
import java.util.List;

/**
 * A tiny hand-built diarized transcript standing in for a real episode, so the
 * deterministic pipeline (segmentation → snap → EDL) can be exercised without
 * WhisperX. Words are laid out on a fixed 500 ms grid (start = i*500, end = start+400).
 *
 * <pre>
 *   i  word      speaker      ends-sentence
 *   0  This      SPEAKER_00
 *   1  is        SPEAKER_00
 *   2  the       SPEAKER_00
 *   3  big       SPEAKER_00
 *   4  idea.     SPEAKER_00   yes
 *   5  Hold      SPEAKER_01
 *   6  on.       SPEAKER_01   yes
 *   7  Explain   SPEAKER_01
 *   8  that.     SPEAKER_01   yes
 *   9  Sure,     SPEAKER_00
 *  10  it        SPEAKER_00
 *  11  works.    SPEAKER_00   yes
 *  12  Trust     SPEAKER_00
 *  13  me.       SPEAKER_00   yes
 * </pre>
 */
final class TranscriptFixture {

    static final String S0 = "SPEAKER_00";
    static final String S1 = "SPEAKER_01";

    private TranscriptFixture() {}

    static DiarizedTranscript episode() {
        String[][] script = {
                {"This", S0}, {"is", S0}, {"the", S0}, {"big", S0}, {"idea.", S0},
                {"Hold", S1}, {"on.", S1}, {"Explain", S1}, {"that.", S1},
                {"Sure,", S0}, {"it", S0}, {"works.", S0}, {"Trust", S0}, {"me.", S0},
        };
        List<TranscriptWord> words = new ArrayList<>();
        for (int i = 0; i < script.length; i++) {
            int start = i * 500;
            words.add(new TranscriptWord(script[i][0], start, start + 400, script[i][1]));
        }
        int durationMs = words.get(words.size() - 1).endMs();
        return new DiarizedTranscript(words, "en", durationMs);
    }
}
