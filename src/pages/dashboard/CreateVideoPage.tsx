import { useEffect, useMemo, useRef, useState } from "react";
import { useNavigate } from "react-router-dom";
import { motion, AnimatePresence } from "framer-motion";
import {
  Sparkles,
  ArrowRight,
  ChevronRight,
  Loader2,
  Wand2,
  Check,
  AlertTriangle,
  RotateCcw,
  Film,
  Mic,
  Music,
  Trash2,
  GraduationCap,
} from "lucide-react";
import { OrderedAssetZone } from "@/features/video/components/OrderedAssetZone";
import {
  useAssets,
  useGenerationProgress,
  useProjects,
  useStartGeneration,
} from "@/features/video/hooks";
import {
  clearDraft,
  loadDraft,
  pickInOrder,
  saveDraft,
} from "@/features/video/draft";
import type {
  AssetDTO,
  ProgressEvent as ProgressPayload,
  UUID,
  VideoStyle,
} from "@/features/video/types";
import { useAuth } from "@/features/auth/context/AuthContext";
import { Button } from "@/components/ui/button";
import { Progress } from "@/components/ui/progress";
import { Badge } from "@/components/ui/badge";
import { useToast } from "@/components/ui/toast";
import { cn } from "@/lib/utils";

type Step = "compose" | "generating";

const stepLabels: Record<Step, string> = {
  compose: "Compose",
  generating: "Generating",
};

/** The single v0.1 preset — no style picker, this is baked into every request. */
const FIXED_STYLE: VideoStyle = "EDUCATIONAL";
const DNA_PRESET = "PROBLEM_PAYOFF";
const MIN_PROMPT = 10;

