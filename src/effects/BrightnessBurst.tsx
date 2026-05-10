import React from "react";
import { useCurrentFrame, useVideoConfig, interpolate, Easing } from "remotion";

interface BrightnessBurstProps {
  children: React.ReactNode;
  brightnessDelta?: number;
  durationMs?: number;
}

/**
 * BrightnessBurst — skok jasności na początku segmentu, opada do normalnej.
 * Używany na climaxie i reveal momentach — daje wizualny "punch" na bicie.
 * brightnessDelta: 0.45 → brightness filtr 1.45 → 1.0 w czasie durationMs.
 */
export const BrightnessBurst: React.FC<BrightnessBurstProps> = ({
  children,
  brightnessDelta = 0.45,
  durationMs = 120,
}) => {
  const frame = useCurrentFrame();
  const { fps } = useVideoConfig();

  const burstFrames = Math.max(1, Math.round((durationMs / 1000) * fps));

  const brightness = interpolate(
    frame,
    [0, burstFrames],
    [1.0 + brightnessDelta, 1.0],
    {
      extrapolateLeft: "clamp",
      extrapolateRight: "clamp",
      easing: Easing.out(Easing.exp),
    }
  );

  return (
    <div
      style={{
        width: "100%",
        height: "100%",
        filter: `brightness(${brightness.toFixed(3)})`,
      }}
    >
      {children}
    </div>
  );
};
