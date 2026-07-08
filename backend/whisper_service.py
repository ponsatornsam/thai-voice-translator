"""
Thai Voice Translator — Faster-Whisper ASR Service
====================================================
Module 3: Speech-to-text using Faster-Whisper (model "base", int8 quantization).

Architecture:
  PCM 16kHz mono bytes → numpy float32 array → WhisperModel.transcribe()
  → concatenated text string

The model is loaded ONCE as a singleton (lazy-load) to avoid re-loading
on every request. In development, test via Colab MCP (GPU T4).
In production, runs locally on the HP machine via Docker.
"""

import logging
import numpy as np

logger = logging.getLogger("translator.whisper")

# ── Singleton model instance ─────────────────────────────────────────
# _model is None until first use; transcribe_audio() loads it on demand.
_model = None


def _get_model():
    """
    Lazy-load the Faster-Whisper model (singleton).

    Returns:
        WhisperModel: loaded model instance, ready for transcription.

    Tries CUDA first (Colab T4 or local GPU), falls back to CPU.
    Model "base" is the smallest English+multilingual model (~142 MB)
    that still gives good accuracy for real-time use.
    """
    global _model
    if _model is not None:
        return _model

    from faster_whisper import WhisperModel

    # ── Device selection ─────────────────────────────────────────────
    # Check for CUDA availability (Colab GPU T4 during dev, or local GPU
    # on the HP machine if it has one). Fall back to CPU otherwise.
    try:
        import torch  # noqa: F401 — used by faster-whisper internally
        # Try a tiny CUDA op to confirm GPU is actually usable
        _model = WhisperModel("base", device="cuda", compute_type="int8")
        logger.info("WhisperModel loaded on CUDA (GPU)")
    except Exception:
        logger.warning("CUDA not available, falling back to CPU")
        _model = WhisperModel("base", device="cpu", compute_type="int8")
        logger.info("WhisperModel loaded on CPU (int8)")

    return _model


# ── PCM-to-NumPy conversion ──────────────────────────────────────────
def _pcm_to_samples(audio_bytes: bytes) -> np.ndarray:
    """
    Convert raw PCM 16-bit mono bytes to a float32 NumPy array.

    Input format (from Android AudioRecord):
      - 16kHz sample rate
      - Mono (1 channel)
      - PCM 16-bit signed integer, little-endian

    Faster-Whisper expects: float32 samples in range [-1.0, 1.0].

    Args:
        audio_bytes: Raw PCM audio data.

    Returns:
        np.ndarray of shape (n_samples,) with dtype float32, normalized.
    """
    # Interpret bytes as 16-bit signed integers
    samples_i16 = np.frombuffer(audio_bytes, dtype=np.int16)

    # Normalize to float32 in [-1.0, 1.0]
    samples_f32 = samples_i16.astype(np.float32) / 32768.0

    return samples_f32


# ── Public API ───────────────────────────────────────────────────────
def transcribe_audio(audio_bytes: bytes) -> str:
    """
    Transcribe raw PCM audio bytes to text using Faster-Whisper.

    Args:
        audio_bytes: Raw PCM 16kHz mono audio (1–2 second chunk).

    Returns:
        Transcribed text string (may be empty for silence / very short audio).

    Raises:
        Never raises — errors are logged and an empty string is returned
        so the pipeline continues without crashing.
    """
    # ── Guard: empty or near-empty audio ──────────────────────────────
    if not audio_bytes or len(audio_bytes) < 320:  # < 10ms @ 16kHz 16-bit
        logger.warning(f"Audio too short ({len(audio_bytes)} bytes), returning empty string")
        return ""

    try:
        # ── Convert PCM → samples ────────────────────────────────────
        samples = _pcm_to_samples(audio_bytes)
        logger.debug(f"Converted {len(audio_bytes)} bytes → {len(samples)} samples")

        # ── Run Whisper transcription ─────────────────────────────────
        model = _get_model()
        segments, info = model.transcribe(
            samples,
            beam_size=5,              # Beam search width
            language=None,            # Auto-detect language
            vad_filter=True,          # Filter out silence/non-speech
            vad_parameters=dict(
                min_silence_duration_ms=500,
            ),
        )

        # ── Combine all segments into one string ──────────────────────
        # segments is a generator — iterate to collect all text.
        text_parts = []
        for segment in segments:
            text_parts.append(segment.text.strip())

        full_text = " ".join(text_parts)
        logger.info(
            f"Transcription done: language={info.language} "
            f"(p={info.language_probability:.2f}), "
            f"text='{full_text[:80]}{'...' if len(full_text) > 80 else ''}'"
        )
        return full_text

    except Exception:
        logger.exception(f"Transcription failed for {len(audio_bytes)} byte chunk")
        return ""


# ── Warm-up helper (for Colab / production startup) ──────────────────
def preload_model() -> bool:
    """
    Force-load the model now (instead of waiting for the first request).
    Call this at application startup to avoid cold-start latency.

    Returns:
        True if model loaded successfully.
    """
    try:
        _get_model()
        logger.info("Whisper model pre-loaded successfully")
        return True
    except Exception:
        logger.exception("Failed to pre-load Whisper model")
        return False
