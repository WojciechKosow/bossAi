package com.BossAi.bossAi.service.generation.step;

import com.BossAi.bossAi.entity.AssetSource;
import com.BossAi.bossAi.entity.AssetType;
import com.BossAi.bossAi.service.AssetService;
import com.BossAi.bossAi.service.FalAiService;
import com.BossAi.bossAi.service.generation.GenerationContext;
import com.BossAi.bossAi.service.generation.GenerationStepName;
import com.BossAi.bossAi.service.generation.ModelSelector;
import com.BossAi.bossAi.service.generation.context.SceneAsset;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * ImageStep — generuje obraz dla każdej sceny przez fal.ai.
 *
 * Input:  context.scenes[].imagePrompt, context.planType
 * Output: context.scenes[].imageUrl (URL na CDN fal.ai)
 *
 * Sceny są przetwarzane sekwencyjnie (nie równolegle) żeby:
 *   - nie przekroczyć rate limits fal.ai
 *   - mieć przewidywalny koszt per generacja
 * W Fazie 4 możemy dodać równoległość per scena dla planów PRO+.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ImageStep implements GenerationStep {

    private final FalAiService falAiService;
    private final AssetService assetService;
    private final ModelSelector modelSelector;

    @Override
    public void execute(GenerationContext context) throws Exception {
        List<SceneAsset> scenes = context.getScenes();
        String modelId = modelSelector.imageModel(context.getPlanType());

        log.info("[ImageStep] START — {} scen, model: {}, generationId: {}",
                scenes.size(), modelId, context.getGenerationId());

        for (int i = 0; i < scenes.size(); i++) {
            SceneAsset scene = scenes.get(i);

            // Aktualizuj progress per scena
            int progressBase = GenerationStepName.IMAGE.getProgressPercent();
            int progressPerScene = 10 / Math.max(scenes.size(), 1);
            context.updateProgress(
                    GenerationStepName.IMAGE,
                    progressBase + (i * progressPerScene),
                    String.format("Generuję obraz sceny %d/%d...", i + 1, scenes.size())
            );

            log.info("[ImageStep] Scena {}/{} — prompt: {}",
                    i + 1, scenes.size(), scene.getImagePrompt());

            // Generuj obraz przez fal.ai — Resilience4j retry w FalAiService
            String imageUrl = falAiService.generateImage(scene.getImagePrompt(), modelId);
            scene.setImageUrl(imageUrl);

            // Zapisz asset do bazy przez AssetService
            assetService.createAssetFromUrl(
                    context.getUserId(),
                    AssetType.IMAGE,
                    AssetSource.AI_GENERATED,
                    imageUrl,
                    context.getGenerationId()
            );

            log.info("[ImageStep] Scena {}/{} DONE — imageUrl: {}", i + 1, scenes.size(), imageUrl);
        }

        log.info("[ImageStep] DONE — wszystkie {} obrazy wygenerowane", scenes.size());
    }
}