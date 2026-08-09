from pydantic_settings import BaseSettings


class Settings(BaseSettings):
    app_name: str = "Audio Analysis Service"
    # Alignment (TTS) payloads stay small; the podcast /transcribe path ingests
    # full episodes (the Spring side already extracts 16kHz mono WAV, but that is
    # still hundreds of MB for a 2h show). Keep a generous cap.
    max_file_size_mb: int = 2048
    allowed_extensions: set[str] = {"mp3", "wav", "m4a"}
    energy_sample_interval: float = 0.5
    host: str = "0.0.0.0"
    port: int = 8000

    # --- WhisperX settings ---
    whisperx_model_size: str = "large-v3"       # large-v3 for best quality
    whisperx_device: str = "auto"               # "auto", "cuda", "cpu"
    whisperx_compute_type: str | None = None    # "float16" (GPU), "int8" (CPU) — auto if None
    whisperx_batch_size: int = 16               # reduce if GPU OOM
    whisperx_default_language: str = "en"       # fallback when auto-detect fails
    whisperx_noise_reduce: bool = True          # apply noisereduce preprocessing

    # --- Diarization (podcast /transcribe) ---
    # pyannote's speaker-diarization models are gated on Hugging Face. Set
    # AUDIO_HF_TOKEN to a token that has accepted the pyannote/speaker-diarization
    # user conditions. Without it, /transcribe still returns words but every
    # speaker label is null (the pipeline degrades to a single speaker).
    hf_token: str | None = None
    diarize_min_speakers: int | None = None     # optional hint
    diarize_max_speakers: int | None = None     # optional hint

    model_config = {"env_prefix": "AUDIO_"}


settings = Settings()
