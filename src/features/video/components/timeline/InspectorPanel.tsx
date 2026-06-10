import {
  Trash2,
  Sparkles,
  Wand2,
  Mic,
  Music,
  Captions,
  Type,
  SplitSquareHorizontal,
} from "lucide-react";
import type {
  EdlAudioTrack,
  EdlDto,
  EdlSegment,
  EdlTextOverlay,
  EffectType,
  TransitionType,
} from "../../types";
import { Button } from "@/components/ui/button";
import { cn } from "@/lib/utils";
import {
  DEFAULT_HIGHLIGHT_PALETTE,
  SUBTITLE_POSITIONS,
  SUBTITLE_POSITION_LABELS,
  type SubtitleSelection,
  deleteGroup,
  groupWhisperWords,
  regroupWords,
  retimeGroup,
  splitGroupIntoWords,
  toSubtitlePosition,
  updateWordInGroup,
  withSubtitleConfig,
  withWhisperWords,
} from "./subtitleUtils";

const EFFECT_OPTIONS: EffectType[] = [
  "zoom_in",
  "zoom_out",
  "fast_zoom",
  "pan_left",
  "pan_right",
  "pan_up",
  "pan_down",
  "shake",
  "slow_motion",
  "speed_ramp",
  "zoom_pulse",
  "ken_burns",
  "glitch",
  "flash",
  "bounce",
  "drift",
  "zoom_in_offset",
];

const TRANSITION_OPTIONS: TransitionType[] = [
  "cut",
  "fade",
  "fade_white",
  "fade_black",
  "dissolve",
  "wipe_left",
  "wipe_right",
  "slide_left",
  "slide_right",
];

interface Props {
  edl: EdlDto;
  segment: EdlSegment | null;
  audioTrack: EdlAudioTrack | null;
  subtitle: SubtitleSelection | null;
  onSelectSubtitle: (sel: SubtitleSelection | null) => void;
  onChange: (next: EdlDto) => void;
}

export const InspectorPanel = ({
  edl,
  segment,
  audioTrack,
  subtitle,
  onSelectSubtitle,
  onChange,
}: Props) => {
  if (subtitle?.kind === "settings") {
    return <CaptionSettingsInspector edl={edl} onChange={onChange} />;
  }

  if (subtitle?.kind === "group") {
    return (
      <CaptionGroupInspector
        edl={edl}
        groupIndex={subtitle.index}
        onChange={onChange}
        onSelectSubtitle={onSelectSubtitle}
      />
    );
  }

  if (subtitle?.kind === "overlay") {
    return (
      <OverlayInspector
        edl={edl}
        overlayId={subtitle.id}
        onChange={onChange}
        onSelectSubtitle={onSelectSubtitle}
      />
    );
  }

  if (audioTrack) {
    return (
      <AudioInspector edl={edl} track={audioTrack} onChange={onChange} />
    );
  }

  if (!segment) {
    return (
      <div className="rounded-xl border border-border bg-card p-6 text-center text-sm text-muted-foreground">
        <Wand2 className="mx-auto size-5 mb-3 text-muted-foreground" />
        Select a clip, audio block or caption on the timeline to tweak it —
        or open caption settings via the gear on the Captions track.
      </div>
    );
  }

  return <SegmentInspector edl={edl} segment={segment} onChange={onChange} />;
};

