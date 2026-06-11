/** Small deterministic string hash (FNV-1a) for stable per-segment variation. */
export function hashString(input: string): number {
  let hash = 0x811c9dc5;
  for (let i = 0; i < input.length; i++) {
    hash ^= input.charCodeAt(i);
    hash = Math.imul(hash, 0x01000193);
  }
  return hash >>> 0;
}

export interface AutoKenBurnsParams {
  scaleFrom: number;
  scaleTo: number;
  panDirection: "left" | "right" | "up" | "down";
}

const PAN_DIRECTIONS: AutoKenBurnsParams["panDirection"][] = [
  "left",
  "right",
  "up",
  "down",
];

/**
 * Subtle default Ken Burns parameters for static images that arrive without
 * any effect, so an image hold is never a dead frame. Derived from the segment
 * id so adjacent stills move differently but renders stay deterministic.
 */
export function autoKenBurnsParams(segmentId: string): AutoKenBurnsParams {
  const hash = hashString(segmentId);
  const panDirection = PAN_DIRECTIONS[hash % PAN_DIRECTIONS.length];
  const zoomOut = (hash & 0x10) !== 0;
  return zoomOut
    ? { scaleFrom: 1.14, scaleTo: 1.04, panDirection }
    : { scaleFrom: 1.04, scaleTo: 1.14, panDirection };
}
