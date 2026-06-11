import {
  computeSpeechIntervals,
  duckFactorAt,
  effectiveFadeMs,
  fadeInGain,
  fadeOutGain,
  shouldDuck,
  DEFAULT_MIX_CONFIG,
  DEFAULT_MUSIC_FADE_IN_MS,
  DEFAULT_MUSIC_FADE_OUT_MS,
} from "../src/utils/audio-mix";
import { AudioTrackSchema, MixConfigSchema } from "../src/types/edl";

const word = (start: number, end: number) => ({
  word: "x",
  start_ms: start,
  end_ms: end,
  sentence_index: 0,
});

const musicTrack = (overrides: Record<string, unknown> = {}) =>
  AudioTrackSchema.parse({
    id: "m1",
    asset_url: "music.mp3",
    type: "music",
    ...overrides,
  });

const voiceTrack = (overrides: Record<string, unknown> = {}) =>
  AudioTrackSchema.parse({
    id: "v1",
    asset_url: "voice.mp3",
    type: "voiceover",
    ...overrides,
  });

describe("computeSpeechIntervals", () => {
  it("merges words separated by gaps shorter than gapHoldMs", () => {
    const intervals = computeSpeechIntervals(
      [word(0, 400), word(700, 1200), word(2500, 3000)],
      undefined,
      10000,
      600
    );
    expect(intervals).toEqual([
      [0, 1200],
      [2500, 3000],
    ]);
  });

  it("falls back to voiceover track ranges without whisper words", () => {
    const intervals = computeSpeechIntervals(
      undefined,
      [voiceTrack({ start_ms: 500, end_ms: 4000 }), musicTrack()],
      10000,
      600
    );
    expect(intervals).toEqual([[500, 4000]]);
  });

  it("voiceover without end_ms extends to total duration", () => {
    const intervals = computeSpeechIntervals(
      [],
      [voiceTrack({ start_ms: 0 })],
      8000,
      600
    );
    expect(intervals).toEqual([[0, 8000]]);
  });
});

describe("duckFactorAt", () => {
  const mix = DEFAULT_MIX_CONFIG;
  const intervals = [[1000, 3000] as const];

  it("is 1 far outside speech", () => {
    expect(duckFactorAt(0, intervals, mix)).toBe(1);
    expect(duckFactorAt(5000, intervals, mix)).toBe(1);
  });

  it("is duck_volume during speech", () => {
    expect(duckFactorAt(2000, intervals, mix)).toBe(mix.duck_volume);
  });

  it("ramps down through the attack window", () => {
    const mid = duckFactorAt(1000 - mix.duck_attack_ms / 2, intervals, mix);
    expect(mid).toBeLessThan(1);
    expect(mid).toBeGreaterThan(mix.duck_volume);
  });

  it("ramps back up through the release window", () => {
    const mid = duckFactorAt(3000 + mix.duck_release_ms / 2, intervals, mix);
    expect(mid).toBeLessThan(1);
    expect(mid).toBeGreaterThan(mix.duck_volume);
  });

  it("takes the minimum across overlapping intervals", () => {
    const overlapping = [
      [1000, 3000],
      [2900, 4000],
    ] as const;
    expect(duckFactorAt(2950, overlapping, mix)).toBe(mix.duck_volume);
  });
});

describe("fade gains", () => {
  it("equal-power fade-in starts at 0 and ends at 1", () => {
    expect(fadeInGain(0)).toBe(0);
    expect(fadeInGain(1)).toBeCloseTo(1);
    expect(fadeInGain(0.5)).toBeGreaterThan(0.5); // not linear
  });

  it("equal-power fade-out starts at 1 and ends at 0", () => {
    expect(fadeOutGain(0)).toBe(1);
    expect(fadeOutGain(1)).toBeCloseTo(0);
  });
});

describe("shouldDuck", () => {
  it("never ducks the voiceover", () => {
    expect(shouldDuck(voiceTrack(), DEFAULT_MIX_CONFIG)).toBe(false);
  });

  it("ducks music by default", () => {
    expect(shouldDuck(musicTrack(), DEFAULT_MIX_CONFIG)).toBe(true);
  });

  it("respects the per-track override", () => {
    expect(shouldDuck(musicTrack({ ducking: false }), DEFAULT_MIX_CONFIG)).toBe(
      false
    );
  });

  it("respects mix_config.auto_duck = false", () => {
    const mix = MixConfigSchema.parse({ auto_duck: false });
    expect(shouldDuck(musicTrack(), mix)).toBe(false);
  });
});

describe("effectiveFadeMs", () => {
  it("applies default fades to music tracks without explicit fades", () => {
    expect(effectiveFadeMs(musicTrack())).toEqual({
      fadeInMs: DEFAULT_MUSIC_FADE_IN_MS,
      fadeOutMs: DEFAULT_MUSIC_FADE_OUT_MS,
    });
  });

  it("keeps explicit music fades", () => {
    expect(
      effectiveFadeMs(musicTrack({ fade_in_ms: 300, fade_out_ms: 600 }))
    ).toEqual({ fadeInMs: 300, fadeOutMs: 600 });
  });

  it("never adds fades to the voiceover", () => {
    expect(effectiveFadeMs(voiceTrack())).toEqual({ fadeInMs: 0, fadeOutMs: 0 });
  });
});
