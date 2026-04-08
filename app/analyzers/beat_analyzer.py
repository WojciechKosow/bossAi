import numpy as np
import librosa

from app.config import settings
from app.models.schemas import EnergyPoint


def analyze_beats(y: np.ndarray, sr: int) -> dict:
    """Extract beats, onsets, tempo, and energy curve from audio signal."""
    tempo, beat_frames = librosa.beat.beat_track(y=y, sr=sr)
    beat_times = librosa.frames_to_time(beat_frames, sr=sr).tolist()

    onset_frames = librosa.onset.onset_detect(y=y, sr=sr)
    onset_times = librosa.frames_to_time(onset_frames, sr=sr).tolist()

    bpm = float(np.atleast_1d(tempo)[0])

    duration = librosa.get_duration(y=y, sr=sr)

    energy_curve = _compute_energy_curve(y, sr, duration)

    return {
        "bpm": round(bpm, 1),
        "duration_seconds": round(duration, 2),
        "beats": [round(b, 3) for b in beat_times],
        "onsets": [round(o, 3) for o in onset_times],
        "energy_curve": energy_curve,
    }


def _compute_energy_curve(
    y: np.ndarray, sr: int, duration: float
) -> list[EnergyPoint]:
    """Compute RMS energy sampled at regular intervals."""
    rms = librosa.feature.rms(y=y)[0]
    times = librosa.times_like(rms, sr=sr)

    interval = settings.energy_sample_interval
    sample_times = np.arange(0, duration, interval)

    rms_max = rms.max() if rms.max() > 0 else 1.0

    curve = []
    for t in sample_times:
        idx = np.argmin(np.abs(times - t))
        energy = float(rms[idx] / rms_max)
        curve.append(EnergyPoint(time=round(t, 2), energy=round(energy, 3)))

    return curve
