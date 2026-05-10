import React from "react";
import { useCurrentFrame, useVideoConfig, interpolate, Easing } from "remotion";

interface WhipPanProps {
  children: React.ReactNode;
  direction?: "left" | "right";
  distancePercent?: number;
  blurAmount?: number;
  durationMs?: number;
}

/**
 * WhipPan — ekstremalny pan (domyślnie 55% szerokości) z motion blur.
 * Klip "wpada" z boku na ekran w ciągu ~140ms — sygnatura zmiany sceny.
 * Blur narasta do połowy panu, potem opada → realistyczny motion blur.
 * direction: "right" = content wpada z lewej strony (przesuwa się w prawo do centrum)
 */
export const WhipPan: React.FC<WhipPanProps> = ({
  children,
  direction = "right",
  distancePercent = 55,
  blurAmount = 22,
  durationMs = 140,
}) => {
  const frame = useCurrentFrame();
  const { durationInFrames, fps } = useVideoConfig();

  const whipFrames = Math.max(1, Math.round((durationMs / 1000) * fps));

  // Translate: from +/-distance% to 0 over whipFrames, then hold at 0
  const rawProgress = interpolate(frame, [0, whipFrames], [1, 0], {
    extrapolateLeft: "clamp",
    extrapolateRight: "clamp",
    easing: Easing.out(Easing.exp),
  });

  const translate = direction === "right"
    ? -distancePercent * rawProgress   // content comes from left
    : distancePercent * rawProgress;   // content comes from right

  // Motion blur peaks at midpoint of whip, zero at start and end
  const blurProgress = interpolate(frame, [0, whipFrames / 2, whipFrames], [0, 1, 0], {
    extrapolateLeft: "clamp",
    extrapolateRight: "clamp",
  });
  const blur = blurAmount * blurProgress;

  return (
    <div style={{ width: "100%", height: "100%", overflow: "hidden" }}>
      <div
        style={{
          width: "100%",
          height: "100%",
          transform: `translateX(${translate.toFixed(2)}%) scale(1.1)`,
          filter: blur > 0.5 ? `blur(${blur.toFixed(1)}px)` : undefined,
        }}
      >
        {children}
      </div>
    </div>
  );
};
