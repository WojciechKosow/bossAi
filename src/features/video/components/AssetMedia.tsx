import { useAssetBlobUrl } from "../hooks";
import type { AssetType, UUID } from "../types";
import { cn } from "@/lib/utils";
import { Image as ImageIcon, Film, Music, Mic, Loader2 } from "lucide-react";

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

export const AssetMedia = ({
  assetId,
  type,
  className,
  preview = true,
  alt,
}: Props) => {
  const url = useAssetBlobUrl(assetId);
  const Icon = fallbackIcon[type] ?? ImageIcon;

  if (!url) {
    return (
      <div
        className={cn(
          "size-full flex items-center justify-center bg-muted/60",
          className,
        )}
      >
        {assetId ? (
          <Loader2 className="size-4 animate-spin text-muted-foreground" />
        ) : (
          <Icon className="size-5 text-muted-foreground" />
        )}
      </div>
    );
  }

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
      />
    );
  }

  if (type === "IMAGE") {
    return (
      <img
        src={url}
        alt={alt ?? ""}
        className={cn("size-full object-cover", className)}
      />
    );
  }

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
};
