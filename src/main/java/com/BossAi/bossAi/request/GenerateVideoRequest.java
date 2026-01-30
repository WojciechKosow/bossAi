package com.BossAi.bossAi.request;

import lombok.Data;

@Data
public class GenerateVideoRequest {
    private String prompt;
    private String imageUrl;
}
