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


# ============================================================================
# Module 12: Latency Optimization — Pipeline Overlap Strategies
# ============================================================================
#
# ## Current Latency Breakdown (sequential pipeline, ~1s chunk)
#
#   Step         Typical (CPU)    Typical (GPU T4)    Bound
#   ─────────    ─────────────    ────────────────    ──────
#   ASR           0.8–2.0 s         0.2–0.5 s        CPU
#   Translate     0.3–1.0 s         0.3–1.0 s        Network (API)
#   TTS           0.2–0.8 s         0.2–0.5 s        CPU
#   ─────────────────────────────────────────────────
#   TOTAL         1.3–3.8 s         0.7–2.0 s
#
# Target: < 2.0s end-to-end latency for natural conversation feel.
#
# ## Strategy A: Inter-Chunk Pipelining (recommended — implement first)
#
# Process chunk N+1's ASR while chunk N's Translate+TTS run:
#
#   Chunk 0: [ASR]  →  [Translate]  →  [TTS]
#   Chunk 1:           [ASR]  →  [Translate]  →  [TTS]
#   Chunk 2:                      [ASR]  →  [Translate]  →  [TTS]
#
# Because ASR is CPU-bound (executor thread) and Translate is I/O-bound
# (async await), they naturally overlap when launched as separate tasks.
#
# Expected improvement: ~30–40% latency reduction (ASR hidden behind Translate+TTS).
#
# TODO(Module 12): Implement inter-chunk pipelining
#   1. Replace the sequential await in audio_stream() with asyncio.create_task():
#      - Start pipeline_task(chunk_0) as a background task
#      - Immediately receive chunk_1, start pipeline_task(chunk_1)
#      - Use asyncio.gather() or a task queue to collect results
#   2. Be careful: ASR+TTS both use run_in_executor (default thread pool).
#      With multiple concurrent chunks, the thread pool may saturate.
#      Increase ThreadPoolExecutor max_workers or use dedicated pools.
#   3. Track chunk ordering — results may complete out-of-order.
#      Send audio back with a sequence number so the client can reorder.
#
# ## Strategy B: Streaming ASR (partial transcripts)
#
# Faster-Whisper returns segments via a generator. We can start translating
# the first segment while later segments are still being transcribed:
#
#   ASR: [seg1] [seg2] [seg3]
#   TL:         [seg1] [seg2] [seg3]
#   TTS:               [seg1] [seg2] [seg3]
#
# This requires modifying whisper_service.py to yield segments instead of
# joining them into a single string.
#
# TODO(Module 12): Streaming ASR in whisper_service.py
#   1. Add transcribe_audio_streaming(audio_bytes) -> AsyncGenerator[str, None]
#   2. Modify run_pipeline() to iterate segments and start TL/TTS earlier
#   3. Merge TTS output for all segments before sending to client
#
# ## Strategy C: Dedicated Thread Pools
#
# Currently both ASR and TTS share the default ThreadPoolExecutor.
# On multi-core machines (HP All-in-One probably has 4–8 cores), dedicated
# pools allow ASR and TTS to run truly in parallel on different cores.
#
# TODO(Module 12): Dedicated executor pools
#   1. Create separate ThreadPoolExecutor instances:
#      - asr_executor (max_workers=2) for Whisper
#      - tts_executor (max_workers=2) for Piper
#   2. Update _run_cpu_bound() to accept an optional executor parameter
#   3. This prevents TTS from queuing behind ASR when multiple clients connect
#
# ## Strategy D: Model Quantization + Compute Type
#
# Current config: Faster-Whisper "base" model, int8 quantisation.
#   - "tiny" model: 2× faster, ~5% worse accuracy (worth testing)
#   - "int8_float16" compute_type: faster on some GPUs
#   - Piper "low" quality model: smaller .onnx, faster inference
#
# TODO(Module 12): Experiment with model sizes
#   1. Test "tiny" vs "base" Whisper on Colab GPU → accuracy vs speed trade-off
#   2. Test Piper low vs medium quality → audio quality vs speed
#   3. Make model size configurable via env var (WHISPER_MODEL_SIZE, PIPER_QUALITY)
#
# ============================================================================


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
