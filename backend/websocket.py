"""
Thai Voice Translator — WebSocket Handler
==========================================
Module 2–7: WebSocket endpoint for real-time audio streaming with
full ASR → Translate → TTS pipeline, concurrent client support,
and API key authentication.

Architecture (Module 6):
  - CPU-bound tasks (Whisper ASR, Piper TTS) → run_in_executor
  - I/O-bound tasks (Translation API)         → async/await directly
  - Multiple clients handled concurrently      → FastAPI async WebSocket
  - Per-step latency logging                  → timing dict in response
  - Per-step error handling                   → errors as JSON, no disconnect
"""

import asyncio
import logging
import time
from functools import partial

from fastapi import APIRouter, WebSocket, WebSocketDisconnect

# Module 3: ASR via Faster-Whisper (CPU-bound)
from whisper_service import transcribe_audio

# Module 4: Translation to Thai (I/O-bound, async)
from translate_service import translate_to_thai

# Module 5: TTS via Piper (CPU-bound)
from tts_service import synthesize_speech

# Module 7: Security — API key auth + rate limiting for WebSocket
from security import require_api_key_ws

logger = logging.getLogger("translator.websocket")
router = APIRouter()

# ── CPU-bound helper ──────────────────────────────────────────────────


async def _run_cpu_bound(func, *args, timeout: float = 15.0):
    """
    Run a synchronous CPU-bound function in the default thread-pool executor.

    This prevents blocking the asyncio event loop, so other WebSocket
    clients continue to be served while one request runs ASR or TTS.

    Args:
        func: The synchronous function to run.
        *args: Arguments to pass.
        timeout: Max seconds to wait (default 15s).

    Returns:
        The function's return value, or None on timeout/error.
    """
    loop = asyncio.get_running_loop()
    try:
        return await asyncio.wait_for(
            loop.run_in_executor(None, partial(func, *args)),
            timeout=timeout,
        )
    except asyncio.TimeoutError:
        logger.error(f"CPU-bound task {func.__name__} timed out after {timeout}s")
        return None
    except Exception:
        logger.exception(f"CPU-bound task {func.__name__} failed")
        return None


# ── Pipeline ──────────────────────────────────────────────────────────


async def run_pipeline(audio_bytes: bytes) -> dict:
    """
    Execute the full speech-to-speech translation pipeline.

    Steps:
      1. ASR  (Whisper) → run in executor (CPU-bound)
      2. Translate       → async API call  (I/O-bound)
      3. TTS  (Piper)    → run in executor (CPU-bound)

    Each step is timed and errors are caught individually — a failure
    in one step does not break the rest of the pipeline.

    Args:
        audio_bytes: Raw PCM 16kHz mono audio chunk.

    Returns:
        dict with keys:
          - text:        original transcribed text (str)
          - thai:        Thai translation (str)
          - audio:       synthesized PCM audio bytes (bytes)
          - timings:     per-step elapsed seconds (dict)
          - errors:      per-step error messages, if any (list[str])
    """
    result = {
        "text": "",
        "thai": "",
        "audio": b"",
        "timings": {},
        "errors": [],
    }

    # ── Step 1: ASR (CPU-bound) ───────────────────────────────────────
    t0 = time.perf_counter()
    try:
        text = await _run_cpu_bound(transcribe_audio, audio_bytes, timeout=15.0)
        if text is None:
            result["errors"].append("ASR: transcription failed or timed out")
            text = ""
        result["text"] = text
        result["timings"]["asr"] = round(time.perf_counter() - t0, 3)
        logger.info(f"ASR: {result['timings']['asr']:.3f}s — '{text[:40]}'")
    except Exception:
        result["errors"].append(f"ASR: unexpected error")
        result["timings"]["asr"] = round(time.perf_counter() - t0, 3)
        logger.exception("ASR step failed")

    # ── Step 2: Translate (I/O-bound) ─────────────────────────────────
    t0 = time.perf_counter()
    try:
        if result["text"]:
            thai_text = await translate_to_thai(result["text"])
        else:
            thai_text = ""
        result["thai"] = thai_text
        result["timings"]["translate"] = round(time.perf_counter() - t0, 3)
        logger.info(
            f"TL:  {result['timings']['translate']:.3f}s — '{thai_text[:40]}'"
        )
    except Exception:
        result["errors"].append("Translate: API call failed")
        result["timings"]["translate"] = round(time.perf_counter() - t0, 3)
        logger.exception("Translation step failed")

    # ── Step 3: TTS (CPU-bound) ───────────────────────────────────────
    t0 = time.perf_counter()
    try:
        if result["thai"]:
            audio_out = await _run_cpu_bound(synthesize_speech, result["thai"], timeout=15.0)
            if audio_out is None:
                result["errors"].append("TTS: synthesis failed or timed out")
                audio_out = b""
        else:
            audio_out = b""
        result["audio"] = audio_out
        result["timings"]["tts"] = round(time.perf_counter() - t0, 3)
        logger.info(
            f"TTS: {result['timings']['tts']:.3f}s — "
            f"{len(audio_out)} bytes ({len(audio_out) / 32000:.1f}s PCM)"
        )
    except Exception:
        result["errors"].append("TTS: unexpected error")
        result["timings"]["tts"] = round(time.perf_counter() - t0, 3)
        logger.exception("TTS step failed")

    result["timings"]["total"] = round(
        sum(result["timings"].values()), 3
    )
    return result


