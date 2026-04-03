package com.BossAi.bossAi.service.audio;

import com.BossAi.bossAi.config.properties.AudioAnalysisProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.MediaType;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * HTTP client do mikroserwisu audio-analysis-service (Python/FastAPI).
 *
 * Wysyła plik audio (MP3/WAV/M4A) → odbiera AudioAnalysisResponse
 * z beat map, energy curve, nastrojem i sekcjami.
 */
@Slf4j
@Service
public class AudioAnalysisClient {

    private final WebClient webClient;

    public AudioAnalysisClient(AudioAnalysisProperties properties, WebClient.Builder builder) {
        this.webClient = builder
                .baseUrl(properties.getBaseUrl())
                .build();

        log.info("[AudioAnalysisClient] Initialized — baseUrl: {}", properties.getBaseUrl());
    }

    /**
     * Analizuje plik audio i zwraca pełny profil: beats, energy, mood, sections.
     *
     * @param audioBytes  zawartość pliku audio
     * @param filename    nazwa pliku (np. "music.mp3") — potrzebna dla multipart
     * @return AudioAnalysisResponse z wynikami analizy
     */
    public AudioAnalysisResponse analyzeAudio(byte[] audioBytes, String filename) {
        log.info("[AudioAnalysisClient] Analyzing audio — file: {}, size: {} bytes", filename, audioBytes.length);

        MultipartBodyBuilder bodyBuilder = new MultipartBodyBuilder();
        bodyBuilder.part("file", new ByteArrayResource(audioBytes) {
            @Override
            public String getFilename() {
                return filename;
            }
        }).contentType(MediaType.APPLICATION_OCTET_STREAM);

        AudioAnalysisResponse response = webClient.post()
                .uri("/api/v1/analyze-audio")
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(BodyInserters.fromMultipartData(bodyBuilder.build()))
                .retrieve()
                .bodyToMono(AudioAnalysisResponse.class)
                .block();

        if (response != null) {
            log.info("[AudioAnalysisClient] Analysis OK — bpm={}, duration={}s, {} beats, {} sections, mood={}",
                    response.bpm(), response.durationSeconds(),
                    response.beats() != null ? response.beats().size() : 0,
                    response.sections() != null ? response.sections().size() : 0,
                    response.mood());
        }

        return response;
    }
}
