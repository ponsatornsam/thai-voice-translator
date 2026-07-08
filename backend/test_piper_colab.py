"""
Module 5 Test Script — Run on Colab GPU T4
==========================================
Upload to Colab scratchpad via MCP, then run to verify:
1. Piper TTS is installed and working
2. Thai voice model synthesizes speech correctly
3. Measure synthesis latency

Usage (on Colab):
    # First install Piper and download Thai model
    !pip install piper-tts
    !wget https://huggingface.co/rhasspy/piper-voices/resolve/main/th/th_TH/vat/model.onnx -O th_TH.onnx
    !wget https://huggingface.co/rhasspy/piper-voices/resolve/main/th/th_TH/vat/model.json -O th_TH.json

    python test_piper_colab.py --model th_TH.onnx --config th_TH.json --text "สวัสดีครับ ทดสอบเสียงภาษาไทย"
"""

import time
import sys
import argparse
import subprocess
import os


def check_piper() -> bool:
    """Verify piper binary is available."""
    try:
        result = subprocess.run(["piper", "--help"], capture_output=True, timeout=5)
        return result.returncode == 0
    except (FileNotFoundError, subprocess.TimeoutExpired):
        return False


def install_piper():
    """Install Piper TTS on Colab."""
    print("Installing Piper TTS...")
    subprocess.check_call([sys.executable, "-m", "pip", "install", "piper-tts"],
                          stdout=subprocess.DEVNULL)
    time.sleep(1)
    if not check_piper():
        print("ERROR: Piper install failed — binary not found after install")
        sys.exit(1)
    print("Piper TTS installed successfully")


def download_thai_model(model_path: str, config_path: str):
    """Download Thai female voice model from HuggingFace."""
    import urllib.request

    base_url = "https://huggingface.co/rhasspy/piper-voices/resolve/main/th/th_TH/vat"

    if not os.path.isfile(model_path):
        print(f"Downloading Thai voice model ({model_path})...")
        urllib.request.urlretrieve(f"{base_url}/model.onnx", model_path)
        print(f"  Done: {os.path.getsize(model_path) / 1024 / 1024:.1f} MB")

    if not os.path.isfile(config_path):
        print(f"Downloading model config ({config_path})...")
        urllib.request.urlretrieve(f"{base_url}/model.json", config_path)
        print(f"  Done: {os.path.getsize(config_path)} bytes")

    print("Thai voice model ready")


def synthesize(text: str, model_path: str, config_path: str) -> tuple[int, float]:
    """
    Run Piper TTS and return (audio_bytes_count, elapsed_seconds).
    """
    t0 = time.time()
    result = subprocess.run(
        [
            "piper",
            "--model", model_path,
            "--config", config_path,
            "--output-raw",
        ],
        input=text.strip(),
        capture_output=True,
        timeout=30,
        check=True,
    )
    elapsed = time.time() - t0
    audio_len = len(result.stdout)
    duration = audio_len / 32000  # 16kHz × 2 bytes = 32000 bytes/sec
    return audio_len, elapsed, duration


def test_piper(model_path: str, config_path: str):
    """Full Piper TTS test suite."""
    print("=" * 60)
    print("Module 5 — Piper TTS Test on Colab")
    print("=" * 60)

    test_texts = [
        "สวัสดีครับ ยินดีต้อนรับสู่ระบบแปลเสียงภาษาไทย",
        "วันนี้อากาศดีมากเลยนะครับ",
        "Hello, this is a test of the Thai voice system.",
    ]

    for i, text in enumerate(test_texts):
        print(f"\n--- Test {i + 1}: '{text[:60]}...' " + "-" * 20)
        try:
            audio_len, elapsed, duration = synthesize(text, model_path, config_path)
            rtf = duration / elapsed if elapsed > 0 else 0
            print(f"  Audio: {audio_len} bytes ({duration:.1f}s)")
            print(f"  Time:  {elapsed:.3f}s")
            print(f"  RTF:   {rtf:.1f}x (real-time factor)")
            if rtf >= 1.0:
                print(f"  ✅ Faster than real-time")
            else:
                print(f"  ⚠️  Slower than real-time")
        except Exception as e:
            print(f"  ❌ Failed: {e}")

    print("\n" + "=" * 60)
    print("Piper TTS test complete!")


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--model", default="th_TH.onnx", help="Path to .onnx model")
    parser.add_argument("--config", default="th_TH.json", help="Path to .json config")
    parser.add_argument("--text", default=None, help="Single text to synthesize (skip test suite)")
    args = parser.parse_args()

    # Ensure Piper is installed
    if not check_piper():
        install_piper()

    # Ensure model is downloaded
    download_thai_model(args.model, args.config)

    # Run test
    if args.text:
        audio_len, elapsed, duration = synthesize(args.text, args.model, args.config)
        print(f"Synthesis: {audio_len} bytes audio in {elapsed:.3f}s (RTF: {duration / elapsed:.1f}x)")
    else:
        test_piper(args.model, args.config)
