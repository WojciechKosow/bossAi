import { useMemo } from "react";
import { Link } from "react-router-dom";
import { motion } from "framer-motion";
import { formatDistanceToNow } from "date-fns";
import {
  Film,
  Plus,
  Loader2,
  PlayCircle,
  Pencil,
  Sparkles,
  Eye,
  Download,
} from "lucide-react";
import {
  useAssets,
  useProjects,
  useRecentGenerations,
} from "@/features/video/hooks";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { AssetMedia } from "@/features/video/components/AssetMedia";
import { assetFileUrl } from "@/features/video/api";
import type {
  AssetDTO,
  GenerationDTO,
  ProjectStatus,
  UUID,
  VideoProjectDTO,
} from "@/features/video/types";

/**
 * The library shows two kinds of "video items":
 *   1. Editable timeline projects (VideoProject) — produced by the
 *      timeline-first pipeline (rendering.use-new-pipeline=true).
 *   2. Plain rendered videos — every TikTok-ad generation always saves a
 *      VIDEO + AI_GENERATED asset, so we surface those as preview-only
 *      cards. This way the library is never empty just because the new
 *      pipeline flag is off.
 *
 * Project → asset correlation is best-effort: when the backend exposes
 * generationId on AssetDTO and Generation.videoUrl pointing at the asset
 * UUID, we'll prefer the project card and skip the duplicate asset card.
 */
type LibraryItem = {
  key: string;
  kind: "project" | "asset";
  generationId?: UUID;
  project?: VideoProjectDTO;
  asset?: AssetDTO;
  status: ProjectStatus | "READY";
  title?: string;
  prompt?: string;
  createdAt: string;
};

const buildItems = (
  assets: AssetDTO[] | undefined,
  projects: VideoProjectDTO[] | undefined,
  generations: GenerationDTO[] | undefined,
): LibraryItem[] => {
  const items: LibraryItem[] = [];
  const claimedAssetIds = new Set<UUID>();
  const generationById = new Map<UUID, GenerationDTO>();
  (generations ?? []).forEach((g) => generationById.set(g.id, g));

  // 1. Projects — editable timeline items.
  for (const p of projects ?? []) {
    const gen = p.generationId ? generationById.get(p.generationId) : undefined;
    items.push({
      key: `project:${p.id}`,
      kind: "project",
      generationId: p.generationId,
      project: p,
      status: p.status,
      title: p.title || gen?.id?.slice(0, 8),
      prompt: p.originalPrompt,
      createdAt: p.createdAt ?? gen?.createdAt ?? new Date().toISOString(),
    });

    // If we ever get generationId on AssetDTO, skip duplicate asset cards
    // for the same logical video.
    (assets ?? [])
      .filter(
        (a) =>
          a.generationId &&
          p.generationId &&
          a.generationId === p.generationId,
      )
      .forEach((a) => claimedAssetIds.add(a.id));
  }

  // 2. Rendered videos — every successful generation produces one
  //    AI_GENERATED VIDEO asset. Show it as a view-only card unless a
  //    project already covers it.
  for (const a of assets ?? []) {
    if (a.type !== "VIDEO") continue;
    if (a.source && a.source !== "AI_GENERATED") continue;
    if (claimedAssetIds.has(a.id)) continue;

    const gen = a.generationId ? generationById.get(a.generationId) : undefined;
    items.push({
      key: `asset:${a.id}`,
      kind: "asset",
      generationId: a.generationId,
      asset: a,
      status: "READY",
      title: gen?.id?.slice(0, 8),
      prompt: undefined,
      createdAt: a.createdAt ?? new Date().toISOString(),
    });
  }

  return items.sort(
    (a, b) =>
      new Date(b.createdAt).getTime() - new Date(a.createdAt).getTime(),
  );
};

const statusVariant = (
  status: LibraryItem["status"],
): { label: string; variant: any } => {
  switch (status) {
    case "DRAFT":
      return { label: "Draft", variant: "outline" };
    case "GENERATING":
      return { label: "Generating", variant: "warning" };
    case "RENDERING":
      return { label: "Rendering", variant: "warning" };
    case "READY":
      return { label: "Ready", variant: "success" };
    case "COMPLETE":
      return { label: "Complete", variant: "success" };
    case "FAILED":
      return { label: "Failed", variant: "destructive" };
    default:
      return { label: String(status), variant: "outline" };
  }
};

