package com.BossAi.bossAi.service.render;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Response z mikroserwisu remotion-renderer po zleceniu renderowania.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record RemotionRenderResponse(

        @JsonProperty("render_id")
        String renderId,

        @JsonProperty("status")
        String status,

        @JsonProperty("message")
        String message

) {}
