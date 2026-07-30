package com.BossAi.bossAi.dto.edl;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * A single word from the Whisper transcription — exact per-word timing.
 * Remotion uses this in SubtitleTrack → KaraokeHighlight
 * to highlight the active word in real time.
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
     * Index of the sentence this word belongs to.
     * Remotion SubtitleTrack displays only the words from the active sentence.
     */
    @JsonProperty("sentence_index")
    @Builder.Default
    private int sentenceIndex = 0;
}
