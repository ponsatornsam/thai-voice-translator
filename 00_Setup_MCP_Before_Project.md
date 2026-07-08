# Setup Guide: เชื่อม MCP ก่อน แล้วค่อยเริ่มโปรเจกต์แปลเสียงไทย

> ใช้คู่กับเอกสาร `Firebase_Studio_AI_Prompts.md` (Prompt Pack 14 โมดูล)
> เอกสารนี้เป็นขั้นตอน **ก่อน** เริ่ม Module 0 — ต้องทำให้เสร็จก่อนถึงจะสั่ง AI เขียนโค้ดได้

---

## ทำไมต้องเชื่อม MCP ก่อน

โปรเจกต์นี้ต้องใช้ compute หนัก (Faster-Whisper, Piper TTS) ซึ่งคอมที่ไม่แรงรันเองไม่ไหว
เลยต้องผูกเครื่องมือ 2 ฝั่งเข้าด้วยกันก่อนเริ่มเขียนโค้ดจริง:

```
เบราว์เซอร์ (เครื่องคุณ)
    │
    ▼
GitHub Codespaces  ──MCP──►  Google Colab (GPU ฟรี)
    │                              │
    │  เขียน/รัน                    │  รัน Whisper, Piper
    │  Backend + Android            │  (โมเดล AI หนัก ๆ)
    ▼
GitHub (เก็บโค้ด)
```

ถ้าไม่เชื่อม MCP ก่อน AI ในเซสชันจะไม่มีสิทธิ์เข้าถึง Colab และจะพยายามรันโมเดลหนัก ๆ บนเครื่อง Codespaces เอง ซึ่งอาจช้าหรือ error เพราะไม่มี GPU

---

## Part 1 — ตั้งค่า GitHub Codespaces

### 1.1 สร้าง Repository

1. เข้า GitHub → New Repository → ตั้งชื่อ เช่น `thai-voice-translator`
2. เลือก Add README (จะได้มีอะไรให้ Codespaces เปิดได้ทันที)

### 1.2 เปิด Codespace

1. เข้า repository ที่สร้าง → กดปุ่มสีเขียว **Code** → แท็บ **Codespaces** → **Create codespace on main**
2. รอสักครู่ ระบบจะเปิด VS Code เต็มรูปแบบในเบราว์เซอร์ (ไม่ต้องติดตั้งอะไรในเครื่องเลย)

### 1.3 เปิดใช้งาน Copilot Agent Mode (ตัวที่คุย MCP ได้)

1. กดไอคอน Copilot Chat ด้านข้าง (หรือ `Ctrl+Alt+I`)
2. เปลี่ยนโหมดด้านล่างช่องแชทเป็น **Agent**
3. ถ้ายังไม่เคยเปิด Copilot ให้ sign in ด้วยบัญชี GitHub (ต้องมี Copilot subscription หรือ free tier ที่เปิดให้ใช้)

---

## Part 2 — เชื่อม Colab MCP Server เข้ากับ Codespaces

### 2.1 สร้างไฟล์ config MCP

