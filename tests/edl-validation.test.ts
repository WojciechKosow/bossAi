import {
  RenderRequestSchema,
  EdlSchema,
  SegmentSchema,
  TextOverlaySchema,
  AudioTrackSchema,
  SubtitleConfigSchema,
  WhisperWordSchema,
} from "../src/types/edl";

describe("EDL Schema validation", () => {
  it("validates a minimal EDL", () => {
    const result = EdlSchema.safeParse({
      metadata: { total_duration_ms: 10000 },
      segments: [],
    });
    expect(result.success).toBe(true);
    if (result.success) {
      expect(result.data.metadata.width).toBe(1080);
      expect(result.data.metadata.height).toBe(1920);
      expect(result.data.metadata.fps).toBe(30);
    }
  });

  it("rejects EDL without metadata", () => {
    const result = EdlSchema.safeParse({ segments: [] });
    expect(result.success).toBe(false);
  });

  it("rejects EDL without total_duration_ms", () => {
    const result = EdlSchema.safeParse({
      metadata: {},
      segments: [],
    });
    expect(result.success).toBe(false);
  });

  it("validates a segment with effects and transition", () => {
    const result = SegmentSchema.safeParse({
      id: "seg-1",
      asset_url: "storage/scene.mp4",
      asset_type: "VIDEO",
      start_ms: 0,
      end_ms: 5000,
      effects: [
        {
          type: "bounce",
          intensity: 1.0,
          params: { scale_peak: 1.12, easing: "spring" },
        },
      ],
      transition: {
        type: "wipe_left",
        duration_ms: 400,
      },
    });
    expect(result.success).toBe(true);
    if (result.success) {
      expect(result.data.layer).toBe(0);
      expect(result.data.effects).toHaveLength(1);
    }
  });

  it("validates all 17 effect types", () => {
    const effectTypes = [
      "zoom_in", "zoom_out", "fast_zoom",
      "pan_left", "pan_right", "pan_up", "pan_down",
      "shake", "slow_motion", "speed_ramp",
      "zoom_pulse", "ken_burns", "glitch", "flash",
      "bounce", "drift", "zoom_in_offset",
    ];
    for (const type of effectTypes) {
      const result = SegmentSchema.safeParse({
        id: "s1",
        asset_url: "storage/v.mp4",
        asset_type: "VIDEO",
        start_ms: 0,
        end_ms: 1000,
        effects: [{ type }],
      });
      expect(result.success).toBe(true);
    }
  });

  it("rejects invalid effect type", () => {
    const result = SegmentSchema.safeParse({
      id: "s1",
      asset_url: "storage/v.mp4",
      asset_type: "VIDEO",
      start_ms: 0,
      end_ms: 1000,
      effects: [{ type: "nonexistent" }],
    });
    expect(result.success).toBe(false);
  });

  it("validates all 9 transition types", () => {
    const transitionTypes = [
      "cut", "fade", "fade_white", "fade_black",
      "dissolve", "wipe_left", "wipe_right",
      "slide_left", "slide_right",
    ];
    for (const type of transitionTypes) {
      const result = SegmentSchema.safeParse({
        id: "s1",
        asset_url: "storage/v.mp4",
        asset_type: "VIDEO",
        start_ms: 0,
        end_ms: 1000,
        transition: { type, duration_ms: 300 },
      });
      expect(result.success).toBe(true);
    }
  });

  it("rejects invalid transition type", () => {
    const result = SegmentSchema.safeParse({
      id: "s1",
      asset_url: "storage/v.mp4",
      asset_type: "VIDEO",
      start_ms: 0,
      end_ms: 1000,
      transition: { type: "teleport" },
    });
    expect(result.success).toBe(false);
  });

  it("validates audio track with defaults", () => {
    const result = AudioTrackSchema.safeParse({
      id: "voice-1",
      asset_url: "storage/voice.mp3",
      type: "voiceover",
    });
    expect(result.success).toBe(true);
    if (result.success) {
      expect(result.data.volume).toBe(1.0);
      expect(result.data.start_ms).toBe(0);
      expect(result.data.fade_in_ms).toBe(0);
      expect(result.data.fade_out_ms).toBe(0);
    }
  });

  it("validates text overlay with all animation types", () => {
    const animations = [
      "fade_in", "slide_up", "typewriter",
      "bounce", "word_by_word", "karaoke",
    ];
    for (const animation of animations) {
      const result = TextOverlaySchema.safeParse({
        id: "t1",
        text: "Test",
        start_ms: 0,
        end_ms: 1000,
        animation,
      });
      expect(result.success).toBe(true);
    }
  });

  it("validates subtitle_config with defaults", () => {
    const result = SubtitleConfigSchema.safeParse({ enabled: true });
    expect(result.success).toBe(true);
    if (result.success) {
      expect(result.data.position).toBe("bottom_third");
      expect(result.data.highlight_color).toBe("#FFD700");
      expect(result.data.font_size).toBe(48);
      expect(result.data.font_family).toBe("Inter");
      expect(result.data.stroke_color).toBe("#000000");
      expect(result.data.stroke_width).toBe(3);
    }
  });

  it("validates whisper_words", () => {
    const result = WhisperWordSchema.safeParse({
      word: "Hello",
      start_ms: 100,
      end_ms: 400,
    });
    expect(result.success).toBe(true);
  });

  it("validates EDL with subtitle_config and whisper_words", () => {
    const result = EdlSchema.safeParse({
      metadata: { total_duration_ms: 10000 },
      segments: [],
      subtitle_config: {
        enabled: true,
        position: "bottom_third",
        highlight_color: "#FFD700",
      },
      whisper_words: [
        { word: "This", start_ms: 0, end_ms: 300 },
        { word: "is", start_ms: 300, end_ms: 500 },
        { word: "amazing", start_ms: 500, end_ms: 1100 },
      ],
    });
    expect(result.success).toBe(true);
    if (result.success) {
      expect(result.data.subtitle_config?.enabled).toBe(true);
      expect(result.data.whisper_words).toHaveLength(3);
    }
  });

  it("validates text overlay with style and position", () => {
    const result = TextOverlaySchema.safeParse({
      id: "t1",
      text: "Nike Air Max",
      type: "subtitle",
      start_ms: 1000,
      end_ms: 2500,
      animation: "karaoke",
      style: {
        font_family: "Inter",
        font_size: 60,
        font_weight: "bold",
        color: "#FFFFFF",
        stroke_color: "#000000",
        stroke_width: 3,
        background_color: "rgba(0,0,0,0.3)",
        background_padding: 12,
      },
      position: {
        x: "center",
        y: "75%",
        max_width: "85%",
        text_align: "center",
      },
    });
    expect(result.success).toBe(true);
  });
});

