import React from "react";
import {
  useCurrentFrame,
  useVideoConfig,
  Video,
  Img,
  interpolate,
} from "remotion";
import type { Segment, Effect, Transition } from "../types/edl";
import { ZoomIn } from "../effects/ZoomIn";
import { ZoomOut } from "../effects/ZoomOut";
import { FastZoom } from "../effects/FastZoom";
import { Pan } from "../effects/Pan";
import { ShakeOnBeat } from "../effects/ShakeOnBeat";
import { ZoomPulse } from "../effects/ZoomPulse";
import { KenBurns } from "../effects/KenBurns";
import { GlitchEffect } from "../effects/GlitchEffect";
import { Flash } from "../effects/Flash";
import { Bounce } from "../effects/Bounce";
import { Drift } from "../effects/Drift";
import { ZoomInOffset } from "../effects/ZoomInOffset";
import { GrainOverlay } from "../effects/GrainOverlay";

interface VideoSegmentProps {
  segment: Segment;
  bpm?: number;
}

function wrapWithEffect(
  element: React.ReactNode,
  effect: Effect,
  bpm?: number
): React.ReactNode {
  const p = (effect.params ?? {}) as Record<string, unknown>;

  switch (effect.type) {
    case "zoom_in":
      return (
        <ZoomIn
          scaleFrom={p.scale_from as number}
          scaleTo={p.scale_to as number}
          easing={p.easing as string}
        >
          {element}
        </ZoomIn>
      );
    case "zoom_out":
      return (
        <ZoomOut
          scaleFrom={p.scale_from as number}
          scaleTo={p.scale_to as number}
          easing={p.easing as string}
        >
          {element}
        </ZoomOut>
      );
    case "fast_zoom":
      return (
        <FastZoom
          scaleFrom={p.scale_from as number}
          scaleTo={p.scale_to as number}
          durationMs={p.duration_ms as number}
          easing={p.easing as string}
        >
          {element}
        </FastZoom>
      );
    case "pan_left":
      return (
        <Pan
          direction="left"
          distancePercent={p.distance_percent as number}
          easing={p.easing as string}
        >
          {element}
        </Pan>
      );
    case "pan_right":
      return (
        <Pan
          direction="right"
          distancePercent={p.distance_percent as number}
          easing={p.easing as string}
        >
          {element}
        </Pan>
      );
    case "pan_up":
      return (
        <Pan
          direction="up"
          distancePercent={p.distance_percent as number}
          easing={p.easing as string}
        >
          {element}
        </Pan>
      );
    case "pan_down":
      return (
        <Pan
          direction="down"
          distancePercent={p.distance_percent as number}
          easing={p.easing as string}
        >
          {element}
        </Pan>
      );
    case "shake":
      return (
        <ShakeOnBeat
          amplitude={p.amplitude as number}
          frequency={p.frequency as number}
        >
          {element}
        </ShakeOnBeat>
      );
    case "zoom_pulse":
      return (
        <ZoomPulse
          scale={p.scale as number}
          frequencyBpm={p.frequency_bpm as number}
        >
          {element}
        </ZoomPulse>
      );
    case "ken_burns":
      return (
        <KenBurns
          scaleFrom={p.scale_from as number}
          scaleTo={p.scale_to as number}
          panDirection={p.pan_direction as "left" | "right" | "up" | "down"}
        >
          {element}
        </KenBurns>
      );
    case "glitch":
      return (
        <GlitchEffect
          intensity={effect.intensity ?? (p.intensity as number)}
          rgbSplit={(p.frequency as number) ?? 5}
        >
          {element}
        </GlitchEffect>
      );
    case "flash":
      return (
        <Flash
          opacity={p.opacity as number}
          durationMs={p.duration_ms as number}
        >
          {element}
        </Flash>
      );
    case "bounce":
      return (
        <Bounce
          scalePeak={p.scale_peak as number}
          easing={p.easing as string}
          bpm={bpm}
        >
          {element}
        </Bounce>
      );
    case "drift":
      return (
        <Drift
          direction={p.direction as "diagonal" | "horizontal" | "vertical"}
          distancePercent={p.distance_percent as number}
          easing={p.easing as string}
        >
          {element}
        </Drift>
      );
    case "zoom_in_offset":
      return (
        <ZoomInOffset
          scaleFrom={p.scale_from as number}
          scaleTo={p.scale_to as number}
          offsetX={p.offset_x as number}
          offsetY={p.offset_y as number}
          easing={p.easing as string}
        >
          {element}
        </ZoomInOffset>
      );
    case "slow_motion":
    case "speed_ramp":
      // Handled at Video playbackRate level
      return element;
    default:
      return element;
  }
}

