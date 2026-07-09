"""
Thai Voice Translator — FastAPI Entry Point
===========================================
Real-time speech-to-speech translation pipeline:
  Android Audio → WebSocket → ASR (Whisper) → Translate → TTS (Piper) → Android Audio

Module 1: FastAPI application with health check, CORS, and WebSocket router placeholder.
"""

from fastapi import FastAPI, Depends
from fastapi.middleware.cors import CORSMiddleware

from websocket import router as ws_router
from security import require_api_key, get_rate_limit_stats  # Module 7

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


# ── Entry Point ──────────────────────────────────────────────────────
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
