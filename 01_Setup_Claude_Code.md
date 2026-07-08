# Setup Guide: ตั้งค่า Claude Code สำหรับโปรเจกต์แปลเสียงไทย

> ใช้เป็นทางเลือกแทน/เสริม GitHub Codespaces จากเอกสาร `00_Setup_MCP_Before_Project.md`
> เหมาะกับคนที่อยากได้ agent ที่คุมเองได้เต็มที่กว่า Copilot Agent mode

---

## Claude Code เหมาะกับ workflow นี้ตรงไหน

Claude Code รันในเครื่อง (หรือ Codespaces/VM ก็ได้) แต่ตัวโปรแกรมเองเบามาก ส่วนที่หนักจริง (Whisper, Piper)
ยังคงส่งไปรันผ่าน **Colab MCP** เหมือนเดิม ไม่ต่างจาก workflow ที่ทำกับ Codespaces

```
Terminal (เครื่องคุณ หรือ Codespaces)
    │
    ▼
Claude Code  ──MCP──►  Google Colab (GPU ฟรี)
    │                        │
    │ เขียน/แก้โค้ด            │ รัน Whisper, Piper
    ▼
GitHub (เก็บโค้ด)
```

---

## Part 1 — ติดตั้ง Claude Code

**macOS / Linux / WSL:**
```bash
curl -fsSL https://claude.ai/install.sh | bash
```

**Windows PowerShell:**
```powershell
irm https://claude.ai/install.ps1 | iex
```

ถ้าคอมไม่แรงจริง ๆ แนะนำติดตั้งใน **GitHub Codespaces แทนเครื่องตัวเอง** (ใช้คำสั่งเดียวกัน รันใน terminal ของ Codespaces ได้เลย) จะได้ไม่กิน resource เครื่อง

## Part 2 — Login

```bash
claude
```
ครั้งแรกจะให้เลือกวิธีล็อกอิน:
- ใช้ Claude subscription เดิม (Pro/Max/Team/Enterprise) — แนะนำถ้ามีอยู่แล้ว
- หรือ Anthropic Console account (จ่ายตาม API credit)

## Part 3 — เปิดในโฟลเดอร์โปรเจกต์

```bash
git clone <your-repo-url>
cd thai-voice-translator
claude
```
ต้อง `cd` เข้าโฟลเดอร์ project ก่อนเสมอ เพราะ Claude Code จะอ่านไฟล์ในโฟลเดอร์นั้นเป็นบริบทอัตโนมัติ

## Part 4 — สร้าง CLAUDE.md ให้โปรเจกต์นี้

พิมพ์ในเซสชัน:
```
/init
```
แล้วแก้ไขให้เหลือแค่สิ่งจำเป็น ตัวอย่างที่ควรมีสำหรับโปรเจกต์นี้:

```markdown
# Thai Voice Translator

## Tech Stack
- Backend: FastAPI + WebSocket, Python 3.12
- ASR: Faster-Whisper | Translation: Gemini/OpenAI API | TTS: Piper (ไทย)
- Android: Kotlin, AudioRecord, OkHttp WebSocket, AudioTrack
- Deploy: Docker + Cloudflare Tunnel บนเครื่อง HP ที่บ้าน

## โครงสร้างโปรเจกต์
project/android, project/backend, project/models, project/tests, project/docs

## กติกา
- งานที่ต้องรันโมเดล AI หนัก (Whisper, Piper) ให้ใช้ Colab MCP รันบน GPU แทนเครื่องนี้
- build APK จริง/ทดสอบ audio hardware ต้องใช้ Android Studio บนเครื่องจริง ไม่ใช่ที่นี่
- commit ขึ้น GitHub บ่อย ๆ

## คำสั่ง
- รัน backend: uvicorn main:app --reload --host 0.0.0.0 --port 8000
- ทดสอบ: pytest tests/
```

## Part 5 — เชื่อม Colab MCP เข้ากับ Claude Code

ติดตั้ง `uv` ก่อน (ถ้ายังไม่มี):
```bash
curl -LsSf https://astral.sh/uv/install.sh | sh
```

เพิ่ม MCP server ด้วยคำสั่ง:
```bash
claude mcp add colab-proxy-mcp -- uvx git+https://github.com/googlecolab/colab-mcp
```

