# Prompt Pack: สั่ง AI ใน GitHub Codespaces สร้างระบบแปลเสียงวิดีโอเป็นเสียงไทย

> เวอร์ชันปรับปรุง — ใช้ GitHub Codespaces + Copilot Agent mode แทน Firebase Studio
> (Firebase Studio ปิดรับ workspace ใหม่ตั้งแต่ 22 มิ.ย. 2026 และจะ sunset มี.ค. 2027)
> **ต้องทำเอกสาร `00_Setup_MCP_Before_Project.md` ให้ครบ checklist ก่อน แล้วค่อยเริ่มที่ Module 0 ในนี้**

---

## วิธีใช้เอกสารนี้

1. เปิด Codespaces ตาม `00_Setup_MCP_Before_Project.md` ให้ครบ checklist ก่อนเสมอ
2. เริ่มจาก **Module 0** ไล่ลงไปทีละโมดูล ห้ามข้าม
3. ทุก prompt สั่งในช่อง **Copilot Chat (โหมด Agent)** ไม่ใช่โหมด Ask
4. โมดูลที่มีป้าย 🟢 **ใช้ Colab MCP** หมายถึงงานหนัก (โหลด/รันโมเดล AI) ต้องสั่งให้ Agent รันผ่าน Colab แทนที่จะรันในเครื่อง Codespaces เอง
5. โมดูลที่มีป้าย 🔵 **รันใน Codespaces ปกติ** คือ code เบา ๆ (FastAPI routing, Kotlin, config) ไม่ต้องพึ่ง Colab
6. หลัง AI สร้างโค้ดแต่ละโมดูล ให้ทดสอบก่อนไปโมดูลถัดไปเสมอ
7. ทุกครั้งที่เปิดเซสชันใหม่ ให้สั่ง "เชื่อมต่อ Colab MCP อีกครั้ง" ก่อนเริ่มงานที่มีป้าย 🟢

### โครงสร้างโปรเจกต์ (ยึดตลอดทั้งงาน)

```
project/
 ├── android/
 │    └── app/src/main/java/.../ (Kotlin source)
 ├── backend/
 │   ├── main.py
 │   ├── websocket.py
 │   ├── whisper_service.py
 │   ├── translate_service.py
 │   ├── tts_service.py
 │   ├── security.py
 │   ├── requirements.txt
 │   ├── Dockerfile
 │   └── docker-compose.yml
 ├── models/          (เก็บ Piper voice model ฯลฯ)
 ├── tests/
 └── docs/
      ├── 00_Setup_MCP_Before_Project.md
      └── Firebase_Studio_AI_Prompts.md (ไฟล์นี้)
```

### ลำดับการพัฒนา (Roadmap)

| Phase | โมดูล | สภาพแวดล้อม |
|---|---|---|
| 1 | FastAPI พื้นฐาน + Whisper + Translation + Piper | Codespaces เขียนโค้ด / Colab รันโมเดล |
| 2 | Android App (บันทึกเสียง + WebSocket + เล่นเสียง) | Codespaces เขียนโค้ด / เครื่องจริงหรือ CI build APK |
| 3 | ลด Latency + รองรับ Live Stream | ทั้งคู่ |
| 4 | Security + Docker + Deployment | Codespaces + เครื่อง HP ที่บ้าน |

---

## Module 0 — Setup โปรเจกต์และ requirements.txt 🔵

**บริบทให้แปะก่อน prompt:**
> กำลังสร้างระบบแปลเสียงวิดีโอเป็นเสียงพูดภาษาไทยแบบเรียลไทม์ ทำงานใน GitHub Codespaces ประกอบด้วย Android (Kotlin) ส่งเสียงผ่าน WebSocket ไปยัง Backend (FastAPI) ซึ่งใช้ Faster-Whisper ถอดเสียง แปลเป็นไทย แล้วสร้างเสียงด้วย Piper TTS งานที่ต้องรันโมเดล AI หนัก ๆ จะสั่งผ่าน Colab MCP แยกต่างหาก

