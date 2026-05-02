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

// ─── Types ────────────────────────────────────────────────────────────────────

interface Props {
  edl: EdlDto;
  selectedSegmentId: string | null;
  onSelectSegment: (id: string | null) => void;
  onChange: (next: EdlDto) => void;
  playheadMs: number;
  onScrub: (ms: number) => void;
}

type DragMode = "move" | "trim-left" | "trim-right";

interface SegmentDragState {
  segId: string;
  startX: number;
  origStart: number;
  origEnd: number;
  mode: DragMode;
}

interface AudioDragState {
  trackId: string;
  startX: number;
  origStart: number;
  origEnd: number;
}

// ─── Helpers ──────────────────────────────────────────────────────────────────

/** Prevent all text / image selection on the page during drag operations. */
function lockSelection() {
  document.body.style.userSelect = "none";
  // @ts-ignore — vendor prefix still needed in some environments
  document.body.style.webkitUserSelect = "none";
}

function unlockSelection() {
  document.body.style.userSelect = "";
  // @ts-ignore
  document.body.style.webkitUserSelect = "";
}

// ─── Constants ────────────────────────────────────────────────────────────────

const LAYER_HEIGHT = 64;
const HEADER_HEIGHT = 32;
const RULER_TICK_SECONDS = 1;
const MIN_PPS = 30;
const MAX_PPS = 220;

// ─── Timeline ─────────────────────────────────────────────────────────────────

