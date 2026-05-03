import {
  forwardRef,
  memo,
  useCallback,
  useEffect,
  useImperativeHandle,
  useMemo,
  useRef,
  useState,
} from "react";
import {
  Maximize,
  Minus,
  Music,
  Mic,
  Trash2,
  Plus,
  Type,
  Sparkles,
  Magnet,
} from "lucide-react";
import { cn } from "@/lib/utils";
import type { EdlAudioTrack, EdlDto, EdlSegment } from "../../types";
import {
  formatTime,
  groupByLayer,
  msToPx,
  pxToMs,
  totalDurationFromSegments,
} from "./timelineUtils";

// ─── Public handle ────────────────────────────────────────────────────────────

export interface TimelineHandle {
  updatePlayhead: (ms: number) => void;
}

// ─── Props ────────────────────────────────────────────────────────────────────

interface Props {
  edl: EdlDto;
  selectedSegmentId: string | null;
  onSelectSegment: (id: string | null) => void;
  selectedAudioId: string | null;
  onSelectAudio: (id: string | null) => void;
  onChange: (next: EdlDto) => void;
  playheadMs: number;
  onScrub: (ms: number) => void;
}

// ─── Types ────────────────────────────────────────────────────────────────────

type DragMode = "move" | "trim-left" | "trim-right";

type DragState =
  | {
      kind: "segment";
      id: string;
      mode: DragMode;
      startX: number;
      origStart: number;
      origEnd: number;
    }
  | {
      kind: "audio";
      id: string;
      mode: DragMode;
      startX: number;
      origStart: number;
      origEnd: number;
      origTrimIn: number;
      origTrimOut: number;
    };

// ─── Constants ────────────────────────────────────────────────────────────────

const LAYER_HEIGHT = 64;
const HEADER_HEIGHT = 32;
const RULER_TICK_SECONDS = 1;
const MIN_PPS = 30;
const MAX_PPS = 220;

/** Snap threshold in pixels — independent of zoom level. */
const SNAP_PX = 10;

// ─── Helpers ──────────────────────────────────────────────────────────────────

function lockSelection() {
  document.body.style.userSelect = "none";
  // @ts-ignore
  document.body.style.webkitUserSelect = "none";
}

function unlockSelection() {
  document.body.style.userSelect = "";
  // @ts-ignore
  document.body.style.webkitUserSelect = "";
}

/**
 * Collect all snap points (ms) from the current EDL, excluding the dragged
 * element itself. Each segment and audio track contributes its start and end.
 * t=0 is always included.
 */
function collectSnapPoints(
  edl: EdlDto,
  excludeId: string,
  totalMs: number,
): number[] {
  const pts = new Set<number>();
  pts.add(0);
  pts.add(totalMs);

  for (const seg of edl.segments ?? []) {
    if (seg.id === excludeId) continue;
    pts.add(seg.start_ms);
    pts.add(seg.end_ms);
  }

  for (const t of edl.audio_tracks ?? []) {
    if (t.id === excludeId) continue;
    pts.add(t.start_ms ?? 0);
    pts.add(t.end_ms ?? totalMs);
  }

  return Array.from(pts).sort((a, b) => a - b);
}

/**
 * Try to snap a candidate ms value to the nearest snap point.
 * Returns the snapped value if within threshold, otherwise returns candidate.
 */
function snap(
  candidateMs: number,
  snapPoints: number[],
  pps: number,
  enabled: boolean,
): number {
  if (!enabled) return candidateMs;
  const threshMs = pxToMs(SNAP_PX, pps);
  let best = candidateMs;
  let bestDist = threshMs;
  for (const pt of snapPoints) {
    const dist = Math.abs(pt - candidateMs);
    if (dist < bestDist) {
      bestDist = dist;
      best = pt;
    }
  }
  return best;
}

/**
 * Compute next EDL state from drag + optional snapping.
 * For "move": we try to snap both the leading AND trailing edge,
 * prefer the closer snap, then shift the whole block.
 */
