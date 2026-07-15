/**
 * Tracks the id of the generation the user most recently kicked off, in
 * localStorage, so a global watcher (mounted in the dashboard layout) can
 * notify them when it finishes even if they've navigated away from the
 * Create page. Cleared once the result has been surfaced.
 */
const keyFor = (userId: string | null | undefined) =>
  `bossai:active-generation:${userId ?? "anon"}`;

export const setActiveGeneration = (
  userId: string | null | undefined,
  generationId: string,
): void => {
  try {
    localStorage.setItem(keyFor(userId), generationId);
  } catch {
    /* best-effort */
  }
};

export const getActiveGeneration = (
  userId: string | null | undefined,
): string | null => {
  try {
    return localStorage.getItem(keyFor(userId));
  } catch {
    return null;
  }
};

export const clearActiveGeneration = (
  userId: string | null | undefined,
): void => {
  try {
    localStorage.removeItem(keyFor(userId));
  } catch {
    /* ignore */
  }
};
