package com.BossAi.bossAi.service.podcast;

import com.BossAi.bossAi.dto.edl.EdlAudioTrack;
import com.BossAi.bossAi.dto.edl.EdlDto;
import com.BossAi.bossAi.dto.edl.EdlSegment;
import com.BossAi.bossAi.dto.edl.EdlWhisperWord;
import com.BossAi.bossAi.service.podcast.model.DiarizedTranscript;
import com.BossAi.bossAi.service.podcast.model.SelectedMoment;
import com.BossAi.bossAi.service.podcast.model.SnappedClip;
import com.BossAi.bossAi.service.podcast.model.SpeakerTurn;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Runs the deterministic spine end to end on one hardcoded test episode:
 * speaker segmentation → (LLM stand-in) → sentence snap → per-clip EDL.
 * The only non-deterministic step (the LLM director) is represented by a fixed
 * {@link SelectedMoment}, so the whole chain is verifiable without any service.
 */
class PodcastPipelineChainTest {

    private final SpeakerTurnSegmenter segmenter = new SpeakerTurnSegmenter();
    private final SentenceBoundarySnapper snapper = new SentenceBoundarySnapper();
    private final ClipEdlBuilder edlBuilder = new ClipEdlBuilder();

    @Test
    @DisplayName("episode → speaker turns → snapped clip → renderable EDL")
    void fullChain() {
        DiarizedTranscript episode = TranscriptFixture.episode();

        // 1) Structure the transcript for the director.
        List<SpeakerTurn> turns = segmenter.segment(episode);
        assertEquals(3, turns.size());

        // 2) Director stand-in: a moment with rough, mid-sentence timestamps.
        SelectedMoment picked = new SelectedMoment(2600, 5600, "It works", "clear payoff");

        // 3) Deterministic sentence snap.
        SnappedClip clip = snapper.snap(episode, picked);
        assertNotNull(clip);
        assertEquals(2500, clip.startMs());
        assertEquals(5900, clip.endMs());

        // 4) Primary path: the clip window is pre-cut into its own small file, so
        // the EDL points at that file and plays it whole (trim 0..duration).
        String clipUrl = "https://cdn.example/clip-0-src.mp4";
        EdlDto edl = edlBuilder.buildPreCut(clip, clipUrl, null);

        assertEquals(1, edl.getSegments().size());
        EdlSegment seg = edl.getSegments().get(0);
        assertEquals("VIDEO", seg.getAssetType());
        assertEquals(clipUrl, seg.getAssetUrl());
        assertEquals(0, seg.getTrimInMs());
        assertEquals(3400, seg.getTrimOutMs()); // whole pre-cut file
        assertEquals(0, seg.getStartMs());
        assertEquals(3400, seg.getEndMs()); // clip-local duration

        // --- Speech routed through a full-volume voiceover track (video is muted) ---
        assertEquals(1, edl.getAudioTracks().size());
        EdlAudioTrack voice = edl.getAudioTracks().get(0);
        assertEquals("voiceover", voice.getType());
        assertEquals(clipUrl, voice.getAssetUrl());
        assertEquals(0, voice.getTrimInMs());
        assertEquals(3400, voice.getTrimOutMs());
        assertEquals(1.0, voice.getVolume(), 1e-9);

        // --- Fallback path: full source with a trim window into the episode ---
        EdlDto fallback = edlBuilder.build(clip, "https://cdn.example/episode.mp4", "asset-123");
        EdlSegment fseg = fallback.getSegments().get(0);
        assertEquals(2500, fseg.getTrimInMs());
        assertEquals(5900, fseg.getTrimOutMs());
        assertEquals(3400, fseg.getEndMs());

        // --- Captions: enabled, words rebased to clip-local time, sentence-grouped ---
        assertTrue(edl.getSubtitleConfig().isEnabled());
        List<EdlWhisperWord> words = edl.getWhisperWords();
        assertEquals(7, words.size()); // "Hold on. Explain that. Sure, it works."
        assertEquals("Hold", words.get(0).getWord());
        assertEquals(0, words.get(0).getStartMs()); // 2500 - 2500 == 0
        assertEquals("works.", words.get(words.size() - 1).getWord());

        // Sentence indices increment at each terminator.
        assertEquals(List.of(0, 0, 1, 1, 2, 2, 2),
                words.stream().map(EdlWhisperWord::getSentenceIndex).toList());

        // --- 9:16, duration matches the clip ---
        assertEquals(1080, edl.getMetadata().getWidth());
        assertEquals(1920, edl.getMetadata().getHeight());
        assertEquals(3400, edl.getMetadata().getTotalDurationMs());
    }
}
