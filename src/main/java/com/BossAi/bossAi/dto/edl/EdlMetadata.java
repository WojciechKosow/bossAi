package com.BossAi.bossAi.dto.edl;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class EdlMetadata {

    @JsonProperty("title")
    private String title;

    @JsonProperty("style")
    private String style;

    @JsonProperty("total_duration_ms")
    private int totalDurationMs;

    @JsonProperty("width")
    @Builder.Default
    private int width = 1080;

    @JsonProperty("height")
    @Builder.Default
    private int height = 1920;

    @JsonProperty("fps")
    @Builder.Default
    private int fps = 30;

    @JsonProperty("bpm")
    private Integer bpm;

    @JsonProperty("pacing")
    private String pacing;
}
