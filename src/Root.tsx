import React from "react";
import { Composition } from "remotion";
import { TikTokVideo } from "./compositions/TikTokVideo";

export const RemotionRoot: React.FC = () => {
  return (
    <Composition
      id="TikTokVideo"
      component={TikTokVideo}
      durationInFrames={300}
      fps={30}
      width={1080}
      height={1920}
      defaultProps={{
        edl: {
          metadata: {
            total_duration_ms: 10000,
            width: 1080,
            height: 1920,
            fps: 30,
          },
          segments: [],
        },
      }}
    />
  );
};
