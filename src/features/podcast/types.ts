import type { GenerationStatus, UUID } from "@/features/video/types";

export type ClipStatus = "PENDING" | "RENDERING" | "READY" | "FAILED";

/** Mirrors the backend ClipDto (GET /api/podcast/clips/{generationId}). */
export interface ClipDto {
  id: UUID;
  clipIndex: number;
  status: ClipStatus;
  title: string | null;
  reasoning: string | null;
  sourceStartMs: number;
  sourceEndMs: number;
  durationMs: number;
  downloadUrl: string | null;
  errorMessage: string | null;
}

/** Response from POST /api/podcast/clips — the generation acts as the job anchor. */
export interface PodcastStartResponse {
  generationId: UUID;
  status: GenerationStatus;
}

/** Response from POST /api/podcast/upload-url — a presigned direct-to-R2 PUT. */
export interface PodcastUploadUrlResponse {
  uploadUrl: string;
  storageKey: string;
}
