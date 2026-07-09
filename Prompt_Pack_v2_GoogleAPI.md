# Prompt Pack v2: แอปแปลเสียง YouTube เป็นไทยอัตโนมัติ (ใช้ Google API + ดักเสียงระบบ)

> ⚠️ **หมายเหตุ (2026-07-09):** โปรเจกต์นี้ถูกปรับเป็น **Hybrid v1+v2** แล้ว:
> - **Backend** ใช้ของฟรีจาก v1 (Faster-Whisper + แปล API + Piper TTS) — ไม่มีค่าใช้จ่าย
> - **Android** ใช้ v2 เต็มรูปแบบ (AudioPlaybackCapture + Foreground Service + Audio Focus Ducking)
> - **Module 0 (Google Cloud)** ถูกข้ามไป — ไม่ต้องใช้ Google Cloud Console, Billing, หรือ Service Account
> - **Modules 4-6 (Google STT/Translate/TTS)** ถูกแทนที่ด้วย v1 backend ที่มีอยู่แล้ว:
>   - `asr_service.py` → `whisper_service.py` (Faster-Whisper, ฟรี)
>   - `translate_service.py` → `translate_service.py` (Gemini/OpenAI API, มี free tier)
>   - `tts_service.py` → `tts_service.py` (Piper TTS, ฟรี)
> - โค้ด Android v2 (AudioCaptureService, MainActivity, AudioPlayer+ducking) ถูกสร้างแล้ว
> - ดู implementation จริงได้ที่ `android/app/src/main/java/com/thaivoice/translator/`

> เวอร์ชันนี้แทนที่ไฟล์ `Firebase_Studio_AI_Prompts.md` เดิมทั้งหมด
> เปลี่ยนแปลงหลักจากเวอร์ชันก่อน:
> - ASR/แปลภาษา/TTS ใช้ **Google Cloud API** แทน Whisper/Piper self-host → **ไม่ต้องใช้ Colab MCP อีกต่อไป** (ไม่มีโมเดลหนักให้รันเอง)
> - ฝั่ง Android เปลี่ยนจาก "อัดไมค์" เป็น **ดักเสียงระบบ (AudioPlaybackCapture) + Foreground Service + Audio Focus Ducking**
> - ใช้ **Kotlin native** (ไม่ใช่ Expo) เพราะฟีเจอร์หลักต้องพึ่ง native API ระดับลึก
> - ยังคงใช้ GitHub Codespaces หรือ Claude Code เป็น coding environment ตามเอกสาร `00_Setup_MCP_Before_Project.md` / `01_Setup_Claude_Code.md` — แต่ **ข้าม Colab MCP ได้เลย** ไม่จำเป็นสำหรับสถาปัตยกรรมนี้แล้ว ใช้แค่ command-line Android build tools ตาม `02_Setup_Android_Build_Tools.md`

---

## วิธีใช้เอกสารนี้

1. เริ่มจาก **Module 0** ไล่ลงไปทีละโมดูล ห้ามข้าม
2. ทุก prompt สั่งใน Copilot Chat (Agent mode) หรือ Claude Code ก็ได้
3. หลัง AI สร้างโค้ดแต่ละโมดูล ให้ทดสอบก่อนไปโมดูลถัดไปเสมอ
4. โมดูลฝั่ง Android (9-12) **ต้อง build + test บนมือถือจริงผ่าน adb** ตามเอกสาร `02_Setup_Android_Build_Tools.md` — เขียนโค้ดใน Codespaces/Claude Code ได้ แต่ทดสอบผลจริงต้องใช้เครื่องจริงเท่านั้น

### โครงสร้างโปรเจกต์

```
project/
 ├── android/
 │    └── app/src/main/java/.../
 │         ├── capture/       (AudioPlaybackCapture, Foreground Service)
 │         ├── network/       (WebSocket client)
 │         └── playback/      (AudioTrack, Audio Focus ducking)
 ├── backend/
 │   ├── main.py
 │   ├── websocket.py
 │   ├── asr_service.py        (Google Cloud Speech-to-Text)
 │   ├── translate_service.py  (Google Cloud Translation / Gemini API)
 │   ├── tts_service.py        (Google Cloud Text-to-Speech)
 │   ├── security.py
 │   ├── requirements.txt
 │   ├── Dockerfile
 │   ├── docker-compose.yml
 │   └── credentials/          (service account key — ห้าม commit ขึ้น GitHub)
 ├── tests/
 └── docs/
```

