package com.BossAi.bossAi.service.audio;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * DTO mapujący response z mikroserwisu audio-analysis-service (Python/FastAPI).
 * Używany przez EdlGeneratorService do przekazania danych GPT.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record AudioAnalysisResponse(

        @JsonProperty("bpm")
        int bpm,

        @JsonProperty("duration_seconds")
        double durationSeconds,

        @JsonProperty("beats")
        List<Double> beats,

        @JsonProperty("onsets")
        List<Double> onsets,

        @JsonProperty("energy_curve")
        List<EnergyPoint> energyCurve,

        @JsonProperty("sections")
        List<Section> sections,

        @JsonProperty("mood")
        String mood,

        @JsonProperty("genre_estimate")
        String genreEstimate,

        @JsonProperty("danceability")
        double danceability

) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record EnergyPoint(
            @JsonProperty("time") double time,
            @JsonProperty("energy") double energy
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Section(
            @JsonProperty("start") double start,
            @JsonProperty("end") double end,
            @JsonProperty("type") String type,
            @JsonProperty("energy") String energy
    ) {}
}
