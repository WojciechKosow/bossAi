import React from "react";
import { useCurrentFrame, useVideoConfig, spring } from "remotion";
import { useBeatSync } from "../utils/beat-sync";

interface BounceProps {
  children: React.ReactNode;
  scalePeak?: number;
  easing?: string;
  bpm?: number;
}

export const Bounce: React.FC<BounceProps> = ({
  children,
  scalePeak = 1.12,
  easing = "spring",
  bpm = 120,
}) => {
  const frame = useCurrentFrame();
  const { fps } = useVideoConfig();

  let scale: number;

  if (easing === "spring") {
    const { beatProgress } = useBeatSync(bpm);
    // Pulse on each beat with spring decay
    const pulse = Math.exp(-beatProgress * 6);
    scale = 1 + (scalePeak - 1) * pulse;
  } else {
    // Sinusoidal bounce
    const { beatProgress } = useBeatSync(bpm);
    scale = 1 + (scalePeak - 1) * Math.sin(beatProgress * Math.PI);
  }

  return (
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
  );
};
