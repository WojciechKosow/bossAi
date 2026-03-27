package com.BossAi.bossAi.service.director;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Cut {

    private int startMs;
    private int endMs;

    private String shotType;
    private String cameraMovement;
    private String energy;

    private CutType type;
    private EffectType effect;
}
