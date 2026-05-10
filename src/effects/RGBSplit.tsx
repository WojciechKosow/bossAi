import React, { useId } from "react";
import { useCurrentFrame, useVideoConfig, interpolate, Easing } from "remotion";

interface RGBSplitProps {
  children: React.ReactNode;
  /** Max channel offset in pixels at burst peak. Default: 8 */
  offsetPx?: number;
  /** Duration of the entire burst in ms. Default: 100 */
  durationMs?: number;
}

/**
 * RGBSplit — chromatic aberration burst at the start of a segment.
 *
 * Red channel shifts right, blue shifts left, then both converge back to center
 * over durationMs. Designed for drop hits and high-energy scene entrances.
 *
 * Uses React useId() for unique SVG filter IDs — safe in multi-segment renders.
 */
export const RGBSplit: React.FC<RGBSplitProps> = ({
  children,
  offsetPx = 8,
  durationMs = 100,
}) => {
  const frame = useCurrentFrame();
  const { fps } = useVideoConfig();
  const uid = useId().replace(/:/g, "");

  const burstFrames = Math.max(1, Math.round((durationMs / 1000) * fps));
  const peakFrame = Math.round(burstFrames * 0.25);

  const offset = interpolate(
    frame,
    [0, peakFrame, burstFrames],
    [0, offsetPx, 0],
    {
      extrapolateLeft: "clamp",
      extrapolateRight: "clamp",
      easing: Easing.out(Easing.cubic),
    }
  );

  const channelOpacity = interpolate(
    frame,
    [0, peakFrame, burstFrames],
    [0, 0.85, 0],
    {
      extrapolateLeft: "clamp",
      extrapolateRight: "clamp",
    }
  );

  const redId = `rgbr-${uid}`;
  const blueId = `rgbb-${uid}`;
  const active = offset > 0.1;

  return (
    <div style={{ width: "100%", height: "100%", position: "relative", overflow: "hidden" }}>
      {active && (
        <>
          {/* Red channel shifted right */}
          <div
            style={{
              position: "absolute",
              inset: 0,
              transform: `translateX(${offset.toFixed(2)}px)`,
              opacity: channelOpacity,
              mixBlendMode: "screen",
              filter: `url(#${redId})`,
              pointerEvents: "none",
            }}
          >
            {children}
          </div>
          {/* Blue channel shifted left */}
          <div
            style={{
              position: "absolute",
              inset: 0,
              transform: `translateX(${(-offset).toFixed(2)}px)`,
              opacity: channelOpacity,
              mixBlendMode: "screen",
              filter: `url(#${blueId})`,
              pointerEvents: "none",
            }}
          >
            {children}
          </div>
        </>
      )}
      {/* Primary content always on top */}
      <div style={{ width: "100%", height: "100%" }}>{children}</div>
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
