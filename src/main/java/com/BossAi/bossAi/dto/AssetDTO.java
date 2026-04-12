package com.BossAi.bossAi.dto;

import com.BossAi.bossAi.entity.AssetSource;
import com.BossAi.bossAi.entity.AssetType;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.UUID;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class AssetDTO {
    private UUID id;
    private AssetType type;
    private AssetSource source;
    private String url;
    private Integer orderIndex;
    private Integer durationSeconds;
    private Integer width;
    private Integer height;
    private LocalDateTime createdAt;
    private LocalDateTime expiresAt;
}
