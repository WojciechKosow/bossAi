package com.BossAi.bossAi.service.director;

import com.BossAi.bossAi.entity.Asset;
import com.BossAi.bossAi.entity.AssetType;
import com.BossAi.bossAi.config.properties.FfmpegProperties;
import com.BossAi.bossAi.service.OpenAiService;
import com.BossAi.bossAi.service.StorageService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Analizuje custom media assety usera i tworzy AssetProfile per asset.
 *
 * Daje GPT "oczy" — zamiast ślepego "Asset 0: type=VIDEO",
 * system wie: "Asset 0: logo animation, role=intro, mood=professional".
 *
 * Trzy tryby:
 *   1. VISION-BASED (priorytet): ekstrakcja keyframe'ów + GPT-4o Vision
 *   2. GPT-ENRICHED (fallback): GPT wnioskuje rolę z metadanych + kontekstu
 *   3. METADATA-BASED (fallback): analiza filename, type, duration
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AssetAnalyzer {

    private final OpenAiService openAiService;
    private final ObjectMapper objectMapper;
    private final StorageService storageService;
    private final FfmpegProperties ffmpegProperties;

    /** Max keyframes to extract per video */
    private static final int MAX_KEYFRAMES = 3;

    /** Max image dimension for vision API (saves tokens) */
    private static final int VISION_MAX_DIM = 512;

    /**
     * Analizuje listę custom media assetów i tworzy profile.
     *
     * Strategia:
     *   1. Próbuj GPT-4o Vision (ekstrakcja klatek + analiza wizualna)
     *   2. Fallback na GPT text-only (metadata + kontekst)
     *   3. Fallback na heurystyki z metadanych
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

        // Strategia 1: Vision — wyciągnij klatki i wyślij do GPT-4o Vision
        try {
            List<AssetProfile> visionProfiles = analyzeViaVision(assets, userPrompt);
            if (visionProfiles != null && visionProfiles.size() == assets.size()) {
                log.info("[AssetAnalyzer] Vision analysis complete — {} profiles", visionProfiles.size());
                return visionProfiles;
            }
        } catch (Exception e) {
            log.warn("[AssetAnalyzer] Vision analysis failed, falling back to text GPT: {}", e.getMessage());
        }

        // Strategia 2: GPT text-only (metadata + kontekst)
        try {
            return analyzeViaGpt(assets, userPrompt);
        } catch (Exception e) {
            log.warn("[AssetAnalyzer] GPT text analysis failed, using metadata fallback: {}", e.getMessage());
            return buildMetadataProfiles(assets);
        }
    }

    // =========================================================================
    // VISION ANALYSIS — GPT-4o with keyframes
    // =========================================================================

    /**
     * Analizuje assety przez GPT-4o Vision.
     *
     * Dla VIDEO: wyciąga keyframe'y FFmpegiem (1-3 klatki) i wysyła jako obrazy.
     * Dla IMAGE: wysyła obraz bezpośrednio.
     *
     * Jeden call per asset (Vision wymaga osobnych analiz per asset
     * bo każdy ma inny content i kontekst).
     */
    private List<AssetProfile> analyzeViaVision(List<Asset> assets, String userPrompt) {
        List<AssetProfile> profiles = new ArrayList<>();

        for (int i = 0; i < assets.size(); i++) {
            Asset asset = assets.get(i);
            try {
                List<byte[]> frames = extractFrames(asset);
                if (frames.isEmpty()) {
                    log.debug("[AssetAnalyzer] No frames extracted for asset {} — skipping vision", i);
                    profiles.add(buildSingleMetadataProfile(asset, i));
                    continue;
                }

                String prompt = buildVisionPrompt(asset, i, assets.size(), userPrompt);
                String rawJson = openAiService.analyzeWithVision(frames, prompt);
                AssetProfile profile = parseVisionResponse(rawJson, asset, i);
                profiles.add(profile);

                log.debug("[AssetAnalyzer] Vision profile for asset {}: role={}, mood={}, desc={}",
                        i, profile.getSuggestedRole(), profile.getMood(),
                        profile.getVisualDescription().substring(0, Math.min(60, profile.getVisualDescription().length())));

            } catch (Exception e) {
                log.warn("[AssetAnalyzer] Vision failed for asset {}, using metadata: {}", i, e.getMessage());
                profiles.add(buildSingleMetadataProfile(asset, i));
            }
        }

        return profiles;
    }

    /**
     * Wyciąga klatki z assetu do analizy Vision.
     *
     * VIDEO → FFmpeg ekstrakcja keyframe'ów (1-3 klatek w zależności od duration)
     * IMAGE → ładuje obraz bezpośrednio ze storage
     */
    private List<byte[]> extractFrames(Asset asset) {
        if (asset.getStorageKey() == null) return List.of();

        try {
            if (asset.getType() == AssetType.IMAGE) {
                byte[] imageBytes = storageService.load(asset.getStorageKey());
                if (imageBytes != null && imageBytes.length > 0) {
                    return List.of(imageBytes);
                }
                return List.of();
            }

            if (asset.getType() == AssetType.VIDEO) {
                return extractVideoKeyframes(asset);
            }
        } catch (Exception e) {
            log.warn("[AssetAnalyzer] Frame extraction failed for {}: {}", asset.getStorageKey(), e.getMessage());
        }
        return List.of();
    }

    /**
     * Wyciąga keyframe'y z video przez FFmpeg.
     *
     * Strategia: wyciągnij N klatek równomiernie rozłożonych w czasie.
     *   - Video <= 3s → 1 klatka (środek)
     *   - Video 3-10s → 2 klatki (1/3 i 2/3)
     *   - Video > 10s → 3 klatki (1/4, 1/2, 3/4)
     */
    private List<byte[]> extractVideoKeyframes(Asset asset) throws IOException, InterruptedException {
        Path videoPath = storageService.resolvePath(asset.getStorageKey());
        if (!Files.exists(videoPath)) {
            log.warn("[AssetAnalyzer] Video file not found: {}", videoPath);
            return List.of();
        }

        int durationSec = asset.getDurationSeconds() != null ? asset.getDurationSeconds() : 5;
        int frameCount;
        double[] timestamps;

        if (durationSec <= 3) {
            frameCount = 1;
            timestamps = new double[]{durationSec / 2.0};
        } else if (durationSec <= 10) {
            frameCount = 2;
            timestamps = new double[]{durationSec / 3.0, durationSec * 2.0 / 3.0};
        } else {
            frameCount = MAX_KEYFRAMES;
            timestamps = new double[]{durationSec / 4.0, durationSec / 2.0, durationSec * 3.0 / 4.0};
        }

        List<byte[]> frames = new ArrayList<>();
        Path tempDir = Path.of(ffmpegProperties.getTemp().getDir(), "keyframes");
        Files.createDirectories(tempDir);

        for (int i = 0; i < frameCount; i++) {
            Path framePath = tempDir.resolve("kf_" + asset.getId() + "_" + i + ".jpg");
            try {
                List<String> cmd = List.of(
                        ffmpegProperties.getBinary().getPath(),
                        "-y",
                        "-ss", String.format("%.2f", timestamps[i]),
                        "-i", videoPath.toString(),
                        "-vframes", "1",
                        "-vf", "scale='min(" + VISION_MAX_DIM + ",iw)':'min(" + VISION_MAX_DIM + ",ih)':force_original_aspect_ratio=decrease",
                        "-q:v", "6",
                        framePath.toString()
                );

                Process process = new ProcessBuilder(cmd)
                        .redirectErrorStream(true)
                        .start();
                boolean finished = process.waitFor(15, java.util.concurrent.TimeUnit.SECONDS);

                if (finished && process.exitValue() == 0 && Files.exists(framePath)) {
                    byte[] frameBytes = Files.readAllBytes(framePath);
                    if (frameBytes.length > 0) {
                        frames.add(frameBytes);
                    }
                }
            } finally {
                // Cleanup temp frame
                Files.deleteIfExists(framePath);
            }
        }

        log.debug("[AssetAnalyzer] Extracted {} keyframes from video {}", frames.size(), asset.getStorageKey());
        return frames;
    }

    private String buildVisionPrompt(Asset asset, int index, int totalAssets, String userPrompt) {
        return String.format("""
                Analyze this visual asset for a TikTok video project.

                CONTEXT:
                - User's goal: %s
                - This is asset %d of %d
                - Asset type: %s
                - Filename: %s
                - Duration: %s
                - User description: %s

                ANALYZE the visual content and return JSON:
                {
                  "visual_description": "describe what you SEE in the image/frames (be specific: colors, objects, people, text, motion)",
                  "suggested_role": "intro|hook|content|b-roll|product-shot|testimonial|transition|outro|cta|background",
                  "mood": "the emotional tone (professional|energetic|calm|dramatic|playful|luxurious|urgent|neutral)",
                  "visual_complexity": 0.0-1.0,
                  "tags": ["tag1", "tag2", "tag3"],
                  "loopable": false,
                  "dominant_colors": ["color1", "color2"],
                  "has_text": false,
                  "has_person": false
                }

                ROLE RULES:
                - If it shows a logo/brand → "intro" or "outro"
                - If it shows a person speaking/reacting → "testimonial" or "hook"
                - If it shows a product close-up → "product-shot"
                - If it's ambient/scenic → "b-roll" or "background"
                - If asset is first (%d==0) and no clear role → "hook"
                - If asset is last (%d==%d) and no clear role → "cta" or "outro"

                Return ONLY valid JSON.
                """,
                userPrompt != null ? userPrompt : "(not provided)",
                index, totalAssets,
                asset.getType().name(),
                asset.getOriginalFilename() != null ? asset.getOriginalFilename() : "unknown",
                asset.getDurationSeconds() != null ? asset.getDurationSeconds() + "s" : "unknown",
                asset.getPrompt() != null ? asset.getPrompt() : "(none)",
                index, index, totalAssets - 1
        );
    }

    private AssetProfile parseVisionResponse(String rawJson, Asset asset, int index) {
        try {
            JsonNode root = objectMapper.readTree(rawJson);
            String content = root
                    .path("choices").get(0)
                    .path("message").path("content")
                    .asText();

            JsonNode node = objectMapper.readTree(content);

            List<String> tags = new ArrayList<>();
            if (node.has("tags") && node.get("tags").isArray()) {
                for (JsonNode tagNode : node.get("tags")) {
                    tags.add(tagNode.asText());
                }
            }

            // Enrich tags with vision-specific info
            if (node.path("has_person").asBoolean(false)) tags.add("person");
            if (node.path("has_text").asBoolean(false)) tags.add("text-overlay");

            return AssetProfile.builder()
                    .assetId(asset.getId())
                    .index(index)
                    .assetType(asset.getType().name())
                    .visualDescription(node.path("visual_description").asText("unknown"))
                    .suggestedRole(node.path("suggested_role").asText("content"))
                    .mood(node.path("mood").asText("neutral"))
                    .visualComplexity(node.path("visual_complexity").asDouble(0.5))
                    .tags(tags)
                    .durationSeconds(asset.getDurationSeconds())
                    .loopable(node.path("loopable").asBoolean(false))
                    .build();

        } catch (Exception e) {
            throw new RuntimeException("[AssetAnalyzer] Failed to parse vision response", e);
        }
    }

    // =========================================================================
    // GPT TEXT-ONLY ANALYSIS (fallback)
    // =========================================================================

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
