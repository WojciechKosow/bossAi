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
} from "lucide-react";
import { useProjects } from "@/features/video/hooks";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import type { ProjectStatus, VideoProjectDTO } from "@/features/video/types";
import { ProjectThumbnail } from "@/features/video/components/ProjectThumbnail";

const statusBadge: Record<ProjectStatus, { label: string; variant: any }> = {
  DRAFT: { label: "Draft", variant: "outline" },
  GENERATING: { label: "Generating", variant: "warning" },
  READY: { label: "Ready", variant: "success" },
  RENDERING: { label: "Rendering", variant: "warning" },
  COMPLETE: { label: "Complete", variant: "success" },
  FAILED: { label: "Failed", variant: "destructive" },
};

const LibraryPage = () => {
  const { data: projects, isLoading } = useProjects();

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

      {!isLoading && (!projects || projects.length === 0) && (
        <EmptyState />
      )}

      {projects && projects.length > 0 && (
        <motion.div
          layout
          className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-3 xl:grid-cols-4 gap-5"
        >
          {projects.map((p, idx) => (
            <ProjectCard key={p.id} project={p} index={idx} />
          ))}
        </motion.div>
      )}
    </div>
  );
};

export default LibraryPage;

const ProjectCard = ({
  project,
  index,
}: {
  project: VideoProjectDTO;
  index: number;
}) => {
  const sb = statusBadge[project.status];
  const updated = project.updatedAt ?? project.createdAt;
  const isProcessing =
    project.status === "GENERATING" || project.status === "RENDERING";

  return (
    <motion.div
      initial={{ opacity: 0, y: 12 }}
      animate={{ opacity: 1, y: 0 }}
      transition={{ duration: 0.25, delay: index * 0.04 }}
      className="group relative overflow-hidden rounded-xl border border-border bg-card hover:border-primary/40 transition-all hover:shadow-elev"
    >
      <Link to={`/dashboard/projects/${project.id}`} className="block">
        <div className="relative aspect-[9/16] bg-muted overflow-hidden">
          <ProjectThumbnail projectId={project.id} status={project.status} />
          <div className="absolute inset-0 bg-gradient-to-t from-black/80 via-black/0 to-black/0 opacity-90" />
          <div className="absolute top-3 left-3 right-3 flex items-center justify-between">
            <Badge variant={sb.variant}>
              {isProcessing && <Loader2 size={10} className="animate-spin" />}
              {sb.label}
            </Badge>
            {project.style && (
              <span className="text-[10px] uppercase tracking-wider text-white/80 bg-black/40 px-2 py-0.5 rounded-full backdrop-blur">
                {project.style.replace(/_/g, " ")}
              </span>
            )}
          </div>
          <div className="absolute inset-0 flex items-center justify-center opacity-0 group-hover:opacity-100 transition">
            <div className="size-12 rounded-full gradient-bg flex items-center justify-center shadow-glow">
              <PlayCircle className="size-6 text-white" />
            </div>
          </div>
          <div className="absolute bottom-3 left-3 right-3 text-white">
            <p className="font-semibold text-sm leading-tight line-clamp-2">
              {project.title || project.originalPrompt || "Untitled video"}
            </p>
          </div>
        </div>
        <div className="px-4 py-3 flex items-center justify-between text-xs">
          <span className="text-muted-foreground">
            {updated &&
              formatDistanceToNow(new Date(updated), { addSuffix: true })}
          </span>
          <span className="text-muted-foreground inline-flex items-center gap-1">
            <Pencil size={11} /> v{project.currentEdlVersion ?? 1}
          </span>
        </div>
      </Link>
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