**Prompt:**
```
สร้างโครงสร้างโปรเจกต์เริ่มต้นตามนี้:

project/
 ├── android/
 ├── backend/
 ├── models/
 ├── tests/
 └── docs/

ในโฟลเดอร์ backend/ ให้สร้างไฟล์ requirements.txt ที่มี dependencies ต่อไปนี้
พร้อมเวอร์ชันที่เข้ากันได้กับ Python 3.12:
- fastapi
- uvicorn[standard]
- faster-whisper
- ctranslate2
- piper-tts
- ffmpeg-python
- python-jose[cryptography] (สำหรับ JWT)
- websockets

อย่าเพิ่งเขียนโค้ด logic ใด ๆ ในขั้นนี้ แค่สร้างโครงสร้างโฟลเดอร์และไฟล์ requirements.txt เท่านั้น
ทำในเครื่อง Codespaces นี้เลย ไม่ต้องใช้ Colab MCP
```

**ผลลัพธ์ที่คาดหวัง:** โฟลเดอร์ครบตามโครงสร้าง + requirements.txt

---

## Module 1 — FastAPI พื้นฐาน (main.py) 🔵

**บริบทให้แปะก่อน prompt:**
> มีโครงสร้างโปรเจกต์ backend/ พร้อม requirements.txt แล้ว ตอนนี้จะสร้าง entry point ของ FastAPI ทำงานใน Codespaces

**Prompt:**
```
สร้างไฟล์ backend/main.py เป็น FastAPI application เริ่มต้น โดยมี:
1. FastAPI app instance ชื่อ app
2. GET endpoint /health ที่ return {"status": "ok"}
3. CORS middleware เปิดให้ทุก origin (สำหรับ dev เท่านั้น มี comment เตือนว่าต้องจำกัดตอน production)
4. โครงสำหรับ mount websocket router จากไฟล์ websocket.py (ยังไม่ต้องสร้างไฟล์นั้นตอนนี้ แค่ import แบบ placeholder และ comment ไว้)
5. ใช้ uvicorn รันด้วยคำสั่ง: uvicorn main:app --reload --host 0.0.0.0 --port 8000

Codespaces จะ forward port 8000 ให้อัตโนมัติ ให้เปิดแถบ PORTS ด้านล่างเพื่อเช็ค URL ที่ forward ออกมา
อธิบายสั้น ๆ ท้ายไฟล์ (เป็น comment) ว่าแต่ละส่วนทำหน้าที่อะไร
```

**ผลลัพธ์ที่คาดหวัง:** รัน `uvicorn main:app --reload` แล้วเข้า `/health` ผ่าน forwarded URL ได้ผล `{"status":"ok"}`

---

## Module 2 — WebSocket Handler (websocket.py) 🔵

**บริบทให้แปะก่อน prompt:**
> มี backend/main.py ที่รัน FastAPI ได้แล้วใน Codespaces ตอนนี้จะเพิ่ม WebSocket endpoint สำหรับรับเสียง PCM จาก Android เป็น chunk ทุก 1-2 วินาที (16kHz mono)

**Prompt:**
```
สร้างไฟล์ backend/websocket.py โดยมี:
1. APIRouter ชื่อ router
2. WebSocket endpoint ที่ path /ws/audio
3. รับข้อมูลเสียงแบบ binary (bytes) จาก client เป็น loop
4. มี try/except ครอบ WebSocketDisconnect เพื่อจัดการตอน client ตัดการเชื่อมต่อ
5. เก็บ buffer เสียงที่รับมาไว้ใน list ชั่วคราว (ยังไม่ต้องส่งไป ASR จริง แค่ทำ placeholder function ชื่อ process_audio_chunk(audio_bytes) ที่ print ขนาดข้อมูลที่รับมา)
6. ส่ง acknowledgement กลับไปหา client แบบ text เป็น JSON {"type": "ack", "received_bytes": ...}

แล้วแก้ไข backend/main.py ให้ import router จาก websocket.py และ include_router() เข้ากับ app จริง (แทน placeholder เดิม)

อธิบาย comment กำกับทุก step สำคัญ
```

**ผลลัพธ์ที่คาดหวัง:** ทดสอบด้วย WebSocket client (เช่น `websocat` หรือ Postman) ผ่าน forwarded URL ของ Codespaces ส่ง binary เข้า `/ws/audio` แล้วได้ ack กลับ

---

## Module 3 — Faster-Whisper ASR (whisper_service.py) 🟢 ใช้ Colab MCP

