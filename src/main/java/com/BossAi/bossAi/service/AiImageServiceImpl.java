package com.BossAi.bossAi.service;

import org.springframework.stereotype.Service;

@Service
public class AiImageServiceImpl implements AiImageService {
    @Override
    public byte[] generateImage(String prompt, String imageUrl) {
        try {
            Thread.sleep(3000);
        } catch (InterruptedException ignored) {
        }
        return new byte[]{1, 2, 3};
    }
}
