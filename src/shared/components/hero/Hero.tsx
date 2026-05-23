import { motion } from "framer-motion";
import { Sparkles, Zap, Music } from "lucide-react";
import { BETA_MODE } from "@/lib/betaMode";

const Hero = () => {
  const scrollToWaitlist = () => {
    document.getElementById("waitlist")?.scrollIntoView({ behavior: "smooth" });
  };

  return (
    <div className="pt-32 pb-28 bg-background">
      <div className="max-w-6xl mx-auto px-8 text-center">
        <motion.div
          initial={{ opacity: 0, y: 24 }}
          animate={{ opacity: 1, y: 0 }}
          transition={{ duration: 0.6 }}
          className="space-y-6"
        >
          {BETA_MODE && (
            <span className="inline-flex items-center gap-2 px-3 py-1.5 rounded-full border border-primary/30 bg-primary/5 text-primary text-sm font-medium">
              <Sparkles size={13} /> Closed Beta &mdash; Limited spots available
            </span>
          )}

          <h1 className="text-5xl md:text-7xl font-bold leading-tight text-foreground tracking-tight">
            Turn ideas into{" "}
            <span className="gradient-text">TikTok-ready videos</span>
          </h1>

          <p className="text-lg md:text-xl text-muted-foreground max-w-2xl mx-auto">
            Drop your assets, describe your vision &mdash; Toucan handles the script,
            voiceover, cuts and music. From prompt to final video in seconds.
          </p>

          <div className="flex flex-col sm:flex-row items-center justify-center gap-4 pt-2">
            {BETA_MODE ? (
              <button
                onClick={scrollToWaitlist}
                className="px-8 py-3.5 rounded-xl gradient-bg text-white font-semibold shadow-glow hover:opacity-90 transition text-base"
              >
                Request beta access
              </button>
            ) : (
              <>
                <a
                  href="/register"
                  className="px-8 py-3.5 rounded-xl gradient-bg text-white font-semibold shadow-glow hover:opacity-90 transition text-base"
                >
                  Get started free
                </a>
                <a
                  href="/login"
                  className="px-8 py-3.5 rounded-xl border border-border bg-background text-foreground hover:bg-muted transition font-medium text-base"
                >
                  Sign in
                </a>
              </>
            )}
          </div>

          <div className="flex flex-wrap items-center justify-center gap-6 text-sm text-muted-foreground pt-2">
            <span className="flex items-center gap-1.5">
              <Sparkles size={14} className="text-primary" /> AI script &amp; voiceover
            </span>
            <span className="flex items-center gap-1.5">
              <Zap size={14} className="text-primary" /> Auto-cut engine
            </span>
            <span className="flex items-center gap-1.5">
              <Music size={14} className="text-primary" /> Beat-synced music
            </span>
          </div>
        </motion.div>
      </div>
    </div>
  );
};

export default Hero;
