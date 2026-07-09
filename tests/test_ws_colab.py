#!/usr/bin/env python3
"""Quick WebSocket end-to-end test against the Colab+ngrok server."""
import asyncio, json, time
import numpy as np

# Try multiple websocket libraries
USE_WEBSOCKETS = False
USE_WS_CLIENT = False

try:
    import websockets
    USE_WEBSOCKETS = True
except ImportError:
    pass

if not USE_WEBSOCKETS:
    import websocket as ws_client
    USE_WS_CLIENT = True

API_KEY = "k3EOTM10GxevuDLUHj7g7TwZ16nfiq8bBGqEG0hAMoA"
WS_URL = f"wss://comment-scrubber-ninja.ngrok-free.dev/ws/audio?api_key={API_KEY}"

# Generate 1s synthetic audio
sr = 16000
t = np.linspace(0, 1.0, sr, endpoint=False)
samples = 0.6 * np.sin(2 * np.pi * 200 * t) + 0.3 * np.sin(2 * np.pi * 600 * t)
samples = (samples / np.max(np.abs(samples)) * 0.8 * 32767).astype(np.int16)
pcm_bytes = samples.tobytes()
print(f"Test audio: {len(pcm_bytes)} bytes (1s, 16kHz mono)")


async def test_with_websockets():
    """Use the modern websockets library."""
    # Try with extra_headers as keyword (v11+) first, then fallback
    t0 = time.perf_counter()
    try:
        async with websockets.connect(
            WS_URL,
            extra_headers={"ngrok-skip-browser-warning": "true"},
            ping_interval=30,
        ) as ws:
            await _run_test(ws, t0)
    except TypeError:
        # Older websockets version — try without extra_headers
        print("(older websockets version — trying without extra_headers)")
        try:
            async with websockets.connect(WS_URL, ping_interval=30) as ws:
                await _run_test(ws, t0)
        except Exception as e2:
            print(f"Both attempts failed. Try: pip install websockets --upgrade")
            print(f"  Error: {e2}")


async def _run_test(ws, t0):
    print(f"✅ Connected in {time.perf_counter() - t0:.1f}s\n")
    await ws.send(pcm_bytes)
    print(f"📤 Sent {len(pcm_bytes)}B PCM audio")

    t1 = time.perf_counter()
    resp = await asyncio.wait_for(ws.recv(), timeout=30)
    data = json.loads(resp)
    elapsed = time.perf_counter() - t1
    print(f"📥 JSON ({elapsed:.1f}s):")
    print(f"   type:       {data.get('type')}")
    print(f"   text:       \"{data.get('text', '')[:80]}\"")
    print(f"   thai:       \"{data.get('thai', '')[:80]}\"")
    print(f"   audio_bytes: {data.get('audio_bytes', 0)}")
    timings = data.get("timings", {})
    if timings:
        print(f"   ⏱️  timings:")
        for step, sec in timings.items():
            bar = "#" * int(sec * 20)
            print(f"      {step:<10} {sec:.3f}s {bar}")
    errors = data.get("errors")
    if errors:
        print(f"   ❌ errors: {errors}")
    try:
        audio = await asyncio.wait_for(ws.recv(), timeout=5)
        if isinstance(audio, bytes):
            dur = len(audio) / 32000
            print(f"\n🔊 Audio: {len(audio)}B ({dur:.1f}s PCM)")
    except asyncio.TimeoutError:
        print(f"\n⚠️  No TTS audio (Piper model may not be on Colab)")
    total = time.perf_counter() - t0
    print(f"\n{'='*50}")
    print(f"✅ Pipeline complete in {total:.1f}s!")
    print(f"{'='*50}")


def test_with_ws_client():
    """Use websocket-client (synchronous, works everywhere)."""
    import websocket as ws_client

    t0 = time.time()
    print(f"Connecting...")
    ws = ws_client.create_connection(
        WS_URL,
        header={"ngrok-skip-browser-warning": "true"},
        timeout=15,
    )
    print(f"✅ Connected in {time.time() - t0:.1f}s\n")

    ws.send_binary(pcm_bytes)
    print(f"📤 Sent {len(pcm_bytes)}B PCM audio")

    t1 = time.time()
    resp = ws.recv()
    data = json.loads(resp)
    elapsed = time.time() - t1
    print(f"📥 JSON ({elapsed:.1f}s):")
    print(f"   type:       {data.get('type')}")
    print(f"   text:       \"{data.get('text', '')[:80]}\"")
    print(f"   thai:       \"{data.get('thai', '')[:80]}\"")
    print(f"   audio_bytes: {data.get('audio_bytes', 0)}")
    timings = data.get("timings", {})
    if timings:
        print(f"   ⏱️  timings:")
        for step, sec in timings.items():
            bar = "#" * int(sec * 20)
            print(f"      {step:<10} {sec:.3f}s {bar}")
    errors = data.get("errors")
    if errors:
        print(f"   ❌ errors: {errors}")

    try:
        ws.settimeout(5)
        audio = ws.recv()
        if isinstance(audio, bytes):
            dur = len(audio) / 32000
            print(f"\n🔊 Audio: {len(audio)}B ({dur:.1f}s PCM)")
    except:
        print(f"\n⚠️  No TTS audio (Piper model may not be on Colab)")

    total = time.time() - t0
    ws.close()
    print(f"\n{'='*50}")
    print(f"✅ Pipeline complete in {total:.1f}s!")
    print(f"{'='*50}")


if __name__ == "__main__":
    if USE_WEBSOCKETS:
        asyncio.run(test_with_websockets())
    elif USE_WS_CLIENT:
        test_with_ws_client()
    else:
        print("Install: pip install websocket-client")
