package com.BossAi.bossAi.service;

import com.BossAi.bossAi.config.properties.OpenAiProperties;
import com.BossAi.bossAi.service.generation.context.ScriptResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.resilience4j.retry.annotation.Retry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.List;
import java.util.Map;

/**
 * OpenAiService v2 — przebudowany prompt engine.
 *
 * FAZA 2 zmiany:
 *
 *   1. SYSTEM PROMPT — kompletna przebudowa.
 *      Poprzedni prompt generował "poprawne" skrypty ale nie wiralowe.
 *      Nowy wymusza:
 *        - contentType detection (AD / EDUCATIONAL / STORY / VIRAL)
 *        - Strukturę emocjonalną per contentType (hook/pain/solution/CTA dla AD,
 *          hook/list-items/summary/cta dla EDUCATIONAL, itd.)
 *        - mediaAssignments — GPT-4o decyduje które sceny są VIDEO (max 2)
 *        - TextOverlay z timingiem zsynchronizowanym z TTS (~150 wpm)
 *        - Dłuższe filmy: 30-90s dla EDUCATIONAL, 15-30s dla AD
 *
 *   2. Osobny system prompt per contentType — buildSystemPrompt(contentType)
 *      Każdy typ ma inne reguły struktury, inny overlay style, inny pacing.
 *
 *   3. TTS timing — GPT-4o oblicza startMs/endMs dla każdego overlay
 *      na podstawie tempa narracji (instrukcja: ~150 słów/min, ~400ms/słowo).
 *      Nie jest to perfekcyjne — Faza 3 doda WhisperTimestamp alignment.
 */
@Slf4j
@Service
public class OpenAiService {

    private final WebClient webClient;
    private final OpenAiProperties properties;
    private final ObjectMapper objectMapper;

    public OpenAiService(
            @Qualifier("openAiWebClient") WebClient webClient,
            OpenAiProperties properties,
            ObjectMapper objectMapper
    ) {
        this.webClient    = webClient;
        this.properties   = properties;
        this.objectMapper = objectMapper;
    }

    // =========================================================================
    // SYSTEM PROMPTS PER CONTENT TYPE
    // =========================================================================

    /**
     * Base system prompt — wspólne reguły dla wszystkich typów.
     * Zawiera pełny JSON schema z nowymi polami Fazy 2.
     */
    private static final String BASE_SYSTEM_PROMPT = """
        You are an expert short-form video content creator for TikTok, Instagram Reels, and YouTube Shorts.
        You create scroll-stopping, high-retention videos that feel native to the platform.

        PLATFORM RULES:
        - 9:16 vertical format ALWAYS
        - First 2 seconds must stop the scroll — hook is everything
        - Pacing: fast cuts feel energetic, slower cuts feel authoritative
        - Image prompts: always English, always include "9:16 vertical format, photorealistic"

        MEDIA ASSIGNMENT RULES (CRITICAL FOR COST):
        - MAX 2 scenes can be VIDEO type — always scene[0] (hook) and the last scene (CTA/outro)
        - ALL other scenes MUST be IMAGE type
        - VIDEO scenes = animated clips (expensive, use for hook + CTA only)
        - IMAGE scenes = static photo displayed for durationMs (cheap, use for content)
        - Exception: if totalDurationMs < 20000, only 1 VIDEO scene (index 0)

        OVERLAY TIMING RULES:
        - Calculate startMs/endMs based on narration pacing: ~150 words/min = ~400ms per word
        - HOOK overlay: appears at 0ms, stays until first scene transition
        - Body overlays: appear 300ms after scene starts, fade 500ms before scene ends
        - CTA overlay: last 3 seconds of video
        - Never overlap two overlays at the same position simultaneously

        OVERLAY STYLES:
        - HOOK: fontSize 52, bold true, position CENTER, animation POP
        - BODY: fontSize 36, bold true, position TOP, animation SLIDE_IN
        - FACT: fontSize 26, bold false, position BOTTOM, animation FADE
        - CTA:  fontSize 44, bold true, position CENTER, animation POP
        - LIST_ITEM: fontSize 38, bold true, position CENTER, animation SLIDE_IN

        ALWAYS respond ONLY with valid JSON matching the exact schema below.
        No markdown, no comments, no preamble, no explanation outside JSON.

        JSON SCHEMA:
        {
          "narration": "full narration text for TTS (user's language or English)",
          "scenes": [
            {
              "index": 0,
              "imagePrompt": "detailed English prompt, 9:16 vertical format, photorealistic",
              "motionPrompt": "camera movement description for video scenes",
              "durationMs": 4000,
              "subtitleText": "narration fragment for this scene"
            }
          ],
          "overlays": [
            {
              "text": "text to display",
              "startMs": 0,
              "endMs": 2500,
              "position": "CENTER",
              "style": "HOOK",
              "animation": "POP",
              "fontSize": 52,
              "bold": true
            }
          ],
          "mediaAssignments": [
            { "sceneIndex": 0, "mediaType": "VIDEO" },
            { "sceneIndex": 1, "mediaType": "IMAGE" }
          ],
          "contentType": "EDUCATIONAL",
          "style": "energetic",
          "targetAudience": "description",
          "hook": "opening hook line",
          "callToAction": "CTA text",
          "totalDurationMs": 45000
        }
        """;

