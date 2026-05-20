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

    // TikTok 9:16 frame — safe overlay zone: y 0.05–0.75 (subtitle area starts ~0.78)
    // All positions: [x, y, width, height], normalized 0–1, x/y = top-left corner.
    private static final Map<String, float[]> POSITION_PRESETS = Map.of(
            // logo — centered, prominent; used when narrator mentions a brand/platform
            "logo",        new float[]{0.25f, 0.30f, 0.50f, 0.28f},
            // screenshot — nearly full-width, upper zone so subtitles don't overlap
            "screenshot",  new float[]{0.05f, 0.08f, 0.90f, 0.50f},
            // product — centered, slightly upper half
            "product",     new float[]{0.15f, 0.20f, 0.70f, 0.39f},
            // cta — bottom strip, full width, above subtitle zone
            "cta",         new float[]{0.05f, 0.65f, 0.90f, 0.12f},
            // decoration — small top-right accent
            "decoration",  new float[]{0.78f, 0.04f, 0.18f, 0.10f}
    );

    private static final Map<String, String> DEFAULT_ANIMATION = Map.of(
            "logo",       "zoom_in",
            "screenshot", "slide_up",
            "product",    "zoom_in",
            "cta",        "slide_up",
            "decoration", "fade_in"
    );

    /** Returns true when a word ends a clause or sentence (., , ! ? ;). */
    private static boolean endsWithClauseMarker(String word) {
        if (word == null || word.isEmpty()) return false;
        char last = word.charAt(word.length() - 1);
        return last == '.' || last == ',' || last == '!' || last == '?' || last == ';';
    }

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
        int ttsEndMs = estimateTtsEnd(context);
        int[][] sceneBoundaries = buildSceneBoundaries(context);

        if (transcript.isBlank()) {
            log.warn("[OverlayEngine] No transcript available — applying default end-of-video placement");
            return clampEndMs(defaultPlacements(descriptors, totalDurationMs), ttsEndMs);
        }

        List<OverlayPlacement> gptResult;
        try {
            gptResult = callGptForPlacements(descriptors, transcript, totalDurationMs, sceneBoundaries);
        } catch (Exception e) {
            log.warn("[OverlayEngine] GPT placement failed — using keyword fallback: {}", e.getMessage());
            gptResult = List.of();
        }
        if (gptResult.isEmpty()) {
            log.info("[OverlayEngine] GPT returned no placements — using keyword fallback");
            return clampEndMs(
                    enforceSceneBoundaries(
                            keywordFallbackPlacements(descriptors, context, totalDurationMs, sceneBoundaries),
                            sceneBoundaries, totalDurationMs),
                    ttsEndMs);
        }
        return clampEndMs(enforceSceneBoundaries(gptResult, sceneBoundaries, totalDurationMs), ttsEndMs);
    }

    /**
     * Clamps every placement's endMs to at most maxEndMs (TTS end cap).
     */
    private List<OverlayPlacement> clampEndMs(List<OverlayPlacement> placements, int maxEndMs) {
        for (OverlayPlacement p : placements) {
            if (p.getEndMs() > maxEndMs) {
                log.debug("[OverlayEngine] Clamping overlay endMs {} → {} (TTS end)", p.getEndMs(), maxEndMs);
                p.setEndMs(maxEndMs);
            }
        }
        return placements;
    }

    /**
     * Overrides each placement's endMs to the end of the scene that contains startMs.
     * Ensures overlays never bleed past a scene cut regardless of what GPT returned.
     */
    private List<OverlayPlacement> enforceSceneBoundaries(List<OverlayPlacement> placements,
                                                           int[][] sceneBoundaries,
                                                           int totalDurationMs) {
        for (OverlayPlacement p : placements) {
            int sceneEnd = sceneEndForTime(p.getStartMs(), sceneBoundaries, totalDurationMs);
            if (p.getEndMs() > sceneEnd) {
                log.debug("[OverlayEngine] Enforcing scene boundary: overlay endMs {} → {} (scene end)",
                        p.getEndMs(), sceneEnd);
                p.setEndMs(sceneEnd);
            }
        }
        return placements;
    }

    /**
     * Returns scene boundaries as int[][]{startMs, endMs} derived from context scenes.
     * Falls back to a single full-video "scene" when scenes aren't available.
     *
     * IMPORTANT: applies the same voice/scene scale factor that EdlGeneratorService uses
     * so that boundaries are in the same coordinate system as WhisperX word timings.
     * Without this, overlays calculated here would appear at wrong positions in the
     * rendered video (unscaled scene time ≠ actual TTS audio time).
     */
    private int[][] buildSceneBoundaries(GenerationContext context) {
        var scenes = context.getScenes();
        if (scenes == null || scenes.isEmpty()) {
            int total = estimateTotalDuration(context);
            return new int[][]{{0, total}};
        }

        // Mirror the scaling logic from EdlGeneratorService so boundaries match the
        // actual rendered timeline (word timings are in TTS audio time, not raw scene time).
        int sceneDurationMs = scenes.stream().mapToInt(s -> s.getDurationMs()).sum();
        int voiceDurationMs = 0;
        List<SubtitleService.WordTiming> wordTimings = context.getWordTimings();
        if (wordTimings != null && !wordTimings.isEmpty()) {
            voiceDurationMs = wordTimings.get(wordTimings.size() - 1).endMs();
        }
        double scale = (voiceDurationMs > sceneDurationMs && sceneDurationMs > 0)
                ? (double) voiceDurationMs / sceneDurationMs
                : 1.0;

        if (scale != 1.0) {
            log.debug("[OverlayEngine] Scaling scene boundaries by {} (voice={}ms, scenes={}ms)",
                    String.format("%.3f", scale), voiceDurationMs, sceneDurationMs);
        }

        int[][] boundaries = new int[scenes.size()][2];
        int cursor = 0;
        for (int i = 0; i < scenes.size(); i++) {
            boundaries[i][0] = cursor;
            cursor += (int)(scenes.get(i).getDurationMs() * scale);
            boundaries[i][1] = cursor;
        }
        // Snap last boundary to actual voice end — eliminates rounding drift.
        if (voiceDurationMs > 0) {
            boundaries[boundaries.length - 1][1] = voiceDurationMs;
        }
        return boundaries;
    }

    /** Returns the endMs of the scene that contains timeMs, or totalDurationMs if none found. */
    private int sceneEndForTime(int timeMs, int[][] sceneBoundaries, int totalDurationMs) {
        for (int[] b : sceneBoundaries) {
            if (timeMs >= b[0] && timeMs < b[1]) return b[1];
        }
        return totalDurationMs;
    }

    /** Returns the startMs of the scene that contains timeMs, or 0 if none found. */
    private int sceneStartForTime(int timeMs, int[][] sceneBoundaries) {
        for (int[] b : sceneBoundaries) {
            if (timeMs >= b[0] && timeMs < b[1]) return b[0];
        }
        return 0;
    }

    /** Returns the last word timing's endMs + 300ms — the furthest point TTS audio plays. */
    private int estimateTtsEnd(GenerationContext context) {
        List<SubtitleService.WordTiming> wordTimings = context.getWordTimings();
        if (wordTimings != null && !wordTimings.isEmpty()) {
            return wordTimings.get(wordTimings.size() - 1).endMs() + 300;
        }
        return estimateTotalDuration(context);
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
        // Prefer TTS word timings (actual audio end) over scene sum — scene sum can include
        // a music-only outro, which would incorrectly allow overlays to extend into silence.
        List<SubtitleService.WordTiming> wordTimings = context.getWordTimings();
        if (wordTimings != null && !wordTimings.isEmpty()) {
            return wordTimings.get(wordTimings.size() - 1).endMs() + 500;
        }
        if (context.getScenes() != null && !context.getScenes().isEmpty()) {
            return context.getScenes().stream()
                    .mapToInt(s -> s.getDurationMs())
                    .sum();
        }
        return 30_000;
    }

    private List<OverlayPlacement> callGptForPlacements(List<OverlayDescriptor> descriptors,
                                                         String transcript,
                                                         int totalDurationMs,
                                                         int[][] sceneBoundaries) throws Exception {
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

        StringBuilder sceneInfo = new StringBuilder();
        for (int i = 0; i < sceneBoundaries.length; i++) {
            sceneInfo.append("  Scene ").append(i).append(": ")
                     .append(sceneBoundaries[i][0]).append("ms – ")
                     .append(sceneBoundaries[i][1]).append("ms\n");
        }

        String prompt = """
                You are a professional TikTok video editor. Decide WHEN and WHERE to show each overlay image.

                VIDEO DURATION: %dms

                SCENE BOUNDARIES (the video clip changes exactly at these times):
                %s
                OVERLAY IMAGES:
                %s

                NARRATION TRANSCRIPT (with word timings in ms):
                %s

                POSITIONING RULES (TikTok 9:16 frame, safe zone y=0.05–0.75):
                - logo (Discord, YouTube, Twitter, brand icon):
                    CENTER of frame. x=0.25, y=0.30, width=0.50, height=0.28. Animation: zoom_in.
                - screenshot / stats / to-do list:
                    Upper area, nearly full width. x=0.05, y=0.08, width=0.90, height=0.50. Animation: slide_up.
                - product photo:
                    Center, large. x=0.15, y=0.20, width=0.70, height=0.39. Animation: zoom_in.
                - cta (call-to-action, QR code, "follow" graphic):
                    Bottom strip ABOVE subtitles. x=0.05, y=0.65, width=0.90, height=0.12. Animation: slide_up.
                    Always in the last 20%% of video.
                - decoration (sticker, abstract):
                    Small top-right corner. x=0.78, y=0.04, width=0.18, height=0.10. Animation: fade_in.

                TIMING RULES — CRITICAL:
                1. Match each overlay to narration using trigger_keywords.
                2. Find the first word in the transcript that matches a trigger_keyword.
                3. start_ms = that word's timestamp − 200ms (minimum 0).
                4. end_ms: find the END OF THE CLAUSE containing the keyword.
                   Scan forward word by word from the matched keyword and STOP at whichever comes first:
                     a) A word whose text ends with clause punctuation: period (.), comma (,), exclamation (!), question (?), semicolon (;)
                     b) A gap >500ms between two consecutive word timestamps (natural speech pause / sentence break)
                     c) 15 words scanned past the keyword
                   end_ms = last included word timestamp + 400ms
                   end_ms must NEVER exceed the containing scene boundary from SCENE BOUNDARIES.
                   IMPORTANT: the overlay must disappear when the narrator FINISHES THE SENTENCE about this topic, NOT at the end of the scene.
                5. If no keyword match: place at 80%% of video duration, end at the containing scene's end.
                6. Subtitles occupy y > 0.78 — never place overlays there.

                Return ONLY a JSON array (no markdown, no wrapper object):
                [
                  {
                    "overlay_index": 0,
                    "start_ms": 4200,
                    "end_ms": 5800,
                    "y": 0.30,
                    "width": 0.50,
                    "height": 0.28,
                    "opacity": 1.0,
                    "animation_in": "zoom_in",
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
     * Finds the keyword in word timings, then detects the end of the spoken
     * clause/sentence containing that keyword using punctuation and pause gaps.
     * Scene boundaries are a secondary safety cap only — primary end detection
     * is word-timing based to avoid depending on scene duration estimates.
     */
    private List<OverlayPlacement> keywordFallbackPlacements(List<OverlayDescriptor> descriptors,
                                                              GenerationContext context,
                                                              int totalDurationMs,
                                                              int[][] sceneBoundaries) {
        List<SubtitleService.WordTiming> wordTimings = context.getWordTimings();
        List<OverlayPlacement> result = new ArrayList<>();

        for (OverlayDescriptor desc : descriptors) {
            int startMs = -1;
            int endMs = -1;

            if (wordTimings != null && !desc.getTriggerKeywords().isEmpty()) {
                for (int wtIdx = 0; wtIdx < wordTimings.size(); wtIdx++) {
                    SubtitleService.WordTiming wt = wordTimings.get(wtIdx);
                    String lower = wt.word().toLowerCase().replaceAll("[^a-z0-9]", "");
                    boolean matches = desc.getTriggerKeywords().stream()
                            .anyMatch(kw -> lower.contains(kw.toLowerCase()));
                    if (!matches) continue;

                    startMs = Math.max(0, wt.startMs() - 200);

                    // Find the end of the spoken clause about this topic.
                    // Stop at: clause-ending punctuation (.,!?;), natural pause >500ms, or 15 words.
                    // Scene boundary is a cap of last resort only.
                    int sceneEnd = sceneEndForTime(wt.startMs(), sceneBoundaries, totalDurationMs);
                    int passageEndMs = wt.endMs();
                    if (!endsWithClauseMarker(wt.word())) {
                        int prevWordEnd = wt.endMs();
                        int wordsScanned = 0;
                        for (int fwd = wtIdx + 1; fwd < wordTimings.size() && wordsScanned < 15; fwd++) {
                            SubtitleService.WordTiming next = wordTimings.get(fwd);
                            if (next.startMs() >= sceneEnd) break;
                            if (next.startMs() - prevWordEnd > 500) break; // natural clause pause
                            passageEndMs = next.endMs();
                            prevWordEnd = next.endMs();
                            wordsScanned++;
                            if (endsWithClauseMarker(next.word())) break;
                        }
                    }
                    endMs = Math.min(passageEndMs + 400, sceneEnd);
                    break;
                }
            }

            // No keyword match → put at 80% of video duration, end at containing scene boundary
            if (startMs < 0) {
                startMs = (int) (totalDurationMs * 0.80);
                endMs = sceneEndForTime(startMs, sceneBoundaries, totalDurationMs);
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
