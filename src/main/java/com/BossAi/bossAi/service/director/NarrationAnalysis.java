package com.BossAi.bossAi.service.director;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.*;

import java.util.List;

/**
 * Narration analysis — GPT breaks the script down into semantic segments.
 *
 * Each segment has:
 *   - text: a fragment of the narration
 *   - type: its role in the narration (hook, point, transition, cta, emphasis, setup)
 *   - importance: 0.0-1.0 — how important this fragment is to the message
 *   - energy: 0.0-1.0 — the dynamics/tempo of this fragment
 *   - topic: a thematic key (e.g. "intro", "automation", "summary")
 *   - keyword: the most important word in the segment (a good place to cut on)
 *
 * This is the FOUNDATION for justified cuts — instead of "when to cut"
 * it answers the question "WHY cut now?"
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class NarrationAnalysis {

    @JsonProperty("segments")
    private List<NarrationSegment> segments;

    @JsonProperty("editing_intent")
    private EditingIntent editingIntent;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class NarrationSegment {

        /** A fragment of the narration text */
        @JsonProperty("text")
        private String text;

        /**
         * Segment role:
         *   hook — opener, scroll-stopper
         *   setup — context, introduction
         *   point — main point/argument
         *   emphasis — emphasis, reinforcement
         *   transition — transition between topics
         *   cta — call to action
         *   climax — climactic point
         *   cooldown — wind-down after the peak
         */
        @JsonProperty("type")
        private String type;

        /** How important this fragment is (0.0-1.0). Higher = more worth highlighting visually */
        @JsonProperty("importance")
        private double importance;

        /** The dynamics/tempo of this fragment (0.0-1.0). Higher = faster cuts */
        @JsonProperty("energy")
        private double energy;

        /** Thematic key — a topic change = a potential HARD CUT */
        @JsonProperty("topic")
        private String topic;

        /** The most important word — a potential cut or emphasis point */
        @JsonProperty("keyword")
        private String keyword;

        /** Index of the segment in the narration (0-based) */
        @JsonProperty("index")
        private int index;
    }

    /**
     * Intent-Based Editing — GPT decides the CHARACTER of the edit.
     *
     * Instead of a random style, GPT analyzes the content + music + mood
     * and picks a specific editing strategy.
     *
     * Examples:
     *   intent=build_tension, pattern=slow_to_fast
     *   intent=rhythmic_pulse, pattern=on_beat_consistent
     *   intent=contrast_shock, pattern=long_hold_then_burst
     *   intent=flowing_narrative, pattern=breathing_with_pauses
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class EditingIntent {

        /**
         * Main editing intent:
         *   build_tension — rising tension, progressively faster cuts
         *   rhythmic_pulse — cuts on the music beat, dance-like
         *   contrast_shock — long shots broken by sudden cuts
         *   flowing_narrative — smooth transitions, breathing between thoughts
         *   staccato_energy — fast, sharp cuts on every sentence
         *   emotional_wave — cuts following the narrator's emotion
         *   reveal_punctuate — a long build-up to the reveal moment
         */
        @JsonProperty("intent")
        private String intent;

        /**
         * Tempo pattern:
         *   slow_to_fast — starts slow, speeds up
         *   fast_to_slow — opens strong, slows down
         *   wave — fast-slow-fast waves
         *   constant_high — consistently fast
         *   on_beat_consistent — steady, on the beat
         *   long_hold_then_burst — long shots, then a burst of quick ones
         *   breathing_with_pauses — natural breaths
         */
        @JsonProperty("pattern")
        private String pattern;

        /**
         * Editing arc — how the cut density changes over time.
         * GPT defines the film's phases with their mood and density.
         */
        @JsonProperty("arc")
        private List<EditingArc> arc;

        /**
         * A short justification for why this intent/pattern fits
         * this specific content + music.
         */
        @JsonProperty("reasoning")
        private String reasoning;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class EditingArc {

        /** Film phase: opening, buildup, middle, climax, resolution, outro */
        @JsonProperty("phase")
        private String phase;

        /** Cut density: very_low, low, medium, high, very_high */
        @JsonProperty("density")
        private String density;

        /** Phase mood: curious, building, intense, euphoric, reflective, urgent */
        @JsonProperty("mood")
        private String mood;

        /** Approximate percentage of the film at which this phase begins (0.0-1.0) */
        @JsonProperty("start_pct")
        private double startPct;
    }
}