const SegmentInspector = ({
  edl,
  segment,
  onChange,
}: {
  edl: EdlDto;
  segment: EdlSegment;
  onChange: (next: EdlDto) => void;
}) => {
  const update = (patch: Partial<EdlSegment>) => {
    onChange({
      ...edl,
      segments: edl.segments.map((s) =>
        s.id === segment.id ? { ...s, ...patch } : s,
      ),
    });
  };

  const setEffect = (type: EffectType) => {
    const has = segment.effects?.some((e) => e.type === type);
    const next = has
      ? segment.effects!.filter((e) => e.type !== type)
      : [...(segment.effects ?? []), { type, intensity: 0.7 }];
    update({ effects: next });
  };

  const setTransition = (type: TransitionType) => {
    update({
      transition:
        type === "cut" ? { type: "cut" } : { type, duration_ms: 300 },
    });
  };

  const remove = () => {
    onChange({
      ...edl,
      segments: edl.segments.filter((s) => s.id !== segment.id),
    });
  };

  const dur = segment.end_ms - segment.start_ms;

  return (
    <div className="space-y-4">
      <div className="rounded-xl border border-border bg-card p-4 space-y-3">
        <div className="flex items-start justify-between">
          <div>
            <p className="text-xs uppercase tracking-wider text-muted-foreground">
              Selected clip
            </p>
            <p className="text-sm font-semibold mt-0.5">
              {segment.asset_type} · Layer {segment.layer}
            </p>
          </div>
          <button
            onClick={remove}
            className="size-7 rounded-md hover:bg-destructive/10 text-destructive flex items-center justify-center transition"
          >
            <Trash2 size={13} />
          </button>
        </div>
        <div className="grid grid-cols-3 gap-2 text-xs">
          <FieldNum
            label="Start"
            value={segment.start_ms}
            onChange={(v) => update({ start_ms: Math.max(0, v) })}
          />
          <FieldNum
            label="End"
            value={segment.end_ms}
            onChange={(v) =>
              update({ end_ms: Math.max(segment.start_ms + 100, v) })
            }
          />
          <FieldNum label="Duration" value={dur} readOnly />
        </div>
      </div>

      <div className="rounded-xl border border-border bg-card p-4 space-y-3">
        <p className="text-xs uppercase tracking-wider text-muted-foreground">
          Effects
        </p>
        <div className="flex flex-wrap gap-1.5">
          {EFFECT_OPTIONS.map((eff) => {
            const active = segment.effects?.some((e) => e.type === eff);
            return (
              <button
                key={eff}
                onClick={() => setEffect(eff)}
                className={cn(
                  "text-[11px] rounded-full px-2.5 py-1 border transition",
                  active
                    ? "gradient-bg text-white border-transparent shadow-glow"
                    : "border-border bg-muted/40 hover:bg-muted text-muted-foreground hover:text-foreground",
                )}
              >
                {active && <Sparkles size={10} className="inline mr-1" />}
                {eff.replace(/_/g, " ")}
              </button>
            );
          })}
        </div>
      </div>

      <div className="rounded-xl border border-border bg-card p-4 space-y-3">
        <p className="text-xs uppercase tracking-wider text-muted-foreground">
          Transition out
        </p>
        <div className="flex flex-wrap gap-1.5">
          {TRANSITION_OPTIONS.map((t) => {
            const active = segment.transition?.type === t;
            return (
              <button
                key={t}
                onClick={() => setTransition(t)}
                className={cn(
                  "text-[11px] rounded-full px-2.5 py-1 border transition",
                  active
                    ? "border-primary bg-accent text-accent-foreground"
                    : "border-border bg-muted/40 hover:bg-muted text-muted-foreground hover:text-foreground",
                )}
              >
                {t.replace(/_/g, " ")}
              </button>
            );
          })}
        </div>
        {segment.transition && segment.transition.type !== "cut" && (
          <FieldNum
            label="Duration (ms)"
            value={segment.transition.duration_ms ?? 300}
            onChange={(v) =>
              update({
                transition: {
                  type: segment.transition!.type,
                  duration_ms: Math.max(50, v),
                },
              })
            }
          />
        )}
      </div>
    </div>
  );
};

