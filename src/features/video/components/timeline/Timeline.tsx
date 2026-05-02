import { useEffect, useMemo, useRef, useState } from "react";
import { motion } from "framer-motion";
import {
  Maximize,
  Minus,
  Music,
  Mic,
  Trash2,
  Plus,
  Type,
  Sparkles,
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

const LAYER_HEIGHT = 64;
const HEADER_HEIGHT = 32;
const RULER_TICK_SECONDS = 1;
const MIN_PPS = 30;
const MAX_PPS = 220;

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

export const Timeline = ({
  edl,
  selectedSegmentId,
  onSelectSegment,
  selectedAudioId,
  onSelectAudio,
  onChange,
  playheadMs,
  onScrub,
}: Props) => {
  const [pps, setPps] = useState(90);
  const trackRef = useRef<HTMLDivElement>(null);

  const layers = useMemo(
    () => groupByLayer(edl.segments ?? []),
    [edl.segments],
  );
  const audioTracks = edl.audio_tracks ?? [];

  /* group audio by row (one row per type, all clips of that type live in it) */
  const audioRows = useMemo(() => {
    const map = new Map<string, EdlAudioTrack[]>();
    for (const t of audioTracks) {
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
  }, [audioTracks]);

  const textOverlays = edl.text_overlays ?? [];
  const totalMs = totalDurationFromSegments(edl);
  const totalSeconds = Math.ceil(totalMs / 1000) + 2;
  const widthPx = msToPx(totalSeconds * 1000, pps);

  /* ── drag wiring ────────────────────────────────────────────────────────
     window listeners are registered ONCE on mount so we don't churn through
     stale closures every re-render. The handlers read everything fresh via
     refs, so each mousemove sees the latest edl/pps/onChange. */
  const dragRef = useRef<DragState | null>(null);
  const edlRef = useRef(edl);
  const ppsRef = useRef(pps);
  const onChangeRef = useRef(onChange);
  edlRef.current = edl;
  ppsRef.current = pps;
  onChangeRef.current = onChange;

  useEffect(() => {
    let raf = 0;
    let lastEvent: MouseEvent | null = null;

    const apply = () => {
      raf = 0;
      const e = lastEvent;
      const d = dragRef.current;
      if (!e || !d) return;

      const deltaMs = pxToMs(e.clientX - d.startX, ppsRef.current);
      const cur = edlRef.current;

      if (d.kind === "segment") {
        const seg = cur.segments.find((s) => s.id === d.id);
        if (!seg) return;

        let nextStart = seg.start_ms;
        let nextEnd = seg.end_ms;

        if (d.mode === "move") {
          const len = d.origEnd - d.origStart;
          nextStart = Math.max(0, d.origStart + deltaMs);
          nextEnd = nextStart + len;
        } else if (d.mode === "trim-left") {
          nextStart = Math.max(
            0,
            Math.min(d.origEnd - 100, d.origStart + deltaMs),
          );
        } else if (d.mode === "trim-right") {
          nextEnd = Math.max(d.origStart + 100, d.origEnd + deltaMs);
        }

        onChangeRef.current({
          ...cur,
          segments: cur.segments.map((s) =>
            s.id === d.id ? { ...s, start_ms: nextStart, end_ms: nextEnd } : s,
          ),
        });
        return;
      }

      // audio
      let nextStart = d.origStart;
      let nextEnd = d.origEnd;
      let nextTrimIn = d.origTrimIn;
      let nextTrimOut = d.origTrimOut;

      if (d.mode === "move") {
        // Reposition on timeline; the slice (trim_in/trim_out) plays the
        // same content, just at a different time.
        const len = d.origEnd - d.origStart;
        nextStart = Math.max(0, d.origStart + deltaMs);
        nextEnd = nextStart + len;
      } else if (d.mode === "trim-left") {
        // start_ms and trim_in_ms shift together. Clamp so trim_in stays >=0,
        // start stays >=0, and the block stays at least 100ms wide.
        const maxLeft = Math.min(d.origStart, d.origTrimIn);
        const maxRight = d.origEnd - 100 - d.origStart;
        const clamped = Math.max(-maxLeft, Math.min(maxRight, deltaMs));
        nextStart = d.origStart + clamped;
        nextTrimIn = d.origTrimIn + clamped;
      } else if (d.mode === "trim-right") {
        // end_ms and trim_out_ms shift together.
        const minLeft = -(d.origEnd - 100 - d.origStart);
        const clamped = Math.max(minLeft, deltaMs);
        nextEnd = d.origEnd + clamped;
        nextTrimOut = d.origTrimOut + clamped;
      }

      onChangeRef.current({
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
      });
    };

    const onMove = (e: MouseEvent) => {
      if (!dragRef.current) return;
      e.preventDefault(); // suppress text/image selection while dragging
      lastEvent = e;
      if (!raf) raf = requestAnimationFrame(apply);
    };

    const onUp = () => {
      dragRef.current = null;
      lastEvent = null;
      if (raf) {
        cancelAnimationFrame(raf);
        raf = 0;
      }
    };

    window.addEventListener("mousemove", onMove);
    window.addEventListener("mouseup", onUp);
    return () => {
      window.removeEventListener("mousemove", onMove);
      window.removeEventListener("mouseup", onUp);
      if (raf) cancelAnimationFrame(raf);
    };
  }, []);

  const onSegmentMouseDown = (
    e: React.MouseEvent,
    seg: EdlSegment,
    mode: DragMode,
  ) => {
    e.stopPropagation();
    e.preventDefault();
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
  };

  const onAudioMouseDown = (
    e: React.MouseEvent,
    track: EdlAudioTrack,
    mode: DragMode,
  ) => {
    e.stopPropagation();
    e.preventDefault();
    onSelectAudio(track.id);
    onSelectSegment(null);
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
  };

  const onRulerClick = (e: React.MouseEvent) => {
    if (!trackRef.current) return;
    const rect = trackRef.current.getBoundingClientRect();
    const x = e.clientX - rect.left + trackRef.current.scrollLeft;
    onScrub(pxToMs(x, pps));
  };

  const removeSelected = () => {
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
  };

  const hasSelection = !!selectedSegmentId || !!selectedAudioId;

  return (
    <div className="flex flex-col bg-[hsl(var(--surface-2))] border border-border rounded-xl overflow-hidden">
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
              key={`a-${type}`}
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

        {/* tracks scroll */}
        <div
          ref={trackRef}
          className="flex-1 overflow-x-auto overflow-y-auto scrollbar-thin"
        >
          <div style={{ width: widthPx }} className="relative">
            <Ruler
              pps={pps}
              totalSeconds={totalSeconds}
              onClick={onRulerClick}
              height={HEADER_HEIGHT}
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

            {/* audio rows — one per type, all clips of that type live here */}
            {audioRows.map(({ type, tracks }) => (
              <div
                key={`atrow-${type}`}
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

            {/* subtitles strip */}
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

            {/* playhead */}
            <div
              className="absolute top-0 bottom-0 w-px bg-primary z-20 pointer-events-none"
              style={{ left: msToPx(playheadMs, pps) }}
            >
              <div className="size-3 rounded-full bg-primary -translate-x-1/2 -translate-y-1 shadow-glow" />
            </div>
          </div>
        </div>
      </div>
    </div>
  );
};

const TrackLabel = ({
  icon,
  label,
}: {
  icon: React.ReactNode;
  label: string;
}) => (
  <div
    className="border-b border-border/60 flex items-center gap-2 px-3 text-xs text-muted-foreground"
    style={{ height: LAYER_HEIGHT }}
  >
    {icon}
    <span className="font-medium">{label}</span>
  </div>
);

const Ruler = ({
  pps,
  totalSeconds,
  onClick,
  height,
}: {
  pps: number;
  totalSeconds: number;
  onClick: (e: React.MouseEvent) => void;
  height: number;
}) => {
  const ticks = [];
  for (let s = 0; s <= totalSeconds; s += RULER_TICK_SECONDS) {
    ticks.push(s);
  }
  return (
    <div
      onClick={onClick}
      className="relative cursor-crosshair border-b border-border bg-[hsl(var(--surface-3))]"
      style={{ height }}
    >
      {ticks.map((s) => (
        <div
          key={s}
          className="absolute top-0 bottom-0 border-l border-border/60 text-[10px] text-muted-foreground tabular-nums pl-1 pt-0.5"
          style={{ left: msToPx(s * 1000, pps) }}
        >
          {s}s
        </div>
      ))}
    </div>
  );
};

const SegmentBlock = ({
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
    <motion.div
      layout
      onMouseDown={(e) => onMouseDown(e, seg, "move")}
      className={cn(
        "absolute top-1.5 bottom-1.5 rounded-md border cursor-grab active:cursor-grabbing overflow-hidden select-none transition-shadow",
        selected
          ? "border-primary shadow-glow z-10"
          : "border-transparent hover:border-primary/50",
      )}
      style={{ left, width }}
    >
      <div
        className={cn(
          "absolute inset-0",
          seg.asset_type === "VIDEO"
            ? "bg-gradient-to-br from-violet-500/80 via-fuchsia-500/70 to-rose-500/70"
            : "bg-gradient-to-br from-sky-500/80 via-cyan-500/70 to-emerald-500/70",
        )}
      />
      {seg.asset_url && seg.asset_type === "IMAGE" && (
        <img
          src={seg.asset_url}
          className="absolute inset-0 size-full object-cover opacity-50 mix-blend-overlay"
          alt=""
        />
      )}
      <div className="absolute inset-0 flex items-center px-2 text-[10px] font-medium text-white drop-shadow tracking-wide">
        <span className="truncate flex-1">
          {seg.asset_type} ·{" "}
          {Math.round((seg.end_ms - seg.start_ms) / 100) / 10}s
        </span>
        {hasEffects && <Sparkles size={10} className="ml-1 shrink-0" />}
      </div>
      <div
        onMouseDown={(e) => onMouseDown(e, seg, "trim-left")}
        className="absolute left-0 top-0 bottom-0 w-1.5 cursor-w-resize hover:bg-white/40"
      />
      <div
        onMouseDown={(e) => onMouseDown(e, seg, "trim-right")}
        className="absolute right-0 top-0 bottom-0 w-1.5 cursor-e-resize hover:bg-white/40"
      />
    </motion.div>
  );
};

const AudioBlock = ({
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
    <motion.div
      layout
      onMouseDown={(e) => onMouseDown(e, track, "move")}
      className={cn(
        "absolute top-2 bottom-2 rounded-md overflow-hidden border cursor-grab active:cursor-grabbing select-none transition-shadow",
        selected
          ? "border-primary shadow-glow z-10"
          : "border-transparent hover:border-primary/50",
      )}
      style={{ left, width }}
    >
      <div
        className={cn(
          "absolute inset-0",
          isMusic
            ? "bg-gradient-to-r from-emerald-600/40 via-emerald-500/25 to-emerald-400/25"
            : "bg-gradient-to-r from-amber-600/40 via-amber-500/25 to-amber-400/25",
        )}
      />
      <div className="absolute inset-0 flex items-center gap-1 px-2 text-[10px] font-medium text-foreground/80">
        {isMusic ? <Music size={10} /> : <Mic size={10} />}
        <span className="truncate">
          {isMusic ? "music" : "voice"} ·{" "}
          {Math.round((end - start) / 100) / 10}s
        </span>
      </div>
      <div
        onMouseDown={(e) => onMouseDown(e, track, "trim-left")}
        className="absolute left-0 top-0 bottom-0 w-1.5 cursor-w-resize hover:bg-white/40"
      />
      <div
        onMouseDown={(e) => onMouseDown(e, track, "trim-right")}
        className="absolute right-0 top-0 bottom-0 w-1.5 cursor-e-resize hover:bg-white/40"
      />
    </motion.div>
  );
};

const SubBlock = ({
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
    className="absolute top-2 bottom-2 rounded-md bg-amber-500/15 border border-amber-500/30 px-2 flex items-center text-[10px] truncate text-amber-700 dark:text-amber-300"
    style={{
      left: msToPx(startMs, pps),
      width: Math.max(20, msToPx(endMs - startMs, pps)),
    }}
  >
    {text}
  </div>
);
