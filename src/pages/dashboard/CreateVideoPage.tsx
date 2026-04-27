import { useEffect, useMemo, useState } from "react";
import { useNavigate } from "react-router-dom";
import { motion, AnimatePresence } from "framer-motion";
import {
  Sparkles,
  ArrowRight,
  ChevronRight,
  Loader2,
  Wand2,
  Lock,
} from "lucide-react";
import { AssetUploader } from "@/features/video/components/AssetUploader";
import { StylePicker } from "@/features/video/components/StylePicker";
import { SceneAssignmentBoard } from "@/features/video/components/SceneAssignmentBoard";
import {
  useActivePlan,
  useAnalyzePrompt,
  useGenerationProgress,
  useProjects,
  useStartGeneration,
} from "@/features/video/hooks";
import type {
  AssetDTO,
  PromptAnalysisResponse,
  UUID,
  VideoStyle,
} from "@/features/video/types";
import { Button } from "@/components/ui/button";
import { Progress } from "@/components/ui/progress";
import { Badge } from "@/components/ui/badge";
import { useToast } from "@/components/ui/toast";
import { cn } from "@/lib/utils";

type Step = "compose" | "review" | "generating";

const stepLabels: Record<Step, string> = {
  compose: "Compose",
  review: "Review scenes",
  generating: "Generating",
};

const PRO_PLANS = new Set(["PRO", "PREMIUM", "ULTIMATE"]);

