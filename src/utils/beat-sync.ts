import { useCurrentFrame, useVideoConfig } from "remotion";

export function useBeatSync(bpm: number = 120): {
  beatProgress: number;
  beatIndex: number;
  isOnBeat: boolean;
} {
  const frame = useCurrentFrame();
  const { fps } = useVideoConfig();

  const framesPerBeat = (60 / bpm) * fps;
  const beatIndex = Math.floor(frame / framesPerBeat);
  const beatProgress = (frame % framesPerBeat) / framesPerBeat;
  const isOnBeat = beatProgress < 0.1;

  return { beatProgress, beatIndex, isOnBeat };
}
