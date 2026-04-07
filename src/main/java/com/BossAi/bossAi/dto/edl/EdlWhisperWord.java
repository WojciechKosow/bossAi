package com.BossAi.bossAi.dto.edl;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Pojedyncze slowo z Whisper transcription — dokladny timing per word.
 * Remotion uzywa tego w SubtitleTrack → KaraokeHighlight
 * do podswietlania aktywnego slowa w czasie rzeczywistym.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class EdlWhisperWord {

    @JsonProperty("word")
    private String word;

    @JsonProperty("start_ms")
    private int startMs;

    @JsonProperty("end_ms")
    private int endMs;

    /**
     * Index zdania do ktorego nalezy to slowo.
     * Remotion SubtitleTrack wyswietla tylko slowa z aktywnego zdania.
     */
    @JsonProperty("sentence_index")
    @Builder.Default
    private int sentenceIndex = 0;
}
