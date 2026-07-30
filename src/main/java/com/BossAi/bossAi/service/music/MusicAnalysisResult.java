package com.BossAi.bossAi.service.music;

import java.util.List;

/**
 * Result of the music structure analysis — energy profile, segments (drop, build, peak, quiet).
 *
 * Used by MusicAlignmentService to intelligently align the music moment
 * to the video context (hook → drop, narration → quiet, CTA → peak).
 */
public record MusicAnalysisResult(

        /** Music duration in ms */
        int totalDurationMs,

        /**
         * Energy profile every 500ms — value 0.0-1.0 (normalized).
         * Index i = energia w przedziale [i*500ms, (i+1)*500ms).
         */
        List<Double> energyProfile,

        /** Wykryte segmenty muzyczne (drop, build-up, peak, quiet) */
        List<MusicSegment> segments,

        /** Average energy of the whole track */
        double averageEnergy,

        /** Tempo in BPM (approximate, from beat detection) */
        int estimatedBpm

) {

    /**
     * Music segment — a continuous fragment with a specific character.
     */
    public record MusicSegment(
            int startMs,
            int endMs,
            SegmentType type,
            /** Average segment energy 0.0-1.0 */
            double energy
    ) {}

    public enum SegmentType {
        /** Quiet fragment — low energy, good under narration */
        QUIET,
        /** Build-up — rising energy, leads to the drop */
        BUILD_UP,
        /** Drop — a sudden energy increase, the climactic moment */
        DROP,
        /** Peak — utrzymana wysoka energia */
        PEAK,
        /** Normal fragment — average energy */
        NORMAL
    }
}
