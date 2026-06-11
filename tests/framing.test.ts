import { resolveFraming } from "../src/utils/framing";
import { autoKenBurnsParams } from "../src/utils/deterministic";
import { SegmentSchema, EffectSchema } from "../src/types/edl";

describe("resolveFraming", () => {
  const frame = { w: 1080, h: 1920 };

  it("passes explicit modes through untouched", () => {
    expect(resolveFraming("cover", 1920, 1080, frame.w, frame.h)).toBe("cover");
    expect(resolveFraming("contain", 1920, 1080, frame.w, frame.h)).toBe(
      "contain"
    );
    expect(resolveFraming("blur_fill", 1080, 1920, frame.w, frame.h)).toBe(
      "blur_fill"
    );
  });

  it("auto: blur-fills landscape 16:9 footage", () => {
    expect(resolveFraming("auto", 1920, 1080, frame.w, frame.h)).toBe(
      "blur_fill"
    );
  });

  it("auto: blur-fills square and 3:4 sources", () => {
    expect(resolveFraming("auto", 1000, 1000, frame.w, frame.h)).toBe(
      "blur_fill"
    );
    expect(resolveFraming("auto", 1500, 2000, frame.w, frame.h)).toBe(
      "blur_fill"
    );
  });

  it("auto: covers native 9:16 and near-portrait sources", () => {
    expect(resolveFraming("auto", 1080, 1920, frame.w, frame.h)).toBe("cover");
    expect(resolveFraming("auto", 1000, 1500, frame.w, frame.h)).toBe("cover");
  });

  it("auto: falls back to cover when dimensions are unknown", () => {
    expect(resolveFraming("auto", null, null, frame.w, frame.h)).toBe("cover");
    expect(resolveFraming("auto", 1920, 0, frame.w, frame.h)).toBe("cover");
  });
});

describe("segment framing schema", () => {
  const base = {
    id: "seg-1",
    asset_url: "a.mp4",
    asset_type: "VIDEO",
    start_ms: 0,
    end_ms: 1000,
  };

  it("defaults to auto", () => {
    const seg = SegmentSchema.parse(base);
    expect(seg.framing).toBe("auto");
  });

  it("accepts explicit framing values", () => {
    expect(SegmentSchema.parse({ ...base, framing: "blur_fill" }).framing).toBe(
      "blur_fill"
    );
  });

  it("rejects unknown framing values", () => {
    expect(SegmentSchema.safeParse({ ...base, framing: "stretch" }).success).toBe(
      false
    );
  });
});

describe("effect schema completeness", () => {
  it("accepts rgb_split (emitted by the backend EffectRegistry)", () => {
    expect(EffectSchema.safeParse({ type: "rgb_split" }).success).toBe(true);
  });

  it("accepts grain_overlay", () => {
    expect(
      EffectSchema.safeParse({ type: "grain_overlay", intensity: 0.3 }).success
    ).toBe(true);
  });
});

describe("autoKenBurnsParams", () => {
  it("is deterministic for the same segment id", () => {
    expect(autoKenBurnsParams("seg-abc")).toEqual(autoKenBurnsParams("seg-abc"));
  });

  it("stays within subtle scale bounds", () => {
    for (const id of ["a", "b", "c", "d", "e", "f", "g", "h"]) {
      const p = autoKenBurnsParams(id);
      expect(Math.min(p.scaleFrom, p.scaleTo)).toBeGreaterThanOrEqual(1.0);
      expect(Math.max(p.scaleFrom, p.scaleTo)).toBeLessThanOrEqual(1.2);
      expect(["left", "right", "up", "down"]).toContain(p.panDirection);
    }
  });

  it("varies across different segment ids", () => {
    const variants = new Set(
      ["a", "b", "c", "d", "e", "f", "g", "h", "i", "j"].map((id) =>
        JSON.stringify(autoKenBurnsParams(id))
      )
    );
    expect(variants.size).toBeGreaterThan(1);
  });
});
