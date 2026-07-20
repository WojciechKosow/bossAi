import { format } from "date-fns";
import { Check, Coins, Crown, Loader2, Settings } from "lucide-react";
import { Button } from "@/components/ui/button";
import { BETA_MODE } from "@/lib/betaMode";
import {
  useActivePlan,
  useBillingPortal,
  useCancelSubscription,
  useCreditPacks,
  useCredits,
  usePlansCatalog,
  useResumeSubscription,
  useStartPlan,
  useStartSubscription,
  useStartTopUp,
} from "@/features/billing/hooks";
import type { PlanDefinition } from "@/features/billing/types";

const formatPrice = (cents: number, currency = "USD") =>
  new Intl.NumberFormat("en-US", { style: "currency", currency }).format(
    cents / 100,
  );

const planFeatures = (p: PlanDefinition): string[] => {
  const features = [`${p.monthlyCreditsTotal} credits`];
  features.push(p.watermark ? "Watermark on videos" : "No watermark");
  if (p.commercialUse) features.push("Commercial use");
  if (p.priorityQueue) features.push("Priority queue");
  if (p.storage) features.push("Asset & video storage");
  if (p.assetReuse) features.push("Asset reuse");
  return features;
};

const BillingPage = () => {
  const { data: activePlan } = useActivePlan();
  const { data: plans } = usePlansCatalog();
  const { data: creditPacks } = useCreditPacks();
  const { data: credits } = useCredits();

  const startSubscription = useStartSubscription();
  const startPlan = useStartPlan();
  const startTopUp = useStartTopUp();
  const portal = useBillingPortal();
  const cancelSub = useCancelSubscription();
  const resumeSub = useResumeSubscription();

  const currentType = activePlan?.type;
  const hasSubscription = currentType === "BASIC" || currentType === "PRO";

  const subscriptionPlans =
    plans
      ?.filter((p) => p.subscription && p.priceCents > 0)
      .sort((a, b) => a.priceCents - b.priceCents) ?? [];
  const oneTimePlans =
    plans?.filter((p) => p.oneTime && p.priceCents > 0) ?? [];

  return (
    <div className="max-w-6xl mx-auto space-y-14">
      {/* HEADER */}
      <div className="flex items-start justify-between flex-wrap gap-4">
        <div>
          <h1 className="text-2xl sm:text-3xl font-semibold tracking-tight">
            Billing &amp; Plans
          </h1>
          <p className="text-muted-foreground mt-2">
            Upgrade your plan or top up credits. Payments are handled securely by
            Stripe.
          </p>
        </div>

        {hasSubscription && (
          <Button
            variant="outline"
            onClick={() => portal.mutate()}
            disabled={portal.isPending}
          >
            {portal.isPending ? (
              <Loader2 className="animate-spin" />
            ) : (
              <Settings />
            )}
            Manage subscription
          </Button>
        )}
      </div>

      {BETA_MODE && (
        <div className="bg-card border border-primary/30 rounded-xl p-6 text-center text-sm text-muted-foreground">
          Beta access — billing is disabled while the closed beta is running.
        </div>
      )}

      {/* CREDIT BALANCE */}
      {credits && (
        <div className="bg-card border border-border rounded-xl p-8">
          <div className="flex items-center gap-2 text-muted-foreground text-sm">
            <Coins size={16} />
            Available credits
          </div>
          <p className="text-4xl font-bold mt-2">{credits.totalCredits}</p>
          <p className="text-sm text-muted-foreground mt-2">
            {credits.planCredits} from your plan &middot; {credits.walletCredits}{" "}
            in your wallet
          </p>
        </div>
      )}

      {/* CURRENT PLAN */}
      {activePlan && (
        <div className="bg-card border border-border rounded-xl p-8">
          <div className="flex items-center gap-2">
            <Crown size={18} />
            <h2 className="text-lg font-semibold">{activePlan.type}</h2>
            <span
              className={`text-xs px-2 py-1 rounded-full ${
                activePlan.active
                  ? "bg-green-600 text-white"
                  : "bg-muted text-muted-foreground"
              }`}
            >
              {activePlan.active ? "Active" : "Inactive"}
            </span>
          </div>
          {activePlan.expiresAt && (
            <p className="text-muted-foreground mt-2 text-sm">
              {hasSubscription
                ? activePlan.cancelAtPeriodEnd
                  ? "Cancels"
                  : "Renews"
                : "Expires"}{" "}
              {format(new Date(activePlan.expiresAt), "PPP")}
            </p>
          )}

          {/* Cancel / resume — deferred cancellation keeps the plan until it expires */}
          {hasSubscription && (
            <div className="mt-5">
              {activePlan.cancelAtPeriodEnd ? (
                <div className="flex items-center gap-3 flex-wrap">
                  <span className="text-sm text-amber-600 dark:text-amber-400">
                    Your subscription is set to cancel — you keep {activePlan.type}{" "}
                    until {format(new Date(activePlan.expiresAt), "PPP")}.
                  </span>
                  <Button
                    size="sm"
                    onClick={() => resumeSub.mutate()}
                    disabled={resumeSub.isPending}
                  >
                    {resumeSub.isPending ? (
                      <Loader2 className="animate-spin" />
                    ) : null}
                    Resume subscription
                  </Button>
                </div>
              ) : (
                <Button
                  size="sm"
                  variant="outline"
                  onClick={() => {
                    if (
                      window.confirm(
                        `Cancel your ${activePlan.type} subscription? You'll keep it until ${format(
                          new Date(activePlan.expiresAt),
                          "PPP",
                        )}, then drop to the free plan. You won't be charged again.`,
                      )
                    ) {
                      cancelSub.mutate();
                    }
                  }}
                  disabled={cancelSub.isPending}
                >
                  {cancelSub.isPending ? (
                    <Loader2 className="animate-spin" />
                  ) : null}
                  Cancel subscription
                </Button>
              )}
            </div>
          )}
        </div>
      )}

      {/* SUBSCRIPTION PLANS */}
      {subscriptionPlans.length > 0 && (
        <section>
          <h2 className="text-lg font-semibold mb-6">Subscription plans</h2>
          <div className="grid gap-6 md:grid-cols-2">
            {subscriptionPlans.map((plan) => {
              const isCurrent = currentType === plan.id;
              return (
                <PlanCard
                  key={plan.id}
                  plan={plan}
                  suffix="/mo"
                  isCurrent={isCurrent}
                  loading={
                    startSubscription.isPending &&
                    startSubscription.variables === plan.id
                  }
                  onSelect={() => startSubscription.mutate(plan.id)}
                  cta={isCurrent ? "Current plan" : "Subscribe"}
                />
              );
            })}
          </div>
        </section>
      )}

      {/* ONE-TIME PLANS */}
      {oneTimePlans.length > 0 && (
        <section>
          <h2 className="text-lg font-semibold mb-6">One-time plans</h2>
          <div className="grid gap-6 md:grid-cols-2">
            {oneTimePlans.map((plan) => {
              const isCurrent = currentType === plan.id;
              return (
                <PlanCard
                  key={plan.id}
                  plan={plan}
                  isCurrent={isCurrent}
                  loading={
                    startPlan.isPending && startPlan.variables === plan.id
                  }
                  onSelect={() => startPlan.mutate(plan.id)}
                  cta={isCurrent ? "Current plan" : "Buy"}
                />
              );
            })}
          </div>
        </section>
      )}

      {/* CREDIT PACKS */}
      {creditPacks && creditPacks.length > 0 && (
        <section>
          <h2 className="text-lg font-semibold mb-2">Top up credits</h2>
          <p className="text-muted-foreground text-sm mb-6">
            Credits go to your wallet and are used after your plan credits run
            out. No plan required.
          </p>
          <div className="grid gap-6 sm:grid-cols-3">
            {creditPacks.map((pack) => (
              <div
                key={pack.id}
                className="border border-border rounded-xl p-6 flex flex-col"
              >
                <div className="flex items-center gap-2">
                  <Coins size={18} />
                  <h3 className="font-semibold">{pack.credits} credits</h3>
                </div>
                <p className="text-2xl font-bold mt-3">
                  {formatPrice(pack.priceCents)}
                </p>
                <Button
                  className="mt-6 w-full"
                  variant="outline"
                  onClick={() => startTopUp.mutate(pack.id)}
                  disabled={
                    startTopUp.isPending && startTopUp.variables === pack.id
                  }
                >
                  {startTopUp.isPending && startTopUp.variables === pack.id ? (
                    <Loader2 className="animate-spin" />
                  ) : null}
                  Buy
                </Button>
              </div>
            ))}
          </div>
        </section>
      )}
    </div>
  );
};

