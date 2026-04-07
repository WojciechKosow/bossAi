import { parseEdlToTimeline, msToFrames } from "../src/utils/edl-parser";
import type { Edl } from "../src/types/edl";

describe("msToFrames", () => {
  it("converts milliseconds to frames at 30fps", () => {
    expect(msToFrames(1000, 30)).toBe(30);
    expect(msToFrames(500, 30)).toBe(15);
    expect(msToFrames(3000, 30)).toBe(90);
  });

  it("converts milliseconds to frames at 60fps", () => {
    expect(msToFrames(1000, 60)).toBe(60);
  });

  it("handles zero", () => {
    expect(msToFrames(0, 30)).toBe(0);
  });
});

describe("parseEdlToTimeline", () => {
  const baseEdl: Edl = {
    version: "1.0",
    metadata: {
      title: "Test Video",
      total_duration_ms: 15000,
      width: 1080,
      height: 1920,
      fps: 30,
      bpm: 128,
    },
    segments: [
      {
        id: "seg-1",
        asset_url: "storage/projects/abc/scene_0.mp4",
        asset_type: "VIDEO",
        start_ms: 0,
        end_ms: 5000,
        layer: 0,
      },
      {
        id: "seg-2",
        asset_url: "storage/projects/abc/scene_1.jpg",
        asset_type: "IMAGE",
        start_ms: 5000,
        end_ms: 10000,
        layer: 0,
      },
    ],
    audio_tracks: [
      {
        id: "voice-1",
        asset_url: "storage/projects/abc/voice.mp3",
        type: "voiceover",
        start_ms: 0,
        volume: 1.0,
        fade_in_ms: 0,
        fade_out_ms: 0,
      },
      {
        id: "music-1",
        asset_url: "storage/projects/abc/music.mp3",
        type: "music",
        start_ms: 0,
        volume: 0.3,
        fade_in_ms: 500,
        fade_out_ms: 1000,
      },
    ],
    text_overlays: [
      {
        id: "text-1",
        text: "Nike Air Max",
        start_ms: 1000,
        end_ms: 3000,
        animation: "karaoke",
      },
    ],
  };

  it("calculates total duration in frames", () => {
    const result = parseEdlToTimeline(baseEdl);
    expect(result.durationInFrames).toBe(450); // 15s * 30fps
    expect(result.fps).toBe(30);
  });

  it("parses resolution from metadata", () => {
    const result = parseEdlToTimeline(baseEdl);
    expect(result.width).toBe(1080);
    expect(result.height).toBe(1920);
  });

  it("passes through bpm", () => {
    const result = parseEdlToTimeline(baseEdl);
    expect(result.bpm).toBe(128);
  });

  it("converts segments to frame-based", () => {
    const result = parseEdlToTimeline(baseEdl);
    expect(result.segments).toHaveLength(2);

    expect(result.segments[0].id).toBe("seg-1");
    expect(result.segments[0].from).toBe(0);
    expect(result.segments[0].durationInFrames).toBe(150); // 5s * 30fps

    expect(result.segments[1].id).toBe("seg-2");
    expect(result.segments[1].from).toBe(150);
    expect(result.segments[1].durationInFrames).toBe(150);
  });

  it("converts audio tracks to frame-based", () => {
    const result = parseEdlToTimeline(baseEdl);
    expect(result.audioTracks).toHaveLength(2);

    expect(result.audioTracks[0].id).toBe("voice-1");
    expect(result.audioTracks[0].from).toBe(0);
    // end_ms is null, so duration extends to total
    expect(result.audioTracks[0].durationInFrames).toBe(450);

    expect(result.audioTracks[1].track.volume).toBe(0.3);
  });

  it("converts text overlays to frame-based", () => {
    const result = parseEdlToTimeline(baseEdl);
    expect(result.textOverlays).toHaveLength(1);
    expect(result.textOverlays[0].from).toBe(30); // 1000ms at 30fps
    expect(result.textOverlays[0].durationInFrames).toBe(60); // 2000ms at 30fps
  });

  it("handles empty optional arrays", () => {
    const edl: Edl = {
      metadata: { total_duration_ms: 5000, width: 1080, height: 1920, fps: 30 },
      segments: [],
    };
    const result = parseEdlToTimeline(edl);
    expect(result.audioTracks).toHaveLength(0);
    expect(result.textOverlays).toHaveLength(0);
  });

  it("preserves original segment data", () => {
    const result = parseEdlToTimeline(baseEdl);
    expect(result.segments[0].segment.asset_url).toBe(
      "storage/projects/abc/scene_0.mp4"
    );
    expect(result.segments[0].segment.asset_type).toBe("VIDEO");
  });
});
