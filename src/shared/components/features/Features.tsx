import { Wand2, Scissors, Upload } from "lucide-react";
import { motion } from "framer-motion";
import type { ReactNode } from "react";

const features: { icon: ReactNode; title: string; desc: string }[] = [
  {
    icon: <Wand2 className="w-5 h-5 text-white" />,
    title: "AI Script & Voiceover",
    desc: "Drop your brief and get a fully narrated video script — tone, pacing, and hooks included.",
  },
  {
    icon: <Scissors className="w-5 h-5 text-white" />,
    title: "Auto-Cut Engine",
    desc: "Beat-aligned cuts, smart transitions, and scene-to-asset mapping. No timeline dragging required.",
  },
  {
    icon: <Upload className="w-5 h-5 text-white" />,
    title: "Ready to Post",
    desc: "Export TikTok-ready MP4 in seconds. Your assets, your brand — fully automated.",
  },
];

const Features = () => {
  return (
    <section id="features" className="py-24 md:py-32 bg-background">
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
            How it works
          </motion.p>
          <motion.h2
            className="text-3xl md:text-4xl font-bold text-foreground tracking-tight leading-tight"
            initial={{ opacity: 0, y: 16 }}
            whileInView={{ opacity: 1, y: 0 }}
            transition={{ duration: 0.55, delay: 0.05 }}
            viewport={{ once: true }}
          >
            Everything you need to ship content fast.
          </motion.h2>
          <motion.p
            className="mt-4 text-muted-foreground text-lg leading-relaxed"
            initial={{ opacity: 0, y: 16 }}
            whileInView={{ opacity: 1, y: 0 }}
            transition={{ duration: 0.55, delay: 0.1 }}
            viewport={{ once: true }}
          >
            From raw assets and a short brief to a fully edited video — automated end to end.
          </motion.p>
        </div>

        <div className="grid md:grid-cols-3 gap-4 md:gap-5">
          {features.map((feat, i) => (
            <motion.div
              key={i}
              className="group relative overflow-hidden rounded-2xl border border-border bg-card p-7 cursor-default transition-all duration-300 hover:-translate-y-1 hover:border-primary/25 hover:shadow-glow"
              initial={{ opacity: 0, y: 28 }}
              whileInView={{ opacity: 1, y: 0 }}
              transition={{ duration: 0.5, delay: i * 0.1, ease: [0.22, 1, 0.36, 1] }}
              viewport={{ once: true }}
            >
              {/* Hover gradient wash */}
              <div className="absolute inset-0 bg-gradient-to-br from-primary/[0.04] via-transparent to-transparent opacity-0 group-hover:opacity-100 transition-opacity duration-300 pointer-events-none" />

              {/* Step number — watermark */}
              <span className="absolute top-5 right-6 text-7xl font-black text-foreground/[0.04] select-none leading-none tabular-nums">
                {String(i + 1).padStart(2, "0")}
              </span>

              {/* Icon container */}
              <div className="relative mb-6 w-10 h-10 rounded-xl gradient-bg flex items-center justify-center shadow-glow">
                {feat.icon}
              </div>

              <h3 className="relative text-[17px] font-semibold text-foreground mb-2.5 leading-snug">
                {feat.title}
              </h3>
              <p className="relative text-muted-foreground text-[15px] leading-relaxed">
                {feat.desc}
              </p>
            </motion.div>
          ))}
        </div>
      </div>
    </section>
  );
};

export default Features;
