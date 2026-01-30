package com.BossAi.bossAi.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
public class AiVideoServiceImpl implements AiVideoService {
    @Override
    public String generateVideo(String prompt, String imageUrl) {
        try {
            Thread.sleep(3000);
        } catch (InterruptedException ignored) {}
        return "https://cdn.mock.ai/video.mp4";
    }
}
