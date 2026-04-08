"""
WhisperX forced alignment — precise word-level timestamps for TTS audio.

Pipeline:
  1. Preprocessing: convert to 16kHz mono, optional noise reduction
  2. Transcription: WhisperX with VAD (large-v3 model)
  3. Forced alignment: wav2vec2-based phoneme alignment (<20ms accuracy)
  4. Cleanup: filter empty/garbage tokens, enforce minimum word duration

Usage:
  align_words(audio_path, language="en", transcript=None)
  → returns list of WordTimestamp(word, start_ms, end_ms)

  If transcript is provided, WhisperX uses it for alignment instead of
  its own transcription — this gives the best results when the exact
  text is already known (e.g. from TTS narration script).
"""

import logging
import re
import tempfile
from pathlib import Path

import numpy as np
import noisereduce as nr
import soundfile as sf
import torch

# --- torchaudio compatibility shim ---
# pyannote.audio (WhisperX dependency) calls torchaudio.set_audio_backend()
# which was removed in torchaudio >= 2.2. Patch it before importing whisperx.
import torchaudio
if not hasattr(torchaudio, "set_audio_backend"):
    torchaudio.set_audio_backend = lambda backend: None

import whisperx  # noqa: E402 — must be after torchaudio shim

from app.config import settings

logger = logging.getLogger(__name__)

# Lazy-loaded model cache (loaded once, reused across requests)
_whisper_model = None
_align_models: dict[str, tuple] = {}


def _get_device() -> str:
    """Detect best available device."""
    if settings.whisperx_device == "auto":
        return "cuda" if torch.cuda.is_available() else "cpu"
    return settings.whisperx_device


def _get_compute_type() -> str:
    """Pick compute type based on device."""
    device = _get_device()
    if device == "cuda":
        return settings.whisperx_compute_type or "float16"
    return "int8"


def _load_whisper_model():
    """Load WhisperX transcription model (cached)."""
    global _whisper_model
    if _whisper_model is None:
        device = _get_device()
        compute_type = _get_compute_type()
        model_size = settings.whisperx_model_size

        logger.info(
            "[WhisperX] Loading model=%s device=%s compute=%s",
            model_size, device, compute_type,
        )
        _whisper_model = whisperx.load_model(
            model_size,
            device=device,
            compute_type=compute_type,
        )
        logger.info("[WhisperX] Model loaded successfully")
    return _whisper_model


def _load_align_model(language_code: str):
    """Load alignment model for a specific language (cached per language)."""
    if language_code not in _align_models:
        device = _get_device()
        logger.info(
            "[WhisperX] Loading align model for lang=%s device=%s",
            language_code, device,
        )
        model, metadata = whisperx.load_align_model(
            language_code=language_code,
            device=device,
        )
        _align_models[language_code] = (model, metadata)
        logger.info("[WhisperX] Align model loaded for %s", language_code)
    return _align_models[language_code]


def _preprocess_audio(audio_path: str) -> tuple[np.ndarray, int]:
    """
    Load and preprocess audio for WhisperX:
      - Resample to 16kHz mono
      - Apply noise reduction (noisereduce)
      - Normalize amplitude

    Returns (audio_array, sample_rate=16000).
    """
    target_sr = 16000

    # Load with librosa for robust format handling + resampling
    import librosa
    y, sr = librosa.load(audio_path, sr=target_sr, mono=True)

    # Noise reduction — gentle settings to preserve speech clarity
    if settings.whisperx_noise_reduce:
        logger.info("[WhisperX] Applying noise reduction")
        y = nr.reduce_noise(
            y=y,
            sr=target_sr,
            prop_decrease=0.6,  # moderate reduction
            n_fft=2048,
            stationary=True,  # assume stationary background noise (TTS is clean)
        )

    # Normalize to prevent clipping
    peak = np.max(np.abs(y))
    if peak > 0:
        y = y / peak * 0.95

    return y, target_sr


def _build_segments_from_transcript(transcript: str) -> list[dict]:
    """
    Build WhisperX-compatible segment list from a known transcript.
    Used when the exact text is already available (from TTS narration).

    WhisperX align() expects segments with 'text', 'start', 'end' keys.
    We create a single segment spanning the whole audio — WhisperX
    forced alignment will determine exact word boundaries.
    """
    # Split into sentence-like chunks for better alignment
    # WhisperX handles long segments poorly — split on sentence boundaries
    sentences = re.split(r'(?<=[.!?;])\s+', transcript.strip())

    segments = []
    for sentence in sentences:
        sentence = sentence.strip()
        if not sentence:
            continue
        segments.append({
            "text": sentence,
            "start": 0.0,  # placeholder — align() will compute real times
            "end": 0.0,
        })

    return segments


