import React from "react";
import { useCurrentFrame, useVideoConfig, interpolate, Easing } from "remotion";

interface KenBurnsProps {
  children: React.ReactNode;
  scaleFrom?: number;
  scaleTo?: number;
  panDirection?: "left" | "right" | "up" | "down";
}

export const KenBurns: React.FC<KenBurnsProps> = ({
  children,
  scaleFrom = 1.0,
  scaleTo = 1.25,
  panDirection = "right",
}) => {
  const frame = useCurrentFrame();
  const { durationInFrames } = useVideoConfig();

  const progress = interpolate(frame, [0, durationInFrames], [0, 1], {
    extrapolateRight: "clamp",
    easing: Easing.inOut(Easing.cubic),
  });

  const scale = scaleFrom + (scaleTo - scaleFrom) * progress;

  // 12% pan gives visible cinematic drift (was 5% — barely noticeable)
  const panAmount = 12;
  let translateX = 0;
  let translateY = 0;

  switch (panDirection) {
    case "left":
      translateX = -progress * panAmount;
      break;
    case "right":
      translateX = progress * panAmount;
      break;
    case "up":
      translateY = -progress * panAmount;
      break;
    case "down":
      translateY = progress * panAmount;
      break;
  }

  // Subtle counter-rotation for organic handheld feel
  const rotation =
    panDirection === "left" || panDirection === "right"
      ? (progress - 0.5) * 0.4
      : 0;

  return (
    <div style={{ width: "100%", height: "100%", overflow: "hidden" }}>
      <div
        style={{
          width: "100%",
          height: "100%",
          transform: `scale(${scale}) translate(${translateX}%, ${translateY}%) rotate(${rotation}deg)`,
          transformOrigin: "center center",
        }}
      >
        {children}
      </div>
    </div>
  );
};
