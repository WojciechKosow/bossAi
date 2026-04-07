import React, { useMemo } from "react";
import { useCurrentFrame } from "remotion";

interface GrainOverlayProps {
  intensity?: number;
}

export const GrainOverlay: React.FC<GrainOverlayProps> = ({
  intensity = 0.3,
}) => {
  const frame = useCurrentFrame();

  const svgFilter = useMemo(() => {
    const seed = frame % 100;
    return (
      <svg width="0" height="0" style={{ position: "absolute" }}>
        <filter id={`grain-${seed}`}>
          <feTurbulence
            type="fractalNoise"
            baseFrequency="0.65"
            numOctaves="3"
            seed={seed}
            stitchTiles="stitch"
          />
          <feColorMatrix type="saturate" values="0" />
        </filter>
      </svg>
    );
  }, [frame]);

  const seed = frame % 100;

  return (
    <>
      {svgFilter}
      <div
        style={{
          position: "absolute",
          top: 0,
          left: 0,
          width: "100%",
          height: "100%",
          filter: `url(#grain-${seed})`,
          opacity: intensity,
          mixBlendMode: "overlay",
          pointerEvents: "none",
        }}
      />
    </>
  );
};
