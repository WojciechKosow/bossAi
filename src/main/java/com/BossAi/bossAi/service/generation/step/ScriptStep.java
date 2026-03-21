package com.BossAi.bossAi.service.generation.step;

import com.BossAi.bossAi.service.generation.GenerationContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

// =============================================================================
// STUBY KROKÓW PIPELINE — Faza 0
//
// Każdy Step jest @Service żeby Spring mógł je wstrzyknąć do PipelineConfig.
// Implementacje wypełniamy w Fazie 1 (ScriptStep, ImageStep...) i Fazie 2 (RenderStep).
//
// Dlaczego stuby zamiast TODO w jednym pliku?
// Bo PipelineConfig kompiluje się i Spring context startuje — możemy testować
// wiring i postęp generacji bez zaimplementowanych kroków.
// =============================================================================

/**
 * STUB — zastąpiony pełną implementacją w Fazie 1.
 * GPT-4o generuje JSON scenariusza TikTok Ad.
 */
@Slf4j
@Service
public class ScriptStep implements GenerationStep {

    @Override
    public void execute(GenerationContext context) throws Exception {
        log.warn("[ScriptStep] STUB — brak implementacji. GenerationId: {}",
                context.getGenerationId());
        // TODO Faza 1: wywołaj OpenAiService.generateScript(context.getPrompt())
        //              zdeserializuj odpowiedź do ScriptResult
        //              wypełnij context.setScript(scriptResult)
        //              zbuduj context.getScenes() z ScriptResult.scenes()
        throw new UnsupportedOperationException("ScriptStep not yet implemented — Faza 1");
    }
}