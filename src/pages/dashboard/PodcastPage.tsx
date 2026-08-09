import { useMemo, useRef, useState } from "react";
import {
  AlertCircle,
  Download,
  Loader2,
  Mic,
  Scissors,
  Sparkles,
  Upload,
} from "lucide-react";
import { Button } from "@/components/ui/button";
import {
  Card,
  CardContent,
  CardDescription,
  CardHeader,
  CardTitle,
} from "@/components/ui/card";
import { Badge } from "@/components/ui/badge";
import { Progress } from "@/components/ui/progress";
import { absoluteUrl, downloadFile } from "@/features/video/api";
import type { UUID } from "@/features/video/types";
import {
  useGenerationStatus,
  usePodcastClips,
  useStartPodcastClips,
} from "@/features/podcast/hooks";
import type { ClipDto, ClipStatus } from "@/features/podcast/types";

const CLIP_COUNT_OPTIONS = [3, 5, 7, 10];
const LAST_GEN_KEY = "podcast:lastGenerationId";

const formatMs = (ms: number): string => {
  const totalSeconds = Math.max(0, Math.round(ms / 1000));
  const m = Math.floor(totalSeconds / 60);
  const s = totalSeconds % 60;
  return `${m}:${String(s).padStart(2, "0")}`;
};

