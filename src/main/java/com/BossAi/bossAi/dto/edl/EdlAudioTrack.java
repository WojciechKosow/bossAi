package com.BossAi.bossAi.dto.edl;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Sciezka audio na timeline — voiceover, muzyka, SFX.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class EdlAudioTrack {

    @JsonProperty("id")
    private String id;

    @JsonProperty("asset_id")
    private String assetId;

    @JsonProperty("type")
    private String type;

    @JsonProperty("start_ms")
    @Builder.Default
    private int startMs = 0;

    @JsonProperty("end_ms")
    private Integer endMs;

    @JsonProperty("volume")
    @Builder.Default
    private double volume = 1.0;

    @JsonProperty("fade_in_ms")
    @Builder.Default
    private int fadeInMs = 0;

    @JsonProperty("fade_out_ms")
    @Builder.Default
    private int fadeOutMs = 0;

    @JsonProperty("trim_in_ms")
    @Builder.Default
    private int trimInMs = 0;

    @JsonProperty("trim_out_ms")
    private Integer trimOutMs;
}
