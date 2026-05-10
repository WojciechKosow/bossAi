import React from "react";
import { AbsoluteFill, Sequence } from "remotion";
import type { Edl, ColorGrade } from "../types/edl";
import { parseEdlToTimeline, type RemotionSegment } from "../utils/edl-parser";
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

/**
 * Returns per-segment wrapper styles for multi-layer composition.
 *
 * layer < 0  = background (generated asset): dimmed, renders first in DOM
 * layer = 0  = primary (user asset): slightly transparent when background present
 * layer > 0  = overlay: semi-transparent, renders last
 *
 * DOM order determines z-stacking (last = on top), so segments must be
 * sorted ascending by layer before rendering.
 */
function layerWrapperStyle(
  seg: RemotionSegment,
  backgroundIntervals: ReadonlyArray<readonly [number, number]>
): React.CSSProperties | undefined {
  const { layer } = seg.segment;
  if (layer < 0) {
    // Background: dim so primary content stands out
    return { filter: "brightness(0.6)" };
  }
  if (layer === 0) {
    // Primary: if a background layer overlaps this scene, go slightly transparent
    // so the background is visible through the primary (double-exposure effect)
    const overlapsBackground = backgroundIntervals.some(
      ([start, end]) =>
        start < seg.from + seg.durationInFrames && end > seg.from
    );
    return overlapsBackground ? { opacity: 0.85 } : undefined;
  }
  // Overlay (layer > 0): semi-transparent so primary shows through
  return { opacity: 0.85 };
}

export const TikTokVideo: React.FC<TikTokVideoProps> = ({ edl }) => {
  const timeline = parseEdlToTimeline(edl);

  const subtitleConfig = edl.subtitle_config;
  const whisperWords = edl.whisper_words;
  const colorGrade = edl.metadata.color_grade;

  const colorFilter = buildColorGradeFilter(colorGrade);
  const vignetteStyle = buildVignetteStyle(colorGrade);

  // Sort segments ascending by layer so background renders before primary
  // (DOM order = z-stacking: first in DOM = visually behind).
  const sortedSegments = [...timeline.segments].sort(
    (a, b) => a.segment.layer - b.segment.layer
  );

  // Pre-compute background time intervals for primary opacity logic
  const backgroundIntervals = sortedSegments
    .filter((s) => s.segment.layer < 0)
    .map((s) => [s.from, s.from + s.durationInFrames] as const);

  return (
    <AbsoluteFill style={{ backgroundColor: "#000" }}>
      {/* Color-graded video layer */}
      <AbsoluteFill
        style={{
          filter: colorFilter !== "none" ? colorFilter : undefined,
        }}
      >
        {/* Video/Image segments — sorted by layer (background first, overlay last) */}
        {sortedSegments.map((seg) => {
          const wrapperStyle = layerWrapperStyle(seg, backgroundIntervals);
          return (
            <AbsoluteFill key={seg.id} style={wrapperStyle}>
              <Sequence from={seg.from} durationInFrames={seg.durationInFrames}>
                <VideoSegment segment={seg.segment} bpm={timeline.bpm} />
              </Sequence>
            </AbsoluteFill>
          );
        })}
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
