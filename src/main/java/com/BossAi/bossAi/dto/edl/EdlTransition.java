package com.BossAi.bossAi.dto.edl;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * Przejscie miedzy segmentami — xfade, cut, dissolve, wipe, etc.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class EdlTransition {

    @JsonProperty("type")
    private String type;

    @JsonProperty("duration_ms")
    @Builder.Default
    private int durationMs = 300;

    @JsonProperty("params")
    private Map<String, Object> params;
}
