import { motion } from "framer-motion";
import { Sparkles } from "lucide-react";
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
    price: "$29 / mo",
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
    price: "$99 / mo",
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
      <section id="pricing" className="py-28 bg-muted/30">
        <div className="max-w-xl mx-auto px-8 text-center space-y-6">
          <motion.div
            initial={{ opacity: 0, y: 20 }}
            whileInView={{ opacity: 1, y: 0 }}
            transition={{ duration: 0.6 }}
            viewport={{ once: true }}
            className="space-y-4"
          >
            <div className="size-14 rounded-2xl gradient-bg mx-auto flex items-center justify-center shadow-glow">
              <Sparkles className="size-6 text-white" />
            </div>
            <h2 className="text-3xl font-bold tracking-tight">
              Free during beta
            </h2>
            <p className="text-muted-foreground">
              Beta testers get full, unlimited access at no cost.
              Pricing plans will be announced once we exit beta &mdash; and early
              supporters get a lifetime discount.
            </p>
          </motion.div>
        </div>
      </section>
    );
  }

  return (
    <section id="pricing" className="py-28 bg-background">
      <div className="max-w-6xl mx-auto px-8 text-center">
        <motion.h2
          className="text-3xl md:text-4xl font-semibold text-foreground"
          initial={{ opacity: 0, y: 20 }}
          whileInView={{ opacity: 1, y: 0 }}
          transition={{ duration: 0.6 }}
          viewport={{ once: true }}
        >
          Simple, transparent pricing.
        </motion.h2>
        <p className="mt-4 text-muted-foreground max-w-xl mx-auto">
          Choose the plan that fits your content output.
        </p>
        <div className="mt-16 grid grid-cols-1 md:grid-cols-3 gap-8">
          {plans.map((plan, i) => (
            <motion.div
              key={i}
              className={`border rounded-2xl p-8 text-left transition ${
                plan.highlighted ? "border-primary shadow-lg scale-[1.03]" : "border-border"
              }`}
              initial={{ opacity: 0, y: 30 }}
              whileInView={{ opacity: 1, y: 0 }}
              transition={{ duration: 0.5, delay: i * 0.1 }}
              viewport={{ once: true }}
            >
              <h3 className="text-xl font-semibold">{plan.name}</h3>
              <p className="text-3xl font-bold mt-3">{plan.price}</p>
              <p className="text-muted-foreground mt-3 text-sm">{plan.description}</p>
              <ul className="mt-8 space-y-3 text-muted-foreground text-sm">
                {plan.features.map((f, index) => (
                  <li key={index} className="flex items-center gap-2">
                    <span className="text-primary">✓</span> {f}
                  </li>
                ))}
              </ul>
              <button
                className={`mt-10 w-full py-2.5 rounded-xl text-sm font-medium transition ${
                  plan.highlighted
                    ? "bg-primary text-primary-foreground hover:opacity-90"
                    : "border border-border text-foreground hover:bg-muted"
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
