import numpy as np
import librosa


def analyze_mood(y: np.ndarray, sr: int) -> dict:
    """Classify mood, estimate genre, and compute danceability using spectral features."""
    spectral_centroid = np.mean(librosa.feature.spectral_centroid(y=y, sr=sr))
    rms_mean = np.mean(librosa.feature.rms(y=y))
    zcr_mean = np.mean(librosa.feature.zero_crossing_rate(y))
    rolloff = np.mean(librosa.feature.spectral_rolloff(y=y, sr=sr))
    tempo = float(np.atleast_1d(librosa.beat.beat_track(y=y, sr=sr)[0])[0])

    mood = _classify_mood(spectral_centroid, rms_mean, tempo)
    genre = _estimate_genre(spectral_centroid, tempo, rms_mean, zcr_mean)
    danceability = _compute_danceability(tempo, rms_mean, zcr_mean)

    return {
        "mood": mood,
        "genre_estimate": genre,
        "danceability": round(danceability, 2),
    }


def _classify_mood(centroid: float, energy: float, tempo: float) -> str:
    if energy > 0.05 and tempo > 120:
        return "aggressive"
    if centroid > 3000 and tempo > 110:
        return "happy"
    if energy < 0.02 and tempo < 100:
        return "relaxed"
    if centroid < 2000 and energy < 0.03:
        return "sad"
    if energy > 0.04:
        return "aggressive"
    return "relaxed"


def _estimate_genre(
    centroid: float, tempo: float, energy: float, zcr: float
) -> str:
    if tempo > 130 and energy > 0.04:
        return "trap"
    if tempo > 120 and centroid > 3500:
        return "edm"
    if 85 < tempo < 115 and energy > 0.03:
        return "hip-hop"
    if centroid > 3000 and tempo > 110:
        return "pop"
    if centroid < 2000 and energy < 0.02:
        return "ambient"
    return "electronic"


def _compute_danceability(tempo: float, energy: float, zcr: float) -> float:
    tempo_score = 1.0 - abs(tempo - 120) / 120
    tempo_score = max(0.0, min(1.0, tempo_score))

    energy_score = min(energy / 0.05, 1.0)
    zcr_score = min(zcr / 0.1, 1.0)

    return 0.5 * tempo_score + 0.3 * energy_score + 0.2 * zcr_score