**บริบทให้แปะก่อน prompt:**
> มี websocket.py ที่รับเสียงเป็น binary chunk แล้ว ตอนนี้จะสร้าง service ถอดเสียงเป็นข้อความด้วย faster-whisper งานนี้หนัก ให้รันทดสอบผ่าน Colab MCP (GPU T4) แทนเครื่อง Codespaces
> **ก่อนเริ่ม ให้สั่ง Agent เชื่อมต่อ Colab MCP ก่อน**

**Prompt:**
```
ก่อนอื่นเชื่อมต่อกับ Colab MCP และยืนยันว่ามองเห็น GPU T4

จากนั้นสร้างไฟล์ backend/whisper_service.py โดยมี:
1. โหลด WhisperModel จาก faster-whisper แบบ lazy-load (โหลดครั้งเดียวตอน import หรือ singleton) ใช้ model size "base" และ compute_type="int8" เพื่อความเร็ว รองรับการสลับ device เป็น "cuda" ถ้ามี GPU ไม่งั้น fallback เป็น "cpu"
2. ฟังก์ชัน transcribe_audio(audio_bytes: bytes) -> str ที่:
   - แปลง PCM 16kHz mono bytes เป็น numpy array ที่ whisper ใช้ได้
   - เรียก model.transcribe()
   - รวมข้อความจากทุก segment เป็น string เดียว แล้ว return
3. จัดการ error กรณีเสียงสั้นเกินไปหรือแปลงไม่ได้ ให้ return string ว่าง พร้อม log warning

เขียนไฟล์นี้ในเครื่อง Codespaces ตามปกติ (เพราะเป็นแค่ text/code)
แต่ให้ทดสอบรันจริงผ่าน Colab MCP: อัปโหลดไฟล์เสียงทดสอบ (.wav 16kHz mono) ไปที่ Colab scratchpad
แล้วรัน transcribe_audio() บน GPU T4 เพื่อยืนยันว่าโค้ดทำงานถูกต้องและเร็วพอ ก่อนค่อยเอาไฟล์กลับมาไว้ที่ repo

แล้วแก้ไข backend/websocket.py ให้เรียกใช้ transcribe_audio() แทน process_audio_chunk() เดิม แล้ว print ข้อความที่ถอดได้ออกมา (ยังไม่ต้องส่งต่อไปแปลภาษา)
```

**ผลลัพธ์ที่คาดหวัง:** เห็นผลทดสอบถอดเสียงบน Colab GPU สำเร็จ + ไฟล์ whisper_service.py ที่ถูกต้องอยู่ใน repo Codespaces

---

## Module 4 — Translation Service (translate_service.py) 🔵

**บริบทให้แปะก่อน prompt:**
> มี whisper_service.py ที่ถอดเสียงเป็นข้อความภาษาต้นทางได้แล้ว ตอนนี้จะแปลข้อความนั้นเป็นภาษาไทย โมดูลนี้เรียก API ภายนอก ไม่ใช่งานหนักที่ต้องพึ่ง Colab จึงเขียน/ทดสอบใน Codespaces ได้ตรง ๆ

**Prompt:**
```
สร้างไฟล์ backend/translate_service.py โดยมี:
1. ฟังก์ชัน translate_to_thai(text: str, source_lang: str = "auto") -> str
2. ออกแบบให้เรียกผ่าน API ภายนอก (เช่น Gemini หรือ OpenAI compatible API) โดยอ่าน API key จาก environment variable ชื่อ TRANSLATE_API_KEY (ใช้ os.environ.get และแจ้ง error ถ้าไม่มี key)
3. ใส่ระบบ fallback: ถ้าเรียก API ไม่สำเร็จ (exception หรือ timeout) ให้ return ข้อความต้นฉบับพร้อม log error แทนที่จะทำให้ระบบล่ม
4. ตั้ง timeout การเรียก API ไม่เกิน 5 วินาที เพื่อรักษาความหน่วงของระบบ

ให้ตั้งค่า TRANSLATE_API_KEY ผ่าน Codespaces Secrets (Settings > Secrets and variables > Codespaces) แทนการ hardcode ในไฟล์

แล้วแก้ไข backend/websocket.py ให้เรียก translate_to_thai() ต่อจาก transcribe_audio() แล้ว print ข้อความไทยที่แปลได้
```

