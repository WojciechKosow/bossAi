package com.BossAi.bossAi.service.director.overlay;

import com.BossAi.bossAi.entity.Asset;
import com.BossAi.bossAi.service.OpenAiService;
import com.BossAi.bossAi.service.StorageService;
import com.BossAi.bossAi.service.SubtitleService;
import com.BossAi.bossAi.service.generation.GenerationContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Intelligently places user-provided overlay images on the video timeline.
 *
 * Two-phase approach:
 *   Phase 1 — Describe: GPT Vision analyzes each overlay image and returns a
 *             semantic descriptor (category, label, trigger keywords, size hint).
 *   Phase 2 — Place: single GPT call with narration transcript + timestamps +
 *             overlay descriptors → precise startMs/endMs/position/size decisions.
 *
 * The engine acts like a human editor: "Discord logo → narrator says 'join our
 * Discord server' at 4.2s → show logo from 4.0s to 7.5s in bottom-right corner."
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OverlayPlacementEngine {

    private final OpenAiService openAiService;
    private final StorageService storageService;
    private final ObjectMapper objectMapper;

    // ── Position & size presets per category ─────────────────────────────────

    private static final Map<String, float[]> POSITION_PRESETS = Map.of(
            // category → [x, y, width, height]
            "logo",        new float[]{0.76f, 0.80f, 0.20f, 0.113f},
            "screenshot",  new float[]{0.10f, 0.12f, 0.80f, 0.45f},
            "product",     new float[]{0.25f, 0.25f, 0.50f, 0.28f},
            "cta",         new float[]{0.10f, 0.72f, 0.80f, 0.14f},
            "decoration",  new float[]{0.78f, 0.04f, 0.18f, 0.10f}
    );

    private static final Map<String, String> DEFAULT_ANIMATION = Map.of(
            "logo",       "fade_in",
            "screenshot", "slide_up",
            "product",    "zoom_in",
            "cta",        "slide_up",
            "decoration", "fade_in"
    );

    // =========================================================================
    // PUBLIC API
    // =========================================================================

    /**
     * Full pipeline: describe overlays → place them.
     * Stores results in context.overlayPlacements.
     *
     * @param context must have overlayAssets, wordTimings (or script), and scenes
     */
    public void describeAndPlace(GenerationContext context) {
        List<Asset> overlays = context.getOverlayAssets();
        if (overlays == null || overlays.isEmpty()) {
            log.debug("[OverlayEngine] No overlay assets — skipping");
            return;
        }

        log.info("[OverlayEngine] Processing {} overlay asset(s)", overlays.size());

        List<OverlayDescriptor> descriptors = describeOverlays(overlays);
        if (descriptors.isEmpty()) {
            log.warn("[OverlayEngine] All overlay descriptions failed — skipping placement");
            return;
        }

        List<OverlayPlacement> placements = placeOverlays(descriptors, context);
        context.setOverlayPlacements(placements);

        log.info("[OverlayEngine] Placement complete — {} overlay(s) placed on timeline",
                placements.size());
    }

    // =========================================================================
    // PHASE 1 — DESCRIBE
    // =========================================================================

    private List<OverlayDescriptor> describeOverlays(List<Asset> assets) {
        List<OverlayDescriptor> result = new ArrayList<>();

        for (Asset asset : assets) {
            try {
                OverlayDescriptor descriptor = describeOne(asset);
                result.add(descriptor);
                log.debug("[OverlayEngine] Described {} → category={} label={}",
                        asset.getId(), descriptor.getCategory(), descriptor.getSemanticLabel());
            } catch (Exception e) {
                log.warn("[OverlayEngine] Vision description failed for asset {} — using fallback: {}",
                        asset.getId(), e.getMessage());
                result.add(fallbackDescriptor(asset));
            }
        }

        return result;
    }

    private OverlayDescriptor describeOne(Asset asset) throws Exception {
        byte[] imageBytes = storageService.load(asset.getStorageKey());

        String prompt = """
                Analyze this image that will be used as an overlay in a TikTok short-form video.
                Return ONLY valid JSON (no markdown) with this exact structure:
                {
                  "category": "logo|screenshot|product|cta|decoration",
                  "semantic_label": "brief description in English, max 15 words",
                  "trigger_keywords": ["keyword1", "keyword2", "keyword3"],
                  "suggested_position": "bottom_right|bottom_left|top_right|top_left|center|bottom_center",
                  "suggested_size": "small|medium|large"
                }

                Category rules:
                  logo       — brand/social icon (Discord, Twitter, Instagram, YouTube, TikTok, company logo)
                  screenshot — screen capture, stats table, to-do list, results, graph
                  product    — physical product photo or mockup
                  cta        — call-to-action button, QR code, coupon, "follow now" graphic
                  decoration — abstract sticker, emoji-style graphic, ornamental element

                trigger_keywords: 2-5 Polish or English words that, when spoken in narration, indicate \
                this overlay should be shown. E.g. for Discord logo: ["discord", "serwer", "dołącz", "community"].

                suggested_size: small (<25% frame width), medium (25-55%), large (>55%)
                """;

        String rawJson = openAiService.analyzeWithVision(List.of(imageBytes), prompt);
        JsonNode root = objectMapper.readTree(rawJson);

        // analyzeWithVision wraps response in OpenAI chat completion envelope
        JsonNode content = root.path("choices").get(0).path("message").path("content");
        JsonNode data = objectMapper.readTree(content.asText());

        List<String> keywords = new ArrayList<>();
        data.path("trigger_keywords").forEach(kw -> keywords.add(kw.asText()));

        return OverlayDescriptor.builder()
                .assetId(asset.getId())
                .assetUrl(storageService.generateUrl(asset.getStorageKey()))
                .category(data.path("category").asText("decoration"))
                .semanticLabel(data.path("semantic_label").asText("overlay image"))
                .triggerKeywords(keywords)
                .suggestedPosition(data.path("suggested_position").asText("bottom_right"))
                .suggestedSize(data.path("suggested_size").asText("small"))
                .build();
    }

    private OverlayDescriptor fallbackDescriptor(Asset asset) {
        String filename = asset.getOriginalFilename() != null
                ? asset.getOriginalFilename().toLowerCase() : "";

        String category = "decoration";
        if (filename.contains("logo") || filename.contains("discord") ||
                filename.contains("twitter") || filename.contains("ig") ||
                filename.contains("tiktok") || filename.contains("yt")) {
            category = "logo";
        } else if (filename.contains("screen") || filename.contains("result") ||
                filename.contains("todo") || filename.contains("list")) {
            category = "screenshot";
        } else if (filename.contains("cta") || filename.contains("qr") ||
                filename.contains("follow")) {
            category = "cta";
        }

        return OverlayDescriptor.builder()
                .assetId(asset.getId())
                .assetUrl(storageService.generateUrl(asset.getStorageKey()))
                .category(category)
                .semanticLabel(asset.getOriginalFilename() != null
                        ? asset.getOriginalFilename() : "overlay image")
                .triggerKeywords(List.of())
                .suggestedPosition("bottom_right")
                .suggestedSize("small")
                .build();
    }

    // =========================================================================
    // PHASE 2 — PLACE
    // =========================================================================

    private List<OverlayPlacement> placeOverlays(List<OverlayDescriptor> descriptors,
                                                  GenerationContext context) {
        String transcript = buildTranscript(context);
        int totalDurationMs = estimateTotalDuration(context);

        if (transcript.isBlank()) {
            log.warn("[OverlayEngine] No transcript available — applying default end-of-video placement");
            return defaultPlacements(descriptors, totalDurationMs);
        }

        List<OverlayPlacement> gptResult;
        try {
            gptResult = callGptForPlacements(descriptors, transcript, totalDurationMs);
        } catch (Exception e) {
            log.warn("[OverlayEngine] GPT placement failed — using keyword fallback: {}", e.getMessage());
            gptResult = List.of();
        }
        if (gptResult.isEmpty()) {
            log.info("[OverlayEngine] GPT returned no placements — using keyword fallback");
            return keywordFallbackPlacements(descriptors, context, totalDurationMs);
        }
        return gptResult;
    }

    /**
     * Builds a timestamped transcript from wordTimings (WhisperX) or a plain
     * narration string as fallback.
     */
    private String buildTranscript(GenerationContext context) {
        List<SubtitleService.WordTiming> wordTimings = context.getWordTimings();

        if (wordTimings != null && !wordTimings.isEmpty()) {
            StringBuilder sb = new StringBuilder();
            for (SubtitleService.WordTiming wt : wordTimings) {
                sb.append("[").append(wt.startMs()).append("ms] ")
                  .append(wt.word()).append(" ");
            }
            return sb.toString().trim();
        }

        // Fallback: plain narration text without timing
        if (context.getScript() != null && context.getScript().narration() != null) {
            return context.getScript().narration();
        }

        return "";
    }

    private int estimateTotalDuration(GenerationContext context) {
        if (context.getScenes() != null && !context.getScenes().isEmpty()) {
            return context.getScenes().stream()
                    .mapToInt(s -> s.getDurationMs())
                    .sum();
        }
        // Rough fallback: 30 seconds
        return 30_000;
    }

    private List<OverlayPlacement> callGptForPlacements(List<OverlayDescriptor> descriptors,
                                                         String transcript,
                                                         int totalDurationMs) throws Exception {
        StringBuilder overlayDesc = new StringBuilder();
        for (int i = 0; i < descriptors.size(); i++) {
            OverlayDescriptor d = descriptors.get(i);
            overlayDesc.append(i).append(". category=").append(d.getCategory())
                       .append(", label=\"").append(d.getSemanticLabel()).append("\"")
                       .append(", trigger_keywords=").append(d.getTriggerKeywords())
                       .append(", position_hint=").append(d.getSuggestedPosition())
                       .append(", size_hint=").append(d.getSuggestedSize())
                       .append("\n");
        }

        String prompt = """
                You are a professional TikTok video editor. Decide WHEN and WHERE to show each overlay image.

                VIDEO DURATION: %dms

                OVERLAY IMAGES:
                %s

                NARRATION TRANSCRIPT (with word timings in ms):
                %s

                RULES:
                1. Match overlays to narration moments using trigger_keywords. If the narrator says a keyword, \
                show the overlay for the duration of that sentence/phrase.
                2. Social logos (Discord, Twitter etc.) → show when the platform is mentioned, bottom-right corner.
                3. Screenshots / to-do lists → show when the related topic starts, center or right side, 3-6 seconds.
                4. CTA graphics → always in the last 20%% of the video, bottom center, large.
                5. If no matching keywords are found in transcript, place the overlay at the most relevant moment \
                or at 80%% of video duration.
                6. Positions (x,y) are the TOP-LEFT corner of the overlay, normalized 0.0–1.0 of frame dimensions.
                7. TikTok frame is 9:16 — keep overlays away from subtitle zone (y > 0.85).

                Return ONLY a JSON array (no markdown):
                [
                  {
                    "overlay_index": 0,
                    "start_ms": 4200,
                    "end_ms": 7800,
                    "x": 0.76,
                    "y": 0.80,
                    "width": 0.20,
                    "height": 0.113,
                    "opacity": 1.0,
                    "animation_in": "fade_in",
                    "reasoning": "narrator says 'join our Discord' at 4.2s"
                  }
                ]
                """.formatted(totalDurationMs, overlayDesc, transcript);

        String raw = openAiService.generateDirectorPlan(prompt);
        JsonNode root = objectMapper.readTree(raw);

        // Unwrap OpenAI chat completion envelope
        JsonNode content = root.path("choices").get(0).path("message").path("content");
        JsonNode parsed = objectMapper.readTree(content.asText());

        // generateDirectorPlan uses response_format:json_object, so GPT may wrap the array
        // in an object like {"placements":[...]}. Find the first array node.
        JsonNode placements = parsed;
        if (!parsed.isArray()) {
            placements = null;
            for (JsonNode child : parsed) {
                if (child.isArray()) {
                    placements = child;
                    break;
                }
            }
            if (placements == null) {
                log.warn("[OverlayEngine] GPT returned JSON object with no array inside: {}", content.asText());
                return List.of();
            }
        }

        List<OverlayPlacement> result = new ArrayList<>();
        for (JsonNode p : placements) {
            int idx = p.path("overlay_index").asInt(-1);
            if (idx < 0 || idx >= descriptors.size()) continue;

            OverlayDescriptor desc = descriptors.get(idx);
            float[] pos = POSITION_PRESETS.getOrDefault(desc.getCategory(),
                    POSITION_PRESETS.get("decoration"));

            result.add(OverlayPlacement.builder()
                    .overlayAssetId(desc.getAssetId())
                    .overlayAssetUrl(desc.getAssetUrl())
                    .startMs(p.path("start_ms").asInt())
                    .endMs(p.path("end_ms").asInt())
                    .x((float) p.path("x").asDouble(pos[0]))
                    .y((float) p.path("y").asDouble(pos[1]))
                    .width((float) p.path("width").asDouble(pos[2]))
                    .height((float) p.path("height").asDouble(pos[3]))
                    .opacity((float) p.path("opacity").asDouble(1.0))
                    .animationIn(p.path("animation_in").asText(null))
                    .reasoning(p.path("reasoning").asText(""))
                    .build());
        }

        return result;
    }

    /**
     * Keyword-based fallback when GPT placement fails.
     * Scans word timings for trigger keywords, places overlay around that window.
     */
    private List<OverlayPlacement> keywordFallbackPlacements(List<OverlayDescriptor> descriptors,
                                                              GenerationContext context,
                                                              int totalDurationMs) {
        List<SubtitleService.WordTiming> wordTimings = context.getWordTimings();
        List<OverlayPlacement> result = new ArrayList<>();

        for (OverlayDescriptor desc : descriptors) {
            int startMs = -1;
            int endMs = -1;

            if (wordTimings != null && !desc.getTriggerKeywords().isEmpty()) {
                for (int wi = 0; wi < wordTimings.size(); wi++) {
                    SubtitleService.WordTiming wt = wordTimings.get(wi);
                    String lower = wt.word().toLowerCase().replaceAll("[^a-z0-9]", "");
                    boolean matches = desc.getTriggerKeywords().stream()
                            .anyMatch(kw -> lower.contains(kw.toLowerCase()));
                    if (matches) {
                        startMs = Math.max(0, wt.startMs() - 200);
                        // Find the end of the current sentence (look ahead up to 10 words or 5s)
                        int sentenceEnd = wt.endMs();
                        for (int look = wi + 1; look < wordTimings.size() && look < wi + 10; look++) {
                            SubtitleService.WordTiming next = wordTimings.get(look);
                            if (next.startMs() - sentenceEnd > 500) break; // gap = new sentence
                            sentenceEnd = next.endMs();
                        }
                        endMs = Math.min(totalDurationMs, sentenceEnd + 300);
                        break;
                    }
                }
            }

            // No keyword match → put at 80% of video duration
            if (startMs < 0) {
                startMs = (int) (totalDurationMs * 0.80);
                endMs = Math.min(totalDurationMs, startMs + 3000);
            }

            float[] pos = POSITION_PRESETS.getOrDefault(desc.getCategory(),
                    POSITION_PRESETS.get("decoration"));

            result.add(OverlayPlacement.builder()
                    .overlayAssetId(desc.getAssetId())
                    .overlayAssetUrl(desc.getAssetUrl())
                    .startMs(startMs)
                    .endMs(endMs)
                    .x(pos[0]).y(pos[1]).width(pos[2]).height(pos[3])
                    .opacity(1.0f)
                    .animationIn(DEFAULT_ANIMATION.getOrDefault(desc.getCategory(), "fade_in"))
                    .reasoning("keyword fallback: " + desc.getTriggerKeywords())
                    .build());
        }

        return result;
    }

    /**
     * Last-resort placement when there is no transcript at all.
     * Spreads overlays evenly in the last 30% of the video.
     */
    private List<OverlayPlacement> defaultPlacements(List<OverlayDescriptor> descriptors,
                                                      int totalDurationMs) {
        List<OverlayPlacement> result = new ArrayList<>();
        int windowStart = (int) (totalDurationMs * 0.70);

        for (int i = 0; i < descriptors.size(); i++) {
            OverlayDescriptor desc = descriptors.get(i);
            int offset = i * 3000;
            int startMs = Math.min(windowStart + offset, totalDurationMs - 2000);
            int endMs = Math.min(startMs + 3000, totalDurationMs);

            float[] pos = POSITION_PRESETS.getOrDefault(desc.getCategory(),
                    POSITION_PRESETS.get("decoration"));

            result.add(OverlayPlacement.builder()
                    .overlayAssetId(desc.getAssetId())
                    .overlayAssetUrl(desc.getAssetUrl())
                    .startMs(startMs)
                    .endMs(endMs)
                    .x(pos[0]).y(pos[1]).width(pos[2]).height(pos[3])
                    .opacity(1.0f)
                    .animationIn(DEFAULT_ANIMATION.getOrDefault(desc.getCategory(), "fade_in"))
                    .reasoning("default placement — no transcript available")
                    .build());
        }

        return result;
    }
}
