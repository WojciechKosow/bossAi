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
import type { EdlAudioTrack, EdlDto, EdlSegment } from "../../types";
import { absoluteUrl } from "../../api";
import { totalDurationFromSegments } from "./timelineUtils";

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
    { edl, onTimeUpdate, onPlayStateChange, className, masterVolume = 1 },
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

    // console.log(edl);
    // console.log(edl.whisper_words[0], edl.whisper_words[117]);
    console.log(JSON.stringify(edl.audio_tracks[0], null, 2));

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

        {/* Subtitle overlay */}
        <SubtitleOverlay edl={edl} currentMs={currentMs} />
      </div>
    );
  },
);

EdlPlayer.displayName = "EdlPlayer";

// ─── Subtitle overlay ─────────────────────────────────────────────────────────

const SubtitleOverlay = ({
  edl,
  currentMs,
}: {
  edl: EdlDto;
  currentMs: number;
}) => {
  const active = (edl.text_overlays ?? []).find(
    (t) => currentMs >= t.start_ms && currentMs < t.end_ms,
  );

  if (!active) return null;

  return (
    <div
      className="absolute bottom-6 left-0 right-0 flex items-center justify-center pointer-events-none px-4"
      style={{ zIndex: 10 }}
    >
      <span
        className="text-white text-sm font-semibold text-center leading-snug px-3 py-1.5 rounded-lg"
        style={{
          background: "rgba(0,0,0,0.65)",
          backdropFilter: "blur(4px)",
          textShadow: "0 1px 3px rgba(0,0,0,0.8)",
          maxWidth: "90%",
        }}
      >
        {active.text}
      </span>
    </div>
  );
};
