import React from "react";
import { useCurrentFrame, useVideoConfig, interpolate, Easing } from "remotion";

interface PanProps {
  children: React.ReactNode;
  direction: "left" | "right" | "up" | "down";
  distancePercent?: number;
  easing?: string;
}

export const Pan: React.FC<PanProps> = ({
  children,
  direction,
  distancePercent = 15,
  easing = "linear",
}) => {
  const frame = useCurrentFrame();
  const { durationInFrames } = useVideoConfig();

  const easingFn = easing === "linear" ? Easing.linear : Easing.inOut(Easing.cubic);

  const progress = interpolate(frame, [0, durationInFrames], [0, 1], {
    extrapolateRight: "clamp",
    easing: easingFn,
  });

  let translateX = 0;
  let translateY = 0;
  const d = distancePercent;

  switch (direction) {
    case "left":
      translateX = interpolate(progress, [0, 1], [0, -d]);
      break;
    case "right":
      translateX = interpolate(progress, [0, 1], [0, d]);
      break;
    case "up":
      translateY = interpolate(progress, [0, 1], [0, -d]);
      break;
    case "down":
      translateY = interpolate(progress, [0, 1], [0, d]);
      break;
  }

  // Slight zoom to avoid edges
  const scale = 1 + (distancePercent / 100) * 0.5;

  return (
    <div style={{ width: "100%", height: "100%", overflow: "hidden" }}>
      <div
        style={{
          width: "100%",
          height: "100%",
          transform: `scale(${scale}) translate(${translateX}%, ${translateY}%)`,
          transformOrigin: "center center",
        }}
      >
        {children}
      </div>
    </div>
  );
};
