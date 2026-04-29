import type { EdlDto, EdlSegment } from "../../types";

export const PIXELS_PER_SECOND_DEFAULT = 90;

export const msToPx = (ms: number, pixelsPerSecond: number) =>
  (ms / 1000) * pixelsPerSecond;

export const pxToMs = (px: number, pixelsPerSecond: number) =>
  Math.round((px / pixelsPerSecond) * 1000);

export const formatTime = (ms: number) => {
  const total = Math.max(0, ms);
  const minutes = Math.floor(total / 60000);
  const seconds = Math.floor((total % 60000) / 1000);
  const millis = Math.floor((total % 1000) / 10);
  return `${String(minutes).padStart(2, "0")}:${String(seconds).padStart(2, "0")}.${String(millis).padStart(2, "0")}`;
};

export const groupByLayer = (segments: EdlSegment[]) => {
  const map = new Map<number, EdlSegment[]>();
  for (const s of segments) {
    if (!map.has(s.layer)) map.set(s.layer, []);
    map.get(s.layer)!.push(s);
  }
  return Array.from(map.entries())
    .sort(([a], [b]) => a - b)
    .map(([layer, segs]) => ({
      layer,
      segments: segs.sort((a, b) => a.start_ms - b.start_ms),
    }));
};

export const updateSegment = (
  edl: EdlDto,
  id: string,
  patch: Partial<EdlSegment>,
): EdlDto => ({
  ...edl,
  segments: edl.segments.map((s) =>
    s.id === id ? { ...s, ...patch } : s,
  ),
});

export const removeSegment = (edl: EdlDto, id: string): EdlDto => ({
  ...edl,
  segments: edl.segments.filter((s) => s.id !== id),
});

export const totalDurationFromSegments = (edl: EdlDto): number => {
  if (!edl.segments?.length) return edl.metadata?.total_duration_ms ?? 0;
  return Math.max(
    edl.metadata?.total_duration_ms ?? 0,
    ...edl.segments.map((s) => s.end_ms),
  );
};
