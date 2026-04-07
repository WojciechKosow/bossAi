import React from "react";
import { useCurrentFrame, useVideoConfig, interpolate, Easing } from "remotion";

interface ZoomOutProps {
  children: React.ReactNode;
  scaleFrom?: number;
  scaleTo?: number;
  easing?: string;
}

export const ZoomOut: React.FC<ZoomOutProps> = ({
  children,
  scaleFrom = 1.3,
  scaleTo = 1.0,
  easing = "ease_out",
}) => {
  const frame = useCurrentFrame();
  const { durationInFrames } = useVideoConfig();

  const easingFn = easing === "linear" ? Easing.linear : Easing.out(Easing.cubic);

  const scale = interpolate(frame, [0, durationInFrames], [scaleFrom, scaleTo], {
    extrapolateRight: "clamp",
    easing: easingFn,
  });

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