ใน Codespaces เปิด Terminal (`` Ctrl+` ``) แล้วสร้างโฟลเดอร์/ไฟล์:

```bash
mkdir -p .vscode
```

สร้างไฟล์ `.vscode/mcp.json` ใส่เนื้อหานี้:

```json
{
  "servers": {
    "colab-proxy-mcp": {
      "type": "stdio",
      "command": "uvx",
      "args": ["git+https://github.com/googlecolab/colab-mcp"]
    }
  }
}
```

> หมายเหตุ: Codespaces ใช้ syntax `servers` (ไม่ใช่ `mcpServers` แบบ Antigravity/Firebase Studio) — ถ้า VS Code แจ้งว่า format ผิด ให้เช็คเวอร์ชัน Copilot extension แล้วดู syntax ล่าสุดจาก GitHub Docs

### 2.2 ติดตั้ง uv ใน Codespaces

```bash
curl -LsSf https://astral.sh/uv/install.sh | sh
source $HOME/.local/bin/env
```

### 2.3 เปิด Colab notebook คู่กัน

1. เปิดแท็บใหม่ไปที่ [colab.research.google.com](https://colab.research.google.com)
2. สร้าง notebook เปล่า (ยังไม่ต้องเขียนอะไร — Colab MCP จะสร้าง "scratchpad" notebook ให้เองตอนเชื่อมต่อ)
3. เปลี่ยน Runtime → Change runtime type → เลือก **T4 GPU** (ฟรี)

### 2.4 สั่งให้ Copilot Agent เชื่อมต่อ

กลับมาที่ Codespaces → พิมพ์ในช่อง Copilot Chat (โหมด Agent):

```
เชื่อมต่อกับ Colab MCP แล้วยืนยันว่ามองเห็น GPU T4 ได้
```

ถ้าเชื่อมสำเร็จ AI จะรายงานว่าเปิด scratchpad notebook ให้แล้ว และรัน `nvidia-smi` เช็ค GPU ได้

---

## Part 3 — Checklist ก่อนเริ่ม Module 0

ห้ามเริ่มเขียนโค้ดจนกว่าจะติ๊กครบทุกข้อ:

- [ ] Codespace เปิดใช้งานได้ พิมพ์โค้ดใน VS Code เบราว์เซอร์ได้ปกติ
- [ ] Copilot Chat อยู่ในโหมด **Agent** (ไม่ใช่ Ask/Edit)
- [ ] ไฟล์ `.vscode/mcp.json` มี Colab MCP server ตั้งไว้แล้ว
- [ ] เปิด Colab notebook แล้วตั้ง Runtime เป็น GPU (T4) แล้ว
- [ ] สั่งเชื่อมต่อผ่าน Agent สำเร็จ และเห็น GPU จริงผ่าน `nvidia-smi`
- [ ] repository มี README + โครงสร้างโฟลเดอร์เปล่าพร้อม (`android/ backend/ models/ tests/ docs/`)

---

## Part 4 — เริ่มโปรเจกต์จริง (ไปต่อที่ Prompt Pack)

เมื่อครบ checklist แล้ว ให้เปิดไฟล์ `Firebase_Studio_AI_Prompts.md` แล้วเริ่มจาก **Module 0** ได้เลย
โดยมีข้อแตกต่างจากเดิมที่ต้องปรับตอนสั่ง prompt:

1. **Module 0-8 (Backend + Docker)** — สั่งใน Codespaces ตามปกติ แต่งานหนัก (โหลด/รันโมเดล Whisper, Piper) ให้เติมท้าย prompt ว่า:
   > "งานส่วนที่ต้องรันโมเดล AI หนัก ๆ ให้ใช้ Colab MCP รันบน GPU ของ Colab แทนการรันในเครื่อง Codespaces"

2. **Module 9-11 (Android Kotlin)** — เขียนโค้ดใน Codespaces ได้ปกติ (Kotlin เป็น text ไม่หนัก ไม่ต้องพึ่ง Colab) แต่จำไว้ว่า **build APK จริง / รัน emulator ต้องใช้เครื่องอื่นหรือ CI** (Codespaces ไม่รองรับเต็มรูปแบบ)

3. **ทุกครั้งที่เปิดเซสชันใหม่** — ต้องสั่ง "เชื่อมต่อ Colab MCP อีกครั้ง" ก่อนเสมอ เพราะ connection ไม่ persist ข้าม session

---

## ปัญหาที่เจอบ่อย

| อาการ | สาเหตุที่เป็นไปได้ | วิธีแก้ |
|---|---|---|
| Agent บอกไม่รู้จัก tool ของ Colab MCP | ยังไม่ได้สลับเป็นโหมด Agent | เช็คโหมดด้านล่างช่องแชท |
| เชื่อมต่อไม่ติด / timeout | Colab notebook ยังไม่เปิดค้างไว้ | เปิด tab Colab ทิ้งไว้ตลอดระหว่างทำงาน |
| รันโค้ดแล้ว error เรื่อง GPU | ลืมเปลี่ยน Runtime type เป็น GPU | Runtime → Change runtime type → T4 GPU |
| Agent แก้โค้ดในไฟล์เดิมที่เคยมีอยู่ใน Colab ไม่ได้ | Colab MCP เชื่อมกับ scratchpad ใหม่เท่านั้น | copy โค้ดจาก scratchpad มาไว้ใน repo เองหลังรันเสร็จ |
| ต้อง restart runtime แต่ agent ทำให้ไม่ได้ | เป็นข้อจำกัดของ Colab MCP ปัจจุบัน | ไป restart เองในหน้าเว็บ Colab แล้วสั่ง agent เชื่อมต่อใหม่ |
