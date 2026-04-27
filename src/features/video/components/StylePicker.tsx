import { motion } from "framer-motion";
import {
  BookOpen,
  Megaphone,
  GraduationCap,
  Zap,
  Camera,
  Crown,
  Film,
  PackageCheck,
  Settings2,
} from "lucide-react";
import type { VideoStyle } from "../types";
import { cn } from "@/lib/utils";

const styles: {
  id: VideoStyle;
  label: string;
  description: string;
  icon: typeof Zap;
}[] = [
  { id: "STORY_MODE", label: "Story Mode", description: "Narrative-driven", icon: BookOpen },
  { id: "HIGH_CONVERTING_AD", label: "High-Converting Ad", description: "Conversion-optimized", icon: Megaphone },
  { id: "EDUCATIONAL", label: "Educational", description: "Teach & explain", icon: GraduationCap },
  { id: "VIRAL_EDIT", label: "Viral Edit", description: "Punchy fast cuts", icon: Zap },
  { id: "UGC_STYLE", label: "UGC", description: "Authentic look", icon: Camera },
  { id: "LUXURY_AD", label: "Luxury", description: "Premium tone", icon: Crown },
  { id: "CINEMATIC", label: "Cinematic", description: "Movie-grade", icon: Film },
  { id: "PRODUCT_SHOWCASE", label: "Product", description: "Feature highlights", icon: PackageCheck },
  { id: "CUSTOM", label: "Custom", description: "Let AI decide", icon: Settings2 },
];

interface Props {
  value: VideoStyle | null;
  onChange: (v: VideoStyle | null) => void;
}

export const StylePicker = ({ value, onChange }: Props) => {
  return (
    <div className="grid grid-cols-2 sm:grid-cols-3 lg:grid-cols-3 gap-3">
      {styles.map((s) => {
        const Icon = s.icon;
        const active = value === s.id;
        return (
          <motion.button
            key={s.id}
            type="button"
            whileHover={{ y: -2 }}
            whileTap={{ scale: 0.98 }}
            onClick={() => onChange(active ? null : s.id)}
            className={cn(
              "relative text-left rounded-xl border p-4 transition-all",
              active
                ? "border-primary bg-accent/40 shadow-glow"
                : "border-border bg-card hover:border-primary/40 hover:bg-muted/40",
            )}
          >
            <div
              className={cn(
                "size-9 rounded-lg flex items-center justify-center mb-3",
                active
                  ? "gradient-bg text-white"
                  : "bg-muted text-muted-foreground",
              )}
            >
              <Icon size={16} />
            </div>
            <p className="text-sm font-semibold">{s.label}</p>
            <p className="text-xs text-muted-foreground mt-0.5">
              {s.description}
            </p>
          </motion.button>
        );
      })}
    </div>
  );
};
