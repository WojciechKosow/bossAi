import axios from "axios";
import { clearStoredToken, getStoredToken, setStoredToken } from "./authToken";

const authExcludedPaths = [
  "/api/auth/login",
  "/api/auth/register",
  "/api/auth/refresh",
  "/api/auth/forgot-password",
  "/api/auth/reset-password",
];

// Single source of truth for the API origin. Accepts either env name so a
// misconfigured .env (VITE_API_URL vs VITE_API_BASE) still resolves; falls
// back to local dev.
const baseURL =
  (import.meta.env.VITE_API_BASE as string | undefined) ??
  (import.meta.env.VITE_API_URL as string | undefined) ??
  "http://localhost:8080";

const instance = axios.create({
  baseURL,
  withCredentials: true,
});

// Attach the bearer token from storage on every request. This keeps API calls
// authenticated after a full page reload — when the in-memory default header is
// gone — without relying on the cross-site refresh cookie.
instance.interceptors.request.use((config) => {
  if (!config.headers.Authorization) {
    const token = getStoredToken();
    if (token) {
      config.headers.Authorization = `Bearer ${token}`;
    }
  }
  return config;
});

let isRefreshing = false;
let failedQueue: any[] = [];

const processQueue = (error: any, token: string | null = null) => {
  failedQueue.forEach((prom) => {
    if (error) {
      prom.reject(error);
    } else {
      prom.resolve(token);
    }
  });
  failedQueue = [];
};

const applyToken = (token: string) => {
  setStoredToken(token);
  instance.defaults.headers.common["Authorization"] = `Bearer ${token}`;
};

instance.interceptors.response.use(
  (response) => response,
  async (error) => {
    const originalRequest = error.config;

    if (
      error.response?.status !== 401 ||
      !originalRequest ||
      originalRequest._retry ||
      authExcludedPaths.some((path) => originalRequest.url?.includes(path))
    ) {
      return Promise.reject(error);
    }

    if (isRefreshing) {
      return new Promise(function (resolve, reject) {
        failedQueue.push({ resolve, reject });
      })
        .then((token) => {
          originalRequest.headers["Authorization"] = "Bearer " + token;
          return instance(originalRequest);
        })
        .catch((err) => Promise.reject(err));
    }

    originalRequest._retry = true;
    isRefreshing = true;

    try {
      const res = await instance.post("/api/auth/refresh");

      const newToken = res.data.token;

      applyToken(newToken);

      processQueue(null, newToken);

      // Retry the original request with the fresh token (the queued-request
      // path already sets this; the direct path did not before).
      originalRequest.headers["Authorization"] = `Bearer ${newToken}`;
      return instance(originalRequest);
    } catch (err) {
      processQueue(err, null);
      // Refresh failed for a real 401 — the session can't continue.
      clearStoredToken();
      return Promise.reject(err);
    } finally {
      isRefreshing = false;
    }
  },
);

export default instance;
