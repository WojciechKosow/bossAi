import React from "react";
import { useCurrentFrame, useVideoConfig, interpolate, spring } from "remotion";
import type { SubtitleConfig, WhisperWord } from "../types/edl";

interface SubtitleTrackProps {
  config: SubtitleConfig;
  words: WhisperWord[];
}

// Default color palette if highlight_colors not provided — warm, TikTok-style
const DEFAULT_PALETTE = ["#FFD700", "#FF6B6B", "#4ECDC4", "#45B7D1", "#F7DC6F"];

/**
 * Anticipation offset — highlight leads the voice slightly
 * to prevent perceived subtitle lag.
 */
const ANTICIPATION_MS = 30;

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
 * Uses look-ahead so the sentence appears slightly before
 * the first word starts speaking.
 */
function findActiveSentenceIndex(
  sentences: Map<number, WhisperWord[]>,
  currentTimeMs: number
): number | null {
  const LOOK_AHEAD_MS = 80;

  for (const [idx, words] of sentences) {
    if (words.length === 0) continue;
    const sentenceStart = words[0].start_ms - LOOK_AHEAD_MS;
    const sentenceEnd = words[words.length - 1].end_ms;
    if (currentTimeMs >= sentenceStart && currentTimeMs <= sentenceEnd) {
      return idx;
    }
  }
  return null;
}

/**
 * Pick highlight color for a given sentence index.
 * Rotates through the palette so each sentence gets a different color.
 */
function getHighlightColor(
  config: SubtitleConfig,
  sentenceIndex: number
): string {
  const palette =
    config.highlight_colors && config.highlight_colors.length > 0
      ? config.highlight_colors
      : config.highlight_color
        ? [config.highlight_color]
        : DEFAULT_PALETTE;

  if (palette.length === 1) return palette[0];
  return palette[sentenceIndex % palette.length];
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

/**
 * Word-by-word karaoke subtitle component.
 *
 * Renders the current sentence group on screen and highlights each word
 * individually as it's spoken — synced via Whisper word-level timestamps.
 *
 * Visual states for each word:
 *   - UPCOMING: dim white (waiting to be spoken)
 *   - ACTIVE:   highlight color + scale pulse + glow (currently being spoken)
 *   - PAST:     bright white (already spoken, clearly visible)
 *
 * highlight_mode:
 *   - "word" (default): per-word karaoke highlighting
 *   - "sentence": all words in the sentence highlighted at once (legacy)
 */
export const SubtitleTrack: React.FC<SubtitleTrackProps> = ({
  config,
  words,
}) => {
  const frame = useCurrentFrame();
  const { fps } = useVideoConfig();

  if (!config.enabled || words.length === 0) return null;

  const currentTimeMs = (frame / fps) * 1000;
  const positionStyle = getSubtitlePositionStyle(config.position);
  const highlightMode = config.highlight_mode ?? "word";

  // Group words by sentence
  const sentences = groupBySentence(words);
  const activeSentenceIdx = findActiveSentenceIndex(sentences, currentTimeMs);

  if (activeSentenceIdx === null) return null;

  const sentenceWords = sentences.get(activeSentenceIdx) ?? [];
  if (sentenceWords.length === 0) return null;

  // Pick highlight color for this sentence
  const highlightColor = getHighlightColor(config, activeSentenceIdx);

  // Sentence entrance animation (spring pop-in)
  const sentenceStartMs = sentenceWords[0].start_ms;
  const sentenceStartFrame = (sentenceStartMs / 1000) * fps;
  const localFrame = frame - sentenceStartFrame;
  const entranceProgress = spring({
    frame: Math.max(0, Math.round(localFrame)),
    fps,
    config: { damping: 18, mass: 0.6 },
  });

  // Adaptive font size based on total characters in sentence
  const totalChars = sentenceWords.reduce((sum, w) => sum + w.word.length, 0);
  const baseFontSize = config.font_size;
  const fontSize =
    totalChars <= 10
      ? baseFontSize * 1.4
      : totalChars <= 18
        ? baseFontSize * 1.2
        : totalChars <= 30
          ? baseFontSize * 1.0
          : baseFontSize * 0.85;

  return (
    <div
      style={{
        ...positionStyle,
        opacity: interpolate(entranceProgress, [0, 1], [0, 1]),
        transform: `translateY(${(1 - entranceProgress) * 12}px)`,
      }}
    >
      <span
        style={{
          fontSize,
          fontFamily: `${config.font_family}, sans-serif`,
          fontWeight: 800,
          WebkitTextStroke: `${config.stroke_width}px ${config.stroke_color}`,
          paintOrder: "stroke fill",
          lineHeight: 1.4,
          textShadow: "0 2px 10px rgba(0, 0, 0, 0.8)",
        }}
      >
        {sentenceWords.map((w, i) => {
          if (highlightMode === "sentence") {
            // Legacy mode: all words highlighted in the sentence color
            return (
              <span
                key={`${activeSentenceIdx}-${i}`}
                style={{
                  color: highlightColor,
                  display: "inline-block",
                  marginRight: "0.3em",
                }}
              >
                {w.word}
              </span>
            );
          }

          // --- Word-by-word karaoke mode ---
          const wordStartAdj = w.start_ms - ANTICIPATION_MS;
          const isActive =
            currentTimeMs >= wordStartAdj && currentTimeMs <= w.end_ms;
          const isPast = currentTimeMs > w.end_ms;
          const isUpcoming = !isActive && !isPast;

          // Active word: smooth scale pulse
          let wordScale = 1;
          let glowShadow = "";

          if (isActive) {
            const wordDurationMs = w.end_ms - wordStartAdj;
            const wordProgress = Math.min(
              (currentTimeMs - wordStartAdj) / Math.max(wordDurationMs, 1),
              1
            );

            // Smooth scale: quick up → hold → ease out
            if (wordProgress < 0.12) {
              wordScale = interpolate(wordProgress, [0, 0.12], [1, 1.18], {
                extrapolateRight: "clamp",
              });
            } else if (wordProgress > 0.8) {
              wordScale = interpolate(wordProgress, [0.8, 1], [1.18, 1.0], {
                extrapolateLeft: "clamp",
              });
            } else {
              wordScale = 1.18;
            }

            // Glow effect on active word
            glowShadow = `0 0 20px ${highlightColor}80, 0 0 40px ${highlightColor}40`;
          }

          // Past word: subtle scale-down for visual rhythm
          if (isPast) {
            wordScale = 1.0;
          }

          // Color: active = highlight, past = white, upcoming = dim
          const wordColor = isActive
            ? highlightColor
            : isPast
              ? "#FFFFFF"
              : "rgba(255, 255, 255, 0.35)";

          // Opacity: upcoming words are dimmer to create focus on the active word
          const wordOpacity = isUpcoming ? 0.5 : 1.0;

          return (
            <span
              key={`${activeSentenceIdx}-${i}`}
              style={{
                color: wordColor,
                opacity: wordOpacity,
                transform: `scale(${wordScale})`,
                display: "inline-block",
                marginRight: "0.3em",
                textShadow: glowShadow
                  ? `${glowShadow}, 0 2px 10px rgba(0,0,0,0.8)`
                  : undefined,
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
