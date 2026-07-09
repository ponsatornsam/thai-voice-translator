# Thai Voice Translator — Deployment Guide

> วิธี deploy ระบบแปลเสียงพูดแบบเรียลไทม์บนเครื่อง HP All-in-One ที่บ้าน
> ผ่าน Cloudflare Tunnel ให้แอป Android เชื่อมต่อจากนอกบ้านได้อย่างปลอดภัย

---

## สภาพแวดล้อมเป้าหมาย

| Component | รายละเอียด |
|---|---|
| **Host** | HP All-in-One, Windows 11 |
| **Container** | Docker + docker-compose |
| **Tunnel** | Cloudflare Tunnel (`cloudflared`) |
| **Domain** | ต้องมี domain ที่ชี้ไป Cloudflare DNS (เช่น `thai-translator.yourdomain.com`) |
| **Android App** | เชื่อมต่อผ่าน `wss://` (WebSocket Secure) |

---

## Step 1: Clone Repository จาก GitHub

ทำบนเครื่อง HP (PowerShell หรือ Git Bash):

```bash
# ติดตั้ง Git ถ้ายังไม่มี: https://git-scm.com/download/win

git clone https://github.com/ponsatornsam/thai-voice-translator.git
cd thai-voice-translator/backend
```

---

## Step 2: ตั้งค่า Environment Variables

```bash
# คัดลอกจาก template
cp .env.example .env

# แก้ไข .env ด้วย notepad หรือ code editor
notepad .env
```

**ค่าที่ต้องกรอก:**

```ini
# Security — สร้าง key แบบสุ่ม (ห้ามใช้ค่าตัวอย่างนี้)
# Generate: python -c "import secrets; print(secrets.token_urlsafe(32))"
BACKEND_API_KEY=xK8mP2vR9qL5nW3jH6fY1dA4sC7tE0bG

# Translation API — OpenAI, Gemini, หรือ compatible provider
TRANSLATE_API_KEY=sk-your-actual-api-key-here

# Optional: ถ้าใช้ custom API endpoint
# TRANSLATE_API_BASE=https://api.openai.com/v1
# TRANSLATE_MODEL=gpt-4o-mini
```

---

## Step 3: ดาวน์โหลด Piper Voice Model

```bash
# สร้างโฟลเดอร์ models/ (ถ้ายังไม่มี)
mkdir -p models

# ดาวน์โหลด Thai voice model จาก HuggingFace
# URL: https://huggingface.co/rhasspy/piper-voices/resolve/main/th/th_TH/vits-th-th/...
# หรือใช้คำสั่ง:
curl -L "https://huggingface.co/rhasspy/piper-voices/resolve/main/th/th_TH/your-model.onnx" -o models/th_TH.onnx
curl -L "https://huggingface.co/rhasspy/piper-voices/resolve/main/th/th_TH/your-model.json" -o models/th_TH.json
```

> **หมายเหตุ:** URL จริงของโมเดลอาจเปลี่ยน — ตรวจสอบที่ https://huggingface.co/rhasspy/piper-voices เลือกภาษา `th` → `th_TH` → เลือก voice quality (low/medium/high) แล้วคัดลอกลิงก์ `.onnx` และ `.json`

---

## Step 4: Build & Start Backend ด้วย Docker

```bash
# ต้องติดตั้ง Docker Desktop สำหรับ Windows ก่อน
# Download: https://www.docker.com/products/docker-desktop/

cd thai-voice-translator/backend

# Build image + start container (background)
docker compose up --build -d

# ตรวจสอบว่า container รันอยู่
docker compose ps

# ดู logs
docker compose logs -f

# ทดสอบ health check (จากเครื่องเดียวกัน)
curl http://localhost:8000/health
# → {"status":"ok"}
```

**Docker จะ restart container ให้อัตโนมัติ** — `restart: unless-stopped` ใน `docker-compose.yml` หมายความว่า:
- Container crash → restart
- เครื่องรีสตาร์ท → Docker daemon เริ่ม → container เริ่มตาม
- หยุดเฉพาะเมื่อ `docker compose down` เท่านั้น

---

## Step 5: ติดตั้ง Cloudflare Tunnel

### 5.1 สร้าง Tunnel ใน Cloudflare Dashboard

1. ไปที่ https://one.dash.cloudflare.com/ → **Zero Trust** → **Networks** → **Tunnels**
2. กด **Create a tunnel** → ตั้งชื่อ (เช่น `thai-translator`)
3. เลือก environment เป็น **Docker** — จะได้ token สำหรับ `cloudflared`

### 5.2 เก็บ Token

Cloudflare จะให้คำสั่งประมาณนี้:
```bash
docker run cloudflare/cloudflared:latest tunnel --no-autoupdate run \
  --token eyJhIjoiYWJjZGVmZ2hpamtsbW5vcHFyc3R1dnd4eXowMTIzNDU2Nzg5...
```

**เก็บ token นี้ไว้** — จะใช้ใน `docker-compose.yml` หรือ config file

### 5.3 เพิ่ม cloudflared ใน docker-compose.yml