**ผลลัพธ์ที่คาดหวัง:** ข้อความที่ถอดได้จาก Module 3 ถูกแปลเป็นไทยและ print ออกมา

---

## Module 5 — Piper TTS (tts_service.py) 🟢 ใช้ Colab MCP

**บริบทให้แปะก่อน prompt:**
> มี translate_service.py ที่แปลข้อความเป็นไทยได้แล้ว ตอนนี้จะแปลงข้อความไทยนั้นเป็นเสียงพูดด้วย Piper TTS ซึ่งโหลดโมเดลเสียงหนักพอควร ให้ทดสอบรันผ่าน Colab MCP ก่อน
> **สั่ง Agent เชื่อมต่อ Colab MCP ก่อนเริ่ม**

**Prompt:**
```
เชื่อมต่อ Colab MCP อีกครั้งก่อนเริ่ม (ถ้ายังไม่ได้เชื่อมในเซสชันนี้)

สร้างไฟล์ backend/tts_service.py โดยมี:
1. โหลด Piper voice model ภาษาไทยแบบ preload ตอน import (โมเดลเก็บไว้ที่ models/) เป็น singleton เพื่อไม่ต้องโหลดซ้ำทุกครั้ง
2. ฟังก์ชัน synthesize_speech(text: str) -> bytes ที่:
   - รับข้อความไทย
   - สร้างเสียงเป็น PCM/WAV bytes
   - return เสียงนั้นออกมา
3. จัดการ error กรณีข้อความว่างเปล่า ให้ return bytes ว่างพร้อม log warning

ทดสอบการโหลดโมเดลและสร้างเสียงจริงผ่าน Colab MCP ก่อน (โมเดล Piper ภาษาไทยมีขนาดหลายสิบ MB
รันบน Colab จะเร็วกว่าและไม่กิน storage ของ Codespaces) แล้วค่อยดาวน์โหลดไฟล์เสียงตัวอย่างมาฟังยืนยันผล

แล้วแก้ไข backend/websocket.py ให้เรียก synthesize_speech() ต่อจากขั้นตอนแปลภาษา แล้วส่งเสียงที่ได้กลับไปหา client ผ่าน websocket เป็น binary message (await websocket.send_bytes(...))

ตอนนี้ pipeline เต็มควรเป็น: รับเสียง -> ถอดเสียง -> แปลไทย -> สร้างเสียงไทย -> ส่งกลับ
```

**ผลลัพธ์ที่คาดหวัง:** ได้ยินไฟล์เสียงไทยตัวอย่างที่สร้างจาก Colab เพื่อยืนยันว่าโมเดลทำงานถูกต้อง + pipeline เต็มพร้อมใช้ใน Codespaces

---

## Module 6 — รวม Pipeline และจัดการ Concurrency 🔵

**บริบทให้แปะก่อน prompt:**
> Pipeline เต็ม (ASR -> Translate -> TTS) ทำงานได้แล้วแบบ sequential ในไฟล์เดียว ตอนนี้ต้องการจัดระเบียบโค้ดและรองรับหลาย client พร้อมกันโดยไม่บล็อกกัน โมดูลนี้เป็นแค่การจัดโครงสร้างโค้ด ไม่ต้องพึ่ง Colab

**Prompt:**
```
ปรับปรุง backend/websocket.py ให้:
1. แยก logic ของ pipeline (transcribe -> translate -> synthesize) ออกมาเป็นฟังก์ชันเดียวชื่อ run_pipeline(audio_bytes: bytes) -> bytes ที่เรียกใช้ whisper_service, translate_service, tts_service ตามลำดับ
2. ใช้ asyncio ให้ endpoint /ws/audio รองรับหลาย connection พร้อมกันโดยไม่บล็อก event loop (ใช้ run_in_executor สำหรับงานที่เป็น CPU-bound อย่าง whisper และ piper)
3. เพิ่ม logging (ใช้ Python logging module) บันทึกเวลาที่ใช้ในแต่ละขั้นตอน (ASR, Translate, TTS) เพื่อดู bottleneck ของ latency
4. เพิ่มการจัดการ error กลาง ๆ ถ้าขั้นตอนใดล้มเหลว ให้ส่ง JSON error message กลับไปหา client แทนที่จะตัดการเชื่อมต่อ

หมายเหตุ: ในสภาพแวดล้อม production จริง (Docker บนเครื่อง HP ที่บ้าน) โมเดลจะรันในเครื่องนั้นเองไม่ใช่ Colab
Colab MCP ใช้แค่ตอนพัฒนา/ทดสอบเท่านั้น เขียน comment กำกับไว้ในโค้ดจุดนี้ด้วย
```

