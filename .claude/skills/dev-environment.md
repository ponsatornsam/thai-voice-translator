---
name: dev-environment
description: Local dev environment — HP All-in-One specs, Python/pip usage, Android adb, project navigation, and shell conventions for this machine.
---

# Dev Environment — HP All-in-One (Windows 11)

## Machine Specs

| Component | Detail |
|---|---|
| **Model** | HP All-in-One Desktop |
| **OS** | Windows 11 Home Single Language 10.0.26200 |
| **User** | `HP ALL IN ONE` |
| **Home** | `C:\Users\HP ALL IN ONE` |
| **Shell** | Git Bash (POSIX sh) — use Unix syntax: `/dev/null`, forward slashes, `$VAR` |
| **Primary Drive** | `D:\` (project drive) |

## Project Navigation

```bash
# เปิด terminal → เข้าโปรเจกต์
D:
cd D:\AI_Thai

# หรือพิมพ์เต็ม
cd /d/AI_Thai
```

### Key Paths

| สิ่งที่ต้องการ | Path |
|---|---|
| โปรเจกต์หลัก | `D:\AI_Thai` |
| Backend | `D:\AI_Thai\backend` |
| Android | `D:\AI_Thai\android` |
| Colab notebook | `D:\AI_Thai\colab\thai_translator_server.ipynb` |
| Skills | `D:\AI_Thai\.claude\skills` |
| Screenshot | `D:\AI_Thai\screenshot.png` |

## Python

### pip — ต้องใช้ `python -m pip` เสมอ!

```bash
# ❌ ผิด — ห้ามใช้
pip install something

# ✅ ถูก — ใช้แบบนี้เท่านั้น
python -m pip install something
python -m pip install -r requirements.txt
python -m pip list
python -m pip uninstall something
```

### Virtual Environment

```bash
# สร้าง venv
python -m venv .venv

# Activate (Git Bash)
source .venv/Scripts/activate

# Deactivate
deactivate
```

### Run Backend

```bash
cd /d/AI_Thai/backend
python -m pip install -r requirements.txt
uvicorn main:app --reload --host 0.0.0.0 --port 8000
```

### Dependency Notes
- `faster-whisper` ใช้ `ctranslate2` — **Windows อาจ segfault** → ตั้ง `WHISPER_DISABLED=1` ตอน dev บนเครื่องนี้
- `piper-tts` ต้องการ binary + voice model → dev จริงทำบน Colab
- แนะนำ: รัน backend เฉพาะส่วนที่ไม่ใช่โมเดลบนเครื่องนี้ (FastAPI, WebSocket) ส่วน Whisper/Piper รันบน Colab

## Android (adb)

### Connected Device
```
Xiaomi 23129RAA4G (sapphire_global)
  USB:  737a20bc
  WiFi: 192.168.1.20:5555
```

### คำสั่ง adb พื้นฐาน

```bash
# เช็ค device
adb devices -l

# เลือก device (ต้องระบุทุกครั้งถ้ามีหลายตัว)
adb -s 737a20bc <คำสั่ง>

# ลง APK
adb -s 737a20bc install -r app/build/outputs/apk/debug/app-debug.apk

# เปิดแอป
adb -s 737a20bc shell am start -n com.thaivoice.translator/.MainActivity

# ปิดแอป
adb -s 737a20bc shell am force-stop com.thaivoice.translator

# ดู log (เฉพาะแท็กของเรา)
adb -s 737a20bc logcat -s TranslatorWS:I MainActivity:* Diagnostics:* AudioCaptureService:*

# ดู log ล่าสุด
adb -s 737a20bc logcat -d | tail -50

# Screenshot → PNG
adb -s 737a20bc exec-out screencap -p > D:/AI_Thai/screenshot.png

# เช็คแพ็กเกจ
adb -s 737a20bc shell pm list packages | grep thaivoice
```

### Build APK

```bash
cd /d/AI_Thai/android
./gradlew assembleDebug
# APK อยู่ที่: app/build/outputs/apk/debug/app-debug.apk
```

### Build + ลงเครื่อง รวดเดียว

```bash
cd /d/AI_Thai/android && ./gradlew assembleDebug && adb -s 737a20bc install -r app/build/outputs/apk/debug/app-debug.apk && adb -s 737a20bc shell am start -n com.thaivoice.translator/.MainActivity
```

## Git

```bash
cd /d/AI_Thai
git status
git add <files>
git commit -m "..."
git push origin main

# Repo: https://github.com/ponsatornsam/thai-voice-translator
# Branch: main
# Git user: thestar
```

### Warning: GitHub Push Protection
- ห้าม commit API keys, tokens, credentials
- `.env.example` ใช้ placeholder เท่านั้น
- ถ้า push ถูก reject → หา secret ใน commit → `git commit --amend` ลบออก

## ngrok / API URLs

| Endpoint | URL |
|---|---|
| Health | `https://comment-scrubber-ninja.ngrok-free.dev/health` |
| Rate Limits | `https://comment-scrubber-ninja.ngrok-free.dev/admin/rate-limits?api_key=...` |
| Pipeline Status | `https://comment-scrubber-ninja.ngrok-free.dev/admin/pipeline-status?api_key=...` |
| WebSocket | `wss://comment-scrubber-ninja.ngrok-free.dev/ws/audio?api_key=...` |

> ⚠️ ngrok free tier — session หมดอายุ 6-12 ชม., URL เปลี่ยนทุกรัน ต้องอัปเดต `build.gradle.kts` ใหม่

### Test API from terminal

```bash
# ต้องใส่ header ngrok-skip-browser-warning เสมอ
curl -s -H "ngrok-skip-browser-warning: true" "https://comment-scrubber-ninja.ngrok-free.dev/health"
```

## Shell Notes

```bash
# ใช้ Git Bash — syntax แบบ Unix
# ✅ ถูก
ls /d/AI_Thai
cd /d/AI_Thai/backend
export VAR=value

# ❌ ผิด (cmd.exe syntax)
dir D:\AI_Thai
set VAR=value

# ตำแหน่งปัจจุบันค้างระหว่างคำสั่ง แต่ shell state (env vars) ไม่ persist
# ต้องการ env var → export ในคำสั่งเดียวกัน
cd /d/AI_Thai && export FOO=bar && python -c "import os; print(os.environ['FOO'])"
```

## Quick Start (เปิดโปรเจกต์ใหม่)

```bash
# 1. เข้าโปรเจกต์
D:
cd D:\AI_Thai

# 2. เช็ค git status
git status

# 3. เช็ค device (สำหรับ Android)
adb devices -l

# 4. ดูว่า backend รันอยู่ไหม
curl -s -H "ngrok-skip-browser-warning: true" "https://comment-scrubber-ninja.ngrok-free.dev/health"
```
