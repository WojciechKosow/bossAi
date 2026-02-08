package com.BossAi.bossAi.dto;

import com.BossAi.bossAi.entity.PlanType;
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
public class UserPlanDTO {
    private UUID id;
    private PlanType type;
    private int imagesTotal;
    private int videosTotal;
    private int imagesUsed;
    private int videosUsed;
    private LocalDateTime activatedAt;
    private LocalDateTime expiresAt;
    private boolean active;
}