เพิ่ม service นี้ต่อท้าย `backend/docker-compose.yml` (เตรียม comment ไว้แล้ว):

```yaml
  # ── Cloudflare Tunnel ────────────────────────────────────────────────
  cloudflared:
    image: cloudflare/cloudflared:latest
    container_name: thai-translator-tunnel
    command: tunnel --no-autoupdate run --token ${CLOUDFLARED_TUNNEL_TOKEN}
    restart: unless-stopped
    depends_on:
      backend:
        condition: service_healthy  # รอ backend healthy ก่อน
    network_mode: "service:backend"  # แชร์ network stack = มองเห็น localhost:8000
```

### 5.4 เพิ่ม Token ใน .env

```ini
# ใน backend/.env — เพิ่มบรรทัดนี้
CLOUDFLARED_TUNNEL_TOKEN=eyJhIjoiYWJjZGVmZ2hpamtsbW5vcHFyc3R1dnd4eXow...
```

### 5.5 ตั้งค่า Public Hostname

ใน Cloudflare Dashboard → Tunnel เดิม → **Public Hostname** → **Add a public hostname**:

| Field | Value |
|---|---|
| Subdomain | `thai-translator` (หรืออะไรก็ได้) |
| Domain | `yourdomain.com` (domain ที่อยู่ใน Cloudflare) |
| Type | `HTTP` |
| URL | `localhost:8000` |

> **⚠️ Important:** Cloudflare Tunnel รองรับ WebSocket โดยอัตโนมัติ — ไม่ต้องตั้งค่าเพิ่ม!
> แค่ชี้ Type: HTTP ไปที่ `localhost:8000` ก็พอ — Cloudflare จัดการ upgrade ws→wss ให้เอง

### 5.6 รันใหม่ทั้งหมด

```bash
# เพิ่ม cloudflared token ใน .env แล้ว
docker compose down
docker compose up --build -d

# ตรวจสอบทั้ง 2 services
docker compose ps
# ควรเห็น: thai-translator-backend (healthy)
#          thai-translator-tunnel (running)
```

---

## Step 6: เปลี่ยน Android App เป็น wss://

หลังจาก tunnel พร้อมใช้งาน แก้ไข `MainActivity.kt` (หรือที่เรียก `wsClient.connect()`):

```kotlin
// ❌ Development — Codespaces / localhost
wsClient.connect(
    serverUrl = "ws://10.0.2.2:8000",    // Emulator → host
    apiKey = BuildConfig.BACKEND_API_KEY
)

// ✅ Production — Cloudflare Tunnel
wsClient.connect(
    serverUrl = "wss://thai-translator.yourdomain.com",  // TLS! (wss ไม่ใช่ ws)
    apiKey = BuildConfig.BACKEND_API_KEY
)
```

> **Port ไม่ต้องระบุ** — Cloudflare Tunnel ให้บริการบน port 443 (HTTPS/WSS) โดยอัตโนมัติ

**BuildConfig Note:** ตั้งค่าใน `app/build.gradle.kts`:
```kotlin
android {
    defaultConfig {
        buildConfigField(
            "String",
            "BACKEND_SERVER_URL",
            "\"${System.getenv("BACKEND_SERVER_URL") ?: "wss://thai-translator.yourdomain.com"}\""
        )
    }
}
```

แล้วใช้ `BuildConfig.BACKEND_SERVER_URL` แทน hardcode URL

---

## Step 7: Security Checklist (ก่อนเปิดให้โลกเห็น)

ทำ checklist นี้ให้ครบก่อน expose จริง:

- [ ] **BACKEND_API_KEY ตั้งค่าแล้ว** — เป็น key ที่รัดกุม (ขั้นต่ำ 32 chars, สุ่มด้วย `secrets.token_urlsafe(32)`)
- [ ] **TRANSLATE_API_KEY ตั้งค่าแล้ว** — key จริงของ OpenAI/Gemini
- [ ] **CORS จำกัด origin** — ใน `main.py` เปลี่ยน `allow_origins=["*"]` เป็น domain จริง:
  ```python
  allow_origins=[
      "https://thai-translator.yourdomain.com",  # Cloudflare Tunnel
      "app://thaivoice.translator",              # Android (ถ้าใช้ custom scheme)
  ]
  ```
- [ ] **Rate limiting เปิดอยู่** — default 30 connections/IP/min ตรวจสอบที่ `security.py` ปรับเพิ่ม/ลดได้ด้วย `RATE_LIMIT_PER_MINUTE`
- [ ] **Firewall Windows** — ตรวจสอบว่า port 8000 ไม่ได้เปิดเข้าโดยตรงจากอินเทอร์เน็ต (ทุกอย่างควรผ่าน tunnel เท่านั้น)
- [ ] **`.env` ไม่อยู่ใน git** — เช็คว่า `.env` อยู่ใน `.gitignore` แล้ว
- [ ] **Cloudflare → TLS/SSL** — ตั้งค่าเป็น **Full (strict)** ใน Cloudflare Dashboard (SSL/TLS → Overview)
- [ ] **Docker logs เข้ารหัสข้อมูลไหม?** — ตรวจสอบว่า API keys ไม่หลุดใน logs (`docker compose logs`)
- [ ] **Model files** — Piper `.onnx` + `.json` อยู่ใน `models/` และ mount เป็น `:ro` (read-only)

