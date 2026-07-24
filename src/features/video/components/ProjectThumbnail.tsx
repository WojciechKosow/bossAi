import { useRenderStatus } from "../hooks";
import { absoluteUrl } from "../api";
import type { ProjectStatus, UUID } from "../types";
import { Loader2, Film } from "lucide-react";

interface Props {
  projectId: UUID;
  status: ProjectStatus;
}

export const ProjectThumbnail = ({ projectId, status }: Props) => {
  const enabled = status === "READY" || status === "COMPLETE" || status === "RENDERING";
  const { data } = useRenderStatus(projectId, { enabled });

  if (data?.outputUrl && data.status === "COMPLETE") {
    const src = absoluteUrl(data.outputUrl) ?? data.outputUrl;
    // Static first frame only — never autoplay/loop. A grid of playing videos
    // eats memory; we just want a picture of the (Remotion) render.
    return (
      <video
        src={`${src}#t=0.1`}
        muted
        playsInline
        preload="metadata"
        className="size-full object-cover"
      />
    );
  }

  if (status === "GENERATING" || status === "RENDERING") {
    return (
      <div className="size-full flex items-center justify-center bg-gradient-to-br from-primary/20 to-accent/40">
        <Loader2 className="size-6 text-primary animate-spin" />
      </div>
    );
  }

  return (
    <div className="size-full flex items-center justify-center bg-muted">
      <Film className="size-7 text-muted-foreground" />
    </div>
  );
};
