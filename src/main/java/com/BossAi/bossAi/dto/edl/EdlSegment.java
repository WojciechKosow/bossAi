package com.BossAi.bossAi.dto.edl;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Segment na timeline — pojedynczy klip wideo/obraz.
 * Referencjonuje asset po UUID (nie po sciezce pliku).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class EdlSegment {

    @JsonProperty("id")
    private String id;

    @JsonProperty("asset_id")
    private String assetId;

    @JsonProperty("asset_type")
    private String assetType;

    @JsonProperty("start_ms")
    private int startMs;

    @JsonProperty("end_ms")
    private int endMs;

    @JsonProperty("trim_in_ms")
    @Builder.Default
    private int trimInMs = 0;

    @JsonProperty("trim_out_ms")
    private Integer trimOutMs;

    @JsonProperty("layer")
    @Builder.Default
    private int layer = 0;

    @JsonProperty("effects")
    private List<EdlEffect> effects;

    @JsonProperty("transition")
    private EdlTransition transition;

    public int getDurationMs() {
        return endMs - startMs;
    }
}
