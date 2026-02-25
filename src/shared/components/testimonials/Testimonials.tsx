import { motion } from "framer-motion";

const testimonials = [
  {
    name: "Laura M.",
    role: "Marketing Student",
    quote:
      "Chico helped me learn at my own pace. The courses actually feel modern and engaging.",
  },
  {
    name: "James K.",
    role: "Software Engineer",
    quote:
      "Clear lessons, real-world exercises, and progress tracking made it super easy to stay motivated.",
  },
  {
    name: "Adriana F.",
    role: "UX Designer",
    quote:
      "The certificate I earned helped me land my first full-time role. Couldn’t be happier.",
  },
];

const Testimonials = () => {
  return (
    <section id="testimonials" className="py-28 bg-muted">
      <div className="max-w-6xl mx-auto px-8 text-center">
        <h2 className="text-3xl md:text-4xl font-semibold text-foreground">
          Loved by learners around the world.
        </h2>

        <p className="mt-4 text-muted-foreground max-w-xl mx-auto">
          Real stories from real people.
        </p>

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
                  {t.quote}
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
