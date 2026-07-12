import type { AssetDTO, UUID } from "./types";

/**
 * Compose-screen draft, persisted to localStorage so a user who closes the
 * tab keeps their prompt + chosen assets. Only asset *ids* and order are
 * stored — the binaries already live server-side (uploaded on drop), so the
 * draft is just references we re-hydrate from GET /api/assets on load.
 *
 * Scope: per-user, per-browser. localStorage is keyed by user id, so switching
 * accounts on the same browser won't leak drafts. Cross-device drafts would
 * need a backend endpoint (out of scope for v0.1).
 */
export interface CreateDraft {
  prompt: string;
  mediaIds: UUID[];
  ttsIds: UUID[];
  musicId: UUID | null;
}

const PREFIX = "bossai:create-draft:";
const keyFor = (userId: string | null | undefined) => `${PREFIX}${userId ?? "anon"}`;

export const loadDraft = (
  userId: string | null | undefined,
): CreateDraft | null => {
  try {
    const raw = localStorage.getItem(keyFor(userId));
    if (!raw) return null;
    const parsed = JSON.parse(raw) as Partial<CreateDraft>;
    return {
      prompt: typeof parsed.prompt === "string" ? parsed.prompt : "",
      mediaIds: Array.isArray(parsed.mediaIds) ? parsed.mediaIds : [],
      ttsIds: Array.isArray(parsed.ttsIds) ? parsed.ttsIds : [],
      musicId: parsed.musicId ?? null,
    };
  } catch {
    return null;
  }
};

export const saveDraft = (
  userId: string | null | undefined,
  draft: CreateDraft,
): void => {
  try {
    const empty =
      !draft.prompt.trim() &&
      !draft.mediaIds.length &&
      !draft.ttsIds.length &&
      !draft.musicId;
    if (empty) {
      localStorage.removeItem(keyFor(userId));
      return;
    }
    localStorage.setItem(keyFor(userId), JSON.stringify(draft));
  } catch {
    /* storage full / unavailable — drafting is best-effort */
  }
};

export const clearDraft = (userId: string | null | undefined): void => {
  try {
    localStorage.removeItem(keyFor(userId));
  } catch {
    /* ignore */
  }
};

/** Re-order `pool` to match `ids`, dropping ids no longer present server-side. */
export const pickInOrder = (pool: AssetDTO[], ids: UUID[]): AssetDTO[] => {
  const byId = new Map(pool.map((a) => [a.id, a]));
  return ids.map((id) => byId.get(id)).filter((a): a is AssetDTO => !!a);
};
