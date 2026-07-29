import { motion } from "framer-motion";
import { Check, Sparkles } from "lucide-react";
import { Link } from "react-router-dom";
import { BETA_MODE } from "@/lib/betaMode";
import { usePlansCatalog } from "@/features/billing/hooks";
import type { PlanDefinition, PlanType } from "@/features/billing/types";

/** The tiers the landing page advertises, in display order. Data is the real
 *  plan catalog from the backend (GET /api/plans) — never hardcoded prices. */
const SHOWN: PlanType[] = ["FREE", "BASIC", "PRO"];
const HIGHLIGHT: PlanType = "PRO";

const LABEL: Partial<Record<PlanType, string>> = {
  FREE: "Free",
  BASIC: "Basic",
  PRO: "Pro",
};

const TAGLINE: Partial<Record<PlanType, string>> = {
  FREE: "Kick the tires — no card needed.",
  BASIC: "For creators getting started.",
  PRO: "For creators shipping every day.",
};

const formatPrice = (cents: number, currency = "USD") =>
  new Intl.NumberFormat("en-US", {
    style: "currency",
    currency,
    minimumFractionDigits: cents % 100 === 0 ? 0 : 2,
  }).format(cents / 100);

/** Turn a plan's DB fields into a human feature list (mirrors the billing page). */
function planFeatures(p: PlanDefinition): string[] {
  const features = [`${p.monthlyCreditsTotal.toLocaleString()} credits / month`];
  features.push(p.watermark ? "Watermark on videos" : "No watermark");
  if (p.commercialUse) features.push("Commercial-use license");
  if (p.priorityQueue) features.push("Priority render queue");
  if (p.storage) features.push("Asset & video storage");
  if (p.assetReuse) features.push("Reuse your uploaded assets");
  features.push(
    `${p.maxConcurrentGenerations} generation${
      p.maxConcurrentGenerations > 1 ? "s" : ""
    } at once`,
  );
  return features;
}

