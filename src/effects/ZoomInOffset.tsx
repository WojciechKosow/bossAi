import React from "react";
import { useCurrentFrame, useVideoConfig, interpolate, Easing } from "remotion";

interface ZoomInOffsetProps {
  children: React.ReactNode;
  scaleFrom?: number;
  scaleTo?: number;
  offsetX?: number;
  offsetY?: number;
  easing?: string;
}

export const ZoomInOffset: React.FC<ZoomInOffsetProps> = ({
  children,
  scaleFrom = 1.0,
  scaleTo = 1.5,
  offsetX = 0,
  offsetY = 0,
  easing = "ease_out",
}) => {
  const frame = useCurrentFrame();
  const { durationInFrames } = useVideoConfig();

  const easingFn = easing === "linear" ? Easing.linear : Easing.out(Easing.cubic);

  const scale = interpolate(frame, [0, durationInFrames], [scaleFrom, scaleTo], {
    extrapolateRight: "clamp",
    easing: easingFn,
  });

  const originX = 50 + offsetX;
  const originY = 50 + offsetY;

  return (
    <div style={{ width: "100%", height: "100%", overflow: "hidden" }}>
      <div
        style={{
          width: "100%",
          height: "100%",
          transform: `scale(${scale})`,
          transformOrigin: `${originX}% ${originY}%`,
        }}
      >
        {children}
      </div>
    </div>
  );
};
