package com.BossAi.bossAi.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class UsageDTO {
    private int imagesUsed;
    private int imagesTotal;
    private int videosUsed;
    private int videosTotal;
}
