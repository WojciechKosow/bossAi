package com.BossAi.bossAi.service.edl;

import com.BossAi.bossAi.dto.edl.EdlDto;
import com.BossAi.bossAi.dto.edl.EdlMetadata;
import com.BossAi.bossAi.entity.*;
import com.BossAi.bossAi.service.*;
import com.BossAi.bossAi.service.audio.AudioAnalysisClient;
import com.BossAi.bossAi.service.audio.AudioAnalysisResponse;
import com.BossAi.bossAi.service.director.*;
import com.BossAi.bossAi.service.director.composition.AutonomousCompositionDecider;
import com.BossAi.bossAi.service.director.overlay.OverlayPlacementEngine;
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
    private final com.BossAi.bossAi.service.RenderProgressService renderProgressService;
    private final EditDnaGenerator editDnaGenerator;
    private final NarrationAnalyzer narrationAnalyzer;
    private final SpeechAnalyzer speechAnalyzer;
    private final CutEngine cutEngine;
    private final AssetAnalyzer assetAnalyzer;
    private final UserIntentParser userIntentParser;
    private final LayerAssetGenerator layerAssetGenerator;
    private final AutonomousCompositionDecider autonomousCompositionDecider;
    private final OverlayPlacementEngine overlayPlacementEngine;
    private final ObjectMapper objectMapper;
    private final com.BossAi.bossAi.service.StorageService storageService;
    private final com.BossAi.bossAi.repository.GenerationRepository generationRepository;

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

            // 2.5 NOWE: Analiza assetów + parsowanie intencji usera
            //   Te dwa kroki dają GPT "oczy" i "uszy" — zamiast ślepego montażu,
            //   system wie CO jest na assetach i CZEGO chce user.
            analyzeAssetsAndIntent(context);

            // 2.6 NOWE: Generowanie warstw dla scen z multi-layer composition.
            //   Jeśli UserIntentParser wykrył "w tle X, na środku Y" — generujemy X.
            //   Wynik zapisywany w SceneAsset.layerAssetIds (layerIndex → ProjectAsset UUID),
            //   a wygenerowane assety dopisywane do listy projektowej, by EdlGenerator
            //   mógł je serwować po URL-u.
            generateMultiLayerAssets(projectId, context, projectAssets);

            // 3. WARSTWA A: Analiza narracji (GPT — semantyczne segmenty + editing intent)
            //    TERAZ z kontekstem: UserEditIntent + AssetProfiles
            NarrationAnalysis narrationAnalysis = analyzeNarration(context, audioAnalysis);
            context.setNarrationAnalysis(narrationAnalysis);

            // 4. WARSTWA B: Analiza timingów mowy (WhisperX — pauzy, zdania, tempo)
            SpeechTimingAnalysis speechAnalysis = analyzeSpeechTiming(context);
            context.setSpeechTimingAnalysis(speechAnalysis);

            // 5. Generuj EditDna z narration analysis (LLM Director — osobowość montażu)
            //    TERAZ z kontekstem: UserEditIntent + AssetProfiles (via context)
            EditDna editDna = editDnaGenerator.generate(context, audioAnalysis, narrationAnalysis);

            // 6. WARSTWA C+D: CutEngine — "mózg montażysty" (uzasadnione cięcia)
            //    TERAZ z UserEditIntent jako źródło kandydatów na cięcia
            List<JustifiedCut> justifiedCuts = generateJustifiedCuts(
                    context, narrationAnalysis, speechAnalysis, audioAnalysis, editDna, projectAssets);
            context.setJustifiedCuts(justifiedCuts);

            // 6.5 NOWE: Autonomous composition decisions
            //   Decider analizuje asset profiles + narration + cuts + DNA preset
            //   i autonomicznie decyduje kiedy/jak nakładać warstwy (jak montażysta).
            //   Wynik: SceneAsset.layerAssetIds wypełnione → appendLayerSegments w EdlGenerator
            //   emituje multi-layer segmenty do Remotion.
            autonomousCompositionDecider.decide(context, projectAssets);

            // 6.6 NOWE: Overlay placement — user-provided overlay images
            //   OverlayPlacementEngine opisuje każdy overlay (GPT Vision) i dopasowuje
            //   go do momentu w narracji (semantyczne dopasowanie słów kluczowych).
            //   Wynik: context.overlayPlacements → appendOverlaySegments w EdlGenerator
            //   emituje layer=2 segmenty z x/y/width/height/opacity/animationIn.
            overlayPlacementEngine.describeAndPlace(context);

            // 7. Generuj EDL z edit_dna + justified cuts
            EdlDto edl = edlGeneratorService.generateEdl(context, audioAnalysis, projectAssets, editDna);

            // 8. Waliduj (lenient pipeline mode, asset-aware)
            EdlValidator.ValidationResult validation = edlValidator.validate(edl, projectAssets, false);
            if (!validation.valid()) {
                log.error("[Orchestrator] EDL validation failed: {}", validation.errors());
                videoProjectService.updateStatus(projectId, ProjectStatus.FAILED);
                return;
            }

            // 9. Serializuj i zapisz EDL (individual clips — for timeline editor)
            String edlJson = objectMapper.writeValueAsString(edl);
            EditDecisionListEntity edlEntity = edlService.saveNewVersion(projectId, edlJson, EdlSource.AI_GENERATED);

            // 10. Build render EDL: for custom TTS, swap individual voice clips with
            //     the single concatenated track so whisper_words align 1:1 with audio.
            //     The timeline EDL (saved above) keeps individual clips for editing.
            EdlDto renderEdl = edlGeneratorService.buildRenderEdl(edl, projectAssets);

            // 11. Renderuj przez Remotion
            renderViaRemotion(projectId, context.getGenerationId(), edlEntity, renderEdl);

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

            // Timeline re-render (user edited the EDL) — updates the project's
            // RenderJob; no originating Generation to repoint here.
            renderViaRemotion(projectId, null, edlEntity, edl);
        } catch (Exception e) {
            log.error("[Orchestrator] Render failed for project {}", projectId, e);
            videoProjectService.updateStatus(projectId, ProjectStatus.FAILED);
        }
    }

    // ─── Private ──────────────────────────────────────────────────────

    /**
     * NOWE: Analiza assetów + parsowanie intencji usera.
     *
     * Krok 1: AssetAnalyzer analizuje custom media → AssetProfile[]
     * Krok 2: UserIntentParser parsuje prompt → UserEditIntent
     *
     * Oba wyniki zapisywane na GenerationContext i używane przez:
     *   - ScriptStep (już wykonany — assety i intent są wstrzykiwane przez
     *     orchestrator PRZED production, ale ScriptStep czyta z context)
     *   - NarrationAnalyzer, EditDnaGenerator, CutEngine, EdlGenerator
     */
    private void analyzeAssetsAndIntent(GenerationContext context) {
        // AssetAnalysisStep (pipeline krok 0) już to wykonał przed ScriptStep.
        // Orchestrator re-analizuje tylko jeśli profile są puste (np. brak custom media
        // w pipeline lub błąd w AssetAnalysisStep).
        List<AssetProfile> profiles = context.getAssetProfiles() != null
                ? context.getAssetProfiles() : List.of();

        if (profiles.isEmpty() && context.hasCustomMedia()) {
            try {
                profiles = assetAnalyzer.analyzeAssets(
                        context.getCustomMediaAssets(), context.getPrompt());
                context.setAssetProfiles(profiles);
                log.info("[Orchestrator] Asset analysis (late fallback) — {} profili", profiles.size());
            } catch (Exception e) {
                log.warn("[Orchestrator] Asset analysis failed — continuing without profiles: {}", e.getMessage());
            }
        } else {
            log.info("[Orchestrator] Asset profiles already set ({}) — skipping re-analysis", profiles.size());
        }

        // Intent parsing: skip if already done by AssetAnalysisStep
        if (context.getUserEditIntent() != null) {
            log.info("[Orchestrator] UserEditIntent already parsed — skipping");
            return;
        }

        try {
            UserEditIntent editIntent = userIntentParser.parseIntent(
                    context.getPrompt(),
                    context.hasCustomMedia() ? context.getCustomMediaAssets() : null,
                    profiles);
            context.setUserEditIntent(editIntent);
            log.info("[Orchestrator] User intent parsed — explicit: {}, placements: {}, pacing: {}",
                    editIntent.hasExplicitInstructions(),
                    editIntent.getPlacements() != null ? editIntent.getPlacements().size() : 0,
                    editIntent.getPacingPreference());
        } catch (Exception e) {
            log.warn("[Orchestrator] User intent parsing failed — continuing without intent: {}", e.getMessage());
        }
    }

    /**
     * Generuje assety dla scen z multi-layer composition (layer>0).
     *
     * UserIntentParser może wystawić SceneDirectives, jeśli user opisał warstwy
     * (np. "w tle X, na środku Y"). Tutaj realizujemy te dyrektywy:
     *   1. LayerAssetGenerator generuje obrazy/video przez FalAI dla source=generate
     *   2. Mapujemy wygenerowane ProjectAsset na SceneAsset.layerAssetIds
     *      (klucz = layerIndex, wartość = ProjectAsset UUID)
     *   3. Dodajemy nowe assety do listy projektAssets in-place, żeby
     *      EdlGenerator mógł je rozwiązać po assetId → URL
     *
     * NIE zmienia liczby scen ani głównych assetów — tylko dorzuca warstwy.
     */
    private void generateMultiLayerAssets(UUID projectId,
                                          GenerationContext context,
                                          List<ProjectAsset> projectAssets) {
        UserEditIntent editIntent = context.getUserEditIntent();
        if (editIntent == null || !editIntent.hasSceneDirectives()
                || !editIntent.needsAssetGeneration()) {
            return;
        }

        try {
            List<ProjectAsset> generated = layerAssetGenerator.generateLayerAssets(
                    projectId, editIntent);
            if (generated.isEmpty()) {
                return;
            }

            // Dodaj wygenerowane assety do listy projektu (mutable list z DB).
            projectAssets.addAll(generated);

            // Zmapuj wygenerowane assety na sceneIndex+layerIndex w kolejności
            // generowania (LayerAssetGenerator iteruje SceneDirectives w tej samej
            // kolejności co my — najpierw scena, potem layery z source=generate).
            int genIdx = 0;
            List<SceneAsset> scenes = context.getScenes();
            for (SceneDirective directive : editIntent.getSceneDirectives()) {
                if (directive.getLayers() == null) continue;
                int sceneIndex = directive.getSceneIndex();
                if (sceneIndex < 0 || sceneIndex >= scenes.size()) continue;

                SceneAsset scene = scenes.get(sceneIndex);
                for (SceneDirective.LayerDirective layer : directive.getLayers()) {
                    if (!layer.isGenerate()) continue;
                    if (genIdx >= generated.size()) break;
                    ProjectAsset asset = generated.get(genIdx++);
                    scene.getLayerAssetIds().put(layer.getLayerIndex(), asset.getId());
                }
            }

            log.info("[Orchestrator] Multi-layer asset generation complete — {} new assets across {} scenes",
                    generated.size(),
                    scenes.stream().filter(s -> !s.getLayerAssetIds().isEmpty()).count());

        } catch (Exception e) {
            log.warn("[Orchestrator] Multi-layer asset generation failed — continuing with primary layers only: {}",
                    e.getMessage());
        }
    }

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

            // Pass UserEditIntent to CutEngine (warstwa D)
            UserEditIntent editIntent = context.getUserEditIntent();
            List<JustifiedCut> cuts = cutEngine.generateCuts(
                    narrationAnalysis, speechAnalysis, audioAnalysis,
                    context.getWordTimings(), totalDurationMs, minCutMs, maxCutMs,
                    availableAssetCount, sceneCount, editIntent);

            log.info("[Orchestrator] CutEngine complete — {} justified cuts for {}ms video",
                    cuts.size(), totalDurationMs);

            // Log cut distribution for debugging
            long hardCuts = cuts.stream().filter(c -> c.getClassification() == JustifiedCut.CutClassification.HARD).count();
            long softCuts = cuts.stream().filter(c -> c.getClassification() == JustifiedCut.CutClassification.SOFT).count();
            long microCuts = cuts.stream().filter(c -> c.getClassification() == JustifiedCut.CutClassification.MICRO).count();
            log.info("[Orchestrator] Cut distribution — HARD: {}, SOFT: {}, MICRO: {}", hardCuts, softCuts, microCuts);

            // Log asset assignments for debugging
            long assigned = cuts.stream().filter(c -> c.getAssignedAssetIndex() >= 0).count();
            if (assigned > 0) {
                log.info("[Orchestrator] Asset assignments: {}/{} cuts have explicit asset indices", assigned, cuts.size());
                for (int ci = 0; ci < cuts.size(); ci++) {
                    JustifiedCut c = cuts.get(ci);
                    if (c.getAssignedAssetIndex() >= 0) {
                        log.debug("[Orchestrator]   Cut {} ({}ms-{}ms) → asset index {}",
                                ci, c.getStartMs(), c.getEndMs(), c.getAssignedAssetIndex());
                    }
                }
            }

            return cuts;

        } catch (Exception e) {
            log.warn("[Orchestrator] CutEngine failed — continuing without justified cuts: {}", e.getMessage());
            return List.of();
        }
    }

    @SuppressWarnings("unchecked")
    private void renderViaRemotion(UUID projectId, UUID generationId, EditDecisionListEntity edlEntity, EdlDto edl) {
        // Utworz RenderJob
        RenderJob renderJob = renderJobService.createRenderJob(projectId, edlEntity, "high");
        String renderId = renderJob.getId().toString();
        renderProgressService.broadcast(projectId, RenderStatus.QUEUED, 0.0, null);

        try {
            // Serializuj EDL do Map (Remotion oczekuje raw JSON object)
            if (edl.getMetadata() != null) {
                EdlMetadata meta = edl.getMetadata();
                if (meta.getWidth() <= 0) meta.setWidth(1080);
                if (meta.getHeight() <= 0) meta.setHeight(1920);
                if (meta.getFps() <= 0) meta.setFps(30);
            }

            // Point Remotion at direct presigned R2 URLs so it pulls assets
            // straight from Cloudflare's CDN instead of round-tripping through
            // Spring's /internal callback (the public Railway loopback stalls
            // intermittently — "server sent no data for 20 seconds"). No-op on
            // local storage. Only the ephemeral render EDL is mutated; the saved
            // timeline EDL keeps its stable /internal URLs.
            rewriteAssetUrlsToDirectR2(edl);

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
            renderProgressService.broadcast(projectId, RenderStatus.RENDERING, 0.01, null);

            // Poll until complete — stream progress to the editor's SSE channel
            RemotionRenderStatusResponse status = remotionRenderClient.pollUntilComplete(
                    renderId,
                    progress -> {
                        renderJobService.updateProgress(renderJob.getId(), progress);
                        renderProgressService.broadcast(projectId, RenderStatus.RENDERING, progress, null);
                    });

            // Ingest Remotion's rendered MP4 into our own storage (R2 / local)
            // so the final video lives with every other asset instead of on the
            // render box's ephemeral disk. The bytes are Remotion's output,
            // unchanged — we only relocate them. Served back via the UUID-keyed
            // /api/renders/{renderId}/file route (presigned-redirect on R2).
            String servedUrl = status.outputUrl();
            boolean ingested = false;
            try {
                byte[] videoBytes = remotionRenderClient.downloadOutput(status.outputUrl());
                String storageKey = "renders/" + renderId + ".mp4";
                storageService.save(videoBytes, storageKey);
                servedUrl = "/api/renders/" + renderId + "/file";
                ingested = true;
                log.info("[Orchestrator] Rendered video stored — key: {}, {} bytes",
                        storageKey, videoBytes.length);
            } catch (Exception ingestEx) {
                // Non-fatal: fall back to Remotion's own output URL so the render
                // isn't lost if ingestion hiccups. The video still exists on Remotion.
                log.warn("[Orchestrator] Could not ingest rendered video into storage " +
                        "(falling back to Remotion URL) — {}", ingestEx.getMessage(), ingestEx);
            }

            // Mark complete
            renderJobService.markComplete(renderJob.getId(), servedUrl);
            renderProgressService.broadcast(projectId, RenderStatus.COMPLETE, 1.0, servedUrl);
            log.info("[Orchestrator] Render complete for project {} — output: {}", projectId, servedUrl);

            // Promote the Remotion render to the Generation's final video. The
            // legacy ffmpeg pass already set Generation.videoUrl to its baseline
            // mp4; once Remotion produces the real cut and we've stored it, that
            // becomes the video the user gets. Only when ingestion succeeded —
            // never overwrite the working ffmpeg URL with an unreachable one.
            if (ingested && generationId != null) {
                updateGenerationVideoUrl(generationId, servedUrl);
            }

        } catch (RemotionRenderClient.RenderFailedException e) {
            log.error("[Orchestrator] Render failed for project {}", projectId, e);
            renderJobService.markFailed(renderJob.getId());
            renderProgressService.broadcast(projectId, RenderStatus.FAILED, 0.0, null);

        } catch (RemotionRenderClient.RenderTimeoutException e) {
            log.error("[Orchestrator] Render timed out for project {}", projectId, e);
            renderJobService.markFailed(renderJob.getId());
            renderProgressService.broadcast(projectId, RenderStatus.FAILED, 0.0, null);

        } catch (Exception e) {
            log.error("[Orchestrator] Unexpected error during render for project {}", projectId, e);
            renderJobService.markFailed(renderJob.getId());
            renderProgressService.broadcast(projectId, RenderStatus.FAILED, 0.0, null);
        }
    }

    /**
     * Rewrites the render EDL's asset URLs to direct presigned R2 URLs so
     * Remotion fetches assets straight from Cloudflare instead of through
     * Spring's /internal callback. No-op when storage can't presign (local
     * backend → keeps the callback URLs, which work locally).
     */
    private void rewriteAssetUrlsToDirectR2(EdlDto edl) {
        if (edl == null) return;
        if (edl.getSegments() != null) {
            for (var seg : edl.getSegments()) {
                String direct = presignedForAsset(seg.getAssetId());
                if (direct != null) seg.setAssetUrl(direct);
            }
        }
        if (edl.getAudioTracks() != null) {
            for (var track : edl.getAudioTracks()) {
                String direct = presignedForAsset(track.getAssetId());
                if (direct != null) track.setAssetUrl(direct);
            }
        }
    }

    /** Presigned R2 URL for a ProjectAsset id, or null (unknown asset / local backend). */
    private String presignedForAsset(String assetId) {
        if (assetId == null || assetId.isBlank()) return null;
        try {
            ProjectAsset asset = projectAssetService.getAsset(UUID.fromString(assetId));
            String key = asset.getStorageUrl();
            if (key == null || key.isBlank()) return null;
            return storageService.presignedUrl(key, java.time.Duration.ofHours(2));
        } catch (Exception e) {
            log.debug("[Orchestrator] Direct-R2 presign skipped for asset {} — {}", assetId, e.getMessage());
            return null;
        }
    }

    /**
     * Points the Generation's final video at the Remotion render (stored in R2).
     * Best-effort on the async render thread — save() commits on its own; a
     * failure here must not fail the render, which already completed.
     */
    private void updateGenerationVideoUrl(UUID generationId, String videoUrl) {
        try {
            generationRepository.findById(generationId).ifPresent(gen -> {
                gen.setVideoUrl(videoUrl);
                generationRepository.save(gen);
                log.info("[Orchestrator] Generation {} final video → {}", generationId, videoUrl);
            });
        } catch (Exception e) {
            log.warn("[Orchestrator] Could not update Generation {} videoUrl — {}",
                    generationId, e.getMessage());
        }
    }
}
