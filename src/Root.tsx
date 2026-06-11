import React from "react";
import { Composition } from "remotion";
import { z } from "zod";
import { TikTokVideo } from "./compositions/TikTokVideo";
import { EdlSchema } from "./types/edl";

const CompositionPropsSchema = z.object({ edl: EdlSchema });

const defaultEdl = EdlSchema.parse({
  metadata: {
    total_duration_ms: 10000,
    width: 1080,
    height: 1920,
    fps: 30,
  },
  segments: [],
});

export const RemotionRoot: React.FC = () => {
  return (
    <Composition
      id="TikTokVideo"
      component={TikTokVideo}
      schema={CompositionPropsSchema}
      durationInFrames={300}
      fps={30}
      width={1080}
      height={1920}
      defaultProps={{ edl: defaultEdl }}
    />
  );
};
