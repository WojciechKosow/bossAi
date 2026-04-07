import React from "react";
import { useCurrentFrame, useVideoConfig, interpolate } from "remotion";

interface FlashProps {
  children: React.ReactNode;
  opacity?: number;
  durationMs?: number;
}

export const Flash: React.FC<FlashProps> = ({
  children,
  opacity = 0.8,
  durationMs = 100,
}) => {
  const frame = useCurrentFrame();
  const { fps } = useVideoConfig();

  const flashFrames = Math.round((durationMs / 1000) * fps);
  const flashOpacity = interpolate(frame, [0, flashFrames], [opacity, 0], {
    extrapolateLeft: "clamp",
    extrapolateRight: "clamp",
  });

  return (
    <div style={{ width: "100%", height: "100%", position: "relative" }}>
      {children}
      {frame <= flashFrames && (
        <div
          style={{
            position: "absolute",
            top: 0,
            left: 0,
            width: "100%",
            height: "100%",
            backgroundColor: "#FFFFFF",
            opacity: flashOpacity,
            pointerEvents: "none",
          }}
        />
      )}
    </div>
  );
};
