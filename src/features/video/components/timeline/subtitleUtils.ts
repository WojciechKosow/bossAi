import type {
  EdlDto,
  EdlSubtitleConfig,
  EdlWhisperWord,
  SubtitlePosition,
} from "../../types";

/**
 * Subtitle model recap (mirrors the Remotion renderer):
 *
 * - `edl.whisper_words` carries word-level timings. Words sharing a
 *   `sentence_index` are shown together as one on-screen group; the active
 *   word is karaoke-highlighted. `sentence_index = N words per group = 1`
 *   gives true word-by-word captions.
 * - `edl.subtitle_config` controls placement (named vertical zone), colors,
 *   font and highlight mode for the whole caption track.
 * - `edl.text_overlays` are independent positioned text blocks (titles, CTA).
 *
 * Everything here is pure — helpers take + return new arrays/objects so they
 * plug straight into the editor's immutable EDL state.
 */

export interface SubtitleGroup {
  /** sentence_index shared by the words in this group */
  index: number;
  words: EdlWhisperWord[];
  startMs: number;
  endMs: number;
  text: string;
}

export const SUBTITLE_POSITIONS: SubtitlePosition[] = [
  "top",
  "top_third",
  "center",
  "bottom_third",
  "bottom",
];

export const SUBTITLE_POSITION_LABELS: Record<SubtitlePosition, string> = {
  top: "Top",
  top_third: "Upper",
  center: "Center",
  bottom_third: "Lower",
  bottom: "Bottom",
};

/**
 * Vertical anchor of each named zone as a fraction of frame height.
 * Mirrors getSubtitlePositionStyle in the Remotion SubtitleTrack.
 */
export const SUBTITLE_ZONE_Y: Record<SubtitlePosition, number> = {
  top: 0.05,
  top_third: 0.2,
  center: 0.5,
  bottom_third: 0.8,
  bottom: 0.95,
};

export const DEFAULT_HIGHLIGHT_PALETTE = [
  "#FFD700",
  "#FF6B6B",
  "#4ECDC4",
  "#45B7D1",
  "#F7DC6F",
];

export const subtitleConfigOf = (edl: EdlDto): EdlSubtitleConfig =>
  edl.subtitle_config ?? {};

export const subtitlesEnabled = (edl: EdlDto): boolean =>
  (edl.subtitle_config?.enabled ?? true) &&
  (edl.whisper_words?.length ?? 0) > 0;

/** Resolve the highlight color for a group, same rotation as the renderer. */
export const highlightColorFor = (
  config: EdlSubtitleConfig,
  groupIndex: number,
): string => {
  const palette =
    config.highlight_colors && config.highlight_colors.length > 0
      ? config.highlight_colors
      : config.highlight_color
        ? [config.highlight_color]
        : DEFAULT_HIGHLIGHT_PALETTE;
  return palette[groupIndex % palette.length];
};

/** Normalize a free-form position string to a known zone. */
export const toSubtitlePosition = (
  value: string | undefined,
): SubtitlePosition =>
  (SUBTITLE_POSITIONS as string[]).includes(value ?? "")
    ? (value as SubtitlePosition)
    : "bottom_third";

// ─── Grouping ─────────────────────────────────────────────────────────────────

export const groupWhisperWords = (
  words: EdlWhisperWord[] | undefined,
): SubtitleGroup[] => {
  if (!words?.length) return [];
  const map = new Map<number, EdlWhisperWord[]>();
  for (const w of words) {
    const idx = w.sentence_index ?? 0;
    if (!map.has(idx)) map.set(idx, []);
    map.get(idx)!.push(w);
  }
  return Array.from(map.entries())
    .map(([index, ws]) => {
      const sorted = [...ws].sort((a, b) => a.start_ms - b.start_ms);
      return {
        index,
        words: sorted,
        startMs: sorted[0].start_ms,
        endMs: sorted[sorted.length - 1].end_ms,
        text: sorted.map((w) => w.word).join(" "),
      };
    })
    .sort((a, b) => a.startMs - b.startMs);
};

/** Find the group visible at a given time (with the renderer's look-ahead). */
export const activeGroupAt = (
  groups: SubtitleGroup[],
  currentMs: number,
  lookAheadMs = 80,
): SubtitleGroup | null =>
  groups.find(
    (g) => currentMs >= g.startMs - lookAheadMs && currentMs <= g.endMs,
  ) ?? null;

/**
 * Re-chunk all words into groups of at most `maxPerGroup`.
 * `maxPerGroup = 1` → one word on screen at a time (word-by-word captions).
 * For larger groups we still break early at sentence punctuation and at
 * pauses > 400 ms — same heuristic the backend uses when building the EDL.
 */
export const regroupWords = (
  words: EdlWhisperWord[],
  maxPerGroup: number,
): EdlWhisperWord[] => {
  const sorted = [...words].sort((a, b) => a.start_ms - b.start_ms);
  const result: EdlWhisperWord[] = [];
  let groupIndex = 0;
  let inGroup = 0;
  for (let i = 0; i < sorted.length; i++) {
    const w = sorted[i];
    result.push({ ...w, sentence_index: groupIndex });
    inGroup++;
    if (i < sorted.length - 1) {
      const last = w.word.trim().slice(-1);
      const gap = sorted[i + 1].start_ms - w.end_ms;
      const sentenceBreak =
        maxPerGroup > 1 && (".!?".includes(last) || gap > 400);
      if (inGroup >= maxPerGroup || sentenceBreak) {
        groupIndex++;
        inGroup = 0;
      }
    }
  }
  return result;
};

