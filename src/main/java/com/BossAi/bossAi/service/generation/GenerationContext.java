package com.BossAi.bossAi.service.generation;

import com.BossAi.bossAi.entity.Asset;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.UUID;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class GenerationContext {
    private UUID generationId;
    private String prompt;
    private String imageUrl;
    private List<Asset> images;
    private List<Asset> videos;

    public GenerationContext(UUID generationId, String prompt, String imageUrl) {
        this.generationId = generationId;
        this.prompt = prompt;
        this.imageUrl = imageUrl;
    }
}
