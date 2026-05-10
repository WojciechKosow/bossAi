import React from "react";
import { useCurrentFrame, useVideoConfig, interpolate, Easing } from "remotion";

interface VignettePulseProps {
  children: React.ReactNode;
  vignetteDelta?: number;
  durationMs?: number;
}

/**
 * VignettePulse — intensywna vignette na początku segmentu, opada do zera.
 * Używana na dropach muzycznych — ciemne krawędzie skupiają uwagę na centrum,
 * dając poczucie "nacisku" na beat.
 * vignetteDelta: 0.4 → overlay z opacity 0.4 → 0 w czasie durationMs.
 */
export const VignettePulse: React.FC<VignettePulseProps> = ({
  children,
  vignetteDelta = 0.4,
  durationMs = 150,
}) => {
  const frame = useCurrentFrame();
  const { fps } = useVideoConfig();

  const pulseFrames = Math.max(1, Math.round((durationMs / 1000) * fps));

  const vignetteOpacity = interpolate(frame, [0, pulseFrames], [vignetteDelta, 0], {
    extrapolateLeft: "clamp",
    extrapolateRight: "clamp",
    easing: Easing.out(Easing.cubic),
  });

  return (
    <div style={{ width: "100%", height: "100%", position: "relative" }}>
      {children}
      {vignetteOpacity > 0.01 && (
        <div
          style={{
            position: "absolute",
            top: 0,
            left: 0,
            width: "100%",
            height: "100%",
            background: `radial-gradient(ellipse at center, transparent 40%, rgba(0,0,0,${vignetteOpacity.toFixed(3)}) 100%)`,
            pointerEvents: "none",
          }}
        />
      )}
    </div>
  );
};