const AudioInspector = ({
  edl,
  track,
  onChange,
}: {
  edl: EdlDto;
  track: EdlAudioTrack;
  onChange: (next: EdlDto) => void;
}) => {
  const update = (patch: Partial<EdlAudioTrack>) => {
    onChange({
      ...edl,
      audio_tracks: (edl.audio_tracks ?? []).map((t) =>
        t.id === track.id ? { ...t, ...patch } : t,
      ),
    });
  };

  const remove = () => {
    onChange({
      ...edl,
      audio_tracks: (edl.audio_tracks ?? []).filter((t) => t.id !== track.id),
    });
  };

  const isMusic = track.type === "music";
  const start = track.start_ms ?? 0;
  const end = track.end_ms ?? start;
  const trimIn = track.trim_in_ms ?? 0;
  const trimOut = track.trim_out_ms ?? end;
  const dur = end - start;

  return (
    <div className="space-y-4">
      <div className="rounded-xl border border-border bg-card p-4 space-y-3">
        <div className="flex items-start justify-between">
          <div className="flex items-center gap-2">
            <span
              className={cn(
                "size-7 rounded-md flex items-center justify-center",
                isMusic
                  ? "bg-emerald-500/15 text-emerald-600"
                  : "bg-amber-500/15 text-amber-600",
              )}
            >
              {isMusic ? <Music size={14} /> : <Mic size={14} />}
            </span>
            <div>
              <p className="text-xs uppercase tracking-wider text-muted-foreground">
                Selected audio
              </p>
              <p className="text-sm font-semibold mt-0.5">
                {isMusic ? "Music" : "Voice"} clip
              </p>
            </div>
          </div>
          <button
            onClick={remove}
            className="size-7 rounded-md hover:bg-destructive/10 text-destructive flex items-center justify-center transition"
          >
            <Trash2 size={13} />
          </button>
        </div>
        <div className="grid grid-cols-3 gap-2 text-xs">
          <FieldNum
            label="Start"
            value={start}
            onChange={(v) => {
              const next = Math.max(0, v);
              update({ start_ms: next, end_ms: Math.max(end, next + 100) });
            }}
          />
          <FieldNum
            label="End"
            value={end}
            onChange={(v) => update({ end_ms: Math.max(start + 100, v) })}
          />
          <FieldNum label="Duration" value={dur} readOnly />
        </div>
      </div>

      <div className="rounded-xl border border-border bg-card p-4 space-y-3">
        <p className="text-xs uppercase tracking-wider text-muted-foreground">
          Source slice
        </p>
        <p className="text-[11px] text-muted-foreground">
          Which slice of the source file plays during this block.
        </p>
        <div className="grid grid-cols-2 gap-2 text-xs">
          <FieldNum
            label="Trim in (ms)"
            value={trimIn}
            onChange={(v) => update({ trim_in_ms: Math.max(0, v) })}
          />
          <FieldNum
            label="Trim out (ms)"
            value={trimOut}
            onChange={(v) =>
              update({ trim_out_ms: Math.max(trimIn + 1, v) })
            }
          />
        </div>
      </div>

      <div className="rounded-xl border border-border bg-card p-4 space-y-3">
        <p className="text-xs uppercase tracking-wider text-muted-foreground">
          Mix
        </p>
        <FieldVolume
          value={track.volume ?? 1}
          onChange={(v) => update({ volume: v })}
        />
        <div className="grid grid-cols-2 gap-2 text-xs">
          <FieldNum
            label="Fade in (ms)"
            value={track.fade_in_ms ?? 0}
            onChange={(v) => update({ fade_in_ms: Math.max(0, v) })}
          />
          <FieldNum
            label="Fade out (ms)"
            value={track.fade_out_ms ?? 0}
            onChange={(v) => update({ fade_out_ms: Math.max(0, v) })}
          />
        </div>
      </div>
    </div>
  );
};

// ─── Caption settings (whole track) ──────────────────────────────────────────