const CreateVideoPage = () => {
  const navigate = useNavigate();
  const toast = useToast();
  const userId = useAuth().user?.id ?? null;

  const [step, setStep] = useState<Step>("compose");
  const [prompt, setPrompt] = useState("");
  const [media, setMedia] = useState<AssetDTO[]>([]);
  const [tts, setTts] = useState<AssetDTO[]>([]);
  const [music, setMusic] = useState<AssetDTO | null>(null);
  const [generationId, setGenerationId] = useState<UUID | null>(null);

  const { data: allAssets } = useAssets();
  const startMut = useStartGeneration();
  const { progress, done, error, failed } = useGenerationProgress(
    generationId,
    step === "generating",
  );
  const { data: projects } = useProjects();

  /* ---- draft: hydrate once, then autosave ---- */
  const hydratedRef = useRef(false);
  const [hydrated, setHydrated] = useState(false);

  useEffect(() => {
    if (hydratedRef.current) return;
    const draft = loadDraft(userId);
    if (!draft) {
      hydratedRef.current = true;
      setHydrated(true);
      return;
    }
    // Need the server asset list to turn stored ids back into assets.
    if (!allAssets) return;
    setPrompt(draft.prompt);
    setMedia(pickInOrder(allAssets, draft.mediaIds));
    setTts(pickInOrder(allAssets, draft.ttsIds));
    setMusic(
      draft.musicId
        ? (allAssets.find((a) => a.id === draft.musicId) ?? null)
        : null,
    );
    hydratedRef.current = true;
    setHydrated(true);
  }, [userId, allAssets]);

  useEffect(() => {
    if (!hydrated) return;
    saveDraft(userId, {
      prompt,
      mediaIds: media.map((a) => a.id),
      ttsIds: tts.map((a) => a.id),
      musicId: music?.id ?? null,
    });
  }, [hydrated, userId, prompt, media, tts, music]);

  /* ---- redirect when the finished project appears ---- */
  useEffect(() => {
    if (!done || !generationId) return;
    clearDraft(userId);
    const project = (projects ?? []).find(
      (p) => p.generationId === generationId,
    );
    if (project) {
      toast.success("Your video is ready!");
      navigate(`/dashboard/projects/${project.id}`);
      return;
    }
    const fallback = window.setTimeout(() => {
      toast.success("Your video is ready!");
      navigate(`/dashboard/library/preview/${generationId}`);
    }, 4000);
    return () => window.clearTimeout(fallback);
  }, [done, generationId, projects, navigate, toast, userId]);

  useEffect(() => {
    if (error) toast.error("Lost connection to progress stream");
  }, [error, toast]);

  /* ---- actions ---- */
  const promptOk =
    prompt.trim().length >= MIN_PROMPT && prompt.length <= 2000;
  const canGenerate = promptOk && media.length >= 1 && !startMut.isPending;

  const onClear = () => {
    setPrompt("");
    setMedia([]);
    setTts([]);
    setMusic(null);
    clearDraft(userId);
    toast.success("Draft cleared");
  };

  const hasDraft =
    prompt.trim().length > 0 || media.length > 0 || tts.length > 0 || !!music;

  const onGenerate = async () => {
    if (!canGenerate) return;
    try {
      const res = await startMut.mutateAsync({
        prompt,
        style: FIXED_STYLE,
        dnaPreset: DNA_PRESET,
        customMediaAssetIds: media.map((a) => a.id),
        customTtsAssetIds: tts.map((a) => a.id),
        musicAssetId: music?.id ?? null,
        useGptOrdering: false,
        reuseAssets: false,
        forceReuseForTesting: false,
        gifOverlaysEnabled: false,
      });
      setGenerationId(res.generationId);
      setStep("generating");
    } catch (e: any) {
      toast.error(e?.response?.data?.message ?? "Failed to start generation");
    }
  };

  /* ---- UI ---- */
  return (
    <div className="max-w-6xl mx-auto space-y-8">
      {/* HERO */}
      <motion.div
        initial={{ opacity: 0, y: 8 }}
        animate={{ opacity: 1, y: 0 }}
        className="relative overflow-hidden rounded-2xl border border-border bg-card p-8 grid-bg"
      >
        <div className="relative z-10 flex flex-col sm:flex-row sm:items-center sm:justify-between gap-4">
          <div>
            <Badge variant="gradient">
              <Sparkles size={12} /> AI Studio
            </Badge>
            <h1 className="text-3xl sm:text-4xl font-bold tracking-tight mt-3">
              Create your <span className="gradient-text">TikTok video</span>
            </h1>
            <p className="text-sm text-muted-foreground mt-2 max-w-lg">
              Drop in your clips, voice-over and music in the order you want,
              add a prompt, and we choreograph the edit for you.
            </p>
          </div>
          <Stepper current={step} />
        </div>
      </motion.div>

      <AnimatePresence mode="wait">
        {step === "compose" && (
          <motion.div
            key="compose"
            initial={{ opacity: 0, y: 10 }}
            animate={{ opacity: 1, y: 0 }}
            exit={{ opacity: 0, y: -10 }}
            transition={{ duration: 0.25 }}
            className="grid grid-cols-1 xl:grid-cols-[1fr_340px] gap-6 items-start"
          >
            {/* left — inputs */}
            <div className="space-y-6">
              <Section
                title="Describe your video"
                subtitle="Be vivid. The model loves details."
              >
                <div className="rounded-xl border border-border bg-card p-1 focus-within:shadow-glow transition">
                  <textarea
                    value={prompt}
                    onChange={(e) => setPrompt(e.target.value)}
                    placeholder="A focus supplement TikTok ad. Hook with the problem, walk through how it works, end with a strong call to action."
                    className="w-full min-h-[140px] rounded-lg bg-transparent p-4 text-sm resize-none outline-none placeholder:text-muted-foreground"
                  />
                  <div className="flex items-center justify-between px-3 py-2 border-t border-border text-xs text-muted-foreground">
                    <span>{prompt.trim().length} / 2000</span>
                    <span>{MIN_PROMPT} chars minimum</span>
                  </div>
                </div>
              </Section>

              <Section
                title="1 · Images & videos"
                subtitle="Each clip becomes one scene, in this order. Drag to rearrange."
                icon={Film}
              >
                <OrderedAssetZone
                  assets={media}
                  onChange={setMedia}
                  uploadType="IMAGE"
                  accept="image/*,video/*"
                  icon={Film}
                  dropLabel="Drop images or videos, or"
                  dropHint="Up to 12 files — order top to bottom"
                  maxFiles={12}
                />
              </Section>

              <Section
                title="2 · Voice-over"
                subtitle="Your narration clips, played in this order. Leave empty to auto-generate a voice."
                icon={Mic}
              >
                <OrderedAssetZone
                  assets={tts}
                  onChange={setTts}
                  uploadType="VOICE"
                  accept="audio/*"
                  icon={Mic}
                  dropLabel="Drop voice-over audio, or"
                  dropHint="MP3 / WAV — order top to bottom"
                  maxFiles={12}
                />
              </Section>

              <Section
                title="3 · Music"
                subtitle="One background track. Optional."
                icon={Music}
              >
                <OrderedAssetZone
                  assets={music ? [music] : []}
                  onChange={(a) => setMusic(a[0] ?? null)}
                  uploadType="MUSIC"
                  accept="audio/*"
                  icon={Music}
                  dropLabel="Drop a music track, or"
                  dropHint="One MP3 / WAV"
                  single
                />
              </Section>
            </div>

            {/* right — summary + CTA (sticky) */}
            <div className="xl:sticky xl:top-6 space-y-4">
              <div className="rounded-xl border border-border bg-card p-5 space-y-4">
                <div>
                  <p className="text-sm font-semibold flex items-center gap-2">
                    <Wand2 size={14} className="text-primary" />
                    Ready to generate?
                  </p>
                  <p className="text-xs text-muted-foreground mt-1">
                    We'll write the script, cut the scenes to your voice-over and
                    beat-align the music.
                  </p>
                </div>

                <div className="rounded-lg border border-border bg-muted/30 divide-y divide-border text-sm">
                  <SummaryRow label="Scenes" value={String(media.length)} ok={media.length >= 1} />
                  <SummaryRow
                    label="Voice-over clips"
                    value={tts.length ? String(tts.length) : "auto"}
                  />
                  <SummaryRow label="Music" value={music ? "1 track" : "none"} />
                </div>

                <Badge variant="secondary" className="w-full justify-center gap-1.5">
                  <GraduationCap size={12} /> Educational · Problem → Payoff
                </Badge>

                <Button
                  onClick={onGenerate}
                  disabled={!canGenerate}
                  className="w-full gradient-bg hover:opacity-90 text-white shadow-glow"
                >
                  {startMut.isPending ? (
                    <Loader2 className="size-4 animate-spin" />
                  ) : (
                    <ArrowRight className="size-4" />
                  )}
                  Generate video
                </Button>

                {!canGenerate && (
                  <p className="text-[11px] text-muted-foreground text-center">
                    {media.length < 1
                      ? "Add at least one image or video."
                      : !promptOk
                        ? `Prompt needs ${MIN_PROMPT}+ characters.`
                        : ""}
                  </p>
                )}
              </div>

              <Button
                variant="ghost"
                onClick={onClear}
                disabled={!hasDraft}
                className="w-full text-muted-foreground hover:text-destructive"
              >
                <Trash2 size={14} /> Clear everything
              </Button>
              <p className="text-[11px] text-muted-foreground text-center px-2">
                Your inputs are saved automatically on this device.
              </p>
            </div>
          </motion.div>
        )}

        {step === "generating" && (
          <motion.div
            key="generating"
            initial={{ opacity: 0, y: 10 }}
            animate={{ opacity: 1, y: 0 }}
            exit={{ opacity: 0, y: -10 }}
            transition={{ duration: 0.25 }}
          >
            {failed ? (
              <FailedPanel
                message={failed}
                onRetry={() => {
                  setGenerationId(null);
                  setStep("compose");
                }}
              />
            ) : (
              <GeneratingPanel progress={progress} done={done} />
            )}
          </motion.div>
        )}
      </AnimatePresence>
    </div>
  );
};

