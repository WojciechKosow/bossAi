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

    private static final String BASE_SYSTEM_PROMPT = """
        You are an expert short-form video content creator for TikTok, Instagram Reels, and YouTube Shorts.
        You create scroll-stopping, high-retention videos that feel native to the platform.

        PLATFORM RULES:
        - 9:16 vertical format ALWAYS
        - First 2 seconds must stop the scroll \u2014 hook is everything
        - Pacing: fast cuts feel energetic, slower cuts feel authoritative
        - Image prompts: always English, always include "9:16 vertical format, photorealistic"

        MEDIA ASSIGNMENT RULES (CRITICAL FOR COST):
        - MAX 2 scenes can be VIDEO type \u2014 always scene[0] (hook) and the last scene (CTA/outro)
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
        - LIST_ITEM: fontSize 38, bold true, position TOP, animation SLIDE_IN
        - NUMBER: fontSize 72, bold true, position CENTER, animation POP

        SUBTITLES (subtitleText per scene):
        - subtitleText is the EXACT narration fragment spoken during this scene
        - It will be displayed word-by-word on screen synchronized with TTS
        - Keep sentences short: max 10-12 words per scene
        - Use conversational, spoken language \u2014 NOT written/formal
        - Each word will appear individually on screen as the narrator speaks

        MUSIC DIRECTIONS (musicDirections array):
        - Provide ONE musicDirection per scene
        - volume: 0.0-1.0 \u2014 how loud background music should be during this scene
        - fadeInMs: milliseconds to fade music in at scene start (0 = instant)
        - fadeOutMs: milliseconds to fade music out at scene end (0 = instant)
        - RULES:
          - When narrator speaks intensely -> volume 0.10-0.15
          - When narrator speaks calmly -> volume 0.15-0.25
          - During pauses/transitions -> volume 0.35-0.50
          - Hook scene (first) -> volume 0.40-0.50, fadeOutMs 300-500
          - CTA scene (last) -> volume 0.35-0.45, fadeInMs 300
          - NEVER set volume above 0.60 \u2014 music must never overpower voice

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
          "musicDirections": [
            { "sceneIndex": 0, "volume": 0.45, "fadeInMs": 0, "fadeOutMs": 300 },
            { "sceneIndex": 1, "volume": 0.12, "fadeInMs": 200, "fadeOutMs": 0 }
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
          Scene 0 [VIDEO, 5s]: PATTERN INTERRUPT \u2014 shocking/unexpected/controversial hook.
            Image: product hero shot or problem visualization.
            Overlay HOOK: bold claim or question. Animation: POP.
          Scene 1 [IMAGE, 5-7s]: AMPLIFY THE PAIN \u2014 make them feel the problem deeply.
            Image: person experiencing the problem.
            Overlay BODY: pain point statement. Animation: SLIDE_IN.
          Scene 2 [IMAGE, 5-7s]: THE REVEAL \u2014 product as the obvious solution.
            Image: product in use, transformation.
            Overlay BODY: benefit statement. Animation: FADE.
          Scene 3 [VIDEO, 5s]: SOCIAL PROOF + CTA \u2014 urgency, scarcity, action.
            Image: happy customer or product result.
            Overlay CTA: "Shop now \u2014 link in bio". Animation: POP.

        HOOK PATTERNS (pick one, make it feel native):
        - "Did you know [shocking stat]?"
        - "Stop [common mistake] immediately"
        - "[number] people are doing this wrong"
        - "This [product] changed everything"
        - "Tired of [pain]?"
        Total duration: 20-35 seconds.
        VIDEO scenes MUST have durationMs=5000 (Kling API minimum is 5 seconds).
        """;

    private static final String EDUCATIONAL_STRUCTURE = """

        CONTENT TYPE: EDUCATIONAL / LIST VIDEO
        This is the classic TikTok educational format \u2014 "Top 5", "3 things you didn't know",
        "Did you know?", explainers, tips, facts. These videos DOMINATE TikTok engagement.

        THE FEEL:
        - Conversational, like a smart friend explaining something cool
        - NOT a lecture, NOT a presentation \u2014 it's a CONVERSATION
        - Energy: confident, slightly fast-paced, enthusiastic but not fake
        - Narration tone: "Okay so listen..." / "Here's the thing..." / "Most people don't know this but..."

        MANDATORY STRUCTURE:
          Scene 0 [VIDEO, 5s, durationMs=5000]: HOOK \u2014 the scroll-stopper.
            - Must create CURIOSITY GAP \u2014 viewer NEEDS to keep watching
            - Narration: 1 punchy sentence, max 8 words spoken
            - Overlay HOOK: bold topic title (e.g. "5 AI TOOLS YOU NEED"), CENTER, POP
            - Image: visually striking, related to topic, bold colors
            - Music: volume 0.40-0.50 (louder, energy), fadeOutMs 400

          Scenes 1..N-1 [IMAGE, 6-8s each, durationMs=6000-8000]: LIST ITEMS \u2014 one item per scene.
            - Each scene = ONE point/item/fact
            - Narration: explain the item conversationally (2-3 short sentences)
            - subtitleText: EXACT words narrator says (will appear word-by-word)
            - Overlay NUMBER: just the number "#1", "#2" etc. \u2014 fontSize 72, CENTER, POP
              - appears at scene start, stays 1.5s then disappears
            - Overlay LIST_ITEM: item name \u2014 fontSize 38, TOP, SLIDE_IN
              - appears 300ms after scene start, stays until scene end - 500ms
            - Overlay FACT: key fact/stat \u2014 fontSize 26, BOTTOM, FADE
              - appears 1500ms after scene start, stays until scene end - 500ms
            - Image: clear visual of the item (product screenshot, concept illustration, etc.)
            - Music: volume 0.10-0.15 (quiet, narrator is king), fadeInMs 200

          Last scene [VIDEO, 5s, durationMs=5000]: SUMMARY + CTA
            - Narration: quick recap + call to action ("Follow for more" / "Save this for later")
            - Overlay CTA: action text, CENTER, POP, fontSize 44, gold color
            - Image: energetic, positive, forward-looking
            - Music: volume 0.35-0.45 (rises back up), fadeInMs 300

        HOOK PATTERNS (pick one, adapt to topic):
        - "5 [things] that will change [outcome]"
        - "[Number] [topic] most people don't know about"
        - "Stop scrolling if you [relate to audience]"
        - "I tested [N] [things] \u2014 here's what actually works"
        - "Nobody talks about these [topic]"
        - "Here's what [experts/pros] use instead"

        SCENE COUNT: exactly N+2 scenes (hook + N items + outro).
        - "Top 5" -> 7 scenes, "3 tips" -> 5 scenes, "7 facts" -> 9 scenes
        - If user doesn't specify N, default to 5 items (7 scenes total)

        NARRATION RULES:
        - Language: SAME language as user's prompt (if Polish prompt -> Polish narration)
        - Conversational flow: hook -> "Alright, number one..." -> explain -> "Number two..." -> ... -> CTA
        - Each item intro uses casual transition: "Okay number two", "Next up", "And finally"
        - NO filler words, NO "um", NO "so basically" \u2014 every word earns its place
        - subtitleText per scene = EXACT narration for that scene (for word-by-word display)

        Total duration: 40-75 seconds (6-8s per item + 5s hook + 5s outro).

        CRITICAL RULES:
        - VIDEO scenes (scene 0 and last scene) MUST have durationMs=5000 (API minimum is 5 seconds)
        - You MUST generate exactly N+2 scenes. "Top 5" = 7 scenes, "3 tips" = 5 scenes, etc.
        - If user doesn't specify N, default to 5 items = 7 scenes total
        - NEVER generate fewer than 5 scenes for educational content
        - mediaAssignments MUST have scene 0 as VIDEO, last scene as VIDEO, all others as IMAGE
        """;

    private static final String STORY_STRUCTURE = """

        CONTENT TYPE: STORY / NARRATIVE
        MANDATORY STRUCTURE:
          Scene 0 [VIDEO, 5s, durationMs=5000]: HOOK \u2014 most dramatic/interesting moment (start in medias res).
          Scene 1 [IMAGE, 5-7s]: SETUP \u2014 context and character.
          Scenes 2..N-2 [IMAGE, 5-8s]: RISING ACTION \u2014 build tension, each scene escalates.
          Scene N-1 [VIDEO, 5s, durationMs=5000]: CLIMAX + RESOLUTION \u2014 payoff + lesson learned.
          Overlay style: minimal \u2014 only key emotional beats as FACT overlays.
        Total duration: 30-60 seconds.
        VIDEO scenes MUST have durationMs=5000 (API minimum is 5 seconds).
        """;

    private static final String VIRAL_STRUCTURE = """

        CONTENT TYPE: VIRAL EDIT
        MANDATORY STRUCTURE:
          Scene 0 [VIDEO, 5s, durationMs=5000]: ULTRA-FAST HOOK \u2014 most visually striking moment.
            Overlay HOOK: 1-3 words max, massive font. Animation: POP.
          Scenes 1..N-2 [IMAGE, 2-4s each]: RAPID CONTENT \u2014 fast-paced facts or moments.
            Short FACT overlays, rhythm matches the beat.
          Last scene [VIDEO, 5s, durationMs=5000]: LOOP-FRIENDLY OUTRO \u2014 ends where it could restart.
        Total duration: 15-30 seconds. Fast pacing.
        VIDEO scenes MUST have durationMs=5000 (API minimum is 5 seconds).
        """;

    // =========================================================================
    // CONTENT TYPE DETECTION
    // =========================================================================

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

            String type = switch (detected) {
                case "AD", "EDUCATIONAL", "STORY", "VIRAL" -> detected;
                default -> {
                    log.warn("[OpenAiService] Nieznany content type '{}', uzywam EDUCATIONAL", detected);
                    yield "EDUCATIONAL";
                }
            };

            log.info("[OpenAiService] Content type: {}", type);
            return type;

        } catch (Exception e) {
            log.warn("[OpenAiService] Content type detection failed: {}", e.getMessage());
            return "EDUCATIONAL";
        }
    }

    // =========================================================================
    // SCRIPT GENERATION
    // =========================================================================

    @Retry(name = "openAi")
    public ScriptResult generateScript(String userPrompt) {
        String contentType = detectContentType(userPrompt);
        return generateScriptForContentType(userPrompt, contentType);
    }

    public ScriptResult generateScriptForContentType(String userPrompt, String contentType) {
        log.info("[OpenAiService] generateScript \u2014 model: {}, contentType: {}",
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
        log.info("[OpenAiService] generateTts \u2014 voice: {}, chars: {}",
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
            throw new RuntimeException("[OpenAiService] TTS zwrocil pusty plik");
        }

        log.info("[OpenAiService] TTS gotowy \u2014 {} bytes", audioBytes.length);
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

            log.info("[OpenAiService] ScriptResult OK \u2014 {} scen, {}ms, {} overlays, contentType: {}",
                    result.scenes().size(),
                    result.totalDurationMs(),
                    result.overlays() != null ? result.overlays().size() : 0,
                    result.contentType());

            return result;

        } catch (Exception e) {
            throw new RuntimeException("[OpenAiService] Blad parsowania ScriptResult: " + e.getMessage(), e);
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
            throw new RuntimeException("ScriptResult: za duzo scen ("
                    + result.scenes().size() + ") \u2014 max 12");
        }
        for (ScriptResult.SceneScript scene : result.scenes()) {
            if (scene.imagePrompt() == null || scene.imagePrompt().isBlank()) {
                throw new RuntimeException("ScriptResult: scena " + scene.index() + " bez imagePrompt");
            }
            if (scene.durationMs() < 2000) {
                throw new RuntimeException("ScriptResult: scena " + scene.index()
                        + " ma durationMs=" + scene.durationMs() + " \u2014 minimum 2000ms");
            }
        }

        // Enforce minimum durationMs for VIDEO scenes (Kling API minimum = 5s)
        if (result.mediaAssignments() != null) {
            for (ScriptResult.MediaAssignment ma : result.mediaAssignments()) {
                if (ma.isVideo()) {
                    ScriptResult.SceneScript scene = result.scenes().stream()
                            .filter(s -> s.index() == ma.sceneIndex())
                            .findFirst().orElse(null);
                    if (scene != null && scene.durationMs() < 5000) {
                        log.warn("[OpenAiService] VIDEO scena {} ma durationMs={} < 5000 \u2014 Kling API wymaga minimum 5s",
                                scene.index(), scene.durationMs());
                    }
                }
            }
        }

        if (result.mediaAssignments() != null) {
            long videoCount = result.mediaAssignments().stream()
                    .filter(ScriptResult.MediaAssignment::isVideo)
                    .count();
            if (videoCount > 3) {
                log.warn("[OpenAiService] Zbyt duzo scen VIDEO ({}) \u2014 RenderStep ograniczy do 2", videoCount);
            }
        }
    }
}
