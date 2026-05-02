export type UUID = string;

export type AssetType = "IMAGE" | "VIDEO" | "MUSIC" | "VOICE";

export type VideoStyle =
  | "STORY_MODE"
  | "HIGH_CONVERTING_AD"
  | "EDUCATIONAL"
  | "VIRAL_EDIT"
  | "UGC_STYLE"
  | "LUXURY_AD"
  | "CINEMATIC"
  | "PRODUCT_SHOWCASE"
  | "CUSTOM";

export type GenerationStatus = "PENDING" | "PROCESSING" | "DONE" | "FAILED";

export type ProjectStatus =
  | "DRAFT"
  | "GENERATING"
  | "READY"
  | "RENDERING"
  | "COMPLETE"
  | "FAILED";

export type RenderStatus = "QUEUED" | "RENDERING" | "COMPLETE" | "FAILED";

export type EdlSource = "AI_GENERATED" | "USER_MODIFIED";

export type EffectType =
  | "zoom_in"
  | "zoom_out"
  | "fast_zoom"
  | "pan_left"
  | "pan_right"
  | "pan_up"
  | "pan_down"
  | "shake"
  | "slow_motion"
  | "speed_ramp"
  | "zoom_pulse"
  | "ken_burns"
  | "glitch"
  | "flash"
  | "bounce"
  | "drift"
  | "zoom_in_offset";

export type TransitionType =
  | "cut"
  | "fade"
  | "fade_white"
  | "fade_black"
  | "dissolve"
  | "wipe_left"
  | "wipe_right"
  | "slide_left"
  | "slide_right";

export interface AssetDTO {
  id: UUID;
  type: AssetType;
  source?: AssetSource;
  generationId?: UUID;
  originalFilename?: string;
  orderIndex?: number;
  description?: string;
  url?: string;
  durationSeconds?: number;
  width?: number;
  height?: number;
  createdAt?: string;
  expiresAt?: string;
}

export type AssetSource = "AI_GENERATED" | "USER_UPLOAD" | "SYSTEM_GENERATED";

export interface SceneLayer {
  layerIndex: number;
  role: string;
  source: "generate" | "provided";
  generationPrompt?: string;
  assetId?: UUID;
}

export interface SceneDTO {
  index: number;
  imagePrompt: string;
  motionPrompt?: string;
  subtitleText?: string;
  durationMs: number;
  suggestedRole?: string;
  suggestedMood?: string;
  sceneDirection?: string;
  suggestedAssetId?: UUID;
  layers?: SceneLayer[];
}

export interface UserIntent {
  overallGoal?: string;
  pacingPreference?: "auto" | "fast" | "moderate" | "slow";
  editingStyle?: string | null;
  structureHints?: string[];
  userControlsOrder?: boolean;
  hasExplicitInstructions?: boolean;
}

export interface PromptAnalysisResponse {
  contentType?: string;
  hook?: string;
  callToAction?: string;
  totalDurationMs: number;
  scenes: SceneDTO[];
  userIntent?: UserIntent;
  availableAssets: AssetDTO[];
}

export interface AnalyzePromptRequest {
  prompt: string;
  style?: VideoStyle | null;
  customMediaAssetIds?: UUID[];
  analyzeAssets?: boolean;
}

export interface SceneAssignment {
  sceneIndex: number;
  assetId: UUID;
}

export interface TikTokAdRequest {
  prompt: string;
  style?: VideoStyle | null;
  assetIds?: UUID[];
  musicAssetId?: UUID | null;
  customMediaAssetIds?: UUID[];
  customTtsAssetIds?: UUID[];
  useGptOrdering?: boolean;
  reuseAssets?: boolean;
  forceReuseForTesting?: boolean;
  sceneAssignments?: SceneAssignment[];
}

export interface GenerationStartResponse {
  generationId: UUID;
  status: GenerationStatus;
}

export interface GenerationDTO {
  id: UUID;
  status: GenerationStatus;
  type?: string;
  videoUrl?: string;
  imageUrl?: string;
  createdAt: string;
  finishedAt?: string;
}

export interface ProgressEvent {
  step: string;
  percent: number;
  message: string;
  generationId?: UUID;
}

export interface VideoProjectDTO {
  id: UUID;
  title?: string;
  originalPrompt?: string;
  status: ProjectStatus;
  style?: VideoStyle;
  currentEdlId?: UUID;
  currentEdlVersion?: number;
  generationId?: UUID;
  createdAt: string;
  updatedAt?: string;
}

export interface ProjectAssetDTO {
  id: UUID;
  assetId: UUID;
  type: AssetType;
  originalFilename?: string;
  url?: string;
  orderIndex?: number;
  description?: string;
}

export interface RenderJobDTO {
  id: UUID;
  projectId: UUID;
  edlVersionId?: UUID;
  status: RenderStatus;
  progress?: number;
  outputUrl?: string;
  quality?: string;
  startedAt?: string;
  completedAt?: string;
}

export interface EdlEffect {
  type: EffectType;
  start_ms?: number;
  end_ms?: number;
  intensity?: number;
  params?: Record<string, unknown>;
}

export interface EdlTransition {
  type: TransitionType;
  duration_ms?: number;
  params?: Record<string, unknown>;
}

export interface EdlSegment {
  id: UUID;
  asset_id: UUID;
  asset_url?: string;
  asset_type: AssetType;
  start_ms: number;
  end_ms: number;
  trim_in_ms?: number;
  trim_out_ms?: number | null;
  layer: number;
  effects?: EdlEffect[];
  transition?: EdlTransition;
}

export interface EdlAudioTrack {
  id: UUID;
  asset_id: UUID;
  asset_url?: string;
  type: "voiceover" | "music" | string;
  start_ms: number;
  end_ms?: number | null;
  volume?: number;
  fade_in_ms?: number;
  fade_out_ms?: number;
  trim_in_ms?: number;
  trim_out_ms?: number | null;
}

export interface EdlTextOverlay {
  id?: string;
  text: string;
  type?: string;
  start_ms: number;
  end_ms: number;
  style?: Record<string, unknown>;
  position?: Record<string, unknown>;
  animation?: string;
}

export interface EdlWhisperWord {
  word: string;
  start_ms: number;
  end_ms: number;
  sentence_index?: number;
}

export interface EdlMetadata {
  title?: string;
  style?: string;
  total_duration_ms: number;
  bpm?: number;
  width: number;
  height: number;
  fps: number;
  pacing?: string;
  color_grade?: Record<string, unknown>;
}

export interface EdlDto {
  version: string;
  metadata: EdlMetadata;
  segments: EdlSegment[];
  audio_tracks?: EdlAudioTrack[];
  text_overlays?: EdlTextOverlay[];
  whisper_words?: EdlWhisperWord[];
  subtitle_config?: Record<string, unknown>;
}

export interface EdlVersionDTO {
  version: number;
  source: EdlSource;
  edlId?: UUID;
  createdAt?: string;
}

export interface ActivePlanDTO {
  type: "BASIC" | "PRO" | "PREMIUM" | string;
  planType?: string;
  videosTotal: number;
  videosUsed: number;
  imagesTotal: number;
  imagesUsed: number;
  expiresAt: string;
}
