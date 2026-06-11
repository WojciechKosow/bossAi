import type {
  AudioTrack,
  Edl,
  MixConfig,
  VolumePoint,
  WhisperWord,
} from "../types/edl";
import { MixConfigSchema } from "../types/edl";

export type SpeechInterval = readonly [startMs: number, endMs: number];

export const DEFAULT_MIX_CONFIG: MixConfig = MixConfigSchema.parse({});

/**
 * Builds the intervals during which the voiceover is speaking.
 *
 * Prefers whisper word timings (precise); merges words separated by gaps
 * shorter than gapHoldMs so the music doesn't pump between words.
 * Falls back to voiceover track ranges when no word timings exist.
 */
export function computeSpeechIntervals(
  whisperWords: WhisperWord[] | undefined,
  audioTracks: AudioTrack[] | undefined,
  totalDurationMs: number,
  gapHoldMs: number
): SpeechInterval[] {
  if (whisperWords && whisperWords.length > 0) {
    const sorted = [...whisperWords].sort((a, b) => a.start_ms - b.start_ms);
    const intervals: Array<[number, number]> = [];
    for (const w of sorted) {
      const last = intervals[intervals.length - 1];
      if (last && w.start_ms - last[1] <= gapHoldMs) {
        last[1] = Math.max(last[1], w.end_ms);
      } else {
        intervals.push([w.start_ms, w.end_ms]);
      }
    }
    return intervals;
  }

  return (audioTracks ?? [])
    .filter((t) => t.type === "voiceover")
    .map((t) => [t.start_ms, t.end_ms ?? totalDurationMs] as const);
}

/** Equal-power ramp between two gain values. */
function ramp(progress: number, from: number, to: number): number {
  const p = Math.min(Math.max(progress, 0), 1);
  // Sine ease keeps the gain change perceptually smooth (no clicky linear ramps).
  const eased = Math.sin((p * Math.PI) / 2);
  return from + (to - from) * eased;
}

/**
 * Gain multiplier (0–1] applied to music at absolute time tMs, given speech
 * intervals. 1 = no ducking; duckVolume while speech is active, with eased
 * attack/release ramps around interval boundaries.
 */
export function duckFactorAt(
  tMs: number,
  intervals: ReadonlyArray<SpeechInterval>,
  mix: MixConfig
): number {
  let factor = 1;
  for (const [start, end] of intervals) {
    if (tMs < start - mix.duck_attack_ms || tMs > end + mix.duck_release_ms) {
      continue;
    }
    let value: number;
    if (tMs < start) {
      value = ramp(
        (tMs - (start - mix.duck_attack_ms)) / Math.max(mix.duck_attack_ms, 1),
        1,
        mix.duck_volume
      );
    } else if (tMs > end) {
      value = ramp(
        (tMs - end) / Math.max(mix.duck_release_ms, 1),
        mix.duck_volume,
        1
      );
    } else {
      value = mix.duck_volume;
    }
    factor = Math.min(factor, value);
  }
  return factor;
}

/** Equal-power fade-in gain for progress in [0,1]. */
export function fadeInGain(progress: number): number {
  const p = Math.min(Math.max(progress, 0), 1);
  return Math.sin((p * Math.PI) / 2);
}

/** Equal-power fade-out gain for progress in [0,1] (1 → 0). */
export function fadeOutGain(progress: number): number {
  const p = Math.min(Math.max(progress, 0), 1);
  return Math.cos((p * Math.PI) / 2);
}

/**
 * Base volume at an absolute timeline position from the automation envelope:
 * linear interpolation between points, held flat beyond the ends.
 * Returns null when the track has no envelope (use the static volume).
 */
export function volumeFromPoints(
  tMs: number,
  points: VolumePoint[] | undefined
): number | null {
  if (!points || points.length === 0) return null;
  const sorted = [...points].sort((a, b) => a.ms - b.ms);
  if (tMs <= sorted[0].ms) return sorted[0].volume;
  for (let i = 1; i < sorted.length; i++) {
    const a = sorted[i - 1];
    const b = sorted[i];
    if (tMs <= b.ms) {
      if (b.ms === a.ms) return b.volume;
      const t = (tMs - a.ms) / (b.ms - a.ms);
      return a.volume + (b.volume - a.volume) * t;
    }
  }
  return sorted[sorted.length - 1].volume;
}

/** Whether ducking applies to this track. Only music ducks, never the voiceover. */
export function shouldDuck(track: AudioTrack, mix: MixConfig): boolean {
  if (track.type !== "music") return false;
  return track.ducking ?? mix.auto_duck;
}

export function resolveMixConfig(edl: Edl): MixConfig {
  return edl.mix_config ?? DEFAULT_MIX_CONFIG;
}

/**
 * Default fades for music tracks so a track never starts or stops abruptly.
 * Explicit backend values always win.
 */
export const DEFAULT_MUSIC_FADE_IN_MS = 150;
export const DEFAULT_MUSIC_FADE_OUT_MS = 400;

export function effectiveFadeMs(
  track: AudioTrack
): { fadeInMs: number; fadeOutMs: number } {
  if (track.type !== "music") {
    return { fadeInMs: track.fade_in_ms, fadeOutMs: track.fade_out_ms };
  }
  return {
    fadeInMs: track.fade_in_ms > 0 ? track.fade_in_ms : DEFAULT_MUSIC_FADE_IN_MS,
    fadeOutMs:
      track.fade_out_ms > 0 ? track.fade_out_ms : DEFAULT_MUSIC_FADE_OUT_MS,
  };
}
