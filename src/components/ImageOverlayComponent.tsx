import React from "react";
import { Img, interpolate, useCurrentFrame, useVideoConfig } from "remotion";
import type { Segment } from "../types/edl";

interface Props {
  segment: Segment;
}

const ANIMATION_DURATION_FRAMES = 9; // ~300ms at 30fps

function computeAnimationStyle(
  frame: number,
  animationIn: string | null | undefined,
  opacity: number
): React.CSSProperties {
  if (!animationIn) return { opacity };

  const progress = interpolate(
    frame,
    [0, ANIMATION_DURATION_FRAMES],
    [0, 1],
    { extrapolateLeft: "clamp", extrapolateRight: "clamp" }
  );

  const currentOpacity = interpolate(progress, [0, 1], [0, opacity], {
    extrapolateLeft: "clamp",
    extrapolateRight: "clamp",
  });

  switch (animationIn) {
    case "fade_in":
      return { opacity: currentOpacity };

    case "slide_up": {
      const translateY = interpolate(progress, [0, 1], [20, 0], {
        extrapolateLeft: "clamp",
        extrapolateRight: "clamp",
      });
      return { opacity: currentOpacity, transform: `translateY(${translateY}px)` };
    }

    case "slide_left": {
      const translateX = interpolate(progress, [0, 1], [20, 0], {
        extrapolateLeft: "clamp",
        extrapolateRight: "clamp",
      });
      return { opacity: currentOpacity, transform: `translateX(${translateX}px)` };
    }

    case "zoom_in": {
      const scale = interpolate(progress, [0, 1], [0.7, 1], {
        extrapolateLeft: "clamp",
        extrapolateRight: "clamp",
      });
      return { opacity: currentOpacity, transform: `scale(${scale})` };
    }

    default:
      return { opacity: currentOpacity };
  }
}

/**
 * Renders a static image as a positioned overlay on the video frame.
 * Uses segment.x / y / width / height (normalized 0–1) to place the image.
 * Supports entrance animations: fade_in, slide_up, slide_left, zoom_in.
 */
export const ImageOverlayComponent: React.FC<Props> = ({ segment }) => {
  const frame = useCurrentFrame();
  const { width: frameWidth, height: frameHeight } = useVideoConfig();

  const x = (segment.x ?? 0) * frameWidth;
  const y = (segment.y ?? 0) * frameHeight;
  const w = (segment.width ?? 1) * frameWidth;
  const h = (segment.height ?? 1) * frameHeight;
  const opacity = segment.opacity ?? 1;

  const animStyle = computeAnimationStyle(frame, segment.animation_in, opacity);

  return (
    <div
      style={{
        position: "absolute",
        left: x,
        top: y,
        width: w,
        height: h,
        transformOrigin: "center center",
        ...animStyle,
      }}
    >
      <Img
        src={segment.asset_url}
        style={{
          width: "100%",
          height: "100%",
          objectFit: "contain",
        }}
      />
    </div>
  );
};
