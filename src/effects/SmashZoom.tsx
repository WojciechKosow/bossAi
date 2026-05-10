import React from "react";
import { useCurrentFrame, useVideoConfig, interpolate, Easing } from "remotion";

interface SmashZoomProps {
  children: React.ReactNode;
  scaleFrom?: number;
  scaleTo?: number;
  durationMs?: number;
}

/**
 * SmashZoom — ekstremalny snap zoom w pierwszych ~90ms.
 * Po snap-in lekki pullback do ~80% wartości szczytowej — daje poczucie
 * energii bez przyklejania na stałe do dużego przybliżenia.
 * Używany wyłącznie na hooku (stop-scroll w pierwszych klatkach).
 */
export const SmashZoom: React.FC<SmashZoomProps> = ({
  children,
  scaleFrom = 1.0,
  scaleTo = 2.2,
  durationMs = 90,
}) => {
  const frame = useCurrentFrame();
  const { durationInFrames, fps } = useVideoConfig();

  const snapFrames = Math.max(1, Math.round((durationMs / 1000) * fps));
  // Peak at snap, then ease back to ~85% of peak for natural feel
  const holdScale = 1.0 + (scaleTo - 1.0) * 0.85;

  let scale: number;
  if (frame <= snapFrames) {
    scale = interpolate(frame, [0, snapFrames], [scaleFrom, scaleTo], {
      extrapolateLeft: "clamp",
      extrapolateRight: "clamp",
      easing: Easing.out(Easing.exp),
    });
  } else {
    scale = interpolate(frame, [snapFrames, durationInFrames], [scaleTo, holdScale], {
      extrapolateLeft: "clamp",
      extrapolateRight: "clamp",
      easing: Easing.out(Easing.cubic),
    });
  }

  return (
    <div style={{ width: "100%", height: "100%", overflow: "hidden" }}>
      <div
        style={{
          width: "100%",
          height: "100%",
          transform: `scale(${scale})`,
          transformOrigin: "center center",
        }}
      >
        {children}
      </div>
    </div>
  );
};
