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

    /**
     * WhisperX forced alignment — precyzyjne per-word timestamps (<20ms).
     *
     * @param audioBytes  plik audio TTS (MP3)
     * @param filename    nazwa pliku (np. "voice.mp3")
     * @param language    kod języka (np. "en", "pl") — null = auto-detect
     * @param transcript  znany tekst narracji (z TTS) — null = pełna transkrypcja
     * @return WhisperXAlignResponse z per-word timestamps
     */
    public WhisperXAlignResponse alignWords(
            byte[] audioBytes,
            String filename,
            String language,
            String transcript
    ) {
        log.info("[AudioAnalysisClient] WhisperX align — file: {}, size: {} bytes, lang: {}, transcript: {}",
                filename, audioBytes.length, language != null ? language : "auto",
                transcript != null ? transcript.length() + " chars" : "none");

        MultipartBodyBuilder bodyBuilder = new MultipartBodyBuilder();
        bodyBuilder.part("file", new ByteArrayResource(audioBytes) {
            @Override
            public String getFilename() {
                return filename;
            }
        }).contentType(MediaType.APPLICATION_OCTET_STREAM);

        if (language != null && !language.isBlank()) {
            bodyBuilder.part("language", language);
        }
        if (transcript != null && !transcript.isBlank()) {
            bodyBuilder.part("transcript", transcript);
        }

        WhisperXAlignResponse response = webClient.post()
                .uri("/api/v1/align")
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(BodyInserters.fromMultipartData(bodyBuilder.build()))
                .retrieve()
                .bodyToMono(WhisperXAlignResponse.class)
                .block();

        if (response != null && response.words() != null) {
            log.info("[AudioAnalysisClient] WhisperX OK — {} words, duration={}ms, model={}",
                    response.words().size(), response.durationMs(), response.model());
        }

        return response;
    }
}
