package com.BossAi.bossAi.service.generation.step;

import com.BossAi.bossAi.service.director.AssetAnalyzer;
import com.BossAi.bossAi.service.director.AssetProfile;
import com.BossAi.bossAi.service.director.UserEditIntent;
import com.BossAi.bossAi.service.director.UserIntentParser;
import com.BossAi.bossAi.service.generation.GenerationContext;
import com.BossAi.bossAi.service.generation.GenerationStepName;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * AssetAnalysisStep — analizuje custom media assety PRZED ScriptStep.
 *
 * Cel:
 *   ScriptStep musi mieć wizualny opis assetów żeby GPT mógł napisać narrację
 *   dopasowaną do rzeczywistej zawartości assetów (nie strzelać w ciemno).
 *
 * Co robi:
 *   1. AssetAnalyzer — GPT-4o Vision analizuje każdy asset (IMAGE/VIDEO keyframe)
 *      → wypełnia context.assetProfiles (visualDescription, role, mood, tags)
 *   2. UserIntentParser — parsuje prompt usera → UserEditIntent
 *      (placement hints, scene descriptions, pacing preference)
 *
 * Oba są opcjonalne — przy błędzie loguje warning i kontynuuje.
 * ScriptStep radzi sobie bez profili, ale wynik jest gorszy.
 *
 * Skipped gdy:
 *   - Brak custom media (context.hasCustomMedia() == false)
 *   - Profile już ustawione (context.getAssetProfiles() != null) — unika
 *     podwójnej analizy gdy krok jest wywołany ponownie lub przez test.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AssetAnalysisStep implements GenerationStep {

    private final AssetAnalyzer assetAnalyzer;
    private final UserIntentParser userIntentParser;

    @Override
    public void execute(GenerationContext context) throws Exception {
        if (!context.hasCustomMedia()) {
            log.info("[AssetAnalysisStep] Brak custom media — pomijam analizę");
            return;
        }

        // Skip if already populated (e.g. test re-run or future retry)
        if (context.getAssetProfiles() != null && !context.getAssetProfiles().isEmpty()) {
            log.info("[AssetAnalysisStep] Profile już dostępne ({}) — pomijam", context.getAssetProfiles().size());
            return;
        }

        context.updateProgress(
                GenerationStepName.SCRIPT,
                GenerationStepName.SCRIPT.getProgressPercent() - 5,
                "Analizuję twoje assety..."
        );

        log.info("[AssetAnalysisStep] START — {} assetów do analizy, generationId: {}",
                context.getCustomMediaAssets().size(), context.getGenerationId());

        // Step 1: Vision analysis
        List<AssetProfile> profiles = List.of();
        try {
            profiles = assetAnalyzer.analyzeAssets(
                    context.getCustomMediaAssets(), context.getPrompt());
            context.setAssetProfiles(profiles);
            log.info("[AssetAnalysisStep] Vision analysis OK — {} profili", profiles.size());
            for (int i = 0; i < profiles.size(); i++) {
                AssetProfile p = profiles.get(i);
                log.info("[AssetAnalysisStep]   asset[{}]: role={}, mood={}, desc={}",
                        i, p.getSuggestedRole(), p.getMood(),
                        p.getVisualDescription() != null
                                ? p.getVisualDescription().substring(0, Math.min(80, p.getVisualDescription().length()))
                                : "null");
            }
        } catch (Exception e) {
            log.warn("[AssetAnalysisStep] Vision analysis FAILED — ScriptStep nadal ruszy bez profili: {}",
                    e.getMessage());
        }

        // Step 2: Intent parsing
        try {
            UserEditIntent editIntent = userIntentParser.parseIntent(
                    context.getPrompt(),
                    context.getCustomMediaAssets(),
                    profiles);
            context.setUserEditIntent(editIntent);
            log.info("[AssetAnalysisStep] Intent parsed — explicit: {}, placements: {}, pacing: {}",
                    editIntent.hasExplicitInstructions(),
                    editIntent.getPlacements() != null ? editIntent.getPlacements().size() : 0,
                    editIntent.getPacingPreference());
        } catch (Exception e) {
            log.warn("[AssetAnalysisStep] Intent parsing FAILED — kontynuuję bez intent: {}", e.getMessage());
        }

        log.info("[AssetAnalysisStep] DONE — profile: {}, intent: {}",
                context.getAssetProfiles() != null ? context.getAssetProfiles().size() : 0,
                context.getUserEditIntent() != null ? "OK" : "null");
    }
}
