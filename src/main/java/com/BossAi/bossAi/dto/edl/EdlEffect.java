package com.BossAi.bossAi.dto.edl;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * Efekt aplikowany na segment — zoom, shake, speed ramp, pan, etc.
 * Parametry w mapie params pozwalaja na rozszerzalnosc bez zmiany schematu.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class EdlEffect {

    @JsonProperty("type")
    private String type;

    @JsonProperty("start_ms")
    private Integer startMs;

    @JsonProperty("end_ms")
    private Integer endMs;

    @JsonProperty("intensity")
    @Builder.Default
    private double intensity = 1.0;

    @JsonProperty("params")
    private Map<String, Object> params;
}
