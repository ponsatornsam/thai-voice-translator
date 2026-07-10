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
import asyncio

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
_piper_binary_available: bool | None = None
_ffmpeg_available: bool | None = None


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


def _check_piper_binary() -> bool:
    """Check if the 'piper' command is available on PATH."""
    global _piper_binary_available
    if _piper_binary_available is not None:
        return _piper_binary_available
    try:
        subprocess.run(["piper", "--help"], capture_output=True, timeout=5)
        _piper_binary_available = True
    except (FileNotFoundError, subprocess.TimeoutExpired):
        _piper_binary_available = False
        logger.warning("Piper binary not found on PATH — will use fallback TTS")
    return _piper_binary_available


def _check_ffmpeg() -> bool:
    """Check if ffmpeg is available for MP3→PCM conversion."""
    global _ffmpeg_available
    if _ffmpeg_available is not None:
        return _ffmpeg_available
    try:
        subprocess.run(["ffmpeg", "-version"], capture_output=True, timeout=5)
        _ffmpeg_available = True
    except (FileNotFoundError, subprocess.TimeoutExpired):
        _ffmpeg_available = False
        logger.warning("ffmpeg not found — Google TTS fallback unavailable")
    return _ffmpeg_available


# ── Google Translate TTS Fallback ─────────────────────────────────────

def _synthesize_via_google(text: str) -> bytes:
    """
    Fallback TTS using Google Translate (free, no API key).

    Downloads MP3 audio from translate.google.com and converts to raw PCM
    16kHz 16-bit mono via ffmpeg.

    Returns empty bytes if ffmpeg is unavailable or the request fails.
    """
    if not _check_ffmpeg():
        logger.error("Google TTS fallback requires ffmpeg — install: apt install ffmpeg")
        return b""

    try:
        # Step 1: Download MP3 from Google Translate TTS
        import urllib.request
        import urllib.parse

        # Google TTS free endpoint (unofficial but stable)
        params = {
            "ie": "UTF-8",
            "tl": "th",
            "client": "tw-ob",
            "q": text.strip(),
        }
        url = "https://translate.google.com/translate_tts?" + urllib.parse.urlencode(params)

        req = urllib.request.Request(url, headers={
            "User-Agent": "Mozilla/5.0 (compatible; ThaiTranslator/1.0)",
        })

        with urllib.request.urlopen(req, timeout=10) as resp:
            mp3_data = resp.read()

        if not mp3_data or len(mp3_data) < 100:
            logger.warning(f"Google TTS returned too little data ({len(mp3_data)}B)")
            return b""

        # Step 2: Convert MP3 → PCM 16kHz 16-bit mono via ffmpeg
        with tempfile.NamedTemporaryFile(suffix=".mp3", delete=False) as tmp_mp3:
            tmp_mp3.write(mp3_data)
            mp3_path = tmp_mp3.name

        pcm_path = mp3_path + ".pcm"
        try:
            subprocess.run(
                [
                    "ffmpeg", "-y",
                    "-i", mp3_path,
                    "-f", "s16le",
                    "-acodec", "pcm_s16le",
                    "-ar", str(SAMPLE_RATE),
                    "-ac", str(CHANNELS),
                    pcm_path,
                ],
                check=True,
                capture_output=True,
                timeout=15,
            )

            with open(pcm_path, "rb") as f:
                pcm_bytes = f.read()

            duration = len(pcm_bytes) / (SAMPLE_RATE * SAMPLE_WIDTH)
            logger.info(
                f"Google TTS fallback: '{text[:40]}' → {len(pcm_bytes)}B ({duration:.1f}s PCM)"
            )
            return pcm_bytes

        finally:
            # Clean up temp files
            for p in (mp3_path, pcm_path):
                try:
                    os.unlink(p)
                except OSError:
                    pass

    except Exception as e:
        logger.error(f"Google TTS fallback failed: {e}")
        return b""


# ── Public API ───────────────────────────────────────────────────────
def synthesize_speech(text: str) -> bytes:
    """
    Convert Thai text to PCM audio bytes using Piper TTS (primary) or
    Google Translate TTS (free fallback).

    Priority:
      1. Piper TTS — if model + binary are available (best quality, offline)
      2. Google Translate TTS — free, no API key, requires internet + ffmpeg

    Args:
        text: Thai text to synthesize into speech.

    Returns:
        Raw PCM audio bytes (16kHz, 16-bit, mono, little-endian).
        Returns empty bytes on all failures.

    Raises:
        Never raises — errors are logged and empty bytes returned.
    """
    # ── Guard: empty input ───────────────────────────────────────────
    if not text or not text.strip():
        logger.debug("Empty text — returning empty audio")
        return b""

    # ── Try Piper first (best quality, offline) ──────────────────────
    if check_model() and _check_piper_binary():
        try:
            result = subprocess.run(
                [
                    "piper",
                    "--model", MODEL_PATH,
                    "--config", MODEL_CONFIG,
                    "--output-raw",
                ],
                input=text.strip(),
                capture_output=True,
                text=False,
                timeout=10,
                check=True,
            )

            pcm_bytes = result.stdout
            if pcm_bytes:
                duration = len(pcm_bytes) / (SAMPLE_RATE * SAMPLE_WIDTH)
                logger.info(
                    f"Piper TTS: '{text[:50]}{'...' if len(text) > 50 else ''}' "
                    f"→ {len(pcm_bytes)} bytes ({duration:.1f}s PCM)"
                )
                return pcm_bytes
            else:
                logger.warning("Piper produced no audio — trying fallback")

        except subprocess.TimeoutExpired:
            logger.error(f"Piper TTS timeout (10s) — trying fallback")
        except subprocess.CalledProcessError as e:
            logger.error(f"Piper exit {e.returncode} — trying fallback")
        except FileNotFoundError:
            logger.warning("Piper binary not on PATH — using fallback")
        except Exception:
            logger.exception("Piper TTS error — trying fallback")
    else:
        logger.info("Piper not available — using Google TTS fallback")

    # ── Fallback: Google Translate TTS (free, needs internet + ffmpeg) ──
    pcm_bytes = _synthesize_via_google(text)
    if pcm_bytes:
        return pcm_bytes

    # ── Nothing worked ───────────────────────────────────────────────
    logger.error(
        f"All TTS methods failed for: '{text[:60]}'. "
        "Install Piper or ffmpeg for TTS support."
    )
    return b""


def preload_model() -> bool:
    """
    Verify the model is available at startup.
    Call this during application initialization to fail early.

    Returns:
        True if model is ready.
    """
    return check_model()
