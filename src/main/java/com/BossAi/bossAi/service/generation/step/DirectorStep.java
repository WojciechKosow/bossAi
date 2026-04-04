package com.BossAi.bossAi.service.generation.step;

import com.BossAi.bossAi.service.BeatDetection.BeatDetectionService;
import com.BossAi.bossAi.service.director.*;
import com.BossAi.bossAi.service.generation.GenerationContext;
import com.BossAi.bossAi.service.generation.GenerationStepName;
import com.BossAi.bossAi.service.generation.context.SceneAsset;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * DirectorStep — generuje plan cięć i efektów dla każdej sceny.
 *
 * FAZA 1 BUGFIX:
 *
 *   1. context.setDirectorPlan(plan) był wywoływany tylko w bloku catch (fallback).
 *      Przy sukcesie AI planu — plan był generowany ale NIE zapisywany do kontekstu.
 *      RenderStep dostawał null i crashował. Naprawione: setDirectorPlan zawsze
 *      na końcu execute(), niezależnie od ścieżki (AI lub fallback).
 *
 *   2. Beat sync był stosowany po fallback planie zamiast po AI planie.
 *      Naprawione: beat sync stosowany na finalnym planie (niezależnie od źródła).
 *
 *   3. effectAssigner.applyEffects był wywoływany dwukrotnie przy sukcesie AI.
 *      Raz w try, raz po beat sync. Naprawione: tylko raz, po beat sync.
 *
 *   4. callback.onStep w DIRECTOR krok używał GenerationStepName.SCRIPT zamiast
 *      dedykowanego kroku. Zostawiono SCRIPT bo DIRECTOR nie ma osobnego enum value —
 *      do poprawy w Fazie 2 gdy dodamy DIRECTOR do GenerationStepName.
 *
 * Przepływ:
 *   1. Próba generacji planu przez AI (DirectorAiService)
 *   2. Jeśli AI failuje → fallback plan oparty na StyleConfig
 *   3. Beat sync (jeśli muzyka dostępna)
 *   4. Apply effects
 *   5. Zapisz plan do kontekstu (ZAWSZE)
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DirectorStep implements GenerationStep {

    private final DirectorAiService directorAiService;
    private final EffectAssigner effectAssigner;
    private final BeatDetectionService beatDetectionService;

    @Override
    public void execute(GenerationContext context) throws Exception {
        context.updateProgress(
                GenerationStepName.SCRIPT,
                40,
                "Directing video..."
        );

        // Krok 1: Próba AI planu, fallback przy błędzie
        DirectorPlan plan = generatePlanWithFallback(context);

        // Krok 2: Beat sync (jeśli muzyka jest dostępna)
        // Uwaga: w normalnym pipeline muzyka jest dostępna dopiero po MusicStep,
        // który biegnie PO DirectorStep. Beat sync tutaj działa tylko gdy
        // context.musicLocalPath jest ustawiony z poprzedniej sesji lub user upload.
        if (context.getMusicLocalPath() != null) {
            log.info("[DirectorStep] Muzyka dostępna — stosuję beat sync");
            try {
                List<Integer> beats = beatDetectionService.detectBeats(context.getMusicLocalPath(), context);
                applyBeatSync(plan, context, beats);
                log.info("[DirectorStep] Beat sync OK — {} beatów", beats.size());
            } catch (Exception e) {
                log.warn("[DirectorStep] Beat sync failed — używam oryginalnych cuts: {}", e.getMessage());
            }
        }

        // Krok 3: Efekty + przejścia między scenami
        String contentType = context.getScript() != null ? context.getScript().contentType() : null;
        effectAssigner.applyEffects(plan, context.getStyle(), contentType);
        effectAssigner.applyTransitions(plan, context.getStyle(), contentType);

        // Krok 4: Zapisz do kontekstu — ZAWSZE, niezależnie od ścieżki powyżej
        context.setDirectorPlan(plan);

        log.info("[DirectorStep] DONE — {} scen, pacing: {}, energy: {}",
                plan.getScenes().size(),
                plan.getPacing(),
                plan.getEnergyLevel());
    }

    // =========================================================================
    // GENERACJA PLANU (AI + FALLBACK)
    // =========================================================================

    /**
     * Próbuje wygenerować plan przez AI.
     * Przy każdym błędzie loguje i zwraca fallback plan — pipeline nigdy się nie zatrzymuje.
     */
    private DirectorPlan generatePlanWithFallback(GenerationContext context) {
        try {
            log.info("[DirectorStep] Generuję AI plan dla {} scen", context.getScenes().size());
            DirectorPlan aiPlan = directorAiService.generatePlan(context);

            log.info("[DirectorStep] AI plan OK — {} scen", aiPlan.getScenes().size());
            return aiPlan;

        } catch (Exception e) {
            log.warn("[DirectorStep] AI plan failed → fallback. Przyczyna: {}", e.getMessage());
            return buildFallbackPlan(context);
        }
    }

    /**
     * Generuje prosty, deterministyczny plan cięć oparty na StyleConfig.
     * Używany gdy AI director failuje lub timeout.
     *
     * Fallback nie jest "złym" planem — to plan spójny z wybranym stylem,
     * tylko bez AI-driven dramaturgii.
     */
    private DirectorPlan buildFallbackPlan(GenerationContext context) {
        log.info("[DirectorStep] Buduję fallback plan — styl: {}", context.getStyle());

        List<SceneDirection> directions = new ArrayList<>();

        for (SceneAsset scene : context.getScenes()) {
            List<Cut> cuts = generateCuts(scene.getDurationMs(), context);

            directions.add(SceneDirection.builder()
                    .sceneIndex(scene.getIndex())
                    .cuts(cuts)
                    .transitionToNext("cut")
                    .build());
        }

        DirectorPlan fallback = DirectorPlan.builder()
                .style(context.getStyle())
                .pacing(context.getStyleConfig().getPacing())
                .energyLevel(context.getStyleConfig().getEnergyLevel())
                .scenes(directions)
                .build();

        log.info("[DirectorStep] Fallback plan gotowy — {} scen",
                directions.size());

        return fallback;
    }

    // =========================================================================
    // BEAT SYNC
    // =========================================================================

    private void applyBeatSync(DirectorPlan plan, GenerationContext context, List<Integer> beats) {
        for (SceneDirection scene : plan.getScenes()) {
            SceneAsset asset = context.getScenes().stream()
                    .filter(s -> s.getIndex() == scene.getSceneIndex())
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException(
                            "[DirectorStep] Scena " + scene.getSceneIndex() + " nie znaleziona przy beat sync"));

            List<Cut> cuts = mapBeatsToScene(beats, asset.getDurationMs());

            if (!cuts.isEmpty()) {
                scene.setCuts(cuts);
            } else {
                log.warn("[DirectorStep] Beat sync dla sceny {} wygenerował 0 cuts — zostawiam oryginalne",
                        scene.getSceneIndex());
            }
        }
    }

    private List<Cut> mapBeatsToScene(List<Integer> beats, int durationMs) {
        List<Cut> cuts = new ArrayList<>();
        int current = 0;

        for (int beat : beats) {
            if (beat >= durationMs) break;
            if (beat <= current) continue; // ignoruj beaty wsteczne

            cuts.add(Cut.builder()
                    .startMs(current)
                    .endMs(beat)
                    .energy(resolveEnergy(beat))
                    .build());

            current = beat;
        }

        // Domknij ostatni cut do końca sceny
        if (current < durationMs) {
            cuts.add(Cut.builder()
                    .startMs(current)
                    .endMs(durationMs)
                    .energy("low")
                    .build());
        }

        return cuts;
    }

    private String resolveEnergy(int beatMs) {
        if (beatMs < 1000) return "high";
        if (beatMs < 3000) return "medium";
        return "low";
    }

    // =========================================================================
    // FALLBACK CUTS GENERATION
    // =========================================================================

    /**
     * Generuje równomiernie rozłożone cuts na podstawie pacing ze StyleConfig.
     * FAST → 500ms cuts, MEDIUM → 1000ms, SLOW → cała scena jako jeden cut.
     */
    private List<Cut> generateCuts(int durationMs, GenerationContext context) {
        List<Cut> cuts = new ArrayList<>();

        int stepMs = switch (context.getStyleConfig().getPacing()) {
            case "FAST"   -> 500;
            case "MEDIUM" -> 1000;
            case "SLOW"   -> durationMs; // jedna scena = jeden cut
            default       -> 1000;
        };

        CutType cutType = resolveCutType(context);
        int current = 0;

        while (current < durationMs) {
            int end = Math.min(current + stepMs, durationMs);

            cuts.add(Cut.builder()
                    .startMs(current)
                    .endMs(end)
                    .type(cutType)
                    .build());

            current = end;
        }

        return cuts;
    }

    private CutType resolveCutType(GenerationContext context) {
        return switch (context.getStyleConfig().getEnergyLevel()) {
            case "HIGH" -> CutType.FAST;
            case "LOW"  -> CutType.SLOW;
            default     -> CutType.NORMAL;
        };
    }
}