---

## Step 8: Verify End-to-End

หลังจาก deploy ครบ:

### 8.1 ทดสอบ HTTP Endpoint
```bash
# จากเครื่องนอกบ้าน (หรือใช้ curl ผ่าน tunnel)
curl https://thai-translator.yourdomain.com/health
# → {"status":"ok"}
```

### 8.2 ทดสอบ Authentication
```bash
# ไม่มี API key → ควรถูกปฏิเสธ
curl https://thai-translator.yourdomain.com/admin/rate-limits
# → {"error":"unauthorized", ...} (401)

# มี API key → สำเร็จ
curl -H "X-API-Key: your-key-here" \
  https://thai-translator.yourdomain.com/admin/rate-limits
# → {"auth_enabled":true,"stats":{...}} (200)
```

### 8.3 ทดสอบ WebSocket
```bash
# ใช้ websocat หรือ wscat
wscat -c "wss://thai-translator.yourdomain.com/ws/audio?api_key=your-key-here"
# → ควรเชื่อมต่อสำเร็จ (ไม่ถูกปิดด้วย code 4001)
```

### 8.4 ทดสอบ Android App
1. Build APK ด้วย Android Studio
2. ติดตั้งบนมือถือ
3. ปิด WiFi — ใช้ 4G/5G (เพื่อทดสอบจากนอกเครือข่ายบ้าน)
4. กดปุ่มบันทึกเสียง → ควรได้ยินเสียงไทยตอบกลับภายใน 1-2 วิ

---

## Troubleshooting

| ปัญหา | วิธีตรวจสอบ |
|---|---|
| **Container ไม่เริ่ม** | `docker compose logs backend` |
| **WebSocket 4001 (Unauthorized)** | เช็ค `?api_key=...` ตรงกับ `BACKEND_API_KEY` ใน `.env` |
| **WebSocket 4002 (Rate Limit)** | รอ 60 วิ แล้วลองใหม่ / เพิ่ม `RATE_LIMIT_PER_MINUTE` |
| **Tunnel เชื่อมไม่ติด** | `docker compose logs cloudflared` — เช็ค token ถูกต้องไหม, domain ชี้ Cloudflare หรือยัง |
| **wss:// เชื่อมไม่ได้** | Cloudflare DNS ต้องเป็น proxied (⚡️ orange cloud) และ SSL/TLS ต้องเป็น Full |
| **ไม่มีเสียงไทย** | เช็ค Piper model ใน `models/` และ TRANSLATE_API_KEY valid |
| **Latency > 5 วิ** | ดู Module 12 (Latency Optimization) — ใช้ Colab เทียบ GPU vs CPU |
| **AudioTrack เล่นเสียงแตก** | เช็ค sample rate ตรงกัน (16kHz) และ PCM format (16-bit signed) |

---

## สรุปคำสั่งที่ใช้บ่อย

```bash
# เริ่มระบบ
cd ~/thai-voice-translator/backend
docker compose up --build -d

# ดูสถานะ
docker compose ps
docker compose logs --tail=50 backend

# ดู logs แบบ real-time
docker compose logs -f

# หยุดระบบ
docker compose down

# รีสตาร์ทหลังจากแก้ .env หรือ config
docker compose down && docker compose up --build -d

# อัปเดตโค้ดจาก GitHub
cd ~/thai-voice-translator
git pull origin main
cd backend
docker compose up --build -d
```

---

## Architecture (Production)

```
                    Internet
                       │
                       ▼
              ☁️ Cloudflare Tunnel
              (wss://thai-translator.yourdomain.com:443)
                       │
                       ▼
              ┌────────────────────┐
              │   HP All-in-One    │
              │                    │
              │  ┌──────────────┐  │
              │  │ cloudflared  │  │  ← Docker container
              │  │ (tunnel)     │  │     network_mode: service:backend
              │  └──────┬───────┘  │
              │         │          │
              │  ┌──────▼───────┐  │
              │  │   backend    │  │  ← Docker container
              │  │   (uvicorn)  │  │     port 8000 (ภายใน)
              │  │   port 8000  │  │
              │  └──────────────┘  │
              │                    │
              └────────────────────┘

Android App ──wss──► Cloudflare ──HTTP──► cloudflared ──HTTP──► backend:8000
                       Tunnel                                  (FastAPI + WS)
```

---

## Next Steps (หลัง Deploy สำเร็จ)

1. **Module 12 (Optional)** — Latency Optimization: วัดเวลา ASR/TTS บน GPU เทียบ CPU, ทำ pipeline overlap
2. **Monitor** — ตั้ง `docker compose logs -f` หรือใช้ Grafana + Prometheus ถ้าต้องการ metrics จริงจัง
3. **CI/CD** — GitHub Actions build Android APK ให้อัตโนมัติ (ดู `.github/workflows/`)
