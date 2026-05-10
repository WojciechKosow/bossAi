import React from "react";
import { useVideoConfig } from "remotion";
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
  // Hook always called unconditionally — Rules of Hooks compliant
  const { beatProgress } = useBeatSync(bpm);

  const scale =
    easing === "spring"
      ? 1 + (scalePeak - 1) * Math.exp(-beatProgress * 6)
      : 1 + (scalePeak - 1) * Math.sin(beatProgress * Math.PI);

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
