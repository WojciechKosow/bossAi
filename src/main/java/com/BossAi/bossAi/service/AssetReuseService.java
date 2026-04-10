package com.BossAi.bossAi.service;

import com.BossAi.bossAi.entity.Asset;
import com.BossAi.bossAi.entity.AssetType;
import com.BossAi.bossAi.entity.User;
import com.BossAi.bossAi.repository.AssetRepository;
import com.BossAi.bossAi.repository.UserRepository;
import com.BossAi.bossAi.service.generation.GenerationContext;
import com.BossAi.bossAi.service.generation.context.SceneAsset;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.BossAi.bossAi.config.properties.OpenAiProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.*;
import java.util.stream.Collectors;

/**
 * AssetReuseService — analizuje wcześniej wygenerowane assety usera
 * i dopasowuje je tematycznie do nowych scen przez GPT.
 *
 * Przepływ:
 *   1. Pobierz reusable assety usera (IMAGE, VIDEO) z bazy
 *   2. Wyślij do GPT listę assetów (prompt/opis) + listę nowych scen (imagePrompt)
 *   3. GPT zwraca mapowanie: sceneIndex → assetId (lub null jeśli brak dopasowania)
 *   4. Wynik trafia do GenerationContext.reusedImageAssets / reusedVideoAssets
 *
 * Minimalne wymagania do reuse (normal mode):
 *   - User musi mieć min. 1 reusable IMAGE asset (changed from 3)
 *   - Plan > BASIC (STARTER i FREE nie mają dostępu)
 *
 * TEST MODE (forceReuseForTesting):
 *   - Bypasses all thresholds and GPT matching
 *   - Directly assigns available assets to scenes (round-robin)
 *   - No fal.ai API calls are made
 */
@Slf4j
@Service
public class AssetReuseService {

    private final AssetRepository assetRepository;
    private final UserRepository userRepository;
    private final WebClient webClient;
    private final OpenAiProperties properties;
    private final ObjectMapper objectMapper;

    /** Lowered from 3 → 1: even a single reusable image is worth reusing */
    private static final int MIN_REUSABLE_IMAGES = 1;
    private static final int MIN_REUSABLE_VIDEOS = 1;