const LibraryPage = () => {
  const { data: assets, isLoading: assetsLoading } = useAssets();
  const { data: projects, isLoading: projectsLoading } = useProjects();
  const { data: generations, isLoading: gensLoading } = useRecentGenerations();

  const items = useMemo(
    () => buildItems(assets, projects, generations),
    [assets, projects, generations],
  );

  const isLoading = assetsLoading || projectsLoading || gensLoading;

  return (
    <div className="max-w-7xl mx-auto space-y-8">
      <header className="flex flex-col sm:flex-row sm:items-end sm:justify-between gap-4">
        <div>
          <h1 className="text-3xl font-bold tracking-tight">Library</h1>
          <p className="text-muted-foreground mt-2">
            Every video you've created, edited, or rendered.
          </p>
        </div>
        <Link to="/dashboard/create">
          <Button className="gradient-bg text-white shadow-glow">
            <Plus size={16} /> New video
          </Button>
        </Link>
      </header>

      {isLoading && (
        <div className="flex items-center justify-center py-24">
          <Loader2 className="size-5 animate-spin text-muted-foreground" />
        </div>
      )}

      {!isLoading && items.length === 0 && <EmptyState />}

      {items.length > 0 && (
        <motion.div
          layout
          className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-3 xl:grid-cols-4 gap-5"
        >
          {items.map((item, idx) => (
            <ItemCard key={item.key} item={item} index={idx} />
          ))}
        </motion.div>
      )}
    </div>
  );
};

export default LibraryPage;

const ItemCard = ({ item, index }: { item: LibraryItem; index: number }) => {
  const sb = statusVariant(item.status);
  const isProcessing =
    item.status === "GENERATING" || item.status === "RENDERING";
  const editable = item.kind === "project";
  const target =
    item.kind === "project"
      ? `/dashboard/projects/${item.project!.id}`
      : item.generationId
        ? `/dashboard/library/preview/${item.generationId}`
        : `/dashboard/library`;
  const downloadHref =
    item.kind === "asset" && item.asset
      ? assetFileUrl(item.asset.id)
      : undefined;

  return (
    <motion.div
      initial={{ opacity: 0, y: 12 }}
      animate={{ opacity: 1, y: 0 }}
      transition={{ duration: 0.25, delay: index * 0.04 }}
      className="group relative overflow-hidden rounded-xl border border-border bg-card hover:border-primary/40 transition-all hover:shadow-elev"
    >
      <Link to={target} className="block">
        <div className="relative aspect-[9/16] bg-muted overflow-hidden">
          {item.kind === "asset" && item.asset ? (
            <AssetMedia assetId={item.asset.id} type="VIDEO" />
          ) : isProcessing ? (
            <div className="size-full flex items-center justify-center bg-gradient-to-br from-primary/20 to-accent/40">
              <Loader2 className="size-6 text-primary animate-spin" />
            </div>
          ) : (
            <div className="size-full flex items-center justify-center bg-muted">
              <Film className="size-7 text-muted-foreground" />
            </div>
          )}

          <div className="absolute inset-0 bg-gradient-to-t from-black/80 via-black/0 to-black/0 opacity-90 pointer-events-none" />

          <div className="absolute top-3 left-3 right-3 flex items-center justify-between">
            <Badge variant={sb.variant}>
              {isProcessing && <Loader2 size={10} className="animate-spin" />}
              {sb.label}
            </Badge>
            {item.project?.style && (
              <span className="text-[10px] uppercase tracking-wider text-white/80 bg-black/40 px-2 py-0.5 rounded-full backdrop-blur">
                {item.project.style.replace(/_/g, " ")}
              </span>
            )}
          </div>

          <div className="absolute inset-0 flex items-center justify-center opacity-0 group-hover:opacity-100 transition pointer-events-none">
            <div className="size-12 rounded-full gradient-bg flex items-center justify-center shadow-glow">
              <PlayCircle className="size-6 text-white" />
            </div>
          </div>

          <div className="absolute bottom-3 left-3 right-3 text-white pointer-events-none">
            <p className="font-semibold text-sm leading-tight line-clamp-2">
              {item.title || item.prompt || "Untitled"}
            </p>
          </div>
        </div>
      </Link>
      <div className="px-4 py-3 flex items-center justify-between text-xs">
        <span className="text-muted-foreground">
          {formatDistanceToNow(new Date(item.createdAt), { addSuffix: true })}
        </span>
        <div className="flex items-center gap-2">
          {downloadHref && (
            <a
              href={downloadHref}
              download
              onClick={(e) => e.stopPropagation()}
              className="text-muted-foreground hover:text-foreground transition inline-flex items-center"
              title="Download"
            >
              <Download size={12} />
            </a>
          )}
          <Link
            to={target}
            className="text-muted-foreground hover:text-primary transition inline-flex items-center gap-1"
          >
            {editable ? (
              <>
                <Pencil size={11} /> Edit
              </>
            ) : (
              <>
                <Eye size={11} /> View
              </>
            )}
          </Link>
        </div>
      </div>
    </motion.div>
  );
};

const EmptyState = () => (
  <div className="rounded-2xl border border-dashed border-border bg-card p-16 text-center">
    <div className="size-14 rounded-2xl gradient-bg mx-auto flex items-center justify-center shadow-glow">
      <Film className="size-6 text-white" />
    </div>
    <h2 className="text-lg font-semibold mt-5">No videos yet</h2>
    <p className="text-sm text-muted-foreground mt-1.5 max-w-sm mx-auto">
      Spin up your first AI-edited TikTok in under a minute.
    </p>
    <Link to="/dashboard/create" className="inline-block mt-6">
      <Button className="gradient-bg text-white shadow-glow">
        <Sparkles size={16} /> Create your first video
      </Button>
    </Link>
  </div>
);
