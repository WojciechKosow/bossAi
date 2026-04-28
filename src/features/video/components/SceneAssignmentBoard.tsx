import { useMemo, useState } from "react";
import { motion, AnimatePresence } from "framer-motion";
import { Image as ImageIcon, Film, GripVertical, Sparkles } from "lucide-react";
import type { AssetDTO, PromptAnalysisResponse, UUID } from "../types";
import { AssetMedia } from "./AssetMedia";
import { cn } from "@/lib/utils";

const formatDuration = (ms: number) => {
  const s = ms / 1000;
  return s >= 1 ? `${s.toFixed(1)}s` : `${ms}ms`;
};

interface Props {
  analysis: PromptAnalysisResponse;
  assets: AssetDTO[];
  assignments: Map<number, UUID>;
  onChange: (next: Map<number, UUID>) => void;
}

export const SceneAssignmentBoard = ({
  analysis,
  assets,
  assignments,
  onChange,
}: Props) => {
  const [dragAssetId, setDragAssetId] = useState<UUID | null>(null);
  const [dragOverScene, setDragOverScene] = useState<number | null>(null);

  const assetById = useMemo(() => {
    const m = new Map<UUID, AssetDTO>();
    assets.forEach((a) => m.set(a.id, a));
    return m;
  }, [assets]);

  const usedAssetIds = useMemo(
    () => new Set(Array.from(assignments.values())),
    [assignments],
  );

  const drop = (sceneIndex: number) => {
    if (!dragAssetId) return;
    const next = new Map(assignments);

    for (const [k, v] of next) {
      if (v === dragAssetId) next.delete(k);
    }
    next.set(sceneIndex, dragAssetId);
    onChange(next);
    setDragAssetId(null);
    setDragOverScene(null);
  };

  const clearScene = (sceneIndex: number) => {
    const next = new Map(assignments);
    next.delete(sceneIndex);
    onChange(next);
  };

  return (
    <div className="grid grid-cols-1 lg:grid-cols-[1fr_320px] gap-6">
      {/* Scenes column */}
      <div className="space-y-3">
        <div className="flex items-center justify-between">
          <div>
            <h3 className="text-sm font-semibold">Scenes</h3>
            <p className="text-xs text-muted-foreground">
              Drag your assets onto scenes to override the AI suggestion.
            </p>
          </div>
          <span className="text-xs text-muted-foreground">
            {analysis.scenes.length} scenes ·{" "}
            {(analysis.totalDurationMs / 1000).toFixed(1)}s
          </span>
        </div>
        <div className="space-y-2">
          {analysis.scenes.map((scene) => {
            const assetId =
              assignments.get(scene.index) ?? scene.suggestedAssetId ?? null;
            const asset = assetId ? assetById.get(assetId) : undefined;
            const isOver = dragOverScene === scene.index;
            return (
              <motion.div
                layout
                key={scene.index}
                onDragOver={(e) => {
                  e.preventDefault();
                  setDragOverScene(scene.index);
                }}
                onDragLeave={() => setDragOverScene(null)}
                onDrop={() => drop(scene.index)}
                className={cn(
                  "rounded-xl border bg-card p-3 flex gap-3 items-stretch transition-all",
                  isOver
                    ? "border-primary shadow-glow scale-[1.005]"
                    : "border-border",
                )}
              >
                <div className="w-12 flex flex-col items-center justify-center text-center">
                  <span className="text-[10px] text-muted-foreground">
                    SCENE
                  </span>
                  <span className="text-lg font-semibold tabular-nums">
                    {String(scene.index + 1).padStart(2, "0")}
                  </span>
                </div>

                <div className="w-24 sm:w-28 aspect-[9/16] rounded-lg overflow-hidden bg-muted border border-border flex items-center justify-center">
                  {asset ? (
                    <AssetMedia
                      assetId={asset.id}
                      type={asset.type}
                      alt={asset.originalFilename}
                    />
                  ) : (
                    <div className="text-[10px] text-muted-foreground p-2 text-center">
                      <Sparkles size={14} className="mx-auto mb-1" />
                      AI-generated
                    </div>
                  )}
                </div>

                <div className="flex-1 min-w-0">
                  <div className="flex items-center gap-2 flex-wrap">
                    {scene.suggestedRole && (
                      <span className="text-[10px] font-medium uppercase tracking-wider text-primary bg-accent px-2 py-0.5 rounded-full">
                        {scene.suggestedRole}
                      </span>
                    )}
                    {scene.suggestedMood && (
                      <span className="text-[10px] text-muted-foreground">
                        · {scene.suggestedMood}
                      </span>
                    )}
                    <span className="text-[10px] text-muted-foreground ml-auto">
                      {formatDuration(scene.durationMs)}
                    </span>
                  </div>
                  {scene.subtitleText && (
                    <p className="text-sm font-medium mt-1.5 line-clamp-2">
                      “{scene.subtitleText}”
                    </p>
                  )}
                  <p className="text-xs text-muted-foreground mt-1 line-clamp-2">
                    {scene.imagePrompt}
                  </p>
                  <div className="flex items-center gap-2 mt-2">
                    {asset ? (
                      <>
                        <span className="text-[11px] text-muted-foreground truncate">
                          Using: {asset.originalFilename}
                        </span>
                        <button
                          type="button"
                          onClick={() => clearScene(scene.index)}
                          className="text-[11px] text-muted-foreground hover:text-destructive transition"
                        >
                          unassign
                        </button>
                      </>
                    ) : (
                      <span className="text-[11px] text-muted-foreground">
                        AI will generate this scene
                      </span>
                    )}
                  </div>
                </div>
              </motion.div>
            );
          })}
        </div>
      </div>

      {/* Asset palette */}
      <div className="space-y-3 lg:sticky lg:top-4 self-start">
        <div>
          <h3 className="text-sm font-semibold">Your assets</h3>
          <p className="text-xs text-muted-foreground">
            Drag to a scene. Unused assets fill leftover slots automatically.
          </p>
        </div>
        <div className="rounded-xl border border-border bg-card p-2 grid grid-cols-3 gap-2 max-h-[480px] overflow-y-auto scrollbar-thin">
          <AnimatePresence>
            {assets.map((a) => {
              const used = usedAssetIds.has(a.id);
              return (
                <motion.div
                  layout
                  key={a.id}
                  draggable
                  onDragStart={() => setDragAssetId(a.id)}
                  onDragEnd={() => setDragAssetId(null)}
                  className={cn(
                    "relative aspect-square rounded-lg overflow-hidden border cursor-grab active:cursor-grabbing transition-all",
                    used
                      ? "border-primary opacity-60"
                      : "border-border hover:border-primary/40",
                    dragAssetId === a.id && "scale-95 opacity-80",
                  )}
                >
                  <AssetMedia
                    assetId={a.id}
                    type={a.type}
                    alt={a.originalFilename}
                  />

                  <div className="absolute top-1 left-1 bg-black/60 text-white rounded p-0.5">
                    <GripVertical size={10} />
                  </div>
                  <div className="absolute top-1 right-1 text-[10px] bg-black/60 text-white rounded px-1.5 py-0.5">
                    {a.type === "VIDEO" ? (
                      <Film size={10} />
                    ) : (
                      <ImageIcon size={10} />
                    )}
                  </div>
                  {used && (
                    <div className="absolute inset-0 bg-primary/15 flex items-center justify-center">
                      <span className="text-[10px] font-medium text-primary bg-background/90 rounded px-1.5 py-0.5">
                        in use
                      </span>
                    </div>
                  )}
                </motion.div>
              );
            })}
          </AnimatePresence>
        </div>
      </div>
    </div>
  );
};