const CaptionSettingsInspector = ({
  edl,
  onChange,
}: {
  edl: EdlDto;
  onChange: (next: EdlDto) => void;
}) => {
  const config = edl.subtitle_config ?? {};
  const words = edl.whisper_words ?? [];
  const enabled = config.enabled ?? true;
  const position = toSubtitlePosition(config.position);
  const mode = config.highlight_mode ?? "word";
  const wordsPerGroup = config.max_words_per_group ?? 5;
  const activeColor =
    config.highlight_colors?.length === 1
      ? config.highlight_colors[0]
      : config.highlight_colors?.length
        ? "multi"
        : (config.highlight_color ?? "multi");

  const patch = (p: Parameters<typeof withSubtitleConfig>[1]) =>
    onChange(withSubtitleConfig(edl, p));

  const applyGrouping = (n: number) => {
    onChange(
      withSubtitleConfig(
        withWhisperWords(edl, regroupWords(words, n)),
        { max_words_per_group: n },
      ),
    );
  };

  return (
    <div className="space-y-4">
      <div className="rounded-xl border border-border bg-card p-4 space-y-3">
        <div className="flex items-center justify-between">
          <div className="flex items-center gap-2">
            <span className="size-7 rounded-md bg-amber-500/15 text-amber-600 flex items-center justify-center">
              <Captions size={14} />
            </span>
            <div>
              <p className="text-xs uppercase tracking-wider text-muted-foreground">
                Captions
              </p>
              <p className="text-sm font-semibold mt-0.5">
                {words.length} words · {groupWhisperWords(words).length} lines
              </p>
            </div>
          </div>
          <label className="flex items-center gap-2 text-xs cursor-pointer">
            <input
              type="checkbox"
              checked={enabled}
              onChange={(e) => patch({ enabled: e.target.checked })}
              className="accent-primary"
            />
            Show
          </label>
        </div>
      </div>

      <div className="rounded-xl border border-border bg-card p-4 space-y-3">
        <p className="text-xs uppercase tracking-wider text-muted-foreground">
          Position on screen
        </p>
        <p className="text-[11px] text-muted-foreground">
          You can also drag the captions directly in the preview.
        </p>
        <div className="grid grid-cols-5 gap-1.5">
          {SUBTITLE_POSITIONS.map((p) => (
            <button
              key={p}
              onClick={() => patch({ position: p })}
              className={cn(
                "text-[11px] rounded-md px-1 py-1.5 border transition",
                position === p
                  ? "border-primary bg-accent text-accent-foreground font-medium"
                  : "border-border bg-muted/40 hover:bg-muted text-muted-foreground hover:text-foreground",
              )}
            >
              {SUBTITLE_POSITION_LABELS[p]}
            </button>
          ))}
        </div>
      </div>

      <div className="rounded-xl border border-border bg-card p-4 space-y-3">
        <p className="text-xs uppercase tracking-wider text-muted-foreground">
          Words per line
        </p>
        <p className="text-[11px] text-muted-foreground">
          1 shows captions one word at a time (word-by-word).
        </p>
        <div className="grid grid-cols-6 gap-1.5">
          {[1, 2, 3, 4, 5, 6].map((n) => (
            <button
              key={n}
              onClick={() => applyGrouping(n)}
              disabled={!words.length}
              className={cn(
                "text-[11px] rounded-md px-1 py-1.5 border transition disabled:opacity-40",
                wordsPerGroup === n
                  ? "border-primary bg-accent text-accent-foreground font-medium"
                  : "border-border bg-muted/40 hover:bg-muted text-muted-foreground hover:text-foreground",
              )}
            >
              {n === 1 ? "1 word" : n}
            </button>
          ))}
        </div>
        <div className="flex gap-1.5 pt-1">
          {(["word", "sentence"] as const).map((m) => (
            <button
              key={m}
              onClick={() => patch({ highlight_mode: m })}
              className={cn(
                "text-[11px] rounded-full px-2.5 py-1 border transition flex-1",
                mode === m
                  ? "border-primary bg-accent text-accent-foreground"
                  : "border-border bg-muted/40 hover:bg-muted text-muted-foreground hover:text-foreground",
              )}
            >
              {m === "word" ? "Karaoke highlight" : "Highlight whole line"}
            </button>
          ))}
        </div>
      </div>

      <div className="rounded-xl border border-border bg-card p-4 space-y-3">
        <p className="text-xs uppercase tracking-wider text-muted-foreground">
          Style
        </p>
        <div className="flex items-center gap-1.5 flex-wrap">
          <button
            onClick={() =>
              patch({ highlight_colors: DEFAULT_HIGHLIGHT_PALETTE })
            }
            className={cn(
              "h-7 px-2 rounded-md border text-[11px] transition",
              activeColor === "multi"
                ? "border-primary bg-accent text-accent-foreground"
                : "border-border text-muted-foreground hover:text-foreground",
            )}
            title="Rotate through multiple colors"
          >
            Multi
          </button>
          {DEFAULT_HIGHLIGHT_PALETTE.map((c) => (
            <button
              key={c}
              onClick={() =>
                patch({ highlight_color: c, highlight_colors: [c] })
              }
              className={cn(
                "size-7 rounded-md border-2 transition",
                activeColor === c ? "border-primary" : "border-transparent",
              )}
              style={{ background: c }}
              title={c}
            />
          ))}
          <input
            type="color"
            value={activeColor !== "multi" ? activeColor : "#FFD700"}
            onChange={(e) =>
              patch({
                highlight_color: e.target.value,
                highlight_colors: [e.target.value],
              })
            }
            className="size-7 rounded-md border border-border bg-transparent cursor-pointer"
            title="Custom highlight color"
          />
        </div>
        <div className="grid grid-cols-2 gap-2 text-xs">
          <FieldNum
            label="Font size"
            value={config.font_size ?? 42}
            onChange={(v) => patch({ font_size: Math.max(10, v) })}
          />
          <FieldNum
            label="Outline width"
            value={config.stroke_width ?? 3}
            onChange={(v) => patch({ stroke_width: Math.max(0, v) })}
          />
        </div>
      </div>
    </div>
  );
};

// ─── Single caption line (word group) ────────────────────────────────────────

