import { Link } from "react-router-dom";
import { motion } from "framer-motion";
import { ArrowRight } from "lucide-react";
import { BETA_MODE } from "@/lib/betaMode";
import BetaSignupForm from "@/shared/components/beta/BetaSignupForm";

const SectionCTA = () => {
  if (BETA_MODE) {
    return (
      <section id="waitlist" className="relative w-full py-24 md:py-32 bg-background overflow-hidden">
        {/* Background orbs */}
        <div className="absolute inset-0 pointer-events-none overflow-hidden">
          <div className="absolute -top-1/2 left-1/2 -translate-x-1/2 w-[600px] h-[600px] rounded-full bg-primary/10 blur-[100px]" />
        </div>
        <div className="absolute inset-0 grid-bg opacity-40" />

        <div className="relative max-w-xl mx-auto px-6 md:px-10">
          <motion.div
            initial={{ opacity: 0, y: 24 }}
            whileInView={{ opacity: 1, y: 0 }}
            transition={{ duration: 0.6, ease: [0.22, 1, 0.36, 1] }}
            viewport={{ once: true }}
            className="text-center space-y-4 mb-10"
          >
            <h2 className="text-3xl md:text-4xl font-bold tracking-tight">
              Get early access
            </h2>
            <p className="text-muted-foreground leading-relaxed">
              We're onboarding a small group of creators for the closed beta.
              Drop your email and we'll send you credentials when you're
              approved.
            </p>
          </motion.div>

          <motion.div
            initial={{ opacity: 0, y: 16 }}
            whileInView={{ opacity: 1, y: 0 }}
            transition={{ duration: 0.6, delay: 0.15, ease: [0.22, 1, 0.36, 1] }}
            viewport={{ once: true }}
          >
            <BetaSignupForm />
          </motion.div>
        </div>
      </section>
    );
  }

  return (
    <section className="relative w-full py-24 md:py-32 overflow-hidden bg-background">
      {/* Gradient orb */}
      <div className="absolute inset-0 pointer-events-none">
        <div className="absolute inset-0 gradient-bg opacity-[0.92]" />
        <div className="absolute bottom-0 left-0 right-0 h-1/2 bg-gradient-to-t from-black/20 to-transparent" />
      </div>

      <div className="relative max-w-3xl mx-auto px-6 md:px-10 text-center">
        <motion.h2
          initial={{ opacity: 0, y: 20 }}
          whileInView={{ opacity: 1, y: 0 }}
          transition={{ duration: 0.6 }}
          viewport={{ once: true }}
          className="text-3xl md:text-4xl lg:text-5xl font-bold tracking-tight text-white leading-tight"
        >
          Ready to start creating?
        </motion.h2>

        <motion.p
          initial={{ opacity: 0, y: 20 }}
          whileInView={{ opacity: 1, y: 0 }}
          transition={{ duration: 0.6, delay: 0.1 }}
          viewport={{ once: true }}
          className="mt-5 text-white/75 text-lg max-w-xl mx-auto leading-relaxed"
        >
          Join creators using Toucan to produce TikTok content at scale.
        </motion.p>

        <motion.div
          initial={{ opacity: 0, y: 20 }}
          whileInView={{ opacity: 1, y: 0 }}
          transition={{ duration: 0.6, delay: 0.2 }}
          viewport={{ once: true }}
          className="mt-10 flex flex-col sm:flex-row gap-3 justify-center"
        >
          <Link
            to="/register"
            className="group inline-flex items-center justify-center gap-2 px-7 py-3.5 rounded-xl bg-white text-foreground font-semibold hover:bg-white/90 active:scale-[0.98] transition-all duration-200 text-[15px]"
          >
            Get started free
            <ArrowRight
              size={16}
              className="group-hover:translate-x-0.5 transition-transform duration-200"
            />
          </Link>
          <Link
            to="/login"
            className="inline-flex items-center justify-center px-7 py-3.5 rounded-xl border border-white/25 text-white hover:bg-white/10 active:scale-[0.98] transition-all duration-200 font-medium text-[15px]"
          >
            Sign in
          </Link>
        </motion.div>
      </div>
    </section>
  );
};

export default SectionCTA;
