import type { Edl, Segment, AudioTrack, TextOverlay } from "../types/edl";

export interface RemotionTimeline {
  durationInFrames: number;
  fps: number;
  width: number;
  height: number;
  bpm?: number;
  segments: RemotionSegment[];
  audioTracks: RemotionAudioTrack[];
  textOverlays: RemotionTextOverlay[];
}

export interface RemotionSegment {
  id: string;
  from: number;
  durationInFrames: number;
  segment: Segment;
}

export interface RemotionAudioTrack {
  id: string;
  from: number;
  durationInFrames: number;
  track: AudioTrack;
}

export interface RemotionTextOverlay {
  id: string;
  from: number;
  durationInFrames: number;
  overlay: TextOverlay;
}

export function msToFrames(ms: number, fps: number): number {
  return Math.round((ms / 1000) * fps);
}

export function parseEdlToTimeline(edl: Edl): RemotionTimeline {
  const { metadata } = edl;
  const fps = metadata.fps;
  const durationInFrames = msToFrames(metadata.total_duration_ms, fps);

  const segments: RemotionSegment[] = edl.segments.map((seg) => ({
    id: seg.id,
    from: msToFrames(seg.start_ms, fps),
    durationInFrames: msToFrames(seg.end_ms - seg.start_ms, fps),
    segment: seg,
  }));

  const audioTracks: RemotionAudioTrack[] = (edl.audio_tracks ?? []).map(
    (track) => {
      const startFrames = msToFrames(track.start_ms, fps);
      const endFrames = track.end_ms
        ? msToFrames(track.end_ms, fps)
        : durationInFrames;
      return {
        id: track.id,
        from: startFrames,
        durationInFrames: endFrames - startFrames,
        track,
      };
    }
  );

  const textOverlays: RemotionTextOverlay[] = (edl.text_overlays ?? []).map(
    (overlay) => ({
      id: overlay.id,
      from: msToFrames(overlay.start_ms, fps),
      durationInFrames: msToFrames(overlay.end_ms - overlay.start_ms, fps),
      overlay,
    })
  );

  return {
    durationInFrames,
    fps,
    width: metadata.width,
    height: metadata.height,
    bpm: metadata.bpm,
    segments,
    audioTracks,
    textOverlays,
  };
}