**ผลลัพธ์ที่คาดหวัง:** ระบบรองรับหลาย client พร้อมกัน มี log เวลาแต่ละขั้นตอนให้ดู latency

---

## Module 7 — Security (security.py) 🔵

**บริบทให้แปะก่อน prompt:**
> Backend ทำงานครบ pipeline แล้ว ตอนนี้ต้องการป้องกันการเข้าถึงโดยไม่ได้รับอนุญาต ก่อนเอาไปรันจริงผ่าน Cloudflare Tunnel

**Prompt:**
```
สร้างไฟล์ backend/security.py โดยมี:
1. ฟังก์ชันตรวจสอบ API Key ที่ส่งมาผ่าน query parameter หรือ header เทียบกับค่าที่อ่านจาก environment variable BACKEND_API_KEY
2. ฟังก์ชัน dependency สำหรับ FastAPI (ใช้กับ Depends()) ที่ใช้ตรวจสอบ API key นี้กับ endpoint ปกติ
3. สำหรับ WebSocket endpoint ให้เพิ่มการตรวจสอบ API key จาก query parameter ตอนเปิด connection ก่อน accept() ถ้าไม่ถูกต้องให้ปิด connection ด้วย code 4001
4. เพิ่ม rate limiting เบื้องต้นแบบ in-memory (จำกัดจำนวน connection ต่อ IP ต่อนาที) โดยไม่ต้องใช้ library ภายนอกเพิ่ม

ตั้งค่า BACKEND_API_KEY ผ่าน Codespaces Secrets เช่นเดียวกับ TRANSLATE_API_KEY ใน Module 4
แล้วแก้ไข backend/websocket.py และ main.py ให้ใช้การตรวจสอบนี้
```

**ผลลัพธ์ที่คาดหวัง:** เชื่อมต่อโดยไม่มี API key ที่ถูกต้องจะถูกปฏิเสธ

---

## Module 8 — Docker (Dockerfile + docker-compose.yml) 🔵

**บริบทให้แปะก่อน prompt:**
> Backend ทำงานสมบูรณ์และมีระบบความปลอดภัยแล้ว ตอนนี้ต้องการ containerize เพื่อ deploy บนเครื่อง HP All-in-One ที่บ้าน (ไม่ใช่ Codespaces หรือ Colab อีกต่อไป — สภาพแวดล้อมจริง)

**Prompt:**
```
สร้างไฟล์ backend/Dockerfile โดย:
1. ใช้ python:3.12-slim เป็น base image
2. ติดตั้ง ffmpeg และ dependencies ระบบที่จำเป็นสำหรับ faster-whisper และ piper-tts
3. copy requirements.txt แล้ว pip install
4. copy โค้ดทั้งหมดของ backend/
5. expose port 8000
6. CMD รัน uvicorn main:app --host 0.0.0.0 --port 8000

สร้างไฟล์ backend/docker-compose.yml โดย:
1. กำหนด service ชื่อ backend build จาก Dockerfile นี้
2. map port 8000:8000
3. mount volume สำหรับโฟลเดอร์ models/ จากเครื่อง host
4. ส่ง environment variable BACKEND_API_KEY และ TRANSLATE_API_KEY ผ่าน .env file
5. ตั้ง restart policy เป็น unless-stopped

สร้างไฟล์ backend/.env.example เป็นตัวอย่าง environment variables ที่ต้องใช้ (ไม่ใส่ค่าจริง)

โค้ดชุดนี้เขียนใน Codespaces ได้ตามปกติ แต่การรันจริง (docker compose up) ต้องทำบนเครื่อง HP
ที่บ้านหรือ VPS จริง เพราะ Codespaces ไม่ใช่ที่ที่จะรัน production service ถาวร
```

**ผลลัพธ์ที่คาดหวัง:** commit ไฟล์ Docker ทั้งหมดขึ้น GitHub แล้วไป `git pull` + `docker compose up --build` บนเครื่องจริงที่บ้าน

---