หรือแก้ไฟล์ config เองที่ `~/.claude.json` (หรือ `.claude/mcp.json` ในโปรเจกต์ ถ้าอยากให้ผูกกับโปรเจกต์นี้เท่านั้น):
```json
{
  "mcpServers": {
    "colab-proxy-mcp": {
      "command": "uvx",
      "args": ["git+https://github.com/googlecolab/colab-mcp"],
      "timeout": 30000
    }
  }
}
```

เปิด Colab notebook ในเบราว์เซอร์ ตั้ง Runtime เป็น T4 GPU แล้วในเซสชัน Claude Code พิมพ์:
```
เชื่อมต่อกับ Colab MCP แล้วยืนยันว่ามองเห็น GPU T4
```

## Part 6 — เชื่อม Firebase MCP (ถ้าต้องใช้ Firebase services)

ถ้าโปรเจกต์ในอนาคตต้องใช้ Firestore/Auth ของ Firebase ด้วย เพิ่มได้อีกตัว:
```bash
claude mcp add firebase -- npx -y firebase-tools@latest mcp
```

---

## Checklist ก่อนเริ่ม Module 0 (เหมือนเดิมแต่ปรับสำหรับ Claude Code)

- [ ] `claude` เปิดและ login สำเร็จ
- [ ] อยู่ในโฟลเดอร์ project ที่ clone จาก GitHub แล้ว (`cd` เข้าถูกที่)
- [ ] มีไฟล์ `CLAUDE.md` ที่สรุป tech stack + กติกาของโปรเจกต์แล้ว
- [ ] เพิ่ม Colab MCP ด้วย `claude mcp add` เรียบร้อย
- [ ] เปิด Colab notebook ตั้ง Runtime เป็น T4 GPU ค้างไว้
- [ ] สั่งเชื่อมต่อ Colab MCP ในเซสชันสำเร็จ เห็น GPU จริงผ่าน `nvidia-smi`

---

## คำสั่งที่ใช้บ่อยระหว่างทำงาน

| คำสั่ง | ใช้ทำอะไร |
|---|---|
| `/clear` | ล้าง context เมื่อเปลี่ยนงาน (เช่น จบ backend จะไปทำ Android) |
| `/compact` | สรุปบทสนทนาย่อ ๆ เมื่อ context เริ่มเต็ม |
| `/mcp` | ดูสถานะ MCP server ที่เชื่อมอยู่ |
| `claude mcp list` | ดูรายชื่อ MCP server ที่ตั้งค่าไว้ทั้งหมด (รันนอกเซสชัน) |
| `/help` | ดูคำสั่งทั้งหมด |

---

## ไปต่อ

เมื่อครบ checklist แล้ว ให้เปิด `Firebase_Studio_AI_Prompts.md` แล้วเริ่มจาก **Module 0**
ใช้ prompt ชุดเดิมได้เลย เพียงแต่พิมพ์ในช่อง Claude Code แทนช่อง Copilot Chat
- โมดูลป้าย 🟢 (ต้องใช้ Colab MCP) — สั่ง "เชื่อมต่อ Colab MCP อีกครั้ง" ก่อนทุกครั้งที่เปิดเซสชันใหม่ เหมือนเดิม
- โมดูลป้าย 🔵 — ทำในเครื่อง/Codespaces ที่รัน Claude Code ได้ตรง ๆ

## ปัญหาที่เจอบ่อย

| อาการ | วิธีแก้ |
|---|---|
| `claude: command not found` | เปิด terminal ใหม่ หรือเพิ่ม PATH ตามข้อความตอนติดตั้ง |
| MCP server ไม่ขึ้นในลิสต์ | เช็คด้วย `claude mcp list` แล้วลอง `claude mcp remove` แล้ว add ใหม่ |
| Agent บอกไม่รู้จัก tool ของ Colab | พิมพ์ `/mcp` เช็คว่า server status เป็น connected ไหม |
| Session หลุดแล้วต้องเชื่อม Colab ใหม่ทุกครั้ง | เป็นพฤติกรรมปกติของ Colab MCP ปัจจุบัน ไม่ persist ข้าม session |
