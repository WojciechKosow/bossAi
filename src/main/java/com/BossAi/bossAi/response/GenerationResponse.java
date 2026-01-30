package com.BossAi.bossAi.response;

import com.BossAi.bossAi.entity.GenerationStatus;
import lombok.AllArgsConstructor;
import lombok.Data;

import java.util.UUID;

@Data
@AllArgsConstructor
public class GenerationResponse {
    private UUID generationId;
    private GenerationStatus status;
}
