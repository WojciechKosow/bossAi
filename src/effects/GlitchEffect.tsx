import React, { useId } from "react";
import { useCurrentFrame, useVideoConfig } from "remotion";

interface GlitchEffectProps {
  children: React.ReactNode;
  intensity?: number;
  rgbSplit?: number;
}

function seededRandom(seed: number): number {
  const x = Math.sin(seed * 9301 + 49297) * 233280;
  return x - Math.floor(x);
}

/**
 * GlitchEffect — deterministic glitch pops synced to frame number.
 *
 * Triggers brief (2-frame) glitch bursts at intervals driven by intensity.
 * Uses React useId() for unique SVG filter IDs — safe when multiple segments
 * render simultaneously in the Remotion composition.
 */
export const GlitchEffect: React.FC<GlitchEffectProps> = ({
  children,
  intensity = 0.5,
  rgbSplit = 5,
}) => {
  const frame = useCurrentFrame();
  const { fps } = useVideoConfig();
  const uid = useId().replace(/:/g, "");

  // Pop interval: intensity 1.0 → pop every ~4 frames; 0.3 → every ~13 frames
  const popInterval = Math.max(4, Math.round(fps / (intensity * 6)));
  const withinPop = frame % popInterval < 2;
  const popSeed = Math.floor(frame / popInterval);
  const glitchActive = withinPop && seededRandom(popSeed) < intensity;

  const dx = glitchActive ? (seededRandom(frame * 3 + 1) * 2 - 1) * rgbSplit * intensity : 0;
  const dy = glitchActive ? (seededRandom(frame * 7 + 2) * 2 - 1) * rgbSplit * 0.3 * intensity : 0;
  const sliceX = glitchActive ? (seededRandom(frame * 13 + 3) * 2 - 1) * 8 * intensity : 0;

  const redId = `gr-${uid}`;
  const blueId = `gb-${uid}`;

  return (
    <div style={{ width: "100%", height: "100%", position: "relative", overflow: "hidden" }}>
      {/* Red channel — shifted right */}
      <div
        style={{
          position: "absolute",
          inset: 0,
          transform: glitchActive ? `translate(${dx}px, ${dy}px)` : undefined,
          mixBlendMode: "screen",
          opacity: glitchActive ? 0.75 : 0,
          filter: `url(#${redId})`,
          pointerEvents: "none",
        }}
      >
        {children}
      </div>
      {/* Blue channel — shifted left */}
      <div
        style={{
          position: "absolute",
          inset: 0,
          transform: glitchActive ? `translate(${-dx}px, ${-dy}px)` : undefined,
          mixBlendMode: "screen",
          opacity: glitchActive ? 0.75 : 0,
          filter: `url(#${blueId})`,
          pointerEvents: "none",
        }}
      >
        {children}
      </div>
      {/* Primary content with scan-line horizontal slice on pop */}
      <div
        style={{
          width: "100%",
          height: "100%",
          transform: glitchActive ? `translateX(${sliceX}px)` : undefined,
        }}
      >
        {children}
      </div>
      <svg width="0" height="0" style={{ position: "absolute", pointerEvents: "none" }}>
        <defs>
          <filter id={redId}>
            <feColorMatrix type="matrix" values="1 0 0 0 0  0 0 0 0 0  0 0 0 0 0  0 0 0 1 0" />
          </filter>
          <filter id={blueId}>
            <feColorMatrix type="matrix" values="0 0 0 0 0  0 0 0 0 0  0 0 1 0 0  0 0 0 1 0" />
          </filter>
        </defs>
      </svg>
    </div>
  );
};
