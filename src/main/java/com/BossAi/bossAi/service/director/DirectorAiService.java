package com.BossAi.bossAi.service.director;

import com.BossAi.bossAi.service.generation.GenerationContext;

public interface DirectorAiService {
    DirectorPlan generatePlan(GenerationContext context);
}
