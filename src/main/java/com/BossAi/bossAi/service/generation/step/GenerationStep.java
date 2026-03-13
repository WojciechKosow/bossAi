package com.BossAi.bossAi.service.generation.step;

import com.BossAi.bossAi.entity.Generation;
import com.BossAi.bossAi.service.generation.GenerationContext;

public interface GenerationStep {
    void execute(GenerationContext context) throws Exception;
}
