"""
Module 3 Test Script — Run on Colab GPU T4
==========================================
Upload this to Colab scratchpad via MCP, then run to verify:
1. WhisperModel loads on CUDA (GPU T4)
2. Transcription works with a test audio file
3. Measure latency

Usage (on Colab):
    !pip install faster-whisper numpy soundfile
    # Upload or generate a test .wav file at 16kHz mono
    python test_whisper_colab.py --audio test_audio.wav
"""

import time
import sys
import argparse
import numpy as np

# Ensure faster-whisper is installed
try:
    from faster_whisper import WhisperModel
except ImportError:
    print("Installing faster-whisper...")
    import subprocess
    subprocess.check_call([sys.executable, "-m", "pip", "install", "faster-whisper"])
    from faster_whisper import WhisperModel


def generate_test_audio(output_path: str, duration: float = 3.0, sample_rate: int = 16000):
    """
    Generate a test WAV file with a simple tone + silence pattern.
    This is just for testing the pipeline — real testing uses actual speech.
    """
    import struct
    import wave

    t = np.linspace(0, duration, int(sample_rate * duration), endpoint=False)
    # Mix of frequencies to simulate speech-like signal
    signal = (
        np.sin(2 * np.pi * 200 * t) * 0.3 +
        np.sin(2 * np.pi * 500 * t) * 0.2 +
        np.sin(2 * np.pi * 800 * t) * 0.1
    ).astype(np.float32)
    # Normalize
    signal = signal / np.abs(signal).max() * 0.8
    samples_i16 = (signal * 32767).astype(np.int16)

    with wave.open(output_path, "wb") as wf:
        wf.setnchannels(1)
        wf.setsampwidth(2)
        wf.setframerate(sample_rate)
        wf.writeframes(samples_i16.tobytes())

    print(f"Generated test audio: {output_path} ({duration}s, {sample_rate}Hz mono)")


def pcm_to_samples(audio_bytes: bytes) -> np.ndarray:
    """Convert PCM 16-bit mono bytes → float32 numpy array (mirrors whisper_service.py)."""
    samples_i16 = np.frombuffer(audio_bytes, dtype=np.int16)
    return samples_i16.astype(np.float32) / 32768.0


def test_transcription(audio_path: str) -> dict:
    """
    Run transcription test on Colab GPU.
    Returns timing + result dict.
    """
    import wave

    print("=" * 60)
    print("Module 3 — Whisper ASR Test on Colab GPU T4")
    print("=" * 60)

    # ── Load audio ───────────────────────────────────────────────────
    t0 = time.time()
    with wave.open(audio_path, "rb") as wf:
        assert wf.getnchannels() == 1, "Audio must be mono"
        assert wf.getframerate() == 16000, "Sample rate must be 16kHz"
        audio_bytes = wf.readframes(wf.getnframes())
    print(f"Audio loaded: {len(audio_bytes)} bytes ({len(audio_bytes) / 32000:.1f}s)")

    # ── Load model on CUDA ───────────────────────────────────────────
    t1 = time.time()
    print("Loading WhisperModel (base, int8) on CUDA...")
    try:
        model = WhisperModel("base", device="cuda", compute_type="int8")
        device = "cuda"
    except Exception:
        print("CUDA failed, falling back to CPU")
        model = WhisperModel("base", device="cpu", compute_type="int8")
        device = "cpu"
    model_load_time = time.time() - t1
    print(f"Model loaded on {device} in {model_load_time:.2f}s")

    # ── Transcribe ───────────────────────────────────────────────────
    t2 = time.time()
    samples = pcm_to_samples(audio_bytes)
    segments, info = model.transcribe(samples, beam_size=5, vad_filter=True)
    text_parts = [seg.text.strip() for seg in segments]
    full_text = " ".join(text_parts)
    transcribe_time = time.time() - t2
    total_time = time.time() - t0

    # ── Results ──────────────────────────────────────────────────────
    print(f"\nResults:")
    print(f"  Device:            {device}")
    print(f"  Language detected: {info.language} (p={info.language_probability:.3f})")
    print(f"  Duration (audio):  {info.duration:.2f}s")
    print(f"  Text:              '{full_text[:100]}'")
    print(f"  Model load time:   {model_load_time:.2f}s")
    print(f"  Transcribe time:   {transcribe_time:.2f}s")
    print(f"  Real-time factor:  {info.duration / transcribe_time:.1f}x" if transcribe_time > 0 else "")
    print(f"  Total time:        {total_time:.2f}s")
    print(f"\n{'✅ PASS' if device == 'cuda' else '⚠️  CPU fallback — slower but functional'}")

    return {
        "device": device,
        "language": info.language,
        "text": full_text,
        "model_load_s": model_load_time,
        "transcribe_s": transcribe_time,
        "total_s": total_time,
    }


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--audio", default=None, help="Path to test .wav file")
    parser.add_argument("--generate", action="store_true", help="Generate a test tone first")
    args = parser.parse_args()

    if args.generate or args.audio is None:
        generate_test_audio("test_tone.wav", duration=3.0)
        audio_path = "test_tone.wav"
    else:
        audio_path = args.audio

    result = test_transcription(audio_path)
