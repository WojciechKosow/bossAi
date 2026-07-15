import { useEffect, useState } from "react";
import {
  useMutation,
  useQuery,
  useQueryClient,
} from "@tanstack/react-query";
import axios from "@/lib/axios";
import {
  analyzePrompt,
  deleteAsset,
  getActivePlan,
  getGeneration,
  getProject,
  getRenderStatus,
  getTimeline,
  listAssets,
  listMyGenerations,
  listProjectAssets,
  listProjects,
  saveTimeline,
  startGeneration,
  triggerRender,
  uploadAsset,
} from "./api";
import { streamGenerationProgress } from "./sse";
import type {
  AnalyzePromptRequest,
  AssetType,
  EdlDto,
  ProgressEvent as ProgressPayload,
  RenderJobDTO,
  TikTokAdRequest,
  UUID,
} from "./types";

/* ---------- assets ---------- */

export const useAssets = () =>
  useQuery({
    queryKey: ["assets"],
    queryFn: listAssets,
  });

export const useUploadAsset = () => {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (params: {
      file: File;
      type: AssetType;
      orderIndex?: number;
      onProgress?: (p: number) => void;
    }) => uploadAsset(params),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ["assets"] });
    },
  });
};

export const useDeleteAsset = () => {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (id: UUID) => deleteAsset(id),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ["assets"] });
    },
  });
};

/**
 * Fetch an asset binary with the JWT bearer header and expose it as a
 * blob URL. Browser <img>/<video> tags can't carry the Authorization
 * header, and `/api/assets/file/**` is auth-protected on the backend,
 * so naive `<img src="/api/assets/file/{id}">` returns 401.
 *
 * Cached forever per asset id, so re-mounting the same tile reuses the
 * blob instead of re-downloading.
 */
export const useAssetBlobUrl = (
  assetId: UUID | null | undefined,
): string | null => {
  const { data } = useQuery({
    queryKey: ["asset-blob", assetId],
    queryFn: async () => {
      const res = await axios.get(`/api/assets/file/${assetId}`, {
        responseType: "blob",
      });
      return URL.createObjectURL(res.data as Blob);
    },
    enabled: !!assetId,
    staleTime: Infinity,
    gcTime: 1000 * 60 * 30,
  });
  return data ?? null;
};

/* ---------- analyze + start ---------- */

export const useAnalyzePrompt = () =>
  useMutation({
    mutationFn: (body: AnalyzePromptRequest) => analyzePrompt(body),
  });

export const useStartGeneration = () =>
  useMutation({
    mutationFn: (body: TikTokAdRequest) => startGeneration(body),
  });

/* ---------- progress (SSE) ---------- */

