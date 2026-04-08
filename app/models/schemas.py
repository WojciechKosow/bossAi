from pydantic import BaseModel


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
