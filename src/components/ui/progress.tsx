import * as React from "react";
import { cn } from "@/lib/utils";

interface ProgressProps extends React.HTMLAttributes<HTMLDivElement> {
  value: number;
  indeterminate?: boolean;
}

export const Progress = React.forwardRef<HTMLDivElement, ProgressProps>(
  ({ className, value, indeterminate, ...props }, ref) => {
    const clamped = Math.max(0, Math.min(100, value));
    return (
      <div
        ref={ref}
        className={cn(
          "h-1.5 w-full overflow-hidden rounded-full bg-muted",
          className,
        )}
        {...props}
      >
        <div
          className={cn(
            "h-full gradient-bg transition-all duration-500 ease-out",
            indeterminate && "animate-shimmer",
          )}
          style={{ width: indeterminate ? "100%" : `${clamped}%` }}
        />
      </div>
    );
  },
);
Progress.displayName = "Progress";
