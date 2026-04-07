import React from "react";
import { useCurrentFrame, useVideoConfig } from "remotion";
import { useBeatSync } from "../utils/beat-sync";

interface ZoomPulseProps {
  children: React.ReactNode;
  scale?: number;
  frequencyBpm?: number;
}

export const ZoomPulse: React.FC<ZoomPulseProps> = ({
  children,
  scale = 1.05,
  frequencyBpm = 120,
}) => {
  const { beatProgress } = useBeatSync(frequencyBpm);

  const pulse = Math.sin(beatProgress * Math.PI);
  const currentScale = 1 + (scale - 1) * pulse;

  return (
    <div
      style={{
        width: "100%",
        height: "100%",
        transform: `scale(${currentScale})`,
        transformOrigin: "center center",
      }}
    >
      {children}
    </div>
  );
};
