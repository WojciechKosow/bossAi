import React from "react";
import { AbsoluteFill, Sequence } from "remotion";
import type { Edl, ColorGrade } from "../types/edl";
import { parseEdlToTimeline } from "../utils/edl-parser";
import { VideoSegment } from "../components/VideoSegment";
import { TextOverlayComponent } from "../components/TextOverlay";
import { AudioTrackComponent } from "../components/AudioTrackComponent";
import { SubtitleTrack } from "../components/SubtitleTrack";

export interface TikTokVideoProps {
  edl: Edl;
}

/**
 * Builds a CSS filter string from EditDna color_grade values.
 * Falls back to identity (no change) when values are at defaults.
 */
function buildColorGradeFilter(grade?: ColorGrade): string {
  if (!grade) return "none";

  const parts: string[] = [];

  if (grade.brightness !== undefined && grade.brightness !== 1.0) {
    parts.push(`brightness(${grade.brightness})`);
  }
  if (grade.contrast_boost !== undefined && grade.contrast_boost !== 1.0) {
    parts.push(`contrast(${grade.contrast_boost})`);
  }
  if (grade.saturation !== undefined && grade.saturation !== 1.0) {
    parts.push(`saturate(${grade.saturation})`);
  }

  return parts.length > 0 ? parts.join(" ") : "none";
}

/**
 * Builds a vignette overlay gradient (radial, dark edges).
 */
function buildVignetteStyle(
  grade?: ColorGrade
): React.CSSProperties | null {
  if (!grade || !grade.vignette || grade.vignette <= 0) return null;

  const intensity = Math.min(grade.vignette, 1.0);
  const alpha = (intensity * 0.7).toFixed(2);

  return {
    position: "absolute",
    top: 0,
    left: 0,
    width: "100%",
    height: "100%",
    background: `radial-gradient(ellipse at center, transparent 50%, rgba(0,0,0,${alpha}) 100%)`,
    pointerEvents: "none",
    zIndex: 10,
  };
}

export const TikTokVideo: React.FC<TikTokVideoProps> = ({ edl }) => {
  const timeline = parseEdlToTimeline(edl);

  const subtitleConfig = edl.subtitle_config;
  const whisperWords = edl.whisper_words;
  const colorGrade = edl.metadata.color_grade;

  const colorFilter = buildColorGradeFilter(colorGrade);
  const vignetteStyle = buildVignetteStyle(colorGrade);

  return (
    <AbsoluteFill style={{ backgroundColor: "#000" }}>
      {/* Color-graded video layer */}
      <AbsoluteFill
        style={{
          filter: colorFilter !== "none" ? colorFilter : undefined,
        }}
      >
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
      </AbsoluteFill>

      {/* Vignette overlay */}
      {vignetteStyle && <div style={vignetteStyle} />}

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

      {/* Text overlays (titles, section headers — NOT subtitles) */}
      {timeline.textOverlays.map((overlay) => (
        <Sequence
          key={overlay.id}
          from={overlay.from}
          durationInFrames={overlay.durationInFrames}
        >
          <TextOverlayComponent overlay={overlay.overlay} />
        </Sequence>
      ))}

      {/* Subtitles: sentence-by-sentence with word-level karaoke highlight */}
      {subtitleConfig?.enabled && whisperWords && whisperWords.length > 0 && (
        <Sequence from={0} durationInFrames={timeline.durationInFrames}>
          <SubtitleTrack config={subtitleConfig} words={whisperWords} />
        </Sequence>
      )}
    </AbsoluteFill>
  );
};
