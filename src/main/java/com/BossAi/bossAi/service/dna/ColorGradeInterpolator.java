package com.BossAi.bossAi.service.dna;

import com.BossAi.bossAi.dto.edl.EdlColorGrade;
import com.BossAi.bossAi.dto.edl.EdlDto;
import com.BossAi.bossAi.dto.edl.EdlSegment;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * Applies per-segment color_grade_override based on the DNA preset beat arc.
 *
 * Two modes per segment:
 *   - Segment has beat field (set by GPT or AssetClassifier) →
 *       use the exact EdlColorGrade from DnaPresetConfig.beats[beat].colorGradeOverride
 *   - Segment has no beat field →
 *       linearly interpolate between beat A (cold/desaturated) and beat E (warm/saturated)
 *       based on the segment's position in the timeline
 *
 * Only layer-0 segments receive color grade — overlay layers (layer > 0) are skipped.
 * Already-set color_grade_override values are replaced to ensure the DNA arc is applied
 * consistently (GPT's per-segment grades may be inconsistent).
 */
@Slf4j
@Service
public class ColorGradeInterpolator {

    /**
     * Mutates each qualifying segment in the EDL with a computed color_grade_override.
     * Safe to call when dnaConfig has no beat definitions — does nothing.
     */
    public void interpolate(EdlDto edl, DnaPresetConfig dnaConfig) {
        if (edl.getSegments() == null || edl.getSegments().isEmpty()) return;

        Map<String, DnaPresetConfig.BeatConfig> beats = dnaConfig.getBeats();
        if (beats == null || beats.isEmpty()) return;

        // Anchor grades: A = coldest, E = warmest
        EdlColorGrade gradeA = beatGrade(beats, "A");
        EdlColorGrade gradeE = beatGrade(beats, "E");
        if (gradeA == null || gradeE == null) return;

        int totalDurationMs = edl.getMetadata() != null
                ? edl.getMetadata().getTotalDurationMs() : 0;

        int applied = 0;
        for (EdlSegment seg : edl.getSegments()) {
            if (seg.getLayer() > 0) continue; // overlays inherit from primary layer

            EdlColorGrade grade = resolveGrade(seg, beats, gradeA, gradeE, totalDurationMs);
            if (grade != null) {
                seg.setColorGradeOverride(grade);
                applied++;
            }
        }

        log.info("[ColorGradeInterpolator] Applied color_grade_override to {}/{} segments",
                applied, edl.getSegments().size());
    }

    // ─── Resolution ────────────────────────────────────────────────────────────

    private EdlColorGrade resolveGrade(EdlSegment seg,
                                        Map<String, DnaPresetConfig.BeatConfig> beats,
                                        EdlColorGrade gradeA,
                                        EdlColorGrade gradeE,
                                        int totalDurationMs) {
        String beat = seg.getBeat();

        // Mode 1: beat assigned → use exact beat color grade
        if (beat != null && !beat.isBlank()) {
            EdlColorGrade beatGrade = beatGrade(beats, beat.toUpperCase());
            if (beatGrade != null) return beatGrade;
        }

        // Mode 2: no beat → interpolate by timeline position
        if (totalDurationMs <= 0) return gradeA; // can't interpolate without duration
        double progress = Math.min(1.0, Math.max(0.0, (double) seg.getStartMs() / totalDurationMs));
        return interpolate(gradeA, gradeE, progress);
    }

    private EdlColorGrade beatGrade(Map<String, DnaPresetConfig.BeatConfig> beats, String letter) {
        DnaPresetConfig.BeatConfig beat = beats.get(letter);
        return beat != null ? beat.getColorGradeOverride() : null;
    }

    // ─── Interpolation ─────────────────────────────────────────────────────────

    /**
     * Linear interpolation between two color grades.
     * progress=0.0 → returns from, progress=1.0 → returns to.
     */
    private EdlColorGrade interpolate(EdlColorGrade from, EdlColorGrade to, double progress) {
        return EdlColorGrade.builder()
                .preset("problem_payoff_arc")
                .saturation(lerp(from.getSaturation(),     to.getSaturation(),     progress))
                .contrastBoost(lerp(from.getContrastBoost(), to.getContrastBoost(), progress))
                .brightness(lerp(from.getBrightness(),     to.getBrightness(),     progress))
                .vignette(lerp(from.getVignette(),         to.getVignette(),       progress))
                .build();
    }

    private double lerp(double from, double to, double t) {
        return from + (to - from) * t;
    }
}
