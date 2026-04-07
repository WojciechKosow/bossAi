import React from "react";
import { useCurrentFrame, useVideoConfig } from "remotion";

interface ShakeOnBeatProps {
  children: React.ReactNode;
  /** Max shake displacement in pixels. Default 4. */
  amplitude?: number;
  /** BPM for beat sync. If not provided, uses constant subtle drift. */
  bpm?: number;
}

/**
 * Deterministic pseudo-random based on seed.
 * Returns value between -1 and 1.
 */
function seededRandom(seed: number): number {
  const x = Math.sin(seed * 127.1 + seed * 311.7) * 43758.5453;
  return (x - Math.floor(x)) * 2 - 1;
}

/**
 * Professional camera shake — beat-triggered with exponential decay.
 *
 * How it works:
 * - On each beat: sharp displacement (amplitude depends on intensity)
 * - Between beats: exponential decay back to center (settles quickly)
 * - Uses deterministic pseudo-random for varied X/Y per beat
 * - Slight rotation for realistic handheld feel
 * - Scale slightly on impact for "punch" feel
 *
 * This is NOT a constant wobble — it's a punchy hit-and-settle.
 */
export const ShakeOnBeat: React.FC<ShakeOnBeatProps> = ({
  children,
  amplitude = 4,
  bpm,
}) => {
  const frame = useCurrentFrame();
  const { fps } = useVideoConfig();

  // If BPM available, sync to beats
  if (bpm && bpm > 0) {
    const framesPerBeat = (60 / bpm) * fps;
    const beatIndex = Math.floor(frame / framesPerBeat);
    const beatProgress = (frame % framesPerBeat) / framesPerBeat;

    // Exponential decay: strong at beat start, settles by 30% through
    const decay = Math.exp(-8 * beatProgress);

    // Deterministic random direction per beat
    const randX = seededRandom(beatIndex * 2);
    const randY = seededRandom(beatIndex * 2 + 1);
    const randRot = seededRandom(beatIndex * 3);

    const shakeX = randX * amplitude * decay;
    const shakeY = randY * amplitude * 0.7 * decay;
    const rotation = randRot * amplitude * 0.15 * decay;
    const scale = 1 + decay * 0.008;

    return (
      <div
        style={{
          width: "100%",
          height: "100%",
          transform: `translate(${shakeX}px, ${shakeY}px) rotate(${rotation}deg) scale(${scale})`,
          overflow: "hidden",
        }}
      >
        {children}
      </div>
    );
  }

  // Fallback: very subtle handheld drift (no BPM available)
  const time = frame / fps;
  const driftX = Math.sin(time * 0.8) * amplitude * 0.2;
  const driftY = Math.cos(time * 1.1) * amplitude * 0.15;

  return (
    <div
      style={{
        width: "100%",
        height: "100%",
        transform: `translate(${driftX}px, ${driftY}px)`,
        overflow: "hidden",
      }}
    >
      {children}
    </div>
  );
};
