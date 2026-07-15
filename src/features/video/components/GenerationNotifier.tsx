import { useEffect, useRef } from "react";
import { useAuth } from "@/features/auth/context/AuthContext";
import { useToast } from "@/components/ui/toast";
import { getGeneration, getRenderStatus, listProjects } from "../api";
import { clearActiveGeneration, getActiveGeneration } from "../activeGeneration";

const POLL_MS = 6000;

/**
 * Watches the user's most recent generation (id kept in localStorage by the
 * Create page) and pops a toast when it finishes — so a user who navigated
 * away from the Create page still gets told their video is ready.
 *
 * The Create page shows the result inline and clears the marker itself, so
 * this watcher stays silent while the user is actually on /dashboard/create.
 * Waits for the final render to COMPLETE before notifying (matches the
 * inline flow), and only notifies once per generation.
 */
export const GenerationNotifier = () => {
  const userId = useAuth().user?.id ?? null;
  const toast = useToast();
  const handledRef = useRef<Set<string>>(new Set());

  useEffect(() => {
    let stopped = false;

    const onCreatePage = () =>
      window.location.pathname.startsWith("/dashboard/create");

    const tick = async () => {
      if (stopped) return;
      const genId = getActiveGeneration(userId);
      if (!genId || handledRef.current.has(genId)) return;

      try {
        const gen = await getGeneration(genId);
        if (gen.status === "FAILED") {
          handledRef.current.add(genId);
          clearActiveGeneration(userId);
          if (!onCreatePage()) toast.error("Your video generation failed.");
          return;
        }
        if (gen.status !== "DONE") return; // still generating

        // Generation done — wait for the final render to actually complete.
        const projects = await listProjects().catch(() => []);
        const project = projects.find((p) => p.generationId === genId);
        if (project) {
          const render = await getRenderStatus(project.id).catch(() => null);
          if (
            render &&
            (render.status === "QUEUED" || render.status === "RENDERING")
          ) {
            return; // still rendering — wait for the next tick
          }
        }

        handledRef.current.add(genId);
        clearActiveGeneration(userId);
        if (!onCreatePage()) {
          toast.success("Your video is ready — find it in your Library.");
        }
      } catch {
        /* transient — retry next tick */
      }
    };

    const interval = window.setInterval(tick, POLL_MS);
    void tick();
    return () => {
      stopped = true;
      window.clearInterval(interval);
    };
  }, [userId, toast]);

  return null;
};
