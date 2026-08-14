package com.BossAi.bossAi.dto;

import com.BossAi.bossAi.entity.Clip;
import com.BossAi.bossAi.entity.ClipStatus;

import java.util.UUID;

/**
 * API view of a single produced clip.
 */
public record ClipDto(
        UUID id,
        int clipIndex,
        ClipStatus status,
        String title,
        String reasoning,
        Integer score,
        int sourceStartMs,
        int sourceEndMs,
        int durationMs,
        String downloadUrl,
        String errorMessage
) {
    public static ClipDto from(Clip clip) {
        return new ClipDto(
                clip.getId(),
                clip.getClipIndex(),
                clip.getStatus(),
                clip.getTitle(),
                clip.getReasoning(),
                clip.getScore(),
                clip.getSourceStartMs(),
                clip.getSourceEndMs(),
                clip.durationMs(),
                clip.getOutputUrl(),
                clip.getErrorMessage()
        );
    }
}