export default CreateVideoPage;

/* ============ inner components ============ */

const Section = ({
  title,
  subtitle,
  icon: Icon,
  children,
}: {
  title: string;
  subtitle?: React.ReactNode;
  icon?: typeof Film;
  children: React.ReactNode;
}) => (
  <div className="space-y-3">
    <div>
      <h2 className="text-sm font-semibold tracking-tight flex items-center gap-1.5">
        {Icon && <Icon size={14} className="text-primary" />}
        {title}
      </h2>
      {subtitle && (
        <p className="text-xs text-muted-foreground mt-0.5">{subtitle}</p>
      )}
    </div>
    {children}
  </div>
);

const SummaryRow = ({
  label,
  value,
  ok,
}: {
  label: string;
  value: string;
  ok?: boolean;
}) => (
  <div className="flex items-center justify-between px-3 py-2">
    <span className="text-muted-foreground">{label}</span>
    <span
      className={cn(
        "font-medium tabular-nums",
        ok === false && "text-muted-foreground/60",
      )}
    >
      {value}
    </span>
  </div>
);

const Stepper = ({ current }: { current: Step }) => {
  const order: Step[] = ["compose", "generating"];
  const idx = order.indexOf(current);
  return (
    <div className="flex items-center gap-2 text-xs">
      {order.map((s, i) => (
        <div key={s} className="flex items-center gap-2">
          <div
            className={cn(
              "size-6 rounded-full flex items-center justify-center font-medium transition",
              i <= idx
                ? "gradient-bg text-white shadow-glow"
                : "bg-muted text-muted-foreground",
            )}
          >
            {i + 1}
          </div>
          <span
            className={cn(
              "hidden sm:inline",
              i === idx
                ? "text-foreground font-medium"
                : "text-muted-foreground",
            )}
          >
            {stepLabels[s]}
          </span>
          {i < order.length - 1 && (
            <ChevronRight size={12} className="text-muted-foreground" />
          )}
        </div>
      ))}
    </div>
  );
};

