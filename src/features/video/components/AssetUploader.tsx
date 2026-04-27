import { useCallback, useRef, useState } from "react";
import { motion, AnimatePresence } from "framer-motion";
import { UploadCloud, ImageIcon, Film, Music, Mic, X, Loader2 } from "lucide-react";
import { useDeleteAsset, useUploadAsset } from "../hooks";
import { assetFileUrl } from "../api";
import type { AssetDTO, AssetType } from "../types";
import { cn } from "@/lib/utils";

const typeIcon: Record<AssetType, typeof ImageIcon> = {
  IMAGE: ImageIcon,
  VIDEO: Film,
  MUSIC: Music,
  VOICE: Mic,
};

const detectType = (file: File): AssetType => {
  if (file.type.startsWith("image/")) return "IMAGE";
  if (file.type.startsWith("video/")) return "VIDEO";
  if (file.type.startsWith("audio/")) return "MUSIC";
  return "IMAGE";
};

interface Props {
  assets: AssetDTO[];
  onChange: (assets: AssetDTO[]) => void;
  accept?: string;
  className?: string;
  maxFiles?: number;
}

export const AssetUploader = ({
  assets,
  onChange,
  accept = "image/*,video/*",
  className,
  maxFiles = 12,
}: Props) => {
  const inputRef = useRef<HTMLInputElement>(null);
  const upload = useUploadAsset();
  const remove = useDeleteAsset();
  const [dragOver, setDragOver] = useState(false);
  const [pending, setPending] = useState<string[]>([]);

  const handleFiles = useCallback(
    async (files: FileList | File[]) => {
      const arr = Array.from(files).slice(0, maxFiles - assets.length);
      const startOrder = assets.length;
      const tempIds = arr.map((f) => f.name + Math.random());
      setPending((p) => [...p, ...tempIds]);

      const uploaded: AssetDTO[] = [];
      for (let i = 0; i < arr.length; i++) {
        const file = arr[i];
        try {
          const result = await upload.mutateAsync({
            file,
            type: detectType(file),
            orderIndex: startOrder + i,
          });
          uploaded.push(result);
        } catch (e) {
          console.error("upload failed", e);
        }
      }

      setPending((p) => p.filter((id) => !tempIds.includes(id)));
      if (uploaded.length) onChange([...assets, ...uploaded]);
    },
    [assets, maxFiles, onChange, upload],
  );

  const onDrop = (e: React.DragEvent) => {
    e.preventDefault();
    setDragOver(false);
    if (e.dataTransfer.files.length) handleFiles(e.dataTransfer.files);
  };

  const removeAsset = async (id: string) => {
    try {
      await remove.mutateAsync(id);
    } catch {}
    onChange(assets.filter((a) => a.id !== id));
  };

  return (
    <div className={cn("space-y-4", className)}>
      <div
        onDragOver={(e) => {
          e.preventDefault();
          setDragOver(true);
        }}
        onDragLeave={() => setDragOver(false)}
        onDrop={onDrop}
        onClick={() => inputRef.current?.click()}
        className={cn(
          "relative flex flex-col items-center justify-center gap-3 rounded-xl border-2 border-dashed px-6 py-10 text-center cursor-pointer transition-all",
          dragOver
            ? "border-primary bg-accent/40 scale-[1.01]"
            : "border-border hover:border-primary/40 hover:bg-muted/40",
        )}
      >
        <div className="size-12 rounded-full gradient-bg/10 flex items-center justify-center bg-accent">
          <UploadCloud className="size-5 text-accent-foreground" />
        </div>
        <div>
          <p className="text-sm font-medium">
            Drop your media or <span className="gradient-text">browse</span>
          </p>
          <p className="text-xs text-muted-foreground mt-1">
            Images & videos up to {maxFiles} files
          </p>
        </div>
        <input
          ref={inputRef}
          type="file"
          accept={accept}
          multiple
          className="hidden"
          onChange={(e) => e.target.files && handleFiles(e.target.files)}
        />
      </div>

      <AnimatePresence>
        {(assets.length > 0 || pending.length > 0) && (
          <motion.div
            initial={{ opacity: 0 }}
            animate={{ opacity: 1 }}
            exit={{ opacity: 0 }}
            className="grid grid-cols-3 sm:grid-cols-4 md:grid-cols-6 gap-3"
          >
            {assets.map((a) => {
              const Icon = typeIcon[a.type];
              return (
                <motion.div
                  layout
                  key={a.id}
                  className="group relative aspect-square rounded-lg overflow-hidden border border-border bg-muted"
                  initial={{ opacity: 0, scale: 0.96 }}
                  animate={{ opacity: 1, scale: 1 }}
                  transition={{ duration: 0.2 }}
                >
                  {a.type === "IMAGE" ? (
                    <img
                      src={assetFileUrl(a.id)}
                      alt={a.originalFilename}
                      className="size-full object-cover"
                    />
                  ) : a.type === "VIDEO" ? (
                    <video
                      src={assetFileUrl(a.id)}
                      className="size-full object-cover"
                      muted
                      playsInline
                    />
                  ) : (
                    <div className="size-full flex items-center justify-center">
                      <Icon className="size-6 text-muted-foreground" />
                    </div>
                  )}
                  <button
                    onClick={(e) => {
                      e.stopPropagation();
                      removeAsset(a.id);
                    }}
                    className="absolute top-1.5 right-1.5 size-6 rounded-full bg-black/60 text-white opacity-0 group-hover:opacity-100 transition flex items-center justify-center hover:bg-black/80"
                  >
                    <X size={12} />
                  </button>
                  <div className="absolute bottom-0 inset-x-0 px-2 py-1 bg-gradient-to-t from-black/70 to-transparent">
                    <p className="text-[10px] text-white/80 truncate">
                      {a.originalFilename}
                    </p>
                  </div>
                </motion.div>
              );
            })}
            {pending.map((id) => (
              <div
                key={id}
                className="aspect-square rounded-lg border border-border bg-muted/40 flex items-center justify-center"
              >
                <Loader2 className="size-4 animate-spin text-muted-foreground" />
              </div>
            ))}
          </motion.div>
        )}
      </AnimatePresence>
    </div>
  );
};