const CaptionGroupInspector = ({
  edl,
  groupIndex,
  onChange,
  onSelectSubtitle,
}: {
  edl: EdlDto;
  groupIndex: number;
  onChange: (next: EdlDto) => void;
  onSelectSubtitle: (sel: SubtitleSelection | null) => void;
}) => {
  const words = edl.whisper_words ?? [];
  const group = groupWhisperWords(words).find((g) => g.index === groupIndex);

  if (!group) {
    return (
      <div className="rounded-xl border border-border bg-card p-6 text-center text-sm text-muted-foreground">
        This caption no longer exists.
      </div>
    );
  }

  const retime = (startMs: number, endMs: number) =>
    onChange(
      withWhisperWords(edl, retimeGroup(words, groupIndex, startMs, endMs)),
    );

  const split = () => {
    onChange(withWhisperWords(edl, splitGroupIntoWords(words, groupIndex)));
    onSelectSubtitle(null);
  };

  const remove = () => {
    onChange(withWhisperWords(edl, deleteGroup(words, groupIndex)));
    onSelectSubtitle(null);
  };

  return (
    <div className="space-y-4">
      <div className="rounded-xl border border-border bg-card p-4 space-y-3">
        <div className="flex items-start justify-between">
          <div className="flex items-center gap-2 min-w-0">
            <span className="size-7 shrink-0 rounded-md bg-amber-500/15 text-amber-600 flex items-center justify-center">
              <Captions size={14} />
            </span>
            <div className="min-w-0">
              <p className="text-xs uppercase tracking-wider text-muted-foreground">
                Caption line
              </p>
              <p className="text-sm font-semibold mt-0.5 truncate">
                “{group.text}”
              </p>
            </div>
          </div>
          <button
            onClick={remove}
            className="size-7 shrink-0 rounded-md hover:bg-destructive/10 text-destructive flex items-center justify-center transition"
            title="Delete this caption line"
          >
            <Trash2 size={13} />
          </button>
        </div>
        <div className="grid grid-cols-3 gap-2 text-xs">
          <FieldNum
            label="Start (ms)"
            value={group.startMs}
            onChange={(v) =>
              retime(Math.max(0, Math.min(group.endMs - 50, v)), group.endMs)
            }
          />
          <FieldNum
            label="End (ms)"
            value={group.endMs}
            onChange={(v) => retime(group.startMs, Math.max(group.startMs + 50, v))}
          />
          <FieldNum label="Duration" value={group.endMs - group.startMs} readOnly />
        </div>
        {group.words.length > 1 && (
          <Button
            variant="outline"
            size="sm"
            onClick={split}
            className="w-full"
            title="Each word becomes its own caption shown one at a time"
          >
            <SplitSquareHorizontal size={13} /> Split into single words
          </Button>
        )}
      </div>

      <div className="rounded-xl border border-border bg-card p-4 space-y-2">
        <p className="text-xs uppercase tracking-wider text-muted-foreground">
          Words ({group.words.length})
        </p>
        <p className="text-[11px] text-muted-foreground">
          Fine-tune the text and exact timing of each word.
        </p>
        <div className="space-y-1.5 max-h-64 overflow-y-auto pr-1">
          {group.words.map((w, i) => (
            <div key={i} className="grid grid-cols-[1fr_72px_72px] gap-1.5">
              <input
                type="text"
                value={w.word}
                onChange={(e) =>
                  onChange(
                    withWhisperWords(
                      edl,
                      updateWordInGroup(words, groupIndex, i, {
                        word: e.target.value,
                      }),
                    ),
                  )
                }
                className="rounded-md border border-border bg-muted/40 px-2 py-1.5 text-xs outline-none focus:border-primary focus:bg-background"
              />
              <input
                type="number"
                value={w.start_ms}
                onChange={(e) =>
                  onChange(
                    withWhisperWords(
                      edl,
                      updateWordInGroup(words, groupIndex, i, {
                        start_ms: Math.max(0, Number(e.target.value)),
                      }),
                    ),
                  )
                }
                title="Word start (ms)"
                className="rounded-md border border-border bg-muted/40 px-2 py-1.5 text-xs font-mono outline-none focus:border-primary focus:bg-background"
              />
              <input
                type="number"
                value={w.end_ms}
                onChange={(e) =>
                  onChange(
                    withWhisperWords(
                      edl,
                      updateWordInGroup(words, groupIndex, i, {
                        end_ms: Math.max(w.start_ms + 1, Number(e.target.value)),
                      }),
                    ),
                  )
                }
                title="Word end (ms)"
                className="rounded-md border border-border bg-muted/40 px-2 py-1.5 text-xs font-mono outline-none focus:border-primary focus:bg-background"
              />
            </div>
          ))}
        </div>
      </div>
    </div>
  );
};

