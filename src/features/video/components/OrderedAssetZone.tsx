import { useCallback, useRef, useState } from "react";
import { Reorder, useDragControls } from "framer-motion";
import {
  UploadCloud,
  GripVertical,
  X,
  Loader2,
  type LucideIcon,
} from "lucide-react";
import { useAssetBlobUrl, useDeleteAsset, useUploadAsset } from "../hooks";
import { AssetMedia } from "./AssetMedia";
import type { AssetDTO, AssetType } from "../types";
import { cn } from "@/lib/utils";

interface Props {
  /** Assets currently in this zone, in the order the user wants them used. */
  assets: AssetDTO[];
  onChange: (assets: AssetDTO[]) => void;
  /** Fixed asset type every upload in this zone is tagged with. */
  uploadType: AssetType;
  accept: string;
  icon: LucideIcon;
  dropLabel: string;
  dropHint: string;
  /** Single-slot mode (music) — no ordering, replaces the current item. */
  single?: boolean;
  maxFiles?: number;
}

export const OrderedAssetZone = ({
  assets,
  onChange,
  uploadType,
  accept,
  icon: Icon,
  dropLabel,
  dropHint,
  single = false,
  maxFiles = 12,
}: Props) => {
  const inputRef = useRef<HTMLInputElement>(null);
  const upload = useUploadAsset();
  const remove = useDeleteAsset();
  const [dragOver, setDragOver] = useState(false);
  const [pending, setPending] = useState<string[]>([]);

  const limit = single ? 1 : maxFiles;
  const full = assets.length >= limit;

  const handleFiles = useCallback(
    async (files: FileList | File[]) => {
      const room = single ? 1 : Math.max(0, limit - assets.length);
      const arr = Array.from(files).slice(0, room);
      if (!arr.length) return;

      const startOrder = single ? 0 : assets.length;
      const tempIds = arr.map((f) => f.name + Math.random());
      setPending((p) => [...p, ...tempIds]);

      const uploaded: AssetDTO[] = [];
      for (let i = 0; i < arr.length; i++) {
        try {
          uploaded.push(
            await upload.mutateAsync({
              file: arr[i],
              type: uploadType,
              orderIndex: startOrder + i,
            }),
          );
        } catch (e) {
          console.error("upload failed", e);
        }
      }

      setPending((p) => p.filter((id) => !tempIds.includes(id)));
      if (uploaded.length) {
        onChange(single ? uploaded.slice(0, 1) : [...assets, ...uploaded]);
      }
    },
    [assets, limit, onChange, single, upload, uploadType],
  );

  const onDrop = (e: React.DragEvent) => {
    e.preventDefault();
    setDragOver(false);
    if (e.dataTransfer.files.length) handleFiles(e.dataTransfer.files);
  };

  const removeAsset = async (id: string) => {
    // Deletion is a no-op during beta (backend 403s) — drop it from the
    // working set regardless so it leaves this zone and the draft.
    try {
      await remove.mutateAsync(id);
    } catch {
      /* ignore — keep the asset server-side, remove from the zone only */
    }
    onChange(assets.filter((a) => a.id !== id));
  };

  const isAudio = uploadType === "MUSIC" || uploadType === "VOICE";

  return (
    <div className="space-y-3">
      {!full && (
        <div
          onDragOver={(e) => {
            e.preventDefault();
            setDragOver(true);
          }}
          onDragLeave={() => setDragOver(false)}
          onDrop={onDrop}
          onClick={() => inputRef.current?.click()}
          className={cn(
            "relative flex items-center gap-3 rounded-xl border-2 border-dashed px-4 py-4 text-left cursor-pointer transition-all",
            dragOver
              ? "border-primary bg-accent/40"
              : "border-border hover:border-primary/40 hover:bg-muted/40",
          )}
        >
          <div className="size-10 rounded-lg bg-accent flex items-center justify-center shrink-0">
            <UploadCloud className="size-5 text-accent-foreground" />
          </div>
          <div className="min-w-0">
            <p className="text-sm font-medium">
              {dropLabel} <span className="gradient-text">browse</span>
            </p>
            <p className="text-xs text-muted-foreground mt-0.5">{dropHint}</p>
          </div>
          <input
            ref={inputRef}
            type="file"
            accept={accept}
            multiple={!single}
            className="hidden"
            onChange={(e) => e.target.files && handleFiles(e.target.files)}
          />
        </div>
      )}

      {(assets.length > 0 || pending.length > 0) && (
        <Reorder.Group
          axis="y"
          values={assets}
          onReorder={onChange}
          className="space-y-2"
        >
          {assets.map((a, i) => (
            <AssetRow
              key={a.id}
              asset={a}
              order={i + 1}
              icon={Icon}
              isAudio={isAudio}
              draggable={!single && assets.length > 1}
              onRemove={() => removeAsset(a.id)}
            />
          ))}
          {pending.map((id) => (
            <div
              key={id}
              className="flex items-center gap-3 rounded-xl border border-border bg-muted/40 px-3 py-2.5"
            >
              <Loader2 className="size-4 animate-spin text-muted-foreground" />
              <span className="text-xs text-muted-foreground">Uploading…</span>
            </div>
          ))}
        </Reorder.Group>
      )}
    </div>
  );
};

/* ---------------------------------------------------------------- */

const AssetRow = ({
  asset,
  order,
  icon: Icon,
  isAudio,
  draggable,
  onRemove,
}: {
  asset: AssetDTO;
  order: number;
  icon: LucideIcon;
  isAudio: boolean;
  draggable: boolean;
  onRemove: () => void;
}) => {
  const controls = useDragControls();
  const audioUrl = useAssetBlobUrl(isAudio ? asset.id : null);

  return (
    <Reorder.Item
      value={asset}
      dragListener={false}
      dragControls={controls}
      className="flex items-center gap-3 rounded-xl border border-border bg-card px-3 py-2.5"
    >
      {draggable ? (
        <button
          type="button"
          onPointerDown={(e) => controls.start(e)}
          className="shrink-0 cursor-grab touch-none text-muted-foreground hover:text-foreground active:cursor-grabbing"
          aria-label="Drag to reorder"
        >
          <GripVertical size={16} />
        </button>
      ) : (
        <span className="size-4 shrink-0" />
      )}

      {!isAudio && (
        <span className="grid size-6 shrink-0 place-items-center rounded-md gradient-bg text-[11px] font-semibold text-white">
          {order}
        </span>
      )}

      <div className="size-11 shrink-0 overflow-hidden rounded-lg border border-border bg-muted">
        {asset.type === "IMAGE" || asset.type === "VIDEO" ? (
          <AssetMedia
            assetId={asset.id}
            type={asset.type}
            alt={asset.originalFilename}
          />
        ) : (
          <div className="grid size-full place-items-center">
            <Icon className="size-5 text-muted-foreground" />
          </div>
        )}
      </div>

      <div className="min-w-0 flex-1">
        <p className="truncate text-sm font-medium">
          {asset.originalFilename ?? asset.type.toLowerCase()}
        </p>
        {isAudio && audioUrl && (
          <audio src={audioUrl} controls className="mt-1 h-7 w-full max-w-[240px]" />
        )}
      </div>

      <button
        type="button"
        onClick={onRemove}
        className="grid size-7 shrink-0 place-items-center rounded-full text-muted-foreground transition hover:bg-muted hover:text-foreground"
        aria-label="Remove"
      >
        <X size={14} />
      </button>
    </Reorder.Item>
  );
};
