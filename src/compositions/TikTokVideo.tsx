import React from "react";
import { AbsoluteFill, Sequence } from "remotion";
import type { Edl } from "../types/edl";
import { parseEdlToTimeline } from "../utils/edl-parser";
import { VideoSegment } from "../components/VideoSegment";
import { TextOverlayComponent } from "../components/TextOverlay";
import { AudioTrackComponent } from "../components/AudioTrackComponent";
import { SubtitleTrack } from "../components/SubtitleTrack";

export interface TikTokVideoProps {
  edl: Edl;
}

export const TikTokVideo: React.FC<TikTokVideoProps> = ({ edl }) => {
  const timeline = parseEdlToTimeline(edl);

  const subtitleConfig = edl.subtitle_config;
  const whisperWords = edl.whisper_words;

  return (
    <AbsoluteFill style={{ backgroundColor: "#000" }}>
      {/* Video/Image segments */}
      {timeline.segments.map((seg) => (
        <Sequence
          key={seg.id}
          from={seg.from}
          durationInFrames={seg.durationInFrames}
        >
          <VideoSegment segment={seg.segment} bpm={timeline.bpm} />
        </Sequence>
      ))}

      {/* Audio tracks */}
      {timeline.audioTracks.map((at) => (
        <Sequence
          key={at.id}
          from={at.from}
          durationInFrames={at.durationInFrames}
        >
          <AudioTrackComponent
            track={at.track}
            totalDurationInFrames={timeline.durationInFrames}
          />
        </Sequence>
      ))}

      {/* Text overlays */}
      {timeline.textOverlays.map((overlay) => (
        <Sequence
          key={overlay.id}
          from={overlay.from}
          durationInFrames={overlay.durationInFrames}
        >
          <TextOverlayComponent overlay={overlay.overlay} />
        </Sequence>
      ))}

      {/* Subtitles (karaoke highlight from Whisper word timestamps) */}
      {subtitleConfig?.enabled && whisperWords && whisperWords.length > 0 && (
        <Sequence from={0} durationInFrames={timeline.durationInFrames}>
          <SubtitleTrack config={subtitleConfig} words={whisperWords} />
        </Sequence>
      )}
    </AbsoluteFill>
  );
};
