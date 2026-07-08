"""
Thai Voice Translator — Piper TTS Service
===========================================
Module 5: Text-to-Speech using Piper TTS with a Thai voice model.

Architecture:
  Thai text → Piper binary (subprocess) → PCM 16kHz mono bytes

The Piper model is referenced by path (models/ directory); the actual
model file must be downloaded separately (~50-80 MB .onnx + .json config).

In development, test via Colab MCP (GPU T4) or locally if Piper is installed.
In production, runs in Docker with the model mounted via volume.
"""

import logging
import subprocess
import os
import tempfile

logger = logging.getLogger("translator.tts")

# ── Configuration ────────────────────────────────────────────────────
# Path to the Piper voice model (.onnx file).
# In dev: downloaded to models/ manually or via Colab MCP.
# In production: mounted via Docker volume from host.
MODEL_DIR = os.environ.get("PIPER_MODEL_DIR", "models")
MODEL_NAME = os.environ.get("PIPER_MODEL_NAME", "th_TH")

# Default voice model path — Thai female voice
# Download from: https://huggingface.co/rhasspy/piper-voices
# File: th_TH/{MODEL_NAME}.onnx + {MODEL_NAME}.json
MODEL_PATH = os.path.join(MODEL_DIR, f"{MODEL_NAME}.onnx")
MODEL_CONFIG = os.path.join(MODEL_DIR, f"{MODEL_NAME}.json")

# PCM output format (matching Android AudioTrack expectations)
SAMPLE_RATE = 16000  # Hz
SAMPLE_WIDTH = 2     # bytes (16-bit)
CHANNELS = 1         # mono

# ── Model readiness ──────────────────────────────────────────────────
_model_available: bool | None = None  # None = not checked yet


def check_model() -> bool:
    """
    Check whether the Piper voice model file exists on disk.

    Returns:
        True if model .onnx and .json files are present.
    """
    global _model_available
    if _model_available is not None:
        return _model_available

    onnx_ok = os.path.isfile(MODEL_PATH)
    json_ok = os.path.isfile(MODEL_CONFIG)
    _model_available = onnx_ok and json_ok

    if _model_available:
        logger.info(f"Piper model found: {MODEL_PATH}")
    else:
        logger.warning(
            f"Piper model NOT found at {MODEL_PATH}. "
            f"Download from https://huggingface.co/rhasspy/piper-voices "
            f"(resolved: th_TH/{MODEL_NAME}) and place in {MODEL_DIR}/"
        )
        if not onnx_ok:
            logger.warning(f"Missing: {MODEL_PATH}")
        if not json_ok:
            logger.warning(f"Missing: {MODEL_CONFIG}")

    return _model_available


# ── Public API ───────────────────────────────────────────────────────
def synthesize_speech(text: str) -> bytes:
    """
    Convert Thai text to PCM audio bytes using Piper TTS.

    Calls the `piper` command-line tool via subprocess. The Piper binary
    must be installed on the system (apt install piper-tts / pip install piper-tts).

    Args:
        text: Thai text to synthesize into speech.

    Returns:
        Raw PCM audio bytes (16kHz, 16-bit, mono, little-endian).
        Returns empty bytes on any error.

    Raises:
        Never raises — errors are logged and empty bytes returned.
    """
    # ── Guard: empty input ───────────────────────────────────────────
    if not text or not text.strip():
        logger.debug("Empty text — returning empty audio")
        return b""

    # ── Guard: model not available ───────────────────────────────────
    if not check_model():
        logger.error("Piper model not available — cannot synthesize speech")
        return b""

    try:
        # ── Run Piper via subprocess ─────────────────────────────────
        # piper --model <model> --config <config> --output-raw
        # Input:  text via stdin
        # Output: raw PCM 16-bit 16kHz mono on stdout
        result = subprocess.run(
            [
                "piper",
                "--model", MODEL_PATH,
                "--config", MODEL_CONFIG,
                "--output-raw",  # Raw PCM output (no WAV header)
            ],
            input=text.strip(),
            capture_output=True,
            text=False,             # stdin is text, stdout is binary
            timeout=10,             # 10s should be plenty for 1-2 sentences
            check=True,
        )

        pcm_bytes = result.stdout
        if not pcm_bytes:
            logger.warning(
                f"Piper produced no audio for text: '{text[:60]}'"
            )
            return b""

        duration = len(pcm_bytes) / (SAMPLE_RATE * SAMPLE_WIDTH)
        logger.info(
            f"TTS: '{text[:50]}{'...' if len(text) > 50 else ''}' "
            f"→ {len(pcm_bytes)} bytes ({duration:.1f}s PCM)"
        )
        return pcm_bytes

    except subprocess.TimeoutExpired:
        logger.error(f"Piper TTS timeout (10s) for text: '{text[:60]}'")
        return b""

    except subprocess.CalledProcessError as e:
        logger.error(
            f"Piper returned exit code {e.returncode}: "
            f"stderr={e.stderr[:200] if e.stderr else 'none'}"
        )
        return b""

    except FileNotFoundError:
        logger.error(
            "Piper binary not found. Install with: "
            "apt install piper-tts  OR  pip install piper-tts  OR "
            "download from https://github.com/rhasspy/piper/releases"
        )
        return b""

    except Exception:
        logger.exception(f"Piper TTS unexpected error for text: '{text[:60]}'")
        return b""


def preload_model() -> bool:
    """
    Verify the model is available at startup.
    Call this during application initialization to fail early.

    Returns:
        True if model is ready.
    """
    return check_model()
