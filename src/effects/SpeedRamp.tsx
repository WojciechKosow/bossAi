import React from "react";

// SpeedRamp is handled at the Video component level via playbackRate.
// The actual speed adjustment happens in VideoSegment when it reads
// the effect params (speed_from, speed_to).
interface SpeedRampProps {
  children: React.ReactNode;
  speedFrom?: number;
  speedTo?: number;
  easing?: string;
}

export const SpeedRamp: React.FC<SpeedRampProps> = ({ children }) => {
  return <>{children}</>;
};
