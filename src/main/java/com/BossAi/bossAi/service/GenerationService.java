package com.BossAi.bossAi.service;

import com.BossAi.bossAi.entity.Generation;
import com.BossAi.bossAi.request.GenerateImageRequest;
import com.BossAi.bossAi.request.GenerateVideoRequest;
import com.BossAi.bossAi.response.GenerationResponse;

import java.util.UUID;

public interface GenerationService {
    GenerationResponse generateImage(GenerateImageRequest request);
    GenerationResponse generateVideo(GenerateVideoRequest request);
    Generation getById(UUID id);
}
