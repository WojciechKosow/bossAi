import { motion } from "framer-motion";
import { Upload, PenLine, Zap, Check } from "lucide-react";
import type { ReactNode } from "react";
import { BETA_MODE } from "@/lib/betaMode";

/** V0.1 has no real testimonials — this honest workflow strip stands in for
 *  social proof, showing exactly what happens between upload and export. */
const steps: { icon: ReactNode; title: string; desc: string }[] = [
  {
    icon: <Upload className="w-5 h-5 text-white" />,
    title: "Upload your assets",
    desc: "Drop in clips, images and music — in any order. Toucan reads them as raw material.",
  },
  {
    icon: <PenLine className="w-5 h-5 text-white" />,
    title: "Describe the vibe",
    desc: "Two sentences on the hook and mood. The AI turns that into a structured script.",
  },
  {
    icon: <Zap className="w-5 h-5 text-white" />,
    title: "Toucan edits it",
    desc: "Beat-synced cuts, transitions and music are assembled automatically to the script.",
  },
  {
    icon: <Check className="w-5 h-5 text-white" />,
    title: "Export & post",
    desc: "Download a vertical, TikTok-ready MP4 in seconds — your brand, your assets.",
  },
];

const Workflow = () => {
  return (
    <section
      id="workflow"
      className="relative overflow-hidden py-24 md:py-32 bg-muted/40"
    >
      <div className="max-w-6xl mx-auto px-6 md:px-10">
        {/* Section label + heading */}
        <div className="max-w-2xl mb-16 md:mb-20">
          <motion.p
            className="text-sm font-semibold text-primary uppercase tracking-widest mb-3"
            initial={{ opacity: 0, y: 12 }}
            whileInView={{ opacity: 1, y: 0 }}
            transition={{ duration: 0.5 }}
            viewport={{ once: true }}
          >
            The workflow
          </motion.p>
          <motion.h2
            className="text-3xl md:text-4xl font-bold text-foreground tracking-tight"
            initial={{ opacity: 0, y: 16 }}
            whileInView={{ opacity: 1, y: 0 }}
            transition={{ duration: 0.55, delay: 0.05 }}
            viewport={{ once: true }}
          >
            From brief to feed in four moves.
          </motion.h2>
          <motion.p
            className="mt-4 text-muted-foreground text-lg leading-relaxed"
            initial={{ opacity: 0, y: 16 }}
            whileInView={{ opacity: 1, y: 0 }}
            transition={{ duration: 0.55, delay: 0.1 }}
            viewport={{ once: true }}
          >
            {BETA_MODE
              ? "Here's exactly what happens between your upload and a post-ready video."
              : "No fake reviews here — this is V0.1. Instead, here's exactly what happens between your upload and a post-ready video."}
          </motion.p>
        </div>

        {/* Pipeline */}
        <div className="relative grid gap-10 md:grid-cols-4 md:gap-4">
          {/* Connecting line + flowing highlight (md and up) */}
          <div className="hidden md:block absolute top-11 left-[12%] right-[12%] h-px bg-border overflow-hidden">
            <motion.div
              className="absolute inset-y-0 w-1/3 progress-bar-fill rounded-full"
              initial={{ x: "-120%" }}
              animate={{ x: "420%" }}
              transition={{ duration: 3.4, ease: "linear", repeat: Infinity }}
            />
          </div>

          {steps.map((step, i) => (
            <motion.div
              key={i}
              className="group relative z-10 text-center px-2"
              initial={{ opacity: 0, y: 24 }}
              whileInView={{ opacity: 1, y: 0 }}
              transition={{ duration: 0.5, delay: i * 0.12, ease: [0.22, 1, 0.36, 1] }}
              viewport={{ once: true }}
            >
              {/* Badge */}
              <div className="relative mx-auto mb-6 w-[88px] h-[88px] rounded-3xl bg-card border border-border shadow-elev flex items-center justify-center transition-all duration-300 group-hover:-translate-y-1 group-hover:shadow-glow">
                <span className="absolute -top-2 -right-2 w-6 h-6 rounded-full bg-foreground text-background text-[12px] font-bold flex items-center justify-center tabular-nums">
                  {i + 1}
                </span>
                <div className="w-11 h-11 rounded-2xl gradient-bg flex items-center justify-center shadow-glow">
                  {step.icon}
                </div>
              </div>

              <h3 className="text-base font-semibold text-foreground mb-2">
                {step.title}
              </h3>
              <p className="text-sm text-muted-foreground leading-relaxed max-w-[240px] mx-auto">
                {step.desc}
              </p>
            </motion.div>
          ))}
        </div>
      </div>
    </section>
  );
};

export default Workflow;
