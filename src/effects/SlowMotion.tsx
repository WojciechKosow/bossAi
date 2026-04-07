import React from "react";

// SlowMotion is handled at the Video component level via playbackRate.
// This wrapper passes through children unchanged; the actual speed
// adjustment happens in VideoSegment when it reads the effect params.
interface SlowMotionProps {
  children: React.ReactNode;
  speed?: number;
}

export const SlowMotion: React.FC<SlowMotionProps> = ({ children }) => {
  return <>{children}</>;
};
