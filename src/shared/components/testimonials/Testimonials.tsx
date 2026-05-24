import { motion } from "framer-motion";
import { Star } from "lucide-react";
import { BETA_MODE } from "@/lib/betaMode";

const testimonials = [
  {
    name: "Marta K.",
    role: "Content Creator",
    initials: "MK",
    quote:
      "Toucan cuts my video editing time from 3 hours to 10 minutes. The auto-cut engine just gets the rhythm right.",
  },
  {
    name: "Piotr W.",
    role: "E-commerce Brand",
    initials: "PW",
    quote:
      "We went from 2 TikToks a week to 15. The AI script is surprisingly good — hooks that actually convert.",
  },
  {
    name: "Julia R.",
    role: "Social Media Manager",
    initials: "JR",
    quote:
      "Drop the assets, write two sentences, done. It took me longer to read this testimonial than to make a video.",
  },
  {
    name: "Adam S.",
    role: "Marketing Agency",
    initials: "AS",
    quote:
      "The beat-sync feature alone saved us hours. Our clients can't believe we moved this fast.",
  },
];

const Testimonials = () => {
  if (BETA_MODE) return null;

  const doubled = [...testimonials, ...testimonials];

  return (
    <section id="testimonials" className="py-24 md:py-32 bg-muted/40 overflow-hidden">
      <div className="max-w-6xl mx-auto px-6 md:px-10 mb-14">
        <motion.p
          className="text-sm font-semibold text-primary uppercase tracking-widest mb-3"
          initial={{ opacity: 0, y: 12 }}
          whileInView={{ opacity: 1, y: 0 }}
          transition={{ duration: 0.5 }}
          viewport={{ once: true }}
        >
          Creators love it
        </motion.p>
        <motion.h2
          className="text-3xl md:text-4xl font-bold text-foreground tracking-tight"
          initial={{ opacity: 0, y: 16 }}
          whileInView={{ opacity: 1, y: 0 }}
          transition={{ duration: 0.55, delay: 0.05 }}
          viewport={{ once: true }}
        >
          Loved by creators.
        </motion.h2>
      </div>

      {/* Marquee — no gap/pause jitter */}
      <div className="relative">
        {/* Edge fades */}
        <div className="absolute left-0 top-0 bottom-0 w-24 bg-gradient-to-r from-muted/40 to-transparent z-10 pointer-events-none" />
        <div className="absolute right-0 top-0 bottom-0 w-24 bg-gradient-to-l from-muted/40 to-transparent z-10 pointer-events-none" />

        <div className="overflow-hidden">
          <motion.div
            className="flex gap-5"
            animate={{ x: ["0%", "-50%"] }}
            transition={{
              repeat: Infinity,
              repeatType: "loop",
              duration: 28,
              ease: "linear",
            }}
            style={{ width: "max-content" }}
          >
            {doubled.map((t, i) => (
              <TestimonialCard key={i} {...t} />
            ))}
          </motion.div>
        </div>
      </div>
    </section>
  );
};

const TestimonialCard = ({
  name,
  role,
  initials,
  quote,
}: {
  name: string;
  role: string;
  initials: string;
  quote: string;
}) => (
  <div className="w-[340px] md:w-[400px] flex-shrink-0 bg-card border border-border rounded-2xl p-6 text-left">
    {/* Stars */}
    <div className="flex gap-0.5 mb-4">
      {[...Array(5)].map((_, i) => (
        <Star key={i} size={13} className="text-primary fill-primary" />
      ))}
    </div>

    <p className="text-foreground/90 text-[15px] leading-relaxed mb-6">
      &ldquo;{quote}&rdquo;
    </p>

    {/* Author */}
    <div className="flex items-center gap-3">
      <div className="w-9 h-9 rounded-full gradient-bg flex items-center justify-center text-white text-[12px] font-bold flex-shrink-0">
        {initials}
      </div>
      <div>
        <p className="text-[13px] font-semibold text-foreground">{name}</p>
        <p className="text-[12px] text-muted-foreground">{role}</p>
      </div>
    </div>
  </div>
);

export default Testimonials;
