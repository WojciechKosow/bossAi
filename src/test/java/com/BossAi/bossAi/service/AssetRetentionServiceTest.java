package com.BossAi.bossAi.service;

import com.BossAi.bossAi.config.BetaConfig;
import com.BossAi.bossAi.entity.Asset;
import com.BossAi.bossAi.entity.AssetSource;
import com.BossAi.bossAi.entity.AssetType;
import com.BossAi.bossAi.entity.Generation;
import com.BossAi.bossAi.entity.PlanDefinition;
import com.BossAi.bossAi.entity.PlanType;
import com.BossAi.bossAi.entity.ProjectAsset;
import com.BossAi.bossAi.entity.UserPlan;
import com.BossAi.bossAi.repository.AssetRepository;
import com.BossAi.bossAi.repository.GenerationRepository;
import com.BossAi.bossAi.repository.PlanDefinitionRepository;
import com.BossAi.bossAi.repository.ProjectAssetRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class AssetRetentionServiceTest {

    private AssetRepository assetRepository;
    private ProjectAssetRepository projectAssetRepository;
    private GenerationRepository generationRepository;
    private PlanDefinitionRepository planDefinitionRepository;
    private StorageService storageService;
    private BetaConfig betaConfig;

    private AssetRetentionService service;

    private UUID generationId;
    private UUID projectId;
    private UUID finalVideoId;

    @BeforeEach
    void setUp() {
        assetRepository = mock(AssetRepository.class);
        projectAssetRepository = mock(ProjectAssetRepository.class);
        generationRepository = mock(GenerationRepository.class);
        planDefinitionRepository = mock(PlanDefinitionRepository.class);
        storageService = mock(StorageService.class);
        betaConfig = mock(BetaConfig.class);
        when(betaConfig.isBetaMode()).thenReturn(false);

        service = new AssetRetentionService(
                assetRepository, projectAssetRepository, generationRepository,
                planDefinitionRepository, storageService, betaConfig);

        generationId = UUID.randomUUID();
        projectId = UUID.randomUUID();
        finalVideoId = UUID.randomUUID();
    }

    // ---------------------------------------------------------------------

    private Generation generation(PlanType planType) {
        UserPlan plan = new UserPlan();
        plan.setPlanType(planType);
        return Generation.builder()
                .id(generationId)
                .userPlan(plan)
                .videoUrl("/api/assets/file/" + finalVideoId)
                .build();
    }

    private PlanDefinition planDef(PlanType type, boolean storage, int retentionHours) {
        return PlanDefinition.builder()
                .id(type)
                .storage(storage)
                .generatedVideoRetentionHours(retentionHours)
                .build();
    }

    private Asset asset(UUID id, AssetType type, String storageKey) {
        return Asset.builder().id(id).type(type).storageKey(storageKey).build();
    }

    // ---------------------------------------------------------------------

    @Test
    void betaMode_keepsEverything() {
        when(betaConfig.isBetaMode()).thenReturn(true);

        service.applyPostGenerationRetention(generationId, projectId);

        verifyNoInteractions(generationRepository, assetRepository, projectAssetRepository, storageService);
    }

    @Test
    void storagePlan_keepsEverything() {
        when(generationRepository.findById(generationId)).thenReturn(Optional.of(generation(PlanType.PRO)));
        when(planDefinitionRepository.findById(PlanType.PRO)).thenReturn(Optional.of(planDef(PlanType.PRO, true, -1)));

        service.applyPostGenerationRetention(generationId, projectId);

        verify(assetRepository, never()).findAllByGenerationId(any());
        verify(assetRepository, never()).deleteAll(any());
        verify(storageService, never()).delete(anyString());
    }

    @Test
    void unknownPlan_keepsEverything() {
        when(generationRepository.findById(generationId)).thenReturn(Optional.of(generation(PlanType.PRO)));
        when(planDefinitionRepository.findById(any())).thenReturn(Optional.empty());

        service.applyPostGenerationRetention(generationId, projectId);

        verify(assetRepository, never()).deleteAll(any());
        verify(storageService, never()).delete(anyString());
    }

    @Test
    void nonStoragePlan_deletesIntermediates_keepsFinalVideoWithTtl() {
        when(generationRepository.findById(generationId)).thenReturn(Optional.of(generation(PlanType.FREE)));
        when(planDefinitionRepository.findById(PlanType.FREE)).thenReturn(Optional.of(planDef(PlanType.FREE, false, 5)));

        Asset image = asset(UUID.randomUUID(), AssetType.IMAGE, "u/images/scene_0.jpg");
        Asset voice = asset(UUID.randomUUID(), AssetType.VOICE, "voice/voice.mp3");
        Asset sceneClip = asset(UUID.randomUUID(), AssetType.VIDEO, "video/scenes/scene_00.mp4");
        Asset finalVideo = asset(finalVideoId, AssetType.VIDEO, "video/final/" + generationId + "/final.mp4");
        when(assetRepository.findAllByGenerationId(generationId))
                .thenReturn(List.of(image, voice, sceneClip, finalVideo));

        ProjectAsset bridgedScene = ProjectAsset.builder()
                .id(UUID.randomUUID()).source(AssetSource.AI_GENERATED)
                .storageUrl("projects/" + projectId + "/scenes/scene_00.mp4").build();
        ProjectAsset uploadedClip = ProjectAsset.builder()
                .id(UUID.randomUUID()).source(AssetSource.USER_UPLOAD)
                .storageUrl("u/voices/clip.mp3").build();
        when(projectAssetRepository.findByProjectIdOrderByCreatedAtAsc(projectId))
                .thenReturn(List.of(bridgedScene, uploadedClip));

        LocalDateTime before = LocalDateTime.now().plusHours(5).minusMinutes(1);
        service.applyPostGenerationRetention(generationId, projectId);
        LocalDateTime after = LocalDateTime.now().plusHours(5).plusMinutes(1);

        // Intermediates removed from DB (final video NOT among them).
        ArgumentCaptor<List<Asset>> deleted = ArgumentCaptor.forClass(List.class);
        verify(assetRepository).deleteAll(deleted.capture());
        assertEquals(3, deleted.getValue().size());
        assertTrue(deleted.getValue().contains(image));
        assertTrue(deleted.getValue().contains(voice));
        assertTrue(deleted.getValue().contains(sceneClip));
        assertFalse(deleted.getValue().contains(finalVideo));

        // Final video kept with a ~5h TTL.
        ArgumentCaptor<Asset> saved = ArgumentCaptor.forClass(Asset.class);
        verify(assetRepository).save(saved.capture());
        assertEquals(finalVideoId, saved.getValue().getId());
        assertNotNull(saved.getValue().getExpiresAt());
        assertTrue(saved.getValue().getExpiresAt().isAfter(before)
                && saved.getValue().getExpiresAt().isBefore(after));

        // Intermediate R2 objects purged; final video object left in place.
        verify(storageService).delete("u/images/scene_0.jpg");
        verify(storageService).delete("voice/voice.mp3");
        verify(storageService).delete("video/scenes/scene_00.mp4");
        verify(storageService, never()).delete("video/final/" + generationId + "/final.mp4");

        // Bridged AI copy purged from R2; the USER_UPLOAD key (shared) is not.
        verify(storageService).delete("projects/" + projectId + "/scenes/scene_00.mp4");
        verify(storageService, never()).delete("u/voices/clip.mp3");
        // …but every ProjectAsset row is removed.
        verify(projectAssetRepository).deleteAll(List.of(bridgedScene, uploadedClip));
    }

    @Test
    void nonStoragePlan_identifiesFinalVideoByStorageKeyWhenUrlMissing() {
        Generation gen = generation(PlanType.BASIC);
        gen.setVideoUrl(null); // force the storage-key fallback
        when(generationRepository.findById(generationId)).thenReturn(Optional.of(gen));
        when(planDefinitionRepository.findById(PlanType.BASIC)).thenReturn(Optional.of(planDef(PlanType.BASIC, false, 5)));

        Asset sceneClip = asset(UUID.randomUUID(), AssetType.VIDEO, "video/scenes/scene_00.mp4");
        Asset finalVideo = asset(UUID.randomUUID(), AssetType.VIDEO, "video/final/" + generationId + "/final.mp4");
        when(assetRepository.findAllByGenerationId(generationId)).thenReturn(List.of(sceneClip, finalVideo));

        service.applyPostGenerationRetention(generationId, null);

        ArgumentCaptor<List<Asset>> deleted = ArgumentCaptor.forClass(List.class);
        verify(assetRepository).deleteAll(deleted.capture());
        assertEquals(List.of(sceneClip), deleted.getValue());
        verify(assetRepository).save(finalVideo);
        verify(storageService).delete("video/scenes/scene_00.mp4");
        verify(storageService, never()).delete("video/final/" + generationId + "/final.mp4");
        // No project → no bridged cleanup.
        verify(projectAssetRepository, never()).findByProjectIdOrderByCreatedAtAsc(any());
    }
}
