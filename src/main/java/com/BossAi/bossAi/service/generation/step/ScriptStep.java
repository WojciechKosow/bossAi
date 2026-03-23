package com.BossAi.bossAi.service.generation.step;

import com.BossAi.bossAi.service.OpenAiService;
import com.BossAi.bossAi.service.generation.GenerationContext;
import com.BossAi.bossAi.service.generation.GenerationStepName;
import com.BossAi.bossAi.service.generation.context.SceneAsset;
import com.BossAi.bossAi.service.generation.context.ScriptResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

/**
 * ScriptStep — wywołuje GPT-4o i buduje scenariusz TikTok Ad.
 *
 * Input:  context.prompt, context.userImageAssets (opcjonalne)
 * Output: context.script (ScriptResult), context.scenes (lista SceneAsset z promptami)
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ScriptStep implements GenerationStep {

    private final OpenAiService openAiService;

    @Override
    public void execute(GenerationContext context) throws Exception {
        context.updateProgress(
                GenerationStepName.SCRIPT,
                GenerationStepName.SCRIPT.getProgressPercent(),
                GenerationStepName.SCRIPT.getDisplayMessage()
        );

        log.info("[ScriptStep] START — generationId: {}, prompt: {}",
                context.getGenerationId(), context.getPrompt());

        // Budujemy wzbogacony prompt jeśli user dostarczył assety
        String enrichedPrompt = buildEnrichedPrompt(context);

        // Wywołanie GPT-4o — Resilience4j retry w OpenAiService
        ScriptResult script = openAiService.generateScript(enrichedPrompt);

        // Zapisujemy scenariusz do kontekstu
        context.setScript(script);

        // Budujemy SceneAsset dla każdej sceny — na razie tylko prompty,
        // imageUrl i videoLocalPath będą wypełnione przez następne Stepy
        List<SceneAsset> scenes = script.scenes().stream()
                .map(scene -> SceneAsset.builder()
                        .index(scene.index())
                        .imagePrompt(scene.imagePrompt())
                        .motionPrompt(scene.motionPrompt())
                        .durationMs(scene.durationMs())
                        .subtitleText(scene.subtitleText())
                        .build())
                .collect(Collectors.toList());

        context.setScenes(scenes);

        log.info("[ScriptStep] DONE — {} scen, hook: '{}', CTA: '{}'",
                scenes.size(),
                script.hook(),
                script.callToAction());
    }

    /**
     * Wzbogaca prompt usera o kontekst assetów jeśli je dostarczył.
     * GPT-4o dostaje więcej kontekstu → lepsze, bardziej spójne prompty scen.
     */
    private String buildEnrichedPrompt(GenerationContext context) {
        StringBuilder sb = new StringBuilder(context.getPrompt());

        if (!context.getUserImageAssets().isEmpty()) {
            sb.append("\n\nUser dostarczył ")
                    .append(context.getUserImageAssets().size())
                    .append(" zdjęcie(a) produktu — uwzględnij ich styl przy generowaniu promptów scen.");
        }

        if (context.hasUserVoice()) {
            sb.append("\n\nUser nagra własny voice-over — dostosuj długość narracji do typowego TikTok voice-over (max 40 słów).");
        }

        if (context.hasUserMusic()) {
            sb.append("\n\nUser dostarczył własną muzykę — scenariusz powinien być energetyczny i pasować do podkładu muzycznego.");
        }

        return sb.toString();
    }
}