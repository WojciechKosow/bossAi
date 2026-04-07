import React from "react";
import { useCurrentFrame, useVideoConfig, interpolate, Easing } from "remotion";

interface DriftProps {
  children: React.ReactNode;
  direction?: "diagonal" | "horizontal" | "vertical";
  distancePercent?: number;
  easing?: string;
}

export const Drift: React.FC<DriftProps> = ({
  children,
  direction = "diagonal",
  distancePercent = 10,
  easing = "linear",
}) => {
  const frame = useCurrentFrame();
  const { durationInFrames } = useVideoConfig();

  const easingFn = easing === "linear" ? Easing.linear : Easing.inOut(Easing.cubic);

  const progress = interpolate(frame, [0, durationInFrames], [0, 1], {
    extrapolateRight: "clamp",
    easing: easingFn,
  });

  const d = distancePercent;
  let translateX = 0;
  let translateY = 0;

  switch (direction) {
    case "diagonal":
      translateX = progress * d * 0.7;
      translateY = progress * d * 0.7;
      break;
    case "horizontal":
      translateX = progress * d;
      break;
    case "vertical":
      translateY = progress * d;
      break;
  }

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
