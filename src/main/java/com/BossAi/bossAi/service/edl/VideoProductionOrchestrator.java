package com.BossAi.bossAi.service.edl;

import com.BossAi.bossAi.dto.edl.EdlDto;
import com.BossAi.bossAi.dto.edl.EdlMetadata;
import com.BossAi.bossAi.entity.*;
import com.BossAi.bossAi.service.*;
import com.BossAi.bossAi.service.audio.AudioAnalysisClient;
import com.BossAi.bossAi.service.audio.AudioAnalysisResponse;
import com.BossAi.bossAi.service.generation.GenerationContext;
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

            // 3. Generuj EDL
            EdlDto edl = edlGeneratorService.generateEdl(context, audioAnalysis, projectAssets);

            // 4. Waliduj
            EdlValidator.ValidationResult validation = edlValidator.validate(edl);
            if (!validation.valid()) {
                log.error("[Orchestrator] EDL validation failed: {}", validation.errors());
                videoProjectService.updateStatus(projectId, ProjectStatus.FAILED);
                return;
            }

            // 5. Serializuj i zapisz EDL
            String edlJson = objectMapper.writeValueAsString(edl);
            EditDecisionListEntity edlEntity = edlService.saveNewVersion(projectId, edlJson, EdlSource.AI_GENERATED);

            // 6. Renderuj przez Remotion
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
