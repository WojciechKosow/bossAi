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

        // 4) Build the renderable EDL for this clip.
        String sourceUrl = "https://cdn.example/episode.mp4";
        EdlDto edl = edlBuilder.build(clip, sourceUrl, "asset-123");

        // --- One VIDEO segment, trimmed to the clip's source window ---
        assertEquals(1, edl.getSegments().size());
        EdlSegment seg = edl.getSegments().get(0);
        assertEquals("VIDEO", seg.getAssetType());
        assertEquals(sourceUrl, seg.getAssetUrl());
        assertEquals(2500, seg.getTrimInMs());
        assertEquals(5900, seg.getTrimOutMs());
        assertEquals(0, seg.getStartMs());
        assertEquals(3400, seg.getEndMs()); // clip-local duration

        // --- Speech routed through a full-volume voiceover track (video is muted) ---
        assertEquals(1, edl.getAudioTracks().size());
        EdlAudioTrack voice = edl.getAudioTracks().get(0);
        assertEquals("voiceover", voice.getType());
        assertEquals(sourceUrl, voice.getAssetUrl());
        assertEquals(2500, voice.getTrimInMs());
        assertEquals(5900, voice.getTrimOutMs());
        assertEquals(1.0, voice.getVolume(), 1e-9);

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
