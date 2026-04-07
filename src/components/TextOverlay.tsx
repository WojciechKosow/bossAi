import React from "react";
import { useCurrentFrame, useVideoConfig, interpolate, spring } from "remotion";
import type { TextOverlay, TextStyle, TextPosition } from "../types/edl";

interface TextOverlayProps {
  overlay: TextOverlay;
}

function getPositionStyle(position?: TextPosition): React.CSSProperties {
  const pos = position ?? { x: "center", y: "75%", max_width: "85%", text_align: "center" };

  const style: React.CSSProperties = {
    position: "absolute",
    display: "flex",
    justifyContent: "center",
    maxWidth: pos.max_width,
    textAlign: pos.text_align as React.CSSProperties["textAlign"],
  };

  // Horizontal positioning
  if (pos.x === "center") {
    style.left = "50%";
    style.transform = "translateX(-50%)";
  } else if (pos.x.endsWith("%")) {
    style.left = pos.x;
  } else {
    style.left = pos.x;
  }

  // Vertical positioning
  if (pos.y.endsWith("%")) {
    style.top = pos.y;
  } else {
    style.top = pos.y;
  }

  return style;
}

function getTextStyle(textStyle?: TextStyle): React.CSSProperties {
  const s = textStyle ?? {
    font_family: "Inter",
    font_size: 60,
    font_weight: "bold",
    color: "#FFFFFF",
  };

  const style: React.CSSProperties = {
    fontFamily: `${s.font_family}, sans-serif`,
    fontSize: s.font_size,
    fontWeight: s.font_weight as React.CSSProperties["fontWeight"],
    color: s.color,
    lineHeight: 1.3,
    wordWrap: "break-word",
  };

  if (s.stroke_color && s.stroke_width) {
    style.WebkitTextStroke = `${s.stroke_width}px ${s.stroke_color}`;
    style.paintOrder = "stroke fill";
  }

  if (s.background_color) {
    style.backgroundColor = s.background_color;
    style.padding = s.background_padding ? `${s.background_padding}px` : "8px";
    style.borderRadius = "8px";
  }

  return style;
}

// --- Animation renderers ---

const FadeInText: React.FC<{ text: string; textStyle: React.CSSProperties }> = ({
  text,
  textStyle,
}) => {
  const frame = useCurrentFrame();
  const { fps } = useVideoConfig();
  const opacity = interpolate(frame, [0, fps * 0.3], [0, 1], {
    extrapolateRight: "clamp",
  });
  return <span style={{ ...textStyle, opacity }}>{text}</span>;
};

const SlideUpText: React.FC<{ text: string; textStyle: React.CSSProperties }> = ({
  text,
  textStyle,
}) => {
  const frame = useCurrentFrame();
  const { fps } = useVideoConfig();
  const progress = spring({ frame, fps, config: { damping: 15, mass: 0.8 } });
  const translateY = (1 - progress) * 60;
  return (
    <span
      style={{
        ...textStyle,
        display: "inline-block",
        transform: `translateY(${translateY}px)`,
        opacity: progress,
      }}
    >
      {text}
    </span>
  );
};

const TypewriterText: React.FC<{ text: string; textStyle: React.CSSProperties }> = ({
  text,
  textStyle,
}) => {
  const frame = useCurrentFrame();
  const { durationInFrames } = useVideoConfig();

  const charsPerFrame = text.length / (durationInFrames * 0.7);
  const visibleChars = Math.min(Math.floor(frame * charsPerFrame), text.length);
  const displayText = text.slice(0, visibleChars);
  const showCursor = frame % 16 < 10 && visibleChars < text.length;

  return (
    <span style={textStyle}>
      {displayText}
      {showCursor && <span>|</span>}
    </span>
  );
};

const BounceText: React.FC<{ text: string; textStyle: React.CSSProperties }> = ({
  text,
  textStyle,
}) => {
  const frame = useCurrentFrame();
  const { fps } = useVideoConfig();
  const scale = spring({ frame, fps, config: { damping: 8, mass: 0.5 } });
  return (
    <span style={{ ...textStyle, display: "inline-block", transform: `scale(${scale})` }}>
      {text}
    </span>
  );
};

const WordByWordText: React.FC<{ text: string; textStyle: React.CSSProperties }> = ({
  text,
  textStyle,
}) => {
  const frame = useCurrentFrame();
  const { durationInFrames } = useVideoConfig();
  const words = text.split(" ");
  const framesPerWord = durationInFrames / (words.length + 1);

  return (
    <span style={textStyle}>
      {words.map((word, i) => {
        const wordStart = i * framesPerWord;
        const opacity = interpolate(
          frame,
          [wordStart, wordStart + framesPerWord * 0.4],
          [0, 1],
          { extrapolateLeft: "clamp", extrapolateRight: "clamp" }
        );
        return (
          <span
            key={i}
            style={{ opacity, display: "inline-block", marginRight: "0.25em" }}
          >
            {word}
          </span>
        );
      })}
    </span>
  );
};

const KaraokeText: React.FC<{ text: string; textStyle: React.CSSProperties }> = ({
  text,
  textStyle,
}) => {
  const frame = useCurrentFrame();
  const { durationInFrames } = useVideoConfig();
  const words = text.split(" ");
  const framesPerWord = durationInFrames / words.length;

  return (
    <span style={textStyle}>
      {words.map((word, i) => {
        const wordMid = (i + 0.5) * framesPerWord;
        const isActive = frame >= i * framesPerWord && frame < (i + 1) * framesPerWord;
        const isPast = frame >= (i + 1) * framesPerWord;

        return (
          <span
            key={i}
            style={{
              display: "inline-block",
              marginRight: "0.25em",
              color: isActive ? "#FFD700" : isPast ? textStyle.color : `${textStyle.color}88`,
              transform: isActive ? "scale(1.1)" : "scale(1)",
            }}
          >
            {word}
          </span>
        );
      })}
    </span>
  );
};

export const TextOverlayComponent: React.FC<TextOverlayProps> = ({ overlay }) => {
  const positionStyle = getPositionStyle(overlay.position);
  const textStyle = getTextStyle(overlay.style);

  let textElement: React.ReactNode;

  switch (overlay.animation) {
    case "fade_in":
      textElement = <FadeInText text={overlay.text} textStyle={textStyle} />;
      break;
    case "slide_up":
      textElement = <SlideUpText text={overlay.text} textStyle={textStyle} />;
      break;
    case "typewriter":
      textElement = <TypewriterText text={overlay.text} textStyle={textStyle} />;
      break;
    case "bounce":
      textElement = <BounceText text={overlay.text} textStyle={textStyle} />;
      break;
    case "word_by_word":
      textElement = <WordByWordText text={overlay.text} textStyle={textStyle} />;
      break;
    case "karaoke":
      textElement = <KaraokeText text={overlay.text} textStyle={textStyle} />;
      break;
    default:
      textElement = <span style={textStyle}>{overlay.text}</span>;
  }

  return <div style={positionStyle}>{textElement}</div>;
};
