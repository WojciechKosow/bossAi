package com.BossAi.bossAi.service;

import com.BossAi.bossAi.dto.GenerationDTO;
import com.BossAi.bossAi.entity.Generation;
import com.BossAi.bossAi.request.GenerateImageRequest;
import com.BossAi.bossAi.request.GenerateVideoRequest;
import com.BossAi.bossAi.response.GenerationResponse;

import java.util.UUID;

public interface GenerationService {
    GenerationResponse generateImage(GenerateImageRequest request, String email);
    GenerationResponse generateVideo(GenerateVideoRequest request, String email);
    GenerationDTO getById(UUID id, String email);
}
