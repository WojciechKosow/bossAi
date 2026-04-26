import { Link } from "react-router";
import { motion } from "framer-motion";

const SectionCTA = () => {
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
        Join thousands of learners using Toucan to improve, progress, and grow
        every day.
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
          to="/contact"
          className="px-5 py-2.5 rounded-lg border border-border hover:bg-muted hover:text-black transition"
        >
          Contact
        </Link>
      </motion.div>
    </section>
  );
};

export default SectionCTA;
