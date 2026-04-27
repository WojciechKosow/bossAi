import axios from "@/lib/axios";
import type { ProgressEvent as ProgressPayload, UUID } from "./types";

/**
 * Streams generation progress as SSE.
 *
 * EventSource doesn't allow custom headers, so we manually fetch with
 * the Authorization header and parse the SSE protocol from the body
 * stream. This stays consistent with the rest of the auth flow which
 * keeps the access token on `axios.defaults.headers.common.Authorization`
 * (refreshed via cookie). The HttpOnly refresh cookie also rides along
 * thanks to credentials: "include".
 */
export const streamGenerationProgress = (
  generationId: UUID,
  handlers: {
    onEvent: (payload: ProgressPayload) => void;
    onError?: (err: unknown) => void;
    onClose?: () => void;
    signal?: AbortSignal;
  },
): (() => void) => {
  const controller = new AbortController();
  const signal = handlers.signal ?? controller.signal;

  const baseURL = axios.defaults.baseURL ?? "";
  const auth = axios.defaults.headers.common["Authorization"];

  const headers: Record<string, string> = {
    Accept: "text/event-stream",
  };
  if (auth) headers["Authorization"] = String(auth);

  let buffer = "";
  let closed = false;

  const close = () => {
    if (closed) return;
    closed = true;
    controller.abort();
    handlers.onClose?.();
  };

  const dispatch = (chunk: string) => {
    let eventName = "message";
    let dataLines: string[] = [];

    for (const raw of chunk.split(/\r?\n/)) {
      if (!raw) continue;
      if (raw.startsWith("event:")) {
        eventName = raw.slice(6).trim();
      } else if (raw.startsWith("data:")) {
        dataLines.push(raw.slice(5).trim());
      }
    }

    if (!dataLines.length) return;
    const dataString = dataLines.join("\n");

    try {
      const json = JSON.parse(dataString) as ProgressPayload;
      handlers.onEvent(json);
      if (json.step === "DONE" || eventName === "done") close();
    } catch {
      // ignore non-JSON heartbeat frames
    }
  };

  (async () => {
    try {
      const res = await fetch(`${baseURL}/api/generations/${generationId}/progress`, {
        method: "GET",
        headers,
        credentials: "include",
        signal,
      });

      if (!res.ok || !res.body) {
        throw new Error(`SSE failed: ${res.status}`);
      }

      const reader = res.body.getReader();
      const decoder = new TextDecoder();

      while (!closed) {
        const { done, value } = await reader.read();
        if (done) break;

        buffer += decoder.decode(value, { stream: true });

        let idx: number;
        while ((idx = buffer.indexOf("\n\n")) !== -1) {
          const chunk = buffer.slice(0, idx);
          buffer = buffer.slice(idx + 2);
          dispatch(chunk);
        }
      }
    } catch (err) {
      if (!closed && (err as Error).name !== "AbortError") {
        handlers.onError?.(err);
      }
    } finally {
      close();
    }
  })();

  return close;
};
