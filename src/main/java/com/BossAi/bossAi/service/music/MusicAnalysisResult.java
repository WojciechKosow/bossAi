package com.BossAi.bossAi.service.music;

import java.util.List;

/**
 * Wynik analizy struktury muzyki — energy profile, segmenty (drop, build, peak, quiet).
 *
 * Used by MusicAlignmentService to intelligently align the music moment
 * do kontekstu wideo (hook → drop, narracja → quiet, CTA → peak).
 */
public record MusicAnalysisResult(

        /** Czas trwania muzyki w ms */
        int totalDurationMs,

        /**
         * Profil energii co 500ms — wartość 0.0-1.0 (znormalizowana).
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
     * Segment muzyczny — ciągły fragment o określonym charakterze.
     */
    public record MusicSegment(
            int startMs,
            int endMs,
            SegmentType type,
            /** Average segment energy 0.0-1.0 */
            double energy
    ) {}

    public enum SegmentType {
        /** Cichy fragment — niska energia, dobry pod narrację */
        QUIET,
        /** Build-up — rosnąca energia, prowadzi do dropu */
        BUILD_UP,
        /** Drop — nagły wzrost energii, moment kulminacyjny */
        DROP,
        /** Peak — utrzymana wysoka energia */
        PEAK,
        /** Normalny fragment — średnia energia */
        NORMAL
    }
}