    private static final String AD_STRUCTURE = """

        CONTENT TYPE: ADVERTISEMENT
        MANDATORY STRUCTURE:
          Scene 0 [VIDEO, 3-4s]: PATTERN INTERRUPT — shocking/unexpected/controversial hook.
            Image: product hero shot or problem visualization.
            Overlay HOOK: bold claim or question. Animation: POP.
          Scene 1 [IMAGE, 4-6s]: AMPLIFY THE PAIN — make them feel the problem deeply.
            Image: person experiencing the problem.
            Overlay BODY: pain point statement. Animation: SLIDE_IN.
          Scene 2 [IMAGE, 4-6s]: THE REVEAL — product as the obvious solution.
            Image: product in use, transformation.
            Overlay BODY: benefit statement. Animation: FADE.
          Scene 3 [VIDEO, 3-4s]: SOCIAL PROOF + CTA — urgency, scarcity, action.
            Image: happy customer or product result.
            Overlay CTA: "Shop now — link in bio". Animation: POP.

        HOOK PATTERNS (pick one, make it feel native):
        - "Did you know [shocking stat]?"
        - "Stop [common mistake] immediately"
        - "[number] people are doing this wrong"
        - "This [product] changed everything"
        - "Tired of [pain]?"
        Total duration: 15-30 seconds.
        """;

    private static final String EDUCATIONAL_STRUCTURE = """

        CONTENT TYPE: EDUCATIONAL / LIST VIDEO
        MANDATORY STRUCTURE:
          Scene 0 [VIDEO, 3-4s]: HOOK — bold title of the list/topic.
            Overlay HOOK: "Top [N] [topic]" or "[topic] you need to know". Animation: POP.
          Scenes 1..N-1 [IMAGE, 4-8s each]: LIST ITEMS — one item per scene.
            Each scene: item name as Overlay LIST_ITEM (top), key fact as Overlay FACT (bottom).
            Image: visual representation of the item.
          Last scene [VIDEO, 4-5s]: SUMMARY + CTA — recap and follow for more.
            Overlay CTA: "Follow for more [topic]". Animation: POP.

        SCENE COUNT: exactly N+2 scenes where N = number of list items.
        For "Top 5" → 7 scenes (hook + 5 items + outro).
        For "3 tips" → 5 scenes (hook + 3 tips + outro).

        OVERLAY per list item scene:
          - LIST_ITEM overlay: "#[N] [item name]", position TOP, startMs = scene start + 300ms
          - FACT overlay: "[key fact or benefit]", position BOTTOM, startMs = scene start + 1000ms

        Total duration: 30-90 seconds (depends on list length, ~6s per item).
        """;

    private static final String STORY_STRUCTURE = """

        CONTENT TYPE: STORY / NARRATIVE
        MANDATORY STRUCTURE:
          Scene 0 [VIDEO, 3-4s]: HOOK — most dramatic/interesting moment (start in medias res).
          Scene 1 [IMAGE, 5-7s]: SETUP — context and character.
          Scenes 2..N-2 [IMAGE, 5-8s]: RISING ACTION — build tension, each scene escalates.
          Scene N-1 [VIDEO, 4-5s]: CLIMAX + RESOLUTION — payoff + lesson learned.
          Overlay style: minimal — only key emotional beats as FACT overlays.
        Total duration: 30-60 seconds.
        """;

