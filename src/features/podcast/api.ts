import axios from "@/lib/axios";
import type { UUID } from "@/features/video/types";
import type { ClipDto, PodcastStartResponse } from "./types";

/**
 * Uploads a source episode and starts clip generation.
 * Multipart, with upload progress (episodes are large).
 */
export const startPodcastClips = async (params: {
  file: File;
  clipCount?: number;
  language?: string;
  onProgress?: (percent: number) => void;
}): Promise<PodcastStartResponse> => {
  const formData = new FormData();
  formData.append("file", params.file);
  if (params.clipCount !== undefined) {
    formData.append("clipCount", String(params.clipCount));
  }
  if (params.language) {
    formData.append("language", params.language);
  }

  const res = await axios.post<PodcastStartResponse>(
    "/api/podcast/clips",
    formData,
    {
      headers: { "Content-Type": "multipart/form-data" },
      onUploadProgress: (e) => {
        if (params.onProgress && e.total) {
          params.onProgress(Math.round((e.loaded / e.total) * 100));
        }
      },
    },
  );
  return res.data;
};

/** Lists the clips produced for a generation (populates as clips render). */
export const getPodcastClips = async (
  generationId: UUID,
): Promise<ClipDto[]> => {
  const res = await axios.get<ClipDto[]>(
    `/api/podcast/clips/${generationId}`,
  );
  return res.data;
};
