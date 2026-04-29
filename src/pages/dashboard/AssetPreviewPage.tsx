import { useMemo } from "react";
import { useNavigate, useParams } from "react-router-dom";
import { motion } from "framer-motion";
import { ArrowLeft, Download, Loader2, Pencil, Sparkles } from "lucide-react";
import { useAssets, useProjects } from "@/features/video/hooks";
import { AssetMedia } from "@/features/video/components/AssetMedia";
import { assetFileUrl } from "@/features/video/api";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { computeItemTitle } from "@/features/video/components/libraryUtils";
import { format } from "date-fns";

const AssetPreviewPage = () => {
  const { assetId } = useParams<{ assetId: string }>();
  const navigate = useNavigate();

  const { data: assets, isLoading: assetsLoading } = useAssets();
  const { data: projects } = useProjects();

  const asset = useMemo(
    () => assets?.find((a) => a.id === assetId),
    [assets, assetId],
  );

  const linkedProject = useMemo(() => {
    if (!asset?.generationId) return undefined;
    return (projects ?? []).find((p) => p.generationId === asset.generationId);
  }, [asset, projects]);

  const title = useMemo(
    () =>
      computeItemTitle({
        project: linkedProject,
        asset,
        createdAt: asset?.createdAt,
      }),
    [linkedProject, asset],
  );

  if (assetsLoading) {
    return (
      <div className="flex items-center justify-center py-32">
        <Loader2 className="size-5 animate-spin text-muted-foreground" />
      </div>
    );
  }

  if (!asset) {
    return (
      <div className="max-w-xl mx-auto rounded-2xl border border-border bg-card p-10 text-center mt-10">
        <p className="text-sm font-semibold">Asset not found</p>
        <p className="text-xs text-muted-foreground mt-2">
          It may have been deleted or doesn't belong to your account.
        </p>
        <Button
          onClick={() => navigate("/dashboard/library")}
          className="mt-6 gradient-bg text-white shadow-glow"
        >
          <ArrowLeft size={14} /> Back to library
        </Button>
      </div>
    );
  }

  return (
    <div className="max-w-5xl mx-auto space-y-6">
      <div className="flex items-center justify-between gap-3">
        <button
          onClick={() => navigate("/dashboard/library")}
          className="inline-flex items-center gap-2 text-sm text-muted-foreground hover:text-foreground transition"
        >
          <ArrowLeft size={14} /> Library
        </button>
        <Badge variant="success">Ready</Badge>
      </div>

      <motion.div
        layout
        initial={{ opacity: 0, y: 8 }}
        animate={{ opacity: 1, y: 0 }}
        className="grid grid-cols-1 lg:grid-cols-[420px_1fr] gap-6"
      >
        <div className="rounded-xl border border-border bg-black overflow-hidden">
          <div className="aspect-[9/16] relative flex items-center justify-center bg-black">
            <AssetMedia
              assetId={asset.id}
              type={asset.type === "VIDEO" ? "VIDEO" : "IMAGE"}
              preview={false}
              className="size-full object-contain"
            />
          </div>
          <div className="px-3 py-2 border-t border-border flex items-center justify-between bg-card">
            <span className="text-xs text-muted-foreground">Preview</span>
            <a
              href={assetFileUrl(asset.id)}
              download
              className="text-xs text-muted-foreground hover:text-foreground inline-flex items-center gap-1.5 transition"
            >
              <Download size={12} /> mp4
            </a>
          </div>
        </div>

        <div className="space-y-4">
          <div className="rounded-xl border border-border bg-card p-5">
            <h1 className="text-xl font-semibold tracking-tight line-clamp-2">
              {title}
            </h1>
            <p className="text-xs text-muted-foreground mt-2">
              {asset.createdAt
                ? format(new Date(asset.createdAt), "MMM d, yyyy · h:mm a")
                : "—"}
            </p>
            <div className="mt-4 grid grid-cols-2 gap-3 text-xs">
              <Field label="Type" value={String(asset.type)} />
              <Field
                label="Source"
                value={String(asset.source ?? "AI_GENERATED")}
              />
              {asset.durationSeconds && (
                <Field
                  label="Duration"
                  value={`${asset.durationSeconds.toFixed(1)}s`}
                />
              )}
              {asset.width && asset.height && (
                <Field
                  label="Resolution"
                  value={`${asset.width}×${asset.height}`}
                />
              )}
            </div>
          </div>

          {linkedProject ? (
            <div className="rounded-xl border border-border bg-card p-5">
              <p className="text-sm font-semibold flex items-center gap-2">
                <Pencil size={14} className="text-primary" />
                Edit on the timeline
              </p>
              <p className="text-xs text-muted-foreground mt-1.5">
                This video is backed by an editable timeline project. Open the
                editor to rearrange clips, swap assets, or change effects.
              </p>
              <Button
                onClick={() =>
                  navigate(`/dashboard/projects/${linkedProject.id}`)
                }
                className="mt-4 gradient-bg text-white shadow-glow"
              >
                <Pencil size={14} /> Open editor
              </Button>
            </div>
          ) : (
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
                ). New generations from that pipeline will open in the full
                editor automatically.
              </p>
              <Button
                onClick={() => navigate("/dashboard/create")}
                className="mt-4 gradient-bg text-white shadow-glow"
              >
                <Sparkles size={14} /> Create new video
              </Button>
            </div>
          )}
        </div>
      </motion.div>
    </div>
  );
};

export default AssetPreviewPage;

const Field = ({ label, value }: { label: string; value: string }) => (
  <div className="rounded-lg border border-border bg-muted/30 px-3 py-2">
    <p className="text-[10px] uppercase tracking-wider text-muted-foreground">
      {label}
    </p>
    <p className="text-sm font-medium mt-0.5 truncate">{value}</p>
  </div>
);
