import io
import numpy as np
import soundfile as sf
from fastapi.testclient import TestClient

from app.main import app

client = TestClient(app)


def _make_synthetic_wav(duration: float = 5.0, sr: int = 22050) -> bytes:
    """Generate a synthetic WAV with a kick-like pattern for testing."""
    t = np.linspace(0, duration, int(sr * duration), endpoint=False)
    # Simple beat pattern: sine bursts every 0.5s
    signal = np.zeros_like(t)
    beat_interval = 0.5
    for beat_start in np.arange(0, duration, beat_interval):
        mask = (t >= beat_start) & (t < beat_start + 0.05)
        signal[mask] += 0.8 * np.sin(2 * np.pi * 100 * (t[mask] - beat_start))
    # Add some mid-frequency content
    signal += 0.2 * np.sin(2 * np.pi * 440 * t)

    buf = io.BytesIO()
    sf.write(buf, signal, sr, format="WAV")
    buf.seek(0)
    return buf.read()


def test_health():
    resp = client.get("/health")
    assert resp.status_code == 200
    assert resp.json()["status"] == "ok"


def test_analyze_audio_success():
    wav_bytes = _make_synthetic_wav()
    resp = client.post(
        "/api/v1/analyze-audio",
        files={"file": ("test.wav", wav_bytes, "audio/wav")},
    )
    assert resp.status_code == 200
    data = resp.json()

    assert "bpm" in data
    assert data["duration_seconds"] > 0
    assert isinstance(data["beats"], list)
    assert isinstance(data["onsets"], list)
    assert isinstance(data["energy_curve"], list)
    assert len(data["energy_curve"]) > 0
    assert "time" in data["energy_curve"][0]
    assert "energy" in data["energy_curve"][0]
    assert isinstance(data["sections"], list)
    assert len(data["sections"]) >= 2
    assert data["mood"] in ("aggressive", "happy", "relaxed", "sad")
    assert isinstance(data["genre_estimate"], str)
    assert 0.0 <= data["danceability"] <= 1.0


def test_invalid_format():
    resp = client.post(
        "/api/v1/analyze-audio",
        files={"file": ("test.txt", b"not audio", "text/plain")},
    )
    assert resp.status_code == 422


def test_corrupted_audio():
    resp = client.post(
        "/api/v1/analyze-audio",
        files={"file": ("test.wav", b"corrupted data", "audio/wav")},
    )
    assert resp.status_code == 422
