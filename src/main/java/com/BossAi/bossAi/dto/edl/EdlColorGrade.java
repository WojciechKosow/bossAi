package com.BossAi.bossAi.dto.edl;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Color grading parameters — applied as CSS filters in Remotion.
 * Values are CSS filter multipliers: 1.0 = no change.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class EdlColorGrade {

    @JsonProperty("preset")
    @Builder.Default
    private String preset = "neutral";

    @JsonProperty("contrast_boost")
    @Builder.Default
    private double contrastBoost = 1.0;

    @JsonProperty("saturation")
    @Builder.Default
    private double saturation = 1.0;

    @JsonProperty("brightness")
    @Builder.Default
    private double brightness = 1.0;

    @JsonProperty("vignette")
    @Builder.Default
    private double vignette = 0.0;
}