def align_words(
    audio_path: str,
    language: str | None = None,
    transcript: str | None = None,
) -> list[dict]:
    """
    Main entry point: transcribe + align audio, return word timestamps.

    Args:
        audio_path: Path to audio file (mp3, wav, m4a)
        language: Language code (e.g. "en", "pl"). If None, auto-detect.
        transcript: Known transcript text. If provided, skip transcription
                    and use this text for forced alignment (best accuracy).

    Returns:
        List of dicts: [{"word": "hello", "start_ms": 120, "end_ms": 480}, ...]
    """
    device = _get_device()

    # Step 1: Preprocess audio
    logger.info("[WhisperX] Preprocessing audio: %s", audio_path)
    audio_np, sr = _preprocess_audio(audio_path)

    # Save preprocessed audio to temp WAV for WhisperX
    preprocessed_path = tempfile.NamedTemporaryFile(
        suffix=".wav", delete=False
    )
    sf.write(preprocessed_path.name, audio_np, sr)
    preprocessed_path.close()

    try:
        # Step 2: Load WhisperX audio (their internal loader)
        audio = whisperx.load_audio(preprocessed_path.name)

        if transcript:
            # Use known transcript — skip Whisper transcription entirely
            logger.info("[WhisperX] Using provided transcript (%d chars)", len(transcript))
            segments = _build_segments_from_transcript(transcript)
            detected_language = language or settings.whisperx_default_language

            # We still need Whisper for VAD segmentation if transcript has no timing
            # Use transcription to get proper segment boundaries, then override text
            model = _load_whisper_model()
            whisper_result = model.transcribe(
                audio,
                batch_size=settings.whisperx_batch_size,
                language=detected_language,
            )

            # Replace Whisper's transcription with our known text
            # Keep Whisper's segment boundaries (from VAD) for alignment
            if whisper_result.get("segments"):
                segments = _merge_transcript_with_segments(
                    transcript, whisper_result["segments"]
                )
            else:
                segments = _build_segments_from_transcript(transcript)

        else:
            # Full transcription mode
            model = _load_whisper_model()
            logger.info("[WhisperX] Transcribing with VAD, language=%s", language or "auto")

            transcribe_kwargs = {"batch_size": settings.whisperx_batch_size}
            if language:
                transcribe_kwargs["language"] = language

            whisper_result = model.transcribe(audio, **transcribe_kwargs)
            segments = whisper_result.get("segments", [])
            detected_language = whisper_result.get("language", language or "en")

            logger.info(
                "[WhisperX] Transcription done: %d segments, detected_lang=%s",
                len(segments), detected_language,
            )

        # Step 3: Forced alignment
        logger.info("[WhisperX] Running forced alignment for lang=%s", detected_language)
        align_model, align_metadata = _load_align_model(detected_language)

        aligned = whisperx.align(
            segments,
            align_model,
            align_metadata,
            audio,
            device=device,
            return_char_alignments=False,
        )

        # Step 4: Extract and clean word timestamps
        word_segments = aligned.get("word_segments", [])
        words = _clean_word_segments(word_segments)

        logger.info(
            "[WhisperX] Alignment done: %d raw → %d clean words",
            len(word_segments), len(words),
        )

        return words

    finally:
        Path(preprocessed_path.name).unlink(missing_ok=True)


def _merge_transcript_with_segments(
    transcript: str,
    whisper_segments: list[dict],
) -> list[dict]:
    """
    Merge known transcript text with Whisper's VAD-based segment boundaries.

    Strategy: distribute transcript words across Whisper segments proportionally.
    This gives WhisperX alignment the best of both worlds:
      - Accurate segment boundaries from VAD
      - Exact text from the TTS script (no transcription errors)
    """
    transcript_words = transcript.strip().split()
    if not transcript_words or not whisper_segments:
        return _build_segments_from_transcript(transcript)

    # Count total words in Whisper segments
    whisper_word_count = sum(
        len(seg.get("text", "").split()) for seg in whisper_segments
    )

    if whisper_word_count == 0:
        return _build_segments_from_transcript(transcript)

    # Distribute transcript words proportionally across segments
    merged_segments = []
    word_idx = 0

    for seg in whisper_segments:
        seg_word_count = len(seg.get("text", "").split())
        if seg_word_count == 0:
            continue

        # Proportional word count for this segment
        ratio = seg_word_count / whisper_word_count
        words_for_seg = max(1, round(len(transcript_words) * ratio))

        # Clamp to remaining words
        end_idx = min(word_idx + words_for_seg, len(transcript_words))
        seg_text = " ".join(transcript_words[word_idx:end_idx])

        if seg_text.strip():
            merged_segments.append({
                "text": seg_text,
                "start": seg.get("start", 0.0),
                "end": seg.get("end", 0.0),
            })

        word_idx = end_idx

    # Any remaining words go into the last segment
    if word_idx < len(transcript_words) and merged_segments:
        remaining = " ".join(transcript_words[word_idx:])
        merged_segments[-1]["text"] += " " + remaining

    return merged_segments


def _clean_word_segments(word_segments: list[dict]) -> list[dict]:
    """
    Clean WhisperX word segments:
      - Remove empty/whitespace-only words
      - Remove punctuation-only tokens ("...", ",", etc.)
      - Enforce minimum word duration (50ms)
      - Convert to milliseconds
      - Sort by start time
    """
    MIN_DURATION_MS = 50
    words = []

    for ws in word_segments:
        word = ws.get("word", "").strip()

        # Skip empty or whitespace
        if not word:
            continue

        # Skip punctuation-only tokens
        if re.match(r'^[^\w]+$', word):
            continue

        start = ws.get("start")
        end = ws.get("end")

        # Skip words without timing (alignment failed for this word)
        if start is None or end is None:
            logger.debug("[WhisperX] Skipping word without timing: '%s'", word)
            continue

        start_ms = int(round(start * 1000))
        end_ms = int(round(end * 1000))

        # Enforce minimum duration
        if end_ms - start_ms < MIN_DURATION_MS:
            end_ms = start_ms + MIN_DURATION_MS

        words.append({
            "word": word,
            "start_ms": start_ms,
            "end_ms": end_ms,
        })

    # Sort by start time (should already be, but ensure)
    words.sort(key=lambda w: w["start_ms"])

    return words
