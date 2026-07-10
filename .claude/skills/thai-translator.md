---
name: thai-translator
description: Thai Voice Translator — real-time speech-to-speech pipeline (Android → FastAPI → Whisper → Gemini → Piper TTS). Use for any work on this project: backend, Android, Colab, deployment, debugging.
---

# Thai Voice Translator — Project Skill 🇹🇭

Real-time speech-to-speech translation: Android captures system audio → WebSocket → FastAPI backend → Faster-Whisper ASR → Gemini Translate → Piper TTS → audio back to Android.

## Architecture

```
Android (Kotlin)                        Backend (FastAPI / Colab)
─────────────────                       ──────────────────────────
AudioPlaybackCapture                     WebSocket /ws/audio
  │ (PCM 16kHz mono, ~1s chunks)          │
  ▼                                         ▼
AudioCaptureService ──► OkHttp WS ──► whisper_service.transcribe_audio()
  (foreground service)                    │ (text)
                                          ▼
                                        translate_service.translate_to_thai()
                                          │ (Thai text)
                                          ▼
                                        tts_service.synthesize_speech()
                                          │ (PCM bytes)
                                          ▼
AudioPlayer.playChunk() ◄─── OkHttp WS ◄── WebSocket send_bytes()
  (AudioTrack + ducking)
```

## Tech Stack

| Layer | Technology |
|---|---|
| **ASR** | Faster-Whisper `base` · int8 · GPU T4 (Colab) |
| **Translation** | Gemini `gemini-1.5-flash` via OpenAI-compatible endpoint · LRU cache (50 entries) · 429 retry |
| **TTS** | Piper TTS (primary, `.onnx` model) · Google Translate TTS (fallback, needs ffmpeg) |
| **Backend** | FastAPI + WebSocket · Python 3.12 · uvicorn |
| **Android** | Kotlin · AudioPlaybackCapture API · OkHttp WS · AudioTrack · Foreground Service |
| **Deploy** | Colab (dev GPU) / Docker + Cloudflare Tunnel (prod on HP machine) |
| **Tunnel** | ngrok free tier (dev) → Cloudflare Tunnel (prod) |

## Project Structure

```
D:/AI_Thai/
├── android/                          # Kotlin Android app
│   └── app/src/main/java/com/thaivoice/translator/
│       ├── MainActivity.kt           # UI + service binding + diagnostics
│       ├── capture/
│       │   └── AudioCaptureService.kt # Foreground service, AudioPlaybackCapture
│       ├── audio/
│       │   ├── AudioRecorder.kt      # Microphone capture (v1, deprecated)
│       │   └── AudioPlayer.kt        # AudioTrack playback + ducking
│       └── network/
│           ├── TranslatorWebSocketClient.kt  # WS client + health check + reconnect
│           └── Diagnostics.kt                # 6-step pipeline diagnostic
├── backend/                          # FastAPI server
│   ├── main.py                       # FastAPI app + /health + /admin/* endpoints
│   ├── websocket.py                  # /ws/audio endpoint + pipeline orchestrator
│   ├── whisper_service.py            # Faster-Whisper ASR (singleton)
│   ├── translate_service.py          # Gemini translate + cache + retry
│   ├── tts_service.py                # Piper TTS + Google TTS fallback
│   ├── security.py                   # API key auth + rate limiting
│   ├── requirements.txt
│   ├── Dockerfile
│   └── docker-compose.yml
├── colab/
│   └── thai_translator_server.ipynb  # Colab notebook — 7 cells
├── models/                           # Piper voice model (.onnx + .json)
├── docs/
└── tests/
```

## Current State (2026-07-10)

### ✅ Working
- Android: AudioCaptureService, WebSocket, AudioPlayer, Diagnostics, status UI
- Backend: All endpoints, WebSocket pipeline, rate limiting, pipeline-status endpoint
- ASR: Whisper on Colab GPU (~150ms/chunk)
- TTS: Piper generating 30-67KB PCM per chunk (~0.15s)
- Colab: 7-cell notebook with auto-diagnostics

### ⚠️ Known Issues
- **ngrok free tier**: Session expires ~6-12h, URL changes each restart
- **Translation**: Currently hitting Gemini rate limit (2 RPM free tier for gemini-2.0-flash). Switched to gemini-1.5-flash + added LRU cache to mitigate.
- **"Already connected" warning**: `wireServiceCallbacks()` re-called on re-bind. Non-critical.

