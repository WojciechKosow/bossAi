FROM python:3.11-slim

RUN apt-get update && apt-get install -y --no-install-recommends \
    libsndfile1 \
    ffmpeg \
    git \
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