function computeDraft(
  d: DragState,
  deltaMs: number,
  cur: EdlDto,
  snapEnabled: boolean,
  pps: number,
): EdlDto {
  const totalMs = totalDurationFromSegments(cur);

  if (d.kind === "segment") {
    let nextStart = d.origStart;
    let nextEnd = d.origEnd;
    const len = d.origEnd - d.origStart;
    const snapPts = collectSnapPoints(cur, d.id, totalMs);

    if (d.mode === "move") {
      const rawStart = Math.max(0, d.origStart + deltaMs);
      const rawEnd = rawStart + len;

      if (snapEnabled) {
        const snappedStart = snap(rawStart, snapPts, pps, true);
        const snappedEnd = snap(rawEnd, snapPts, pps, true);
        // Pick whichever snap pulled less distance
        const distStart = Math.abs(snappedStart - rawStart);
        const distEnd = Math.abs(snappedEnd - rawEnd);
        if (distStart <= distEnd) {
          nextStart = snappedStart;
          nextEnd = nextStart + len;
        } else {
          nextEnd = snappedEnd;
          nextStart = nextEnd - len;
        }
      } else {
        nextStart = rawStart;
        nextEnd = rawEnd;
      }
      nextStart = Math.max(0, nextStart);
      nextEnd = nextStart + len;
    } else if (d.mode === "trim-left") {
      const rawStart = Math.max(
        0,
        Math.min(d.origEnd - 100, d.origStart + deltaMs),
      );
      nextStart = snap(rawStart, snapPts, pps, snapEnabled);
      nextStart = Math.max(0, Math.min(d.origEnd - 100, nextStart));
      nextEnd = d.origEnd;
    } else {
      const rawEnd = Math.max(d.origStart + 100, d.origEnd + deltaMs);
      nextEnd = snap(rawEnd, snapPts, pps, snapEnabled);
      nextEnd = Math.max(d.origStart + 100, nextEnd);
      nextStart = d.origStart;
    }

    return {
      ...cur,
      segments: cur.segments.map((s) =>
        s.id === d.id ? { ...s, start_ms: nextStart, end_ms: nextEnd } : s,
      ),
    };
  }

  // ── audio ──────────────────────────────────────────────────────────────────
  let nextStart = d.origStart;
  let nextEnd = d.origEnd;
  let nextTrimIn = d.origTrimIn;
  let nextTrimOut = d.origTrimOut;
  const len = d.origEnd - d.origStart;
  const snapPts = collectSnapPoints(cur, d.id, totalMs);

  if (d.mode === "move") {
    const rawStart = Math.max(0, d.origStart + deltaMs);
    const rawEnd = rawStart + len;

    if (snapEnabled) {
      const snappedStart = snap(rawStart, snapPts, pps, true);
      const snappedEnd = snap(rawEnd, snapPts, pps, true);
      const distStart = Math.abs(snappedStart - rawStart);
      const distEnd = Math.abs(snappedEnd - rawEnd);
      if (distStart <= distEnd) {
        nextStart = snappedStart;
        nextEnd = nextStart + len;
      } else {
        nextEnd = snappedEnd;
        nextStart = nextEnd - len;
      }
    } else {
      nextStart = rawStart;
      nextEnd = rawEnd;
    }
    nextStart = Math.max(0, nextStart);
    nextEnd = nextStart + len;
  } else if (d.mode === "trim-left") {
    const maxLeft = Math.min(d.origStart, d.origTrimIn);
    const maxRight = d.origEnd - 100 - d.origStart;
    const rawDelta = Math.max(-maxLeft, Math.min(maxRight, deltaMs));
    const rawStart = d.origStart + rawDelta;
    nextStart = snap(rawStart, snapPts, pps, snapEnabled);
    const clampedDelta = nextStart - d.origStart;
    const finalDelta = Math.max(-maxLeft, Math.min(maxRight, clampedDelta));
    nextStart = d.origStart + finalDelta;
    nextTrimIn = d.origTrimIn + finalDelta;
    nextEnd = d.origEnd;
  } else {
    const minLeft = -(d.origEnd - 100 - d.origStart);
    const rawDelta = Math.max(minLeft, deltaMs);
    const rawEnd = d.origEnd + rawDelta;
    nextEnd = snap(rawEnd, snapPts, pps, snapEnabled);
    const clampedDelta = Math.max(minLeft, nextEnd - d.origEnd);
    nextEnd = d.origEnd + clampedDelta;
    nextTrimOut = d.origTrimOut + clampedDelta;
    nextStart = d.origStart;
  }

  return {
    ...cur,
    audio_tracks: (cur.audio_tracks ?? []).map((t) =>
      t.id === d.id
        ? {
            ...t,
            start_ms: nextStart,
            end_ms: nextEnd,
            trim_in_ms: nextTrimIn,
            trim_out_ms: nextTrimOut,
          }
        : t,
    ),
  };
}

