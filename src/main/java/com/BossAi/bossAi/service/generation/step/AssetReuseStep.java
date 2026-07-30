package com.BossAi.bossAi.service.generation.step;

import com.BossAi.bossAi.service.AssetReuseService;
import com.BossAi.bossAi.service.generation.GenerationContext;
import com.BossAi.bossAi.service.generation.GenerationStepName;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * AssetReuseStep — uruchamiany po ScriptStep, przed ImageStep.
 *
 * If reuseAssets=true in the context, it calls AssetReuseService
 * which uses GPT to match the user's earlier assets to the new scenes.
 *
 * Wynik: context.reusedImageAssets i context.reusedVideoAssets
 * get filled in. ImageStep and VideoStep check these maps
 * and skip generation for the matched scenes.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AssetReuseStep implements GenerationStep {

    private final AssetReuseService assetReuseService;

    @Override
    public void execute(GenerationContext context) throws Exception {
        if (!context.isReuseAssets()) {
            log.info("[AssetReuseStep] SKIP — reuseAssets=false, generationId: {}",
                    context.getGenerationId());
            return;
        }

        log.info("[AssetReuseStep] START — generationId: {}", context.getGenerationId());

        context.updateProgress(
                GenerationStepName.SCRIPT,
                GenerationStepName.SCRIPT.getProgressPercent() + 5,
                "Looking for matching assets from previous generations..."
        );

        assetReuseService.matchReusableAssets(context);

        int imageMatches = context.getReusedImageAssets() != null
                ? context.getReusedImageAssets().size() : 0;
        int videoMatches = context.getReusedVideoAssets() != null
                ? context.getReusedVideoAssets().size() : 0;

        log.info("[AssetReuseStep] DONE — {} IMAGE matches, {} VIDEO matches",
                imageMatches, videoMatches);
    }
}
