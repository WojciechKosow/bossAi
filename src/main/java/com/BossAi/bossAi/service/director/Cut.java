package com.BossAi.bossAi.service.director;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class Cut {

    private int startMs;
    private int endMs;

    private CutType type;
}
