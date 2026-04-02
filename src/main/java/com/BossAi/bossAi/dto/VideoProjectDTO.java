package com.BossAi.bossAi.dto;

import com.BossAi.bossAi.entity.ProjectStatus;
import com.BossAi.bossAi.entity.VideoStyle;
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
public class VideoProjectDTO {

    private UUID id;
    private String title;
    private String originalPrompt;
    private ProjectStatus status;
    private VideoStyle style;
    private UUID currentEdlId;
    private Integer currentEdlVersion;
    private UUID generationId;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
