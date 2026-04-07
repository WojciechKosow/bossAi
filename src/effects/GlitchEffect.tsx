import React from "react";
import { useCurrentFrame, useVideoConfig } from "remotion";

interface GlitchEffectProps {
  children: React.ReactNode;
  intensity?: number;
  rgbSplit?: number;
}

export const GlitchEffect: React.FC<GlitchEffectProps> = ({
  children,
  intensity = 0.5,
  rgbSplit = 5,
}) => {
  const frame = useCurrentFrame();
  const { fps } = useVideoConfig();

  const time = frame / fps;
  // Pseudo-random glitch triggers based on frame
  const glitchActive =
    Math.sin(frame * 0.7) * Math.cos(frame * 1.3) > 0.3 * (1 - intensity);

  const offsetX = glitchActive
    ? Math.sin(frame * 13.37) * rgbSplit * intensity
    : 0;
  const offsetY = glitchActive
    ? Math.cos(frame * 7.77) * rgbSplit * intensity * 0.5
    : 0;
  const sliceOffset = glitchActive
    ? Math.sin(frame * 3.14) * 10 * intensity
    : 0;

  return (
    <div style={{ width: "100%", height: "100%", position: "relative" }}>
      {/* Red channel offset */}
      <div
        style={{
          position: "absolute",
          top: 0,
          left: 0,
          width: "100%",
          height: "100%",
          transform: `translate(${offsetX}px, ${offsetY}px)`,
          mixBlendMode: "screen",
          opacity: glitchActive ? 0.8 : 0,
          filter: "url(#red-channel)",
        }}
      >
        {children}
      </div>
      {/* Blue channel offset */}
      <div
        style={{
          position: "absolute",
          top: 0,
          left: 0,
          width: "100%",
          height: "100%",
          transform: `translate(${-offsetX}px, ${-offsetY}px)`,
          mixBlendMode: "screen",
          opacity: glitchActive ? 0.8 : 0,
          filter: "url(#blue-channel)",
        }}
      >
        {children}
      </div>
      {/* Main content with scan line displacement */}
      <div
        style={{
          width: "100%",
          height: "100%",
          transform: glitchActive
            ? `translateX(${sliceOffset}px)`
            : undefined,
        }}
      >
        {children}
      </div>
      {/* SVG filters for color channel separation */}
      <svg width="0" height="0" style={{ position: "absolute" }}>
        <filter id="red-channel">
          <feColorMatrix
            type="matrix"
            values="1 0 0 0 0  0 0 0 0 0  0 0 0 0 0  0 0 0 1 0"
          />
        </filter>
        <filter id="blue-channel">
          <feColorMatrix
            type="matrix"
            values="0 0 0 0 0  0 0 0 0 0  0 0 1 0 0  0 0 0 1 0"
          />
        </filter>
      </svg>
    </div>
  );
};
