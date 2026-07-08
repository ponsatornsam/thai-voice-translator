# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project: Thai Voice Translator (Real-time Speech-to-Speech)

ระบบแปลเสียงพูดแบบเรียลไทม์ — พูดภาษาต้นทางผ่านแอป Android → ถอดเสียงด้วย Faster-Whisper → แปลเป็นไทยผ่าน API → สร้างเสียงไทยด้วย Piper TTS → เล่นกลับทันที

**Current state:** Documentation/setup phase. The actual source code (`backend/`, `android/`, etc.) has not been written yet. Development follows the 14-module prompt pack in `Firebase_Studio_AI_Prompts.md`, guided by AI agents.

## Development Workflow (Critical)

โปรเจกต์นี้ใช้ MCP (Model Context Protocol) เชื่อมระหว่าง GitHub Codespaces (เขียนโค้ด) และ Google Colab (รันโมเดล AI บน GPU ฟรี):

```
Claude Code / Copilot Agent  ──MCP──►  Google Colab (GPU T4)
       │                                      │
       │ เขียน/แก้โค้ด                         │ รัน Whisper, Piper
       ▼
  GitHub (เก็บโค้ด)
```

- **Codespaces/Claude Code** — เขียนโค้ด, รัน FastAPI, ทดสอบ WebSocket
- **Colab MCP (T4 GPU)** — โหลดและรันโมเดลหนัก (Faster-Whisper, Piper TTS) ระหว่างพัฒนาเท่านั้น
- **Production** — โมเดลทั้งหมดรันใน Docker บนเครื่อง HP ที่บ้าน ไม่พึ่ง Colab

Connection to Colab MCP does NOT persist across sessions — ต้องรีเซ็ตทุกครั้งที่เปิดเซสชันใหม่:
```bash
claude mcp add colab-proxy-mcp -- uvx git+https://github.com/googlecolab/colab-mcp
```

## Tech Stack

- **Backend:** FastAPI + WebSocket, Python 3.12
- **ASR:** Faster-Whisper (model `base`, compute `int8`)
- **Translation:** Gemini or OpenAI-compatible API
- **TTS:** Piper TTS (Thai voice model)
- **Android:** Kotlin — AudioRecord → OkHttp WebSocket → AudioTrack
- **Deploy:** Docker + Cloudflare Tunnel → HP All-in-One Windows machine

## Project Structure (Target)

```
project/
 ├── android/          # Kotlin: AudioRecorder, WebSocket client, AudioPlayer
 ├── backend/          # FastAPI: main.py, websocket.py, whisper_service.py,
 │                     #   translate_service.py, tts_service.py, security.py
 ├── models/           # Piper voice model files
 ├── tests/
 └── docs/             # Setup guides, prompt pack, deployment docs
```

## Architecture: Audio Pipeline

```
Android App                          Backend (FastAPI)
───────────                         ──────────────────
AudioRecord                          WebSocket /ws/audio
  │ (PCM 16kHz mono, 1-2s chunks)      │
  ▼                                     ▼
OkHttp WS ──── binary msg ────►  whisper_service.transcribe_audio()
                                       │ (text)
                                       ▼
                                   translate_service.translate_to_thai()
                                       │ (Thai text)
                                       ▼
                                   tts_service.synthesize_speech()
                                       │ (PCM bytes)
                                       ▼
AudioTrack ◄──── binary msg ────  WebSocket send_bytes()
```

## Module-Based Development (Firebase_Studio_AI_Prompts.md)

โค้ดถูกสร้างทีละโมดูลตามลำดับ 0–13 ห้ามข้ามโมดูล เพราะแต่ละอันอ้างอิงไฟล์ที่โมดูลก่อนหน้าสร้างไว้:

| Phase | Modules | Description |
|-------|---------|-------------|
| 1 | 0–5 | FastAPI + Whisper + Translation + Piper pipeline (🟢 = ใช้ Colab MCP, 🔵 = Codespaces อย่างเดียว) |
| 2 | 9–11 | Android app (Kotlin) — เขียนใน Codespaces ได้ แต่ build APK/ทดสอบเสียงต้องใช้ Android Studio บนเครื่องจริง |
| 3 | 12 | Latency optimization |
| 4 | 7,8,13 | Security + Docker + Deployment (Cloudflare Tunnel) |

## Key Constraints

- **งานหนัก (Whisper, Piper) ระหว่างพัฒนา** → ใช้ Colab MCP รันบน GPU เสมอ เติมท้าย prompt ว่า: "งานส่วนที่ต้องรันโมเดล AI หนัก ๆ ให้ใช้ Colab MCP รันบน GPU ของ Colab แทนการรันในเครื่อง Codespaces"
- **Build APK / ทดสอบ audio hardware** → ต้องทำบนเครื่องจริงหรือ CI เท่านั้น (Codespaces จำลองไม่ได้)
- **Commit บ่อย** — Codespaces เป็น cloud environment ชั่วคราว ข้อมูลที่ยังไม่ commit จะหายไปถ้า codespace ถูกลบ
- **Environment variables** (ห้าม hardcode): `BACKEND_API_KEY`, `TRANSLATE_API_KEY`
- **CORS เปิด `*` แค่ตอน dev** — ต้องจำกัด origin ตอน production

## Commands

```bash
# Backend
cd backend
pip install -r requirements.txt
uvicorn main:app --reload --host 0.0.0.0 --port 8000

# Run tests
pytest tests/

# Docker (production — on HP machine, not Codespaces)
docker compose up --build -d

# Claude Code MCP
claude mcp add colab-proxy-mcp -- uvx git+https://github.com/googlecolab/colab-mcp
claude mcp list          # ดู MCP servers ที่ตั้งค่าไว้
/mcp                     # ดูสถานะ MCP ในเซสชัน
```

## Associated Documents

- `00_Setup_MCP_Before_Project.md` — ขั้นตอน setup Codespaces + Colab MCP ก่อนเริ่มเขียนโค้ด
- `01_Setup_Claude_Code.md` — ตั้งค่า Claude Code เป็นทางเลือกแทน Copilot Agent
- `Firebase_Studio_AI_Prompts.md` — Prompt pack 14 โมดูลที่ใช้สั่ง AI สร้างระบบทั้งหมด (เริ่มจาก Module 0)
