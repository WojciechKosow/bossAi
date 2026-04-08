package com.BossAi.bossAi.service.audio;

import com.BossAi.bossAi.service.SubtitleService;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * DTO mapujący response z WhisperX /api/v1/align endpoint.
 * Zawiera per-word timestamps z forced alignment (<20ms accuracy).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record WhisperXAlignResponse(

        @JsonProperty("words")
        List<WordTimestamp> words,

        @JsonProperty("language")
        String language,

        @JsonProperty("duration_ms")
        int durationMs,

        @JsonProperty("model")
        String model

) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record WordTimestamp(
            @JsonProperty("word") String word,
            @JsonProperty("start_ms") int startMs,
            @JsonProperty("end_ms") int endMs
    ) {}

    /**
     * Konwertuje WhisperX response na listę SubtitleService.WordTiming
     * kompatybilną z istniejącym pipeline (RenderStep, EdlGeneratorService).
     */
    public List<SubtitleService.WordTiming> toWordTimings() {
        if (words == null) return List.of();
        return words.stream()
                .filter(w -> w.word() != null && !w.word().isBlank())
                .map(w -> new SubtitleService.WordTiming(w.word(), w.startMs(), w.endMs()))
                .toList();
    }
}
