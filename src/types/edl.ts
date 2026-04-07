import { z } from "zod";

// --- Effect params ---

export const EffectParamsSchema = z.record(z.unknown()).optional();

export const EffectSchema = z.object({
  type: z.enum([
    "zoom_in",
    "zoom_out",
    "fast_zoom",
    "pan_left",
    "pan_right",
    "pan_up",
    "pan_down",
    "shake",
    "slow_motion",
    "speed_ramp",
    "zoom_pulse",
    "ken_burns",
    "glitch",
    "flash",
    "bounce",
    "drift",
    "zoom_in_offset",
  ]),
  intensity: z.number().optional(),
  start_ms: z.number().nullable().optional(),
  end_ms: z.number().nullable().optional(),
  params: EffectParamsSchema,
});

// --- Transition ---

export const TransitionSchema = z.object({
  type: z.enum([
    "cut",
    "fade",
    "fade_white",
    "fade_black",
    "dissolve",
    "wipe_left",
    "wipe_right",
    "slide_left",
    "slide_right",
  ]),
  duration_ms: z.number().optional(),
  params: z.record(z.unknown()).nullable().optional(),
});

// --- Segment ---

export const SegmentSchema = z.object({
  id: z.string(),
  asset_id: z.string().optional(),
  asset_url: z.string(),
  asset_type: z.enum(["VIDEO", "IMAGE"]),
  start_ms: z.number(),
  end_ms: z.number(),
  trim_in_ms: z.number().nullable().optional(),
  trim_out_ms: z.number().nullable().optional(),
  layer: z.number().default(0),
  effects: z.array(EffectSchema).optional(),
  transition: TransitionSchema.nullable().optional(),
});

// --- Audio track ---

export const AudioTrackSchema = z.object({
  id: z.string(),
  asset_id: z.string().optional(),
  asset_url: z.string(),
  type: z.enum(["voiceover", "music"]),
  start_ms: z.number().default(0),
  end_ms: z.number().nullable().optional(),
  volume: z.number().default(1.0),
  fade_in_ms: z.number().default(0),
  fade_out_ms: z.number().default(0),
  trim_in_ms: z.number().nullable().optional(),
  trim_out_ms: z.number().nullable().optional(),
});

// --- Text overlay style ---

export const TextStyleSchema = z.object({
  font_family: z.string().default("Inter"),
  font_size: z.number().default(60),
  font_weight: z.string().default("bold"),
  color: z.string().default("#FFFFFF"),
  stroke_color: z.string().optional(),
  stroke_width: z.number().optional(),
  background_color: z.string().optional(),
  background_padding: z.number().optional(),
});

export const TextPositionSchema = z.object({
  x: z.string().default("center"),
  y: z.string().default("75%"),
  max_width: z.string().default("85%"),
  text_align: z.string().default("center"),
});

export const TextOverlaySchema = z.object({
  id: z.string(),
  text: z.string(),
  type: z.string().optional(),
  start_ms: z.number(),
  end_ms: z.number(),
  animation: z.enum([
    "fade_in",
    "slide_up",
    "typewriter",
    "bounce",
    "word_by_word",
    "karaoke",
  ]).optional(),
  style: TextStyleSchema.optional(),
  position: TextPositionSchema.optional(),
});

// --- EDL Metadata ---

export const MetadataSchema = z.object({
  title: z.string().optional(),
  style: z.string().optional(),
  total_duration_ms: z.number(),
  width: z.number().default(1080),
  height: z.number().default(1920),
  fps: z.number().default(30),
  bpm: z.number().optional(),
  pacing: z.string().optional(),
});

// --- Subtitle config ---

export const SubtitleConfigSchema = z.object({
  enabled: z.boolean().default(false),
  position: z.string().default("bottom_third"),
  highlight_color: z.string().default("#FFD700"),
  font_size: z.number().default(48),
  font_family: z.string().default("Inter"),
  stroke_color: z.string().default("#000000"),
  stroke_width: z.number().default(3),
});

// --- Whisper word ---

export const WhisperWordSchema = z.object({
  word: z.string(),
  start_ms: z.number(),
  end_ms: z.number(),
});

// --- EDL root ---

export const EdlSchema = z.object({
  version: z.string().optional(),
  metadata: MetadataSchema,
  segments: z.array(SegmentSchema),
  audio_tracks: z.array(AudioTrackSchema).optional(),
  text_overlays: z.array(TextOverlaySchema).optional(),
  subtitle_config: SubtitleConfigSchema.optional(),
  whisper_words: z.array(WhisperWordSchema).optional(),
});

// --- Output config ---

export const OutputConfigSchema = z.object({
  width: z.number().default(1080),
  height: z.number().default(1920),
  fps: z.number().default(30),
  codec: z.string().default("h264"),
  quality: z.enum(["low", "medium", "high"]).default("high"),
});

// --- Render request ---

export const RenderRequestSchema = z.object({
  render_id: z.string().optional(),
  edl: EdlSchema,
  output_config: OutputConfigSchema.optional(),
});

// --- Inferred types ---

export type Effect = z.infer<typeof EffectSchema>;
export type Transition = z.infer<typeof TransitionSchema>;
export type Segment = z.infer<typeof SegmentSchema>;
export type AudioTrack = z.infer<typeof AudioTrackSchema>;
export type TextStyle = z.infer<typeof TextStyleSchema>;
export type TextPosition = z.infer<typeof TextPositionSchema>;
export type TextOverlay = z.infer<typeof TextOverlaySchema>;
export type Metadata = z.infer<typeof MetadataSchema>;
export type SubtitleConfig = z.infer<typeof SubtitleConfigSchema>;
export type WhisperWord = z.infer<typeof WhisperWordSchema>;
export type Edl = z.infer<typeof EdlSchema>;
export type OutputConfig = z.infer<typeof OutputConfigSchema>;
export type RenderRequest = z.infer<typeof RenderRequestSchema>;

// --- Render job ---

export interface RenderJob {
  render_id: string;
  status: "queued" | "rendering" | "completed" | "failed";
  progress: number;
  output_url: string | null;
  error?: string;
  created_at: Date;
}
