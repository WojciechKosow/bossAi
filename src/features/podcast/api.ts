import axios from "@/lib/axios";
import type { UUID } from "@/features/video/types";
import type {
  ClipDto,
  PodcastStartResponse,
  PodcastUploadUrlResponse,
} from "./types";

/**
 * Uploads a source episode and starts clip generation.
 *
 * Large episodes (multi-GB) can't go through the backend — Railway's HTTP/2
 * edge + Spring multipart choke on big bodies (ERR_HTTP2_PROTOCOL_ERROR). So we
 * upload the file straight to R2 with a presigned PUT, then start generation
 * from the resulting storageKey:
 *
 *   1. POST /api/podcast/upload-url  → { uploadUrl, storageKey }
 *   2. PUT the file bytes to uploadUrl (raw XHR, for progress; no auth header —
 *      the URL is self-authorizing)
 *   3. POST /api/podcast/clips (JSON) → { generationId, status }
 */
export const startPodcastClips = async (params: {
  file: File;
  clipCount?: number;
  language?: string;
  onProgress?: (percent: number) => void;
}): Promise<PodcastStartResponse> => {
  // 1. Ask the backend for a presigned direct-to-R2 upload URL.
  const { data: presign } = await axios.post<PodcastUploadUrlResponse>(
    "/api/podcast/upload-url",
    { filename: params.file.name },
  );

  // 2. Upload the file straight to R2. XHR (not axios) so we get real upload
  //    progress and can PUT the raw File body to the presigned URL as-is.
  await putToStorage(presign.uploadUrl, params.file, params.onProgress);

  // 3. Start generation from the uploaded object.
  const res = await axios.post<PodcastStartResponse>("/api/podcast/clips", {
    storageKey: presign.storageKey,
    originalFilename: params.file.name,
    clipCount: params.clipCount,
    language: params.language,
  });
  return res.data;
};

/**
 * PUTs a file straight to a presigned storage URL with upload progress.
 * Deliberately does NOT use the axios instance: the URL is an absolute R2 URL
 * that carries its own auth in the query string, so the app's baseURL, bearer
 * token, and interceptors must not be attached.
 */
const putToStorage = (
  uploadUrl: string,
  file: File,
  onProgress?: (percent: number) => void,
): Promise<void> =>
  new Promise((resolve, reject) => {
    const xhr = new XMLHttpRequest();
    xhr.open("PUT", uploadUrl, true);
    xhr.setRequestHeader(
      "Content-Type",
      file.type || "application/octet-stream",
    );
    xhr.upload.onprogress = (e) => {
      if (onProgress && e.lengthComputable) {
        onProgress(Math.round((e.loaded / e.total) * 100));
      }
    };
    xhr.onload = () => {
      if (xhr.status >= 200 && xhr.status < 300) {
        onProgress?.(100);
        resolve();
      } else {
        reject(
          new Error(
            `Upload failed (${xhr.status}). If this persists, the storage ` +
              `bucket may be missing CORS for PUT from this site.`,
          ),
        );
      }
    };
    xhr.onerror = () =>
      reject(
        new Error(
          "Upload failed — network error or blocked by storage CORS.",
        ),
      );
    xhr.send(file);
  });

/** Lists the clips produced for a generation (populates as clips render). */
export const getPodcastClips = async (
  generationId: UUID,
): Promise<ClipDto[]> => {
  const res = await axios.get<ClipDto[]>(
    `/api/podcast/clips/${generationId}`,
  );
  return res.data;
};
