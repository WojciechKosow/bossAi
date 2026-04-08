from pydantic import BaseModel, Field


class EnergyPoint(BaseModel):
    time: float
    energy: float


class Section(BaseModel):
    start: float
    end: float
    type: str
    energy: str


class AudioAnalysisResponse(BaseModel):
    bpm: float
    duration_seconds: float
    beats: list[float]
    onsets: list[float]
    energy_curve: list[EnergyPoint]
    sections: list[Section]
    mood: str
    genre_estimate: str
    danceability: float


# --- WhisperX alignment schemas ---


class WordTimestamp(BaseModel):
    word: str
    start_ms: int
    end_ms: int


class AlignRequest(BaseModel):
    """Request body for /api/v1/align (when sending JSON with transcript)."""
    language: str | None = Field(
        default=None,
        description="Language code (e.g. 'en', 'pl'). Auto-detect if omitted.",
    )
    transcript: str | None = Field(
        default=None,
        description="Known transcript text. If provided, WhisperX uses forced "
                    "alignment against this text instead of its own transcription. "
                    "Best accuracy when text comes from TTS narration script.",
    )


class AlignResponse(BaseModel):
    words: list[WordTimestamp]
    language: str
    duration_ms: int
    model: str = Field(description="WhisperX model used")
