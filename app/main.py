import tempfile
from pathlib import Path

import librosa
from fastapi import FastAPI, File, HTTPException, UploadFile

from app.analyzers.beat_analyzer import analyze_beats
from app.analyzers.mood_analyzer import analyze_mood
from app.analyzers.section_detector import detect_sections
from app.config import settings
from app.models.schemas import AudioAnalysisResponse

app = FastAPI(
    title=settings.app_name,
    version="1.0.0",
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
