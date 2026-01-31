package com.BossAi.bossAi.ai.cloudflare.dto;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class CloudflareImageRequest {
    private String prompt;
}
