import { Wand2, Scissors, Upload } from "lucide-react";
import { motion } from "framer-motion";

const features = [
  {
    icon: <Wand2 className="w-8 h-8 text-primary" />,
    title: "AI Script & Voiceover",
    desc: "Drop your brief and get a fully narrated video script — tone, pacing, and hooks included.",
  },
  {
    icon: <Scissors className="w-8 h-8 text-primary" />,
    title: "Auto-Cut Engine",
    desc: "Beat-aligned cuts, smart transitions, and scene-to-asset mapping. No timeline dragging required.",
  },
  {
    icon: <Upload className="w-8 h-8 text-primary" />,
    title: "Ready to Post",
    desc: "Export TikTok-ready MP4 in seconds. Your assets, your brand — fully automated.",
  },
];

const Features = () => {
  return (
    <section id="features" className="py-28 bg-background">
      <div className="max-w-6xl mx-auto px-8 text-center">
        <motion.h2
          className="text-3xl md:text-4xl font-semibold text-foreground"
          initial={{ opacity: 0, y: 20 }}
          whileInView={{ opacity: 1, y: 0 }}
          transition={{ duration: 0.6 }}
          viewport={{ once: true }}
        >
          Everything you need to ship content fast.
        </motion.h2>

        <motion.p
          className="mt-4 text-muted-foreground max-w-2xl mx-auto text-lg"
          initial={{ opacity: 0, y: 20 }}
          whileInView={{ opacity: 1, y: 0 }}
          transition={{ duration: 0.6, delay: 0.1 }}
          viewport={{ once: true }}
        >
          From raw assets and a short brief to a fully edited video — automated end to end.
        </motion.p>

        <div className="grid md:grid-cols-3 gap-14 mt-20">
          {features.map((item, index) => (
            <motion.div
              key={index}
              className="flex flex-col items-center text-center"
              initial={{ opacity: 0, y: 30 }}
              whileInView={{ opacity: 1, y: 0 }}
              transition={{ duration: 0.5, delay: index * 0.1 }}
              viewport={{ once: true }}
            >
              <div className="mb-6">{item.icon}</div>
              <h3 className="text-xl font-medium text-foreground">{item.title}</h3>
              <p className="mt-3 text-muted-foreground text-base">{item.desc}</p>
            </motion.div>
          ))}
        </div>
      </div>
    </section>
  );
};

export default Features;
