package com.BossAi.bossAi.service.edl;

import com.BossAi.bossAi.entity.*;
import com.BossAi.bossAi.service.ProjectAssetService;
import com.BossAi.bossAi.service.VideoProjectService;
import com.BossAi.bossAi.service.generation.GenerationContext;
import com.BossAi.bossAi.service.generation.context.SceneAsset;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Most miedzy starym pipeline (GenerationContext) a nowym (VideoProject + ProjectAsset).
 *
 * Po zakonczeniu fazy generowania assetow przez stary pipeline,
 * tworzy VideoProject i mapuje wszystkie assety z GenerationContext
 * na encje ProjectAsset w bazie.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AssetBridgeService {

    private final VideoProjectService videoProjectService;
    private final ProjectAssetService projectAssetService;

    /**
     * Tworzy VideoProject i rejestruje wszystkie assety z GenerationContext.
     *
     * @param context    GenerationContext po zakonczeniu faz SCRIPT → MUSIC
     * @param generation encja Generation z bazy
     * @param email      email usera (do ownership)
     * @return UUID utworzonego VideoProject
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public UUID bridgeToVideoProject(GenerationContext context, Generation generation, String email) {
        log.info("[AssetBridge] Bridging generation {} to VideoProject", context.getGenerationId());

        // 1. Utwórz VideoProject
        VideoProject project = videoProjectService.createProject(
                email,
                context.getPrompt() != null ? context.getPrompt().substring(0, Math.min(context.getPrompt().length(), 100)) : "Video " + context.getGenerationId(),
                context.getPrompt(),
                context.getStyle()
        );

        UUID projectId = project.getId();

        // 2. Linkuj z Generation
        videoProjectService.linkGeneration(projectId, generation);

        // 3. Rejestruj assety scen (IMAGE / VIDEO)
        for (SceneAsset scene : context.getScenes()) {
            if (scene.getVideoLocalPath() != null) {
                ProjectAsset asset = projectAssetService.createAsset(
                        projectId,
                        scene.getVideoUrl() != null && isVideoScene(scene) ? AssetType.VIDEO : AssetType.IMAGE,
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

    private boolean isVideoScene(SceneAsset scene) {
        // Scena VIDEO (fal.ai animation) vs IMAGE (Ken Burns FFmpeg)
        return scene.getVideoUrl() != null && !scene.getVideoLocalPath().contains("_image_clip");
    }
}