// ─── Timeline ─────────────────────────────────────────────────────────────────

export const Timeline = forwardRef<TimelineHandle, Props>(function Timeline(
  {
    edl,
    selectedSegmentId,
    onSelectSegment,
    selectedAudioId,
    onSelectAudio,
    onChange,
    playheadMs,
    onScrub,
  },
  ref,
) {
  const [pps, setPps] = useState(90);
  const [isScrubbing, setIsScrubbing] = useState(false);
  const [snapEnabled, setSnapEnabled] = useState(true);

  // Visual snap indicator: ms value that is currently snapping, or null
  const [snapIndicatorMs, setSnapIndicatorMs] = useState<number | null>(null);

  const [draftEdl, setDraftEdl] = useState<EdlDto | null>(null);
  const displayEdl = draftEdl ?? edl;

  const trackRef = useRef<HTMLDivElement>(null);
  const playheadRef = useRef<HTMLDivElement>(null);
  const ppsRef = useRef(pps);
  ppsRef.current = pps;

  const edlRef = useRef(edl);
  const onChangeRef = useRef(onChange);
  edlRef.current = edl;
  onChangeRef.current = onChange;

  const snapEnabledRef = useRef(snapEnabled);
  snapEnabledRef.current = snapEnabled;

  // Track Shift key for temporary snap override
  const shiftRef = useRef(false);

  const dragRef = useRef<DragState | null>(null);

  // ── Imperative playhead ─────────────────────────────────────────────────────

  useImperativeHandle(
    ref,
    () => ({
      updatePlayhead: (ms: number) => {
        const el = playheadRef.current;
        if (el)
          el.style.transform = `translateX(${msToPx(ms, ppsRef.current)}px)`;
      },
    }),
    [],
  );

  useEffect(() => {
    const el = playheadRef.current;
    if (el) el.style.transform = `translateX(${msToPx(playheadMs, pps)}px)`;
  }, [playheadMs, pps]);

  // ── Shift key tracking ─────────────────────────────────────────────────────

  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      shiftRef.current = e.shiftKey;
    };
    window.addEventListener("keydown", onKey);
    window.addEventListener("keyup", onKey);
    return () => {
      window.removeEventListener("keydown", onKey);
      window.removeEventListener("keyup", onKey);
    };
  }, []);

  // ── Drag loop ──────────────────────────────────────────────────────────────

  useEffect(() => {
    let raf = 0;
    let lastX = 0;
    let lastShift = false;

    const apply = () => {
      raf = 0;
      const d = dragRef.current;
      if (!d) return;

      // Shift key temporarily disables snap
      const shouldSnap = snapEnabledRef.current && !shiftRef.current;
      const deltaMs = pxToMs(lastX - d.startX, ppsRef.current);
      const next = computeDraft(
        d,
        deltaMs,
        edlRef.current,
        shouldSnap,
        ppsRef.current,
      );
      setDraftEdl(next);

      // Compute snap indicator: find the snapped edge position
      if (shouldSnap) {
        const totalMs = totalDurationFromSegments(edlRef.current);
        const snapPts = collectSnapPoints(edlRef.current, d.id, totalMs);
        const threshMs = pxToMs(SNAP_PX, ppsRef.current);

        // Find which edge actually snapped (check the moved element's new pos)
        let snappedAt: number | null = null;
        if (d.kind === "segment") {
          const seg = next.segments.find((s) => s.id === d.id);
          if (seg) {
            for (const pt of snapPts) {
              if (Math.abs(seg.start_ms - pt) < threshMs) {
                snappedAt = pt;
                break;
              }
              if (Math.abs(seg.end_ms - pt) < threshMs) {
                snappedAt = pt;
                break;
              }
            }
          }
        } else {
          const track = (next.audio_tracks ?? []).find((t) => t.id === d.id);
          if (track) {
            const s = track.start_ms ?? 0;
            const e = track.end_ms ?? totalMs;
            for (const pt of snapPts) {
              if (Math.abs(s - pt) < threshMs) {
                snappedAt = pt;
                break;
              }
              if (Math.abs(e - pt) < threshMs) {
                snappedAt = pt;
                break;
              }
            }
          }
        }
        setSnapIndicatorMs(snappedAt);
      } else {
        setSnapIndicatorMs(null);
      }
    };

    const onMove = (e: MouseEvent) => {
      if (!dragRef.current) return;
      e.preventDefault();
      lastX = e.clientX;
      lastShift = e.shiftKey;
      shiftRef.current = e.shiftKey;
      if (!raf) raf = requestAnimationFrame(apply);
    };

    const onUp = (e: MouseEvent) => {
      const d = dragRef.current;
      if (!d) return;
      if (raf) {
        cancelAnimationFrame(raf);
        raf = 0;
      }
      const shouldSnap = snapEnabledRef.current && !e.shiftKey;
      const deltaMs = pxToMs(e.clientX - d.startX, ppsRef.current);
      const committed = computeDraft(
        d,
        deltaMs,
        edlRef.current,
        shouldSnap,
        ppsRef.current,
      );
      dragRef.current = null;
      setDraftEdl(null);
      setSnapIndicatorMs(null);
      onChangeRef.current(committed);
      unlockSelection();
    };

    window.addEventListener("mousemove", onMove);
    window.addEventListener("mouseup", onUp);
    return () => {
      window.removeEventListener("mousemove", onMove);
      window.removeEventListener("mouseup", onUp);
      if (raf) cancelAnimationFrame(raf);
    };
  }, []);

  // ── Mousedown handlers ─────────────────────────────────────────────────────

  const onSegmentMouseDown = useCallback(
    (e: React.MouseEvent, seg: EdlSegment, mode: DragMode) => {
      e.stopPropagation();
      e.preventDefault();
      lockSelection();
      onSelectSegment(seg.id);
      onSelectAudio(null);
      dragRef.current = {
        kind: "segment",
        id: seg.id,
        mode,
        startX: e.clientX,
        origStart: seg.start_ms,
        origEnd: seg.end_ms,
      };
    },
    [onSelectSegment, onSelectAudio],
  );

  const onAudioMouseDown = useCallback(
    (e: React.MouseEvent, track: EdlAudioTrack, mode: DragMode) => {
      e.stopPropagation();
      e.preventDefault();
      lockSelection();
      onSelectAudio(track.id);
      onSelectSegment(null);
      const totalMs = totalDurationFromSegments(edlRef.current);
      const start = track.start_ms ?? 0;
      const end = track.end_ms ?? totalMs;
      dragRef.current = {
        kind: "audio",
        id: track.id,
        mode,
        startX: e.clientX,
        origStart: start,
        origEnd: end,
        origTrimIn: track.trim_in_ms ?? 0,
        origTrimOut: track.trim_out_ms ?? end,
      };
    },
    [onSelectAudio, onSelectSegment],
  );

  // ── Ruler drag-scrub ───────────────────────────────────────────────────────

  const onRulerMouseDown = useCallback(
    (e: React.MouseEvent) => {
      e.preventDefault();
      lockSelection();
      setIsScrubbing(true);

      const scrubTo = (clientX: number) => {
        if (!trackRef.current) return;
        const rect = trackRef.current.getBoundingClientRect();
        const x = Math.max(
          0,
          clientX - rect.left + trackRef.current.scrollLeft,
        );
        const ms = pxToMs(x, ppsRef.current);
        if (playheadRef.current)
          playheadRef.current.style.transform = `translateX(${msToPx(ms, ppsRef.current)}px)`;
        onScrub(ms);
      };

      scrubTo(e.clientX);

      const handleMove = (ev: MouseEvent) => scrubTo(ev.clientX);
      const handleUp = () => {
        setIsScrubbing(false);
        unlockSelection();
        window.removeEventListener("mousemove", handleMove);
        window.removeEventListener("mouseup", handleUp);
      };
      window.addEventListener("mousemove", handleMove);
      window.addEventListener("mouseup", handleUp);
    },
    [onScrub],
  );

  // ── Delete selected ────────────────────────────────────────────────────────

  const removeSelected = useCallback(() => {
    if (selectedSegmentId) {
      onChange({
        ...edl,
        segments: edl.segments.filter((s) => s.id !== selectedSegmentId),
      });
      onSelectSegment(null);
      return;
    }
    if (selectedAudioId) {
      onChange({
        ...edl,
        audio_tracks: (edl.audio_tracks ?? []).filter(
          (t) => t.id !== selectedAudioId,
        ),
      });
      onSelectAudio(null);
    }
  }, [
    selectedSegmentId,
    selectedAudioId,
    edl,
    onChange,
    onSelectSegment,
    onSelectAudio,
  ]);

  // ── Derived layout — all from displayEdl ──────────────────────────────────

  const layers = useMemo(
    () => groupByLayer(displayEdl.segments ?? []),
    [displayEdl.segments],
  );

  const audioRows = useMemo(() => {
    const map = new Map<string, EdlAudioTrack[]>();
    for (const t of displayEdl.audio_tracks ?? []) {
      const key = t.type === "voice" ? "voiceover" : t.type;
      if (!map.has(key)) map.set(key, []);
      map.get(key)!.push(t);
    }
    const order = ["voiceover", "music"];
    return Array.from(map.entries())
      .sort(([a], [b]) => {
        const ai = order.indexOf(a);
        const bi = order.indexOf(b);
        return (ai === -1 ? 99 : ai) - (bi === -1 ? 99 : bi);
      })
      .map(([type, tracks]) => ({ type, tracks }));
  }, [displayEdl.audio_tracks]);

  const textOverlays = displayEdl.text_overlays ?? [];
  const totalMs = totalDurationFromSegments(displayEdl);
  const totalSeconds = Math.ceil(totalMs / 1000) + 2;
  const widthPx = msToPx(totalSeconds * 1000, pps);
  const hasSelection = !!selectedSegmentId || !!selectedAudioId;

  // ── Render ─────────────────────────────────────────────────────────────────

  return (
    <div
      className={cn(
        "flex flex-col bg-[hsl(var(--surface-2))] border border-border rounded-xl overflow-hidden",
        isScrubbing && "select-none cursor-col-resize",
      )}
    >
      {/* toolbar */}
      <div className="flex items-center justify-between gap-3 px-3 py-2 border-b border-border bg-[hsl(var(--surface-3))]">
        <div className="flex items-center gap-2">
          <span className="text-xs font-medium text-muted-foreground uppercase tracking-wider">
            Timeline
          </span>
          <span className="text-xs font-mono text-muted-foreground">
            {formatTime(playheadMs)} / {formatTime(totalMs)}
          </span>
        </div>
        <div className="flex items-center gap-1">
          {/* Snap toggle */}
          <button
            onClick={() => setSnapEnabled((v) => !v)}
            className={cn(
              "size-7 rounded-md flex items-center justify-center transition",
              snapEnabled
                ? "bg-primary/15 text-primary hover:bg-primary/25"
                : "hover:bg-muted text-muted-foreground hover:text-foreground",
            )}
            title={
              snapEnabled ? "Snapping on (Shift to override)" : "Snapping off"
            }
          >
            <Magnet size={13} />
          </button>
          <div className="h-4 w-px bg-border mx-1" />
          <button
            onClick={removeSelected}
            disabled={!hasSelection}
            className="size-7 rounded-md hover:bg-muted text-muted-foreground hover:text-destructive disabled:opacity-40 disabled:hover:bg-transparent flex items-center justify-center transition"
            title="Delete selected"
          >
            <Trash2 size={13} />
          </button>
          <div className="h-4 w-px bg-border mx-1" />
          <button
            onClick={() => setPps((p) => Math.max(MIN_PPS, p - 20))}
            className="size-7 rounded-md hover:bg-muted text-muted-foreground hover:text-foreground flex items-center justify-center transition"
          >
            <Minus size={13} />
          </button>
          <span className="text-[10px] tabular-nums text-muted-foreground w-10 text-center">
            {pps}px/s
          </span>
          <button
            onClick={() => setPps((p) => Math.min(MAX_PPS, p + 20))}
            className="size-7 rounded-md hover:bg-muted text-muted-foreground hover:text-foreground flex items-center justify-center transition"
          >
            <Plus size={13} />
          </button>
          <button
            onClick={() => setPps(90)}
            className="size-7 rounded-md hover:bg-muted text-muted-foreground hover:text-foreground flex items-center justify-center transition"
            title="Reset zoom"
          >
            <Maximize size={13} />
          </button>
        </div>
      </div>

      <div className="flex max-h-[60vh]">
        {/* track labels */}
        <div className="shrink-0 w-32 border-r border-border bg-[hsl(var(--surface-3))]">
          <div style={{ height: HEADER_HEIGHT }} />
          {layers.map(({ layer }) => (
            <TrackLabel
              key={`v-${layer}`}
              icon={<Sparkles size={12} className="text-primary" />}
              label={layer === 0 ? "Video" : `V${layer + 1}`}
            />
          ))}
          {audioRows.map(({ type }) => (
            <TrackLabel
              key={`al-${type}`}
              icon={
                type === "music" ? (
                  <Music size={12} className="text-chart-2" />
                ) : (
                  <Mic size={12} className="text-chart-3" />
                )
              }
              label={type === "music" ? "Music" : "Voice"}
            />
          ))}
          {textOverlays.length > 0 && (
            <TrackLabel
              icon={<Type size={12} className="text-chart-4" />}
              label="Subs"
            />
          )}
        </div>

        {/* scrollable track area */}
        <div
          ref={trackRef}
          className="flex-1 overflow-x-auto overflow-y-auto scrollbar-thin"
        >
          <div style={{ width: widthPx }} className="relative">
            <Ruler
              pps={pps}
              totalSeconds={totalSeconds}
              onMouseDown={onRulerMouseDown}
              height={HEADER_HEIGHT}
              isScrubbing={isScrubbing}
            />

            {/* video tracks */}
            {layers.map(({ layer, segments }) => (
              <div
                key={`vt-${layer}`}
                className="relative border-b border-border/60"
                style={{ height: LAYER_HEIGHT }}
              >
                {segments.map((seg) => (
                  <SegmentBlock
                    key={seg.id}
                    seg={seg}
                    pps={pps}
                    selected={seg.id === selectedSegmentId}
                    onMouseDown={onSegmentMouseDown}
                  />
                ))}
              </div>
            ))}

            {/* audio rows */}
            {audioRows.map(({ type, tracks }) => (
              <div
                key={`arow-${type}`}
                className="relative border-b border-border/60"
                style={{ height: LAYER_HEIGHT }}
              >
                {tracks.map((track) => (
                  <AudioBlock
                    key={track.id}
                    track={track}
                    pps={pps}
                    totalMs={totalMs}
                    selected={track.id === selectedAudioId}
                    onMouseDown={onAudioMouseDown}
                  />
                ))}
              </div>
            ))}

            {/* subtitle strip */}
            {textOverlays.length > 0 && (
              <div
                className="relative border-b border-border/60"
                style={{ height: LAYER_HEIGHT }}
              >
                {textOverlays.map((to, i) => (
                  <SubBlock
                    key={to.id ?? i}
                    startMs={to.start_ms}
                    endMs={to.end_ms}
                    text={to.text}
                    pps={pps}
                  />
                ))}
              </div>
            )}

            {/* Snap indicator — vertical line across all tracks when snapping */}
            {snapIndicatorMs !== null && (
              <div
                className="absolute top-0 bottom-0 w-px z-30 pointer-events-none"
                style={{
                  left: msToPx(snapIndicatorMs, pps),
                  background: "rgba(250, 204, 21, 0.9)", // yellow-400
                  boxShadow: "0 0 4px 1px rgba(250, 204, 21, 0.5)",
                }}
              />
            )}

            {/* Playhead */}
            <div
              ref={playheadRef}
              className="absolute top-0 bottom-0 w-px bg-primary z-20 pointer-events-none"
              style={{
                left: 0,
                willChange: "transform",
                transform: `translateX(${msToPx(playheadMs, pps)}px)`,
              }}
            >
              <div className="size-3 rounded-full bg-primary -translate-x-1/2 -translate-y-1 shadow-glow" />
            </div>
          </div>
        </div>
      </div>
    </div>
  );
});

