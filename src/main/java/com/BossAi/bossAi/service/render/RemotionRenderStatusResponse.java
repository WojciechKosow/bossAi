package com.BossAi.bossAi.service.render;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Response ze statusu renderowania z mikroserwisu remotion-renderer.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record RemotionRenderStatusResponse(

        @JsonProperty("render_id")
        String renderId,

        @JsonProperty("status")
        String status,

        @JsonProperty("progress")
        double progress,

        @JsonProperty("output_url")
        String outputUrl,

        @JsonProperty("error")
        String error

) {

    public boolean isCompleted() {
        return "completed".equalsIgnoreCase(status);
    }

    public boolean isFailed() {
        return "failed".equalsIgnoreCase(status);
    }

    public boolean isInProgress() {
        return "rendering".equalsIgnoreCase(status) || "queued".equalsIgnoreCase(status);
    }
}
