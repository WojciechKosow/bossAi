import { useEffect, useMemo } from "react";
import { useNavigate, useParams } from "react-router-dom";
import { useQuery } from "@tanstack/react-query";
import { ArrowLeft, Download, Loader2, Sparkles } from "lucide-react";
import { motion } from "framer-motion";
import { getGeneration } from "@/features/video/api";
import { useProjects } from "@/features/video/hooks";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";

const GenerationPreviewPage = () => {
  const { id } = useParams<{ id: string }>();
  const navigate = useNavigate();

  const { data: generation, isLoading } = useQuery({
    queryKey: ["generation", id],
    queryFn: () => getGeneration(id as string),
    enabled: !!id,
    refetchInterval: (q) => {
      const data = q.state.data;
      if (!data) return 2000;
      return data.status === "PENDING" || data.status === "PROCESSING"
        ? 2000
        : false;
    },
  });

  const { data: projects } = useProjects();
  const linkedProject = useMemo(
    () => (projects ?? []).find((p) => p.generationId === id),
    [projects, id],
  );

  useEffect(() => {
    if (linkedProject) {
      navigate(`/dashboard/projects/${linkedProject.id}`, { replace: true });
    }
  }, [linkedProject, navigate]);

  if (isLoading || !generation) {
    return (
      <div className="flex items-center justify-center py-32">
        <Loader2 className="size-5 animate-spin text-muted-foreground" />
      </div>
    );
  }

  const isProcessing =
    generation.status === "PENDING" || generation.status === "PROCESSING";

  return (
    <div className="max-w-5xl mx-auto space-y-6">
      <div className="flex items-center justify-between gap-3">
        <button
          onClick={() => navigate("/dashboard/library")}
          className="inline-flex items-center gap-2 text-sm text-muted-foreground hover:text-foreground transition"
        >
          <ArrowLeft size={14} /> Library
        </button>
        <Badge
          variant={
            isProcessing
              ? "warning"
              : generation.status === "DONE"
                ? "success"
                : "destructive"
          }
        >
          {isProcessing && <Loader2 size={10} className="animate-spin" />}
          {generation.status}
        </Badge>
      </div>

      <motion.div
        layout
        initial={{ opacity: 0, y: 8 }}
        animate={{ opacity: 1, y: 0 }}
        className="grid grid-cols-1 lg:grid-cols-[420px_1fr] gap-6"
      >
        <div className="rounded-xl border border-border bg-black overflow-hidden">
          <div className="aspect-[9/16] relative flex items-center justify-center bg-black">
            {generation.videoUrl ? (
              <video
                src={generation.videoUrl}
                className="size-full object-contain"
                playsInline
                controls
                autoPlay
                muted
              />
            ) : (
              <div className="text-center text-muted-foreground p-6">
                <Loader2 className="mx-auto size-5 animate-spin mb-2" />
                <p className="text-xs">{generation.status} · waiting for render…</p>
              </div>
            )}
          </div>
          {generation.videoUrl && (
            <div className="px-3 py-2 border-t border-border flex items-center justify-between bg-card">
              <span className="text-xs text-muted-foreground">
                Preview
              </span>
              <a
                href={generation.videoUrl}
                download
                className="text-xs text-muted-foreground hover:text-foreground inline-flex items-center gap-1.5 transition"
              >
                <Download size={12} /> mp4
              </a>
            </div>
          )}
        </div>

        <div className="space-y-4">
          <div className="rounded-xl border border-border bg-card p-5">
            <h1 className="text-xl font-semibold tracking-tight">
              Generation result
            </h1>
            <p className="text-sm text-muted-foreground mt-2">
              This generation isn't backed by an editable timeline project.
              You can preview, download, or kick off a new edit.
            </p>
            <div className="mt-4 grid grid-cols-2 gap-3 text-xs">
              <Field label="Generation ID" value={generation.id.slice(0, 8) + "…"} />
              <Field
                label="Created"
                value={new Date(generation.createdAt).toLocaleString()}
              />
              {generation.finishedAt && (
                <Field
                  label="Finished"
                  value={new Date(generation.finishedAt).toLocaleString()}
                />
              )}
              <Field label="Type" value={String(generation.type ?? "—")} />
            </div>
          </div>

          <div className="rounded-xl border border-border bg-card p-5">
            <p className="text-sm font-semibold flex items-center gap-2">
              <Sparkles size={14} className="text-primary" />
              Want to edit?
            </p>
            <p className="text-xs text-muted-foreground mt-1.5">
              Editable timelines are produced when the new pipeline is
              enabled on the backend (
              <code className="font-mono text-[10px]">
                rendering.use-new-pipeline=true
              </code>
              ). Newer generations from that pipeline will open in the
              full editor automatically.
            </p>
            <div className="mt-4 flex items-center gap-2">
              <Button
                onClick={() => navigate("/dashboard/create")}
                className="gradient-bg text-white shadow-glow"
              >
                <Sparkles size={14} /> Create new video
              </Button>
            </div>
          </div>
        </div>
      </motion.div>
    </div>
  );
};

export default GenerationPreviewPage;

const Field = ({ label, value }: { label: string; value: string }) => (
  <div className="rounded-lg border border-border bg-muted/30 px-3 py-2">
    <p className="text-[10px] uppercase tracking-wider text-muted-foreground">
      {label}
    </p>
    <p className="text-sm font-medium mt-0.5 truncate">{value}</p>
  </div>
);
