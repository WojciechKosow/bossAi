package com.BossAi.bossAi.service.edl;

import com.BossAi.bossAi.dto.edl.EdlDto;
import com.BossAi.bossAi.dto.edl.EdlMetadata;
import com.BossAi.bossAi.entity.*;
import com.BossAi.bossAi.service.*;
import com.BossAi.bossAi.service.audio.AudioAnalysisClient;
import com.BossAi.bossAi.service.audio.AudioAnalysisResponse;
import com.BossAi.bossAi.service.director.*;
import com.BossAi.bossAi.service.generation.GenerationContext;
import com.BossAi.bossAi.service.generation.context.SceneAsset;
import com.BossAi.bossAi.service.render.RemotionRenderClient;
import com.BossAi.bossAi.service.render.RemotionRenderRequest;
import com.BossAi.bossAi.service.render.RemotionRenderResponse;
import com.BossAi.bossAi.service.render.RemotionRenderStatusResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Orkiestrator nowego pipeline produkcji wideo (Timeline-First).
 *
 * Przepływ:
 *   1. Istniejacy pipeline generuje assety (script, images, video, voice, music)
 *   2. Ten orkiestrator przejmuje po fazie assetow:
 *      a) Analizuje muzyke (Python/FastAPI)
 *      b) Generuje EDL (GPT-4o + audio analysis)
 *      c) Waliduje i zapisuje EDL
 *      d) Zleca renderowanie (Node.js/Remotion)
 *      e) Polluje status i aktualizuje RenderJob
 *
 * Integruje nowe mikroserwisy z istniejacym pipeline bez modyfikacji starego kodu.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class VideoProductionOrchestrator {

    private final AudioAnalysisClient audioAnalysisClient;
    private final RemotionRenderClient remotionRenderClient;
    private final EdlGeneratorService edlGeneratorService;
    private final EdlValidator edlValidator;
    private final EdlService edlService;
    private final VideoProjectService videoProjectService;
    private final ProjectAssetService projectAssetService;
    private final RenderJobService renderJobService;
    private final EditDnaGenerator editDnaGenerator;
    private final NarrationAnalyzer narrationAnalyzer;
    private final SpeechAnalyzer speechAnalyzer;
    private final CutEngine cutEngine;
    private final ObjectMapper objectMapper;

    /**
     * Uruchamia pelny przepływ produkcji wideo dla istniejacego projektu.
     * Wywoływany po zakonczeniu fazy generowania assetow przez stary pipeline.
     *
     * @param projectId ID projektu VideoProject
     * @param context   GenerationContext z zakonczonym pipeline assetow
     */
    @Async
    public void produceVideo(UUID projectId, GenerationContext context) {
        log.info("[Orchestrator] Starting video production for project {}", projectId);

        try {
            videoProjectService.updateStatus(projectId, ProjectStatus.GENERATING);

            // 1. Analiza muzyki (jesli dostepna)
            AudioAnalysisResponse audioAnalysis = analyzeMusic(context);

            // 2. Pobierz assety projektu z bazy
            List<ProjectAsset> projectAssets = projectAssetService.getProjectAssetEntities(projectId);

            // 3. WARSTWA A: Analiza narracji (GPT — semantyczne segmenty + editing intent)
            NarrationAnalysis narrationAnalysis = analyzeNarration(context, audioAnalysis);
            context.setNarrationAnalysis(narrationAnalysis);

            // 4. WARSTWA B: Analiza timingów mowy (WhisperX — pauzy, zdania, tempo)
            SpeechTimingAnalysis speechAnalysis = analyzeSpeechTiming(context);
            context.setSpeechTimingAnalysis(speechAnalysis);

            // 5. Generuj EditDna z narration analysis (LLM Director — osobowość montażu)
            EditDna editDna = editDnaGenerator.generate(context, audioAnalysis, narrationAnalysis);

            // 6. WARSTWA C: CutEngine — "mózg montażysty" (uzasadnione cięcia)
            List<JustifiedCut> justifiedCuts = generateJustifiedCuts(
                    context, narrationAnalysis, speechAnalysis, audioAnalysis, editDna, projectAssets);
            context.setJustifiedCuts(justifiedCuts);

            // 7. Generuj EDL z edit_dna + justified cuts
            EdlDto edl = edlGeneratorService.generateEdl(context, audioAnalysis, projectAssets, editDna);

            // 8. Waliduj
            EdlValidator.ValidationResult validation = edlValidator.validate(edl);
            if (!validation.valid()) {
                log.error("[Orchestrator] EDL validation failed: {}", validation.errors());
                videoProjectService.updateStatus(projectId, ProjectStatus.FAILED);
                return;
            }

            // 9. Serializuj i zapisz EDL
            String edlJson = objectMapper.writeValueAsString(edl);
            EditDecisionListEntity edlEntity = edlService.saveNewVersion(projectId, edlJson, EdlSource.AI_GENERATED);

            // 10. Renderuj przez Remotion
            renderViaRemotion(projectId, edlEntity, edl);

        } catch (Exception e) {
            log.error("[Orchestrator] Video production failed for project {}", projectId, e);
            videoProjectService.updateStatus(projectId, ProjectStatus.FAILED);
        }
    }

    /**
     * Renderuje istniejacy EDL (np. po edycji usera).
     * Wywolywany z VideoProjectController POST /{id}/render.
     */
    public void renderCurrentEdl(UUID projectId) {
        log.info("[Orchestrator] Rendering current EDL for project {}", projectId);

        try {
            EditDecisionListEntity edlEntity = edlService.getCurrentEdl(projectId);
            EdlDto edl = objectMapper.readValue(edlEntity.getEdlJson(), EdlDto.class);

            renderViaRemotion(projectId, edlEntity, edl);
        } catch (Exception e) {
            log.error("[Orchestrator] Render failed for project {}", projectId, e);
            videoProjectService.updateStatus(projectId, ProjectStatus.FAILED);
        }
    }

    // ─── Private ──────────────────────────────────────────────────────

    private AudioAnalysisResponse analyzeMusic(GenerationContext context) {
        if (context.getMusicLocalPath() == null) {
            log.info("[Orchestrator] No music — skipping audio analysis");
            return null;
        }

        // Reuse cached response from pipeline (BeatDetection/MusicAnalysis already called Python)
        if (context.getCachedAudioAnalysis() != null) {
            AudioAnalysisResponse cached = context.getCachedAudioAnalysis();
            log.info("[Orchestrator] Using cached audio analysis — BPM={}, mood={}, {} beats",
                    cached.bpm(), cached.mood(),
                    cached.beats() != null ? cached.beats().size() : 0);
            return cached;
        }

        try {
            Path musicPath = Path.of(context.getMusicLocalPath());
            byte[] audioBytes = Files.readAllBytes(musicPath);
            String filename = musicPath.getFileName().toString();

            AudioAnalysisResponse response = audioAnalysisClient.analyzeAudio(audioBytes, filename);
            log.info("[Orchestrator] Audio analysis complete — BPM={}, mood={}, {} beats",
                    response.bpm(), response.mood(),
                    response.beats() != null ? response.beats().size() : 0);
            return response;

        } catch (Exception e) {
            log.warn("[Orchestrator] Audio analysis failed — continuing without it", e);
            return null;
        }
    }

    /**
     * WARSTWA A — Analiza narracji przez GPT.
     * Rozbija scenariusz na semantyczne segmenty z topic/energy/importance.
     * Generuje EditingIntent (intencja montażowa).
     */
    private NarrationAnalysis analyzeNarration(GenerationContext context, AudioAnalysisResponse audioAnalysis) {
        try {
            NarrationAnalysis analysis = narrationAnalyzer.analyze(context, audioAnalysis);
            log.info("[Orchestrator] Narration analysis complete — {} segments, intent: {}",
                    analysis.getSegments() != null ? analysis.getSegments().size() : 0,
                    analysis.getEditingIntent() != null ? analysis.getEditingIntent().getIntent() : "none");
            return analysis;
        } catch (Exception e) {
            log.warn("[Orchestrator] Narration analysis failed — continuing without it: {}", e.getMessage());
            return null;
        }
    }

    /**
     * WARSTWA B — Analiza timingów mowy z WhisperX.
     * Wykrywa pauzy, granice zdań, tempo mowy.
     */
    private SpeechTimingAnalysis analyzeSpeechTiming(GenerationContext context) {
        if (context.getWordTimings() == null || context.getWordTimings().isEmpty()) {
            log.info("[Orchestrator] No word timings — skipping speech analysis");
            return null;
        }

        try {
            SpeechTimingAnalysis analysis = speechAnalyzer.analyze(context.getWordTimings());
            log.info("[Orchestrator] Speech analysis complete — {} pauses, avg tempo: {} wps",
                    analysis.getPauses() != null ? analysis.getPauses().size() : 0,
                    String.format("%.1f", analysis.getAverageTempo()));
            return analysis;
        } catch (Exception e) {
            log.warn("[Orchestrator] Speech analysis failed — continuing without it: {}", e.getMessage());
            return null;
        }
    }

    /**
     * WARSTWA C — CutEngine generuje uzasadnione cięcia.
     * Łączy wszystkie warstwy (narracja + mowa + muzyka + intent)
     * w listę cięć, z których każde ma powód.
     */
    private List<JustifiedCut> generateJustifiedCuts(
            GenerationContext context,
            NarrationAnalysis narrationAnalysis,
            SpeechTimingAnalysis speechAnalysis,
            AudioAnalysisResponse audioAnalysis,
            EditDna editDna,
            List<ProjectAsset> projectAssets) {

        if (narrationAnalysis == null) {
            log.info("[Orchestrator] No narration analysis — skipping CutEngine");
            return List.of();
        }

        try {
            // totalDurationMs = VOICE-OVER duration (master clock)
            // Jeśli voice ma word timings, użyj ich jako prawdziwego czasu trwania.
            // Scene durations (z GPT) to SZACUNEK — voice-over to PRAWDA.
            int sceneDurationMs = context.getScenes().stream()
                    .mapToInt(SceneAsset::getDurationMs)
                    .sum();

            int voiceDurationMs = 0;
            if (context.getWordTimings() != null && !context.getWordTimings().isEmpty()) {
                voiceDurationMs = context.getWordTimings()
                        .get(context.getWordTimings().size() - 1).endMs();
            }

            // Voice-over jest master clockiem — nigdy nie może być czarnego ekranu
            int totalDurationMs;
            if (voiceDurationMs > 0) {
                totalDurationMs = voiceDurationMs;
                if (Math.abs(voiceDurationMs - sceneDurationMs) > 2000) {
                    log.warn("[Orchestrator] Duration mismatch — voice: {}ms, scenes: {}ms. Using voice as master.",
                            voiceDurationMs, sceneDurationMs);
                }
            } else {
                totalDurationMs = sceneDurationMs;
                log.info("[Orchestrator] No word timings — using scene duration: {}ms", sceneDurationMs);
            }

            int minCutMs = editDna != null && editDna.getCutRhythm() != null
                    ? editDna.getCutRhythm().getMinCutMs() : 400;
            int maxCutMs = editDna != null && editDna.getCutRhythm() != null
                    ? editDna.getCutRhythm().getMaxCutMs() : 5000;

            // Policz unikalne assety wizualne (VIDEO + IMAGE) — CutEngine potrzebuje tego
            // żeby nie generować więcej cięć niż assets * 2
            int availableAssetCount = 0;
            for (ProjectAsset asset : projectAssets) {
                String typeName = asset.getType().name();
                if ("VIDEO".equals(typeName) || "IMAGE".equals(typeName)) {
                    availableAssetCount++;
                }
            }

            int sceneCount = context.getScenes().size();

            log.info("[Orchestrator] Available visual assets: {}, scenes: {}",
                    availableAssetCount, sceneCount);

            List<JustifiedCut> cuts = cutEngine.generateCuts(
                    narrationAnalysis, speechAnalysis, audioAnalysis,
                    context.getWordTimings(), totalDurationMs, minCutMs, maxCutMs,
                    availableAssetCount, sceneCount);

            log.info("[Orchestrator] CutEngine complete — {} justified cuts for {}ms video",
                    cuts.size(), totalDurationMs);

            // Log cut distribution for debugging
            long hardCuts = cuts.stream().filter(c -> c.getClassification() == JustifiedCut.CutClassification.HARD).count();
            long softCuts = cuts.stream().filter(c -> c.getClassification() == JustifiedCut.CutClassification.SOFT).count();
            long microCuts = cuts.stream().filter(c -> c.getClassification() == JustifiedCut.CutClassification.MICRO).count();
            log.info("[Orchestrator] Cut distribution — HARD: {}, SOFT: {}, MICRO: {}", hardCuts, softCuts, microCuts);

            return cuts;

        } catch (Exception e) {
            log.warn("[Orchestrator] CutEngine failed — continuing without justified cuts: {}", e.getMessage());
            return List.of();
        }
    }

    @SuppressWarnings("unchecked")
    private void renderViaRemotion(UUID projectId, EditDecisionListEntity edlEntity, EdlDto edl) {
        // Utworz RenderJob
        RenderJob renderJob = renderJobService.createRenderJob(projectId, edlEntity, "high");
        String renderId = renderJob.getId().toString();

        try {
            // Serializuj EDL do Map (Remotion oczekuje raw JSON object)
            if (edl.getMetadata() != null) {
                EdlMetadata meta = edl.getMetadata();
                if (meta.getWidth() <= 0) meta.setWidth(1080);
                if (meta.getHeight() <= 0) meta.setHeight(1920);
                if (meta.getFps() <= 0) meta.setFps(30);
            }

            Map<String, Object> edlMap = objectMapper.convertValue(edl, Map.class);
            RemotionRenderRequest request = RemotionRenderRequest.builder()
                    .renderId(renderId)
                    .edl(edlMap)
                    .outputConfig(RemotionRenderRequest.OutputConfig.tiktokDefault())
                    .build();

            // Trigger render
            RemotionRenderResponse renderResponse = remotionRenderClient.triggerRender(request);
            log.info("[Orchestrator] Render triggered — renderId: {}, status: {}",
                    renderResponse.renderId(), renderResponse.status());

            renderJobService.updateProgress(renderJob.getId(), 0.01);

            // Poll until complete
            RemotionRenderStatusResponse status = remotionRenderClient.pollUntilComplete(renderId);

            // Mark complete
            renderJobService.markComplete(renderJob.getId(), status.outputUrl());
            log.info("[Orchestrator] Render complete for project {} — output: {}", projectId, status.outputUrl());

        } catch (RemotionRenderClient.RenderFailedException e) {
            log.error("[Orchestrator] Render failed for project {}", projectId, e);
            renderJobService.markFailed(renderJob.getId());

        } catch (RemotionRenderClient.RenderTimeoutException e) {
            log.error("[Orchestrator] Render timed out for project {}", projectId, e);
            renderJobService.markFailed(renderJob.getId());

        } catch (Exception e) {
            log.error("[Orchestrator] Unexpected error during render for project {}", projectId, e);
            renderJobService.markFailed(renderJob.getId());
        }
    }
}
