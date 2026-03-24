package com.BossAi.bossAi.service.style;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class StyleConfig {

    private String promptInstructions;

    private String energyLevel;

    private String pacing;
}
