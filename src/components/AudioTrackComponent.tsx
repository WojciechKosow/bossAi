import React from "react";
import { Audio, useCurrentFrame, useVideoConfig } from "remotion";
import type { AudioTrack, MixConfig } from "../types/edl";
import {
  duckFactorAt,
  effectiveFadeMs,
  fadeInGain,
  fadeOutGain,
  shouldDuck,
  volumeFromPoints,
  type SpeechInterval,
} from "../utils/audio-mix";

interface AudioTrackComponentProps {
  track: AudioTrack;
  totalDurationInFrames: number;
  mixConfig: MixConfig;
  speechIntervalsMs: ReadonlyArray<SpeechInterval>;
}

export const AudioTrackComponent: React.FC<AudioTrackComponentProps> = ({
  track,
  totalDurationInFrames,
  mixConfig,
  speechIntervalsMs,
}) => {
  const frame = useCurrentFrame();
  const { fps } = useVideoConfig();

  const { fadeInMs, fadeOutMs } = effectiveFadeMs(track);
  const fadeInFrames = Math.round((fadeInMs / 1000) * fps);
  const fadeOutFrames = Math.round((fadeOutMs / 1000) * fps);

  const trackDurationFrames = track.end_ms
    ? Math.round(((track.end_ms - track.start_ms) / 1000) * fps)
    : totalDurationInFrames - Math.round((track.start_ms / 1000) * fps);

  // Volume automation envelope (style dynamics) supersedes the static volume;
  // fades and ducking still multiply on top.
  const absoluteMs = track.start_ms + (frame / fps) * 1000;
  let volume = volumeFromPoints(absoluteMs, track.volume_points) ?? track.volume;

  if (fadeInFrames > 0 && frame < fadeInFrames) {
    volume *= fadeInGain(frame / fadeInFrames);
  }

  if (fadeOutFrames > 0) {
    const fadeOutStart = trackDurationFrames - fadeOutFrames;
    if (frame > fadeOutStart) {
      volume *= fadeOutGain((frame - fadeOutStart) / fadeOutFrames);
    }
  }

  // Duck music under active voiceover. The component renders inside a Sequence,
  // so the local frame maps to absolute time via the track's start offset.
  if (shouldDuck(track, mixConfig) && speechIntervalsMs.length > 0) {
    volume *= duckFactorAt(absoluteMs, speechIntervalsMs, mixConfig);
  }

  const trimInFrames = track.trim_in_ms
    ? Math.round((track.trim_in_ms / 1000) * fps)
    : 0;
  const trimOutFrames = track.trim_out_ms
    ? Math.round((track.trim_out_ms / 1000) * fps)
    : undefined;

  return (
    <Audio
      src={track.asset_url}
      volume={Math.max(volume, 0)}
      startFrom={trimInFrames}
      endAt={trimOutFrames}
    />
  );
};