## Module 9 — Android: ขอ Permission และบันทึกเสียง (Kotlin) 🔵

**บริบทให้แปะก่อน prompt:**
> Backend พร้อมใช้งานแล้ว ตอนนี้เริ่มฝั่ง Android ด้วย Kotlin โดยเริ่มจากส่วนขอสิทธิ์และบันทึกเสียงจากไมโครโฟนเป็น PCM 16kHz mono เขียนโค้ดใน Codespaces ได้ปกติ (เป็น text/code เบา ไม่ต้องพึ่ง Colab)

**Prompt:**
```
สร้างไฟล์ Kotlin ในโปรเจกต์ Android (จัดในโฟลเดอร์ android/app/src/main/java/.../audio/) ชื่อ AudioRecorder.kt โดยมี:
1. class AudioRecorder ที่ใช้ AudioRecord API ของ Android
2. ตั้งค่า sample rate 16000Hz, channel mono, encoding PCM 16-bit
3. ฟังก์ชัน startRecording(onChunkReady: (ByteArray) -> Unit) ที่อ่านเสียงเป็น chunk ทุก 1-2 วินาที แล้วเรียก callback ส่ง ByteArray ออกไป
4. ฟังก์ชัน stopRecording() ที่หยุดและ release ทรัพยากรอย่างถูกต้อง
5. รันการอ่านเสียงบน background thread/coroutine ไม่บล็อก UI thread

และบอกวิธีเพิ่ม RECORD_AUDIO permission ใน AndroidManifest.xml พร้อมตัวอย่างโค้ดขอ permission แบบ runtime (ActivityResultContracts) สำหรับ Activity ที่จะใช้ class นี้

หมายเหตุ: Codespaces เขียนและตรวจโครงสร้างโค้ด Kotlin ได้ แต่ build APK จริงหรือรันบน emulator ไม่ได้
ต้องเอาโค้ดนี้ไปเปิดใน Android Studio บนเครื่องจริง หรือใช้ GitHub Actions build ให้ทีหลัง
```

**ผลลัพธ์ที่คาดหวัง:** ไฟล์ Kotlin ถูกต้องตามโครงสร้าง commit ขึ้น GitHub แล้วพร้อมเปิดทดสอบบนเครื่องจริง/Android Studio

---

## Module 10 — Android: WebSocket Client 🔵

**บริบทให้แปะก่อน prompt:**
> มี AudioRecorder.kt ที่ให้เสียงเป็น chunk ByteArray แล้ว ตอนนี้จะส่งเสียงนั้นไป backend ผ่าน WebSocket และรับเสียงไทยกลับมา เขียนใน Codespaces ได้ปกติ

**Prompt:**
```
สร้างไฟล์ Kotlin ชื่อ TranslatorWebSocketClient.kt (โฟลเดอร์เดียวกับ audio/ หรือ network/) โดยมี:
1. ใช้ OkHttp WebSocket client เชื่อมต่อไปยัง URL ของ backend (ws://... หรือ wss://... ต่อ API key เป็น query parameter)
2. ฟังก์ชัน connect() เปิด connection และตั้ง WebSocketListener
3. ฟังก์ชัน sendAudioChunk(chunk: ByteArray) ส่งเสียงแบบ binary message
4. ใน listener: onMessage สำหรับ binary ให้ callback ออกไปเป็น onAudioReceived: (ByteArray) -> Unit ที่ Activity จะใช้เล่นเสียง
5. onMessage สำหรับ text (JSON ack/error) ให้ log ไว้เพื่อ debug
6. จัดการ reconnect อัตโนมัติแบบง่าย ๆ ถ้า connection หลุด (retry พร้อม backoff)
7. ฟังก์ชัน disconnect() ปิด connection อย่างถูกต้อง

ระหว่างพัฒนา ให้ตั้ง URL ชี้ไปที่ backend ที่รันอยู่ใน Codespaces (ผ่าน forwarded port URL)
เพื่อทดสอบ end-to-end ได้ก่อน แล้วค่อยเปลี่ยนเป็น URL จริงตอน deploy ขึ้นเครื่อง HP ที่บ้าน

อธิบายวิธีเชื่อม AudioRecorder.kt เข้ากับ client นี้ใน Activity ตัวอย่าง (เรียก sendAudioChunk ใน callback ของ startRecording)
```

