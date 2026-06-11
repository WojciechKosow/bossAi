/**
 * Edge-safety math for pan/drift/Ken Burns camera moves.
 *
 * CSS `scale(s) translate(t%)` shifts content by t%·s of the frame width while
 * the zoom only creates an (s−1)/2 overhang per side. Content keeps covering
 * the frame iff t ≤ 50·(s−1)/s — otherwise a black band slides into view.
 */

/** Largest translate (in %) that `scale` can absorb without revealing edges. */
export function maxSafeTranslatePercent(
  scale: number,
  bufferPercent = 0
): number {
  if (scale <= 1) return 0;
  return Math.max((50 * (scale - 1)) / scale - bufferPercent, 0);
}

/** Minimum zoom needed to travel `distancePercent` without revealing edges. */
export function scaleForPanDistance(
  distancePercent: number,
  bufferPercent = 1
): number {
  const d = Math.min(Math.max(Math.abs(distancePercent), 0), 45) + bufferPercent;
  return 50 / (50 - d);
}