// ─── Sub-components ───────────────────────────────────────────────────────────

const TrackLabel = memo(
  ({ icon, label }: { icon: React.ReactNode; label: string }) => (
    <div
      className="border-b border-border/60 flex items-center gap-2 px-3 text-xs text-muted-foreground select-none"
      style={{ height: LAYER_HEIGHT }}
    >
      {icon}
      <span className="font-medium">{label}</span>
    </div>
  ),
);
TrackLabel.displayName = "TrackLabel";

const Ruler = memo(
  ({
    pps,
    totalSeconds,
    onMouseDown,
    height,
    isScrubbing,
  }: {
    pps: number;
    totalSeconds: number;
    onMouseDown: (e: React.MouseEvent) => void;
    height: number;
    isScrubbing: boolean;
  }) => {
    const ticks: number[] = [];
    for (let s = 0; s <= totalSeconds; s += RULER_TICK_SECONDS) ticks.push(s);
    return (
      <div
        onMouseDown={onMouseDown}
        className={cn(
          "relative border-b border-border bg-[hsl(var(--surface-3))] select-none cursor-col-resize",
          isScrubbing && "cursor-col-resize",
        )}
        style={{ height }}
      >
        {ticks.map((s) => (
          <div
            key={s}
            className="absolute top-0 bottom-0 border-l border-border/60 text-[10px] text-muted-foreground tabular-nums pl-1 pt-0.5 pointer-events-none select-none"
            style={{ left: msToPx(s * 1000, pps) }}
          >
            {s}s
          </div>
        ))}
      </div>
    );
  },
);
Ruler.displayName = "Ruler";

