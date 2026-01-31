package com.BossAi.bossAi.request;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class GenerateVideoRequest {
    @NotBlank
    private String prompt;
    private String imageUrl;
}
