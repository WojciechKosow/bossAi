from pydantic_settings import BaseSettings


class Settings(BaseSettings):
    app_name: str = "Audio Analysis Service"
    max_file_size_mb: int = 50
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

    model_config = {"env_prefix": "AUDIO_"}


settings = Settings()
