import { useQuery } from "@tanstack/react-query";
import axios from "@/lib/axios";
import { Check, Crown } from "lucide-react";
import { format } from "date-fns";

const BillingPage = () => {
  const { data: activePlan } = useQuery({
    queryKey: ["active-plan"],
    queryFn: async () => {
      const res = await axios.get("/api/me/plans/active-plan");
      return res.data;
    },
  });

  const { data: userPlans } = useQuery({
    queryKey: ["user-plans"],
    queryFn: async () => {
      const res = await axios.get("/api/me/plans");
      return res.data;
    },
  });

  const { data: allPlans } = useQuery({
    queryKey: ["all-plans"],
    queryFn: async () => {
      const res = await axios.get("/api/plans");
      return res.data;
    },
  });

  return (
    <div className="max-w-6xl mx-auto space-y-16">
      {/* HEADER */}
      <div>
        <h1 className="text-2xl sm:text-3xl font-semibold tracking-tight">
          Billing & Plans
        </h1>
        <p className="text-muted-foreground mt-2">
          Manage your subscription and review your plan usage.
        </p>
      </div>

      {/* CURRENT PLAN */}
      {activePlan && (
        <div className="bg-card border border-border rounded-xl p-8">
          <div className="flex items-center justify-between flex-wrap gap-4">
            <div>
              <div className="flex items-center gap-2">
                <Crown size={18} />
                <h2 className="text-lg font-semibold">{activePlan.type}</h2>
                <span className="text-xs bg-primary text-primary-foreground px-2 py-1 rounded-full">
                  Active
                </span>
              </div>

              <p className="text-muted-foreground mt-2 text-sm">
                Expires {format(new Date(activePlan.expiresAt), "PPP")}
              </p>
            </div>

            <div className="text-right">
              <p className="text-sm text-muted-foreground">
                {activePlan.imagesUsed}/{activePlan.imagesTotal} images
              </p>
              <p className="text-sm text-muted-foreground">
                {activePlan.videosUsed}/{activePlan.videosTotal} videos
              </p>
            </div>
          </div>
        </div>
      )}

      {/* USER PLANS HISTORY */}
      <div>
        <h2 className="text-lg font-semibold mb-6">Your Plans</h2>

        <div className="grid gap-6 md:grid-cols-2">
          {userPlans?.map((plan: any) => (
            <div
              key={plan.id}
              className="bg-card border border-border rounded-xl p-6"
            >
              <div className="flex justify-between items-center mb-3">
                <h3 className="font-medium">{plan.type}</h3>
                <span
                  className={`text-xs px-2 py-1 rounded-full ${
                    plan.active
                      ? "bg-green-600 text-white"
                      : "bg-muted text-muted-foreground"
                  }`}
                >
                  {plan.active ? "Active" : "Expired"}
                </span>
              </div>

              <p className="text-xs text-muted-foreground">
                {format(new Date(plan.activatedAt), "PPP")} –{" "}
                {format(new Date(plan.expiresAt), "PPP")}
              </p>

              <div className="mt-4 text-xs text-muted-foreground">
                {plan.imagesUsed}/{plan.imagesTotal} images used
              </div>
            </div>
          ))}
        </div>
      </div>

      {/* AVAILABLE PLANS */}
      <div>
        <h2 className="text-lg font-semibold mb-6">Available Plans</h2>

        <div className="grid gap-8 md:grid-cols-3">
          {allPlans?.map((plan: any) => {
            const isCurrent = activePlan?.planType === plan.id;

            return (
              <div
                key={plan.id}
                className={`border rounded-xl p-6 transition ${
                  isCurrent ? "border-primary shadow-md" : "border-border"
                }`}
              >
                <h3 className="text-lg font-semibold">{plan.id}</h3>

                <p className="text-2xl font-bold mt-2">
                  ${(plan.priceCents / 100).toFixed(2)}
                </p>

                <p className="text-xs text-muted-foreground mt-1">
                  {plan.subscription ? "Subscription" : "One-time"}
                </p>

                <ul className="mt-6 space-y-2 text-sm">
                  <Feature text={`${plan.imagesLimit} Images`} />
                  <Feature text={`${plan.videosLimit} Videos`} />
                  {plan.watermark && <Feature text="Watermark" />}
                  {plan.priorityQueue && <Feature text="Priority Queue" />}
                  {plan.commercialUse && <Feature text="Commercial Use" />}
                </ul>

                <button
                  disabled={isCurrent}
                  className={`mt-8 w-full py-2.5 rounded-lg text-sm font-medium transition ${
                    isCurrent
                      ? "bg-muted text-muted-foreground cursor-not-allowed"
                      : "bg-primary text-primary-foreground hover:opacity-90"
                  }`}
                >
                  {isCurrent ? "Current Plan" : "Upgrade"}
                </button>
              </div>
            );
          })}
        </div>
      </div>
    </div>
  );
};

export default BillingPage;

/* ---------- FEATURE ITEM ---------- */

const Feature = ({ text }: { text: string }) => (
  <li className="flex items-center gap-2">
    <Check size={14} />
    {text}
  </li>
);
