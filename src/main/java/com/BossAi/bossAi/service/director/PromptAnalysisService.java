package com.BossAi.bossAi.service.director;

import com.BossAi.bossAi.entity.Asset;
import com.BossAi.bossAi.entity.User;
import com.BossAi.bossAi.repository.AssetRepository;
import com.BossAi.bossAi.repository.UserRepository;
import com.BossAi.bossAi.request.PromptAnalysisRequest;
import com.BossAi.bossAi.response.PromptAnalysisResponse;
import com.BossAi.bossAi.service.generation.GenerationContext;
import com.BossAi.bossAi.service.generation.context.SceneAsset;
import com.BossAi.bossAi.service.generation.step.ScriptStep;
import com.BossAi.bossAi.service.style.StyleService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * Dry-run analizy prompta (Phase 2.1 z CLAUDE.md).
 *
 * Bierze prompt + custom media assety i bez tworzenia Generation/VideoProject
 * uruchamia:
 *   1. AssetAnalyzer (opcjonalnie)
 *   2. UserIntentParser
 *   3. ScriptStep (na transient GenerationContext)
 *
 * Wynik: PromptAnalysisResponse — proponowane sceny + intencja + dostępne assety.
 * Frontend / Postman używa tego jako preview, by user mógł przed generacją zobaczyć
 * scenariusz i zdecydować jak rozpisać assety na sceny.
 *
 * Uwaga: 2 GPT calle (UserIntentParser + ScriptStep) — rozważ caching jeśli
 * frontend będzie często odpytywał ten sam prompt.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PromptAnalysisService {

    private final UserRepository userRepository;
    private final AssetRepository assetRepository;
    private final UserIntentParser userIntentParser;
    private final AssetAnalyzer assetAnalyzer;
    private final ScriptStep scriptStep;
    private final StyleService styleService;

    public PromptAnalysisResponse analyzePrompt(PromptAnalysisRequest req, String email) {
        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "User not found"));

        List<Asset> customMedia = resolveCustomAssets(req.getCustomMediaAssetIds(), user);

        log.info("[PromptAnalysis] Analyzing prompt — length: {}, style: {}, customMedia: {}, analyzeAssets: {}",
                req.getPrompt().length(), req.getStyle(), customMedia.size(), req.isAnalyzeAssets());

        // 1. Optional asset analysis (vision/text profiles)
        List<AssetProfile> profiles = List.of();
        if (req.isAnalyzeAssets() && !customMedia.isEmpty()) {
            try {
                profiles = assetAnalyzer.analyzeAssets(customMedia, req.getPrompt());
            } catch (Exception e) {
                log.warn("[PromptAnalysis] Asset analysis failed — continuing without profiles: {}", e.getMessage());
            }
        }

        // 2. Parse user intent (GPT)
        UserEditIntent intent = userIntentParser.parseIntent(req.getPrompt(), customMedia, profiles);

        // 3. Run ScriptStep on a transient context (no DB writes — pure dry run)
        GenerationContext ctx = GenerationContext.builder()
                .prompt(req.getPrompt())
                .style(req.getStyle())
                .styleConfig(req.getStyle() != null ? styleService.getConfig(req.getStyle()) : null)
                .customMediaAssets(new ArrayList<>(customMedia))
                .assetProfiles(new ArrayList<>(profiles))
                .userEditIntent(intent)
                .build();

        try {
            scriptStep.execute(ctx);
        } catch (Exception e) {
            log.error("[PromptAnalysis] ScriptStep dry-run failed", e);
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "Script analysis failed: " + e.getMessage());
        }

        return buildResponse(ctx, customMedia, intent);
    }

    // ─── Helpers ──────────────────────────────────────────────────────

    private List<Asset> resolveCustomAssets(List<UUID> assetIds, User user) {
        if (assetIds == null || assetIds.isEmpty()) {
            return List.of();
        }
        List<Asset> assets = assetRepository.findAllById(assetIds);
        assets.forEach(asset -> {
            if (!asset.getUser().getId().equals(user.getId())) {
                throw new AccessDeniedException("Asset " + asset.getId() + " is not user's asset");
            }
        });
        return assets.stream()
                .sorted(Comparator.comparing(Asset::getOrderIndex,
                        Comparator.nullsLast(Comparator.naturalOrder())))
                .toList();
    }

    private PromptAnalysisResponse buildResponse(GenerationContext ctx,
                                                 List<Asset> customMedia,
                                                 UserEditIntent intent) {
        List<PromptAnalysisResponse.ProposedScene> scenes = new ArrayList<>();
        List<SceneAsset> sceneAssets = ctx.getScenes() != null ? ctx.getScenes() : List.of();
        for (SceneAsset s : sceneAssets) {
            UserEditIntent.AssetPlacement placement = intent != null
                    ? intent.getPlacementForAsset(s.getIndex()) : null;
            SceneDirective directive = intent != null
                    ? intent.getSceneDirective(s.getIndex()) : null;

            UUID suggestedAssetId = (s.getIndex() < customMedia.size())
                    ? customMedia.get(s.getIndex()).getId() : null;

            scenes.add(PromptAnalysisResponse.ProposedScene.builder()
                    .index(s.getIndex())
                    .imagePrompt(s.getImagePrompt())
                    .motionPrompt(s.getMotionPrompt())
                    .subtitleText(s.getSubtitleText())
                    .durationMs(s.getDurationMs())
                    .suggestedRole(placement != null ? placement.getRole() : null)
                    .suggestedMood(placement != null ? placement.getMood() : null)
                    .sceneDirection(placement != null ? placement.getSceneDescription() : null)
                    .suggestedAssetId(suggestedAssetId)
                    .layers(buildLayerPreviews(directive, customMedia))
                    .build());
        }

        PromptAnalysisResponse.UserIntentSummary intentSummary = null;
        if (intent != null) {
            intentSummary = PromptAnalysisResponse.UserIntentSummary.builder()
                    .overallGoal(intent.getOverallGoal())
                    .pacingPreference(intent.getPacingPreference())
                    .editingStyle(intent.getEditingStyle())
                    .structureHints(intent.getStructureHints())
                    .userControlsOrder(intent.isUserControlsOrder())
                    .hasExplicitInstructions(intent.hasExplicitInstructions())
                    .build();
        }

        List<PromptAnalysisResponse.AssetMeta> assetMetas = customMedia.stream()
                .map(a -> PromptAnalysisResponse.AssetMeta.builder()
                        .id(a.getId())
                        .type(a.getType())
                        .originalFilename(a.getOriginalFilename())
                        .orderIndex(a.getOrderIndex())
                        .description(a.getPrompt())
                        .build())
                .toList();

        return PromptAnalysisResponse.builder()
                .contentType(ctx.getScript() != null ? ctx.getScript().contentType() : null)
                .hook(ctx.getScript() != null ? ctx.getScript().hook() : null)
                .callToAction(ctx.getScript() != null ? ctx.getScript().callToAction() : null)
                .totalDurationMs(ctx.getScript() != null ? ctx.getScript().totalDurationMs() : 0)
                .scenes(scenes)
                .userIntent(intentSummary)
                .availableAssets(assetMetas)
                .build();
    }

    private List<PromptAnalysisResponse.LayerPreview> buildLayerPreviews(SceneDirective directive,
                                                                        List<Asset> customMedia) {
        if (directive == null || directive.getLayers() == null || directive.getLayers().isEmpty()) {
            return List.of();
        }
        List<PromptAnalysisResponse.LayerPreview> previews = new ArrayList<>();
        for (SceneDirective.LayerDirective l : directive.getLayers()) {
            UUID assetId = null;
            if ("provided".equals(l.getSource())
                    && l.getAssetIndex() >= 0
                    && l.getAssetIndex() < customMedia.size()) {
                assetId = customMedia.get(l.getAssetIndex()).getId();
            }
            previews.add(PromptAnalysisResponse.LayerPreview.builder()
                    .layerIndex(l.getLayerIndex())
                    .role(l.getRole())
                    .source(l.getSource())
                    .assetId(assetId)
                    .generationPrompt(l.getGenerationPrompt())
                    .build());
        }
        return previews;
    }
}
