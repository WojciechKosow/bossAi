import { useState, useEffect } from "react";
import { motion } from "framer-motion";
import { Sparkles, Zap, Music, ArrowRight, Play } from "lucide-react";
import { BETA_MODE } from "@/lib/betaMode";

function useClock() {
  const [time, setTime] = useState(() =>
    new Date().toLocaleTimeString([], { hour: "2-digit", minute: "2-digit" })
  );
  useEffect(() => {
    const id = setInterval(() => {
      setTime(new Date().toLocaleTimeString([], { hour: "2-digit", minute: "2-digit" }));
    }, 1000);
    return () => clearInterval(id);
  }, []);
  return time;
}

const container = {
  hidden: { opacity: 0 },
  show: {
    opacity: 1,
    transition: { staggerChildren: 0.11, delayChildren: 0.05 },
  },
};

const item = {
  hidden: { opacity: 0, y: 18 },
  show: {
    opacity: 1,
    y: 0,
    transition: { duration: 0.55, ease: [0.22, 1, 0.36, 1] as const },
  },
};

const Hero = () => {
  const scrollToWaitlist = () => {
    document.getElementById("waitlist")?.scrollIntoView({ behavior: "smooth" });
  };

  return (
    <div className="relative min-h-screen flex items-center overflow-hidden bg-background">
      {/* Background orbs */}
      <div className="absolute inset-0 pointer-events-none overflow-hidden">
        <div className="animate-orb-1 absolute -top-48 -left-48 w-[700px] h-[700px] rounded-full bg-primary/10 blur-[120px]" />
        <div className="animate-orb-2 absolute -bottom-32 -right-32 w-[500px] h-[500px] rounded-full bg-[hsl(358_88%_60%/0.08)] blur-[100px]" />
        <div className="animate-orb-3 absolute top-1/2 left-1/2 -translate-x-1/2 -translate-y-1/2 w-[300px] h-[300px] rounded-full bg-[hsl(328_85%_60%/0.06)] blur-[80px]" />
      </div>

      {/* Grid overlay */}
      <div className="absolute inset-0 grid-bg opacity-50" />

      <div className="relative max-w-7xl mx-auto px-6 md:px-10 pt-36 pb-20 w-full">
        <div className="grid lg:grid-cols-2 gap-12 xl:gap-20 items-center">

          {/* LEFT — text */}
          <motion.div
            variants={container}
            initial="hidden"
            animate="show"
            className="space-y-7"
          >
            {BETA_MODE && (
              <motion.div variants={item}>
                <span className="inline-flex items-center gap-2 px-3.5 py-1.5 rounded-full border border-primary/25 bg-primary/8 text-primary text-[13px] font-medium tracking-tight animate-badge-pop">
                  <Sparkles size={12} className="animate-pulse-soft" />
                  Closed Beta &mdash; Limited spots available
                </span>
              </motion.div>
            )}

            <motion.h1
              variants={item}
              className="text-5xl md:text-6xl lg:text-[64px] font-bold leading-[1.04] tracking-tight text-foreground"
            >
              Turn ideas into{" "}
              <span className="animate-gradient-text">
                viral&nbsp;TikToks
              </span>
              <br />
              <span className="text-foreground/80">in seconds.</span>
            </motion.h1>

            <motion.p
              variants={item}
              className="text-lg md:text-xl text-muted-foreground max-w-[480px] leading-relaxed"
            >
              Drop your assets, describe your vision &mdash; Toucan handles the
              script, voiceover, cuts and music. From prompt to final video in
              seconds.
            </motion.p>

            <motion.div
              variants={item}
              className="flex flex-col sm:flex-row items-start sm:items-center gap-3 pt-1"
            >
              {BETA_MODE ? (
                <button
                  onClick={scrollToWaitlist}
                  className="group inline-flex items-center gap-2.5 px-7 py-3.5 rounded-xl gradient-bg text-white font-semibold shadow-glow hover:opacity-90 active:scale-[0.98] transition-all duration-200 text-[15px]"
                >
                  Request beta access
                  <ArrowRight
                    size={16}
                    className="group-hover:translate-x-0.5 transition-transform duration-200"
                  />
                </button>
              ) : (
                <>
                  <a
                    href="/register"
                    className="group inline-flex items-center gap-2.5 px-7 py-3.5 rounded-xl gradient-bg text-white font-semibold shadow-glow hover:opacity-90 active:scale-[0.98] transition-all duration-200 text-[15px]"
                  >
                    Get started free
                    <ArrowRight
                      size={16}
                      className="group-hover:translate-x-0.5 transition-transform duration-200"
                    />
                  </a>
                  <a
                    href="/login"
                    className="px-6 py-3.5 rounded-xl border border-border bg-background/60 text-foreground hover:bg-muted/60 hover:border-border/80 transition-all duration-200 font-medium text-[15px]"
                  >
                    Sign in
                  </a>
                </>
              )}
            </motion.div>

            <motion.div
              variants={item}
              className="flex flex-wrap items-center gap-5 text-sm text-muted-foreground"
            >
              {[
                { icon: Sparkles, label: "AI script & voiceover" },
                { icon: Zap, label: "Auto-cut engine" },
                { icon: Music, label: "Beat-synced music" },
              ].map(({ icon: Icon, label }) => (
                <span key={label} className="flex items-center gap-1.5">
                  <span className="w-1 h-1 rounded-full bg-primary/80 inline-block" />
                  <Icon size={12} className="text-primary/70" />
                  {label}
                </span>
              ))}
            </motion.div>
          </motion.div>

          {/* RIGHT — phone mockup */}
          <motion.div
            initial={{ opacity: 0, x: 32 }}
            animate={{ opacity: 1, x: 0 }}
            transition={{ duration: 0.75, delay: 0.25, ease: [0.22, 1, 0.36, 1] }}
            className="relative hidden lg:flex items-center justify-center"
          >
            <PhoneMockup />
          </motion.div>
        </div>
      </div>
    </div>
  );
};

