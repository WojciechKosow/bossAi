import React from "react";
import { useCurrentFrame, useVideoConfig, interpolate, Easing } from "remotion";

interface ColorPopProps {
  children: React.ReactNode;
  saturationBoost?: number;
  durationMs?: number;
}

/**
 * ColorPop — skok saturacji na początku segmentu, opada do naturalnej.
 * Używany na reveal produktu i CTA — kolory "wybucha" żywością,
 * tworząc kontrast z desaturowanym problemem z poprzednich beatów.
 * saturationBoost: 0.35 → saturate(1.35) → saturate(1.0) w czasie durationMs.
 */
export const ColorPop: React.FC<ColorPopProps> = ({
  children,
  saturationBoost = 0.35,
  durationMs = 200,
}) => {
  const frame = useCurrentFrame();
  const { fps } = useVideoConfig();

  const popFrames = Math.max(1, Math.round((durationMs / 1000) * fps));

  const saturation = interpolate(
    frame,
    [0, popFrames],
    [1.0 + saturationBoost, 1.0],
    {
      extrapolateLeft: "clamp",
      extrapolateRight: "clamp",
      easing: Easing.out(Easing.cubic),
    }
  );

  // Slight contrast boost alongside saturation for extra punch
  const contrast = interpolate(frame, [0, popFrames], [1.08, 1.0], {
    extrapolateLeft: "clamp",
    extrapolateRight: "clamp",
  });

  return (
    <div
      style={{
        width: "100%",
        height: "100%",
        filter: `saturate(${saturation.toFixed(3)}) contrast(${contrast.toFixed(3)})`,
      }}
    >
      {children}
    </div>
  );
};
