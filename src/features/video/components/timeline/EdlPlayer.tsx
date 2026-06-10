import {
  useCallback,
  useEffect,
  useImperativeHandle,
  useRef,
  useState,
  forwardRef,
  useMemo,
} from "react";
import { useQuery } from "@tanstack/react-query";
import axiosInstance from "@/lib/axios";
import type {
  EdlAudioTrack,
  EdlDto,
  EdlSegment,
  EdlTextOverlay,
  SubtitlePosition,
} from "../../types";
import { absoluteUrl } from "../../api";
import { totalDurationFromSegments } from "./timelineUtils";
import {
  SUBTITLE_ZONE_Y,
  activeGroupAt,
  groupWhisperWords,
  highlightColorFor,
  subtitlesEnabled,
  toSubtitlePosition,
  withSubtitleConfig,
} from "./subtitleUtils";

/**
 * Fetches any API-relative URL (e.g. "/api/assets/file/...") with the JWT
 * bearer header and returns a blob: URL safe for <video>/<img> src.
 * Falls back to asset_id-based endpoint if asset_url is missing.
 * Cached forever per URL — remounting reuses the blob.
 */
const useSegmentBlobUrl = (segment: EdlSegment): string | null => {
  // Prefer asset_url stored directly on the segment (always present in EDL
  // built by AssetBridgeService). Fall back to the /api/assets/file/{id} path.
  const rawUrl = segment.asset_url ?? `/api/assets/file/${segment.asset_id}`;
  const url = absoluteUrl(rawUrl) ?? rawUrl;

  const { data } = useQuery({
    queryKey: ["segment-blob", url],
    queryFn: async () => {
      const res = await axiosInstance.get(url, { responseType: "blob" });
      return URL.createObjectURL(res.data as Blob);
    },
    enabled: !!url,
    staleTime: Infinity,
    gcTime: 1000 * 60 * 30,
    retry: false,
  });
  return data ?? null;
};

/** Same blob-fetch pattern for audio tracks. */
const useAudioBlobUrl = (track: EdlAudioTrack): string | null => {
  const rawUrl = track.asset_url ?? `/api/assets/file/${track.asset_id}`;
  const url = absoluteUrl(rawUrl) ?? rawUrl;

  const { data } = useQuery({
    queryKey: ["audio-blob", url],
    queryFn: async () => {
      const res = await axiosInstance.get(url, { responseType: "blob" });
      return URL.createObjectURL(res.data as Blob);
    },
    enabled: !!url,
    staleTime: Infinity,
    gcTime: 1000 * 60 * 30,
    retry: false,
  });
  return data ?? null;
};

// ─── Public API ──────────────────────────────────────────────────────────────

export interface EdlPlayerHandle {
  play: () => void;
  pause: () => void;
  seek: (ms: number) => void;
  isPlaying: boolean;
  currentMs: number;
}

interface Props {
  edl: EdlDto;
  onTimeUpdate?: (ms: number) => void;
  onPlayStateChange?: (playing: boolean) => void;
  className?: string;
  /** 0–1, default 1. Multiplied with per-track volumes. */
  masterVolume?: number;
  /**
   * When provided, subtitles and text overlays become draggable in the
   * preview — dragging commits position changes back into the EDL.
   */
  onEdlChange?: (next: EdlDto) => void;
}

// ─── Audio track player ───────────────────────────────────────────────────────

interface AudioTrackPlayerProps {
  track: EdlAudioTrack;
  currentMs: number;
  playing: boolean;
  totalMs: number;
  /** 0–1 master volume multiplied with per-track volume */
  masterVolume: number;
}