const PhoneMockup = () => {
  const time = useClock();
  return (
  <div className="relative select-none">
    {/* Ambient glow */}
    <div className="absolute inset-[-20%] rounded-full bg-primary/15 blur-[80px] animate-orb-3" />

    {/* Phone shell */}
    <div className="relative w-[248px] h-[500px] bg-card border border-border/60 rounded-[38px] shadow-elev overflow-hidden">

      {/* Status bar */}
      <div className="flex items-center justify-between px-5 pt-3 pb-2">
        <span className="text-[10px] text-muted-foreground font-semibold tabular-nums">{time}</span>
        <div className="w-20 h-3.5 rounded-full bg-muted" />
        <div className="flex items-center gap-1">
          <div className="w-3 h-3 rounded-full bg-muted/80" />
          <div className="w-3 h-3 rounded-full bg-muted/80" />
        </div>
      </div>

      {/* Video area */}
      <div className="mx-2.5 h-[360px] rounded-2xl overflow-hidden relative bg-gradient-to-br from-primary/25 via-[hsl(358_88%_60%/0.2)] to-[hsl(328_85%_60%/0.25)]">
        {/* Fake scene blocks */}
        <div className="absolute inset-0">
          <div className="absolute top-6 left-6 right-6 h-20 rounded-xl bg-white/8" />
          <div className="absolute top-32 left-4 right-4 h-12 rounded-lg bg-white/6" />
          <div className="absolute top-48 left-8 right-8 h-16 rounded-xl bg-white/5" />
        </div>

        {/* Play button */}
        <div className="absolute inset-0 flex items-center justify-center">
          <div className="w-14 h-14 rounded-full bg-white/15 backdrop-blur-sm border border-white/20 flex items-center justify-center">
            <Play size={20} className="text-white ml-1" fill="white" />
          </div>
        </div>

        {/* Bottom caption area */}
        <div className="absolute bottom-0 left-0 right-0 p-4 bg-gradient-to-t from-black/55 via-black/20 to-transparent">
          <div className="space-y-1.5 mb-3">
            <div className="h-2 w-3/4 rounded-full bg-white/55" />
            <div className="h-1.5 w-1/2 rounded-full bg-white/35" />
          </div>
          <div className="flex gap-2">
            <div className="h-5 w-14 rounded-full bg-primary/70 backdrop-blur-sm" />
            <div className="h-5 w-14 rounded-full bg-white/15 backdrop-blur-sm" />
          </div>
        </div>

        {/* TikTok-style side actions */}
        <div className="absolute right-3 bottom-16 flex flex-col items-center gap-3.5">
          {[...Array(4)].map((_, i) => (
            <div key={i} className="w-7 h-7 rounded-full bg-white/20 backdrop-blur-sm border border-white/10" />
          ))}
        </div>
      </div>

      {/* Generation progress */}
      <div className="mx-2.5 mt-3 px-3 py-2.5 rounded-xl bg-muted/60 border border-border/50">
        <div className="flex items-center justify-between mb-2">
          <span className="text-[10px] font-medium text-muted-foreground flex items-center gap-1.5">
            <Sparkles size={9} className="text-primary animate-pulse-soft" />
            Generating your video…
          </span>
          <span className="text-[10px] font-semibold text-primary tabular-nums">73%</span>
        </div>
        <div className="h-1 w-full rounded-full bg-muted overflow-hidden">
          <motion.div
            className="h-full rounded-full progress-bar-fill"
            initial={{ width: "0%" }}
            animate={{ width: "73%" }}
            transition={{ duration: 2.2, delay: 0.9, ease: [0.25, 0.46, 0.45, 0.94] }}
          />
        </div>
      </div>
    </div>

    {/* Floating badge — left */}
    <div className="animate-float absolute -left-[76px] top-[22%] glass rounded-xl px-3 py-2 shadow-elev border border-border/60">
      <div className="flex items-center gap-2 text-[11px] font-medium text-foreground whitespace-nowrap">
        <Zap size={11} className="text-primary" fill="currentColor" />
        Auto-cut engine
      </div>
    </div>

    {/* Floating badge — right */}
    <div className="animate-float-alt absolute -right-[68px] top-[52%] glass rounded-xl px-3 py-2 shadow-elev border border-border/60">
      <div className="flex items-center gap-2 text-[11px] font-medium text-foreground whitespace-nowrap">
        <Music size={11} className="text-primary" />
        Beat-synced
      </div>
    </div>
  </div>
  );
};

export default Hero;
