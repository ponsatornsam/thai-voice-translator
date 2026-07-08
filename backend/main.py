"""
Thai Voice Translator — FastAPI Entry Point
===========================================
Real-time speech-to-speech translation pipeline:
  Android Audio → WebSocket → ASR (Whisper) → Translate → TTS (Piper) → Android Audio

Module 1: FastAPI application with health check, CORS, and WebSocket router placeholder.
"""

from fastapi import FastAPI
from fastapi.middleware.cors import CORSMiddleware

# TODO (Module 2): Uncomment when websocket.py is created
# from websocket import router as ws_router

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
    """Health-check endpoint for monitoring and Docker health checks."""
    return {"status": "ok"}


# TODO (Module 2): Mount the WebSocket router once websocket.py exists.
# app.include_router(ws_router)


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
# security.py        ← Module 7 — API key auth + rate limiting
#
# Module 6  — Pipeline orchestration + async concurrency
# Module 8  — Dockerfile + docker-compose.yml
# Module 9  — Android: AudioRecorder.kt
# Module 10 — Android: TranslatorWebSocketClient.kt
# Module 11 — Android: AudioPlayer.kt
# Module 12 — Latency optimization
# Module 13 — Cloudflare Tunnel deployment
