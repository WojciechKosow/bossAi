FROM python:3.11-slim

# Runtime libs (libsndfile, ffmpeg, git) plus the toolchain PyAV needs to build
# from source: faster-whisper==1.0.0 (required for whisperx 3.1.6) pins av==11.*,
# which has no wheel picked here and compiles against the system ffmpeg — that
# needs a C compiler, pkg-config, and the ffmpeg dev headers. Debian bookworm's
# ffmpeg 5.1 dev libs are within PyAV 11's supported range.
RUN apt-get update && apt-get install -y --no-install-recommends \
    libsndfile1 \
    ffmpeg \
    git \
    build-essential \
    pkg-config \
    libavformat-dev \
    libavcodec-dev \
    libavdevice-dev \
    libavutil-dev \
    libavfilter-dev \
    libswscale-dev \
    libswresample-dev \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /app

# Install PyTorch CPU first (smaller image; use nvidia/cuda base for GPU)
# For GPU: replace this with torch+cu121 and use nvidia/cuda:12.1-runtime base
COPY requirements.txt .
RUN pip install --no-cache-dir torch torchaudio --index-url https://download.pytorch.org/whl/cpu \
    && pip install --no-cache-dir -r requirements.txt

COPY . .

EXPOSE 8000

# WhisperX downloads models on first request — cache them in a volume
ENV HF_HOME=/app/.cache/huggingface

CMD ["uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "8000"]
