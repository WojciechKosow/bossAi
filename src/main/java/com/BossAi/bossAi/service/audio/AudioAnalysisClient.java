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
 * HTTP client for the audio-analysis-service microservice (Python/FastAPI).
 *
 * Sends an audio file (MP3/WAV/M4A) → receives an AudioAnalysisResponse
 * with a beat map, energy curve, mood, and sections.
 */
@Slf4j
@Service
public class AudioAnalysisClient {

    private final WebClient webClient;

    public AudioAnalysisClient(AudioAnalysisProperties properties, WebClient.Builder builder) {
        // Tolerate a base URL configured without a scheme (e.g. a bare Railway
        // internal host "svc.railway.internal:8000"); reactor-netty otherwise
        // fails with "Host is not specified". A missing port stays on the operator.
        String baseUrl = properties.getBaseUrl();
        if (baseUrl != null && !baseUrl.isBlank() && !baseUrl.matches("(?i)^https?://.*")) {
            baseUrl = "http://" + baseUrl.trim();
        }

        this.webClient = builder
                .baseUrl(baseUrl)
                .build();

        log.info("[AudioAnalysisClient] Initialized — baseUrl: {}", baseUrl);
    }

    /**
     * Analyzes the audio file and returns a full profile: beats, energy, mood, sections.
     *
     * @param audioBytes  the audio file contents
     * @param filename    the file name (e.g. "music.mp3") — needed for multipart
     * @return an AudioAnalysisResponse with the analysis results
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
     * WhisperX forced alignment — precise per-word timestamps (<20ms).
     *
     * @param audioBytes  the TTS audio file (MP3)
     * @param filename    the file name (e.g. "voice.mp3")
     * @param language    the language code (e.g. "en", "pl") — null = auto-detect
     * @param transcript  the known narration text (from TTS) — null = full transcription
     * @return a WhisperXAlignResponse with per-word timestamps
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
