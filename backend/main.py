"""
Thai Voice Translator — FastAPI Entry Point
===========================================
Real-time speech-to-speech translation pipeline:
  Android Audio → WebSocket → ASR (Whisper) → Translate → TTS (Piper) → Android Audio

Module 1: FastAPI application with health check, CORS, and WebSocket router placeholder.
Module 12: Startup model preloading + latency optimization strategies.
"""

import logging
import os

from fastapi import FastAPI, Depends
from fastapi.middleware.cors import CORSMiddleware

from websocket import router as ws_router
from security import require_api_key, get_rate_limit_stats  # Module 7

logger = logging.getLogger("translator.main")

# ── FastAPI App Instance ─────────────────────────────────────────────
app = FastAPI(
    title="Thai Voice Translator",
    description="Real-time speech-to-speech translation pipeline",
    version="0.1.0",
)

# ── CORS Middleware ──────────────────────────────────────────────────
# ⚠️ DEVELOPMENT ONLY: allow all origins.
# Before production (Module 7 + 13), restrict this to the real Android
# client origin and the Cloudflare Tunnel domain.
app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],            # ⚠️ เปลี่ยนเป็น domain จริงตอน production
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)

# ── Routes ───────────────────────────────────────────────────────────


@app.get("/health")
async def health_check():
    """Health-check endpoint for monitoring and Docker health checks.

    This endpoint is intentionally OPEN (no auth) so Docker and monitoring
    tools can check server status without needing an API key.
    """
    return {"status": "ok"}


@app.get("/admin/rate-limits")
async def rate_limit_diagnostics(
    ip: str | None = None,
    _=Depends(require_api_key),  # Protected: requires valid API key
):
    """
    Diagnostic endpoint — view current rate-limit status.

    Query params:
        ip (optional): Check a specific IP address.

    Requires valid API key via X-API-Key header or ?api_key= query param.
    """
    import security
    return {
        "auth_enabled": bool(security.BACKEND_API_KEY),
        "stats": security.get_rate_limit_stats(ip),
    }


# Mount WebSocket router — /ws/audio for real-time audio streaming
# WebSocket connections are authenticated via query parameter (?api_key=...)
# before accept() — see websocket.py and security.py (Module 7).
app.include_router(ws_router)


# ── Startup / Shutdown Events ──────────────────────────────────────────
# Module 12: Preload AI models at startup to avoid cold-start latency on
# the first request. Models are lazy-loaded singletons — this warm-up call
# forces the load now while the server is still "starting" from the user's
# perspective.


@app.on_event("startup")
async def startup_event():
    """
    Preload Whisper and Piper models at application startup.

    Without this, the first WebSocket chunk incurs a 2-5 second cold-start
    penalty while the ASR model loads. With preloading, the first chunk is
    processed at full speed.

    Failures are logged but non-fatal — the server still starts, and the
    models will retry loading on the first request.
    """
    logger.info("=" * 50)
    logger.info("Thai Voice Translator starting up...")

    # ── Preload Whisper ASR model ───────────────────────────────────────
    if not os.environ.get("WHISPER_DISABLED", "").strip() in ("1", "true", "yes"):
        try:
            from whisper_service import preload_model as preload_whisper
            import asyncio
            loop = asyncio.get_running_loop()
            ok = await loop.run_in_executor(None, preload_whisper)
            if ok:
                logger.info("✅ Whisper model preloaded")
            else:
                logger.warning("⚠️  Whisper model not preloaded — will retry on first request")
        except Exception as e:
            logger.warning(f"⚠️  Whisper preload failed (non-fatal): {e}")
    else:
        logger.info("⏭️  WHISPER_DISABLED=1 — skipping ASR model load")

    # ── Preload Piper TTS model ─────────────────────────────────────────
    try:
        from tts_service import preload_model as preload_piper
        import asyncio
        loop = asyncio.get_running_loop()
        ok = await loop.run_in_executor(None, preload_piper)
        if ok:
            logger.info("✅ Piper TTS model verified")
        else:
            logger.warning("⚠️  Piper model not found — download to models/ directory")
    except Exception as e:
        logger.warning(f"⚠️  Piper preload check failed (non-fatal): {e}")

    logger.info("=" * 50)


@app.on_event("shutdown")
async def shutdown_event():
    """Clean up resources on server shutdown."""
    logger.info("Thai Voice Translator shutting down...")
# Run with:
#   uvicorn main:app --reload --host 0.0.0.0 --port 8000
#
# Codespaces will auto-forward port 8000 — check the PORTS tab for the
# public URL, then visit /health to confirm {"status": "ok"}.
if __name__ == "__main__":
    import uvicorn

    uvicorn.run("main:app", host="0.0.0.0", port=8000, reload=True)


# ======================================================================
# FILE STRUCTURE NOTES (Module-by-Module)
# ======================================================================
#
# main.py            ← this file — FastAPI entry point, middleware, routes
# websocket.py       ← Module 2 — WebSocket /ws/audio endpoint
# whisper_service.py ← Module 3 — Faster-Whisper ASR (speech → text)
# translate_service.py ← Module 4 — Translation API (text → Thai text)
# tts_service.py     ← Module 5 — Piper TTS (Thai text → audio PCM)
# security.py        ← Module 7 — API key auth + rate limiting ✅
#
# Module 6  — Pipeline orchestration + async concurrency
# Module 8  — Dockerfile + docker-compose.yml
# Module 9  — Android: AudioRecorder.kt
# Module 10 — Android: TranslatorWebSocketClient.kt
# Module 11 — Android: AudioPlayer.kt
# Module 12 — Latency optimization
# Module 13 — Cloudflare Tunnel deployment
