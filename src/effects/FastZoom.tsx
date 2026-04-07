import React from "react";
import { useCurrentFrame, useVideoConfig, interpolate, Easing } from "remotion";

interface FastZoomProps {
  children: React.ReactNode;
  scaleFrom?: number;
  scaleTo?: number;
  durationMs?: number;
  easing?: string;
}

export const FastZoom: React.FC<FastZoomProps> = ({
  children,
  scaleFrom = 1.0,
  scaleTo = 1.5,
  durationMs,
  easing = "ease_out",
}) => {
  const frame = useCurrentFrame();
  const { durationInFrames, fps } = useVideoConfig();

  const effectDuration = durationMs
    ? Math.round((durationMs / 1000) * fps)
    : durationInFrames;

  const easingFn = easing === "linear" ? Easing.linear : Easing.out(Easing.exp);

  const scale = interpolate(frame, [0, effectDuration], [scaleFrom, scaleTo], {
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
