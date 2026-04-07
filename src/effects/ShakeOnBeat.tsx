import React from "react";
import { useCurrentFrame, useVideoConfig } from "remotion";

interface ShakeOnBeatProps {
  children: React.ReactNode;
  amplitude?: number;
  frequency?: number;
}

export const ShakeOnBeat: React.FC<ShakeOnBeatProps> = ({
  children,
  amplitude = 5,
  frequency = 15,
}) => {
  const frame = useCurrentFrame();
  const { fps } = useVideoConfig();

  const time = frame / fps;
  const shakeX = Math.sin(time * frequency * Math.PI * 2) * amplitude;
  const shakeY = Math.cos(time * frequency * Math.PI * 2 * 0.7) * amplitude;

  return (
    <div
      style={{
        width: "100%",
        height: "100%",
        transform: `translate(${shakeX}px, ${shakeY}px)`,
      }}
    >
      {children}
    </div>
  );
};