export default BillingPage;

/* ---------- PLAN CARD ---------- */

interface PlanCardProps {
  plan: PlanDefinition;
  isCurrent: boolean;
  loading: boolean;
  onSelect: () => void;
  cta: string;
  suffix?: string;
}

const PlanCard = ({
  plan,
  isCurrent,
  loading,
  onSelect,
  cta,
  suffix,
}: PlanCardProps) => (
  <div
    className={`border rounded-xl p-6 flex flex-col transition ${
      isCurrent ? "border-primary shadow-md" : "border-border"
    }`}
  >
    <h3 className="text-lg font-semibold">{plan.id}</h3>
    <p className="text-2xl font-bold mt-2">
      {formatPrice(plan.priceCents, plan.currency)}
      {suffix && (
        <span className="text-sm font-normal text-muted-foreground">
          {suffix}
        </span>
      )}
    </p>

    <ul className="mt-6 space-y-2 text-sm flex-1">
      {planFeatures(plan).map((f) => (
        <li key={f} className="flex items-center gap-2">
          <Check size={14} className="text-primary" />
          {f}
        </li>
      ))}
    </ul>

    <Button
      className="mt-8 w-full"
      disabled={isCurrent || loading}
      onClick={onSelect}
    >
      {loading ? <Loader2 className="animate-spin" /> : null}
      {cta}
    </Button>
  </div>
);