## Android App Details

### Connection Flow
```
App opens → bind AudioCaptureService
         → Health check (HTTP GET /health)
         → 🟢 "API ready · latency Xms"
         → WebSocket connect
         → 🟢 "Connected · ready for audio"
         → [Start Translation] enabled
```

### Status States (ConnectionStatus enum)
| State | Color | Meaning |
|---|---|---|
| IDLE | ⚫ Gray | Not started |
| CHECKING_API | 🟠 Orange | Running health check |
| API_READY | 🟢 Green | Server reachable |
| API_UNREACHABLE | 🔴 Red | Server down |
| CONNECTING | 🟠 Orange | WS handshake |
| CONNECTED | 🟢 Green | Ready for audio |
| DISCONNECTED | 🔴 Red | Connection lost |
| RECONNECTING | 🟠 Orange | Backoff retry N/5 |
| AUTH_FAILED | 🔴 Red | 401/403 — bad API key |

### Diagnostics (6 checks)
1. 🏥 Server Health — HTTP GET /health
2. 🔑 API Auth — HTTP GET /admin/rate-limits
3. 🔌 WebSocket Handshake — Open + close WS
4. 🎙️ ASR (Whisper) — Send test audio → verify result JSON
5. 🌐 Translation API — Verify `thai` field present
6. 🔊 TTS (Piper) — Verify `audio_bytes` field present

### Build & Install
```bash
cd D:/AI_Thai/android
./gradlew assembleDebug
adb -s 737a20bc install -r app/build/outputs/apk/debug/app-debug.apk
adb -s 737a20bc shell am start -n com.thaivoice.translator/.MainActivity
```

### BuildConfig (in `app/build.gradle.kts`)
```kotlin
buildConfigField("String", "BACKEND_SERVER_URL", "\"wss://comment-scrubber-ninja.ngrok-free.dev\"")
buildConfigField("String", "BACKEND_API_KEY", "\"k3EOTM10GxevuDLUHj7g7TwZ16nfiq8bBGqEG0hAMoA\"")
```

## Colab Server

### Notebook Cells (7 total)
| Cell | Purpose |
|---|---|
| 1 | `pip install` + `apt install ffmpeg` |
| 2 | Set API keys (BACKEND, TRANSLATE, model) |
| 3 | Download Piper model + binary |
| 4 | Load Whisper on GPU |
| 5 | Clone repo → start FastAPI (patches tts_service, translate_service, whisper_service) |
| 6 | ngrok tunnel → print URLs |
| 7 | Pipeline diagnostics (health, auth, WS, full check) |

### Key URLs (after Cell 6)
```
Health check:      https://<ngrok>/health
Rate limits:       https://<ngrok>/admin/rate-limits?api_key=...
Pipeline status:   https://<ngrok>/admin/pipeline-status?api_key=...
WebSocket:         wss://<ngrok>/ws/audio?api_key=...
```

### Environment Variables (Cell 2)
```python
os.environ["BACKEND_API_KEY"] = "k3EOTM10GxevuDLUHj7g7TwZ16nfiq8bBGqEG0hAMoA"
os.environ["TRANSLATE_API_KEY"] = "your-gemini-api-key-here"  # ← get from https://aistudio.google.com/apikey
os.environ["TRANSLATE_API_BASE"] = "https://generativelanguage.googleapis.com/v1beta/openai"
os.environ["TRANSLATE_MODEL"] = "gemini-1.5-flash"
os.environ["PIPER_MODEL_DIR"] = "/content/models"
os.environ["PIPER_MODEL_NAME"] = "th_TH"
```

## Backend API Reference

### GET /health
Open. Returns `{"status":"ok"}`. Used by Android health check + Docker monitoring.

### GET /admin/rate-limits?api_key=...
Protected. Returns `{"auth_enabled": true, "stats": {...}}`.

### GET /admin/pipeline-status?api_key=...
Protected. Returns per-service status:
```json
{
  "all_ready": true,
  "services": {
    "asr": {"ready": true, "detail": "Whisper model loaded"},
    "translate": {"ready": true, "detail": "Model: gemini-1.5-flash · Base: ...", "cache": {...}},
    "tts": {"ready": true, "method": "piper", "detail": "Piper TTS (high quality)"},
    "ffmpeg": {"ready": true, "detail": "available"}
  }
}
```

