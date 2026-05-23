import { Link } from "react-router-dom";
import { motion } from "framer-motion";
import { BETA_MODE } from "@/lib/betaMode";
import BetaSignupForm from "@/shared/components/beta/BetaSignupForm";

const SectionCTA = () => {
  if (BETA_MODE) {
    return (
      <section id="waitlist" className="w-full py-28 bg-background">
        <div className="max-w-xl mx-auto px-8">
          <motion.div
            initial={{ opacity: 0, y: 24 }}
            whileInView={{ opacity: 1, y: 0 }}
            transition={{ duration: 0.6 }}
            viewport={{ once: true }}
            className="text-center space-y-4 mb-10"
          >
            <h2 className="text-3xl md:text-4xl font-bold tracking-tight">
              Get early access
            </h2>
            <p className="text-muted-foreground">
              We're onboarding a small group of creators for the closed beta.
              Drop your email and we'll send you credentials when you're approved.
            </p>
          </motion.div>

          <motion.div
            initial={{ opacity: 0, y: 16 }}
            whileInView={{ opacity: 1, y: 0 }}
            transition={{ duration: 0.6, delay: 0.15 }}
            viewport={{ once: true }}
          >
            <BetaSignupForm />
          </motion.div>
        </div>
      </section>
    );
  }

  return (
    <section className="w-full bg-primary text-primary-foreground py-28 text-center">
      <motion.h2
        initial={{ opacity: 0, y: 20 }}
        whileInView={{ opacity: 1, y: 0 }}
        transition={{ duration: 0.6 }}
        className="text-3xl md:text-4xl font-semibold tracking-tight"
      >
        Ready to get started?
      </motion.h2>
      <motion.p
        initial={{ opacity: 0, y: 20 }}
        whileInView={{ opacity: 1, y: 0 }}
        transition={{ duration: 0.6, delay: 0.1 }}
        className="mt-4 opacity-80 max-w-xl mx-auto"
      >
        Join creators using Toucan to produce TikTok content at scale.
      </motion.p>
      <motion.div
        initial={{ opacity: 0, y: 20 }}
        whileInView={{ opacity: 1, y: 0 }}
        transition={{ duration: 0.6, delay: 0.2 }}
        className="mt-10 flex gap-4 justify-center"
      >
        <Link
          to="/register"
          className="px-5 py-2.5 rounded-lg bg-background text-foreground font-medium hover:bg-muted transition"
        >
          Get Started
        </Link>
        <Link
          to="/login"
          className="px-5 py-2.5 rounded-lg border border-white/30 hover:bg-white/10 transition"
        >
          Sign In
        </Link>
      </motion.div>
    </section>
  );
};

export default SectionCTA;
