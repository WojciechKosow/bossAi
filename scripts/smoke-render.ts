/**
 * Phase A smoke render: exercises blur_fill auto-framing (16:9 source),
 * auto Ken Burns on a bare image, speed_ramp, trim_out, music auto-ducking
 * (whisper-word driven, no voiceover so output loudness is measurable),
 * subtitles and a text overlay at the frame edge (safe-area clamp).
 */
import path from "path";
import { bundle } from "@remotion/bundler";
import { renderMedia, selectComposition } from "@remotion/renderer";
import { EdlSchema } from "../src/types/edl";

const ASSETS = "http://127.0.0.1:8766";

const words = [
  "to", "jest", "test", "dukowania", "muzyki", "pod", "lektora", "ok",
].map((w, i) => ({
  word: w,
  start_ms: 500 + i * 500,
  end_ms: 900 + i * 500, // 100ms gaps — merged by duck_gap_hold_ms
  sentence_index: i < 4 ? 0 : 1,
}));

const edl = EdlSchema.parse({
  metadata: {
    title: "Phase A smoke",
    total_duration_ms: 9000,
    width: 1080,
    height: 1920,
    fps: 30,
    bpm: 120,
  },
  segments: [
    {
      id: "seg-landscape",
      asset_url: `${ASSETS}/landscape.mp4`,
      asset_type: "VIDEO",
      start_ms: 0,
      end_ms: 3000,
      transition: { type: "fade", duration_ms: 300 },
    },
    {
      id: "seg-still",
      asset_url: `${ASSETS}/square.png`,
      asset_type: "IMAGE",
      start_ms: 3000,
      end_ms: 6000,
      transition: { type: "fade", duration_ms: 300 },
    },
    {
      id: "seg-ramp",
      asset_url: `${ASSETS}/landscape.mp4`,
      asset_type: "VIDEO",
      start_ms: 6000,
      end_ms: 9000,
      trim_out_ms: 5800,
      effects: [
        { type: "speed_ramp", params: { speed_from: 0.5, speed_to: 2.0 } },
      ],
    },
  ],
  audio_tracks: [
    {
      id: "music-1",
      asset_url: `${ASSETS}/music.wav`,
      type: "music",
      start_ms: 0,
      volume: 0.9,
    },
  ],
  text_overlays: [
    {
      id: "cta-1",
      text: "SHOP NOW",
      start_ms: 6000,
      end_ms: 9000,
      animation: "fade_in",
      position: { x: "center", y: "95%", max_width: "85%", text_align: "center" },
    },
  ],
  subtitle_config: { enabled: true, position: "bottom_third" },
  whisper_words: words,
});

(async () => {
  const entryPoint = path.resolve(__dirname, "../src/index.tsx");
  const serveUrl = await bundle({ entryPoint });
  const composition = await selectComposition({
    serveUrl,
    id: "TikTokVideo",
    inputProps: { edl },
  });
  const fps = edl.metadata.fps;
  await renderMedia({
    composition: {
      ...composition,
      width: edl.metadata.width,
      height: edl.metadata.height,
      fps,
      durationInFrames: Math.round((edl.metadata.total_duration_ms / 1000) * fps),
      props: { edl },
    },
    serveUrl,
    codec: "h264",
    outputLocation: "/tmp/smoke-out.mp4",
  });
  console.log("SMOKE_RENDER_OK /tmp/smoke-out.mp4");
})().catch((err) => {
  console.error("SMOKE_RENDER_FAILED", err);
  process.exit(1);
});
