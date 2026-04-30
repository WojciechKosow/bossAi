package com.BossAi.bossAi.service.edl;

import com.BossAi.bossAi.dto.edl.EdlAudioTrack;
import com.BossAi.bossAi.dto.edl.EdlDto;
import com.BossAi.bossAi.dto.edl.EdlMetadata;
import com.BossAi.bossAi.dto.edl.EdlSegment;
import com.BossAi.bossAi.dto.edl.EdlTextOverlay;
import com.BossAi.bossAi.entity.*;
import com.BossAi.bossAi.service.EdlService;
import com.BossAi.bossAi.service.ProjectAssetService;
import com.BossAi.bossAi.service.RenderJobService;
import com.BossAi.bossAi.service.VideoProjectService;
import com.BossAi.bossAi.service.generation.GenerationContext;
import com.BossAi.bossAi.service.generation.context.SceneAsset;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Most miedzy starym pipeline (GenerationContext) a nowym
 * (VideoProject + ProjectAsset + EDL).
 *
 * Po zakonczeniu fazy generowania assetow przez stary pipeline:
 *   1. {@link #bridgeToVideoProject} — tworzy VideoProject i mapuje assety.
 *   2. {@link #bootstrapEdlAndRender} — syntetyzuje basic EDL (segment-per-scene)
 *      i tworzy RenderJob w stanie COMPLETE wskazujacy na finalne MP4 ze starego
 *      pipeline.
 *
 * Te dwie operacje sa rozdzielone na osobne transakcje, zeby blad
 * podczas zapisu EDL/RenderJob nie cofal stworzenia projektu.
 *
 * Dzieki temu kazda generacja konczy sie projektem widocznym i edytowalnym
 * na timeline, niezaleznie od tego czy nowy pipeline (Remotion) jest aktywny.
 * Gdy useNewPipeline=true, orkiestrator dalej moze podmienic EDL na bardziej
 * zaawansowany i przerobic render — zapisujac nowa wersje EDL i RenderJob.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AssetBridgeService {

    private final VideoProjectService videoProjectService;
    private final ProjectAssetService projectAssetService;
    private final EdlService edlService;
    private final RenderJobService renderJobService;
    private final ObjectMapper objectMapper;

    /**
     * Tworzy VideoProject i rejestruje wszystkie assety z GenerationContext.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public UUID bridgeToVideoProject(GenerationContext context, Generation generation, String email) {
        log.info("[AssetBridge] Bridging generation {} to VideoProject", context.getGenerationId());

        // 1. Utwórz VideoProject
        VideoProject project = videoProjectService.createProject(
                email,
                context.getPrompt() != null
                        ? context.getPrompt().substring(0, Math.min(context.getPrompt().length(), 100))
                        : "Video " + context.getGenerationId(),
                context.getPrompt(),
                context.getStyle()
        );

        UUID projectId = project.getId();

        // 2. Linkuj z Generation
        videoProjectService.linkGeneration(projectId, generation);

        // 3. Rejestruj assety scen (IMAGE / VIDEO)
        for (SceneAsset scene : context.getScenes()) {
            if (scene.getVideoLocalPath() != null) {
                AssetType sceneType = resolveSceneAssetType(scene);
                ProjectAsset asset = projectAssetService.createAsset(
                        projectId,
                        sceneType,
                        AssetSource.AI_GENERATED,
                        "scene_" + String.format("%02d", scene.getIndex()) + ".mp4",
                        "video/mp4"
                );
                projectAssetService.markReady(
                        asset.getId(),
                        scene.getVideoUrl() != null ? scene.getVideoUrl() : scene.getVideoLocalPath(),
                        null,
                        scene.getDurationMs() / 1000.0,
                        1080, 1920
                );
            }
        }

        // 4. Rejestruj voice
        if (context.getVoiceLocalPath() != null) {
            ProjectAsset voiceAsset = projectAssetService.createAsset(
                    projectId,
                    AssetType.VOICE,
                    context.hasUserVoice() ? AssetSource.USER_UPLOAD : AssetSource.AI_GENERATED,
                    "voice.mp3",
                    "audio/mpeg"
            );
            projectAssetService.markReady(
                    voiceAsset.getId(),
                    context.getVoiceLocalPath(),
                    null, null, null, null
            );
        }

        // 5. Rejestruj muzykę
        if (context.getMusicLocalPath() != null) {
            ProjectAsset musicAsset = projectAssetService.createAsset(
                    projectId,
                    AssetType.MUSIC,
                    context.hasUserMusic() ? AssetSource.USER_UPLOAD : AssetSource.AI_GENERATED,
                    "music.mp3",
                    "audio/mpeg"
            );
            projectAssetService.markReady(
                    musicAsset.getId(),
                    context.getMusicLocalPath(),
                    null, null, null, null
            );
        }

        log.info("[AssetBridge] Bridged generation {} → project {} ({} scenes, voice={}, music={})",
                context.getGenerationId(), projectId,
                context.getScenes().size(),
                context.getVoiceLocalPath() != null,
                context.getMusicLocalPath() != null);

        return projectId;
    }

    /**
     * Syntetyzuje basic EDL (segment-per-scene + audio + napisy) i tworzy
     * RenderJob w stanie COMPLETE wskazujacy na podany URL filmu.
     *
     * Wywolywane po {@link #bridgeToVideoProject}, w osobnej transakcji
     * (REQUIRES_NEW) — porazka tu nie cofnie powstalego projektu.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void bootstrapEdlAndRender(UUID projectId, GenerationContext context, String videoUrl) {
        try {
            List<ProjectAsset> projectAssets = projectAssetService.getProjectAssetEntities(projectId);
            EdlDto edl = synthesizeBasicEdl(context, projectAssets);
            String edlJson = objectMapper.writeValueAsString(edl);

            EditDecisionListEntity savedEdl = edlService.saveNewVersion(
                    projectId, edlJson, EdlSource.AI_GENERATED);

            // Pre-completed render job pointing at the legacy pipeline's mp4 — gives
            // the editor an immediate preview without waiting for Remotion.
            RenderJob job = renderJobService.createRenderJob(projectId, savedEdl, "high");
            renderJobService.markComplete(job.getId(), videoUrl);

            log.info("[AssetBridge] Bootstrapped project {} (segments={}, audioTracks={})",
                    projectId,
                    edl.getSegments() != null ? edl.getSegments().size() : 0,
                    edl.getAudioTracks() != null ? edl.getAudioTracks().size() : 0);
        } catch (Exception e) {
            // Don't propagate — caller has already committed the project itself.
            log.warn("[AssetBridge] EDL/render bootstrap failed for project {}: {}",
                    projectId, e.getMessage(), e);
        }
    }

    /**
     * Buduje proste EDL z dostepnych ProjectAssetow + scen w GenerationContext:
     *   - po jednym EdlSegment na scene (layer 0, sekwencyjnie)
     *   - audio tracks dla voiceover + music (jesli sa)
     *   - text overlays z subtitleText sceny (jesli wypelnione)
     *
     * Bez efektow ani transitions — uzytkownik moze je dodac w edytorze.
     */
    private EdlDto synthesizeBasicEdl(GenerationContext context, List<ProjectAsset> projectAssets) {
        List<ProjectAsset> sceneAssets = projectAssets.stream()
                .filter(a -> a.getType() == AssetType.VIDEO || a.getType() == AssetType.IMAGE)
                .toList();

        List<EdlSegment> segments = new ArrayList<>();
        int cursorMs = 0;
        int sceneCount = Math.min(context.getScenes().size(), sceneAssets.size());

        for (int i = 0; i < sceneCount; i++) {
            SceneAsset scene = context.getScenes().get(i);
            ProjectAsset asset = sceneAssets.get(i);
            int duration = Math.max(500, scene.getDurationMs());

            segments.add(EdlSegment.builder()
                    .id(UUID.randomUUID().toString())
                    .assetId(asset.getId().toString())
                    .assetUrl(asset.getStorageUrl())
                    .assetType(asset.getType().name())
                    .startMs(cursorMs)
                    .endMs(cursorMs + duration)
                    .layer(0)
                    .build());

            cursorMs += duration;
        }

        int totalMs = cursorMs;

        // Audio tracks
        List<EdlAudioTrack> audioTracks = new ArrayList<>();
        Optional<ProjectAsset> voice = projectAssets.stream()
                .filter(a -> a.getType() == AssetType.VOICE)
                .findFirst();
        voice.ifPresent(v -> audioTracks.add(EdlAudioTrack.builder()
                .id(UUID.randomUUID().toString())
                .assetId(v.getId().toString())
                .assetUrl(v.getStorageUrl())
                .type("voiceover")
                .startMs(0)
                .endMs(totalMs)
                .volume(1.0)
                .build()));

        Optional<ProjectAsset> music = projectAssets.stream()
                .filter(a -> a.getType() == AssetType.MUSIC)
                .findFirst();
        music.ifPresent(m -> audioTracks.add(EdlAudioTrack.builder()
                .id(UUID.randomUUID().toString())
                .assetId(m.getId().toString())
                .assetUrl(m.getStorageUrl())
                .type("music")
                .startMs(0)
                .endMs(totalMs)
                .volume(0.45)
                .build()));

        // Subtitles per scene (1:1 with segments)
        List<EdlTextOverlay> textOverlays = new ArrayList<>();
        int subCursor = 0;
        for (int i = 0; i < sceneCount; i++) {
            SceneAsset scene = context.getScenes().get(i);
            int duration = Math.max(500, scene.getDurationMs());
            String text = scene.getSubtitleText();
            if (text != null && !text.isBlank()) {
                textOverlays.add(EdlTextOverlay.builder()
                        .id(UUID.randomUUID().toString())
                        .text(text.trim())
                        .startMs(subCursor)
                        .endMs(subCursor + duration)
                        .build());
            }
            subCursor += duration;
        }

        String title = context.getPrompt() != null
                ? context.getPrompt().substring(0, Math.min(context.getPrompt().length(), 80))
                : "Video";

        return EdlDto.builder()
                .version(EdlDto.CURRENT_VERSION)
                .metadata(EdlMetadata.builder()
                        .title(title)
                        .style(context.getStyle() != null ? context.getStyle().name() : null)
                        .totalDurationMs(totalMs)
                        .width(1080)
                        .height(1920)
                        .fps(30)
                        .build())
                .segments(segments)
                .audioTracks(audioTracks.isEmpty() ? null : audioTracks)
                .textOverlays(textOverlays.isEmpty() ? null : textOverlays)
                .build();
    }

    private AssetType resolveSceneAssetType(SceneAsset scene) {
        String path = scene.getVideoLocalPath();
        if (path != null && path.contains("_image_clip")) {
            return AssetType.IMAGE;
        }
        if (scene.getVideoUrl() != null) {
            return AssetType.VIDEO;
        }
        if (path != null && (path.endsWith(".mp4") || path.endsWith(".mov") || path.endsWith(".webm"))) {
            return AssetType.VIDEO;
        }
        return AssetType.VIDEO;
    }
}