/** Renumber sentence_index sequentially by start time (after split/delete). */
const reindexGroups = (words: EdlWhisperWord[]): EdlWhisperWord[] => {
  const groups = groupWhisperWords(words);
  const mapping = new Map<number, number>();
  groups.forEach((g, i) => mapping.set(g.index, i));
  return words.map((w) => ({
    ...w,
    sentence_index: mapping.get(w.sentence_index ?? 0) ?? 0,
  }));
};

/** Break one group apart so each of its words becomes its own group. */
export const splitGroupIntoWords = (
  words: EdlWhisperWord[],
  groupIndex: number,
): EdlWhisperWord[] => {
  // Temporarily hand out fractional indexes inside the split group, then
  // renumber everything sequentially.
  let offset = 0;
  const tmp = words.map((w) => {
    if ((w.sentence_index ?? 0) !== groupIndex) return w;
    offset += 1;
    return { ...w, sentence_index: groupIndex + offset / 1000 } as never;
  }) as unknown as EdlWhisperWord[];
  return reindexGroups(tmp);
};

/** Shift every word in a group by deltaMs (move on the timeline). */
export const shiftGroup = (
  words: EdlWhisperWord[],
  groupIndex: number,
  deltaMs: number,
): EdlWhisperWord[] =>
  words.map((w) =>
    (w.sentence_index ?? 0) === groupIndex
      ? {
          ...w,
          start_ms: Math.max(0, w.start_ms + deltaMs),
          end_ms: Math.max(1, w.end_ms + deltaMs),
        }
      : w,
  );

/**
 * Retime a group to a new [startMs, endMs] window, scaling each word's
 * timing proportionally so the karaoke rhythm is preserved.
 */
export const retimeGroup = (
  words: EdlWhisperWord[],
  groupIndex: number,
  newStartMs: number,
  newEndMs: number,
): EdlWhisperWord[] => {
  const group = words
    .filter((w) => (w.sentence_index ?? 0) === groupIndex)
    .sort((a, b) => a.start_ms - b.start_ms);
  if (!group.length) return words;
  const oldStart = group[0].start_ms;
  const oldEnd = group[group.length - 1].end_ms;
  const oldLen = Math.max(1, oldEnd - oldStart);
  const newLen = Math.max(1, newEndMs - newStartMs);
  const scale = newLen / oldLen;
  return words.map((w) => {
    if ((w.sentence_index ?? 0) !== groupIndex) return w;
    return {
      ...w,
      start_ms: Math.round(newStartMs + (w.start_ms - oldStart) * scale),
      end_ms: Math.round(newStartMs + (w.end_ms - oldStart) * scale),
    };
  });
};

/** Remove a whole group, renumbering the remaining ones. */
export const deleteGroup = (
  words: EdlWhisperWord[],
  groupIndex: number,
): EdlWhisperWord[] =>
  reindexGroups(words.filter((w) => (w.sentence_index ?? 0) !== groupIndex));

/**
 * Patch a single word inside a group. `wordPos` is the word's position in
 * start-time order — the same order groupWhisperWords (and the inspector)
 * present the group in.
 */
export const updateWordInGroup = (
  words: EdlWhisperWord[],
  groupIndex: number,
  wordPos: number,
  patch: Partial<EdlWhisperWord>,
): EdlWhisperWord[] => {
  const target = words
    .filter((w) => (w.sentence_index ?? 0) === groupIndex)
    .sort((a, b) => a.start_ms - b.start_ms)[wordPos];
  if (!target) return words;
  return words.map((w) => (w === target ? { ...w, ...patch } : w));
};

// ─── EDL-level helpers ────────────────────────────────────────────────────────

export const withWhisperWords = (
  edl: EdlDto,
  words: EdlWhisperWord[],
): EdlDto => ({ ...edl, whisper_words: words });

export const withSubtitleConfig = (
  edl: EdlDto,
  patch: Partial<EdlSubtitleConfig>,
): EdlDto => ({
  ...edl,
  subtitle_config: { ...(edl.subtitle_config ?? {}), ...patch },
});

/**
 * Some GPT-generated EDLs ship text overlays without ids. Selection and
 * drag in the editor key off ids, so assign stable ones on hydrate.
 */
export const ensureOverlayIds = (edl: EdlDto): EdlDto => {
  if (!edl.text_overlays?.length) return edl;
  if (edl.text_overlays.every((t) => t.id)) return edl;
  return {
    ...edl,
    text_overlays: edl.text_overlays.map((t, i) =>
      t.id ? t : { ...t, id: `ovl-${i}-${t.start_ms}` },
    ),
  };
};

// ─── Selection model shared by Timeline / Inspector / Editor page ─────────────

export type SubtitleSelection =
  | { kind: "group"; index: number }
  | { kind: "overlay"; id: string }
  | { kind: "settings" };

export const sameSubtitleSelection = (
  a: SubtitleSelection | null,
  b: SubtitleSelection | null,
): boolean => {
  if (!a || !b) return a === b;
  if (a.kind !== b.kind) return false;
  if (a.kind === "group" && b.kind === "group") return a.index === b.index;
  if (a.kind === "overlay" && b.kind === "overlay") return a.id === b.id;
  return true;
};
