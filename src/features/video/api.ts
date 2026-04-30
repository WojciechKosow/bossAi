import axios from "@/lib/axios";
import type {
  ActivePlanDTO,
  AnalyzePromptRequest,
  AssetDTO,
  AssetType,
  EdlDto,
  EdlVersionDTO,
  GenerationDTO,
  GenerationStartResponse,
  ProjectAssetDTO,
  PromptAnalysisResponse,
  RenderJobDTO,
  TikTokAdRequest,
  UUID,
  VideoProjectDTO,
} from "./types";

/* ============ ASSETS ============ */

export const uploadAsset = async (params: {
  file: File;
  type: AssetType;
  orderIndex?: number;
  onProgress?: (percent: number) => void;
}): Promise<AssetDTO> => {
  const formData = new FormData();
  formData.append("file", params.file);
  formData.append("type", params.type);
  if (params.orderIndex !== undefined) {
    formData.append("orderIndex", String(params.orderIndex));
  }

  const res = await axios.post<AssetDTO>("/api/assets/upload", formData, {
    headers: { "Content-Type": "multipart/form-data" },
    onUploadProgress: (e) => {
      if (params.onProgress && e.total) {
        params.onProgress(Math.round((e.loaded / e.total) * 100));
      }
    },
  });
  return res.data;
};

export const listAssets = async (): Promise<AssetDTO[]> => {
  const res = await axios.get<AssetDTO[]>("/api/assets");
  return res.data;
};

export const deleteAsset = async (id: UUID): Promise<void> => {
  await axios.delete(`/api/assets/${id}`);
};

export const assetFileUrl = (id: UUID): string => {
  const base = axios.defaults.baseURL ?? "";
  return `${base}/api/assets/file/${id}`;
};

/**
 * Backend endpoints (RenderJob.outputUrl, Generation.videoUrl, AssetDTO.url)
 * return paths relative to the API host (e.g. "/api/assets/file/{uuid}").
 * Embedding those directly in <video src> / <img src> / <a href> resolves
 * against the *frontend* origin and 404s, since the dev server runs on a
 * different port from the API. This helper prefixes axios.defaults.baseURL
 * when the value isn't already absolute.
 */
export const absoluteUrl = (
  path: string | null | undefined,
): string | undefined => {
  if (!path) return undefined;
  if (/^(https?:|blob:|data:)/.test(path)) return path;
  const base = axios.defaults.baseURL ?? "";
  return `${base}${path.startsWith("/") ? path : `/${path}`}`;
};

/**
 * Asset detail isn't a separate endpoint on the backend — every user-scoped
 * asset is in the /api/assets list. Pull from the list and find by id.
 */
export const findAsset = async (id: UUID): Promise<AssetDTO | undefined> => {
  const all = await listAssets();
  return all.find((a) => a.id === id);
};

/* ============ GENERATIONS ============ */

export const analyzePrompt = async (
  body: AnalyzePromptRequest,
): Promise<PromptAnalysisResponse> => {
  const res = await axios.post<PromptAnalysisResponse>(
    "/api/generations/analyze-prompt",
    body,
  );
  return res.data;
};

export const startGeneration = async (
  body: TikTokAdRequest,
): Promise<GenerationStartResponse> => {
  const res = await axios.post<GenerationStartResponse>(
    "/api/generations/assign-assets",
    body,
  );
  return res.data;
};

export const getGeneration = async (id: UUID): Promise<GenerationDTO> => {
  const res = await axios.get<GenerationDTO>(`/api/generations/${id}`);
  return res.data;
};

export const listMyGenerations = async (
  limit?: number,
): Promise<GenerationDTO[]> => {
  const url = limit
    ? `/api/generations/me?limit=${limit}`
    : "/api/generations/me/all";
  const res = await axios.get<GenerationDTO[]>(url);
  return res.data;
};

/* ============ PROJECTS ============ */

export const listProjects = async (): Promise<VideoProjectDTO[]> => {
  const res = await axios.get<VideoProjectDTO[]>("/api/v1/projects");
  return res.data;
};

export const getProject = async (id: UUID): Promise<VideoProjectDTO> => {
  const res = await axios.get<VideoProjectDTO>(`/api/v1/projects/${id}`);
  return res.data;
};

export const listProjectAssets = async (
  projectId: UUID,
): Promise<ProjectAssetDTO[]> => {
  const res = await axios.get<ProjectAssetDTO[]>(
    `/api/v1/projects/${projectId}/assets`,
  );
  return res.data;
};

export const triggerRender = async (
  projectId: UUID,
  quality: "low" | "medium" | "high" = "high",
): Promise<RenderJobDTO> => {
  const res = await axios.post<RenderJobDTO>(
    `/api/v1/projects/${projectId}/render?quality=${quality}`,
  );
  return res.data;
};

export const getRenderStatus = async (
  projectId: UUID,
): Promise<RenderJobDTO> => {
  const res = await axios.get<RenderJobDTO>(
    `/api/v1/projects/${projectId}/render/status`,
  );
  return res.data;
};

/* ============ TIMELINE / EDL ============ */

export const getTimeline = async (projectId: UUID): Promise<EdlDto> => {
  const res = await axios.get<EdlDto>(
    `/api/v1/projects/${projectId}/timeline/edl`,
  );
  return res.data;
};

export const saveTimeline = async (
  projectId: UUID,
  body: EdlDto,
  triggerRender = true,
): Promise<EdlVersionDTO> => {
  const res = await axios.put<EdlVersionDTO>(
    `/api/v1/projects/${projectId}/timeline?triggerRender=${triggerRender}`,
    body,
  );
  return res.data;
};

/* ============ PLANS ============ */

export const getActivePlan = async (): Promise<ActivePlanDTO> => {
  const res = await axios.get<ActivePlanDTO>("/api/me/plans/active-plan");
  return res.data;
};
