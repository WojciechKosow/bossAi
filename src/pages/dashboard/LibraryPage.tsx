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
  useProjects,
  useRecentGenerations,
} from "@/features/video/hooks";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import type {
  GenerationDTO,
  GenerationStatus,
  ProjectStatus,
  UUID,
  VideoProjectDTO,
} from "@/features/video/types";

/**
 * A unified "library item" abstraction. Each TikTok-ad generation always
 * produces a Generation row; the new timeline-first pipeline additionally
 * produces a VideoProject (with editable EDL). The library shows both —
 * a project-backed item gets an "Edit" button, a generation-only item is
 * preview/download only.
 */
type LibraryItem = {
  id: string;
  generationId: UUID;
  project?: VideoProjectDTO;
  status: GenerationStatus | ProjectStatus;
  videoUrl?: string;
  prompt?: string;
  title?: string;
  createdAt: string;
};

const itemStatusBadge = (
  status: LibraryItem["status"],
): { label: string; variant: any } => {
  switch (status) {
    case "PENDING":
    case "DRAFT":
    case "GENERATING":
    case "PROCESSING":
      return { label: "Generating", variant: "warning" };
    case "RENDERING":
      return { label: "Rendering", variant: "warning" };
    case "READY":
    case "COMPLETE":
    case "DONE":
      return { label: "Ready", variant: "success" };
    case "FAILED":
      return { label: "Failed", variant: "destructive" };
    default:
      return { label: String(status), variant: "outline" };
  }
};

const buildItems = (
  generations: GenerationDTO[] | undefined,
  projects: VideoProjectDTO[] | undefined,
): LibraryItem[] => {
  if (!generations && !projects) return [];
  const projectsByGen = new Map<UUID, VideoProjectDTO>();
  (projects ?? []).forEach((p) => {
    if (p.generationId) projectsByGen.set(p.generationId, p);
  });

  const seen = new Set<UUID>();
  const items: LibraryItem[] = [];

  // 1. Generations are the primary source of truth — every video the user
  //    started has a Generation row, not necessarily a VideoProject.
  for (const g of generations ?? []) {
    const project = projectsByGen.get(g.id);
    items.push({
      id: g.id,
      generationId: g.id,
      project,
      status: project?.status ?? g.status,
      videoUrl: g.videoUrl,
      prompt: project?.originalPrompt,
      title: project?.title,
      createdAt: g.createdAt,
    });
    seen.add(g.id);
  }

  // 2. Surface any project that doesn't yet have a corresponding generation
  //    row (edge case — e.g. test fixtures, or pipeline race).
  for (const p of projects ?? []) {
    if (!p.generationId || seen.has(p.generationId)) continue;
    items.push({
      id: p.id,
      generationId: p.generationId,
      project: p,
      status: p.status,
      prompt: p.originalPrompt,
      title: p.title,
      createdAt: p.createdAt,
    });
  }

  return items.sort(
    (a, b) =>
      new Date(b.createdAt).getTime() - new Date(a.createdAt).getTime(),
  );
};

const LibraryPage = () => {
  const { data: projects, isLoading: projectsLoading } = useProjects();
  const { data: generations, isLoading: gensLoading } = useRecentGenerations();
  const items = useMemo(
    () => buildItems(generations, projects),
    [generations, projects],
  );
  const isLoading = projectsLoading || gensLoading;

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
            <ItemCard key={item.id} item={item} index={idx} />
          ))}
        </motion.div>
      )}
    </div>
  );
};

export default LibraryPage;

const ItemCard = ({ item, index }: { item: LibraryItem; index: number }) => {
  const sb = itemStatusBadge(item.status);
  const isProcessing =
    item.status === "GENERATING" ||
    item.status === "RENDERING" ||
    item.status === "PROCESSING" ||
    item.status === "PENDING";
  const editable = !!item.project;
  const target = editable
    ? `/dashboard/projects/${item.project!.id}`
    : `/dashboard/library/preview/${item.generationId}`;

  return (
    <motion.div
      initial={{ opacity: 0, y: 12 }}
      animate={{ opacity: 1, y: 0 }}
      transition={{ duration: 0.25, delay: index * 0.04 }}
      className="group relative overflow-hidden rounded-xl border border-border bg-card hover:border-primary/40 transition-all hover:shadow-elev"
    >
      <div className="relative aspect-[9/16] bg-muted overflow-hidden">
        {item.videoUrl ? (
          <video
            src={item.videoUrl}
            className="size-full object-cover"
            muted
            loop
            playsInline
            preload="metadata"
            autoPlay
          />
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
            {item.title ||
              item.prompt ||
              `Video ${item.generationId.slice(0, 8)}`}
          </p>
        </div>
      </div>
      <div className="px-4 py-3 flex items-center justify-between text-xs">
        <span className="text-muted-foreground">
          {formatDistanceToNow(new Date(item.createdAt), { addSuffix: true })}
        </span>
        <div className="flex items-center gap-2">
          {item.videoUrl && (
            <a
              href={item.videoUrl}
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