// ─── Text overlay ─────────────────────────────────────────────────────────────

const OVERLAY_ANIMATIONS = [
  "none",
  "fade_in",
  "slide_up",
  "typewriter",
  "bounce",
  "word_by_word",
  "karaoke",
] as const;

const OverlayInspector = ({
  edl,
  overlayId,
  onChange,
  onSelectSubtitle,
}: {
  edl: EdlDto;
  overlayId: string;
  onChange: (next: EdlDto) => void;
  onSelectSubtitle: (sel: SubtitleSelection | null) => void;
}) => {
  const overlays = edl.text_overlays ?? [];
  const idOf = (t: EdlTextOverlay, i: number) => t.id ?? `ovl-${i}`;
  const overlay = overlays.find((t, i) => idOf(t, i) === overlayId);

  if (!overlay) {
    return (
      <div className="rounded-xl border border-border bg-card p-6 text-center text-sm text-muted-foreground">
        This text overlay no longer exists.
      </div>
    );
  }

  const update = (patch: Partial<EdlTextOverlay>) =>
    onChange({
      ...edl,
      text_overlays: overlays.map((t, i) =>
        idOf(t, i) === overlayId ? { ...t, ...patch } : t,
      ),
    });

  const remove = () => {
    onChange({
      ...edl,
      text_overlays: overlays.filter((t, i) => idOf(t, i) !== overlayId),
    });
    onSelectSubtitle(null);
  };

  const yPct = parseFloat(overlay.position?.y ?? "75") || 75;
  const xVal = overlay.position?.x ?? "center";

  return (
    <div className="space-y-4">
      <div className="rounded-xl border border-border bg-card p-4 space-y-3">
        <div className="flex items-start justify-between">
          <div className="flex items-center gap-2">
            <span className="size-7 rounded-md bg-sky-500/15 text-sky-600 flex items-center justify-center">
              <Type size={14} />
            </span>
            <div>
              <p className="text-xs uppercase tracking-wider text-muted-foreground">
                Text overlay
              </p>
              <p className="text-sm font-semibold mt-0.5">
                {overlay.type ?? "subtitle"}
              </p>
            </div>
          </div>
          <button
            onClick={remove}
            className="size-7 rounded-md hover:bg-destructive/10 text-destructive flex items-center justify-center transition"
          >
            <Trash2 size={13} />
          </button>
        </div>
        <textarea
          value={overlay.text}
          onChange={(e) => update({ text: e.target.value })}
          rows={2}
          className="w-full rounded-md border border-border bg-muted/40 px-2 py-1.5 text-xs outline-none resize-none focus:border-primary focus:bg-background"
        />
        <div className="grid grid-cols-3 gap-2 text-xs">
          <FieldNum
            label="Start (ms)"
            value={overlay.start_ms}
            onChange={(v) => update({ start_ms: Math.max(0, v) })}
          />
          <FieldNum
            label="End (ms)"
            value={overlay.end_ms}
            onChange={(v) =>
              update({ end_ms: Math.max(overlay.start_ms + 100, v) })
            }
          />
          <FieldNum
            label="Duration"
            value={overlay.end_ms - overlay.start_ms}
            readOnly
          />
        </div>
      </div>

      <div className="rounded-xl border border-border bg-card p-4 space-y-3">
        <p className="text-xs uppercase tracking-wider text-muted-foreground">
          Position on screen
        </p>
        <p className="text-[11px] text-muted-foreground">
          You can also drag the text directly in the preview.
        </p>
        <div className="flex gap-1.5">
          {(["10%", "center", "75%"] as const).map((x, i) => (
            <button
              key={x}
              onClick={() =>
                update({ position: { ...(overlay.position ?? {}), x } })
              }
              className={cn(
                "text-[11px] rounded-md px-2 py-1.5 border transition flex-1",
                xVal === x
                  ? "border-primary bg-accent text-accent-foreground"
                  : "border-border bg-muted/40 hover:bg-muted text-muted-foreground hover:text-foreground",
              )}
            >
              {["Left", "Center", "Right"][i]}
            </button>
          ))}
        </div>
        <label className="block">
          <div className="flex items-center justify-between mb-1">
            <span className="text-[10px] uppercase tracking-wider text-muted-foreground">
              Vertical
            </span>
            <span className="text-[10px] font-mono text-muted-foreground">
              {Math.round(yPct)}% from top
            </span>
          </div>
          <input
            type="range"
            min={2}
            max={95}
            step={1}
            value={yPct}
            onChange={(e) =>
              update({
                position: {
                  ...(overlay.position ?? {}),
                  y: `${e.target.value}%`,
                },
              })
            }
            className="w-full accent-primary"
          />
        </label>
      </div>

      <div className="rounded-xl border border-border bg-card p-4 space-y-3">
        <p className="text-xs uppercase tracking-wider text-muted-foreground">
          Appearance
        </p>
        <div className="flex flex-wrap gap-1.5">
          {OVERLAY_ANIMATIONS.map((a) => {
            const active = (overlay.animation ?? "none") === a;
            return (
              <button
                key={a}
                onClick={() =>
                  update({ animation: a === "none" ? undefined : a })
                }
                className={cn(
                  "text-[11px] rounded-full px-2.5 py-1 border transition",
                  active
                    ? "border-primary bg-accent text-accent-foreground"
                    : "border-border bg-muted/40 hover:bg-muted text-muted-foreground hover:text-foreground",
                )}
              >
                {a.replace(/_/g, " ")}
              </button>
            );
          })}
        </div>
        <div className="grid grid-cols-2 gap-2 text-xs items-end">
          <FieldNum
            label="Font size"
            value={overlay.style?.font_size ?? 60}
            onChange={(v) =>
              update({
                style: { ...(overlay.style ?? {}), font_size: Math.max(10, v) },
              })
            }
          />
          <label className="block">
            <span className="block text-[10px] uppercase tracking-wider text-muted-foreground mb-1">
              Color
            </span>
            <input
              type="color"
              value={overlay.style?.color ?? "#FFFFFF"}
              onChange={(e) =>
                update({
                  style: { ...(overlay.style ?? {}), color: e.target.value },
                })
              }
              className="w-full h-[30px] rounded-md border border-border bg-transparent cursor-pointer"
            />
          </label>
        </div>
      </div>
    </div>
  );
};

