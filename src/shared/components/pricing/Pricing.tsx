import { motion } from "framer-motion";
import { Check, Sparkles } from "lucide-react";
import { BETA_MODE } from "@/lib/betaMode";

const plans = [
  {
    name: "Starter",
    price: "Free",
    description: "Try out the core features.",
    features: ["5 video generations", "720p export", "Watermark"],
    highlighted: false,
  },
  {
    name: "Pro",
    price: "$29",
    period: "/ mo",
    description: "For creators who ship content daily.",
    features: [
      "Unlimited generations",
      "1080p export",
      "No watermark",
      "Custom music upload",
      "Priority queue",
    ],
    highlighted: true,
  },
  {
    name: "Agency",
    price: "$99",
    period: "/ mo",
    description: "For teams and high-volume production.",
    features: [
      "Everything in Pro",
      "Team seats",
      "Brand presets",
      "Dedicated support",
    ],
    highlighted: false,
  },
];

const Pricing = () => {
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
            Choose the plan that fits your content output.
          </motion.p>
        </div>

        <div className="grid grid-cols-1 md:grid-cols-3 gap-4 md:gap-5 items-start">
          {plans.map((plan, i) => (
            <motion.div
              key={i}
              className={`relative rounded-2xl p-7 text-left transition-all duration-300 ${
                plan.highlighted
                  ? "gradient-border bg-card shadow-glow"
                  : "border border-border bg-card hover:border-border/70"
              }`}
              initial={{ opacity: 0, y: 28 }}
              whileInView={{ opacity: 1, y: 0 }}
              transition={{ duration: 0.5, delay: i * 0.1, ease: [0.22, 1, 0.36, 1] }}
              viewport={{ once: true }}
            >
              {plan.highlighted && (
                <span className="absolute -top-3 left-1/2 -translate-x-1/2 inline-flex items-center gap-1.5 px-3 py-1 rounded-full gradient-bg text-white text-[11px] font-semibold whitespace-nowrap shadow-glow">
                  <Sparkles size={10} />
                  Most popular
                </span>
              )}

              <div className="mb-6">
                <h3 className="text-[15px] font-semibold text-muted-foreground uppercase tracking-wider mb-3">
                  {plan.name}
                </h3>
                <div className="flex items-end gap-1">
                  <span className="text-4xl font-black text-foreground tracking-tight">
                    {plan.price}
                  </span>
                  {plan.period && (
                    <span className="text-muted-foreground text-sm mb-1.5">{plan.period}</span>
                  )}
                </div>
                <p className="text-muted-foreground text-sm mt-2">{plan.description}</p>
              </div>

              <ul className="space-y-2.5 mb-8">
                {plan.features.map((f, j) => (
                  <li key={j} className="flex items-center gap-2.5 text-sm text-foreground/85">
                    <span className="w-4 h-4 rounded-full gradient-bg flex items-center justify-center flex-shrink-0">
                      <Check size={10} className="text-white" strokeWidth={3} />
                    </span>
                    {f}
                  </li>
                ))}
              </ul>

              <button
                className={`w-full py-2.5 rounded-xl text-[14px] font-semibold transition-all duration-200 active:scale-[0.98] ${
                  plan.highlighted
                    ? "gradient-bg text-white hover:opacity-90 shadow-glow"
                    : "border border-border text-foreground hover:bg-muted/60"
                }`}
              >
                Get Started
              </button>
            </motion.div>
          ))}
        </div>
      </div>
    </section>
  );
};

export default Pricing;
