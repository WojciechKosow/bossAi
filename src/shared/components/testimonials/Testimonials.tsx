import { motion } from "framer-motion";
import { BETA_MODE } from "@/lib/betaMode";

const testimonials = [
  {
    name: "Marta K.",
    role: "Content Creator",
    quote:
      "Toucan cuts my video editing time from 3 hours to 10 minutes. The auto-cut engine just gets the rhythm right.",
  },
  {
    name: "Piotr W.",
    role: "E-commerce Brand",
    quote:
      "We went from 2 TikToks a week to 15. The AI script is surprisingly good — hooks that actually convert.",
  },
  {
    name: "Julia R.",
    role: "Social Media Manager",
    quote:
      "Drop the assets, write two sentences, done. It took me longer to read this testimonial than to make a video.",
  },
];

const Testimonials = () => {
  if (BETA_MODE) return null;

  return (
    <section id="testimonials" className="py-28 bg-muted">
      <div className="max-w-6xl mx-auto px-8 text-center">
        <motion.h2
          className="text-3xl md:text-4xl font-semibold text-foreground"
          initial={{ opacity: 0, y: 20 }}
          whileInView={{ opacity: 1, y: 0 }}
          transition={{ duration: 0.6 }}
          viewport={{ once: true }}
        >
          Loved by creators.
        </motion.h2>

        <motion.p
          className="mt-4 text-muted-foreground max-w-xl mx-auto"
          initial={{ opacity: 0, y: 20 }}
          whileInView={{ opacity: 1, y: 0 }}
          transition={{ duration: 0.6, delay: 0.1 }}
          viewport={{ once: true }}
        >
          Real stories from real people.
        </motion.p>

        <div className="relative overflow-hidden mt-20">
          <motion.div
            className="flex gap-12"
            animate={{ x: ["0%", "-50%"] }}
            transition={{
              repeat: Infinity,
              repeatType: "mirror",
              duration: 18,
              ease: "easeInOut",
            }}
          >
            {[...testimonials, ...testimonials].map((t, index) => (
              <div
                key={index}
                className="min-w-[320px] md:min-w-[420px] bg-card border border-border rounded-2xl p-8 text-left"
              >
                <p className="text-foreground text-lg leading-relaxed">
                  &ldquo;{t.quote}&rdquo;
                </p>
                <div className="mt-6">
                  <p className="font-medium text-foreground">{t.name}</p>
                  <p className="text-sm text-muted-foreground">{t.role}</p>
                </div>
              </div>
            ))}
          </motion.div>
        </div>
      </div>
    </section>
  );
};

export default Testimonials;
