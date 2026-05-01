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
  onChange: (next: EdlDto) => void;
  playheadMs: number;
  onScrub: (ms: number) => void;
}

const LAYER_HEIGHT = 64;
const HEADER_HEIGHT = 32;
const RULER_TICK_SECONDS = 1;
const MIN_PPS = 30;
const MAX_PPS = 220;

export const Timeline = ({
  edl,
  selectedSegmentId,
  onSelectSegment,
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
  const textOverlays = edl.text_overlays ?? [];
  const totalMs = totalDurationFromSegments(edl);
  const totalSeconds = Math.ceil(totalMs / 1000) + 2;
  const widthPx = msToPx(totalSeconds * 1000, pps);

  /* drag state */
  const dragRef = useRef<{
    segId: string;
    startX: number;
    origStart: number;
    origEnd: number;
    mode: "move" | "trim-left" | "trim-right";
  } | null>(null);

  const onSegmentMouseDown = (
    e: React.MouseEvent,
    seg: EdlSegment,
    mode: "move" | "trim-left" | "trim-right",
  ) => {
    e.stopPropagation();
    onSelectSegment(seg.id);
    dragRef.current = {
      segId: seg.id,
      startX: e.clientX,
      origStart: seg.start_ms,
      origEnd: seg.end_ms,
      mode,
    };
    window.addEventListener("mousemove", onMouseMove);
    window.addEventListener("mouseup", onMouseUp);
  };

  const onMouseMove = (e: MouseEvent) => {
    const d = dragRef.current;
    if (!d) return;
    const deltaPx = e.clientX - d.startX;
    const deltaMs = pxToMs(deltaPx, pps);
    const seg = edl.segments.find((s) => s.id === d.segId);
    if (!seg) return;

    let nextStart = seg.start_ms;
    let nextEnd = seg.end_ms;

    if (d.mode === "move") {
      const len = d.origEnd - d.origStart;
      nextStart = Math.max(0, d.origStart + deltaMs);
      nextEnd = nextStart + len;
    } else if (d.mode === "trim-left") {
      nextStart = Math.max(0, Math.min(d.origEnd - 100, d.origStart + deltaMs));
    } else if (d.mode === "trim-right") {
      nextEnd = Math.max(d.origStart + 100, d.origEnd + deltaMs);
    }

    onChange({
      ...edl,
      segments: edl.segments.map((s) =>
        s.id === d.segId ? { ...s, start_ms: nextStart, end_ms: nextEnd } : s,
      ),
    });
  };

  const onMouseUp = () => {
    dragRef.current = null;
    window.removeEventListener("mousemove", onMouseMove);
    window.removeEventListener("mouseup", onMouseUp);
  };

  useEffect(() => () => onMouseUp(), []);

  const onRulerClick = (e: React.MouseEvent) => {
    if (!trackRef.current) return;
    const rect = trackRef.current.getBoundingClientRect();
    const x = e.clientX - rect.left + trackRef.current.scrollLeft;
    onScrub(pxToMs(x, pps));
  };

  const removeSelected = () => {
    if (!selectedSegmentId) return;
    onChange({
      ...edl,
      segments: edl.segments.filter((s) => s.id !== selectedSegmentId),
    });
    onSelectSegment(null);
  };

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

        {/* tracks scroll */}
        <div ref={trackRef} className="flex-1 overflow-x-auto overflow-y-auto scrollbar-thin">
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
                    />
                  ))
                ) : (
                  <AudioBlock
                    track={track}
                    pps={pps}
                    totalMs={totalMs}
                  />
                )}
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
  onMouseDown: (
    e: React.MouseEvent,
    seg: EdlSegment,
    mode: "move" | "trim-left" | "trim-right",
  ) => void;
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
      {/* gradient body */}
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
          {seg.asset_type} · {Math.round((seg.end_ms - seg.start_ms) / 100) / 10}s
        </span>
        {hasEffects && <Sparkles size={10} className="ml-1 shrink-0" />}
      </div>
      {/* trim handles */}
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
  overrideStart,
  overrideEnd,
}: {
  track: EdlAudioTrack;
  pps: number;
  totalMs: number;
  overrideStart?: number;
  overrideEnd?: number;
}) => {
  const start = overrideStart ?? track.start_ms ?? 0;
  const end = overrideEnd ?? track.end_ms ?? totalMs;
  const left = msToPx(start, pps);
  const width = msToPx(Math.max(0, end - start), pps);
  return (
    <div
      className={cn(
        "absolute top-2 bottom-2 rounded-md overflow-hidden border border-transparent",
        track.type === "music"
          ? "bg-gradient-to-r from-emerald-600/30 via-emerald-500/20 to-emerald-400/20"
          : "bg-gradient-to-r from-amber-600/30 via-amber-500/20 to-amber-400/20",
      )}
      style={{ left, width }}
    >
      <div className="flex items-center gap-1 px-2 h-full text-[10px] font-medium text-foreground/80">
        {track.type === "music" ? (
          <Music size={10} />
        ) : (
          <Mic size={10} />
        )}
        <span className="truncate">{track.type}</span>
      </div>
    </div>
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
