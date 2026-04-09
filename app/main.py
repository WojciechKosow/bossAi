import logging
import tempfile
from pathlib import Path

import librosa
from fastapi import FastAPI, File, Form, HTTPException, UploadFile

from app.analyzers.beat_analyzer import analyze_beats
from app.analyzers.mood_analyzer import analyze_mood
from app.analyzers.section_detector import detect_sections
from app.config import settings
from app.models.schemas import (
    AlignResponse,
    AudioAnalysisResponse,
    WordTimestamp,
)

logger = logging.getLogger(__name__)

app = FastAPI(
    title=settings.app_name,
    version="2.0.0",
    docs_url="/docs",
)


@app.get("/health")
async def health():
    return {"status": "ok"}


@app.post("/api/v1/analyze-audio", response_model=AudioAnalysisResponse)
async def analyze_audio(file: UploadFile = File(...)):
    extension = Path(file.filename or "").suffix.lstrip(".").lower()
    if extension not in settings.allowed_extensions:
        raise HTTPException(
            status_code=422,
            detail=f"Unsupported format '{extension}'. Allowed: {settings.allowed_extensions}",
        )

    content = await file.read()
    if len(content) > settings.max_file_size_mb * 1024 * 1024:
        raise HTTPException(status_code=422, detail="File too large")

    tmp_path = None
    try:
        tmp_fd = tempfile.NamedTemporaryFile(
            suffix=f".{extension}", delete=False
        )
        tmp_path = tmp_fd.name
        tmp_fd.write(content)
        tmp_fd.close()

        y, sr = librosa.load(tmp_path, sr=22050)
    except HTTPException:
        raise
    except Exception as e:
        raise HTTPException(status_code=422, detail=f"Cannot decode audio: {e}")
    finally:
        if tmp_path:
            Path(tmp_path).unlink(missing_ok=True)

    beat_data = analyze_beats(y, sr)
    mood_data = analyze_mood(y, sr)
    sections = detect_sections(beat_data["energy_curve"], beat_data["duration_seconds"])

    return AudioAnalysisResponse(
        bpm=beat_data["bpm"],
        duration_seconds=beat_data["duration_seconds"],
        beats=beat_data["beats"],
        onsets=beat_data["onsets"],
        energy_curve=beat_data["energy_curve"],
        sections=sections,
        mood=mood_data["mood"],
        genre_estimate=mood_data["genre_estimate"],
        danceability=mood_data["danceability"],
    )


@app.post("/api/v1/align", response_model=AlignResponse)
async def align_audio(
    file: UploadFile = File(..., description="Audio file (mp3, wav, m4a)"),
    language: str | None = Form(
        default=None,
        description="Language code (e.g. 'en', 'pl'). Auto-detect if omitted.",
    ),
    transcript: str | None = Form(
        default=None,
        description="Known transcript text for forced alignment. "
                    "Best accuracy when provided (e.g. from TTS narration).",
    ),
):
    """
    WhisperX word-level alignment endpoint.

    Accepts an audio file and returns precise per-word timestamps
    using WhisperX forced alignment (wav2vec2-based, <20ms accuracy).

    Two modes:
      1. **With transcript** (recommended for TTS): provide the exact text
         that was spoken. WhisperX aligns each word to the audio precisely.
      2. **Without transcript**: WhisperX transcribes + aligns automatically.

    Pipeline: preprocessing (16kHz, noise reduction) → VAD → transcription/alignment → cleanup
    """
    extension = Path(file.filename or "").suffix.lstrip(".").lower()
    if extension not in settings.allowed_extensions:
        raise HTTPException(
            status_code=422,
            detail=f"Unsupported format '{extension}'. Allowed: {settings.allowed_extensions}",
        )

    content = await file.read()
    if len(content) > settings.max_file_size_mb * 1024 * 1024:
        raise HTTPException(status_code=422, detail="File too large")

    tmp_path = None
    try:
        tmp_fd = tempfile.NamedTemporaryFile(
            suffix=f".{extension}", delete=False
        )
        tmp_path = tmp_fd.name
        tmp_fd.write(content)
        tmp_fd.close()

        logger.info(
            "[align] Processing %s (%d bytes), lang=%s, transcript=%s",
            file.filename,
            len(content),
            language or "auto",
            "yes" if transcript else "no",
        )

        from app.analyzers.whisperx_aligner import align_words

        word_dicts = align_words(
            audio_path=tmp_path,
            language=language,
            transcript=transcript,
        )

        words = [WordTimestamp(**w) for w in word_dicts]

        # Calculate duration from last word or audio length
        duration_ms = words[-1].end_ms if words else 0

        return AlignResponse(
            words=words,
            language=language or settings.whisperx_default_language,
            duration_ms=duration_ms,
            model=settings.whisperx_model_size,
        )

    except HTTPException:
        raise
    except RuntimeError as e:
        # Dependency/environment mismatch (e.g. unsupported NumPy/Torch for WhisperX)
        logger.exception("[align] WhisperX runtime dependency check failed")
        raise HTTPException(
            status_code=503,
            detail=f"WhisperX runtime dependency error: {e}",
        )
    except Exception as e:
        logger.exception("[align] WhisperX alignment failed")
        raise HTTPException(
            status_code=500,
            detail=f"WhisperX alignment failed: {e}",
        )
    finally:
        if tmp_path:
            Path(tmp_path).unlink(missing_ok=True)
