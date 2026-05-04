package com.BossAi.bossAi.service.dna;

import com.BossAi.bossAi.dto.edl.EdlColorGrade;
import com.BossAi.bossAi.dto.edl.EdlSubtitleConfig;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * User-supplied overrides applied on top of a DNA preset config.
 * All fields are optional — null means "use preset default".
 *
 * Priority (highest to lowest):
 *   1. textPlaceholderOverrides — from user prompt text
 *   2. colorGradeOverride / pacingOverride / fontFamilyOverride — from DNA video analysis
 *   3. subtitleConfigOverride / volumeByBeatOverride — from manual form
 *   4. Preset default (fallback)
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UserDnaInput {

    /**
     * Text content for named placeholders in text overlay templates.
     * Keys match placeholder names without braces, e.g. "HOOK_TEXT", "RESULT_TEXT", "CTA_TEXT".
     * Resolved by TextOverlayGeneratorService at overlay generation time (Step 6).
     */
    private Map<String, String> textPlaceholderOverrides;

    /** Overrides global color_grade from preset. Source: DNA video analysis or manual form. */
    private EdlColorGrade colorGradeOverride;

    /** Overrides pacing ("FAST", "MEDIUM", "SLOW"). Source: DNA video analysis (avg cut duration). */
    private String pacingOverride;

    /**
     * Overrides font_family in subtitle_config and all text overlay templates.
     * Source: DNA video OCR font detection.
     */
    private String fontFamilyOverride;

    /** Full subtitle config override from manual form — replaces preset subtitle_config entirely. */
    private EdlSubtitleConfig subtitleConfigOverride;

    /** Overrides per-beat music volume map. Source: manual form or advanced DNA analysis. */
    private Map<String, Double> volumeByBeatOverride;

    /** Overrides music_style hint passed to Suno. Source: user prompt or manual form. */
    private String musicStyleOverride;

    /**
     * BPM extracted from user's reference track or DNA video.
     * Used by MusicAlignmentService — stored here for propagation, not directly applied by DnaPresetService.
     */
    private Integer bpmOverride;
}
