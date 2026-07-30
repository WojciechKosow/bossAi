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
 *   1. context.setDirectorPlan(plan) was called only in the catch block (fallback).
 *      Przy sukcesie AI planu — plan był generowany ale NIE zapisywany do kontekstu.
 *      RenderStep got null and crashed. Fixed: setDirectorPlan is always called
 *      at the end of execute(), regardless of the path (AI or fallback).
 *
 *   2. Beat sync was applied after the fallback plan instead of after the AI plan.
 *      Fixed: beat sync is applied to the final plan (regardless of source).
 *
 *   3. effectAssigner.applyEffects was called twice on AI success.
 *      Raz w try, raz po beat sync. Naprawione: tylko raz, po beat sync.
 *
 *   4. callback.onStep in the DIRECTOR step used GenerationStepName.SCRIPT instead
 *      dedykowanego kroku. Zostawiono SCRIPT bo DIRECTOR nie ma osobnego enum value —
 *      do poprawy w Fazie 2 gdy dodamy DIRECTOR do GenerationStepName.
 *
 * Flow:
 *   1. Attempt to generate the plan via AI (DirectorAiService)
 *   2. Jeśli AI failuje → fallback plan oparty na StyleConfig
 *   3. Beat sync (if music is available)
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

        // Step 1: Try the AI plan, fallback on error
        DirectorPlan plan = generatePlanWithFallback(context);

        // Step 2: Beat sync (if music is available)
        // Note: in the normal pipeline, music is only available after MusicStep,
        // which runs AFTER DirectorStep. Beat sync here only works when
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

        // Step 3: Effects + transitions between scenes (music-aware)
        String contentType = context.getScript() != null ? context.getScript().contentType() : null;
        var audioAnalysis = context.getCachedAudioAnalysis();
        effectAssigner.applyEffects(plan, context.getStyle(), contentType, audioAnalysis);
        effectAssigner.applyTransitions(plan, context.getStyle(), contentType, audioAnalysis);

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
     * Attempts to generate the plan via AI.
     * Przy każdym błędzie loguje i zwraca fallback plan — pipeline nigdy się nie zatrzymuje.
     */
    private DirectorPlan generatePlanWithFallback(GenerationContext context) {
        try {
            log.info("[DirectorStep] Generating AI plan for {} scenes", context.getScenes().size());
            DirectorPlan aiPlan = directorAiService.generatePlan(context);

            log.info("[DirectorStep] AI plan OK — {} scen", aiPlan.getScenes().size());
            return aiPlan;

        } catch (Exception e) {
            log.warn("[DirectorStep] AI plan failed → fallback. Przyczyna: {}", e.getMessage());
            return buildFallbackPlan(context);
        }
    }

    /**
     * Generates a simple, deterministic cut plan based on StyleConfig.
     * Used when the AI director fails or times out.
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

            // Oblicz offset czasowy sceny na timeline
            int sceneOffsetMs = 0;
            for (SceneAsset s : context.getScenes()) {
                if (s.getIndex() < scene.getSceneIndex()) {
                    sceneOffsetMs += s.getDurationMs();
                }
            }

            List<Cut> cuts = mapBeatsToScene(beats, asset.getDurationMs(), sceneOffsetMs);

            if (!cuts.isEmpty()) {
                log.info("[DirectorStep] Beat sync scena {} — {} cutów (offset={}ms, duration={}ms)",
                        scene.getSceneIndex(), cuts.size(), sceneOffsetMs, asset.getDurationMs());
                scene.setCuts(cuts);
            } else {
                log.warn("[DirectorStep] Beat sync dla sceny {} wygenerował 0 cuts — zostawiam oryginalne",
                        scene.getSceneIndex());
            }
        }
    }

    /**
     * Maps beats to cuts within a scene.
     * Beaty to pozycje absolutne na timeline muzyki.
     * sceneOffsetMs = the scene's start on the video timeline.
     * Cuts have startMs/endMs RELATIVE to the scene (0 = scene start).
     */
    private List<Cut> mapBeatsToScene(List<Integer> beats, int durationMs, int sceneOffsetMs) {
        List<Cut> cuts = new ArrayList<>();
        int sceneEndMs = sceneOffsetMs + durationMs;
        int current = 0; // relatywny do sceny

        for (int beat : beats) {
            // Skip beats from before this scene
            if (beat < sceneOffsetMs) continue;
            // Stop when a beat is past the scene
            if (beat >= sceneEndMs) break;

            int relBeat = beat - sceneOffsetMs;
            if (relBeat <= current) continue;

            cuts.add(Cut.builder()
                    .startMs(current)
                    .endMs(relBeat)
                    .energy(resolveEnergy(relBeat - current))
                    .build());

            current = relBeat;
        }

        // Close the last cut to the end of the scene
        if (current < durationMs) {
            cuts.add(Cut.builder()
                    .startMs(current)
                    .endMs(durationMs)
                    .energy("low")
                    .build());
        }

        return cuts;
    }

    private String resolveEnergy(int cutDurationMs) {
        if (cutDurationMs < 500) return "high";
        if (cutDurationMs < 1500) return "medium";
        return "low";
    }

    // =========================================================================
    // FALLBACK CUTS GENERATION
    // =========================================================================

    /**
     * Generates evenly distributed cuts based on the pacing from StyleConfig.
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