### WS /ws/audio?api_key=...
Binary protocol:
- Client → Server: Raw PCM 16kHz 16-bit mono (any size, typically ~32KB)
- Server → Client: JSON `{"type":"result","text":"...","thai":"...","audio_bytes":N,"timings":{...}}`
- Server → Client: Binary PCM audio (if TTS produced output)

## Commands

```bash
# Backend (local dev)
cd backend
pip install -r requirements.txt
uvicorn main:app --reload --host 0.0.0.0 --port 8000

# Backend (Docker — on HP machine)
docker compose up --build -d

# Android build
cd android
./gradlew assembleDebug

# Android logs (filtered)
adb -s 737a20bc logcat -s TranslatorWS:I MainActivity:* Diagnostics:*

# Android screenshot
adb -s 737a20bc exec-out screencap -p > screenshot.png

# Git
git push origin main  # Repo: ponsatornsam/thai-voice-translator
```

## Translation Service Internals

### LRU Cache
- Size: 50 entries (configurable via `TRANSLATE_CACHE_SIZE` env var)
- Normalizes keys (lowercase, trimmed)
- Stats available via `cache_stats()` and in `/admin/pipeline-status`

### 429 Retry Logic
- Max 2 retries (configurable via `TRANSLATE_MAX_RETRIES`)
- Parses `retryDelay` from Gemini error response
- Falls back to original text after exhausting retries (keeps pipeline running)

### Gemini API Note
- Free tier: ~1,500 requests/day
- gemini-1.5-flash has higher RPM limits than gemini-2.0-flash
- API key must be from https://aistudio.google.com/apikey (format: `AIzaSy...`)

## TTS Service Internals

### Primary: Piper TTS
- Requires: `piper` binary on PATH + `.onnx` voice model
- Model: `th_TH-medium.onnx` from HuggingFace
- Output: Raw PCM 16kHz 16-bit mono

### Fallback: Google Translate TTS
- Activated when Piper is unavailable
- Requires: `ffmpeg` on PATH (`apt install ffmpeg`)
- Downloads MP3 from `translate.google.com/translate_tts`
- Converts to PCM via ffmpeg subprocess
- Free, no API key, needs internet

## Troubleshooting

| Symptom | Cause | Fix |
|---|---|---|
| HTTP 404 on all endpoints | ngrok session expired | Re-run Colab Cells 5+6 |
| `text='Hello' thai='Hello'` | Gemini rate limited or API key invalid | Check API key format (`AIzaSy...`), wait for quota reset |
| `audio=0B` on every chunk | Piper model not downloaded | Re-run Colab Cell 3 |
| `Already connected` warning | Service re-bound, `wireServiceCallbacks()` called twice | Non-critical — add guard in MainActivity |
| `Max reconnect attempts (5) reached` | Backend unreachable after 5 retries | Check ngrok, restart Colab |
| GitHub push rejected | Secrets in committed files | Remove real API keys from `.env.example`, use placeholders |
| `asyncio.run()` error in Colab Cell 7 | Colab has running event loop | Fixed in latest notebook — re-clone from repo |

## Module History

The project was built following a 14-module prompt pack:
- **Phase 1 (Module 0–6)**: FastAPI, WebSocket, Whisper, Translation, Piper, Pipeline orchestration
- **Phase 2 (Module 9–11)**: Android AudioRecorder, WebSocket client, AudioPlayer
- **Phase 3 (Module 12)**: Latency optimization
- **Phase 4 (Module 7,8,13)**: Security, Docker, Cloudflare Tunnel deployment

v2 upgrade (2026-07-09): Migrated from microphone capture to AudioPlaybackCapture (system audio), added Foreground Service, Audio Focus ducking.

## Key Constraints

- **Heavy AI models** (Whisper, Piper) → run on Colab GPU during dev, Docker on HP machine in prod
- **Build APK / test audio** → must be on real device (Xiaomi 23129RAA4G, adb USB/WiFi)
- **Commit often** — Colab is ephemeral, uncommitted work may be lost
- **Environment variables** (never hardcode): `BACKEND_API_KEY`, `TRANSLATE_API_KEY`
- **CORS `*` only for dev** — restrict for production
- **ngrok free tier** — 6-12h session limit, URL changes each restart
