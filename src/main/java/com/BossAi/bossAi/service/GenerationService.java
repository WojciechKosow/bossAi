package com.BossAi.bossAi.service;

import com.BossAi.bossAi.dto.GenerationDTO;
import com.BossAi.bossAi.entity.User;
import com.BossAi.bossAi.entity.UserPlan;
import com.BossAi.bossAi.request.GenerateImageRequest;
import com.BossAi.bossAi.request.GenerateVideoRequest;
import com.BossAi.bossAi.request.TikTokAdRequest;
import com.BossAi.bossAi.response.GenerationResponse;

import java.util.List;
import java.util.UUID;

public interface GenerationService {

    GenerationResponse generateTikTokAd(TikTokAdRequest request, String email) throws Exception;

    GenerationResponse generateImage(GenerateImageRequest request, String email) throws Exception;

    GenerationResponse generateVideo(GenerateVideoRequest request, String email);

    GenerationDTO getById(UUID id, String email);

    //    boolean canGenerateImage(User user);
//    boolean canGenerateVideo(User user);
    UserPlan selectPlanForImage(User user);

    UserPlan selectPlanForVideo(User user);

    List<GenerationDTO> getRecentGenerations(String email, int limit);

    List<GenerationDTO> getAllUserGenerations(String email);
}