export const useGenerationProgress = (
  generationId: UUID | null,
  enabled = true,
) => {
  const [progress, setProgress] = useState<ProgressPayload | null>(null);
  const [done, setDone] = useState(false);
  /** Generation ended in FAILED — holds the failure message. */
  const [failed, setFailed] = useState<string | null>(null);
  /** Every distinct step event received, in order — drives the step checklist. */
  const [events, setEvents] = useState<ProgressPayload[]>([]);

  useEffect(() => {
    if (!generationId || !enabled) return;
    setDone(false);
    setFailed(null);
    setProgress(null);
    setEvents([]);

    // `settled` guards against the two completion signals (SSE + polling)
    // racing each other, and stops the poll loop once we're done.
    let settled = false;
    const settleDone = () => {
      if (settled) return;
      settled = true;
      setDone(true);
    };
    const settleFailed = (msg?: string) => {
      if (settled) return;
      settled = true;
      setFailed(msg || "Generation failed");
    };

    // 1) SSE — live per-step progress while connected. Best-effort: the backend
    //    SseEmitter times out after 5 min, so long renders WILL drop the stream.
    //    We swallow that error; the poll below is the source of truth.
    const closeSse = streamGenerationProgress(generationId, {
      onEvent: (p) => {
        setProgress(p);
        setEvents((prev) =>
          prev.length && prev[prev.length - 1].step === p.step
            ? [...prev.slice(0, -1), p]
            : [...prev.slice(-49), p],
        );
        if (p.step === "DONE") settleDone();
        if (p.step === "FAILED") settleFailed(p.message);
      },
      onError: () => {}, // ignore — polling covers completion
      onClose: () => {},
    });

    // 2) Poll the generation status as the authoritative completion signal.
    //    Survives SSE timeouts/drops so the UI never gets stuck on a finished
    //    (or failed) render.
    let pollTimer: number | undefined;
    const poll = async () => {
      if (settled) return;
      try {
        const gen = await getGeneration(generationId);
        if (settled) return;
        if (gen.status === "DONE") return settleDone();
        if (gen.status === "FAILED") return settleFailed();
      } catch {
        /* transient (network/SSE-timeout dispatch) — keep polling */
      }
      pollTimer = window.setTimeout(poll, 4000);
    };
    pollTimer = window.setTimeout(poll, 4000);

    return () => {
      settled = true;
      closeSse?.();
      window.clearTimeout(pollTimer);
    };
  }, [generationId, enabled]);

  return { progress, done, failed, events };
};

/* ---------- generations history ---------- */

export const useRecentGenerations = (limit?: number) =>
  useQuery({
    queryKey: ["generations", limit ?? "all"],
    queryFn: () => listMyGenerations(limit),
  });

/* ---------- projects ---------- */

export const useProjects = () =>
  useQuery({
    queryKey: ["projects"],
    queryFn: listProjects,
    refetchInterval: 10_000,
  });

export const useProject = (projectId: UUID | null) =>
  useQuery({
    queryKey: ["project", projectId],
    queryFn: () => getProject(projectId as UUID),
    enabled: !!projectId,
  });

export const useProjectAssets = (projectId: UUID | null) =>
  useQuery({
    queryKey: ["project-assets", projectId],
    queryFn: () => listProjectAssets(projectId as UUID),
    enabled: !!projectId,
  });

export const useTriggerRender = () => {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (params: {
      projectId: UUID;
      quality?: "low" | "medium" | "high";
    }) => triggerRender(params.projectId, params.quality ?? "high"),
    onSuccess: (_, vars) => {
      qc.invalidateQueries({ queryKey: ["render-status", vars.projectId] });
    },
  });
};

export const useRenderStatus = (
  projectId: UUID | null,
  options?: { enabled?: boolean },
) =>
  useQuery<RenderJobDTO>({
    queryKey: ["render-status", projectId],
    queryFn: () => getRenderStatus(projectId as UUID),
    enabled: !!projectId && (options?.enabled ?? true),
    refetchInterval: (query) => {
      const data = query.state.data;
      if (!data) return 3_000;
      if (data.status === "QUEUED" || data.status === "RENDERING")
        return 2_500;
      return false;
    },
  });

/* ---------- timeline ---------- */

export const useTimeline = (projectId: UUID | null) =>
  useQuery({
    queryKey: ["timeline", projectId],
    queryFn: () => getTimeline(projectId as UUID),
    enabled: !!projectId,
  });

export const useSaveTimeline = () => {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (params: {
      projectId: UUID;
      edl: EdlDto;
      triggerRender?: boolean;
    }) => saveTimeline(params.projectId, params.edl, params.triggerRender),
    onSuccess: (_, vars) => {
      qc.invalidateQueries({ queryKey: ["timeline", vars.projectId] });
      qc.invalidateQueries({ queryKey: ["render-status", vars.projectId] });
      qc.invalidateQueries({ queryKey: ["project", vars.projectId] });
    },
  });
};

/* ---------- plan ---------- */

export const useActivePlan = () =>
  useQuery({
    queryKey: ["active-plan"],
    queryFn: getActivePlan,
  });
