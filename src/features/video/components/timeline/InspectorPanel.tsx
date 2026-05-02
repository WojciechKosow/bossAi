import { Trash2, Sparkles, Wand2, Mic, Music } from "lucide-react";
import type {
  EdlAudioTrack,
  EdlDto,
  EdlSegment,
  EffectType,
  TransitionType,
} from "../../types";
import { Button } from "@/components/ui/button";
import { cn } from "@/lib/utils";

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
  onChange: (next: EdlDto) => void;
}

export const InspectorPanel = ({
  edl,
  segment,
  audioTrack,
  onChange,
}: Props) => {
  if (audioTrack) {
    return (
      <AudioInspector edl={edl} track={audioTrack} onChange={onChange} />
    );
  }

  if (!segment) {
    return (
      <div className="rounded-xl border border-border bg-card p-6 text-center text-sm text-muted-foreground">
        <Wand2 className="mx-auto size-5 mb-3 text-muted-foreground" />
        Select a clip on the timeline to tweak effects, transitions and timing.
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
