package com.BossAi.bossAi.service.dna;

import com.BossAi.bossAi.dto.edl.EdlColorGrade;
import com.BossAi.bossAi.dto.edl.EdlEffect;
import com.BossAi.bossAi.dto.edl.EdlSubtitleConfig;
import com.BossAi.bossAi.dto.edl.EdlTextOverlay;
import com.BossAi.bossAi.dto.edl.EdlTransition;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

/**
 * Schema for a DNA preset config file (resources/dna-presets/<id>.json).
 * All values loaded at runtime — no preset logic hardcoded in Java.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class DnaPresetConfig {

    @JsonProperty("id")
    private String id;

    @JsonProperty("display_name")
    private String displayName;

    @JsonProperty("pacing")
    private String pacing;

    /** Global color grade — applied to all segments unless overridden per beat. */
    @JsonProperty("color_grade")
    private EdlColorGrade colorGrade;

    /**
     * Per-beat configuration. Keys are beat letters: A, B, C, D, E.
     * Each beat has timing, available scene patterns, and a default color grade.
     */
    @JsonProperty("beats")
    private Map<String, BeatConfig> beats;

    @JsonProperty("subtitle_config")
    private EdlSubtitleConfig subtitleConfig;

    @JsonProperty("audio_config")
    private AudioConfig audioConfig;

    /**
     * Text overlay templates for this preset.
     * Placeholders like {HOOK_TEXT} are filled by GPT at generation time.
     */
    @JsonProperty("text_overlay_templates")
    private List<TextOverlayTemplate> textOverlayTemplates;

    /**
     * Multi-layer composition rules — which named rules (TalkingHeadBg,
     * ProductReveal, CtaOverlay) apply in which beats. Read by
     * AutonomousCompositionDecider; a preset without this section gets no
     * layered composition.
     */
    @JsonProperty("composition_rules")
    private CompositionRules compositionRules;

    // ─── Nested types ─────────────────────────────────────────────────────────

    /**
     * Configuration for a single beat (A–E).
     * scenePatterns is a rotating list — first scene in beat uses pattern[0],
     * second uses pattern[1], etc. (wraps around if more scenes than patterns).
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class BeatConfig {

        @JsonProperty("name")
        private String name;

        /** Absolute start time for 30s standard video (ms). Scaled for 15s/45s. */
        @JsonProperty("start_ms")
        private int startMs;

        @JsonProperty("end_ms")
        private int endMs;

        @JsonProperty("min_duration_ms")
        private int minDurationMs;

        @JsonProperty("max_duration_ms")
        private int maxDurationMs;

        @JsonProperty("scene_patterns")
        private List<ScenePattern> scenePatterns;

        /**
         * Style grammar for this beat — what the EffectDirector may choose from
         * and how it should feel. When present, supersedes scenePatterns
         * (which rotate blindly regardless of content).
         */
        @JsonProperty("grammar")
        private BeatGrammar grammar;

        /** Default color grade override for all scenes in this beat. */
        @JsonProperty("color_grade_override")
        private EdlColorGrade colorGradeOverride;
    }

    /**
     * Beat-level style grammar. The style declares what is ALLOWED and what it
     * should FEEL like; the EffectDirector decides what actually happens based
     * on the content (narration, asset, music) of each segment.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class BeatGrammar {

        /**
         * Base score for choosing NO effect. Restraint is a decision —
         * calm segments in a calm beat should often stay clean.
         */
        @JsonProperty("restraint_weight")
        private Double restraintWeight;

        @JsonProperty("effects")
        private List<EffectOption> effects;

        @JsonProperty("transitions")
        private List<TransitionOption> transitions;
    }

    /** One effect the style allows in a beat, with its selection preferences. */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class EffectOption {

        @JsonProperty("type")
        private String type;

        /** Style prior — how characteristic this effect is for the beat. */
        @JsonProperty("weight")
        @Builder.Default
        private double weight = 1.0;

        /** [min, max] — actual intensity is driven by narration energy/importance. */
        @JsonProperty("intensity_range")
        private List<Double> intensityRange;

        /** Effect params (merged over EffectRegistry defaults). */
        @JsonProperty("params")
        private Map<String, Object> params;

        /** Hard cap on uses of this effect within one beat (e.g. smash_zoom once). */
        @JsonProperty("max_per_beat")
        private Integer maxPerBeat;

        @JsonProperty("prefers")
        private GrammarPrefs prefers;
    }

    /** One transition the style allows in a beat. */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class TransitionOption {

        @JsonProperty("type")
        private String type;

        @JsonProperty("weight")
        @Builder.Default
        private double weight = 1.0;

        @JsonProperty("duration_ms")
        private Integer durationMs;

        @JsonProperty("prefers")
        private GrammarPrefs prefers;
    }

    /**
     * Content preferences for a grammar option — when does this choice fit?
     * All fields optional; absent = indifferent.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class GrammarPrefs {

        /** Narration segment types this option fits (hook, point, emphasis, climax, cta…). */
        @JsonProperty("segment_types")
        private List<String> segmentTypes;

        /** Narration energy bounds (0–1). */
        @JsonProperty("min_energy")
        private Double minEnergy;

        @JsonProperty("max_energy")
        private Double maxEnergy;

        /** Narration importance floor (0–1). */
        @JsonProperty("min_importance")
        private Double minImportance;

        /** Asset kinds this option suits: VIDEO, IMAGE. Hard filter. */
        @JsonProperty("asset_kinds")
        private List<String> assetKinds;

        /** True = this option wants the cut to land on a music beat. */
        @JsonProperty("on_beat")
        private Boolean onBeat;

        /** Local music energy floor (0–1) — for punchy, beat-driven effects. */
        @JsonProperty("min_music_energy")
        private Double minMusicEnergy;

        /** Cut classifications this option fits: HARD, SOFT, MICRO (transitions). */
        @JsonProperty("classifications")
        private List<String> classifications;
    }

    /**
     * One scene pattern within a beat — effect + transition + optional
     * color grade that overrides the beat-level default.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ScenePattern {

        @JsonProperty("effect")
        private EdlEffect effect;

        @JsonProperty("transition")
        private EdlTransition transition;

        @JsonProperty("color_grade_override")
        private EdlColorGrade colorGradeOverride;
    }

    /** Audio configuration for the preset (volumes per beat, music style). */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class AudioConfig {

        @JsonProperty("voiceover_volume")
        private double voiceoverVolume;

        @JsonProperty("voiceover_fade_out_ms")
        private int voiceoverFadeOutMs;

        /** Music volume per beat — key is beat letter (A–E). */
        @JsonProperty("volume_by_beat")
        private Map<String, Double> volumeByBeat;

        @JsonProperty("music_fade_in_ms")
        private int musicFadeInMs;

        @JsonProperty("music_fade_out_ms")
        private int musicFadeOutMs;

        @JsonProperty("music_style")
        private String musicStyle;

        /**
         * How strongly the actual music energy modulates the per-beat volume
         * curve (0 = pure style curve, 0.25 = ±25% swing). Used by
         * MusicDynamicsPlanner to build volume_points.
         */
        @JsonProperty("energy_modulation")
        private Double energyModulation;

        /** Ramp duration between volume levels at beat boundaries. */
        @JsonProperty("ramp_ms")
        private Integer rampMs;
    }

    /**
     * Template for a text overlay generated by GPT.
     * The `placeholder` field matches a GPT output variable (e.g. {HOOK_TEXT}).
     * start_ms / end_ms are beat-relative defaults for 30s standard video.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class TextOverlayTemplate {

        @JsonProperty("beat")
        private String beat;

        @JsonProperty("placeholder")
        private String placeholder;

        @JsonProperty("type")
        private String type;

        @JsonProperty("start_ms")
        private int startMs;

        @JsonProperty("end_ms")
        private int endMs;

        @JsonProperty("layer")
        private int layer;

        @JsonProperty("style")
        private EdlTextOverlay.TextStyle style;

        /** Simplified position string ("center", "bottom_third", "bottom_quarter"). */
        @JsonProperty("position")
        private String position;

        @JsonProperty("animation")
        private String animation;

        @JsonProperty("animation_duration_ms")
        private int animationDurationMs;
    }

    /** Composition rules section — gates and scopes the layered composition. */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class CompositionRules {

        @JsonProperty("enabled")
        private boolean enabled;

        @JsonProperty("max_layered_scenes_pct")
        private Integer maxLayeredScenesPct;

        /** Rule name (TalkingHeadBg, ProductReveal, CtaOverlay) → its scope. */
        @JsonProperty("rules")
        private Map<String, CompositionRule> rules;
    }

    /**
     * Scope of one named composition rule. Layout params (opacity, scale,
     * position) stay in the JSON for the renderer; the decider only needs
     * the applicability scope.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class CompositionRule {

        @JsonProperty("enabled")
        private boolean enabled;

        /** Beat letters where the rule may fire. Null = any beat. */
        @JsonProperty("applicable_beats")
        private List<String> applicableBeats;

        /** "pip" (background behind primary) or "overlay" (on top). */
        @JsonProperty("composition")
        private String composition;

        /** Narration segment types that also trigger the rule. Null = beat-only. */
        @JsonProperty("trigger_segment_types")
        private List<String> triggerSegmentTypes;
    }
}
