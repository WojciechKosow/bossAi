package com.BossAi.bossAi.dto;

import com.BossAi.bossAi.entity.GenerationStatus;
import com.BossAi.bossAi.entity.GenerationType;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.UUID;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class GenerationDTO {
    private UUID id;
    private GenerationStatus status;
    private GenerationType type;
    private String imageUrl;
    private String videoUrl;
    private String errorMessage;
    private LocalDateTime createdAt;
    private LocalDateTime finishedAt;
}