const FieldNum = ({
  label,
  value,
  onChange,
  readOnly,
}: {
  label: string;
  value: number;
  onChange?: (v: number) => void;
  readOnly?: boolean;
}) => (
  <label className="block">
    <span className="block text-[10px] uppercase tracking-wider text-muted-foreground mb-1">
      {label}
    </span>
    <input
      type="number"
      value={value}
      readOnly={readOnly}
      onChange={(e) => onChange?.(Number(e.target.value))}
      className="w-full rounded-md border border-border bg-muted/40 px-2 py-1.5 text-xs font-mono outline-none focus:border-primary focus:bg-background"
    />
  </label>
);

const FieldVolume = ({
  value,
  onChange,
}: {
  value: number;
  onChange: (v: number) => void;
}) => (
  <label className="block">
    <div className="flex items-center justify-between mb-1">
      <span className="text-[10px] uppercase tracking-wider text-muted-foreground">
        Volume
      </span>
      <span className="text-[10px] font-mono text-muted-foreground">
        {Math.round(value * 100)}%
      </span>
    </div>
    <input
      type="range"
      min={0}
      max={1.5}
      step={0.05}
      value={value}
      onChange={(e) => onChange(Number(e.target.value))}
      className="w-full accent-primary"
    />
  </label>
);

export const InspectorActions = ({
  saving,
  dirty,
  onSave,
  onRevert,
  onTriggerRender,
}: {
  saving: boolean;
  dirty: boolean;
  onSave: () => void;
  onRevert: () => void;
  onTriggerRender: () => void;
}) => (
  <div className="flex items-center gap-2">
    <Button variant="ghost" onClick={onRevert} disabled={!dirty || saving}>
      Revert
    </Button>
    <Button
      onClick={onTriggerRender}
      variant="outline"
      disabled={saving}
      title="Re-render the current saved version"
    >
      Re-render
    </Button>
    <Button
      onClick={onSave}
      disabled={!dirty || saving}
      className="gradient-bg text-white shadow-glow"
    >
      {saving ? "Saving…" : "Save & re-render"}
    </Button>
  </div>
);
