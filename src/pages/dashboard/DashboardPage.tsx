import { Link } from "react-router-dom";
import { motion } from "framer-motion";
import { formatDistanceToNow } from "date-fns";
import {
  Sparkles,
  ArrowRight,
  Film,
  Loader2,
  Image as ImageIcon,
  Video as VideoIcon,
  TrendingUp,
  Crown,
} from "lucide-react";
import { useAuth } from "../../features/auth/context/AuthContext";
import { useActivePlan, useProjects } from "@/features/video/hooks";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Progress } from "@/components/ui/progress";
import { ProjectThumbnail } from "@/features/video/components/ProjectThumbnail";

const DashboardPage = () => {
  const { user } = useAuth();
  const { data: plan } = useActivePlan();
  const { data: projects } = useProjects();

  const recent = (projects ?? []).slice(0, 3);
  const inFlight = (projects ?? []).filter(
    (p) => p.status === "GENERATING" || p.status === "RENDERING",
  );

  return (
    <div className="max-w-7xl mx-auto space-y-10">
      {/* Hero */}
      <motion.section
        initial={{ opacity: 0, y: 8 }}
        animate={{ opacity: 1, y: 0 }}
        className="relative overflow-hidden rounded-2xl border border-border bg-card p-8 grid-bg"
      >
        <div className="relative z-10 flex flex-col lg:flex-row lg:items-end lg:justify-between gap-5">
          <div>
            <Badge variant="gradient">
              <Sparkles size={12} /> Welcome
            </Badge>
            <h1 className="text-3xl sm:text-4xl font-bold tracking-tight mt-3">
              Hi {user?.displayName ?? "creator"} —{" "}
              <span className="gradient-text">let's make something</span>
            </h1>
            <p className="text-sm text-muted-foreground mt-2 max-w-xl">
              Spin up a TikTok-ready video in minutes. Drop your assets, give
              the AI a brief, and tweak it on the timeline.
            </p>
          </div>
          <div className="flex items-center gap-2">
            <Link to="/dashboard/library">
              <Button variant="outline">View library</Button>
            </Link>
            <Link to="/dashboard/create">
              <Button className="gradient-bg text-white shadow-glow">
                <Sparkles size={14} /> New video
                <ArrowRight size={14} />
              </Button>
            </Link>
          </div>
        </div>
      </motion.section>

      {/* Stats grid */}
      <section className="grid gap-4 sm:grid-cols-2 lg:grid-cols-4">
        <StatCard
          icon={<Crown size={16} />}
          label="Current plan"
          value={plan?.type ?? "—"}
          accent
        />
        <StatCard
          icon={<VideoIcon size={16} />}
          label="Videos remaining"
          value={
            plan ? `${plan.videosTotal - plan.videosUsed}/${plan.videosTotal}` : "—"
          }
          progress={
            plan ? (plan.videosUsed / Math.max(1, plan.videosTotal)) * 100 : 0
          }
        />
        <StatCard
          icon={<ImageIcon size={16} />}
          label="Image credits"
          value={
            plan ? `${plan.imagesTotal - plan.imagesUsed}/${plan.imagesTotal}` : "—"
          }
          progress={
            plan ? (plan.imagesUsed / Math.max(1, plan.imagesTotal)) * 100 : 0
          }
        />
        <StatCard
          icon={<TrendingUp size={16} />}
          label="Active projects"
          value={String(inFlight.length)}
          subtitle={
            inFlight.length
              ? `${inFlight.length} in progress`
              : "Nothing rendering"
          }
        />
      </section>

      {/* Recent + in-flight */}
      <section className="grid gap-6 lg:grid-cols-3">
        <div className="lg:col-span-2 rounded-xl border border-border bg-card p-5">
          <div className="flex items-center justify-between mb-4">
            <h2 className="text-sm font-semibold tracking-tight">
              Recent projects
            </h2>
            <Link
              to="/dashboard/library"
              className="text-xs text-muted-foreground hover:text-foreground inline-flex items-center gap-1"
            >
              View all <ArrowRight size={12} />
            </Link>
          </div>
          {recent.length === 0 ? (
            <EmptyRecent />
          ) : (
            <div className="grid grid-cols-3 gap-4">
              {recent.map((p) => (
                <Link
                  key={p.id}
                  to={`/dashboard/projects/${p.id}`}
                  className="group rounded-lg overflow-hidden border border-border hover:border-primary/40 transition"
                >
                  <div className="aspect-[9/16] bg-muted">
                    <ProjectThumbnail projectId={p.id} status={p.status} />
                  </div>
                  <div className="px-3 py-2">
                    <p className="text-xs font-medium truncate">
                      {p.title || p.originalPrompt || "Untitled"}
                    </p>
                    <p className="text-[10px] text-muted-foreground mt-0.5">
                      {formatDistanceToNow(new Date(p.createdAt), {
                        addSuffix: true,
                      })}
                    </p>
                  </div>
                </Link>
              ))}
            </div>
          )}
        </div>

        <div className="rounded-xl border border-border bg-card p-5">
          <h2 className="text-sm font-semibold tracking-tight mb-4">
            In progress
          </h2>
          {inFlight.length === 0 && (
            <p className="text-xs text-muted-foreground">
              Nothing rendering. Time to create something fresh.
            </p>
          )}
          <div className="space-y-3">
            {inFlight.map((p) => (
              <Link
                key={p.id}
                to={`/dashboard/projects/${p.id}`}
                className="block rounded-lg border border-border p-3 hover:border-primary/40 transition"
              >
                <div className="flex items-center gap-2 text-xs font-medium">
                  <Loader2 size={12} className="animate-spin text-primary" />
                  {p.status}
                </div>
                <p className="text-xs mt-1 truncate text-muted-foreground">
                  {p.title || p.originalPrompt || "Untitled"}
                </p>
              </Link>
            ))}
          </div>
        </div>
      </section>
    </div>
  );
};

