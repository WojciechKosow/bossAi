from pydantic_settings import BaseSettings


class Settings(BaseSettings):
    app_name: str = "Audio Analysis Service"
    max_file_size_mb: int = 50
    allowed_extensions: set[str] = {"mp3", "wav", "m4a"}
    energy_sample_interval: float = 0.5
    host: str = "0.0.0.0"
    port: int = 8000

    model_config = {"env_prefix": "AUDIO_"}


settings = Settings()