    public AssetReuseService(
            AssetRepository assetRepository,
            UserRepository userRepository,
            @Qualifier("openAiWebClient") WebClient webClient,
            OpenAiProperties properties,
            ObjectMapper objectMapper
    ) {
        this.assetRepository = assetRepository;
        this.userRepository = userRepository;
        this.webClient = webClient;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    /**
     * Analizuje assety usera i wypełnia context.reusedImageAssets / reusedVideoAssets.
     * Wywoływany po ScriptStep (potrzebujemy scen z imagePrompt).
     */
    public void matchReusableAssets(GenerationContext context) {
        UUID userId = context.getUserId();
        User user = userRepository.findById(userId).orElseThrow();

        boolean forceReuse = context.isForceReuseForTesting();

        // =====================================================================
        // TEST ONLY: Force reuse mode — bypass GPT, assign directly
        // =====================================================================
        if (forceReuse) {
            log.warn("[AssetReuseService] TEST MODE: forceReuseForTesting=true — " +
                    "bypassing GPT matching, assigning assets directly");
            forceAssignAssets(user, context);
            return;
        }

        // =====================================================================
        // Normal mode: GPT-based thematic matching
        // =====================================================================
        normalMatchAssets(user, context);
    }

    /**
     * TEST ONLY — Forces 100% asset reuse by directly assigning existing assets
     * to scenes in round-robin fashion. No GPT call, no thresholds, no API costs.
     *
     * Bypasses reusable=true filter — uses ALL user assets with prompts.
     * If user has zero assets, throws an exception (test mode expects assets to exist).
     */
    private void forceAssignAssets(User user, GenerationContext context) {
        // Fetch ALL assets (bypass reusable filter — test mode)
        List<Asset> allImages = assetRepository
                .findByUserAndTypeAndPromptIsNotNull(user, AssetType.IMAGE);
        List<Asset> allVideos = assetRepository
                .findByUserAndTypeAndPromptIsNotNull(user, AssetType.VIDEO);

        log.warn("[AssetReuseService] TEST MODE — found {} IMAGE, {} VIDEO assets for user {}",
                allImages.size(), allVideos.size(), context.getUserId());

        if (allImages.isEmpty()) {
            throw new IllegalStateException(
                    "[AssetReuseService] TEST MODE FAILED — user has 0 IMAGE assets with prompts. " +
                    "Cannot force reuse. Run at least one normal generation first to create assets.");
        }

        List<SceneAsset> scenes = context.getScenes();
        if (scenes == null || scenes.isEmpty()) {
            throw new IllegalStateException(
                    "[AssetReuseService] TEST MODE FAILED — no scenes in context. " +
                    "ScriptStep must run before AssetReuseStep.");
        }

        // Assign IMAGE assets round-robin to all scenes
        Map<String, Asset> imageMatches = new HashMap<>();
        for (int i = 0; i < scenes.size(); i++) {
            SceneAsset scene = scenes.get(i);
            Asset imageAsset = allImages.get(i % allImages.size());
            imageMatches.put(scene.getImagePrompt(), imageAsset);
            log.warn("[AssetReuseService] TEST MODE — scene {} → IMAGE asset {} (prompt: {})",
                    scene.getIndex(), imageAsset.getId(),
                    truncate(imageAsset.getPrompt(), 60));
        }
        context.setReusedImageAssets(imageMatches);

        // Assign VIDEO assets round-robin (if any exist)
        if (!allVideos.isEmpty()) {
            Map<String, Asset> videoMatches = new HashMap<>();
            for (int i = 0; i < scenes.size(); i++) {
                SceneAsset scene = scenes.get(i);
                Asset videoAsset = allVideos.get(i % allVideos.size());
                videoMatches.put(scene.getImagePrompt(), videoAsset);
                log.warn("[AssetReuseService] TEST MODE — scene {} → VIDEO asset {} (prompt: {})",
                        scene.getIndex(), videoAsset.getId(),
                        truncate(videoAsset.getPrompt(), 60));
            }
            context.setReusedVideoAssets(videoMatches);
        }

        log.warn("[AssetReuseService] TEST MODE DONE — {} IMAGE matches, {} VIDEO matches " +
                "(zero API calls will be made)",
                imageMatches.size(),
                allVideos.isEmpty() ? 0 : scenes.size());
    }

    /**
     * Normal mode — GPT-based thematic matching with lowered thresholds.
     */
    private void normalMatchAssets(User user, GenerationContext context) {
        // Pobierz reusable assety z bazy
        List<Asset> reusableImages = assetRepository
                .findByUserAndReusableTrueAndTypeAndPromptIsNotNull(user, AssetType.IMAGE);
        List<Asset> reusableVideos = assetRepository
                .findByUserAndReusableTrueAndTypeAndPromptIsNotNull(user, AssetType.VIDEO);

        log.info("[AssetReuseService] User {} ma {} reusable IMAGE, {} reusable VIDEO",
                context.getUserId(), reusableImages.size(), reusableVideos.size());

        if (reusableImages.size() < MIN_REUSABLE_IMAGES) {
            log.info("[AssetReuseService] Za mało reusable IMAGE ({} < {}) — pomijam reuse. " +
                    "Tip: assets get reusable=true only for PRO/CREATOR plans.",
                    reusableImages.size(), MIN_REUSABLE_IMAGES);
            return;
        }

        List<SceneAsset> scenes = context.getScenes();
        if (scenes == null || scenes.isEmpty()) {
            log.warn("[AssetReuseService] Brak scen w kontekście — pomijam reuse");
            return;
        }

        try {
            // GPT matching — obrazy
            Map<String, Asset> imageMatches = matchViaGpt(
                    context.getPrompt(), scenes, reusableImages, "IMAGE");
            context.setReusedImageAssets(imageMatches);
            log.info("[AssetReuseService] GPT dopasował {} IMAGE assetów do {} scen",
                    imageMatches.size(), scenes.size());

            // GPT matching — wideo (jeśli są)
            if (reusableVideos.size() >= MIN_REUSABLE_VIDEOS) {
                Map<String, Asset> videoMatches = matchViaGpt(
                        context.getPrompt(), scenes, reusableVideos, "VIDEO");
                context.setReusedVideoAssets(videoMatches);
                log.info("[AssetReuseService] GPT dopasował {} VIDEO assetów do {} scen",
                        videoMatches.size(), scenes.size());
            }

        } catch (Exception e) {
            log.warn("[AssetReuseService] GPT matching failed — pipeline kontynuuje bez reuse: {}",
                    e.getMessage());
            context.setReusedImageAssets(new HashMap<>());
            context.setReusedVideoAssets(new HashMap<>());
        }
    }

    /**
     * Wysyła do GPT listę assetów + listę scen i prosi o dopasowanie.
     * GPT zwraca JSON: { "matches": [ { "sceneIndex": 0, "assetId": "uuid" }, ... ] }
     * Sceny bez dopasowania mają assetId = null.
     */
    private Map<String, Asset> matchViaGpt(
            String userPrompt,
            List<SceneAsset> scenes,
            List<Asset> availableAssets,
            String assetType
    ) {
        // Buduj opis assetów dla GPT
        StringBuilder assetsDescription = new StringBuilder();
        Map<String, Asset> assetMap = new HashMap<>();
        for (int i = 0; i < availableAssets.size(); i++) {
            Asset asset = availableAssets.get(i);
            assetMap.put(asset.getId().toString(), asset);
            assetsDescription.append(String.format(
                    "  ASSET_%d: id=\"%s\", prompt=\"%s\"\n",
                    i, asset.getId(), asset.getPrompt()));
        }

        // Buduj opis scen dla GPT
        StringBuilder scenesDescription = new StringBuilder();
        for (SceneAsset scene : scenes) {
            scenesDescription.append(String.format(
                    "  SCENE_%d: imagePrompt=\"%s\"\n",
                    scene.getIndex(), scene.getImagePrompt()));
        }

        String systemPrompt = """
            You are an asset matching AI. Your job is to find thematic matches between
            existing visual assets and new scene descriptions.

            RULES:
            - Match if the asset is thematically RELATED to the scene (>50% relevance)
            - Consider: subject matter, visual style, mood, objects, setting, product type
            - Be GENEROUS with matching — reusing an existing asset saves money
            - If the same product/brand/topic appears in both, that's a match
            - Each asset can be matched to at most ONE scene
            - Prefer matching assets to scenes where the visual content overlaps most
            - If there are more scenes than assets, match as many as possible
            - It's OK to match even if style/mood differs slightly — content match is more important

            Respond ONLY with valid JSON, no markdown, no explanation.

            JSON FORMAT:
            {
              "matches": [
                { "sceneIndex": 0, "assetId": "uuid-here" },
                { "sceneIndex": 1, "assetId": null },
                { "sceneIndex": 2, "assetId": "uuid-here" }
              ],
              "reasoning": "brief explanation of matching decisions"
            }
            """;

        String userMessage = String.format("""
            USER PROMPT: %s

            ASSET TYPE: %s
            AVAILABLE ASSETS:
            %s

            SCENES TO MATCH:
            %s

            Match each scene to the best available %s asset, or null if no good match exists.
            Be generous — reusing saves API costs. Match if the topic/product is the same.
            """, userPrompt, assetType, assetsDescription, scenesDescription, assetType);

        Map<String, Object> requestBody = Map.of(
                "model", properties.getModel().getChat(),
                "response_format", Map.of("type", "json_object"),
                "temperature", 0.2,
                "max_tokens", 1000,
                "messages", List.of(
                        Map.of("role", "system", "content", systemPrompt),
                        Map.of("role", "user", "content", userMessage)
                )
        );

        String rawJson = webClient.post()
                .uri("/chat/completions")
                .bodyValue(requestBody)
                .retrieve()
                .bodyToMono(String.class)
                .block();

        return parseMatchResult(rawJson, assetMap, scenes);
    }

    private Map<String, Asset> parseMatchResult(
            String rawResponse,
            Map<String, Asset> assetMap,
            List<SceneAsset> scenes
    ) {
        Map<String, Asset> result = new HashMap<>();

        try {
            JsonNode root = objectMapper.readTree(rawResponse);
            String jsonContent = root.path("choices").get(0)
                    .path("message").path("content").asText();

            JsonNode matchRoot = objectMapper.readTree(jsonContent);
            JsonNode matches = matchRoot.path("matches");

            if (!matches.isArray()) {
                log.warn("[AssetReuseService] GPT nie zwrócił tablicy matches");
                return result;
            }

            Set<String> usedAssetIds = new HashSet<>();

            for (JsonNode match : matches) {
                int sceneIndex = match.path("sceneIndex").asInt(-1);
                String assetId = match.path("assetId").asText(null);

                if (sceneIndex < 0 || assetId == null || "null".equals(assetId)) {
                    continue;
                }

                // Nie używaj tego samego assetu dwukrotnie
                if (usedAssetIds.contains(assetId)) {
                    continue;
                }

                Asset asset = assetMap.get(assetId);
                if (asset == null) {
                    log.warn("[AssetReuseService] GPT zwrócił nieznany assetId: {}", assetId);
                    continue;
                }

                // Znajdź imagePrompt sceny jako klucz
                scenes.stream()
                        .filter(s -> s.getIndex() == sceneIndex)
                        .findFirst()
                        .ifPresent(scene -> {
                            result.put(scene.getImagePrompt(), asset);
                            usedAssetIds.add(assetId);
                            log.info("[AssetReuseService] Match: scena {} → asset {} (prompt: {})",
                                    sceneIndex, assetId,
                                    truncate(asset.getPrompt(), 50));
                        });
            }

            String reasoning = matchRoot.path("reasoning").asText("");
            if (!reasoning.isBlank()) {
                log.info("[AssetReuseService] GPT reasoning: {}", reasoning);
            }

        } catch (Exception e) {
            log.error("[AssetReuseService] Błąd parsowania GPT response: {}", e.getMessage());
        }

        return result;
    }

    private static String truncate(String s, int maxLen) {
        if (s == null) return "null";
        return s.length() <= maxLen ? s : s.substring(0, maxLen) + "...";
    }
}
