package com.BossAi.bossAi.dto;

import com.BossAi.bossAi.entity.AssetSource;
import com.BossAi.bossAi.entity.AssetStatus;
import com.BossAi.bossAi.entity.AssetType;
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
public class ProjectAssetDTO {

    private UUID id;
    private UUID projectId;
    private AssetType type;
    private AssetSource source;
    private AssetStatus status;
    private String storageUrl;
    private String thumbnailUrl;
    private String filename;
    private String mimeType;
    private Double durationSeconds;
    private Integer width;
    private Integer height;
    private Long fileSizeBytes;
    private String metadata;
    private LocalDateTime createdAt;
}