function getPlaybackRate(effects?: Effect[]): number {
  if (!effects) return 1;
  for (const e of effects) {
    if (e.type === "slow_motion") {
      const p = (e.params ?? {}) as Record<string, unknown>;
      return (p.speed as number) ?? 0.5;
    }
  }
  return 1;
}

function applyTransition(
  frame: number,
  durationInFrames: number,
  fps: number,
  transition?: Transition | null
): { opacity: number; transform: string; filter: string } {
  let opacity = 1;
  let transform = "";
  let filter = "";

  if (!transition || transition.type === "cut") {
    return { opacity, transform, filter };
  }

  const dur = transition.duration_ms
    ? Math.round((transition.duration_ms / 1000) * fps)
    : Math.round(0.3 * fps);

  // Transition OUT at the end of this segment (transition belongs to this segment)
  const outStart = durationInFrames - dur;

  if (frame >= outStart) {
    const progress = interpolate(frame, [outStart, durationInFrames], [0, 1], {
      extrapolateLeft: "clamp",
      extrapolateRight: "clamp",
    });

    switch (transition.type) {
      case "fade":
      case "fade_black":
      case "dissolve":
        opacity = 1 - progress;
        break;
      case "fade_white":
        // White overlay handled separately
        opacity = 1;
        break;
      case "wipe_left":
        transform = `translateX(${-progress * 100}%)`;
        break;
      case "wipe_right":
        transform = `translateX(${progress * 100}%)`;
        break;
      case "slide_left":
        transform = `translateX(${-progress * 100}%)`;
        break;
      case "slide_right":
        transform = `translateX(${progress * 100}%)`;
        break;
    }
  }

  return { opacity, transform, filter };
}

export const VideoSegment: React.FC<VideoSegmentProps> = ({
  segment,
  bpm,
}) => {
  const frame = useCurrentFrame();
  const { durationInFrames, fps } = useVideoConfig();

  const { opacity, transform, filter } = applyTransition(
    frame,
    durationInFrames,
    fps,
    segment.transition
  );

  // White flash for fade_white transition
  let whiteOpacity = 0;
  if (segment.transition?.type === "fade_white" && segment.transition.duration_ms) {
    const dur = Math.round((segment.transition.duration_ms / 1000) * fps);
    const outStart = durationInFrames - dur;
    if (frame >= outStart) {
      whiteOpacity = interpolate(frame, [outStart, durationInFrames], [0, 1], {
        extrapolateLeft: "clamp",
        extrapolateRight: "clamp",
      });
    }
  }

  const playbackRate = getPlaybackRate(segment.effects);

  // Render asset
  let content: React.ReactNode;
  const trimIn = segment.trim_in_ms
    ? Math.round((segment.trim_in_ms / 1000) * fps)
    : 0;

  if (segment.asset_type === "VIDEO") {
    content = (
      <Video
        src={segment.asset_url}
        startFrom={trimIn}
        style={{ width: "100%", height: "100%", objectFit: "cover" }}
        playbackRate={playbackRate}
      />
    );
  } else if (segment.asset_type === "IMAGE") {
    content = (
      <Img
        src={segment.asset_url}
        style={{ width: "100%", height: "100%", objectFit: "cover" }}
      />
    );
  } else {
    content = null;
  }

  // Apply visual effects
  if (segment.effects) {
    for (const effect of segment.effects) {
      content = wrapWithEffect(content, effect, bpm);
    }
  }

  return (
    <div
      style={{
        position: "absolute",
        top: 0,
        left: 0,
        width: "100%",
        height: "100%",
        opacity,
        transform: transform || undefined,
        filter: filter || undefined,
      }}
    >
      {content}
      {whiteOpacity > 0 && (
        <div
          style={{
            position: "absolute",
            top: 0,
            left: 0,
            width: "100%",
            height: "100%",
            backgroundColor: "#FFFFFF",
            opacity: whiteOpacity,
            pointerEvents: "none",
          }}
        />
      )}
    </div>
  );
};
