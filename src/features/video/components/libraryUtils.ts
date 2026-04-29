import { format, formatDistanceToNow } from "date-fns";
import type {
  AssetDTO,
  GenerationDTO,
  VideoProjectDTO,
} from "../types";

/**
 * Compute a meaningful title for a library item.
 *
 * Resolution order (most specific → least specific):
 *   1. project.title (set by AssetBridgeService from the prompt prefix)
 *   2. project.originalPrompt (full prompt — trimmed to ~60 chars)
 *   3. asset.url-derived hint (n/a for AI-generated assets)
 *   4. formatted timestamp ("Apr 29 · 4:30 PM") — always available
 *   5. last-resort generic label
 */
export const computeItemTitle = (params: {
  project?: VideoProjectDTO;
  asset?: AssetDTO;
  generation?: GenerationDTO;
  createdAt?: string;
}): string => {
  const { project, generation, createdAt } = params;

  const fromProjectTitle = project?.title?.trim();
  if (fromProjectTitle) return truncate(fromProjectTitle, 70);

  const fromProjectPrompt = project?.originalPrompt?.trim();
  if (fromProjectPrompt) return truncate(fromProjectPrompt, 70);

  const ts = createdAt ?? generation?.createdAt;
  if (ts) {
    try {
      return `Video · ${format(new Date(ts), "MMM d, h:mm a")}`;
    } catch {
      /* fall through */
    }
  }

  return "Untitled video";
};

export const computeRelativeTimestamp = (iso: string | undefined): string => {
  if (!iso) return "";
  try {
    return formatDistanceToNow(new Date(iso), { addSuffix: true });
  } catch {
    return "";
  }
};

const truncate = (s: string, max: number): string =>
  s.length > max ? s.slice(0, max - 1).trimEnd() + "…" : s;
