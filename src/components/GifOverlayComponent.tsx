import React from "react";
import { Gif } from "@remotion/gif";
import { interpolate, useCurrentFrame, useVideoConfig } from "remotion";
import type { GifOverlay } from "../types/edl";

interface Props {
  overlay: GifOverlay;
}

function resolvePosition(position: string, gifWidth: number): React.CSSProperties {
  const halfWidth = gifWidth / 2;
  switch (position) {
    case "bottom_center":
      return { bottom: "5%", left: "50%", marginLeft: -halfWidth };
    case "bottom_left":
      return { bottom: "5%", left: "5%" };
    case "bottom_right":
      return { bottom: "5%", right: "5%" };
    case "top_right":
      return { top: "5%", right: "5%" };
    case "top_left":
      return { top: "5%", left: "5%" };
    case "center":
      return { top: "50%", left: "50%", marginLeft: -halfWidth, marginTop: -halfWidth };
    default:
      return { bottom: "5%", left: "50%", marginLeft: -halfWidth };
  }
}

export const GifOverlayComponent: React.FC<Props> = ({ overlay }) => {
  const frame = useCurrentFrame();
  const { fps, width } = useVideoConfig();

  const gifWidth = Math.round((overlay.scale ?? 0.5) * width);
  const fadeInFrames = Math.max(
    1,
    Math.round(((overlay.animation_in_duration_ms ?? 300) / 1000) * fps)
  );

  const opacity =
    overlay.animation_in === "fade_in"
      ? interpolate(frame, [0, fadeInFrames], [0, overlay.opacity ?? 1.0], {
          extrapolateLeft: "clamp",
          extrapolateRight: "clamp",
        })
      : (overlay.opacity ?? 1.0);

  return (
    <Gif
      src={overlay.url}
      width={gifWidth}
      height={gifWidth}
      fit="contain"
      style={{
        position: "absolute",
        opacity,
        ...resolvePosition(overlay.position ?? "bottom_center", gifWidth),
      }}
    />
  );
};
