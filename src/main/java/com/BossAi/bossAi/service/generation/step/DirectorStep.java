package com.BossAi.bossAi.service.generation.step;

import com.BossAi.bossAi.service.director.Cut;
import com.BossAi.bossAi.service.director.CutType;
import com.BossAi.bossAi.service.director.DirectorPlan;
import com.BossAi.bossAi.service.director.SceneDirection;
import com.BossAi.bossAi.service.generation.GenerationContext;
import com.BossAi.bossAi.service.generation.GenerationStepName;
import com.BossAi.bossAi.service.generation.context.SceneAsset;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.checkerframework.checker.units.qual.C;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class DirectorStep implements GenerationStep {

    @Override
    public void execute(GenerationContext context) throws Exception {

        context.updateProgress(
                GenerationStepName.SCRIPT,
                context.getProgressPercent(),
                "Creating edit plan..."
        );

        List<SceneDirection> directions = new ArrayList<>();

        for (SceneAsset scene : context.getScenes()) {
            List<Cut> cuts = generateCuts(scene.getDurationMs(), context);

            directions.add(SceneDirection.builder()
                    .sceneIndex(scene.getIndex())
                    .cuts(cuts)
                    .transitionToNext("cut")
                    .build());
        }

        DirectorPlan plan = DirectorPlan.builder()
                .style(context.getStyle())
                .pacing(context.getStyleConfig().getPacing())
                .energyLevel(context.getStyleConfig().getEnergyLevel())
                .scenes(directions)
                .build();

        context.setDirectorPlan(plan);
        log.info("[DirectorStep] DONE — {} scenes with cuts", directions.size());
    }

    private List<Cut> generateCuts(int durationMs, GenerationContext context) {
        ArrayList<Cut> cuts = new ArrayList<>();

        int step;

        switch (context.getStyleConfig().getPacing()) {
            case "FAST" -> step = 500;
            case "MEDIUM" -> step = 1000;
            case "SLOW" -> step = durationMs;
            default -> step = 1000;
        }

        int current = 0;

        while (current < durationMs) {
            int end = Math.min(current + step, durationMs);

            cuts.add(Cut.builder()
                    .startMs(current)
                    .endMs(end)
                    .type(resolveCutType(context))
                    .build());

            current += step;
        }

        return cuts;
    }

    private CutType resolveCutType(GenerationContext context) {
        return switch (context.getStyleConfig().getEnergyLevel()) {
            case "HIGH" -> CutType.FAST;
            case "LOW" -> CutType.SLOW;
            default -> CutType.NORMAL;
        };
    }
}
