package com.BossAi.bossAi.dto.edl;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

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

    /** URL do pliku audio — Remotion pobiera asset z tego URL */
    @JsonProperty("asset_url")
    private String assetUrl;

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

    /** @deprecated never consumed by the renderer — superseded by volumePoints. */
    @Deprecated
    @JsonProperty("volume_by_beat")
    private Map<String, Double> volumeByBeat;

    @JsonProperty("music_style")
    private String musicStyle;

    /**
     * Volume automation envelope — absolute volume values at timeline positions.
     * The renderer interpolates linearly between points and holds the last value.
     * When present, supersedes the static {@code volume} for this track.
     * Computed by MusicDynamicsPlanner from the style's per-beat curve modulated
     * by the actual music energy.
     */
    @JsonProperty("volume_points")
    private java.util.List<VolumePoint> volumePoints;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class VolumePoint {

        @JsonProperty("ms")
        private int ms;

        @JsonProperty("volume")
        private double volume;
    }
}
