package com.BossAi.bossAi.dto;

import com.BossAi.bossAi.entity.RenderStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RenderJobDTO {

    private UUID id;
    private UUID projectId;
    private UUID edlVersionId;
    private RenderStatus status;
    private Double progress;
    private String outputUrl;
    private String quality;
    private LocalDateTime startedAt;
    private LocalDateTime completedAt;
}
