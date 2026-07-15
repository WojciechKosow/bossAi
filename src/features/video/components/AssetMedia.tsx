import { useState } from "react";
import { assetFileUrl } from "../api";
import type { AssetType, UUID } from "../types";
import { cn } from "@/lib/utils";
import { Image as ImageIcon, Film, Music, Mic } from "lucide-react";

const fallbackIcon: Record<AssetType, typeof ImageIcon> = {
  IMAGE: ImageIcon,
  VIDEO: Film,
  MUSIC: Music,
  VOICE: Mic,
};

interface Props {
  assetId: UUID | null | undefined;
  type: AssetType;
  className?: string;
  /** when true, the rendered <video> autoplays muted (preview) */
  preview?: boolean;
  alt?: string;
}

/**
 * Renders an asset preview straight from the public `/api/assets/file/{id}`
 * endpoint (permitAll — the 122-bit UUID acts as a signed URL). We deliberately
 * do NOT download the file as a blob first: a whole-file blob XHR OOMs / fails
 * with net::ERR_FAILED on large videos and buffers everything in JS memory. A
 * direct <video src> streams via range requests instead. Matches how the
 * timeline player (EdlPlayer) already loads video.
 */
export const AssetMedia = ({
  assetId,
  type,
  className,
  preview = true,
  alt,
}: Props) => {
  const [failed, setFailed] = useState(false);
  const Icon = fallbackIcon[type] ?? ImageIcon;

  if (!assetId || failed || (type !== "IMAGE" && type !== "VIDEO")) {
    return (
      <div
        className={cn(
          "size-full flex items-center justify-center bg-muted",
          className,
        )}
      >
        <Icon className="size-5 text-muted-foreground" />
      </div>
    );
  }

  const url = assetFileUrl(assetId);

  if (type === "VIDEO") {
    return (
      <video
        src={url}
        className={cn("size-full object-cover", className)}
        muted={preview}
        autoPlay={preview}
        loop={preview}
        controls={!preview}
        playsInline
        preload="metadata"
        onError={() => setFailed(true)}
      />
    );
  }

  return (
    <img
      src={url}
      alt={alt ?? ""}
      className={cn("size-full object-cover", className)}
      onError={() => setFailed(true)}
    />
  );
};