/* ============ generation progress ============ */

/**
 * Pipeline steps as broadcast by the backend (GenerationStepName) with the
 * percent each step starts at. Used both for the checklist and to cap the
 * smoothed progress so it never overtakes a step that hasn't happened.
 */
const PIPELINE_STEPS = [
  { key: "INITIALIZING", label: "Preparing generation", percent: 5 },
  { key: "SCRIPT", label: "Writing the script", percent: 15 },
  { key: "IMAGE", label: "Generating scene images", percent: 30 },
  { key: "VOICE", label: "Recording voice-over", percent: 50 },
  { key: "VIDEO", label: "Animating scenes", percent: 70 },
  { key: "MUSIC", label: "Preparing music", percent: 80 },
  { key: "RENDER", label: "Rendering the final cut", percent: 90 },
  { key: "SAVING", label: "Saving results", percent: 97 },
] as const;

const stepIndexOf = (stepKey: string | undefined): number => {
  const idx = PIPELINE_STEPS.findIndex((s) => s.key === stepKey);
  return idx === -1 ? 0 : idx;
};

/**
 * Smooths the coarse SSE percentages: eases toward the latest real value and
 * creeps slowly while a long step is in flight, but never crosses into the
 * next step's range until the backend confirms it.
 */
const useSmoothedPercent = (
  progress: ProgressPayload | null,
  done: boolean,
): number => {
  const [display, setDisplay] = useState(0);
  const ref = useRef({ real: 0, cap: 14, stepStartedAt: 0, display: 0 });

  useEffect(() => {
    const idx = stepIndexOf(progress?.step);
    const nextPercent =
      idx + 1 < PIPELINE_STEPS.length ? PIPELINE_STEPS[idx + 1].percent : 100;
    ref.current.real = done ? 100 : (progress?.percent ?? 0);
    ref.current.cap = done ? 100 : Math.max(0, nextPercent - 1);
    ref.current.stepStartedAt = performance.now();
  }, [progress?.step, progress?.percent, done]);

  useEffect(() => {
    let raf = 0;
    const tick = () => {
      const s = ref.current;
      const elapsedSec = (performance.now() - s.stepStartedAt) / 1000;
      const target = Math.min(
        Math.max(s.real, s.real + elapsedSec * 0.35),
        Math.max(s.real, s.cap),
      );
      s.display = Math.min(100, s.display + (target - s.display) * 0.04);
      setDisplay(s.display);
      raf = requestAnimationFrame(tick);
    };
    raf = requestAnimationFrame(tick);
    return () => cancelAnimationFrame(raf);
  }, []);

  return display;
};