    private static final String VIRAL_STRUCTURE = """

        CONTENT TYPE: VIRAL EDIT
        MANDATORY STRUCTURE:
          Scene 0 [VIDEO, 2-3s]: ULTRA-FAST HOOK — most visually striking moment.
            Overlay HOOK: 1-3 words max, massive font. Animation: POP.
          Scenes 1..N-2 [IMAGE, 2-4s each]: RAPID CONTENT — fast-paced facts or moments.
            Short FACT overlays, rhythm matches the beat.
          Last scene [VIDEO, 2-3s]: LOOP-FRIENDLY OUTRO — ends where it could restart.
        Total duration: 15-30 seconds. Fast pacing. Every scene under 4 seconds.
        """;

    // =========================================================================
    // CONTENT TYPE DETECTION
    // =========================================================================

    /**
     * Wykrywa typ contentu z promptu usera.
     * Używamy osobnego GPT call żeby wykryć intent przed generacją scenariusza.
     * Tani call — mały model, krótki prompt, szybko.
     */
    public String detectContentType(String userPrompt) {
        log.info("[OpenAiService] Wykrywam content type...");

        String detectionPrompt = """
            Classify this video content request into exactly ONE category.
            Respond with ONLY the category name, nothing else.
            
            Categories:
            - AD: product advertisement, promotion, selling something
            - EDUCATIONAL: tutorial, tips, list (top 5, how to, explained), facts
            - STORY: personal story, narrative, experience, transformation
            - VIRAL: entertainment, trend, challenge, meme, reaction
            
            Request: """ + userPrompt + """
            
            Category (one word only):
            """;

        Map<String, Object> requestBody = Map.of(
                "model", properties.getModel().getChat(),
                "temperature", 0.1,
                "max_tokens", 20,
                "messages", List.of(
                        Map.of("role", "user", "content", detectionPrompt)
                )
        );

        try {
            String raw = webClient.post()
                    .uri("/chat/completions")
                    .bodyValue(requestBody)
                    .retrieve()
                    .bodyToMono(String.class)
                    .block();

            JsonNode root = objectMapper.readTree(raw);
            String detected = root.path("choices").get(0)
                    .path("message").path("content").asText()
                    .trim().toUpperCase();

            // Sanitize — tylko znane wartości
            String type = switch (detected) {
                case "AD", "EDUCATIONAL", "STORY", "VIRAL" -> detected;
                default -> {
                    log.warn("[OpenAiService] Nieznany content type '{}', używam EDUCATIONAL", detected);
                    yield "EDUCATIONAL";
                }
            };

            log.info("[OpenAiService] Content type: {}", type);
            return type;

        } catch (Exception e) {
            log.warn("[OpenAiService] Content type detection failed: {}", e.getMessage());
            return "EDUCATIONAL"; // safe default
        }
    }

    // =========================================================================
    // SCRIPT GENERATION
    // =========================================================================

    /**
     * Generuje scenariusz TikTok — teraz z content type aware prompts.
     * Wykrywa typ contentu, dobiera strukturę, generuje z pełnym JSON schema v2.
     */
    @Retry(name = "openAi")
    public ScriptResult generateScript(String userPrompt) {
        String contentType = detectContentType(userPrompt);
        return generateScriptForContentType(userPrompt, contentType);
    }

    /**
     * Generuje scenariusz dla konkretnego content type.
     * Używany też przez ScriptStep gdy contentType jest już znany z VideoStyle.
     */
    public ScriptResult generateScriptForContentType(String userPrompt, String contentType) {
        log.info("[OpenAiService] generateScript — model: {}, contentType: {}",
                properties.getModel().getChat(), contentType);

        String systemPrompt = buildSystemPrompt(contentType);

        Map<String, Object> requestBody = Map.of(
                "model", properties.getModel().getChat(),
                "response_format", Map.of("type", "json_object"),
                "temperature", 0.8,
                "messages", List.of(
                        Map.of("role", "system", "content", systemPrompt),
                        Map.of("role", "user", "content", userPrompt)
                )
        );

        String rawJson = webClient.post()
                .uri("/chat/completions")
                .bodyValue(requestBody)
                .retrieve()
                .bodyToMono(String.class)
                .block();

        return parseScriptResult(rawJson);
    }

    /**
     * Buduje system prompt łącząc base + strukturę dla danego contentType.
     */
    private String buildSystemPrompt(String contentType) {
        String structure = switch (contentType) {
            case "AD"          -> AD_STRUCTURE;
            case "EDUCATIONAL" -> EDUCATIONAL_STRUCTURE;
            case "STORY"       -> STORY_STRUCTURE;
            case "VIRAL"       -> VIRAL_STRUCTURE;
            default            -> EDUCATIONAL_STRUCTURE;
        };

        return BASE_SYSTEM_PROMPT + structure;
    }

