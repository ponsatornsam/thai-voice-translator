"""
Thai Voice Translator — WebSocket Handler
==========================================
Module 2 + 3 + 4: WebSocket endpoint for receiving real-time audio chunks (PCM 16kHz mono)
from the Android client over a persistent connection.

Pipeline (current):
  Android ──binary PCM──► /ws/audio
                            ├── whisper_service.transcribe_audio()  (Module 3)
                            ├── translate_service.translate_to_thai() (Module 4)
                            └──► JSON ack + transcribed + translated text
"""

import logging
from fastapi import APIRouter, WebSocket, WebSocketDisconnect

# Module 3: ASR via Faster-Whisper
from whisper_service import transcribe_audio

# Module 4: Translation to Thai
from translate_service import translate_to_thai

# Module logger
logger = logging.getLogger("translator.websocket")

# ── Router ───────────────────────────────────────────────────────────
router = APIRouter()


# ── WebSocket Endpoint ───────────────────────────────────────────────
@router.websocket("/ws/audio")
async def audio_stream(websocket: WebSocket):
    """
    WebSocket endpoint for real-time audio streaming.

    Client sends:   binary PCM audio chunks (16kHz, 16-bit, mono)
    Server replies:  JSON ack for each chunk → {"type": "ack", "received_bytes": N}

    The collected audio is buffered in memory; a future pipeline (Module 3–6)
    will feed it through ASR → Translation → TTS → send audio back.
    """
    await websocket.accept()
    logger.info("WebSocket client connected: /ws/audio")

    # ── In-memory buffer (accumulates audio chunks for the session) ──
    audio_buffer: list[bytes] = []

    try:
        while True:
            # ── Receive binary audio chunk ───────────────────────────
            audio_bytes: bytes = await websocket.receive_bytes()

            if not audio_bytes:
                logger.warning("Received empty audio chunk, skipping")
                continue

            # Store in buffer for future pipeline use
            audio_buffer.append(audio_bytes)

            # ── Step 1: ASR (Module 3: Faster-Whisper) ──────────────
            text = transcribe_audio(audio_bytes)
            logger.info(f"ASR: '{text[:60]}{'...' if len(text) > 60 else ''}'")

            # ── Step 2: Translate to Thai (Module 4) ──────────────
            thai_text = await translate_to_thai(text) if text else ""
            logger.info(f"TH:  '{thai_text[:60]}{'...' if len(thai_text) > 60 else ''}'")

            # ── Send result back to client ────────────────────────
            ack = {
                "type": "result",
                "received_bytes": len(audio_bytes),
                "text": text,           # Original transcribed text
                "thai": thai_text,      # Thai translation
            }
            await websocket.send_json(ack)

            logger.debug(f"Sent ack: {len(audio_bytes)} bytes")

    except WebSocketDisconnect:
        logger.info(
            f"WebSocket client disconnected. "
            f"Total chunks received: {len(audio_buffer)}, "
            f"Total audio: {sum(len(b) for b in audio_buffer)} bytes"
        )
        # Buffer is released when the function exits — memory is freed.

    except Exception:
        logger.exception("Unexpected error in WebSocket /ws/audio")
        # Attempt to close cleanly if still connected
        try:
            await websocket.close(code=1011, reason="Internal server error")
        except Exception:
            pass  # Already disconnected, nothing to do