const GeneratingPanel = ({
  progress,
  done,
}: {
  progress: ProgressPayload | null;
  done: boolean;
}) => {
  const percent = useSmoothedPercent(progress, done);
  const [elapsedSec, setElapsedSec] = useState(0);
  useEffect(() => {
    const startedAt = Date.now();
    const timer = window.setInterval(
      () => setElapsedSec(Math.floor((Date.now() - startedAt) / 1000)),
      1000,
    );
    return () => window.clearInterval(timer);
  }, []);
  const elapsed = `${String(Math.floor(elapsedSec / 60)).padStart(2, "0")}:${String(elapsedSec % 60).padStart(2, "0")}`;

  const currentIdx = stepIndexOf(progress?.step);

  return (
    <div className="rounded-2xl border border-border bg-card p-8 sm:p-10 max-w-2xl mx-auto">
      <div className="flex items-center gap-4">
        <div className="size-14 rounded-2xl gradient-bg flex items-center justify-center shadow-glow animate-float shrink-0">
          <Wand2 className="size-6 text-white" />
        </div>
        <div className="min-w-0">
          <h2 className="text-xl sm:text-2xl font-semibold">
            Crafting your video
          </h2>
          <p className="text-sm text-muted-foreground mt-0.5 truncate">
            {progress?.message ?? "Connecting to the pipeline…"}
          </p>
        </div>
        <div className="ml-auto text-right shrink-0">
          <p className="text-2xl font-bold tabular-nums gradient-text">
            {Math.round(percent)}%
          </p>
          <p className="text-[11px] text-muted-foreground tabular-nums">
            {elapsed}
          </p>
        </div>
      </div>

      <div className="mt-6">
        <Progress value={percent} indeterminate={!progress && !done} />
      </div>

      <ul className="mt-8 grid grid-cols-1 sm:grid-cols-2 gap-x-6 gap-y-2.5">
        {PIPELINE_STEPS.map((s, i) => {
          const isDone = done || i < currentIdx;
          const isActive = !done && i === currentIdx;
          return (
            <li key={s.key} className="flex items-center gap-2.5 text-sm">
              <span
                className={cn(
                  "size-5 rounded-full flex items-center justify-center shrink-0 transition",
                  isDone
                    ? "gradient-bg text-white"
                    : isActive
                      ? "border-2 border-primary"
                      : "border-2 border-border",
                )}
              >
                {isDone ? (
                  <Check size={11} strokeWidth={3} />
                ) : isActive ? (
                  <Loader2 size={11} className="animate-spin text-primary" />
                ) : null}
              </span>
              <span
                className={cn(
                  isDone
                    ? "text-foreground"
                    : isActive
                      ? "text-foreground font-medium"
                      : "text-muted-foreground",
                )}
              >
                {s.label}
              </span>
            </li>
          );
        })}
      </ul>

      <p className="text-xs text-muted-foreground mt-8 text-center">
        You can leave this page — we'll drop you in the editor when it's ready.
      </p>
    </div>
  );
};

const FailedPanel = ({
  message,
  onRetry,
}: {
  message: string;
  onRetry: () => void;
}) => (
  <div className="rounded-2xl border border-destructive/40 bg-card p-10 text-center max-w-2xl mx-auto">
    <div className="size-14 rounded-2xl bg-destructive/10 text-destructive mx-auto flex items-center justify-center">
      <AlertTriangle className="size-6" />
    </div>
    <h2 className="text-xl font-semibold mt-5">Generation failed</h2>
    <p className="text-sm text-muted-foreground mt-2 break-words">{message}</p>
    <p className="text-xs text-muted-foreground mt-1">
      Your credits for this run have been refunded.
    </p>
    <Button onClick={onRetry} className="mt-6 gradient-bg text-white shadow-glow">
      <RotateCcw size={14} /> Back & try again
    </Button>
  </div>
);
