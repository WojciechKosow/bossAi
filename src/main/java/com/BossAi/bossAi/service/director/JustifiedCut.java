package com.BossAi.bossAi.service.director;

import lombok.*;

import java.util.List;

/**
 * Justified cut — every cut has a REASON.
 *
 * Instead of a formulaic "cut every 2 seconds" or "cut on every beat",
 * each cut has a concrete justification based on:
 *   - NarrationAnalysis (content, importance, topic)
 *   - SpeechTimingAnalysis (pauses, tempo, sentence boundaries)
 *   - AudioAnalysis (music, beat, energy)
 *   - EditingIntent (editing intent)
 *
 * Film grammar:
 *   DON'T: cut in the middle of a word, in the middle of a thought
 *   DO: cut at the end of a sentence, on a keyword, on a context change
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class JustifiedCut {

    /** Cut start (ms) */
    private int startMs;

    /** Cut end (ms) */
    private int endMs;

    /**
     * Cut type:
     *   HARD — frame change, new topic/important information
     *   SOFT — a light change (zoom, pan), sentence end
     *   MICRO — a dynamic cutaway, a quick change in a series
     */
    private CutClassification classification;

    /**
     * Main reason for the cut — why NOW?
     */
    private CutReason primaryReason;

    /**
     * Additional reasons (may overlap, e.g. topic_change + pause + beat)
     */
    private List<CutReason> secondaryReasons;

    /**
     * Decision confidence (0.0-1.0). Higher = stronger justification.
     * A cut with confidence < 0.4 may be skipped by the engine.
     */
    private double confidence;

    /** Index of the narration segment (from NarrationAnalysis) at the cut moment */
    private int narrationSegmentIndex;

    /** Music energy at the cut moment (0.0-1.0), null if there's no music */
    private Double musicEnergy;

    /** Whether the cut lands on a music beat (±50ms) */
    private boolean onBeat;

    /**
     * Editing-arc phase (from EditingIntent.arc) at the cut moment.
     * E.g. "opening", "climax", "resolution"
     */
    private String editingPhase;

    /**
     * Suggested visual effect on this cut (based on context).
     * CutEngine suggests it, but EdlGenerator may override it.
     */
    private String suggestedEffect;

    /**
     * Suggested transition to the next segment.
     */
    private String suggestedTransition;

    /**
     * Explicitly assigned asset index (from UserEditIntent / layer D).
     * -1 = no assignment, use the scene-based fallback.
     * >= 0 = MUST use this asset (user explicitly requested it).
     */
    @Builder.Default
    private int assignedAssetIndex = -1;

    // ─── Enums ──────────────────────────────────────────────────────

    public enum CutClassification {
        /**
         * HARD CUT — a full frame change.
         * Triggers: topic change, importance > 0.75, hook start, beat drop
         */
        HARD,

        /**
         * SOFT CUT — a light change (zoom, pan, subtle transition).
         * Triggers: sentence end + pause, energy drop, a breath
         */
        SOFT,

        /**
         * MICRO CUT — a dynamic cutaway, a quick series.
         * Triggers: high energy (>0.8), fast speech tempo, music drop
         */
        MICRO
    }

    public enum CutReason {
        /** Topic change in the narration */
        TOPIC_CHANGE,

        /** High segment importance (importance > 0.75) */
        HIGH_IMPORTANCE,

        /** Hook start (scroll-stopper) */
        HOOK_START,

        /** Sentence end + pause in speech */
        SENTENCE_END_PAUSE,

        /** Narration energy drop */
        ENERGY_DROP,

        /** Narration energy rise */
        ENERGY_RISE,

        /** High speech energy (>0.8) */
        HIGH_ENERGY_BURST,

        /** Speech tempo change (from fast to slow or vice versa) */
        TEMPO_SHIFT,

        /** Cut on a music beat */
        MUSIC_BEAT,

        /** Music drop (high music energy) */
        MUSIC_DROP,

        /** Keyword — a cut on an important word */
        KEYWORD_EMPHASIS,

        /** Dramatic pause (>800ms) */
        DRAMATIC_PAUSE,

        /** Editing-arc requirement (cut density in a given phase) */
        ARC_DENSITY,

        /** Minimum/maximum shot-duration requirement */
        DURATION_CONSTRAINT,

        /** Call to action — a cut before/on the CTA */
        CTA_TRANSITION,

        /** Viewer attention reset — a break in the monotony */
        ATTENTION_RESET
    }
}