const CreateVideoPage = () => {
  const navigate = useNavigate();
  const toast = useToast();

  const { data: plan } = useActivePlan();
  const isPro = useMemo(
    () => (plan?.type ? PRO_PLANS.has(plan.type) : false),
    [plan],
  );

  const [step, setStep] = useState<Step>("compose");
  const [prompt, setPrompt] = useState("");
  const [style, setStyle] = useState<VideoStyle | null>("CINEMATIC");
  const [assets, setAssets] = useState<AssetDTO[]>([]);
  const [analysis, setAnalysis] = useState<PromptAnalysisResponse | null>(null);
  const [assignments, setAssignments] = useState<Map<number, UUID>>(new Map());
  const [generationId, setGenerationId] = useState<UUID | null>(null);

  const analyzeMut = useAnalyzePrompt();
  const startMut = useStartGeneration();
  const { progress, done, error } = useGenerationProgress(
    generationId,
    step === "generating",
  );
  const { data: projects } = useProjects();

  /* hydrate suggested assignments after analyze */
  useEffect(() => {
    if (!analysis) return;
    const next = new Map<number, UUID>();
    analysis.scenes.forEach((s) => {
      if (s.suggestedAssetId) next.set(s.index, s.suggestedAssetId);
    });
    setAssignments(next);
  }, [analysis]);

  /* navigate to project page when generation done */
  useEffect(() => {
    if (!done || !generationId || !projects) return;
    const project = projects.find((p) => p.generationId === generationId);
    if (project) {
      toast.success("Your video is ready!");
      navigate(`/dashboard/projects/${project.id}`);
    }
  }, [done, generationId, projects, navigate, toast]);

  useEffect(() => {
    if (error) toast.error("Lost connection to progress stream");
  }, [error, toast]);

  /* ---- actions ---- */

  const canAnalyze =
    prompt.trim().length >= 10 && prompt.length <= 2000 && !analyzeMut.isPending;

  const onAnalyze = async () => {
    if (!canAnalyze) return;
    try {
      const result = await analyzeMut.mutateAsync({
        prompt,
        style,
        customMediaAssetIds: assets.map((a) => a.id),
        analyzeAssets: false,
      });
      setAnalysis(result);
      setStep("review");
    } catch (e: any) {
      toast.error(e?.response?.data?.message ?? "Failed to analyze prompt");
    }
  };

  const onGenerate = async () => {
    if (!analysis) return;
    try {
      const sceneAssignments = Array.from(assignments.entries()).map(
        ([sceneIndex, assetId]) => ({ sceneIndex, assetId }),
      );
      const res = await startMut.mutateAsync({
        prompt,
        style,
        customMediaAssetIds: assets.map((a) => a.id),
        sceneAssignments,
        useGptOrdering: false,
        reuseAssets: true,
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
              Describe what you want, drop in your media, and let the AI
              choreograph cuts, voiceover and motion in seconds.
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
            className="grid grid-cols-1 xl:grid-cols-[1fr_360px] gap-6"
          >
            <div className="space-y-6">
              <Section title="Describe your video" subtitle="Be vivid. The model loves details.">
                <div className="rounded-xl border border-border bg-card p-1 focus-within:shadow-glow transition">
                  <textarea
                    value={prompt}
                    onChange={(e) => setPrompt(e.target.value)}
                    placeholder="A 30-second product launch story for a sleek wireless lamp. Hook with a glowing close-up, walk through 3 features, end with a CTA to visit the site."
                    className="w-full min-h-[160px] rounded-lg bg-transparent p-4 text-sm resize-none outline-none placeholder:text-muted-foreground"
                  />
                  <div className="flex items-center justify-between px-3 py-2 border-t border-border text-xs text-muted-foreground">
                    <span>{prompt.trim().length} / 2000</span>
                    <span>10 chars minimum</span>
                  </div>
                </div>
              </Section>

              <Section title="Pick a style">
                <StylePicker value={style} onChange={setStyle} />
              </Section>
            </div>

            <div className="space-y-6">
              <Section
                title="Your media"
                subtitle={
                  isPro ? undefined : (
                    <span className="inline-flex items-center gap-1.5 text-amber-500">
                      <Lock size={12} /> PRO+ to upload custom media
                    </span>
                  )
                }
              >
                <div
                  className={cn(
                    !isPro && "opacity-60 pointer-events-none select-none",
                  )}
                >
                  <AssetUploader assets={assets} onChange={setAssets} />
                </div>
              </Section>

              <div className="rounded-xl border border-border bg-card p-5 space-y-4">
                <div>
                  <p className="text-sm font-semibold flex items-center gap-2">
                    <Wand2 size={14} className="text-primary" />
                    Ready to draft?
                  </p>
                  <p className="text-xs text-muted-foreground mt-1">
                    We'll outline scenes, voiceover and pacing before any heavy
                    lifting. Generation is free at this step.
                  </p>
                </div>
                <Button
                  onClick={onAnalyze}
                  disabled={!canAnalyze}
                  className="w-full gradient-bg hover:opacity-90 text-white shadow-glow"
                >
                  {analyzeMut.isPending ? (
                    <Loader2 className="size-4 animate-spin" />
                  ) : (
                    <Sparkles className="size-4" />
                  )}
                  Analyze prompt
                </Button>
              </div>
            </div>
          </motion.div>
        )}

        {step === "review" && analysis && (
          <motion.div
            key="review"
            initial={{ opacity: 0, y: 10 }}
            animate={{ opacity: 1, y: 0 }}
            exit={{ opacity: 0, y: -10 }}
            transition={{ duration: 0.25 }}
            className="space-y-6"
          >
            <ReviewSummary analysis={analysis} />
            <Section title="Scene blueprint" subtitle="Reassign assets via drag & drop. Empty scenes will be AI-generated.">
              <SceneAssignmentBoard
                analysis={analysis}
                assets={assets}
                assignments={assignments}
                onChange={setAssignments}
              />
            </Section>

            <div className="flex items-center justify-between gap-3 sticky bottom-4 z-10 glass rounded-xl border border-border px-4 py-3 shadow-elev">
              <div className="text-xs text-muted-foreground">
                {assignments.size}/{analysis.scenes.length} scenes assigned
              </div>
              <div className="flex items-center gap-2">
                <Button
                  variant="ghost"
                  onClick={() => setStep("compose")}
                  disabled={startMut.isPending}
                >
                  Back
                </Button>
                <Button
                  onClick={onGenerate}
                  disabled={startMut.isPending}
                  className="gradient-bg text-white shadow-glow"
                >
                  {startMut.isPending ? (
                    <Loader2 className="size-4 animate-spin" />
                  ) : (
                    <ArrowRight className="size-4" />
                  )}
                  Generate video
                </Button>
              </div>
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
            <div className="rounded-2xl border border-border bg-card p-10 text-center max-w-2xl mx-auto">
              <div className="size-16 rounded-2xl gradient-bg mx-auto flex items-center justify-center shadow-glow animate-float">
                <Wand2 className="size-7 text-white" />
              </div>
              <h2 className="text-2xl font-semibold mt-6">
                Crafting your video
              </h2>
              <p className="text-sm text-muted-foreground mt-2">
                {progress?.message ?? "Spinning up the pipeline…"}
              </p>
              <div className="mt-8 space-y-2">
                <Progress value={progress?.percent ?? 0} indeterminate={!progress} />
                <div className="flex items-center justify-between text-xs text-muted-foreground">
                  <span>{progress?.step ?? "INITIALIZING"}</span>
                  <span className="tabular-nums">
                    {progress ? `${Math.round(progress.percent)}%` : "—"}
                  </span>
                </div>
              </div>
              <p className="text-xs text-muted-foreground mt-6">
                You can leave this page — we'll drop you in the editor when it's ready.
              </p>
            </div>
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
  children,
}: {
  title: string;
  subtitle?: React.ReactNode;
  children: React.ReactNode;
}) => (
  <div className="space-y-3">
    <div>
      <h2 className="text-sm font-semibold tracking-tight">{title}</h2>
      {subtitle && (
        <p className="text-xs text-muted-foreground mt-0.5">{subtitle}</p>
      )}
    </div>
    {children}
  </div>
);

const Stepper = ({ current }: { current: Step }) => {
  const order: Step[] = ["compose", "review", "generating"];
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
              i === idx ? "text-foreground font-medium" : "text-muted-foreground",
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

const ReviewSummary = ({ analysis }: { analysis: PromptAnalysisResponse }) => (
  <div className="grid grid-cols-2 lg:grid-cols-4 gap-3">
    <Stat label="Scenes" value={String(analysis.scenes.length)} />
    <Stat
      label="Total length"
      value={`${(analysis.totalDurationMs / 1000).toFixed(1)}s`}
    />
    <Stat label="Type" value={analysis.contentType ?? "—"} />
    <Stat
      label="Pacing"
      value={analysis.userIntent?.pacingPreference ?? "auto"}
    />
  </div>
);

const Stat = ({ label, value }: { label: string; value: string }) => (
  <div className="rounded-xl border border-border bg-card px-4 py-3">
    <p className="text-[11px] text-muted-foreground uppercase tracking-wider">
      {label}
    </p>
    <p className="text-base font-semibold mt-1 truncate">{value}</p>
  </div>
);
