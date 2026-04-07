import React from "react";
import { Audio, useCurrentFrame, useVideoConfig, interpolate } from "remotion";
import type { AudioTrack } from "../types/edl";

interface AudioTrackComponentProps {
  track: AudioTrack;
  totalDurationInFrames: number;
}

export const AudioTrackComponent: React.FC<AudioTrackComponentProps> = ({
  track,
  totalDurationInFrames,
}) => {
  const frame = useCurrentFrame();
  const { fps } = useVideoConfig();

  const fadeInFrames = Math.round((track.fade_in_ms / 1000) * fps);
  const fadeOutFrames = Math.round((track.fade_out_ms / 1000) * fps);

  const trackDurationFrames = track.end_ms
    ? Math.round(((track.end_ms - track.start_ms) / 1000) * fps)
    : totalDurationInFrames - Math.round((track.start_ms / 1000) * fps);

  // Calculate volume with fades
  let volume = track.volume;

  // Fade in
  if (fadeInFrames > 0 && frame < fadeInFrames) {
    volume *= interpolate(frame, [0, fadeInFrames], [0, 1], {
      extrapolateLeft: "clamp",
      extrapolateRight: "clamp",
    });
  }

  // Fade out
  if (fadeOutFrames > 0) {
    const fadeOutStart = trackDurationFrames - fadeOutFrames;
    if (frame > fadeOutStart) {
      volume *= interpolate(
        frame,
        [fadeOutStart, trackDurationFrames],
        [1, 0],
        { extrapolateLeft: "clamp", extrapolateRight: "clamp" }
      );
    }
  }

  const trimInFrames = track.trim_in_ms
    ? Math.round((track.trim_in_ms / 1000) * fps)
    : 0;

  return (
    <Audio
      src={track.asset_url}
      volume={volume}
      startFrom={trimInFrames}
    />
  );
};