export const Timeline = forwardRef<TimelineHandle, Props>(
  (
    { edl, selectedSegmentId, onSelectSegment, onChange, playheadMs, onScrub },
    ref,
  ) => {
    const [pps, setPps] = useState(90);
    const trackRef = useRef<HTMLDivElement>(null);
    const playheadRef = useRef<HTMLDivElement>(null);
    const ppsRef = useRef(pps);
    ppsRef.current = pps;

    // ── Imperative playhead (no React re-render during playback) ──────────────

    useImperativeHandle(
      ref,
      () => ({
        updatePlayhead: (ms: number) => {
          if (playheadRef.current) {
            playheadRef.current.style.transform = `translateX(${msToPx(ms, ppsRef.current)}px)`;
          }
        },
      }),
      [],
    );

    // Sync when prop changes (scrub from parent) or zoom changes
    useEffect(() => {
      if (playheadRef.current) {
        playheadRef.current.style.transform = `translateX(${msToPx(playheadMs, pps)}px)`;
      }
    }, [playheadMs, pps]);

    // ── Local drag state (isolates re-renders inside Timeline) ────────────────

    const [dragEdl, setDragEdl] = useState<EdlDto | null>(null);
    const [isDragging, setIsDragging] = useState(false);
    const [isScrubbing, setIsScrubbing] = useState(false);

    const dragEdlRef = useRef<EdlDto | null>(null);
    const segDragRef = useRef<SegmentDragState | null>(null);
    const audioDragRef = useRef<AudioDragState | null>(null);
    const edlRef = useRef(edl);
    edlRef.current = edl;

    const displayEdl = dragEdl ?? edl;

    // ── Segment drag ──────────────────────────────────────────────────────────

    const onSegmentMouseDown = useCallback(
      (e: React.MouseEvent, seg: EdlSegment, mode: DragMode) => {
        e.stopPropagation();
        e.preventDefault();
        lockSelection();

        onSelectSegment(seg.id);
        segDragRef.current = {
          segId: seg.id,
          startX: e.clientX,
          origStart: seg.start_ms,
          origEnd: seg.end_ms,
          mode,
        };
        dragEdlRef.current = null;
        setIsDragging(true);

        function handleMove(ev: MouseEvent) {
          const d = segDragRef.current;
          if (!d) return;

          const deltaMs = pxToMs(ev.clientX - d.startX, ppsRef.current);
          let nextStart: number;
          let nextEnd: number;

          if (d.mode === "move") {
            const len = d.origEnd - d.origStart;
            nextStart = Math.max(0, d.origStart + deltaMs);
            nextEnd = nextStart + len;
          } else if (d.mode === "trim-left") {
            nextStart = Math.max(
              0,
              Math.min(d.origEnd - 100, d.origStart + deltaMs),
            );
            nextEnd = d.origEnd;
          } else {
            nextStart = d.origStart;
            nextEnd = Math.max(d.origStart + 100, d.origEnd + deltaMs);
          }

          const base = edlRef.current;
          const newEdl: EdlDto = {
            ...base,
            segments: base.segments.map(
              (s) =>
                s.id === d.segId
                  ? { ...s, start_ms: nextStart, end_ms: nextEnd }
                  : s, // same reference → React.memo skips unchanged segments
            ),
          };
          dragEdlRef.current = newEdl;
          setDragEdl(newEdl);
        }

        function handleUp() {
          const committed = dragEdlRef.current;
          if (committed) onChange(committed); // single propagation to parent
          dragEdlRef.current = null;
          segDragRef.current = null;
          setDragEdl(null);
          setIsDragging(false);
          unlockSelection();
          window.removeEventListener("mousemove", handleMove);
          window.removeEventListener("mouseup", handleUp);
        }

        window.addEventListener("mousemove", handleMove);
        window.addEventListener("mouseup", handleUp);
      },
      [onSelectSegment, onChange],
    );

    // ── Ruler drag-scrub (DaVinci / CapCut style) ─────────────────────────────
    //
    //  mousedown → seek immediately, enter scrub mode
    //  mousemove → move playhead imperatively + seek player
    //  mouseup   → end scrub

    const onRulerMouseDown = useCallback(
      (e: React.MouseEvent) => {
        e.preventDefault();
        lockSelection();
        setIsScrubbing(true);

        function scrubTo(clientX: number) {
          if (!trackRef.current) return;
          const rect = trackRef.current.getBoundingClientRect();
          const x = Math.max(
            0,
            clientX - rect.left + trackRef.current.scrollLeft,
          );
          const ms = pxToMs(x, ppsRef.current);

          // 1. Move the needle instantly — pure DOM, no React
          if (playheadRef.current) {
            playheadRef.current.style.transform = `translateX(${msToPx(ms, ppsRef.current)}px)`;
          }

          // 2. Sync player + update parent display state
          onScrub(ms);
        }

        // Seek on initial press (feels snappy)
        scrubTo(e.clientX);

        function handleMove(ev: MouseEvent) {
          scrubTo(ev.clientX);
        }

        function handleUp() {
          setIsScrubbing(false);
          unlockSelection();
          window.removeEventListener("mousemove", handleMove);
          window.removeEventListener("mouseup", handleUp);
        }

        window.addEventListener("mousemove", handleMove);
        window.addEventListener("mouseup", handleUp);
      },
      [onScrub],
    );

    const onAudioMouseDown = useCallback(
      (e: React.MouseEvent, track: EdlAudioTrack) => {
        e.stopPropagation();
        e.preventDefault();
        lockSelection();

        const originalEnd =
          track.end_ms == null ? track.start_ms + 1000 : track.end_ms;

        audioDragRef.current = {
          trackId: track.id,
          startX: e.clientX,
          origStart: track.start_ms ?? 0,
          origEnd: originalEnd,
        };
        dragEdlRef.current = null;
        setIsDragging(true);

        function handleMove(ev: MouseEvent) {
          const d = audioDragRef.current;
          if (!d) return;

          const deltaMs = pxToMs(ev.clientX - d.startX, ppsRef.current);
          const len = Math.max(100, d.origEnd - d.origStart);
          const nextStart = Math.max(0, d.origStart + deltaMs);
          const nextEnd = nextStart + len;

          const base = edlRef.current;
          const newEdl: EdlDto = {
            ...base,
            audio_tracks: (base.audio_tracks ?? []).map((t) =>
              t.id === d.trackId
                ? { ...t, start_ms: nextStart, end_ms: nextEnd }
                : t,
            ),
          };
          dragEdlRef.current = newEdl;
          setDragEdl(newEdl);
        }

        function handleUp() {
          const committed = dragEdlRef.current;
          if (committed) onChange(committed);
          dragEdlRef.current = null;
          audioDragRef.current = null;
          setDragEdl(null);
          setIsDragging(false);
          unlockSelection();
          window.removeEventListener("mousemove", handleMove);
          window.removeEventListener("mouseup", handleUp);
        }

        window.addEventListener("mousemove", handleMove);
        window.addEventListener("mouseup", handleUp);
      },
      [onChange],
    );

    // ── Toolbar ───────────────────────────────────────────────────────────────

    const removeSelected = useCallback(() => {
      if (!selectedSegmentId) return;
      onChange({
        ...edl,
        segments: edl.segments.filter((s) => s.id !== selectedSegmentId),
      });
      onSelectSegment(null);
    }, [selectedSegmentId, edl, onChange, onSelectSegment]);

    // ── Derived layout ────────────────────────────────────────────────────────

    const layers = useMemo(
      () => groupByLayer(displayEdl.segments ?? []),
      [displayEdl.segments],
    );
    const audioTracks = displayEdl.audio_tracks ?? [];
    const textOverlays = displayEdl.text_overlays ?? [];
    const totalMs = totalDurationFromSegments(displayEdl);
    const totalSeconds = Math.ceil(totalMs / 1000) + 2;
    const widthPx = msToPx(totalSeconds * 1000, pps);

    // ── Render ────────────────────────────────────────────────────────────────

    return (
      <div
        className={cn(
          "flex flex-col bg-[hsl(var(--surface-2))] border border-border rounded-xl overflow-hidden",
          (isDragging || isScrubbing) && "select-none",
          isScrubbing && "cursor-col-resize",
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
            <button
              onClick={removeSelected}
              disabled={!selectedSegmentId}
              className="size-7 rounded-md hover:bg-muted text-muted-foreground hover:text-destructive disabled:opacity-40 disabled:hover:bg-transparent flex items-center justify-center transition"
              title="Delete segment"
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
            {audioTracks.map((t) => (
              <TrackLabel
                key={`a-${t.id}`}
                icon={
                  t.type === "music" ? (
                    <Music size={12} className="text-chart-2" />
                  ) : (
                    <Mic size={12} className="text-chart-3" />
                  )
                }
                label={t.type === "music" ? "Music" : "Voice"}
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

              {/* audio tracks */}
              {audioTracks.map((track) => (
                <div
                  key={`at-${track.id}`}
                  className="relative border-b border-border/60"
                  style={{ height: LAYER_HEIGHT }}
                >
                  {track.type === "voiceover" || track.type === "voice" ? (
                    /* one block per video segment so voice mirrors scene structure */
                    layers[0]?.segments.map((seg) => (
                      <AudioBlock
                        key={seg.id}
                        track={track}
                        pps={pps}
                        totalMs={totalMs}
                        overrideStart={seg.start_ms}
                        overrideEnd={seg.end_ms}
                        draggable={false}
                      />
                    ))
                  ) : (
                    <AudioBlock
                      track={track}
                      pps={pps}
                      totalMs={totalMs}
                      onMouseDown={onAudioMouseDown}
                    />
                  )}
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

              {/* Playhead — position driven by DOM style, not React state */}
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
  },
);

Timeline.displayName = "Timeline";

// ─── Sub-components (memo'd — only re-render when own props change) ────────────

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
    for (let s = 0; s <= totalSeconds; s += RULER_TICK_SECONDS) {
      ticks.push(s);
    }
    return (
      <div
        onMouseDown={onMouseDown}
        className={cn(
          "relative border-b border-border bg-[hsl(var(--surface-3))] select-none",
          isScrubbing ? "cursor-col-resize" : "cursor-col-resize",
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

interface SegmentBlockProps {
  seg: EdlSegment;
  pps: number;
  selected: boolean;
  onMouseDown: (e: React.MouseEvent, seg: EdlSegment, mode: DragMode) => void;
}

const SegmentBlock = memo(
  ({ seg, pps, selected, onMouseDown }: SegmentBlockProps) => {
    const left = msToPx(seg.start_ms, pps);
    const width = Math.max(8, msToPx(seg.end_ms - seg.start_ms, pps));
    const hasEffects = (seg.effects?.length ?? 0) > 0;

    return (
      <div
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
        {/* trim handles */}
        <div
          onMouseDown={(e) => onMouseDown(e, seg, "trim-left")}
          className="absolute left-0 top-0 bottom-0 w-2 cursor-w-resize hover:bg-white/30 z-10"
        />
        <div
          onMouseDown={(e) => onMouseDown(e, seg, "trim-right")}
          className="absolute right-0 top-0 bottom-0 w-2 cursor-e-resize hover:bg-white/30 z-10"
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
    overrideStart,
    overrideEnd,
    onMouseDown,
    draggable = true,
  }: {
    track: EdlAudioTrack;
    pps: number;
    totalMs: number;
    overrideStart?: number;
    overrideEnd?: number;
    onMouseDown?: (e: React.MouseEvent, track: EdlAudioTrack) => void;
    draggable?: boolean;
  }) => {
    const start = overrideStart ?? track.start_ms ?? 0;
    const end = overrideEnd ?? track.end_ms ?? totalMs;
    const left = msToPx(start, pps);
    const width = msToPx(Math.max(0, end - start), pps);

    return (
      <div
        onMouseDown={
          draggable && onMouseDown ? (e) => onMouseDown(e, track) : undefined
        }
        className={cn(
          "absolute top-2 bottom-2 rounded-md overflow-hidden border border-transparent",
          draggable && onMouseDown && "cursor-grab active:cursor-grabbing",
          track.type === "music"
            ? "bg-gradient-to-r from-emerald-600/30 via-emerald-500/20 to-emerald-400/20"
            : "bg-gradient-to-r from-amber-600/30 via-amber-500/20 to-amber-400/20",
        )}
        style={{ left, width }}
      >
        <div className="flex items-center gap-1 px-2 h-full text-[10px] font-medium text-foreground/80 select-none pointer-events-none">
          {track.type === "music" ? <Music size={10} /> : <Mic size={10} />}
          <span className="truncate">{track.type}</span>
        </div>
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