### ลำดับการพัฒนา (Roadmap)

| Phase | โมดูล |
|---|---|
| 1 | ตั้งค่า Google Cloud Project + เปิด API |
| 2 | Backend: FastAPI + WebSocket + Google STT/Translate/TTS |
| 3 | Android: ดักเสียงระบบ + ส่งไป backend + เล่นเสียงแปลกลับพร้อม ducking |
| 4 | Security + Docker + Deployment |

---

## Module 0 — ตั้งค่า Google Cloud Project (ทำนอก AI agent)

> ⚠️ **ข้ามโมดูลนี้ได้** — โปรเจกต์นี้ใช้ของฟรี (Faster-Whisper + Piper TTS) แทน Google Cloud API แล้ว
> ข้ามไป **Module 1** โดยใช้ backend จาก v1 ที่มีอยู่แล้วได้เลย

<details>
<summary>📜 คำแนะนำเดิม (สำหรับ Google Cloud API — ไม่ใช้แล้ว)</summary>

ก่อนสั่ง agent เขียนโค้ด ต้องเตรียมสิ่งนี้เองก่อน (ทำผ่านเว็บ Google Cloud Console):

1. สร้างโปรเจกต์ใหม่ที่ [console.cloud.google.com](https://console.cloud.google.com)
2. เปิดใช้งาน API 3 ตัว: **Cloud Speech-to-Text API**, **Cloud Translation API**, **Cloud Text-to-Speech API**
3. เปิด Billing (Google APIs พวกนี้มีค่าใช้จ่ายตามการใช้งานจริง ไม่มี tier ฟรีถาวรสำหรับ streaming)
4. สร้าง Service Account → สร้าง key เป็นไฟล์ JSON → ดาวน์โหลดเก็บไว้
5. **ห้าม commit ไฟล์ JSON นี้ขึ้น GitHub เด็ดขาด** — เก็บไว้ใน `backend/credentials/` แล้วเพิ่มใน `.gitignore`

</details>

---

## Module 1 — Setup โครงสร้างโปรเจกต์และ requirements.txt

**บริบทให้แปะก่อน prompt:**
> กำลังสร้างระบบดักเสียงจากแอปอื่น (เช่น YouTube) บน Android แล้วแปลเป็นเสียงไทยส่งกลับผ่านหูฟังแบบเรียลไทม์ Backend เป็น FastAPI ใช้ Google Cloud API ทั้งหมด (Speech-to-Text, Translation, Text-to-Speech) ไม่ได้ self-host โมเดลเอง

**Prompt:**
```
สร้างโครงสร้างโปรเจกต์เริ่มต้นตามนี้:

project/
 ├── android/
 ├── backend/
 │    └── credentials/
 ├── tests/
 └── docs/

ในโฟลเดอร์ backend/ สร้างไฟล์ requirements.txt ที่มี dependencies:
- fastapi
- uvicorn[standard]
- google-cloud-speech
- google-cloud-translate
- google-cloud-texttospeech
- python-jose[cryptography]
- websockets

สร้างไฟล์ backend/.gitignore ใส่ credentials/ และ .env ไว้ในนั้น เพื่อไม่ให้ commit key หลุด

อย่าเพิ่งเขียน logic ใด ๆ แค่สร้างโครงสร้างและไฟล์เหล่านี้เท่านั้น
```

**ผลลัพธ์ที่คาดหวัง:** โฟลเดอร์ครบ + requirements.txt + .gitignore ป้องกัน credentials หลุด

---

## Module 2 — FastAPI พื้นฐาน (main.py)

**บริบทให้แปะก่อน prompt:**
> มีโครงสร้างโปรเจกต์แล้ว ตอนนี้จะสร้าง entry point ของ FastAPI

**Prompt:**
```
สร้างไฟล์ backend/main.py เป็น FastAPI application เริ่มต้น โดยมี:
1. FastAPI app instance ชื่อ app
2. GET endpoint /health ที่ return {"status": "ok"}
3. โหลด environment variable GOOGLE_APPLICATION_CREDENTIALS ชี้ไปที่ path ของไฟล์ JSON service account ตอน startup พร้อม log แจ้งถ้าไม่เจอไฟล์
4. CORS middleware เปิดให้ทุก origin (dev เท่านั้น มี comment เตือนเรื่อง production)
5. โครงสำหรับ mount websocket router จาก websocket.py (placeholder import ไว้ก่อน)
6. รันด้วย: uvicorn main:app --reload --host 0.0.0.0 --port 8000
```

**ผลลัพธ์ที่คาดหวัง:** รันแล้วเข้า `/health` ได้ `{"status":"ok"}` และ log ยืนยันเจอไฟล์ credentials

---

## Module 3 — WebSocket Handler (websocket.py)

**บริบทให้แปะก่อน prompt:**
> มี main.py พร้อมแล้ว ตอนนี้เพิ่ม WebSocket endpoint รับเสียง PCM จาก Android เป็น chunk ต่อเนื่อง (16kHz mono)

**Prompt:**
```
สร้างไฟล์ backend/websocket.py โดยมี:
1. APIRouter ชื่อ router
2. WebSocket endpoint ที่ path /ws/audio
3. รับข้อมูลเสียงแบบ binary (bytes) จาก client เป็น loop ต่อเนื่อง
4. try/except ครอบ WebSocketDisconnect
5. ฟังก์ชัน placeholder ชื่อ process_audio_chunk(audio_bytes) ที่ print ขนาดข้อมูลที่รับมา (ยังไม่ต่อ Google API ตอนนี้)
6. ส่ง ack กลับเป็น JSON {"type": "ack", "received_bytes": ...}

แล้วแก้ backend/main.py ให้ include_router() จริง
```

**ผลลัพธ์ที่คาดหวัง:** ทดสอบด้วย WebSocket client ส่ง binary เข้า `/ws/audio` แล้วได้ ack กลับ

---

## Module 4 — Google Cloud Speech-to-Text (asr_service.py)

**บริบทให้แปะก่อน prompt:**
> มี websocket.py รับเสียงแล้ว ตอนนี้จะถอดเสียงด้วย Google Cloud Speech-to-Text แบบ streaming (ไม่ใช่ Whisper self-host) รองรับภาษาต้นทางที่ไม่ใช่ไทย (เช่น อังกฤษ) ให้ auto-detect หรือกำหนดภาษาต้นทางไว้ล่วงหน้า

**Prompt:**
```
สร้างไฟล์ backend/asr_service.py โดยมี:
1. ใช้ library google-cloud-speech สร้าง SpeechClient
2. ฟังก์ชัน transcribe_stream(audio_generator) ที่รับ generator ของ audio chunk (bytes) แล้วส่งเข้า Google Cloud Speech-to-Text แบบ streaming recognize
3. ตั้งค่า RecognitionConfig: encoding=LINEAR16, sample_rate_hertz=16000, language_code="en-US" (ปรับได้ผ่าน parameter), enable_automatic_punctuation=True
4. คืนค่าเป็น async generator ที่ yield ข้อความที่ถอดได้ทีละ segment (interim + final results แยกกันได้ยิ่งดี เพื่อ latency ต่ำ)
5. จัดการ error กรณี stream หลุดหรือเสียงเงียบนานเกินไป ให้ reconnect stream ใหม่อัตโนมัติ

แล้วแก้ backend/websocket.py ให้เรียกใช้ transcribe_stream() แทน process_audio_chunk() เดิม แล้ว print ข้อความที่ถอดได้ (ยังไม่ต้องแปลภาษาต่อ)
```

**ผลลัพธ์ที่คาดหวัง:** ส่งเสียงพูดภาษาอังกฤษเข้า WebSocket แล้วเห็นข้อความที่ถอดได้ (ผ่าน Google API จริง มีค่าใช้จ่ายเล็กน้อยตอนทดสอบ)

---

## Module 5 — Translation Service (translate_service.py)

**บริบทให้แปะก่อน prompt:**
> มี asr_service.py ถอดเสียงเป็นข้อความแล้ว ตอนนี้แปลเป็นไทยด้วย Google Cloud Translation API

**Prompt:**
```
สร้างไฟล์ backend/translate_service.py โดยมี:
1. ใช้ library google-cloud-translate สร้าง TranslationServiceClient
2. ฟังก์ชัน translate_to_thai(text: str, source_lang: str = "en") -> str เรียก translate_text() ของ Google Cloud Translation API แปลเป็น target_language_code="th"
3. ใส่ fallback: ถ้าเรียก API ไม่สำเร็จ (exception/timeout) ให้ return ข้อความต้นฉบับพร้อม log error แทนที่จะทำให้ระบบล่ม
4. ตั้ง timeout ไม่เกิน 5 วินาที

แล้วแก้ backend/websocket.py ให้เรียก translate_to_thai() ต่อจาก transcribe_stream() แล้ว print ข้อความไทยที่แปลได้
```

**ผลลัพธ์ที่คาดหวัง:** ข้อความอังกฤษที่ถอดได้ถูกแปลเป็นไทยและ print ออกมา

---

## Module 6 — Google Cloud Text-to-Speech (tts_service.py)

**บริบทให้แปะก่อน prompt:**
> มี translate_service.py แปลเป็นไทยแล้ว ตอนนี้สร้างเสียงพูดไทยด้วย Google Cloud Text-to-Speech

**Prompt:**
```
สร้างไฟล์ backend/tts_service.py โดยมี:
1. ใช้ library google-cloud-texttospeech สร้าง TextToSpeechClient
2. ฟังก์ชัน synthesize_speech(text: str) -> bytes ที่:
   - เรียก synthesize_speech() ของ Google Cloud TTS
   - ใช้เสียงภาษาไทย (VoiceSelectionParams language_code="th-TH") เลือกเสียงคุณภาพสูงสุดที่มี (Neural2 หรือ WaveNet ถ้ามี)
   - ตั้ง AudioConfig เป็น LINEAR16, sample_rate_hertz=16000 ให้ตรงกับที่ Android ต้องการ
   - return audio bytes
3. จัดการ error กรณีข้อความว่าง ให้ return bytes ว่างพร้อม log warning

แล้วแก้ backend/websocket.py ให้เรียก synthesize_speech() ต่อจากขั้นตอนแปลภาษา แล้วส่งเสียงกลับไปหา client ผ่าน websocket.send_bytes()

ตอนนี้ pipeline เต็มควรเป็น: รับเสียง -> Google STT -> Google Translate -> Google TTS -> ส่งกลับ
```

**ผลลัพธ์ที่คาดหวัง:** ได้ยินเสียงไทยที่สร้างจาก Google TTS กลับมาแบบ end-to-end

---

## Module 7 — รวม Pipeline และจัดการ Concurrency

**บริบทให้แปะก่อน prompt:**
> Pipeline เต็ม (STT -> Translate -> TTS) ทำงานได้แล้ว ตอนนี้จัดระเบียบโค้ดและรองรับหลาย client พร้อมกัน

**Prompt:**
```
ปรับปรุง backend/websocket.py ให้:
1. แยก logic pipeline ออกเป็นฟังก์ชันเดียวชื่อ run_pipeline(audio_stream) ที่เรียก asr_service, translate_service, tts_service ตามลำดับ แบบ streaming (ไม่ต้องรอเสียงจบทั้งหมดก่อนเริ่มแปล ถ้า Google STT streaming คืนผลบางส่วนมาก่อนได้)
2. ใช้ asyncio ให้ endpoint /ws/audio รองรับหลาย connection พร้อมกันโดยไม่บล็อกกัน
3. เพิ่ม logging บันทึกเวลาที่ใช้ในแต่ละขั้นตอน (STT/Translate/TTS/network) เพื่อดู bottleneck
4. เพิ่มการจัดการ error กลาง ๆ ถ้าขั้นตอนใดล้มเหลว (เช่น Google API quota เกิน) ให้ส่ง JSON error message กลับไปหา client แทนที่จะตัดการเชื่อมต่อ
```

**ผลลัพธ์ที่คาดหวัง:** ระบบรองรับหลาย client พร้อมกัน มี log latency แต่ละขั้นตอน

---

## Module 8 — Security + Docker

**บริบทให้แปะก่อน prompt:**
> Backend ทำงานครบแล้ว ตอนนี้เพิ่มความปลอดภัยและ containerize

**Prompt:**
```
สร้างไฟล์ backend/security.py โดยมี:
1. ฟังก์ชันตรวจสอบ API Key จาก query parameter หรือ header เทียบกับ environment variable BACKEND_API_KEY
2. Dependency สำหรับ FastAPI endpoint ปกติ
3. สำหรับ WebSocket ตรวจสอบ API key จาก query parameter ก่อน accept() ถ้าไม่ถูกต้องปิดด้วย code 4001
4. Rate limiting เบื้องต้นแบบ in-memory จำกัดจำนวน connection ต่อ IP ต่อนาที

แล้วแก้ backend/websocket.py และ main.py ให้ใช้การตรวจสอบนี้

จากนั้นสร้าง backend/Dockerfile:
1. python:3.12-slim
2. copy requirements.txt แล้ว pip install
3. copy โค้ดทั้งหมด (ยกเว้น credentials/ ที่ mount แยกตอน runtime)
4. expose port 8000
5. CMD รัน uvicorn main:app --host 0.0.0.0 --port 8000

สร้าง backend/docker-compose.yml:
1. service ชื่อ backend build จาก Dockerfile
2. map port 8000:8000
3. mount volume backend/credentials/ เข้า container (read-only)
4. ส่ง environment variable ผ่าน .env file (BACKEND_API_KEY, GOOGLE_APPLICATION_CREDENTIALS ชี้ path ใน container)
5. restart policy unless-stopped

สร้าง backend/.env.example เป็นตัวอย่าง (ไม่ใส่ค่าจริง)
```

**ผลลัพธ์ที่คาดหวัง:** `docker compose up --build` รันได้ครบ มี auth + rate limit ทำงาน

---

## Module 9 — Android: ขอ Permission ดักเสียงระบบ + Foreground Service

**บริบทให้แปะก่อน prompt:**
> เริ่มฝั่ง Android ด้วย Kotlin native ฟีเจอร์หลักคือดักเสียงจากแอปอื่น (เช่น YouTube) ผ่าน AudioPlaybackCapture API (Android 10+) ต้องรันเป็น Foreground Service ตลอดเวลา และขอ user consent ผ่าน MediaProjection prompt ก่อน

**Prompt:**
```
สร้างไฟล์ Kotlin ในโฟลเดอร์ android/app/src/main/java/.../capture/ ชื่อ AudioCaptureService.kt โดยมี:
1. Foreground Service class ที่ประกาศ foregroundServiceType="mediaProjection" ใน AndroidManifest.xml
2. แสดง persistent notification ตอน service ทำงาน (ข้อความ เช่น "กำลังแปลเสียงอยู่") ปิดจาก notification ไม่ได้ ต้องสั่งหยุดจากในแอป
3. ฟังก์ชันรับ MediaProjection token ที่ได้จาก MediaProjectionManager.createScreenCaptureIntent() (เรียก request permission จาก Activity แยกต่างหาก ไม่ใช่ใน Service)
4. สร้าง AudioPlaybackCaptureConfiguration ที่ addMatchingUsage(AudioAttributes.USAGE_MEDIA) เพื่อดักเฉพาะเสียงจากแอปประเภทสื่อ (เช่น YouTube) ไม่ดักเสียงระบบอื่น
5. สร้าง AudioRecord ที่ผูกกับ config ข้างต้น ตั้ง sample rate 16000Hz, mono, PCM 16-bit
6. เปิด/ปิดการอ่านเสียงเป็น chunk ทุก 1-2 วินาที ผ่าน callback ที่โมดูลถัดไปจะเอาไปส่งต่อ

สร้างไฟล์ Kotlin ชื่อ MainActivity.kt (หรือแก้ของเดิม) เพิ่ม:
1. ปุ่ม "เริ่มแปลเสียง" ที่เรียก MediaProjectionManager.createScreenCaptureIntent() แสดง prompt ยืนยันจากระบบ
2. รับผลจาก ActivityResultContracts แล้วส่ง token ต่อให้ AudioCaptureService เริ่มทำงาน
3. เพิ่ม permission ที่จำเป็นใน AndroidManifest.xml: FOREGROUND_SERVICE, FOREGROUND_SERVICE_MEDIA_PROJECTION, POST_NOTIFICATIONS (Android 13+)
```

**ผลลัพธ์ที่คาดหวัง:** กดปุ่มแล้วขึ้น system prompt ยืนยันแบบเดียวกับอัดหน้าจอ กดยอมรับแล้ว service เริ่มทำงานพร้อม notification ค้าง

---

## Module 10 — Android: WebSocket Client ส่ง/รับเสียง

**บริบทให้แปะก่อน prompt:**
> มี AudioCaptureService.kt ที่ให้เสียงเป็น chunk ByteArray แล้ว ตอนนี้ส่งไป backend ผ่าน WebSocket และรับเสียงไทยกลับมา

**Prompt:**
```
สร้างไฟล์ Kotlin ชื่อ TranslatorWebSocketClient.kt (โฟลเดอร์ network/) โดยมี:
1. OkHttp WebSocket client เชื่อมไปยัง URL backend (ws://... หรือ wss://... ต่อ API key เป็น query parameter)
2. ฟังก์ชัน connect() เปิด connection ตั้ง WebSocketListener
3. ฟังก์ชัน sendAudioChunk(chunk: ByteArray) ส่งแบบ binary message
4. onMessage สำหรับ binary → callback onAudioReceived: (ByteArray) -> Unit
5. onMessage สำหรับ text (JSON ack/error) → log ไว้ debug
6. Reconnect อัตโนมัติแบบง่าย ๆ ถ้า connection หลุด (retry พร้อม backoff)
7. ฟังก์ชัน disconnect()

เชื่อม callback การอ่าน chunk จาก AudioCaptureService.kt (Module 9) เข้ากับ sendAudioChunk() ของ client นี้ ใน service เดียวกัน (เพราะทั้งคู่ต้องทำงานต่อเนื่องขณะ foreground service ทำงานอยู่)

ระหว่างพัฒนา ตั้ง URL ชี้ไปที่ backend ที่รันอยู่ใน Codespaces (forwarded port URL) เพื่อทดสอบ end-to-end ก่อน
```

**ผลลัพธ์ที่คาดหวัง:** เสียงที่ดักได้จาก YouTube ถูกส่งไป backend จริง และได้เสียงไทยกลับมา (ยังไม่ต้องเล่น)

---

## Module 11 — Android: เล่นเสียงแปลกลับ + Audio Focus Ducking

**บริบทให้แปะก่อน prompt:**
> มี TranslatorWebSocketClient.kt รับเสียงไทยกลับมาแล้ว ตอนนี้เล่นเสียงนั้นเข้าหูฟัง พร้อมทำให้เสียง YouTube ต้นฉบับหรี่ลงอัตโนมัติตอนเล่นเสียงแปล (audio focus ducking) เพื่อไม่ให้เสียงซ้อนกันจนฟังไม่รู้เรื่อง

**Prompt:**
```
สร้างไฟล์ Kotlin ชื่อ AudioPlayer.kt (โฟลเดอร์ playback/) โดยมี:
1. ใช้ AudioTrack (โหมด streaming) เล่นเสียง PCM ที่ได้รับมาเป็น ByteArray
2. ฟังก์ชัน playChunk(audioData: ByteArray) เขียนข้อมูลเข้า AudioTrack ทันที ไม่รอเสียงก่อนหน้าจบ
3. ใช้ queue ภายในถ้าจำเป็นเพื่อเรียงลำดับเสียงที่มาไม่พร้อมกัน
4. ก่อนเล่นแต่ละ chunk ให้เรียก AudioManager.requestAudioFocus() แบบ AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK เพื่อให้ระบบสั่ง YouTube หรี่เสียงต้นฉบับลงอัตโนมัติช่วงที่เล่นเสียงแปล
5. หลังเล่นเสียง chunk นั้นจบ ให้ abandonAudioFocus() คืน focus ให้ YouTube เล่นเสียงต้นฉบับกลับมาปกติ (จนกว่า chunk ถัดไปจะมาอีก)
6. ฟังก์ชัน release() คืนทรัพยากรเมื่อไม่ใช้แล้ว

เชื่อม onAudioReceived callback จาก TranslatorWebSocketClient.kt (Module 10) เข้ากับ playChunk() ของ AudioPlayer.kt นี้

หลังโค้ดครบ ให้เตือนว่าฟีเจอร์นี้ต้องทดสอบบนมือถือจริงเท่านั้น (เสียบหูฟังจริง เปิด YouTube จริง) เพราะ emulator จำลอง audio focus ระหว่างแอปได้ไม่สมบูรณ์
```

**ผลลัพธ์ที่คาดหวัง:** เปิด YouTube พูดภาษาอังกฤษ เสียบหูฟัง กดเริ่มแปล แล้วได้ยินเสียงไทยแทรกเข้ามาพร้อมเสียงต้นฉบับหรี่ลงอัตโนมัติ

---

## Module 12 — ลด Latency

**บริบทให้แปะก่อน prompt:**
> ระบบทำงานครบวงจรแล้ว (Module 0-11) แต่ latency ยังสูงกว่าเป้าหมาย ต้องการปรับให้เร็วขึ้น

**Prompt:**
```
ตรวจสอบและปรับปรุงโค้ดเพื่อลด latency โดย:
1. ลดขนาด chunk เสียงจากฝั่ง Android เหลือ 1 วินาที (ปรับใน AudioCaptureService.kt)
2. ใช้ Google STT streaming แบบ interim results เพื่อเริ่มแปลภาษาได้ก่อนที่ประโยคจะพูดจบ (ถ้า use case รองรับ)
3. เพิ่ม log เวลาแบบละเอียดทุกขั้นตอน (capture -> network -> STT -> Translate -> TTS -> playback) ถ้ายังไม่มีจาก Module 7
4. ตรวจสอบว่า network round-trip ไป Google API ไม่ใช่ bottleneck หลัก (เช่น เลือก region ของ Google Cloud project ให้ใกล้ตำแหน่งเซิร์ฟเวอร์ backend)
5. เสนอจุดที่ควรทำ parallel/pipeline overlap ได้ พร้อม comment อธิบายแนวทาง ถ้าซับซ้อนเกินไปให้ระบุเป็น TODO
```

**ผลลัพธ์ที่คาดหวัง:** ได้ log breakdown ของ latency แต่ละขั้น เพื่อนำไปวิเคราะห์ต่อ

---

## Module 13 — Deployment (Cloudflare Tunnel)

**บริบทให้แปะก่อน prompt:**
> ระบบพร้อมใช้งานจริงบนเครื่อง HP All-in-One ที่บ้าน ต้องการ expose backend ออกอินเทอร์เน็ตอย่างปลอดภัย

**Prompt:**
```
เขียนเอกสาร docs/deployment.md อธิบาย:
1. วิธี git clone repository ลงเครื่อง HP ที่บ้าน
2. วิธีติดตั้งและรัน cloudflared เป็น tunnel ไปยัง backend ใน docker-compose (port 8000)
3. ตัวอย่างไฟล์ config ของ cloudflared (config.yml)
4. คำแนะนำเรื่อง wss:// แทน ws:// ตอนเชื่อมผ่าน tunnel ออกอินเทอร์เน็ต และต้องแก้ TranslatorWebSocketClient.kt ให้ใช้ wss://
5. Checklist ก่อน expose จริง: BACKEND_API_KEY ตั้งแล้ว, ไฟล์ credentials Google Cloud อยู่ถูกที่และไม่ถูก commit ขึ้น GitHub, rate limit เปิดอยู่, CORS จำกัด origin แล้ว
6. คำเตือนเรื่องค่าใช้จ่าย Google Cloud API — แนะนำตั้ง budget alert ใน Google Cloud Console ป้องกันบิลบานปลายถ้ามีคนใช้เกินคาด
7. วิธี restart service อัตโนมัติเมื่อเครื่องรีสตาร์ท
```

**ผลลัพธ์ที่คาดหวัง:** มีเอกสาร deploy ที่ทำตามได้จริง แอป Android เชื่อมต่อจากนอกเครือข่ายบ้านได้อย่างปลอดภัย

---

## หมายเหตุท้ายเอกสาร

- ทำตามลำดับ Module 0 → 13 อย่าข้าม
- **ไม่ต้องใช้ Colab MCP ในสถาปัตยกรรมนี้เลย** เพราะ ASR/Translate/TTS เป็นการเรียก Google API ภายนอกทั้งหมด ไม่มีโมเดลหนักให้รันเอง — ถ้าเอกสารเก่าเคยพูดถึง Colab MCP ให้ข้ามได้
- Module 9-11 (Android) **ต้องทดสอบบนมือถือจริงเท่านั้น** ตาม `02_Setup_Android_Build_Tools.md` ห้ามพึ่ง emulator สำหรับฟีเจอร์เหล่านี้
- ไฟล์ credentials ของ Google Cloud (JSON key) **ห้าม commit ขึ้น GitHub เด็ดขาด** เช็ค `.gitignore` ทุกครั้งก่อน push
- ระวังค่าใช้จ่าย Google Cloud API สะสมระหว่างพัฒนา/ทดสอบบ่อย ๆ — ตั้ง budget alert ไว้แต่เนิ่น ๆ ไม่ต้องรอถึง Module 13
- AudioPlaybackCapture รองรับเฉพาะ **Android 10 (API 29) ขึ้นไป** — ถ้ามือถือทดสอบเป็นรุ่นเก่ากว่านี้ฟีเจอร์นี้จะใช้ไม่ได้
