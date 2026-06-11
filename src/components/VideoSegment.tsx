import React, { useEffect, useState } from "react";
import {
  useCurrentFrame,
  useVideoConfig,
  OffthreadVideo,
  Img,
  Sequence,
  interpolate,
  delayRender,
  continueRender,
} from "remotion";
import { getVideoMetadata, getImageDimensions } from "@remotion/media-utils";
import type { Segment, Effect, Transition } from "../types/edl";
import { resolveFraming } from "../utils/framing";
import { autoKenBurnsParams } from "../utils/deterministic";
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
    case "grain_overlay":
      // GrainOverlay renders on top of the element rather than wrapping it
      return (
        <>
          {element}
          <GrainOverlay intensity={effect.intensity ?? (p.intensity as number)} />
        </>
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

/**
 * Measures the source asset's dimensions so "auto" framing can decide between
 * cover and blur_fill. Blocks the render (delayRender) until measured; on
 * failure resolves to null and framing falls back to cover.
 */
function useAssetDimensions(
  src: string,
  assetType: Segment["asset_type"],
  enabled: boolean
): { width: number; height: number } | null {
  const [dims, setDims] = useState<{ width: number; height: number } | null>(
    null
  );
  const [handle] = useState(() =>
    enabled ? delayRender(`Measuring asset dimensions: ${src}`) : null
  );

  useEffect(() => {
    if (!enabled || handle === null) return;
    let cancelled = false;
    const measured =
      assetType === "VIDEO"
        ? getVideoMetadata(src).then((m) => ({ width: m.width, height: m.height }))
        : getImageDimensions(src).then((d) => ({ width: d.width, height: d.height }));
    measured
      .then((d) => {
        if (!cancelled) setDims(d);
      })
      .catch(() => {
        // Fall back to cover framing rather than failing the render.
      })
      .finally(() => continueRender(handle));
    return () => {
      cancelled = true;
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [src, assetType, enabled, handle]);

  return dims;
}

const MEDIA_FILL_STYLE: React.CSSProperties = {
  width: "100%",
  height: "100%",
};

/** Linear speed ramp: integrated source-frame offset at the given output frame. */
function speedRampSourceOffset(
  frame: number,
  durationInFrames: number,
  speedFrom: number,
  speedTo: number
): number {
  const span = Math.max(durationInFrames - 1, 1);
  return frame * speedFrom + ((speedTo - speedFrom) * frame * frame) / (2 * span);
}

function clampSpeed(value: unknown, fallback: number): number {
  const n = typeof value === "number" && Number.isFinite(value) ? value : fallback;
  return Math.min(Math.max(n, 0.1), 8);
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
  const trimOut = segment.trim_out_ms
    ? Math.round((segment.trim_out_ms / 1000) * fps)
    : undefined;

  // "auto" framing measures the asset and blur-fills wide (e.g. 16:9) sources
  // instead of silently center-cropping them into the 9:16 frame.
  const { width: frameWidth, height: frameHeight } = useVideoConfig();
  const needsMeasure = segment.framing === "auto";
  const dims = useAssetDimensions(
    segment.asset_url,
    segment.asset_type,
    needsMeasure
  );
  const framing = resolveFraming(
    segment.framing,
    dims?.width ?? null,
    dims?.height ?? null,
    frameWidth,
    frameHeight
  );

  const speedRamp = segment.effects?.find((e) => e.type === "speed_ramp");

  const renderMedia = (
    objectFit: "cover" | "contain",
    muted: boolean,
    extraStyle?: React.CSSProperties
  ): React.ReactNode => {
    const style: React.CSSProperties = {
      ...MEDIA_FILL_STYLE,
      objectFit,
      ...extraStyle,
    };
    if (segment.asset_type === "IMAGE") {
      return <Img src={segment.asset_url} style={style} />;
    }
    if (segment.asset_type !== "VIDEO") return null;

    if (speedRamp) {
      const p = (speedRamp.params ?? {}) as Record<string, unknown>;
      const speedFrom = clampSpeed(p.speed_from, 1);
      const speedTo = clampSpeed(p.speed_to, 1);
      const srcOffset = speedRampSourceOffset(
        frame,
        durationInFrames,
        speedFrom,
        speedTo
      );
      // Frame-remap technique: the Sequence restarts at the current frame so
      // startFrom selects the retimed source frame. Always muted — retimed
      // audio would stutter (segment audio belongs in audio_tracks anyway).
      return (
        <Sequence
          from={frame}
          durationInFrames={Math.max(durationInFrames - frame, 1)}
          layout="none"
        >
          <OffthreadVideo
            src={segment.asset_url}
            startFrom={trimIn + Math.round(srcOffset)}
            endAt={trimOut}
            muted
            style={style}
          />
        </Sequence>
      );
    }

    // OffthreadVideo decodes via the native compositor — frame-accurate and
    // independent of the headless browser's codec support.
    return (
      <OffthreadVideo
        src={segment.asset_url}
        startFrom={trimIn}
        endAt={trimOut}
        muted={muted}
        style={style}
        playbackRate={playbackRate}
      />
    );
  };

  let content: React.ReactNode;
  if (framing === "blur_fill") {
    content = (
      <div style={{ position: "absolute", inset: 0, overflow: "hidden" }}>
        <div
          style={{
            position: "absolute",
            inset: 0,
            transform: "scale(1.2)",
            filter: "blur(30px) brightness(0.65)",
          }}
        >
          {renderMedia("cover", true)}
        </div>
        <div style={{ position: "absolute", inset: 0 }}>
          {renderMedia("contain", false)}
        </div>
      </div>
    );
  } else {
    content = renderMedia(framing, false);
  }

  // A static image with no effect is a dead frame — give it a subtle,
  // deterministic Ken Burns drift instead.
  const effects = segment.effects ?? [];
  if (segment.asset_type === "IMAGE" && effects.length === 0) {
    const kb = autoKenBurnsParams(segment.id);
    content = (
      <KenBurns
        scaleFrom={kb.scaleFrom}
        scaleTo={kb.scaleTo}
        panDirection={kb.panDirection}
      >
        {content}
      </KenBurns>
    );
  }

  for (const effect of effects) {
    content = wrapWithEffect(content, effect, bpm);
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
