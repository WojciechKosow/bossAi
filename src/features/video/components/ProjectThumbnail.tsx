import { useRenderStatus } from "../hooks";
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
    return (
      <video
        src={data.outputUrl}
        muted
        loop
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
