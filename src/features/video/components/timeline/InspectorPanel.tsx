import { Trash2, Sparkles, Wand2 } from "lucide-react";
import type { EdlDto, EdlSegment, EffectType, TransitionType } from "../../types";
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
  onChange: (next: EdlDto) => void;
}

export const InspectorPanel = ({ edl, segment, onChange }: Props) => {
  if (!segment) {
    return (
      <div className="rounded-xl border border-border bg-card p-6 text-center text-sm text-muted-foreground">
        <Wand2 className="mx-auto size-5 mb-3 text-muted-foreground" />
        Select a clip on the timeline to tweak effects, transitions and timing.
      </div>
    );
  }

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
