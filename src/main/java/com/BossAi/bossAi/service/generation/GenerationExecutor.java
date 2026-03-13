package com.BossAi.bossAi.service.generation;

import com.BossAi.bossAi.service.generation.step.GenerationStep;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
public class GenerationExecutor implements GenerationStep {

    private final List<GenerationStep> steps;

    @Override
    public void execute(GenerationContext context) throws Exception {
        for (GenerationStep step : steps) {
            step.execute(context);
        }
    }
}