# ── WebSocket Endpoint ────────────────────────────────────────────────


@router.websocket("/ws/audio")
async def audio_stream(websocket: WebSocket):
    """
    WebSocket endpoint — full real-time speech-to-speech pipeline.

    Protocol:
      Client → binary PCM (16kHz, 16-bit, mono)
      Server → JSON status → binary PCM audio (if TTS succeeded)

    JSON status format:
      {"type":"result","text":"...","thai":"...","audio_bytes":N,
       "timings":{"asr":0.0,"translate":0.0,"tts":0.0,"total":0.0},
       "errors":[...]}

    Multiple clients are handled concurrently by FastAPI's async WebSocket
    and run_in_executor for CPU-bound work — the event loop is never blocked.

    Authentication (Module 7):
      API key must be passed as a query parameter: /ws/audio?api_key=...
      Invalid/missing key → connection closed with code 4001 before accept().
      Rate limit exceeded → connection closed with code 4002 before accept().
    """
    # ── Security check (Module 7) ───────────────────────────────────────
    # Must be done BEFORE accept() — invalid clients are rejected immediately.
    if not await require_api_key_ws(websocket):
        return  # Connection already closed by security module

    await websocket.accept()
    client_ip = websocket.client.host if websocket.client else "unknown"
    logger.info(f"WebSocket client connected: {client_ip}")

    audio_buffer: list[bytes] = []

    try:
        while True:
            # ── Receive binary audio chunk ───────────────────────────
            audio_bytes: bytes = await websocket.receive_bytes()

            if not audio_bytes:
                logger.warning("Empty audio chunk, skipping")
                continue

            audio_buffer.append(audio_bytes)

            # ── Run full pipeline ─────────────────────────────────────
            result = await run_pipeline(audio_bytes)

            # ── Send JSON status to client ────────────────────────────
            status = {
                "type": "result",
                "received_bytes": len(audio_bytes),
                "text": result["text"],
                "thai": result["thai"],
                "audio_bytes": len(result["audio"]),
                "timings": result["timings"],
                "errors": result["errors"] if result["errors"] else None,
            }
            await websocket.send_json(status)

            # ── Send synthesized audio as binary (if available) ──────
            if result["audio"]:
                await websocket.send_bytes(result["audio"])
                logger.debug(f"Sent {len(result['audio'])} bytes audio")

    except WebSocketDisconnect:
        logger.info(
            f"Client {client_ip} disconnected. "
            f"Chunks: {len(audio_buffer)}, "
            f"Total audio: {sum(len(b) for b in audio_buffer)} bytes"
        )

    except asyncio.CancelledError:
        logger.info(f"Client {client_ip} connection cancelled")
        # Clean up if possible
        try:
            await websocket.close(code=1000)
        except Exception:
            pass

    except Exception:
        logger.exception(f"Unexpected error for client {client_ip}")
        # Send error to client before closing (best-effort)
        try:
            await websocket.send_json({
                "type": "error",
                "message": "Internal server error — closing connection",
            })
            await websocket.close(code=1011, reason="Internal server error")
        except Exception:
            pass  # Already disconnected
