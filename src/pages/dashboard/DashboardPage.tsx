import { useAuth } from "../../features/auth/context/AuthContext";
import { Sparkles, TrendingUp, Clock } from "lucide-react";
import { useQuery } from "@tanstack/react-query";
import axios from "@/lib/axios";
import { formatDistanceToNow } from "date-fns";

const DashboardPage = () => {
  const { user } = useAuth();

  const { data: plan } = useQuery({
    queryKey: ["active-plan"],
    queryFn: async () => {
      const res = await axios.get("/api/me/plans/active-plan");
      return res.data;
    },
  });

  const { data: generations } = useQuery({
    queryKey: ["recent-generations"],
    queryFn: async () => {
      const res = await axios.get("/api/generations/me?limit=3");
      return res.data;
    },
  });

  const lastGeneration =
    generations?.length > 0
      ? formatDistanceToNow(new Date(generations[0].createdAt), {
          addSuffix: true,
        })
      : "No generations yet";

  const imagesRemaining = plan ? plan.imagesTotal - plan.imagesUsed : 0;

  const videosRemaining = plan ? plan.videosTotal - plan.videosUsed : 0;

  return (
    <div className="space-y-12">
      {/* HEADER */}
      <div>
        <h1 className="text-2xl sm:text-3xl font-semibold tracking-tight">
          Welcome back, {user?.displayName}
        </h1>
        <p className="text-muted-foreground mt-2">
          Here’s an overview of your activity and account status.
        </p>
      </div>

      {/* STATS */}
      <div className="grid gap-6 sm:grid-cols-2 lg:grid-cols-4">
        <StatCard
          title="Current Plan"
          value={plan?.type ?? "..."}
          icon={<Sparkles size={18} />}
        />

        <StatCard
          title="Images Remaining"
          value={imagesRemaining.toString()}
          icon={<TrendingUp size={18} />}
        />

        <StatCard
          title="Videos Remaining"
          value={videosRemaining.toString()}
          icon={<TrendingUp size={18} />}
        />

        <StatCard
          title="Last Generation"
          value={lastGeneration}
          icon={<Clock size={18} />}
        />
      </div>

      {/* QUICK ACTIONS */}
      <div className="grid gap-6 md:grid-cols-2">
        <div className="bg-card border border-border rounded-xl p-6">
          <h2 className="text-lg font-semibold mb-4">Quick Actions</h2>

          <div className="flex flex-col gap-3">
            <a
              href="/dashboard/generate/images"
              className="bg-primary text-primary-foreground py-2.5 rounded-lg text-sm font-medium text-center hover:opacity-90 transition"
            >
              Generate New Image
            </a>

            <a
              href="/dashboard/generate/videos"
              className="border border-border py-2.5 rounded-lg text-sm font-medium text-center hover:bg-muted transition"
            >
              Generate New Video
            </a>
          </div>
        </div>

        <div className="bg-card border border-border rounded-xl p-6">
          <h2 className="text-lg font-semibold mb-4">Usage Overview</h2>

          <div className="space-y-4 text-sm">
            {plan && (
              <>
                <UsageBar
                  label="Images"
                  used={plan.imagesUsed}
                  total={plan.imagesTotal}
                />
                <UsageBar
                  label="Videos"
                  used={plan.videosUsed}
                  total={plan.videosTotal}
                />
              </>
            )}
          </div>
        </div>
      </div>

      {/* RECENT GENERATIONS */}
      <div>
        <div className="flex items-center justify-between">
          <h2 className="text-lg font-semibold">Recent Generations</h2>
          <a
            href="/dashboard/gallery"
            className="text-sm text-primary hover:underline"
          >
            View all
          </a>
        </div>

        <div className="grid gap-6 mt-6 sm:grid-cols-2 lg:grid-cols-3">
          {generations?.map((gen: any) => (
            <div
              key={gen.id}
              className="rounded-xl overflow-hidden border border-border bg-card hover:shadow-sm transition"
            >
              {gen.imageUrl && (
                <img
                  src={gen.imageUrl}
                  alt="Generated"
                  className="w-full h-48 object-cover"
                />
              )}

              <div className="p-4 text-xs text-muted-foreground">
                {formatDistanceToNow(new Date(gen.createdAt), {
                  addSuffix: true,
                })}
              </div>
            </div>
          ))}
        </div>
      </div>
    </div>
  );
};

export default DashboardPage;

const StatCard = ({
  title,
  value,
  icon,
}: {
  title: string;
  value: string;
  icon: React.ReactNode;
}) => {
  return (
    <div className="bg-card border border-border rounded-xl p-5 flex items-center justify-between">
      <div>
        <p className="text-xs text-muted-foreground">{title}</p>
        <p className="text-lg font-semibold mt-1">{value}</p>
      </div>
      <div className="text-muted-foreground">{icon}</div>
    </div>
  );
};

const UsageBar = ({
  label,
  used,
  total,
}: {
  label: string;
  used: number;
  total: number;
}) => {
  const percentage = total > 0 ? (used / total) * 100 : 0;

  return (
    <div>
      <div className="flex justify-between mb-1">
        <span>{label}</span>
        <span>
          {used}/{total}
        </span>
      </div>
      <div className="h-2 bg-muted rounded-full overflow-hidden">
        <div
          className="h-full bg-primary transition-all"
          style={{ width: `${percentage}%` }}
        />
      </div>
    </div>
  );
};
