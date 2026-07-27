import { useId } from "react";
import { cn } from "@/lib/utils";

/**
 * The Toucan Motion mark. Single-color silhouette (uses `currentColor`) with
 * the eye, open beak and wing feathers punched out via a mask, so it reads on
 * any background — dark on light, white on the brand gradient, etc.
 */
export function ToucanMark({ className }: { className?: string }) {
  const maskId = useId();
  return (
    <svg
      viewBox="0 0 512 512"
      className={className}
      fill="currentColor"
      role="img"
      aria-label="Toucan Motion"
    >
      <defs>
        <mask id={maskId} maskUnits="userSpaceOnUse" x="0" y="0" width="512" height="512">
          <rect width="512" height="512" fill="#fff" />
          {/* eye: transparent ring, filled pupil, transparent catchlight */}
          <circle cx="186" cy="172" r="38" fill="#000" />
          <circle cx="192" cy="169" r="18" fill="#fff" />
          <circle cx="181" cy="159" r="6" fill="#000" />
          {/* open beak */}
          <path d="M312 190 C 392 210 460 244 492 290 C 456 256 386 228 316 222 Z" fill="#000" />
          {/* wing feathers */}
          <path d="M268 292 C 200 308 142 340 92 388" fill="none" stroke="#000" strokeWidth="11" strokeLinecap="round" />
          <path d="M262 336 C 208 352 162 378 120 416" fill="none" stroke="#000" strokeWidth="11" strokeLinecap="round" />
          <path d="M212 400 C 186 410 162 424 138 442" fill="none" stroke="#000" strokeWidth="10" strokeLinecap="round" />
        </mask>
      </defs>
      <g mask={`url(#${maskId})`}>
        <path d="M62 198 C 62 114 122 64 202 64 C 254 64 292 92 300 146 C 306 206 306 248 300 286 C 296 348 248 430 172 432 C 116 434 66 394 54 324 C 47 284 51 240 62 198 Z" />
        <path d="M286 108 C 400 78 488 140 512 230 C 519 256 512 284 494 300 C 480 309 462 311 448 307 C 392 313 330 307 300 296 C 300 240 293 166 286 108 Z" />
        <path d="M148 290 C 100 296 64 326 42 360 C 40 366 44 369 51 366 C 78 360 106 364 130 378 C 151 349 166 318 148 290 Z" />
      </g>
    </svg>
  );
}

/** The gradient app-icon chip with the white toucan inside. */
export function LogoChip({ className }: { className?: string }) {
  return (
    <span
      className={cn(
        "inline-flex items-center justify-center rounded-lg gradient-bg shadow-glow",
        className ?? "size-8",
      )}
    >
      <ToucanMark className="h-[62%] w-[62%] text-white" />
    </span>
  );
}

type BrandLogoProps = {
  /** Show the "Toucan Motion" wordmark next to the chip. Defaults to true. */
  withText?: boolean;
  className?: string;
  chipClassName?: string;
  textClassName?: string;
};

/** Full brand lockup: gradient chip + "Toucan Motion" wordmark. */
export function BrandLogo({
  withText = true,
  className,
  chipClassName,
  textClassName,
}: BrandLogoProps) {
  return (
    <span className={cn("inline-flex items-center gap-2.5", className)}>
      <LogoChip className={chipClassName} />
      {withText && (
        <span
          className={cn(
            "font-bold tracking-tight leading-none text-foreground whitespace-nowrap",
            textClassName ?? "text-[17px]",
          )}
        >
          Toucan
          <span className="font-semibold text-muted-foreground"> Motion</span>
        </span>
      )}
    </span>
  );
}

export default BrandLogo;