const AudioTrackPlayer = ({
  track,
  currentMs,
  playing,
  totalMs,
  masterVolume,
}: AudioTrackPlayerProps) => {
  const blobUrl = useAudioBlobUrl(track);
  const audioRef = useRef<HTMLAudioElement>(null);

  const startMs = track.start_ms ?? 0;
  const endMs = track.end_ms ?? totalMs;
  const trackVolume = track.volume ?? (track.type === "music" ? 0.45 : 1.0);

  // Keep audio element volume in sync with master whenever it changes
  useEffect(() => {
    const a = audioRef.current;
    if (!a) return;
    a.volume = Math.min(1, trackVolume * masterVolume);
  }, [masterVolume, trackVolume]);

  // Sync currentTime when blobUrl first loads or on seek
  useEffect(() => {
    const a = audioRef.current;
    if (!a || !blobUrl) return;
    a.volume = Math.min(1, trackVolume * masterVolume);
    const localSec = (currentMs - startMs) / 1000;
    if (localSec >= 0 && Math.abs(a.currentTime - localSec) > 0.3) {
      a.currentTime = Math.max(0, localSec);
    }
    // Only run on blobUrl change (initial load) — seek is handled separately
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [blobUrl]);

  // Play / pause based on whether currentMs is within this track's window
  useEffect(() => {
    const a = audioRef.current;
    if (!a || !blobUrl) return;
    const inWindow = currentMs >= startMs && currentMs < endMs;
    if (playing && inWindow) {
      // Resync before playing to catch scrubs
      const localSec = (currentMs - startMs) / 1000;
      if (Math.abs(a.currentTime - localSec) > 0.3) {
        a.currentTime = Math.max(0, localSec);
      }
      a.play().catch(() => {});
    } else {
      a.pause();
    }
  }, [playing, currentMs, startMs, endMs, blobUrl]);

  if (!blobUrl) return null;

  return (
    <audio
      ref={audioRef}
      src={blobUrl}
      preload="auto"
      style={{ display: "none" }}
    />
  );
};

// ─── Segment player (single asset) ───────────────────────────────────────────

interface SegmentViewProps {
  segment: EdlSegment;
  /** ms into this segment (0 = start of segment) */
  localMs: number;
  playing: boolean;
  visible: boolean;
}

const SegmentView = ({
  segment,
  localMs,
  playing,
  visible,
}: SegmentViewProps) => {
  const blobUrl = useSegmentBlobUrl(segment);
  const videoRef = useRef<HTMLVideoElement>(null);
  const lastSyncRef = useRef<number>(-1);

  // Keep video currentTime in sync with localMs
  useEffect(() => {
    const v = videoRef.current;
    if (!v || !blobUrl || segment.asset_type !== "VIDEO") return;

    const targetSec = localMs / 1000;
    // Only seek if drift > 200 ms to avoid thrashing
    if (Math.abs(v.currentTime - targetSec) > 0.2) {
      v.currentTime = targetSec;
      lastSyncRef.current = localMs;
    }
  }, [localMs, blobUrl, segment.asset_type]);

  // Play / pause
  useEffect(() => {
    const v = videoRef.current;
    if (!v || !blobUrl || segment.asset_type !== "VIDEO") return;
    if (playing && visible) {
      v.play().catch(() => {});
    } else {
      v.pause();
    }
  }, [playing, visible, blobUrl, segment.asset_type]);

  if (!blobUrl) {
    return (
      <div
        className="absolute inset-0 flex items-center justify-center bg-black"
        style={{ opacity: visible ? 1 : 0 }}
      >
        <div className="size-6 border-2 border-white/30 border-t-white rounded-full animate-spin" />
      </div>
    );
  }

  if (segment.asset_type === "IMAGE") {
    return (
      <img
        src={blobUrl}
        alt=""
        className="absolute inset-0 size-full object-contain"
        style={{
          opacity: visible ? 1 : 0,
          transition: "opacity 80ms linear",
        }}
      />
    );
  }

  return (
    <video
      ref={videoRef}
      src={blobUrl}
      playsInline
      muted
      preload="auto"
      className="absolute inset-0 size-full object-contain"
      style={{
        opacity: visible ? 1 : 0,
        transition: "opacity 80ms linear",
      }}
    />
  );
};

// ─── Main player ─────────────────────────────────────────────────────────────

export const EdlPlayer = forwardRef<EdlPlayerHandle, Props>(
  (
    {
      edl,
      onTimeUpdate,
      onPlayStateChange,
      className,
      masterVolume = 1,
      onEdlChange,
    },
    ref,
  ) => {
    const [currentMs, setCurrentMs] = useState(0);
    const [playing, setPlaying] = useState(false);

    const rafRef = useRef<number | null>(null);
    const lastWallRef = useRef<number | null>(null);
    const currentMsRef = useRef(0);
    const playingRef = useRef(false);

    const audioTracks = useMemo(
      () => edl.audio_tracks ?? [],
      [edl.audio_tracks],
    );

    const totalMs = useMemo(() => totalDurationFromSegments(edl), [edl]);

    const sortedSegments = useMemo(
      () => [...(edl.segments ?? [])].sort((a, b) => a.start_ms - b.start_ms),
      [edl.segments],
    );

    // Which segment is active right now?
    const activeSegment = useMemo(
      () =>
        sortedSegments.find(
          (s) =>
            s.layer === 0 && currentMs >= s.start_ms && currentMs < s.end_ms,
        ) ??
        sortedSegments.find(
          (s) =>
            s.layer === 0 && currentMs >= s.start_ms && currentMs <= s.end_ms,
        ) ??
        null,
      [sortedSegments, currentMs],
    );

    // ── RAF loop ────────────────────────────────────────────────────────────

    const tick = useCallback(() => {
      if (!playingRef.current) return;

      const now = performance.now();
      const wall = lastWallRef.current ?? now;
      const delta = now - wall;
      lastWallRef.current = now;

      const next = Math.min(currentMsRef.current + delta, totalMs);
      currentMsRef.current = next;
      setCurrentMs(next);
      onTimeUpdate?.(next);

      if (next >= totalMs) {
        playingRef.current = false;
        setPlaying(false);
        onPlayStateChange?.(false);
        lastWallRef.current = null;
        return;
      }

      rafRef.current = requestAnimationFrame(tick);
    }, [totalMs, onTimeUpdate, onPlayStateChange]);

    // ── Controls ────────────────────────────────────────────────────────────

    const play = useCallback(() => {
      if (playingRef.current) return;
      // If at end, restart
      if (currentMsRef.current >= totalMs) {
        currentMsRef.current = 0;
        setCurrentMs(0);
      }
      playingRef.current = true;
      lastWallRef.current = performance.now();
      setPlaying(true);
      onPlayStateChange?.(true);
      rafRef.current = requestAnimationFrame(tick);
    }, [totalMs, tick, onPlayStateChange]);

    const pause = useCallback(() => {
      if (!playingRef.current) return;
      playingRef.current = false;
      lastWallRef.current = null;
      if (rafRef.current !== null) {
        cancelAnimationFrame(rafRef.current);
        rafRef.current = null;
      }
      setPlaying(false);
      onPlayStateChange?.(false);
    }, [onPlayStateChange]);

    const seek = useCallback(
      (ms: number) => {
        const clamped = Math.max(0, Math.min(ms, totalMs));
        currentMsRef.current = clamped;
        setCurrentMs(clamped);
        onTimeUpdate?.(clamped);
        // reset wall clock so we don't jump after seeking while playing
        lastWallRef.current = performance.now();
      },
      [totalMs, onTimeUpdate],
    );

    // Expose handle
    useImperativeHandle(
      ref,
      () => ({
        play,
        pause,
        seek,
        get isPlaying() {
          return playingRef.current;
        },
        get currentMs() {
          return currentMsRef.current;
        },
      }),
      [play, pause, seek],
    );

    // Cleanup on unmount
    useEffect(() => {
      return () => {
        if (rafRef.current !== null) cancelAnimationFrame(rafRef.current);
      };
    }, []);

    // When EDL changes (user edits), pause and seek to 0
    useEffect(() => {
      pause();
      seek(0);
      // eslint-disable-next-line react-hooks/exhaustive-deps
    }, [edl.version]);

    // ── Render ──────────────────────────────────────────────────────────────

    // Preload adjacent segments (active + next) to minimise hitching
    const preloadSegments = useMemo(() => {
      if (!activeSegment) return sortedSegments.slice(0, 2);
      const idx = sortedSegments.indexOf(activeSegment);
      return sortedSegments.slice(Math.max(0, idx - 1), idx + 3);
    }, [activeSegment, sortedSegments]);

    return (
      <div
        className={className}
        style={{ position: "relative", background: "#000", overflow: "hidden" }}
      >
        {/* Audio tracks — rendered as hidden <audio> elements */}
        {audioTracks.map((track) => (
          <AudioTrackPlayer
            key={track.id}
            track={track}
            currentMs={currentMs}
            playing={playing}
            totalMs={totalMs}
            masterVolume={masterVolume}
          />
        ))}

        {preloadSegments.map((seg) => {
          const isVisible = seg.id === activeSegment?.id;
          const localMs = Math.max(0, currentMs - seg.start_ms);
          return (
            <SegmentView
              key={seg.id}
              segment={seg}
              localMs={localMs}
              playing={playing}
              visible={isVisible}
            />
          );
        })}

        {/* Empty state */}
        {sortedSegments.length === 0 && (
          <div className="absolute inset-0 flex items-center justify-center text-white/30 text-sm">
            No segments on timeline
          </div>
        )}

        {/* Text overlays + karaoke captions — mirrors the Remotion renderer */}
        <TextAndCaptionLayer
          edl={edl}
          currentMs={currentMs}
          onEdlChange={onEdlChange}
        />
      </div>
    );
  },
);

EdlPlayer.displayName = "EdlPlayer";

// ─── Text overlays + captions preview ────────────────────────────────────────
//
// Faithful (light) port of what Remotion renders on top of the video:
//  1. every active `text_overlay` at its own position/style, and
//  2. the karaoke caption track from `whisper_words` + `subtitle_config`.
// Both are draggable when `onEdlChange` is provided: overlays move freely
// (x/y %), captions snap to the renderer's five named vertical zones.

/** Track the rendered size of the preview so EDL px values scale correctly. */
const useFrameScale = (
  edl: EdlDto,
): { ref: React.RefObject<HTMLDivElement | null>; scale: number } => {
  const ref = useRef<HTMLDivElement>(null);
  const [scale, setScale] = useState(0.3);
  const frameWidth = edl.metadata?.width || 1080;

  useEffect(() => {
    const el = ref.current;
    if (!el) return;
    const update = () => setScale(el.clientWidth / frameWidth);
    update();
    const ro = new ResizeObserver(update);
    ro.observe(el);
    return () => ro.disconnect();
  }, [frameWidth]);

  return { ref, scale };
};

const TextAndCaptionLayer = ({
  edl,
  currentMs,
  onEdlChange,
}: {
  edl: EdlDto;
  currentMs: number;
  onEdlChange?: (next: EdlDto) => void;
}) => {
  const { ref, scale } = useFrameScale(edl);

  const activeOverlays = (edl.text_overlays ?? []).filter(
    (t) => currentMs >= t.start_ms && currentMs < t.end_ms,
  );

  return (
    <div
      ref={ref}
      className="absolute inset-0 overflow-hidden"
      style={{ zIndex: 10, pointerEvents: "none" }}
    >
      {activeOverlays.map((overlay, i) => (
        <OverlayView
          key={overlay.id ?? i}
          edl={edl}
          overlay={overlay}
          scale={scale}
          containerRef={ref}
          onEdlChange={onEdlChange}
        />
      ))}
      <CaptionView
        edl={edl}
        currentMs={currentMs}
        scale={scale}
        containerRef={ref}
        onEdlChange={onEdlChange}
      />
    </div>
  );
};

// ─── Positioned text overlay ──────────────────────────────────────────────────

const OverlayView = ({
  edl,
  overlay,
  scale,
  containerRef,
  onEdlChange,
}: {
  edl: EdlDto;
  overlay: EdlTextOverlay;
  scale: number;
  containerRef: React.RefObject<HTMLDivElement | null>;
  onEdlChange?: (next: EdlDto) => void;
}) => {
  const pos = overlay.position ?? {};
  const style = overlay.style ?? {};
  const x = pos.x ?? "center";
  const y = pos.y ?? "75%";

  const wrapperStyle: React.CSSProperties = {
    position: "absolute",
    top: y,
    maxWidth: pos.max_width ?? "85%",
    textAlign: (pos.text_align ?? "center") as React.CSSProperties["textAlign"],
    pointerEvents: onEdlChange ? "auto" : "none",
    cursor: onEdlChange ? "grab" : undefined,
  };
  if (x === "center") {
    wrapperStyle.left = "50%";
    wrapperStyle.transform = "translateX(-50%)";
  } else {
    wrapperStyle.left = x;
  }

  const textStyle: React.CSSProperties = {
    fontFamily: `${style.font_family ?? "Inter"}, sans-serif`,
    fontSize: (style.font_size ?? 60) * scale,
    fontWeight: (style.font_weight ??
      "bold") as React.CSSProperties["fontWeight"],
    color: style.color ?? "#FFFFFF",
    lineHeight: 1.3,
    wordWrap: "break-word",
    textShadow: "0 2px 8px rgba(0,0,0,0.7)",
  };
  if (style.stroke_color && style.stroke_width) {
    textStyle.WebkitTextStroke = `${style.stroke_width * scale}px ${style.stroke_color}`;
    textStyle.paintOrder = "stroke fill";
  }
  if (style.background_color) {
    textStyle.backgroundColor = style.background_color;
    textStyle.padding = (style.background_padding ?? 8) * scale;
    textStyle.borderRadius = 8 * scale;
  }

  const onPointerDown = (e: React.PointerEvent) => {
    if (!onEdlChange) return;
    e.preventDefault();
    e.stopPropagation();
    const container = containerRef.current;
    if (!container) return;
    const rect = container.getBoundingClientRect();

    const move = (ev: PointerEvent) => {
      const xPct = ((ev.clientX - rect.left) / rect.width) * 100;
      const yPct = ((ev.clientY - rect.top) / rect.height) * 100;
      const clamped = (v: number) => Math.max(0, Math.min(100, v)).toFixed(1);
      onEdlChange({
        ...edl,
        text_overlays: (edl.text_overlays ?? []).map((t) =>
          t === overlay || (t.id && t.id === overlay.id)
            ? {
                ...t,
                position: {
                  ...(t.position ?? {}),
                  x:
                    Math.abs(xPct - 50) < 4 ? "center" : `${clamped(xPct)}%`,
                  y: `${clamped(yPct)}%`,
                },
              }
            : t,
        ),
      });
    };
    const up = () => {
      window.removeEventListener("pointermove", move);
      window.removeEventListener("pointerup", up);
    };
    window.addEventListener("pointermove", move);
    window.addEventListener("pointerup", up);
  };

  return (
    <div
      style={wrapperStyle}
      onPointerDown={onPointerDown}
      onClick={(e) => e.stopPropagation()}
    >
      <span style={textStyle}>{overlay.text}</span>
    </div>
  );
};

// ─── Karaoke captions (whisper words) ─────────────────────────────────────────

const CaptionView = ({
  edl,
  currentMs,
  scale,
  containerRef,
  onEdlChange,
}: {
  edl: EdlDto;
  currentMs: number;
  scale: number;
  containerRef: React.RefObject<HTMLDivElement | null>;
  onEdlChange?: (next: EdlDto) => void;
}) => {
  const [dragZone, setDragZone] = useState<SubtitlePosition | null>(null);

  const groups = useMemo(
    () => groupWhisperWords(edl.whisper_words),
    [edl.whisper_words],
  );

  if (!subtitlesEnabled(edl)) return null;

  const config = edl.subtitle_config ?? {};
  const group = activeGroupAt(groups, currentMs);
  if (!group) return null;

  const position = dragZone ?? toSubtitlePosition(config.position);
  const highlightMode = config.highlight_mode ?? "word";
  const highlight = highlightColorFor(config, group.index);

  // Adaptive font size — same thresholds as the Remotion SubtitleTrack
  const totalChars = group.words.reduce((sum, w) => sum + w.word.length, 0);
  const base = config.font_size ?? 42;
  const sizeMult =
    totalChars <= 10 ? 1.4 : totalChars <= 18 ? 1.2 : totalChars <= 30 ? 1 : 0.85;
  // Same px the renderer would use at 1080w, scaled down to the preview width
  const fontSize = base * sizeMult * scale;

  const zoneStyle: React.CSSProperties = {
    position: "absolute",
    left: 0,
    right: 0,
    display: "flex",
    justifyContent: "center",
    textAlign: "center",
    padding: "0 4%",
    flexWrap: "wrap",
    pointerEvents: onEdlChange ? "auto" : "none",
    cursor: onEdlChange ? "grab" : undefined,
  };
  switch (position) {
    case "top":
      zoneStyle.top = "5%";
      break;
    case "top_third":
      zoneStyle.top = "20%";
      break;
    case "center":
      zoneStyle.top = "50%";
      zoneStyle.transform = "translateY(-50%)";
      break;
    case "bottom":
      zoneStyle.bottom = "5%";
      break;
    default:
      zoneStyle.bottom = "20%";
  }

  const onPointerDown = (e: React.PointerEvent) => {
    if (!onEdlChange) return;
    e.preventDefault();
    e.stopPropagation();
    const container = containerRef.current;
    if (!container) return;
    const rect = container.getBoundingClientRect();

    let lastZone: SubtitlePosition | null = null;
    const zoneFromY = (clientY: number): SubtitlePosition => {
      const frac = Math.max(0, Math.min(1, (clientY - rect.top) / rect.height));
      let best: SubtitlePosition = "bottom_third";
      let bestDist = Infinity;
      for (const [zone, anchor] of Object.entries(SUBTITLE_ZONE_Y)) {
        const dist = Math.abs(frac - anchor);
        if (dist < bestDist) {
          bestDist = dist;
          best = zone as SubtitlePosition;
        }
      }
      return best;
    };

    const move = (ev: PointerEvent) => {
      lastZone = zoneFromY(ev.clientY);
      setDragZone(lastZone);
    };
    const up = () => {
      window.removeEventListener("pointermove", move);
      window.removeEventListener("pointerup", up);
      setDragZone(null);
      if (lastZone) onEdlChange(withSubtitleConfig(edl, { position: lastZone }));
    };
    window.addEventListener("pointermove", move);
    window.addEventListener("pointerup", up);
  };

  return (
    <div
      style={zoneStyle}
      onPointerDown={onPointerDown}
      onClick={(e) => e.stopPropagation()}
      title="Drag to reposition captions"
    >
      <span
        style={{
          fontSize,
          fontFamily: `${config.font_family ?? "Inter"}, sans-serif`,
          fontWeight: 800,
          WebkitTextStroke: `${(config.stroke_width ?? 3) * scale}px ${config.stroke_color ?? "#000000"}`,
          paintOrder: "stroke fill",
          lineHeight: 1.4,
          textShadow: "0 2px 10px rgba(0, 0, 0, 0.8)",
        }}
      >
        {group.words.map((w, i) => {
          if (highlightMode === "sentence") {
            return (
              <span
                key={i}
                style={{
                  color: highlight,
                  display: "inline-block",
                  marginRight: "0.3em",
                }}
              >
                {w.word}
              </span>
            );
          }
          const isActive = currentMs >= w.start_ms - 30 && currentMs <= w.end_ms;
          const isPast = currentMs > w.end_ms;
          const color = isActive
            ? highlight
            : isPast
              ? "#FFFFFF"
              : "rgba(255,255,255,0.35)";
          return (
            <span
              key={i}
              style={{
                color,
                opacity: !isActive && !isPast ? 0.5 : 1,
                transform: isActive ? "scale(1.15)" : "scale(1)",
                display: "inline-block",
                marginRight: "0.3em",
                textShadow: isActive
                  ? `0 0 14px ${highlight}80, 0 2px 10px rgba(0,0,0,0.8)`
                  : undefined,
              }}
            >
              {w.word}
            </span>
          );
        })}
      </span>
    </div>
  );
};