const SegmentBlock = memo(
  ({
    seg,
    pps,
    selected,
    onMouseDown,
  }: {
    seg: EdlSegment;
    pps: number;
    selected: boolean;
    onMouseDown: (e: React.MouseEvent, seg: EdlSegment, mode: DragMode) => void;
  }) => {
    const left = msToPx(seg.start_ms, pps);
    const width = Math.max(8, msToPx(seg.end_ms - seg.start_ms, pps));
    const hasEffects = (seg.effects?.length ?? 0) > 0;

    return (
      <div
        onMouseDown={(e) => onMouseDown(e, seg, "move")}
        className={cn(
          "absolute top-1.5 bottom-1.5 rounded-md border cursor-grab active:cursor-grabbing overflow-hidden select-none",
          selected
            ? "border-primary shadow-glow z-10"
            : "border-transparent hover:border-primary/50",
        )}
        style={{ left, width }}
      >
        <div
          className={cn(
            "absolute inset-0 pointer-events-none",
            seg.asset_type === "VIDEO"
              ? "bg-gradient-to-br from-violet-500/80 via-fuchsia-500/70 to-rose-500/70"
              : "bg-gradient-to-br from-sky-500/80 via-cyan-500/70 to-emerald-500/70",
          )}
        />
        {seg.asset_url && seg.asset_type === "IMAGE" && (
          <img
            src={seg.asset_url}
            className="absolute inset-0 size-full object-cover opacity-50 mix-blend-overlay pointer-events-none"
            alt=""
            draggable={false}
          />
        )}
        <div className="absolute inset-0 flex items-center px-2 text-[10px] font-medium text-white drop-shadow tracking-wide pointer-events-none select-none">
          <span className="truncate flex-1">
            {seg.asset_type} ·{" "}
            {Math.round((seg.end_ms - seg.start_ms) / 100) / 10}s
          </span>
          {hasEffects && <Sparkles size={10} className="ml-1 shrink-0" />}
        </div>
        <div
          onMouseDown={(e) => onMouseDown(e, seg, "trim-left")}
          className="absolute left-0 top-0 bottom-0 w-2 cursor-w-resize hover:bg-white/40 z-10"
        />
        <div
          onMouseDown={(e) => onMouseDown(e, seg, "trim-right")}
          className="absolute right-0 top-0 bottom-0 w-2 cursor-e-resize hover:bg-white/40 z-10"
        />
      </div>
    );
  },
);
SegmentBlock.displayName = "SegmentBlock";

