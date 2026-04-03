package com.BossAi.bossAi.service.render;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;

import java.util.Map;

/**
 * Request DTO wysyłany do mikroserwisu remotion-renderer (Node.js).
 * Zawiera pełny EDL + konfigurację renderowania.
 */
@Builder
public record RemotionRenderRequest(

        @JsonProperty("render_id")
        String renderId,

        @JsonProperty("edl")
        Map<String, Object> edl,

        @JsonProperty("output_config")
        OutputConfig outputConfig

) {

    @Builder
    public record OutputConfig(

            @JsonProperty("width")
            int width,

            @JsonProperty("height")
            int height,

            @JsonProperty("fps")
            int fps,

            @JsonProperty("codec")
            String codec,

            @JsonProperty("quality")
            String quality

    ) {
        public static OutputConfig tiktokDefault() {
            return OutputConfig.builder()
                    .width(1080)
                    .height(1920)
                    .fps(30)
                    .codec("h264")
                    .quality("high")
                    .build();
        }
    }
}
