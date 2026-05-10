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
import { SmashZoom } from "../effects/SmashZoom";
import { BlurTransition } from "../effects/BlurTransition";
import { BrightnessBurst } from "../effects/BrightnessBurst";
import { WhipPan } from "../effects/WhipPan";
import { ColorPop } from "../effects/ColorPop";
import { VignettePulse } from "../effects/VignettePulse";
import { RGBSplit } from "../effects/RGBSplit";

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
          bpm={bpm}
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
      // Handled at Video playbackRate level via getPlaybackRate()
      return element;
    case "smash_zoom":
      return (
        <SmashZoom
          scaleFrom={p.scale_from as number}
          scaleTo={p.scale_to as number}
          durationMs={p.duration_ms as number}
        >
          {element}
        </SmashZoom>
      );
    case "blur_transition":
      return (
        <BlurTransition
          blurAmount={p.blur_amount as number}
          durationMs={p.duration_ms as number}
          phase={p.phase as "intro" | "outro"}
        >
          {element}
        </BlurTransition>
      );
    case "brightness_burst":
      return (
        <BrightnessBurst
          brightnessDelta={p.brightness_delta as number}
          durationMs={p.duration_ms as number}
        >
          {element}
        </BrightnessBurst>
      );
    case "whip_pan":
      return (
        <WhipPan
          direction={p.direction as "left" | "right"}
          distancePercent={p.distance_percent as number}
          blurAmount={p.blur_amount as number}
          durationMs={p.duration_ms as number}
        >
          {element}
        </WhipPan>
      );
    case "color_pop":
      return (
        <ColorPop
          saturationBoost={p.saturation_boost as number}
          durationMs={p.duration_ms as number}
        >
          {element}
        </ColorPop>
      );
    case "vignette_pulse":
      return (
        <VignettePulse
          vignetteDelta={p.vignette_delta as number}
          durationMs={p.duration_ms as number}
        >
          {element}
        </VignettePulse>
      );
    case "rgb_split":
      return (
        <RGBSplit
          offsetPx={p.offset_px as number}
          durationMs={p.duration_ms as number}
        >
          {element}
        </RGBSplit>
      );
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
        // White overlay handled separately below
        opacity = 1;
        break;
      case "wipe_left":
        // Repurposed: scale-down + drift left + fade — was translateX(-100%) revealing black
        opacity = 1 - progress;
        transform = `translateX(${-progress * 15}%) scale(${1 - progress * 0.05})`;
        break;
      case "wipe_right":
        opacity = 1 - progress;
        transform = `translateX(${progress * 15}%) scale(${1 - progress * 0.05})`;
        break;
      case "slide_left":
        // Repurposed: blur + gentle drift left + fade — was translateX(-100%) revealing black
        opacity = interpolate(progress, [0, 0.6, 1], [1, 0.5, 0], {
          extrapolateLeft: "clamp",
          extrapolateRight: "clamp",
        });
        filter = `blur(${(progress * 7).toFixed(1)}px)`;
        transform = `translateX(${-progress * 8}%)`;
        break;
      case "slide_right":
        opacity = interpolate(progress, [0, 0.6, 1], [1, 0.5, 0], {
          extrapolateLeft: "clamp",
          extrapolateRight: "clamp",
        });
        filter = `blur(${(progress * 7).toFixed(1)}px)`;
        transform = `translateX(${progress * 8}%)`;
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

  // White flash overlay for fade_white transition
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

  const trimIn = segment.trim_in_ms
    ? Math.round((segment.trim_in_ms / 1000) * fps)
    : 0;

  let content: React.ReactNode;

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