**ผลลัพธ์ที่คาดหวัง:** แอปส่งเสียงไป backend บน Codespaces ได้จริง และรับข้อมูลเสียงไทยกลับมา (ยังไม่ต้องเล่น)

---

## Module 11 — Android: เล่นเสียงที่รับกลับมาทันที 🔵

**บริบทให้แปะก่อน prompt:**
> มี TranslatorWebSocketClient.kt ที่รับเสียงไทยกลับมาเป็น ByteArray แล้ว ตอนนี้จะเล่นเสียงนั้นทันทีแบบต่อเนื่อง

**Prompt:**
```
สร้างไฟล์ Kotlin ชื่อ AudioPlayer.kt โดยมี:
1. ใช้ AudioTrack (โหมด streaming) เล่นเสียง PCM ที่ได้รับมาเป็น ByteArray
2. ฟังก์ชัน playChunk(audioData: ByteArray) เขียนข้อมูลเข้า AudioTrack ทันทีโดยไม่รอเสียงก่อนหน้าจบ (เพื่อความต่อเนื่อง)
3. ใช้ queue ภายในถ้าจำเป็นเพื่อเรียงลำดับเสียงที่มาไม่พร้อมกัน
4. ฟังก์ชัน release() คืนทรัพยากรเมื่อไม่ใช้แล้ว

แล้วเชื่อม onAudioReceived callback จาก TranslatorWebSocketClient.kt เข้ากับ playChunk() ของ AudioPlayer.kt ใน Activity ตัวอย่าง เพื่อให้ pipeline เต็มทำงาน: บันทึกเสียง -> ส่ง -> รับเสียงไทย -> เล่นทันที

หลังโค้ดครบ ให้เตือนว่าต้องเอาโปรเจกต์ android/ ไปเปิดใน Android Studio (หรือใช้ GitHub Actions build APK)
เพื่อทดสอบเสียงจริงบนอุปกรณ์ เพราะ Codespaces จำลอง audio hardware ไม่ได้
```

**ผลลัพธ์ที่คาดหวัง:** โค้ดครบวงจรอยู่ใน repo พร้อมทดสอบบนอุปกรณ์จริง: พูดภาษาต้นทาง แล้วได้ยินเสียงไทยตอบกลับ

---

## Module 12 — ลด Latency (Phase 3) 🟢 ใช้ Colab MCP บางส่วน

**บริบทให้แปะก่อน prompt:**
> ระบบทำงานครบวงจรแล้ว (Module 0-11) แต่ latency ยังสูงกว่าเป้าหมาย 1-2 วินาที ต้องการปรับให้เร็วขึ้น ใช้ Colab MCP ช่วยวัดเวลารันโมเดลบน GPU เทียบกับที่คาดไว้

**Prompt:**
```
เชื่อมต่อ Colab MCP อีกครั้งก่อนเริ่ม (ถ้ายังไม่ได้เชื่อมในเซสชันนี้)

ตรวจสอบและปรับปรุงโค้ดใน backend/ (whisper_service.py, tts_service.py, websocket.py) เพื่อลด latency โดย:
1. ลดขนาด chunk เสียงจากฝั่ง Android เหลือ 1 วินาที (ปรับใน AudioRecorder.kt ด้วย)
2. ยืนยันว่า whisper model preload ไว้แล้วและไม่โหลดซ้ำทุก request
3. ยืนยันว่า piper model preload ไว้แล้วเช่นกัน
4. เพิ่ม log เวลาแบบละเอียดในแต่ละขั้นตอน (ASR/Translate/TTS/network) ถ้ายังไม่มีจาก Module 6
5. รันการวัดเวลาจริงผ่าน Colab MCP บน GPU T4 เทียบกับที่รันบน CPU ธรรมดา แล้วสรุปตัวเลข latency ที่วัดได้จริงให้ดู
6. เสนอจุดที่ควรทำ parallel/pipeline overlap ได้ (เช่น เริ่มแปลภาษาระหว่างที่ ASR chunk ถัดไปกำลังประมวลผล) พร้อม comment อธิบายแนวทาง โดยยังไม่ต้อง implement เต็มรูปแบบถ้าซับซ้อนเกินไป ให้ระบุเป็น TODO พร้อมคำอธิบาย
```

