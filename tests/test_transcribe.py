"""
Contract tests for the /api/v1/transcribe schema.

These deliberately do NOT exercise WhisperX (which needs heavy GPU/model deps).
They pin the wire shape the Spring Boot TranscribeResponse maps against, and the
graceful-degradation behavior of the word cleaner when no speaker is present.
"""

import pytest

from app.models.schemas import DiarizedWord, TranscribeResponse


def test_transcribe_response_wire_shape():
    resp = TranscribeResponse(
        words=[
            DiarizedWord(word="Hello", start_ms=120, end_ms=410, speaker="SPEAKER_00"),
            DiarizedWord(word="there", start_ms=420, end_ms=700, speaker=None),
        ],
        language="en",
        duration_ms=700,
        num_speakers=1,
    )
    data = resp.model_dump()
    assert set(data.keys()) == {"words", "language", "duration_ms", "num_speakers"}
    first = data["words"][0]
    assert set(first.keys()) == {"word", "start_ms", "end_ms", "speaker"}
    assert first["speaker"] == "SPEAKER_00"
    # Missing diarization degrades to null, not a crash.
    assert data["words"][1]["speaker"] is None


def test_clean_word_segments_preserves_speaker_and_filters_junk():
    # The cleaner lives in the WhisperX module, whose import pulls in torch/whisperx.
    # Skip cleanly where those heavy deps aren't installed (e.g. a lightweight CI).
    pytest.importorskip("whisperx")
    from app.analyzers.whisperx_aligner import _clean_word_segments_with_speaker

    raw = [
        {"word": "Hello", "start": 0.12, "end": 0.41, "speaker": "SPEAKER_00"},
        {"word": "  ", "start": 0.5, "end": 0.6, "speaker": "SPEAKER_00"},   # blank
        {"word": "...", "start": 0.7, "end": 0.8, "speaker": "SPEAKER_00"},  # punct-only
        {"word": "world", "start": 0.9, "end": 0.95, "speaker": "SPEAKER_01"},  # short → min dur
        {"word": "orphan", "start": None, "end": None, "speaker": "S"},      # no timing
    ]
    cleaned = _clean_word_segments_with_speaker(raw)
    assert [w["word"] for w in cleaned] == ["Hello", "world"]
    assert cleaned[0]["speaker"] == "SPEAKER_00"
    assert cleaned[1]["speaker"] == "SPEAKER_01"
    # min-duration enforcement (50ms) on the short "world" token
    assert cleaned[1]["end_ms"] - cleaned[1]["start_ms"] >= 50
