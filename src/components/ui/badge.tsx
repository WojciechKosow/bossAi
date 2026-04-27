import * as React from "react";
import { cn } from "@/lib/utils";

type Variant = "default" | "outline" | "secondary" | "success" | "warning" | "destructive" | "gradient";

const variantClasses: Record<Variant, string> = {
  default: "bg-secondary text-secondary-foreground",
  outline: "border border-border text-foreground/80",
  secondary: "bg-muted text-muted-foreground",
  success: "bg-emerald-500/15 text-emerald-600 dark:text-emerald-400 border border-emerald-500/20",
  warning: "bg-amber-500/15 text-amber-600 dark:text-amber-400 border border-amber-500/20",
  destructive: "bg-destructive/15 text-destructive border border-destructive/30",
  gradient: "gradient-bg text-white",
};

interface BadgeProps extends React.HTMLAttributes<HTMLSpanElement> {
  variant?: Variant;
}

export const Badge = React.forwardRef<HTMLSpanElement, BadgeProps>(
  ({ className, variant = "default", ...props }, ref) => (
    <span
      ref={ref}
      className={cn(
        "inline-flex items-center gap-1.5 rounded-full px-2.5 py-0.5 text-[11px] font-medium tracking-wide",
        variantClasses[variant],
        className,
      )}
      {...props}
    />
  ),
);
Badge.displayName = "Badge";
