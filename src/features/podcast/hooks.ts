import { useMutation, useQuery } from "@tanstack/react-query";
import { getGeneration } from "@/features/video/api";
import type { GenerationDTO, UUID } from "@/features/video/types";
import { getPodcastClips, startPodcastClips } from "./api";
import type { ClipDto } from "./types";

export const useStartPodcastClips = () =>
  useMutation({
    mutationFn: (params: {
      file: File;
      clipCount?: number;
      language?: string;
      onProgress?: (p: number) => void;
    }) => startPodcastClips(params),
  });

/** True while the generation is still working. */
const isActive = (g?: GenerationDTO) =>
  !!g && (g.status === "PENDING" || g.status === "PROCESSING");

/**
 * Polls the generation status. Stops polling once DONE/FAILED.
 */
export const useGenerationStatus = (generationId: UUID | null) =>
  useQuery<GenerationDTO>({
    queryKey: ["generation", generationId],
    queryFn: () => getGeneration(generationId as UUID),
    enabled: !!generationId,
    refetchInterval: (query) => (isActive(query.state.data) ? 4000 : false),
  });

/**
 * Polls the clips list while the generation is active, so clips appear as they
 * finish rendering. Stops once the generation is terminal.
 */
export const usePodcastClips = (
  generationId: UUID | null,
  generationActive: boolean,
) =>
  useQuery<ClipDto[]>({
    queryKey: ["podcast-clips", generationId],
    queryFn: () => getPodcastClips(generationId as UUID),
    enabled: !!generationId,
    refetchInterval: generationActive ? 4000 : false,
  });
