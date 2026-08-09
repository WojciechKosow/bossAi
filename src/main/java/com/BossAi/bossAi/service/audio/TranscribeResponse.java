package com.BossAi.bossAi.service.audio;

import com.BossAi.bossAi.service.podcast.model.DiarizedTranscript;
import com.BossAi.bossAi.service.podcast.model.TranscriptWord;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * DTO mapping the response from the WhisperX {@code POST /api/v1/transcribe}
 * endpoint on the audio-analysis service.
 *
 * <p>Unlike {@link WhisperXAlignResponse} (forced alignment against a known
 * transcript, used for TTS captioning), this endpoint performs FULL open
 * transcription plus speaker diarization — it is the entry point for the
 * podcast → clips pipeline. Each word carries a {@code speaker} label from
 * WhisperX's pyannote diarization.
 *
 * <p><b>Python-side contract</b> (audio-analysis-service must implement):
 * <pre>
 *   POST /api/v1/transcribe   (multipart: file, optional language, optional min/max speakers)
 *   200 → {
 *     "language": "en",
 *     "duration_ms": 2400000,
 *     "words": [
 *       {"word": "Hello", "start_ms": 120, "end_ms": 410, "speaker": "SPEAKER_00"},
 *       ...
 *     ]
 *   }
 * </pre>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record TranscribeResponse(

        @JsonProperty("words") List<Word> words,
        @JsonProperty("language") String language,
        @JsonProperty("duration_ms") int durationMs

) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Word(
            @JsonProperty("word") String word,
            @JsonProperty("start_ms") int startMs,
            @JsonProperty("end_ms") int endMs,
            @JsonProperty("speaker") String speaker
    ) {}

    /** Maps the wire DTO into the pipeline's internal {@link DiarizedTranscript} model. */
    public DiarizedTranscript toDiarizedTranscript() {
        List<TranscriptWord> mapped = (words == null ? List.<Word>of() : words).stream()
                .filter(w -> w.word() != null && !w.word().isBlank())
                .map(w -> new TranscriptWord(w.word(), w.startMs(), w.endMs(), w.speaker()))
                .toList();
        return new DiarizedTranscript(mapped, language, durationMs);
    }
}
