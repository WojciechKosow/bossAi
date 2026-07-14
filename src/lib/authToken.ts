/**
 * Single place that owns the access-token in browser storage.
 *
 * The session is kept alive primarily by this stored access token rather than
 * the cross-site `refreshToken` cookie: when the frontend runs on a different
 * origin than the API (local dev → Railway), that cookie is a third-party
 * cookie and is increasingly blocked by browsers (Safari ITP, Chrome). Reading
 * the token back on reload lets us re-authenticate without depending on it.
 */
const TOKEN_KEY = "access_token";

export const getStoredToken = (): string | null => {
  try {
    return (
      localStorage.getItem(TOKEN_KEY) ??
      sessionStorage.getItem(TOKEN_KEY) ??
      null
    );
  } catch {
    return null;
  }
};

export const setStoredToken = (token: string): void => {
  if (!token) return;
  try {
    localStorage.setItem(TOKEN_KEY, token);
  } catch {
    /* storage unavailable (private mode / disabled) — ignore */
  }
};

export const clearStoredToken = (): void => {
  try {
    localStorage.removeItem(TOKEN_KEY);
    sessionStorage.removeItem(TOKEN_KEY);
  } catch {
    /* ignore */
  }
};
