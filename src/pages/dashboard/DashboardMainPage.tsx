import { useQuery } from "@tanstack/react-query";
import {
  getActivePlan,
  getRecentGenerations,
} from "@/features/dashboard/api/dashboardApi";
import { ImageIcon, Video, Clock } from "lucide-react";
import { formatDistanceToNow } from "date-fns";
import { useAuth } from "../../features/auth/context/AuthContext";

const DashboardMainPage = () => {
  const { user } = useAuth();

  const { data: plan, isLoading: planLoading } = useQuery({
    queryKey: ["active-plan"],
    queryFn: getActivePlan,
  });

  const { data: generations, isLoading: genLoading } = useQuery({
    queryKey: ["recent-generations"],
    queryFn: getRecentGenerations,
  });

  const lastGeneration =
    generations?.length > 0
      ? formatDistanceToNow(new Date(generations[0].createdAt), {
          addSuffix: true,
        })
      : null;

  if (planLoading || genLoading) {
    return <div className="text-muted-foreground">Loading dashboard...</div>;
  }

  return (
    <div className="max-w-6xl mx-auto space-y-10">
      {/* HEADER */}
      <div>
        <h1 className="text-2xl sm:text-3xl font-semibold tracking-tight">
          Welcome back, {user?.displayName}
        </h1>
        <p className="text-muted-foreground mt-2">
          Here's your account overview.
        </p>
      </div>

      {/* PLAN CARD */}
      <div className="grid sm:grid-cols-2 gap-6">
        <div className="bg-card border border-border rounded-xl p-6">
          <h2 className="text-lg font-medium">Current Plan</h2>
          <p className="mt-3 text-2xl font-semibold">{plan.planType}</p>
          <p className="text-sm text-muted-foreground mt-2">
            Expires {new Date(plan.expiresAt).toLocaleDateString()}
          </p>
        </div>

        <div className="bg-card border border-border rounded-xl p-6">
          <h2 className="text-lg font-medium">Last Generation</h2>
          <p className="mt-3 text-lg">
            {lastGeneration ?? "No generations yet"}
          </p>
        </div>
      </div>

      {/* USAGE */}
      <div className="bg-card border border-border rounded-xl p-6">
        <h2 className="text-lg font-medium mb-6">Usage</h2>

        <div className="grid sm:grid-cols-2 gap-8">
          <div>
            <div className="flex items-center gap-2 mb-2">
              <ImageIcon size={18} />
              <span>Images</span>
            </div>
            <div className="h-2 bg-muted rounded-full overflow-hidden">
              <div
                className="h-full bg-primary"
                style={{
                  width: `${(plan.imagesUsed / plan.imagesTotal) * 100}%`,
                }}
              />
            </div>
            <p className="text-sm text-muted-foreground mt-2">
              {plan.imagesUsed} / {plan.imagesTotal}
            </p>
          </div>

          <div>
            <div className="flex items-center gap-2 mb-2">
              <Video size={18} />
              <span>Videos</span>
            </div>
            <div className="h-2 bg-muted rounded-full overflow-hidden">
              <div
                className="h-full bg-primary"
                style={{
                  width: `${(plan.videosUsed / plan.videosTotal) * 100}%`,
                }}
              />
            </div>
            <p className="text-sm text-muted-foreground mt-2">
              {plan.videosUsed} / {plan.videosTotal}
            </p>
          </div>
        </div>
      </div>

      {/* RECENT GENERATIONS */}
      <div className="bg-card border border-border rounded-xl p-6">
        <h2 className="text-lg font-medium mb-6">Recent Generations</h2>

        {generations?.length === 0 && (
          <p className="text-muted-foreground">
            You haven't generated anything yet.
          </p>
        )}

        <div className="grid sm:grid-cols-3 gap-6">
          {generations?.map((gen: any) => (
            <div
              key={gen.id}
              className="rounded-xl overflow-hidden border border-border bg-background"
            >
              {gen.imageUrl && (
                <img src={gen.imageUrl} className="w-full h-40 object-cover" />
              )}

              <div className="p-4 text-sm space-y-1">
                <p className="font-medium">{gen.generationType}</p>
                <p className="text-muted-foreground text-xs">
                  {formatDistanceToNow(new Date(gen.createdAt), {
                    addSuffix: true,
                  })}
                </p>
                <p className="text-xs">Status: {gen.generationStatus}</p>
              </div>
            </div>
          ))}
        </div>
      </div>
    </div>
  );
};

export default DashboardMainPage;
