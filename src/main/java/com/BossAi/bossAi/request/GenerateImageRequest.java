package com.BossAi.bossAi.request;

import lombok.Data;

@Data
public class GenerateImageRequest {
    private String prompt;
    private String imageUrl;
}
