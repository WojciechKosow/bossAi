import { cn } from "@/lib/utils";

/**
 * "auto"  – dark mark on light UI, white mark in dark mode (follows the theme).
 * "dark"  – always the dark mark (for surfaces that stay light, e.g. auth card).
 * "light" – always the white mark (for surfaces that stay dark).
 */
type Tone = "auto" | "dark" | "light";

/**
 * The Toucan Motion mark, rendered as a PNG. Size it via `className`
 * (e.g. "size-9"); the image fills the box.
 */
export function ToucanLogo({
  className,
  tone = "auto",
}: {
  className?: string;
  tone?: Tone;
}) {
  const img = "h-full w-full object-contain";
  return (
    <span className={cn("inline-block shrink-0", className)}>
      {tone !== "light" && (
        <img
          src="/logo.png"
          alt="Toucan Motion"
          className={cn(img, tone === "auto" && "block dark:hidden")}
        />
      )}
      {tone !== "dark" && (
        <img
          src="/logo-white.png"
          alt="Toucan Motion"
          className={cn(img, tone === "auto" && "hidden dark:block")}
        />
      )}
    </span>
  );
}

type BrandLogoProps = {
  /** Show the "Toucan Motion" wordmark next to the mark. Defaults to true. */
  withText?: boolean;
  tone?: Tone;
  className?: string;
  iconClassName?: string;
  textClassName?: string;
};

/** Full brand lockup: the toucan mark + "Toucan Motion" wordmark. */
export function BrandLogo({
  withText = true,
  tone = "auto",
  className,
  iconClassName,
  textClassName,
}: BrandLogoProps) {
  const text =
    tone === "dark"
      ? { main: "text-gray-900", muted: "text-gray-400" }
      : tone === "light"
        ? { main: "text-white", muted: "text-white/60" }
        : { main: "text-foreground", muted: "text-muted-foreground" };

  return (
    <span className={cn("inline-flex items-center gap-2.5", className)}>
      <ToucanLogo tone={tone} className={iconClassName ?? "size-10"} />
      {withText && (
        <span
          className={cn(
            "font-bold tracking-tight leading-none whitespace-nowrap",
            text.main,
            textClassName ?? "text-[17px]",
          )}
        >
          Toucan
          <span className={cn("font-semibold", text.muted)}> Motion</span>
        </span>
      )}
    </span>
  );
}

export default BrandLogo;
