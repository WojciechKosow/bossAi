package com.BossAi.bossAi.service;

import org.springframework.stereotype.Service;

@Service
public class AiImageServiceImpl implements AiImageService {
    @Override
    public String generateImage(String prompt, String imageUrl) {
        try {
            Thread.sleep(3000);
        } catch (InterruptedException ignored) {}
        return "https://cdn.mock.ai/image.png";
    }
}