describe("RenderRequest validation", () => {
  it("validates a full render request matching Spring Boot format", () => {
    const result = RenderRequestSchema.safeParse({
      render_id: "uuid-render-1",
      edl: {
        version: "1.0",
        metadata: {
          title: "TikTok about Nike sneakers",
          style: "VIRAL_EDIT",
          total_duration_ms: 15000,
          width: 1080,
          height: 1920,
          fps: 30,
          bpm: 128,
          pacing: "fast",
        },
        segments: [
          {
            id: "uuid-seg-1",
            asset_id: "uuid-asset-1",
            asset_url: "storage/projects/abc/scene_0.mp4",
            asset_type: "VIDEO",
            start_ms: 0,
            end_ms: 3000,
            trim_in_ms: 0,
            trim_out_ms: null,
            layer: 0,
            effects: [
              {
                type: "bounce",
                intensity: 1.0,
                params: { scale_peak: 1.12, easing: "spring" },
              },
            ],
            transition: {
              type: "wipe_left",
              duration_ms: 400,
              params: null,
            },
          },
        ],
        audio_tracks: [
          {
            id: "uuid-voice",
            asset_id: "uuid-voice-asset",
            asset_url: "storage/projects/abc/voice.mp3",
            type: "voiceover",
            start_ms: 0,
            end_ms: null,
            volume: 1.0,
            fade_in_ms: 0,
            fade_out_ms: 0,
          },
          {
            id: "uuid-music",
            asset_id: "uuid-music-asset",
            asset_url: "storage/projects/abc/music.mp3",
            type: "music",
            start_ms: 0,
            end_ms: null,
            volume: 0.3,
            fade_in_ms: 500,
            fade_out_ms: 1000,
            trim_in_ms: 5000,
          },
        ],
        text_overlays: [
          {
            id: "uuid-text-1",
            text: "Nike Air Max",
            type: "subtitle",
            start_ms: 1000,
            end_ms: 2500,
            animation: "karaoke",
            style: {
              font_family: "Inter",
              font_size: 60,
              font_weight: "bold",
              color: "#FFFFFF",
              stroke_color: "#000000",
              stroke_width: 3,
              background_color: "rgba(0,0,0,0.3)",
              background_padding: 12,
            },
            position: {
              x: "center",
              y: "75%",
              max_width: "85%",
              text_align: "center",
            },
          },
        ],
        subtitle_config: {
          enabled: true,
          position: "bottom_third",
          highlight_color: "#FFD700",
          font_size: 48,
          font_family: "Inter",
          stroke_color: "#000000",
          stroke_width: 3,
        },
        whisper_words: [
          { word: "This", start_ms: 0, end_ms: 300 },
          { word: "is", start_ms: 300, end_ms: 500 },
          { word: "amazing", start_ms: 500, end_ms: 1100 },
        ],
      },
      output_config: {
        width: 1080,
        height: 1920,
        fps: 30,
        codec: "h264",
        quality: "high",
      },
    });

    expect(result.success).toBe(true);
  });

  it("accepts request with only render_id and edl (no output_config)", () => {
    const result = RenderRequestSchema.safeParse({
      edl: {
        metadata: { total_duration_ms: 5000 },
        segments: [],
      },
    });
    expect(result.success).toBe(true);
  });

  it("uses render_id from request if provided", () => {
    const result = RenderRequestSchema.safeParse({
      render_id: "my-custom-id",
      edl: {
        metadata: { total_duration_ms: 5000 },
        segments: [],
      },
    });
    expect(result.success).toBe(true);
    if (result.success) {
      expect(result.data.render_id).toBe("my-custom-id");
    }
  });
});