const Pricing = () => {
  const { data: plans, isLoading } = usePlansCatalog();

  if (BETA_MODE) {
    return (
      <section id="pricing" className="py-24 md:py-32 bg-background">
        <div className="max-w-xl mx-auto px-6 md:px-10 text-center">
          <motion.div
            initial={{ opacity: 0, y: 20 }}
            whileInView={{ opacity: 1, y: 0 }}
            transition={{ duration: 0.55 }}
            viewport={{ once: true }}
            className="space-y-5"
          >
            <div className="size-14 rounded-2xl gradient-bg mx-auto flex items-center justify-center shadow-glow">
              <Sparkles className="size-6 text-white" />
            </div>
            <h2 className="text-3xl font-bold tracking-tight">
              Free during beta
            </h2>
            <p className="text-muted-foreground leading-relaxed">
              Beta testers get full, unlimited access at no cost. Pricing plans
              will be announced once we exit beta &mdash; and early supporters
              get a lifetime discount.
            </p>
          </motion.div>
        </div>
      </section>
    );
  }

  // Order the live catalog into the three advertised tiers.
  const cards = SHOWN.map((id) => plans?.find((p) => p.id === id)).filter(
    (p): p is PlanDefinition => Boolean(p),
  );
  const showSkeleton = isLoading || cards.length === 0;

  return (
    <section id="pricing" className="py-24 md:py-32 bg-background">
      <div className="max-w-6xl mx-auto px-6 md:px-10">
        <div className="max-w-2xl mb-16 md:mb-20">
          <motion.p
            className="text-sm font-semibold text-primary uppercase tracking-widest mb-3"
            initial={{ opacity: 0, y: 12 }}
            whileInView={{ opacity: 1, y: 0 }}
            transition={{ duration: 0.5 }}
            viewport={{ once: true }}
          >
            Pricing
          </motion.p>
          <motion.h2
            className="text-3xl md:text-4xl font-bold text-foreground tracking-tight"
            initial={{ opacity: 0, y: 16 }}
            whileInView={{ opacity: 1, y: 0 }}
            transition={{ duration: 0.55, delay: 0.05 }}
            viewport={{ once: true }}
          >
            Simple, transparent pricing.
          </motion.h2>
          <motion.p
            className="mt-4 text-muted-foreground text-lg"
            initial={{ opacity: 0, y: 16 }}
            whileInView={{ opacity: 1, y: 0 }}
            transition={{ duration: 0.55, delay: 0.1 }}
            viewport={{ once: true }}
          >
            Start free, upgrade when you're ready. No hidden fees.
          </motion.p>
        </div>

        <div className="grid grid-cols-1 md:grid-cols-3 gap-4 md:gap-5 items-start">
          {showSkeleton
            ? SHOWN.map((id, i) => <PlanSkeleton key={id} index={i} />)
            : cards.map((plan, i) => {
                const highlighted = plan.id === HIGHLIGHT;
                const isFree = plan.priceCents === 0;
                return (
                  <motion.div
                    key={plan.id}
                    className={`relative rounded-2xl p-7 text-left transition-all duration-300 ${
                      highlighted
                        ? "gradient-border bg-card shadow-glow"
                        : "border border-border bg-card hover:border-border/70"
                    }`}
                    initial={{ opacity: 0, y: 28 }}
                    whileInView={{ opacity: 1, y: 0 }}
                    transition={{
                      duration: 0.5,
                      delay: i * 0.1,
                      ease: [0.22, 1, 0.36, 1],
                    }}
                    viewport={{ once: true }}
                  >
                    {highlighted && (
                      <span className="absolute -top-3 left-1/2 -translate-x-1/2 inline-flex items-center gap-1.5 px-3 py-1 rounded-full gradient-bg text-white text-[11px] font-semibold whitespace-nowrap shadow-glow">
                        <Sparkles size={10} />
                        Full access
                      </span>
                    )}

                    <div className="mb-6">
                      <h3 className="text-[15px] font-semibold text-muted-foreground uppercase tracking-wider mb-3">
                        {LABEL[plan.id] ?? plan.id}
                      </h3>
                      <div className="flex items-end gap-1">
                        <span className="text-4xl font-black text-foreground tracking-tight">
                          {isFree ? "Free" : formatPrice(plan.priceCents, plan.currency)}
                        </span>
                        {!isFree && (
                          <span className="text-muted-foreground text-sm mb-1.5">
                            / mo
                          </span>
                        )}
                      </div>
                      <p className="text-muted-foreground text-sm mt-2">
                        {TAGLINE[plan.id] ?? " "}
                      </p>
                    </div>

                    <ul className="space-y-2.5 mb-8">
                      {planFeatures(plan).map((f) => (
                        <li
                          key={f}
                          className="flex items-center gap-2.5 text-sm text-foreground/85"
                        >
                          <span className="w-4 h-4 rounded-full gradient-bg flex items-center justify-center flex-shrink-0">
                            <Check size={10} className="text-white" strokeWidth={3} />
                          </span>
                          {f}
                        </li>
                      ))}
                    </ul>

                    {isFree ? (
                      <Link
                        to="/register"
                        className="block w-full py-2.5 rounded-xl text-[14px] font-semibold text-center bg-foreground text-background hover:bg-foreground/90 transition-all duration-200 active:scale-[0.98]"
                      >
                        Get Started
                      </Link>
                    ) : (
                      <p className="text-center text-xs text-muted-foreground py-2.5">
                        Upgrade anytime after signing up
                      </p>
                    )}
                  </motion.div>
                );
              })}
        </div>
      </div>
    </section>
  );
};

/** Placeholder card shown while the plan catalog loads. */
const PlanSkeleton = ({ index }: { index: number }) => (
  <div
    className="rounded-2xl border border-border bg-card p-7"
    style={{ animationDelay: `${index * 120}ms` }}
  >
    <div className="animate-pulse space-y-4">
      <div className="h-3 w-16 rounded bg-muted" />
      <div className="h-9 w-28 rounded bg-muted" />
      <div className="h-3 w-40 rounded bg-muted" />
      <div className="space-y-2.5 pt-4">
        {Array.from({ length: 4 }).map((_, i) => (
          <div key={i} className="h-3 w-full rounded bg-muted" />
        ))}
      </div>
      <div className="h-10 w-full rounded-xl bg-muted mt-4" />
    </div>
  </div>
);

export default Pricing;