const AudioBlock = memo(
  ({
    track,
    pps,
    totalMs,
    selected,
    onMouseDown,
  }: {
    track: EdlAudioTrack;
    pps: number;
    totalMs: number;
    selected: boolean;
    onMouseDown: (
      e: React.MouseEvent,
      track: EdlAudioTrack,
      mode: DragMode,
    ) => void;
  }) => {
    const start = track.start_ms ?? 0;
    const end = track.end_ms ?? totalMs;
    const left = msToPx(start, pps);
    const width = Math.max(8, msToPx(Math.max(0, end - start), pps));
    const isMusic = track.type === "music";

    return (
      <div
        onMouseDown={(e) => onMouseDown(e, track, "move")}
        className={cn(
          "absolute top-2 bottom-2 rounded-md overflow-hidden border cursor-grab active:cursor-grabbing select-none",
          selected
            ? "border-primary shadow-glow z-10"
            : "border-transparent hover:border-primary/50",
        )}
        style={{ left, width }}
      >
        <div
          className={cn(
            "absolute inset-0 pointer-events-none",
            isMusic
              ? "bg-gradient-to-r from-emerald-600/40 via-emerald-500/25 to-emerald-400/25"
              : "bg-gradient-to-r from-amber-600/40 via-amber-500/25 to-amber-400/25",
          )}
        />
        <div className="absolute inset-0 flex items-center gap-1 px-2 text-[10px] font-medium text-foreground/80 pointer-events-none select-none">
          {isMusic ? <Music size={10} /> : <Mic size={10} />}
          <span className="truncate">
            {isMusic ? "music" : "voice"} ·{" "}
            {Math.round((end - start) / 100) / 10}s
          </span>
        </div>
        <div
          onMouseDown={(e) => onMouseDown(e, track, "trim-left")}
          className="absolute left-0 top-0 bottom-0 w-2 cursor-w-resize hover:bg-white/40 z-10"
        />
        <div
          onMouseDown={(e) => onMouseDown(e, track, "trim-right")}
          className="absolute right-0 top-0 bottom-0 w-2 cursor-e-resize hover:bg-white/40 z-10"
        />
      </div>
    );
  },
);
AudioBlock.displayName = "AudioBlock";

const SubBlock = memo(
  ({
    startMs,
    endMs,
    text,
    pps,
  }: {
    startMs: number;
    endMs: number;
    text: string;
    pps: number;
  }) => (
    <div
      className="absolute top-2 bottom-2 rounded-md bg-amber-500/15 border border-amber-500/30 px-2 flex items-center text-[10px] truncate text-amber-700 dark:text-amber-300 select-none pointer-events-none"
      style={{
        left: msToPx(startMs, pps),
        width: Math.max(20, msToPx(endMs - startMs, pps)),
      }}
    >
      {text}
    </div>
  ),
);
SubBlock.displayName = "SubBlock";
