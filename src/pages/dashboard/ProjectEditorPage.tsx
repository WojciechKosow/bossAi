import { useEffect, useMemo, useRef, useState } from "react";
import { useNavigate, useParams } from "react-router-dom";
import { motion, AnimatePresence } from "framer-motion";
import {
  ArrowLeft,
  Loader2,
  Pause,
  Play,
  Download,
  Volume2,
  VolumeX,
} from "lucide-react";
import {
  useProject,
  useRenderStatus,
  useSaveTimeline,
  useTimeline,
  useTriggerRender,
} from "@/features/video/hooks";
import {
  Timeline,
  type TimelineHandle,
} from "@/features/video/components/timeline/Timeline";
import {
  InspectorActions,
  InspectorPanel,
} from "@/features/video/components/timeline/InspectorPanel";
import {
  EdlPlayer,
  type EdlPlayerHandle,
} from "@/features/video/components/timeline/EdlPlayer";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Progress } from "@/components/ui/progress";
import { useToast } from "@/components/ui/toast";
import type { EdlDto } from "@/features/video/types";
import { absoluteUrl } from "@/features/video/api";
import { cn } from "@/lib/utils";
import { formatTime } from "@/features/video/components/timeline/timelineUtils";

const ProjectEditorPage = () => {
  const { id } = useParams<{ id: string }>();
  const navigate = useNavigate();
  const toast = useToast();

  const { data: project, error: projectError } = useProject(id ?? null);
  const {
    data: timeline,
    isLoading: timelineLoading,
    error: timelineError,
  } = useTimeline(id ?? null);
  const { data: render } = useRenderStatus(id ?? null);
  const saveMut = useSaveTimeline();
  const renderMut = useTriggerRender();

  const [edl, setEdl] = useState<EdlDto | null>(null);
  const originalRef = useRef<EdlDto | null>(null);
  const [selectedId, setSelectedId] = useState<string | null>(null);
  const [playing, setPlaying] = useState(false);

  // playheadMs is used ONLY for the transport bar display and for syncing the
  // Timeline on ruler-click scrubs. Playback updates bypass React state entirely
  // via the imperative handle below, so this state is throttled.
  const [playheadMs, setPlayheadMs] = useState(0);
  const [masterVolume, setMasterVolume] = useState(1);

  const playerRef = useRef<EdlPlayerHandle>(null);
  // Imperative handle: lets us move the timeline playhead without re-rendering
  const timelineRef = useRef<TimelineHandle>(null);

  // Tracks last time we updated the React display state (throttled to ~12 fps)
  const lastDisplayUpdateRef = useRef(0);

  /* hydrate edl from API once */
  useEffect(() => {
    if (timeline && !originalRef.current) {
      originalRef.current = timeline;
      setEdl(timeline);
    }
  }, [timeline]);

  /* if a remote update comes in (e.g. re-render finished and bumped version) — refresh baseline */
  useEffect(() => {
    if (!timeline) return;
    const baseline = originalRef.current;
    if (baseline && baseline.version !== timeline.version && !dirty) {
      originalRef.current = timeline;
      setEdl(timeline);
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [timeline?.version]);

  const dirty = useMemo(() => {
    if (!edl || !originalRef.current) return false;
    return JSON.stringify(edl) !== JSON.stringify(originalRef.current);
  }, [edl]);

  const selectedSegment = useMemo(
    () => edl?.segments.find((s) => s.id === selectedId) ?? null,
    [edl, selectedId],
  );

  const downloadUrl = useMemo(
    () => absoluteUrl(render?.outputUrl),
    [render?.outputUrl],
  );

  /* scrub: user clicked the ruler in Timeline */
  const onScrub = (ms: number) => {
    setPlayheadMs(ms);
    lastDisplayUpdateRef.current = performance.now(); // reset throttle
    playerRef.current?.seek(ms);
  };

  const togglePlay = () => {
    const p = playerRef.current;
    if (!p) return;
    if (playing) {
      p.pause();
    } else {
      p.play();
    }
  };

  const onSave = async () => {
    if (!edl || !id) return;
    try {
      await saveMut.mutateAsync({ projectId: id, edl, triggerRender: true });
      originalRef.current = edl;
      toast.success("Saved — re-render queued");
    } catch (e: any) {
      toast.error(
        e?.response?.data?.message ??
          "Could not save timeline (validation error)",
      );
    }
  };

  const onRevert = () => {
    if (originalRef.current) setEdl(originalRef.current);
  };

  const onRerender = async () => {
    if (!id) return;
    try {
      await renderMut.mutateAsync({ projectId: id, quality: "high" });
      toast.info("Render queued");
    } catch (e: any) {
      toast.error(e?.response?.data?.message ?? "Failed to queue render");
    }
  };

  if (projectError || timelineError) {
    return (
      <ErrorState
        title={projectError ? "Project not found" : "Timeline not available"}
        description={
          projectError
            ? "This project may have been deleted, or it never had an editable timeline. You can preview the rendered video from the library."
            : "The backend hasn't generated a timeline for this project yet. This usually means the new pipeline isn't enabled (rendering.use-new-pipeline)."
        }
        onBack={() => navigate("/dashboard/library")}
      />
    );
  }

  if (timelineLoading || !edl) {
    return (
      <div className="flex items-center justify-center py-32">
        <Loader2 className="size-5 animate-spin text-muted-foreground" />
      </div>
    );
  }

  const isRendering =
    render?.status === "RENDERING" || render?.status === "QUEUED";

  return (
    <div className="space-y-5">
      {/* header */}
      <div className="flex flex-col lg:flex-row lg:items-center lg:justify-between gap-3">
        <div className="flex items-center gap-3">
          <button
            onClick={() => navigate("/dashboard/library")}
            className="size-9 rounded-md hover:bg-muted text-muted-foreground hover:text-foreground flex items-center justify-center transition"
          >
            <ArrowLeft size={16} />
          </button>
          <div className="min-w-0">
            <h1 className="text-xl font-semibold truncate max-w-md">
              {project?.title || project?.originalPrompt || "Untitled project"}
            </h1>
            <div className="flex items-center gap-2 mt-1">
              <Badge variant="outline">v{edl.version}</Badge>
              {project?.style && (
                <Badge variant="secondary">
                  {project.style.replace(/_/g, " ")}
                </Badge>
              )}
              {isRendering && (
                <Badge variant="warning">
                  <Loader2 size={10} className="animate-spin" /> Rendering{" "}
                  {render?.progress ? Math.round(render.progress * 100) : ""}%
                </Badge>
              )}
              {render?.status === "COMPLETE" && (
                <Badge variant="success">Up to date</Badge>
              )}
              {render?.status === "FAILED" && (
                <Badge variant="destructive">Render failed</Badge>
              )}
            </div>
          </div>
        </div>
        <InspectorActions
          saving={saveMut.isPending || renderMut.isPending}
          dirty={dirty}
          onSave={onSave}
          onRevert={onRevert}
          onTriggerRender={onRerender}
        />
      </div>

      <div className="grid grid-cols-1 xl:grid-cols-[420px_1fr] gap-5">
        {/* ── Live preview ── */}
        <motion.div
          layout
          className="rounded-xl border border-border bg-black overflow-hidden flex flex-col"
        >
          <div className="aspect-[9/16] bg-black relative">
            <EdlPlayer
              ref={playerRef}
              edl={edl}
              className="size-full"
              onTimeUpdate={(ms) => {
                // 1. Update timeline playhead imperatively — no React re-render,
                //    runs at full RAF speed (60 fps).
                timelineRef.current?.updatePlayhead(ms);

                // 2. Update the transport bar display at ~12 fps to avoid
                //    re-rendering the whole page on every animation frame.
                const now = performance.now();
                if (now - lastDisplayUpdateRef.current > 80) {
                  lastDisplayUpdateRef.current = now;
                  setPlayheadMs(ms);
                }
              }}
              onPlayStateChange={(p) => setPlaying(p)}
              masterVolume={masterVolume}
            />

            {/* Big play button overlay */}
            <button
              onClick={togglePlay}
              className="absolute inset-0 flex items-center justify-center group"
            >
              <AnimatePresence>
                {!playing && (
                  <motion.div
                    key="play"
                    initial={{ opacity: 0, scale: 0.7 }}
                    animate={{ opacity: 1, scale: 1 }}
                    exit={{ opacity: 0, scale: 0.7 }}
                    className="size-16 rounded-full gradient-bg flex items-center justify-center shadow-glow"
                  >
                    <Play className="size-7 text-white ml-0.5" />
                  </motion.div>
                )}
              </AnimatePresence>
            </button>
          </div>

          {/* Transport bar */}
          <div className="px-3 py-2 border-t border-border flex items-center justify-between bg-card">
            <div className="flex items-center gap-2">
              <button
                onClick={togglePlay}
                className="size-8 rounded-md bg-muted hover:bg-accent text-foreground flex items-center justify-center transition"
              >
                {playing ? <Pause size={14} /> : <Play size={14} />}
              </button>
              <span className="text-xs font-mono text-muted-foreground">
                {formatTime(playheadMs)} /{" "}
                {formatTime(edl.metadata?.total_duration_ms ?? 0)}
              </span>
            </div>

            <div className="flex items-center gap-2">
              <button
                onClick={() => setMasterVolume((v) => (v > 0 ? 0 : 1))}
                className="text-muted-foreground hover:text-foreground transition"
                title={masterVolume === 0 ? "Unmute" : "Mute"}
              >
                {masterVolume === 0 ? (
                  <VolumeX size={14} />
                ) : (
                  <Volume2 size={14} />
                )}
              </button>
              <input
                type="range"
                min={0}
                max={1}
                step={0.01}
                value={masterVolume}
                onChange={(e) => setMasterVolume(Number(e.target.value))}
                className="w-20 h-1 accent-primary cursor-pointer"
                title={`Volume: ${Math.round(masterVolume * 100)}%`}
              />

              {downloadUrl && (
                <a
                  href={downloadUrl}
                  download
                  title="Download last render"
                  className="text-xs text-muted-foreground hover:text-foreground inline-flex items-center gap-1.5 transition"
                >
                  <Download size={12} /> mp4
                </a>
              )}
            </div>
          </div>

          {isRendering && (
            <div className="px-3 py-2 border-t border-border">
              <Progress
                value={(render?.progress ?? 0) * 100}
                indeterminate={!render?.progress}
              />
            </div>
          )}
        </motion.div>

        {/* Inspector + Timeline */}
        <div className="space-y-5">
          <Timeline
            ref={timelineRef}
            edl={edl}
            selectedSegmentId={selectedId}
            onSelectSegment={setSelectedId}
            onChange={setEdl}
            playheadMs={playheadMs}
            onScrub={onScrub}
          />
          <InspectorPanel
            edl={edl}
            segment={selectedSegment}
            onChange={setEdl}
          />
        </div>
      </div>

      {dirty && (
        <motion.div
          initial={{ opacity: 0, y: 12 }}
          animate={{ opacity: 1, y: 0 }}
          className={cn(
            "fixed bottom-6 left-1/2 -translate-x-1/2 z-30 glass border border-border rounded-xl px-4 py-2.5 shadow-elev flex items-center gap-3",
          )}
        >
          <span className="text-xs text-muted-foreground">Unsaved changes</span>
          <Button size="sm" variant="ghost" onClick={onRevert}>
            Discard
          </Button>
          <Button
            size="sm"
            onClick={onSave}
            disabled={saveMut.isPending}
            className="gradient-bg text-white shadow-glow"
          >
            {saveMut.isPending ? "Saving…" : "Save & re-render"}
          </Button>
        </motion.div>
      )}
    </div>
  );
};

export default ProjectEditorPage;

const ErrorState = ({
  title,
  description,
  onBack,
}: {
  title: string;
  description: string;
  onBack: () => void;
}) => (
  <div className="max-w-xl mx-auto rounded-2xl border border-border bg-card p-10 text-center mt-10">
    <div className="size-12 rounded-xl bg-destructive/10 text-destructive mx-auto flex items-center justify-center">
      <ArrowLeft className="size-5 rotate-45" />
    </div>
    <h2 className="text-lg font-semibold mt-5">{title}</h2>
    <p className="text-sm text-muted-foreground mt-2">{description}</p>
    <Button
      onClick={onBack}
      className="mt-6 gradient-bg text-white shadow-glow"
    >
      <ArrowLeft size={14} /> Back to library
    </Button>
  </div>
);
