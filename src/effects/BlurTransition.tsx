import React from "react";
import { useCurrentFrame, useVideoConfig, interpolate, Easing } from "remotion";

interface BlurTransitionProps {
  children: React.ReactNode;
  blurAmount?: number;
  durationMs?: number;
  phase?: "intro" | "outro";
}

/**
 * BlurTransition — gaussian blur narastający pod koniec segmentu (phase: "outro")
 * lub opadający na początku (phase: "intro").
 * Efekt TikTok-native: blur na przejściu między klipami daje płynność
 * bez twardego cięcia, charakterystyczną dla nowoczesnego edytingu.
 */
export const BlurTransition: React.FC<BlurTransitionProps> = ({
  children,
  blurAmount = 18,
  durationMs = 200,
  phase = "outro",
}) => {
  const frame = useCurrentFrame();
  const { durationInFrames, fps } = useVideoConfig();

  const effectFrames = Math.max(1, Math.round((durationMs / 1000) * fps));

  let blur: number;

  if (phase === "outro") {
    // Blur narasta w ostatnich `effectFrames` klatach segmentu
    const outStart = durationInFrames - effectFrames;
    blur = interpolate(frame, [outStart, durationInFrames], [0, blurAmount], {
      extrapolateLeft: "clamp",
      extrapolateRight: "clamp",
      easing: Easing.in(Easing.cubic),
    });
  } else {
    // Blur opada na początku segmentu
    blur = interpolate(frame, [0, effectFrames], [blurAmount, 0], {
      extrapolateLeft: "clamp",
      extrapolateRight: "clamp",
      easing: Easing.out(Easing.cubic),
    });
  }

  return (
    <div
      style={{
        width: "100%",
        height: "100%",
        filter: blur > 0.1 ? `blur(${blur.toFixed(1)}px)` : undefined,
      }}
    >
      {children}
    </div>
  );
};
