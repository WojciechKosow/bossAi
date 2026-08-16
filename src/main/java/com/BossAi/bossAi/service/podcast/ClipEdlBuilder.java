package com.BossAi.bossAi.service.podcast;

import com.BossAi.bossAi.dto.edl.*;
import com.BossAi.bossAi.service.podcast.model.SnappedClip;
import com.BossAi.bossAi.service.podcast.model.TranscriptWord;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * Builds a per-clip {@link EdlDto} for the podcast → clips pipeline.
 *
 * <p>Each clip is a single-source subclip of the episode:
 * <ul>
 *   <li>one VIDEO segment pointing at the source episode, trimmed to the clip's
 *       sentence-snapped window (the renderer mutes segment video audio);</li>
 *   <li>one voiceover audio track pointing at the SAME source and window at full
 *       volume — this is where the podcast speech actually comes from;</li>
 *   <li>whisper words rebased to clip-local time with per-sentence grouping, and
 *       a subtitle config so captions are burned in.</li>
 * </ul>
 *
 * <p>When an active-speaker <b>reframe track</b> is supplied (from the audio
 * service's {@code /api/v1/reframe} face analyzer), it is attached to the video
 * segment and the segment's {@code framing} is set to {@code "reframe"} so the
 * renderer crops-to-fill the 9:16 frame following the talking face. Without a
 * track the segment keeps the renderer's {@code "auto"} default (blur-fill of a
 * 16:9 source) — a safe fallback that preserves the pre-reframe look.
 */
@Service
public class ClipEdlBuilder {

    private static final int WIDTH = 1080;
    private static final int HEIGHT = 1920;
    private static final int FPS = 30;

    /**
     * Builds an EDL that references the FULL source episode and trims to the
     * clip's window. Used as a fallback when pre-cutting the subclip fails.
     *
     * @param clip          the sentence-snapped clip
     * @param sourceAssetUrl URL the renderer can fetch the source episode from
     * @param sourceAssetId  optional asset id for the source (may be null)
     */
    public EdlDto build(SnappedClip clip, String sourceAssetUrl, String sourceAssetId) {
        return build(clip, sourceAssetUrl, sourceAssetId, null);
    }

    /**
     * Full-source variant with an active-speaker reframe track. See
     * {@link #build(SnappedClip, String, String)}.
     */
    public EdlDto build(SnappedClip clip, String sourceAssetUrl, String sourceAssetId,
                        List<EdlReframeKeyframe> reframeTrack) {
        return build(clip, sourceAssetUrl, sourceAssetId, clip.startMs(), clip.endMs(), reframeTrack);
    }

    /**
     * Builds an EDL for a clip whose window has already been cut out of the
     * source into its own small file (see {@code AudioExtractor.cutSubclip}).
     * The renderer fetches a ~1-minute file and plays it whole (trim 0..duration)
     * instead of range-seeking the multi-hour source.
     *
     * @param clip        the sentence-snapped clip (for captions + duration)
     * @param clipUrl     URL of the pre-cut subclip file
     * @param clipAssetId optional asset id for the subclip (may be null)
     */
    public EdlDto buildPreCut(SnappedClip clip, String clipUrl, String clipAssetId) {
        return buildPreCut(clip, clipUrl, clipAssetId, null);
    }

    /**
     * Pre-cut variant with an active-speaker reframe track. The track's keyframe
     * times are clip-local (0 == clip start), which matches the pre-cut file's
     * timeline exactly. See {@link #buildPreCut(SnappedClip, String, String)}.
     */
    public EdlDto buildPreCut(SnappedClip clip, String clipUrl, String clipAssetId,
                              List<EdlReframeKeyframe> reframeTrack) {
        return build(clip, clipUrl, clipAssetId, 0, clip.durationMs(), reframeTrack);
    }

    private EdlDto build(SnappedClip clip, String assetUrl, String assetId,
                         int trimInMs, int trimOutMs, List<EdlReframeKeyframe> reframeTrack) {
        int durationMs = clip.durationMs();

        boolean hasReframe = reframeTrack != null && !reframeTrack.isEmpty();
        EdlSegment segment = EdlSegment.builder()
                .id("clip-seg-0")
                .assetId(assetId)
                .assetUrl(assetUrl)
                .assetType("VIDEO")
                .startMs(0)
                .endMs(durationMs)
                .trimInMs(trimInMs)
                .trimOutMs(trimOutMs)
                .layer(0)
                // Face-tracking crop-to-fill when a track is present; otherwise
                // leave framing null so the renderer keeps its "auto" default.
                .framing(hasReframe ? "reframe" : null)
                .reframe(hasReframe ? reframeTrack : null)
                .build();

        // The podcast speech: same file + window, full volume. The video segment
        // is muted by the renderer, so this track carries the audio.
        EdlAudioTrack speech = EdlAudioTrack.builder()
                .id("clip-voice-0")
                .assetId(assetId)
                .assetUrl(assetUrl)
                .type("voiceover")
                .startMs(0)
                .endMs(durationMs)
                .volume(1.0)
                .trimInMs(trimInMs)
                .trimOutMs(trimOutMs)
                .build();

        List<EdlWhisperWord> whisperWords = toClipLocalWords(clip);

        EdlMetadata metadata = EdlMetadata.builder()
                .title(clip.title())
                .style("podcast_clip")
                .totalDurationMs(durationMs)
                .width(WIDTH)
                .height(HEIGHT)
                .fps(FPS)
                .build();

        EdlSubtitleConfig subtitles = EdlSubtitleConfig.builder()
                .enabled(true)
                .position("center")
                .highlightMode("word")
                // TikTok-style: a few words on screen at a time, current one
                // highlighted (the renderer chunks whisper words by this cap).
                .maxWordsPerGroup(4)
                .build();

        return EdlDto.builder()
                .version(EdlDto.CURRENT_VERSION)
                .metadata(metadata)
                .segments(List.of(segment))
                .audioTracks(List.of(speech))
                .subtitleConfig(subtitles)
                .whisperWords(whisperWords)
                .build();
    }

    /**
     * Rebases the clip's words from source-absolute time to clip-local time
     * (0 == clip start) and assigns a sentence index that increments at each
     * sentence terminator — the renderer groups on-screen captions by sentence.
     */
    private List<EdlWhisperWord> toClipLocalWords(SnappedClip clip) {
        List<EdlWhisperWord> out = new ArrayList<>(clip.words().size());
        int base = clip.startMs();
        int sentenceIndex = 0;
        for (TranscriptWord w : clip.words()) {
            int start = Math.max(0, w.startMs() - base);
            int end = Math.max(start, w.endMs() - base);
            out.add(EdlWhisperWord.builder()
                    .word(w.word())
                    .startMs(start)
                    .endMs(end)
                    .sentenceIndex(sentenceIndex)
                    .build());
            if (w.endsSentence()) {
                sentenceIndex++;
            }
        }
        return out;
    }
}
