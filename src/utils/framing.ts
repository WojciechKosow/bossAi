export type ResolvedFraming = "cover" | "contain" | "blur_fill";

/**
 * Assets whose aspect ratio exceeds the frame's by this factor get blur-fill
 * in "auto" mode. 9:16 frame AR = 0.5625; threshold 0.5625 * 1.25 ≈ 0.70, so
 * 16:9 (1.78), 4:3 (1.33), 1:1 (1.0) and 3:4 (0.75) footage is blur-filled,
 * while near-portrait sources (2:3 and taller) keep the full-bleed cover crop.
 */
export const AUTO_BLUR_FILL_AR_FACTOR = 1.25;

/**
 * Resolves the framing mode for a segment.
 *
 * Explicit modes pass through. "auto" needs the asset's measured dimensions;
 * when they are unavailable (measurement failed) it falls back to "cover",
 * which is the legacy behavior.
 */
export function resolveFraming(
  framing: "auto" | ResolvedFraming,
  assetWidth: number | null,
  assetHeight: number | null,
  frameWidth: number,
  frameHeight: number
): ResolvedFraming {
  if (framing !== "auto") return framing;
  if (!assetWidth || !assetHeight || assetHeight <= 0 || frameHeight <= 0) {
    return "cover";
  }
  const assetAr = assetWidth / assetHeight;
  const frameAr = frameWidth / frameHeight;
  return assetAr > frameAr * AUTO_BLUR_FILL_AR_FACTOR ? "blur_fill" : "cover";
}
