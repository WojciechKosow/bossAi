import React from "react";
import { useCurrentFrame, useVideoConfig } from "remotion";
import type { SubtitleConfig, WhisperWord } from "../types/edl";

interface SubtitleTrackProps {
  config: SubtitleConfig;
  words: WhisperWord[];
}

function getSubtitlePositionStyle(position: string): React.CSSProperties {
  const base: React.CSSProperties = {
    position: "absolute",
    left: 0,
    right: 0,
    display: "flex",
    justifyContent: "center",
    textAlign: "center",
    padding: "0 40px",
    flexWrap: "wrap",
  };

  switch (position) {
    case "top":
      return { ...base, top: "5%" };
    case "top_third":
      return { ...base, top: "20%" };
    case "center":
      return { ...base, top: "50%", transform: "translateY(-50%)" };
    case "bottom_third":
      return { ...base, bottom: "20%" };
    case "bottom":
      return { ...base, bottom: "5%" };
    default:
      return { ...base, bottom: "20%" };
  }
}

export const SubtitleTrack: React.FC<SubtitleTrackProps> = ({
  config,
  words,
}) => {
  const frame = useCurrentFrame();
  const { fps } = useVideoConfig();

  if (!config.enabled || words.length === 0) return null;

  const currentTimeMs = (frame / fps) * 1000;
  const positionStyle = getSubtitlePositionStyle(config.position);

  return (
    <div style={positionStyle}>
      <span
        style={{
          fontSize: config.font_size,
          fontFamily: `${config.font_family}, sans-serif`,
          fontWeight: 700,
          WebkitTextStroke: `${config.stroke_width}px ${config.stroke_color}`,
          paintOrder: "stroke fill",
          lineHeight: 1.4,
        }}
      >
        {words.map((w, i) => {
          const isActive = currentTimeMs >= w.start_ms && currentTimeMs <= w.end_ms;
          const isPast = currentTimeMs > w.end_ms;

          return (
            <span
              key={i}
              style={{
                color: isActive
                  ? config.highlight_color
                  : isPast
                  ? "#FFFFFF"
                  : "rgba(255, 255, 255, 0.5)",
                transform: isActive ? "scale(1.1)" : "scale(1)",
                display: "inline-block",
                marginRight: "0.25em",
              }}
            >
              {w.word}
            </span>
          );
        })}
      </span>
    </div>
  );
};