**ผลลัพธ์ที่คาดหวัง:** ได้ตัวเลข latency จริงจากการวัดบน Colab GPU เทียบ CPU เพื่อยืนยันว่าเครื่อง HP ที่บ้าน (ถ้าไม่มี GPU) จะช้ากว่านี้แค่ไหน และควรทำอะไรเพิ่ม

---

## Module 13 — Deployment (Cloudflare Tunnel) 🔵

**บริบทให้แปะก่อน prompt:**
> ระบบพร้อมใช้งานจริงบนเครื่อง HP All-in-One ที่บ้าน ต้องการ expose backend ที่รันใน Docker ออกอินเทอร์เน็ตอย่างปลอดภัยผ่าน Cloudflare Tunnel เพื่อให้แอป Android เชื่อมต่อจากนอกบ้านได้ ขั้นตอนนี้เกิดขึ้นบนเครื่องจริง ไม่ใช่ Codespaces หรือ Colab

**Prompt:**
```
เขียนเอกสาร docs/deployment.md อธิบายขั้นตอน deploy ระบบนี้บนเครื่อง HP All-in-One ที่บ้าน ประกอบด้วย:
1. วิธี git clone repository จาก GitHub (ที่ commit ไว้จาก Codespaces) ลงเครื่อง HP
2. วิธีติดตั้งและรัน cloudflared เป็น tunnel ไปยัง backend ที่รันใน docker-compose (port 8000)
3. ตัวอย่างไฟล์ config ของ cloudflared (config.yml) ที่ map hostname ไปยัง localhost:8000
4. คำแนะนำเรื่อง wss:// (WebSocket ผ่าน TLS) แทน ws:// เมื่อเชื่อมผ่าน tunnel ออกอินเทอร์เน็ต และต้องแก้ TranslatorWebSocketClient.kt ให้ใช้ wss:// (เปลี่ยนจาก URL ที่เคยชี้ไป Codespaces ตอนพัฒนา)
5. Checklist ความปลอดภัยก่อน expose จริง: BACKEND_API_KEY ต้องตั้งค่าแล้ว (คัดลอกจาก Codespaces Secrets มาไว้ใน .env บนเครื่องจริง), rate limit เปิดอยู่, CORS จำกัด origin แล้ว (ไม่ใช้ * แบบตอน dev)
6. วิธี restart service อัตโนมัติเมื่อเครื่องรีสตาร์ท (systemd service หรือ docker restart policy ที่ตั้งไว้แล้ว)
```

**ผลลัพธ์ที่คาดหวัง:** มีเอกสารขั้นตอน deploy ที่ทำตามได้จริง และแอป Android เชื่อมต่อจากนอกเครือข่ายบ้านได้อย่างปลอดภัย โดยไม่ต้องพึ่ง Codespaces/Colab อีกต่อไป

---

## หมายเหตุท้ายเอกสาร

- ทำตามลำดับ Module 0 → 13 อย่าข้าม เพราะแต่ละ prompt อ้างอิงไฟล์ที่ AI ต้องเคยเห็นมาก่อน
- ป้าย 🟢 ต้องเชื่อม Colab MCP ก่อนเริ่มทุกครั้ง (connection ไม่ persist ข้าม session) — ดูขั้นตอนใน `00_Setup_MCP_Before_Project.md`
- ป้าย 🔵 ทำใน Codespaces ได้เลยไม่ต้องพึ่ง Colab
- Module 9-11 (ฝั่ง Android) เขียนโค้ดใน Codespaces ได้ แต่ **build APK จริง/ทดสอบ audio hardware ต้องใช้ Android Studio บนเครื่องจริง หรือ GitHub Actions**
- Colab ใช้แค่ช่วงพัฒนา/ทดสอบโมเดล AI เท่านั้น ตอน deploy จริง (Module 13) โมเดลทั้งหมดต้องรันบนเครื่อง HP ที่บ้านผ่าน Docker ไม่พึ่ง Colab อีก เพราะ Colab ไม่ใช่ persistent server
- แนะนำให้ commit โค้ดขึ้น GitHub บ่อย ๆ ระหว่างทำแต่ละโมดูล เพราะ Codespaces เป็น cloud environment ชั่วคราว ถ้า codespace ถูกลบข้อมูลที่ไม่ commit จะหายไปด้วย