    // =========================================================================
    // DIRECTOR PLAN
    // =========================================================================

    public String generateDirectorPlan(String prompt) {
        Map<String, Object> requestBody = Map.of(
                "model", properties.getModel().getChat(),
                "response_format", Map.of("type", "json_object"),
                "temperature", 0.6,
                "messages", List.of(
                        Map.of("role", "system",
                                "content", "You are an elite video editor. Return ONLY JSON."),
                        Map.of("role", "user", "content", prompt)
                )
        );

        return webClient.post()
                .uri("/chat/completions")
                .bodyValue(requestBody)
                .retrieve()
                .bodyToMono(String.class)
                .block();
    }

    // =========================================================================
    // TTS
    // =========================================================================

    @Retry(name = "openAi")
    public byte[] generateTts(String narration) {
        log.info("[OpenAiService] generateTts — voice: {}, chars: {}",
                properties.getModel().getTtsConfig().getVoice(), narration.length());

        Map<String, Object> requestBody = Map.of(
                "model", properties.getModel().getTts(),
                "input", narration,
                "voice", properties.getModel().getTtsConfig().getVoice(),
                "response_format", "mp3",
                "speed", 1.0
        );

        byte[] audioBytes = webClient.post()
                .uri("/audio/speech")
                .bodyValue(requestBody)
                .accept(MediaType.APPLICATION_OCTET_STREAM)
                .retrieve()
                .bodyToMono(byte[].class)
                .block();

        if (audioBytes == null || audioBytes.length == 0) {
            throw new RuntimeException("[OpenAiService] TTS zwrócił pusty plik");
        }

        log.info("[OpenAiService] TTS gotowy — {} bytes", audioBytes.length);
        return audioBytes;
    }

    // =========================================================================
    // PARSOWANIE
    // =========================================================================

    private ScriptResult parseScriptResult(String rawResponse) {
        try {
            JsonNode root = objectMapper.readTree(rawResponse);

            String jsonContent = root
                    .path("choices").get(0)
                    .path("message").path("content")
                    .asText();

            log.debug("[OpenAiService] ScriptResult JSON: {}",
                    jsonContent.length() > 500 ? jsonContent.substring(0, 500) + "..." : jsonContent);

            ScriptResult result = objectMapper.readValue(jsonContent, ScriptResult.class);

            validateScriptResult(result);

            log.info("[OpenAiService] ScriptResult OK — {} scen, {}ms, {} overlays, contentType: {}",
                    result.scenes().size(),
                    result.totalDurationMs(),
                    result.overlays() != null ? result.overlays().size() : 0,
                    result.contentType());

            return result;

        } catch (Exception e) {
            throw new RuntimeException("[OpenAiService] Błąd parsowania ScriptResult: " + e.getMessage(), e);
        }
    }

    private void validateScriptResult(ScriptResult result) {
        if (result.narration() == null || result.narration().isBlank()) {
            throw new RuntimeException("ScriptResult: brak narracji");
        }
        if (result.scenes() == null || result.scenes().isEmpty()) {
            throw new RuntimeException("ScriptResult: brak scen");
        }
        if (result.scenes().size() > 12) {
            throw new RuntimeException("ScriptResult: za dużo scen ("
                    + result.scenes().size() + ") — max 12");
        }
        for (ScriptResult.SceneScript scene : result.scenes()) {
            if (scene.imagePrompt() == null || scene.imagePrompt().isBlank()) {
                throw new RuntimeException("ScriptResult: scena " + scene.index() + " bez imagePrompt");
            }
            if (scene.durationMs() < 1000) {
                throw new RuntimeException("ScriptResult: scena " + scene.index()
                        + " ma durationMs=" + scene.durationMs() + " — minimum 1000ms");
            }
        }

        // Walidacja media assignments — max 2 VIDEO
        if (result.mediaAssignments() != null) {
            long videoCount = result.mediaAssignments().stream()
                    .filter(ScriptResult.MediaAssignment::isVideo)
                    .count();
            if (videoCount > 3) {
                log.warn("[OpenAiService] Zbyt dużo scen VIDEO ({}) — RenderStep ograniczy do 2", videoCount);
            }
        }
    }
}