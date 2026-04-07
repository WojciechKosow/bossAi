import React from "react";
import { useCurrentFrame, useVideoConfig, interpolate, spring } from "remotion";
import type { SubtitleConfig, WhisperWord } from "../types/edl";

interface SubtitleTrackProps {
  config: SubtitleConfig;
  words: WhisperWord[];
}

/**
 * Groups words by sentence_index for quick lookup.
 */
function groupBySentence(words: WhisperWord[]): Map<number, WhisperWord[]> {
  const map = new Map<number, WhisperWord[]>();
  for (const w of words) {
    const idx = w.sentence_index ?? 0;
    if (!map.has(idx)) map.set(idx, []);
    map.get(idx)!.push(w);
  }
  return map;
}

/**
 * Find the active sentence index based on current time.
 * A sentence is active if currentTimeMs falls within any of its words' time range,
 * or is between the sentence's first word start and last word end.
 */
function findActiveSentenceIndex(
  sentences: Map<number, WhisperWord[]>,
  currentTimeMs: number
): number | null {
  for (const [idx, words] of sentences) {
    if (words.length === 0) continue;
    const sentenceStart = words[0].start_ms;
    const sentenceEnd = words[words.length - 1].end_ms;
    if (currentTimeMs >= sentenceStart && currentTimeMs <= sentenceEnd) {
      return idx;
    }
  }
  return null;
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

  // Group words by sentence
  const sentences = groupBySentence(words);
  const activeSentenceIdx = findActiveSentenceIndex(sentences, currentTimeMs);

  // Don't render anything if no sentence is active (gap between sentences)
  if (activeSentenceIdx === null) return null;

  const sentenceWords = sentences.get(activeSentenceIdx) ?? [];
  if (sentenceWords.length === 0) return null;

  // Calculate sentence-level entrance animation
  const sentenceStartMs = sentenceWords[0].start_ms;
  const sentenceStartFrame = (sentenceStartMs / 1000) * fps;
  const localFrame = frame - sentenceStartFrame;
  const entranceProgress = spring({
    frame: Math.max(0, localFrame),
    fps,
    config: { damping: 18, mass: 0.6 },
  });

  // Adaptive font size based on sentence length
  const totalChars = sentenceWords.reduce((sum, w) => sum + w.word.length, 0);
  const baseFontSize = config.font_size;
  const fontSize =
    totalChars <= 12
      ? baseFontSize * 1.3
      : totalChars <= 25
      ? baseFontSize * 1.1
      : totalChars <= 40
      ? baseFontSize
      : baseFontSize * 0.85;

  return (
    <div
      style={{
        ...positionStyle,
        opacity: entranceProgress,
        transform: `translateY(${(1 - entranceProgress) * 15}px)`,
      }}
    >
      <span
        style={{
          fontSize,
          fontFamily: `${config.font_family}, sans-serif`,
          fontWeight: 700,
          WebkitTextStroke: `${config.stroke_width}px ${config.stroke_color}`,
          paintOrder: "stroke fill",
          lineHeight: 1.4,
          textShadow: "0 2px 8px rgba(0, 0, 0, 0.7)",
        }}
      >
        {sentenceWords.map((w, i) => {
          const isActive =
            currentTimeMs >= w.start_ms && currentTimeMs <= w.end_ms;
          const isPast = currentTimeMs > w.end_ms;

          return (
            <span
              key={`${activeSentenceIdx}-${i}`}
              style={{
                color: isActive
                  ? config.highlight_color
                  : isPast
                  ? "#FFFFFF"
                  : "rgba(255, 255, 255, 0.5)",
                transform: isActive ? "scale(1.12)" : "scale(1)",
                display: "inline-block",
                marginRight: "0.3em",
                transition: "color 0.05s, transform 0.08s",
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