const formatBytes = (bytes: number): string => {
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(0)} KB`;
  if (bytes < 1024 * 1024 * 1024) return `${(bytes / 1024 / 1024).toFixed(1) } MB`;
  return `${(bytes / 1024 / 1024 / 1024).toFixed(2)} GB`;
};

const statusVariant: Record<
  ClipStatus,
  "secondary" | "warning" | "success" | "destructive"
> = {
  PENDING: "secondary",
  RENDERING: "warning",
  READY: "success",
  FAILED: "destructive",
};

export default function PodcastPage() {
  const [generationId, setGenerationId] = useState<UUID | null>(
    () => (localStorage.getItem(LAST_GEN_KEY) as UUID | null) ?? null,
  );
  const [file, setFile] = useState<File | null>(null);
  const [clipCount, setClipCount] = useState(5);
  const [uploadPercent, setUploadPercent] = useState(0);
  const [error, setError] = useState<string | null>(null);
  const fileInputRef = useRef<HTMLInputElement>(null);

  const start = useStartPodcastClips();
  const { data: generation } = useGenerationStatus(generationId);
  const active =
    generation?.status === "PENDING" || generation?.status === "PROCESSING";
  const { data: clips } = usePodcastClips(generationId, !!active);

  const sortedClips = useMemo(
    () => (clips ? [...clips].sort((a, b) => a.clipIndex - b.clipIndex) : []),
    [clips],
  );

  const onSubmit = async () => {
    if (!file) return;
    setError(null);
    setUploadPercent(0);
    try {
      const res = await start.mutateAsync({
        file,
        clipCount,
        onProgress: setUploadPercent,
      });
      setGenerationId(res.generationId);
      localStorage.setItem(LAST_GEN_KEY, res.generationId);
    } catch (e: unknown) {
      setError(readError(e));
    }
  };

  const reset = () => {
    setGenerationId(null);
    setFile(null);
    setUploadPercent(0);
    setError(null);
    localStorage.removeItem(LAST_GEN_KEY);
  };

  const uploading = start.isPending;

  return (
    <div className="mx-auto max-w-5xl space-y-6">
      <header className="space-y-1">
        <h1 className="flex items-center gap-2 text-2xl font-bold">
          <Mic className="size-6" /> Podcast clips
        </h1>
        <p className="text-sm text-muted-foreground">
          Drop a full episode. Toucan finds the best moments and cuts them into
          vertical clips with captions.
        </p>
      </header>

      {!generationId && (
        <Card>
          <CardHeader>
            <CardTitle>New episode</CardTitle>
            <CardDescription>
              MP4, MOV, MP3 or WAV. A 2-hour file is fine.
            </CardDescription>
          </CardHeader>
          <CardContent className="space-y-5">
            <input
              ref={fileInputRef}
              type="file"
              accept="video/*,audio/*"
              className="hidden"
              onChange={(e) => {
                setFile(e.target.files?.[0] ?? null);
                setError(null);
              }}
            />

            <button
              type="button"
              onClick={() => fileInputRef.current?.click()}
              className="flex w-full flex-col items-center justify-center gap-2 rounded-xl border border-dashed border-border bg-muted/30 px-6 py-10 text-center transition hover:bg-muted/50"
            >
              <Upload className="size-6 text-muted-foreground" />
              {file ? (
                <div>
                  <p className="text-sm font-medium">{file.name}</p>
                  <p className="text-xs text-muted-foreground">
                    {formatBytes(file.size)}
                  </p>
                </div>
              ) : (
                <p className="text-sm text-muted-foreground">
                  Click to choose an episode file
                </p>
              )}
            </button>

            <div className="space-y-2">
              <p className="text-sm font-medium">How many clips?</p>
              <div className="flex gap-2">
                {CLIP_COUNT_OPTIONS.map((n) => (
                  <button
                    key={n}
                    type="button"
                    onClick={() => setClipCount(n)}
                    className={
                      "rounded-lg border px-4 py-2 text-sm transition " +
                      (clipCount === n
                        ? "border-transparent gradient-bg text-white"
                        : "border-border text-muted-foreground hover:bg-muted")
                    }
                  >
                    {n}
                  </button>
                ))}
              </div>
            </div>

            {uploading && (
              <div className="space-y-1">
                <Progress value={uploadPercent} />
                <p className="text-xs text-muted-foreground">
                  Uploading… {uploadPercent}%
                </p>
              </div>
            )}

            {error && (
              <p className="flex items-center gap-2 text-sm text-destructive">
                <AlertCircle className="size-4" /> {error}
              </p>
            )}

            <Button
              onClick={onSubmit}
              disabled={!file || uploading}
              className="w-full"
            >
              {uploading ? (
                <>
                  <Loader2 className="mr-2 size-4 animate-spin" /> Starting…
                </>
              ) : (
                <>
                  <Sparkles className="mr-2 size-4" /> Generate {clipCount} clips
                </>
              )}
            </Button>
          </CardContent>
        </Card>
      )}

      {generationId && (
        <div className="space-y-5">
          <StatusBanner
            active={!!active}
            failed={generation?.status === "FAILED"}
            clipCount={sortedClips.length}
          />

          {sortedClips.length > 0 && (
            <div className="grid gap-4 sm:grid-cols-2">
              {sortedClips.map((clip) => (
                <ClipCard key={clip.id} clip={clip} />
              ))}
            </div>
          )}

          {!active && (
            <Button variant="outline" onClick={reset}>
              Start another episode
            </Button>
          )}
        </div>
      )}
    </div>
  );
}

function StatusBanner({
  active,
  failed,
  clipCount,
}: {
  active: boolean;
  failed: boolean;
  clipCount: number;
}) {
  if (failed) {
    return (
      <Card className="border-destructive/40">
        <CardContent className="flex items-center gap-3 py-4 text-sm text-destructive">
          <AlertCircle className="size-5" />
          Generation failed. Your credits were refunded — try another file.
        </CardContent>
      </Card>
    );
  }
  if (active) {
    return (
      <Card>
        <CardContent className="space-y-2 py-4">
          <div className="flex items-center gap-3 text-sm font-medium">
            <Loader2 className="size-4 animate-spin" />
            {clipCount > 0
              ? `Cutting clips… ${clipCount} found so far`
              : "Toucan is watching your episode…"}
          </div>
          <Progress value={100} indeterminate />
          <p className="text-xs text-muted-foreground">
            Transcribing, finding the best moments, and rendering. This can take
            a while for a long episode — you can leave this page and come back.
          </p>
        </CardContent>
      </Card>
    );
  }
  return (
    <Card className="border-emerald-500/30">
      <CardContent className="flex items-center gap-3 py-4 text-sm">
        <Scissors className="size-5 text-emerald-500" />
        Done — {clipCount} clip{clipCount === 1 ? "" : "s"} ready.
      </CardContent>
    </Card>
  );
}

function ClipCard({ clip }: { clip: ClipDto }) {
  const ready = clip.status === "READY" && clip.downloadUrl;
  const src = ready ? absoluteUrl(clip.downloadUrl) : undefined;

  const onDownload = () => {
    if (!clip.downloadUrl) return;
    const name = (clip.title || `clip-${clip.clipIndex + 1}`)
      .replace(/[^\w\-]+/g, "_")
      .slice(0, 60);
    void downloadFile(absoluteUrl(clip.downloadUrl)!, `${name}.mp4`);
  };

  return (
    <Card className="overflow-hidden">
      <CardHeader className="pb-3">
        <div className="flex items-start justify-between gap-2">
          <CardTitle className="text-base">
            {clip.title || `Clip ${clip.clipIndex + 1}`}
          </CardTitle>
          <Badge variant={statusVariant[clip.status]}>{clip.status}</Badge>
        </div>
        <CardDescription>
          {formatMs(clip.durationMs)} · from {formatMs(clip.sourceStartMs)}–
          {formatMs(clip.sourceEndMs)}
        </CardDescription>
      </CardHeader>
      <CardContent className="space-y-3">
        {ready && src ? (
          <video
            src={src}
            controls
            className="aspect-[9/16] w-full max-h-96 rounded-lg bg-black object-contain"
          />
        ) : (
          <div className="flex aspect-[9/16] max-h-96 w-full items-center justify-center rounded-lg bg-muted">
            {clip.status === "FAILED" ? (
              <span className="flex items-center gap-2 text-sm text-destructive">
                <AlertCircle className="size-4" /> Failed
              </span>
            ) : (
              <Loader2 className="size-6 animate-spin text-muted-foreground" />
            )}
          </div>
        )}

        {clip.reasoning && (
          <p className="text-xs text-muted-foreground">{clip.reasoning}</p>
        )}

        {ready && (
          <Button variant="outline" className="w-full" onClick={onDownload}>
            <Download className="mr-2 size-4" /> Download
          </Button>
        )}
      </CardContent>
    </Card>
  );
}

function readError(e: unknown): string {
  const err = e as {
    response?: { data?: { message?: string; error?: string } };
    message?: string;
  };
  return (
    err?.response?.data?.message ||
    err?.response?.data?.error ||
    err?.message ||
    "Something went wrong"
  );
}
