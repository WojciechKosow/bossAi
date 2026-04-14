package com.BossAi.bossAi.service.director;

import com.BossAi.bossAi.entity.Asset;
import com.BossAi.bossAi.entity.AssetType;
import com.BossAi.bossAi.service.OpenAiService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * Analizuje custom media assety usera i tworzy AssetProfile per asset.
 *
 * Daje GPT "oczy" — zamiast ślepego "Asset 0: type=VIDEO",
 * system wie: "Asset 0: logo animation, role=intro, mood=professional".
 *
 * Dwa tryby:
 *   1. METADATA-BASED (zawsze): analizuje prompt/opis, filename, type, duration
 *   2. GPT-ENRICHED (jeśli dostępny prompt usera): GPT wnioskuje rolę z kontekstu
 *
 * Przyszłość: GPT Vision na keyframach video (wymaga ekstrakcji klatek).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AssetAnalyzer {

    private final OpenAiService openAiService;
    private final ObjectMapper objectMapper;

    /**
     * Analizuje listę custom media assetów i tworzy profile.
     *
     * @param assets    custom media assety (IMAGE/VIDEO) posortowane wg orderIndex
     * @param userPrompt  prompt usera — kontekst do wnioskowania ról
     * @return lista AssetProfile, 1 per asset, w tej samej kolejności
     */
    public List<AssetProfile> analyzeAssets(List<Asset> assets, String userPrompt) {
        if (assets == null || assets.isEmpty()) {
            return List.of();
        }

        log.info("[AssetAnalyzer] Analyzing {} assets with user prompt context", assets.size());

        try {
            return analyzeViaGpt(assets, userPrompt);
        } catch (Exception e) {
            log.warn("[AssetAnalyzer] GPT analysis failed, using metadata fallback: {}", e.getMessage());
            return buildMetadataProfiles(assets);
        }
    }

    /**
     * GPT analizuje assety w kontekście prompta usera.
     * Jeden GPT call na całą listę (efektywniejsze niż per-asset).
     */
    private List<AssetProfile> analyzeViaGpt(List<Asset> assets, String userPrompt) {
        String prompt = buildAnalysisPrompt(assets, userPrompt);
        String rawJson = openAiService.generateDirectorPlan(prompt);

        return parseGptResponse(rawJson, assets);
    }

    private String buildAnalysisPrompt(List<Asset> assets, String userPrompt) {
        StringBuilder sb = new StringBuilder();

        sb.append("""
                You are a professional video editor analyzing user-provided media assets.
                Your job: understand WHAT each asset shows, WHAT ROLE it should play in the video,
                and HOW it relates to the user's creative intent.

                The user wants to create a short-form video (TikTok/Reels style).
                Below is their prompt and their uploaded assets.

                For each asset, determine:
                - visual_description: What the asset likely shows (based on filename, type, description, duration)
                - suggested_role: What editing role this asset should play
                - mood: The emotional tone of the asset
                - visual_complexity: 0.0 (static/simple) to 1.0 (dynamic/complex)
                - tags: 3-5 descriptive keywords
                - loopable: Could this asset loop seamlessly?

                ROLE VALUES (pick the best fit):
                - "intro": opening shot, logo, brand reveal — shown FIRST
                - "hook": attention-grabbing shot — shown in first 2-3 seconds
                - "content": main body content — shown during narration
                - "b-roll": supplementary footage — shown during transitions or pauses
                - "product-shot": product close-up — shown during product mention
                - "testimonial": person speaking/reacting — shown during social proof
                - "transition": visual bridge between sections
                - "outro": closing shot — shown LAST
                - "cta": call-to-action visual — shown at end
                - "background": ambient/filler — used when no specific asset matches

                TIMING INFERENCE RULES:
                - Short videos (<5s) are often intros/outros/transitions
                - Medium videos (5-15s) are usually content/b-roll
                - Long videos (>15s) are likely main content
                - Images are versatile — role depends on context
                - If user mentions "intro" or "beginning" for an asset → role=intro
                - If user mentions "outro" or "ending" → role=outro
                - If asset is first in order and user didn't specify → likely intro or hook
                - If asset is last in order and user didn't specify → likely outro or cta

                Return ONLY valid JSON array:
                [
                  {
                    "asset_index": 0,
                    "visual_description": "description of what the asset likely shows",
                    "suggested_role": "intro",
                    "mood": "professional",
                    "visual_complexity": 0.6,
                    "tags": ["logo", "brand", "animation"],
                    "loopable": false
                  }
                ]

                """);

        sb.append("=== USER'S PROMPT ===\n");
        sb.append(userPrompt != null ? userPrompt : "(no prompt provided)").append("\n\n");

        sb.append("=== UPLOADED ASSETS ===\n");
        for (int i = 0; i < assets.size(); i++) {
            Asset a = assets.get(i);
            sb.append("Asset ").append(i).append(":\n");
            sb.append("  type: ").append(a.getType()).append("\n");
            if (a.getOriginalFilename() != null) {
                sb.append("  filename: ").append(a.getOriginalFilename()).append("\n");
            }
            if (a.getPrompt() != null && !a.getPrompt().isBlank()) {
                sb.append("  description: \"").append(a.getPrompt()).append("\"\n");
            }
            if (a.getDurationSeconds() != null) {
                sb.append("  duration: ").append(a.getDurationSeconds()).append("s\n");
            }
            if (a.getWidth() != null && a.getHeight() != null) {
                sb.append("  dimensions: ").append(a.getWidth()).append("x").append(a.getHeight()).append("\n");
            }
        }

        sb.append("\nReturn ONLY the JSON array — no markdown, no explanations.\n");

        return sb.toString();
    }

    private List<AssetProfile> parseGptResponse(String rawJson, List<Asset> assets) {
        try {
            JsonNode root = objectMapper.readTree(rawJson);
            String content = root
                    .path("choices").get(0)
                    .path("message").path("content")
                    .asText();

            JsonNode profilesArray = objectMapper.readTree(content);

            List<AssetProfile> profiles = new ArrayList<>();
            for (int i = 0; i < assets.size(); i++) {
                Asset asset = assets.get(i);
                JsonNode node = i < profilesArray.size() ? profilesArray.get(i) : null;

                if (node != null) {
                    List<String> tags = new ArrayList<>();
                    if (node.has("tags") && node.get("tags").isArray()) {
                        for (JsonNode tagNode : node.get("tags")) {
                            tags.add(tagNode.asText());
                        }
                    }

                    profiles.add(AssetProfile.builder()
                            .assetId(asset.getId())
                            .index(i)
                            .assetType(asset.getType().name())
                            .visualDescription(node.path("visual_description").asText("unknown"))
                            .suggestedRole(node.path("suggested_role").asText("content"))
                            .mood(node.path("mood").asText("neutral"))
                            .visualComplexity(node.path("visual_complexity").asDouble(0.5))
                            .tags(tags)
                            .durationSeconds(asset.getDurationSeconds())
                            .loopable(node.path("loopable").asBoolean(false))
                            .build());
                } else {
                    profiles.add(buildSingleMetadataProfile(asset, i));
                }
            }

            log.info("[AssetAnalyzer] GPT analysis complete — {} profiles", profiles.size());
            return profiles;

        } catch (Exception e) {
            log.warn("[AssetAnalyzer] Failed to parse GPT profiles: {}", e.getMessage());
            return buildMetadataProfiles(assets);
        }
    }

    // =========================================================================
    // METADATA FALLBACK — when GPT fails or is unavailable
    // =========================================================================

    private List<AssetProfile> buildMetadataProfiles(List<Asset> assets) {
        List<AssetProfile> profiles = new ArrayList<>();
        for (int i = 0; i < assets.size(); i++) {
            profiles.add(buildSingleMetadataProfile(assets.get(i), i));
        }
        log.info("[AssetAnalyzer] Built {} metadata-based profiles (fallback)", profiles.size());
        return profiles;
    }

    private AssetProfile buildSingleMetadataProfile(Asset asset, int index) {
        String type = asset.getType().name();
        boolean isVideo = "VIDEO".equals(type);
        Integer dur = asset.getDurationSeconds();

        // Infer role from position + duration
        String role;
        if (index == 0) {
            role = isVideo && dur != null && dur <= 5 ? "intro" : "hook";
        } else {
            role = "content";
        }

        // Infer mood from description
        String mood = "neutral";
        if (asset.getPrompt() != null) {
            String desc = asset.getPrompt().toLowerCase();
            if (desc.contains("energi") || desc.contains("fast") || desc.contains("dynamic")) mood = "energetic";
            else if (desc.contains("calm") || desc.contains("slow") || desc.contains("gentle")) mood = "calm";
            else if (desc.contains("drama") || desc.contains("epic") || desc.contains("intense")) mood = "dramatic";
        }

        // Complexity heuristic: video = higher, short = lower
        double complexity = isVideo ? 0.7 : 0.4;

        // Tags from filename + description
        List<String> tags = new ArrayList<>();
        tags.add(type.toLowerCase());
        if (asset.getOriginalFilename() != null) {
            String name = asset.getOriginalFilename().replaceAll("[^a-zA-Z0-9]", " ").toLowerCase();
            for (String word : name.split("\\s+")) {
                if (word.length() >= 3 && tags.size() < 5) {
                    tags.add(word);
                }
            }
        }

        return AssetProfile.builder()
                .assetId(asset.getId())
                .index(index)
                .assetType(type)
                .visualDescription(asset.getPrompt() != null ? asset.getPrompt() : "User-uploaded " + type.toLowerCase())
                .suggestedRole(role)
                .mood(mood)
                .visualComplexity(complexity)
                .tags(tags)
                .durationSeconds(dur)
                .loopable(false)
                .build();
    }
}