export default DashboardPage;

const StatCard = ({
  icon,
  label,
  value,
  subtitle,
  progress,
  accent,
}: {
  icon: React.ReactNode;
  label: string;
  value: string;
  subtitle?: string;
  progress?: number;
  accent?: boolean;
}) => (
  <motion.div
    initial={{ opacity: 0, y: 8 }}
    animate={{ opacity: 1, y: 0 }}
    transition={{ duration: 0.25 }}
    className="rounded-xl border border-border bg-card p-5"
  >
    <div className="flex items-center justify-between">
      <p className="text-[11px] uppercase tracking-wider text-muted-foreground">
        {label}
      </p>
      <span
        className={
          accent
            ? "size-7 rounded-md gradient-bg text-white flex items-center justify-center"
            : "size-7 rounded-md bg-muted text-muted-foreground flex items-center justify-center"
        }
      >
        {icon}
      </span>
    </div>
    <p className="text-2xl font-semibold mt-2 tabular-nums truncate">{value}</p>
    {subtitle && (
      <p className="text-xs text-muted-foreground mt-1">{subtitle}</p>
    )}
    {progress !== undefined && (
      <div className="mt-3">
        <Progress value={progress} />
      </div>
    )}
  </motion.div>
);

const EmptyRecent = () => (
  <div className="rounded-lg border border-dashed border-border p-8 text-center">
    <div className="size-10 rounded-xl gradient-bg mx-auto flex items-center justify-center shadow-glow">
      <Film className="size-4 text-white" />
    </div>
    <p className="text-sm font-medium mt-3">No projects yet</p>
    <p className="text-xs text-muted-foreground mt-1">
      Create your first video to see it here.
    </p>
    <Link to="/dashboard/create" className="inline-block mt-4">
      <Button size="sm" className="gradient-bg text-white shadow-glow">
        <Sparkles size={12} /> Get started
      </Button>
    </Link>
  </div>
);
