package com.BossAi.bossAi.service;

import com.BossAi.bossAi.config.properties.OpenAiProperties;
import com.BossAi.bossAi.service.generation.context.ScriptResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.resilience4j.retry.annotation.Retry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.List;
import java.util.Map;

/**
 * OpenAiService — klient do OpenAI REST API.
 * <p>
 * Dwie operacje:
 * 1. generateScript()  — GPT-4o chat completion → ScriptResult (JSON)
 * 2. generateTts()     — gpt-4o-mini-tts → byte[] MP3
 * <p>
 * Oba mają @Retry(name = "openAi") — Resilience4j ponawia przy 429/503.
 * Konfiguracja retry w application.properties (resilience4j.retry.instances.openAi.*).
 */
@Slf4j
@Service
public class OpenAiService {

    private final WebClient webClient;

    private final OpenAiProperties properties;
    private final ObjectMapper objectMapper;

    public OpenAiService(@Qualifier("openAiWebClient") WebClient webClient, OpenAiProperties properties, ObjectMapper objectMapper) {
        this.webClient = webClient;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    // =========================================================================
    // SYSTEM PROMPT — serce jakości reklam
    // =========================================================================

    private static final String TIKTOK_AD_SYSTEM_PROMPT = """
        You are an expert TikTok ad creator for e-commerce brands.
        You create high-energy, scroll-stopping video ad scripts in 9:16 format.
        
        RULES:
        - Hook in the first 2 seconds — make it impossible to scroll past
        - Raw, punchy, direct language — Gen Z / Millennial energy
        - Maximum 3-4 scenes (4-8 seconds each)
        - Strong CTA at the end ("Shop now", "Link in bio", "Check description")
        - Narration max 60 words (must fit TTS)
        - Image prompts: detailed English, always include "9:16 vertical format"
        
        ALWAYS respond ONLY with valid JSON, no markdown, no comments, no preamble.
        
        JSON schema:
        {
          "narration": "string — full narration for TTS (in English or user's language)",
          "scenes": [
            {
              "index": 0,
              "imagePrompt": "string — detailed English prompt for image AI, 9:16 vertical format",
              "motionPrompt": "string — description in English",
              "durationMs": 5000,
              "subtitleText": "string — this scene's narration fragment"
            }
          ],
          "style": "string — visual style (energetic/cinematic/ugc/luxury/minimal)",
          "targetAudience": "string — target audience",
          "hook": "string — opening hook line",
          "callToAction": "string — CTA",
          "totalDurationMs": 20000
        }
        """;

    // =========================================================================
    // CHAT COMPLETION → ScriptResult
    // =========================================================================

    /**
     * Generuje scenariusz TikTok Ad na podstawie promptu usera.
     *
     * @param userPrompt opis produktu/reklamy od usera
     * @return sparsowany ScriptResult gotowy do użycia w pipeline
     */
    @Retry(name = "openAi")
    public ScriptResult generateScript(String userPrompt) {
        log.info("[OpenAiService] generateScript — model: {}", properties.getModel().getChat());

        Map<String, Object> requestBody = Map.of(
                "model", properties.getModel().getChat(),
                "response_format", Map.of("type", "json_object"),
                "temperature", 0.8,
                "messages", List.of(
                        Map.of("role", "system", "content", TIKTOK_AD_SYSTEM_PROMPT),
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

    // =========================================================================
    // TTS → byte[] MP3
    // =========================================================================

    /**
     * Generuje syntezę mowy z tekstu narracji.
     *
     * @param narration tekst do syntezy (z ScriptResult.narration())
     * @return bajty pliku MP3
     */
    @Retry(name = "openAi")
    public byte[] generateTts(String narration) {
        log.info("[OpenAiService] generateTts — model: {}, voice: {}, chars: {}",
                properties.getModel().getTts(),
                properties.getModel().getTtsConfig().getVoice(),
                narration.length());

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
            throw new RuntimeException("[OpenAiService] TTS zwrócił pusty plik audio");
        }

        log.info("[OpenAiService] TTS gotowy — {} bytes", audioBytes.length);
        return audioBytes;
    }

    // =========================================================================
    // PARSOWANIE ODPOWIEDZI
    // =========================================================================

    private ScriptResult parseScriptResult(String rawResponse) {
        try {
            JsonNode root = objectMapper.readTree(rawResponse);

            // Wyciągamy content z chat completion response
            String jsonContent = root
                    .path("choices")
                    .get(0)
                    .path("message")
                    .path("content")
                    .asText();

            log.debug("[OpenAiService] ScriptResult JSON: {}", jsonContent);

            ScriptResult result = objectMapper.readValue(jsonContent, ScriptResult.class);

            validateScriptResult(result);

            log.info("[OpenAiService] ScriptResult sparsowany — {} scen, {}ms",
                    result.scenes().size(), result.totalDurationMs());

            return result;

        } catch (Exception e) {
            throw new RuntimeException(
                    "[OpenAiService] Błąd parsowania ScriptResult: " + e.getMessage(), e);
        }
    }

    private void validateScriptResult(ScriptResult result) {
        if (result.narration() == null || result.narration().isBlank()) {
            throw new RuntimeException("ScriptResult: brak narracji");
        }
        if (result.scenes() == null || result.scenes().isEmpty()) {
            throw new RuntimeException("ScriptResult: brak scen");
        }
        if (result.scenes().size() > 5) {
            throw new RuntimeException("ScriptResult: za dużo scen (" + result.scenes().size() + ") — max 5");
        }
        for (ScriptResult.SceneScript scene : result.scenes()) {
            if (scene.imagePrompt() == null || scene.imagePrompt().isBlank()) {
                throw new RuntimeException("ScriptResult: scena " + scene.index() + " bez imagePrompt");
            }
        }
    }
}