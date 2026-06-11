import {
  maxSafeTranslatePercent,
  scaleForPanDistance,
} from "../src/utils/safe-pan";

describe("maxSafeTranslatePercent", () => {
  it("is 0 with no zoom (nothing to absorb a pan)", () => {
    expect(maxSafeTranslatePercent(1)).toBe(0);
    expect(maxSafeTranslatePercent(0.9)).toBe(0);
  });

  it("matches the coverage equation t = 50(s-1)/s", () => {
    expect(maxSafeTranslatePercent(1.25)).toBeCloseTo(10);
    expect(maxSafeTranslatePercent(2)).toBeCloseTo(25);
  });

  it("subtracts the buffer and never goes negative", () => {
    expect(maxSafeTranslatePercent(1.25, 0.75)).toBeCloseTo(9.25);
    expect(maxSafeTranslatePercent(1.001, 5)).toBe(0);
  });
});

describe("scaleForPanDistance", () => {
  it("returns a zoom that absorbs the full travel", () => {
    for (const d of [5, 10, 15, 25]) {
      const s = scaleForPanDistance(d);
      // effective shift d·s must stay within the overhang 50(s-1)
      expect(d * s).toBeLessThanOrEqual(50 * (s - 1) + 1e-9);
    }
  });

  it("requires ~1.47x for the default 15% pan", () => {
    expect(scaleForPanDistance(15)).toBeCloseTo(50 / 34, 5);
  });

  it("caps unreasonable distances instead of exploding", () => {
    expect(scaleForPanDistance(80)).toBeLessThan(15);
    expect(Number.isFinite(scaleForPanDistance(49))).toBe(true);
  });
});
