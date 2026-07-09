#!/usr/bin/env python3
"""
Thai Voice Translator — Latency Benchmark Suite
=================================================
Module 12: Measure real-world latency of each pipeline step on GPU vs CPU.

Run on Colab (T4 GPU) or any Linux machine with CUDA:
    python benchmark_latency.py --device cuda

Run on CPU-only machine (Codespaces, HP machine without GPU):
    python benchmark_latency.py --device cpu

Prerequisites:
    pip install faster-whisper numpy soundfile httpx

Expected Output:
    ┌──────────┬──────────┬──────────┬──────────┐
    │ Step     │ CPU (s)  │ GPU (s)  │ Speedup  │
    ├──────────┼──────────┼──────────┼──────────┤
    │ ASR      │ 1.234    │ 0.345    │ 3.6×     │
    │ Translate│ 0.567    │ 0.567    │ 1.0×     │
    │ TTS      │ 0.432    │ 0.432    │ 1.0×     │
    │ TOTAL    │ 2.233    │ 1.344    │ 1.7×     │
    └──────────┴──────────┴──────────┴──────────┘

NOTE: Translation API timing is mocked unless TRANSLATE_API_KEY is set.
      TTS timing uses a subprocess call to Piper — the model must be
      present in models/ directory.
"""

import argparse
import logging
import os
import subprocess
import sys
import tempfile
import time
from dataclasses import dataclass, field
from pathlib import Path

import numpy as np

logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s [%(levelname)s] %(name)s: %(message)s",
)
logger = logging.getLogger("benchmark")

# ── Configuration ───────────────────────────────────────────────────────
SAMPLE_RATE = 16000
CHUNK_DURATION_SEC = 1.0  # Match Android AudioRecorder
NUM_WARMUP_RUNS = 2
NUM_BENCHMARK_RUNS = 5


@dataclass
class StepResult:
    """Timing for one pipeline step over multiple runs."""

    name: str
    times: list[float] = field(default_factory=list)

    @property
    def mean(self) -> float:
        return sum(self.times) / len(self.times) if self.times else 0.0

    @property
    def min(self) -> float:
        return min(self.times) if self.times else 0.0

    @property
    def max(self) -> float:
        return max(self.times) if self.times else 0.0

    def fmt(self, key: str = "mean") -> str:
        val = getattr(self, key)
        return f"{val:.3f}s"


@dataclass
class BenchmarkResult:
    """Full benchmark run for one device (CPU or GPU)."""

    device: str
    asr: StepResult = field(default_factory=lambda: StepResult("ASR"))
    translate: StepResult = field(default_factory=lambda: StepResult("Translate"))
    tts: StepResult = field(default_factory=lambda: StepResult("TTS"))

    @property
    def total_mean(self) -> float:
        return self.asr.mean + self.translate.mean + self.tts.mean

    def report(self) -> str:
        lines = [
            f"",
            f"{'='*60}",
            f"  Benchmark Results — {self.device.upper()}",
            f"{'='*60}",
            f"  {'Step':<12} {'Mean':>8} {'Min':>8} {'Max':>8} {'Runs':>6}",
            f"  {'-'*44}",
        ]
        for step in [self.asr, self.translate, self.tts]:
            lines.append(
                f"  {step.name:<12} {step.fmt('mean'):>8} "
                f"{step.fmt('min'):>8} {step.fmt('max'):>8} "
                f"{len(step.times):>6}"
            )
        lines.append(f"  {'-'*44}")
        lines.append(f"  {'TOTAL':<12} {self.total_mean:.3f}s")
        lines.append(f"{'='*60}")
        return "\n".join(lines)


# ── Synthetic Audio Generator ───────────────────────────────────────────


def generate_test_audio(duration_sec: float = CHUNK_DURATION_SEC) -> bytes:
    """
    Generate synthetic PCM audio for benchmarking.

    Uses a sine wave at 440 Hz (A4) to simulate speech-like audio.
    Real speech has more frequency content, but the byte count and
    processing load are similar.

    Returns:
        Raw PCM 16-bit 16kHz mono bytes.
    """
    n_samples = int(SAMPLE_RATE * duration_sec)
    t = np.linspace(0, duration_sec, n_samples, endpoint=False)
    # Mix of frequencies to approximate speech: 200 Hz + 800 Hz + 1500 Hz
    samples = (
        0.5 * np.sin(2 * np.pi * 200 * t)
        + 0.3 * np.sin(2 * np.pi * 800 * t)
        + 0.2 * np.sin(2 * np.pi * 1500 * t)
    )
    # Normalize to [-1, 1] and convert to 16-bit PCM
    samples = samples / np.max(np.abs(samples)) * 0.8
    samples_i16 = (samples * 32767).astype(np.int16)
    return samples_i16.tobytes()


# ── ASR Benchmark ───────────────────────────────────────────────────────


def benchmark_asr(
    audio_bytes: bytes,
    device: str = "cpu",
    model_size: str = "base",
    compute_type: str = "int8",
    num_warmup: int = NUM_WARMUP_RUNS,
    num_runs: int = NUM_BENCHMARK_RUNS,
) -> StepResult:
    """Benchmark Faster-Whisper transcription."""
    from faster_whisper import WhisperModel

    logger.info(f"Loading Whisper model '{model_size}' on {device} ({compute_type})...")
    t0 = time.perf_counter()
    try:
        model = WhisperModel(model_size, device=device, compute_type=compute_type)
        load_time = time.perf_counter() - t0
        logger.info(f"Model loaded in {load_time:.2f}s")
    except Exception as e:
        logger.error(f"Failed to load model on {device}: {e}")
        return StepResult("ASR")

    result = StepResult("ASR")

    # Convert bytes → numpy (matching whisper_service._pcm_to_samples)
    samples = np.frombuffer(audio_bytes, dtype=np.int16).astype(np.float32) / 32768.0

    # Warmup
    logger.info(f"Warming up ({num_warmup} runs)...")
    for i in range(num_warmup):
        _ = list(model.transcribe(samples, beam_size=5, vad_filter=True))

    # Benchmark
    logger.info(f"Benchmarking ({num_runs} runs)...")
    for i in range(num_runs):
        t0 = time.perf_counter()
        segments, info = model.transcribe(samples, beam_size=5, vad_filter=True)
        text = " ".join(seg.text.strip() for seg in segments)
        elapsed = time.perf_counter() - t0
        result.times.append(elapsed)
        logger.debug(
            f"  Run {i+1}: {elapsed:.3f}s "
            f"(lang={info.language} p={info.language_probability:.2f}) "
            f"text='{text[:50]}'"
        )

    return result


# ── Translation API Benchmark ───────────────────────────────────────────


def benchmark_translate(
    text: str = "Hello, how are you today? The weather is beautiful.",
    api_key: str | None = None,
    api_base: str = "https://api.openai.com/v1",
    model: str = "gpt-4o-mini",
    num_warmup: int = 1,
    num_runs: int = NUM_BENCHMARK_RUNS,
) -> StepResult:
    """Benchmark translation API call (requires TRANSLATE_API_KEY)."""
    result = StepResult("Translate")

    if not api_key:
        api_key = os.environ.get("TRANSLATE_API_KEY", "")
    if not api_key:
        logger.warning("No TRANSLATE_API_KEY — using simulated timing (0.5s)")
        # Simulate typical API latency for benchmark comparison
        for i in range(num_runs):
            result.times.append(0.5 + np.random.uniform(-0.1, 0.1))
        return result

    import httpx

    url = f"{api_base.rstrip('/')}/chat/completions"
    headers = {
        "Authorization": f"Bearer {api_key}",
        "Content-Type": "application/json",
    }
    payload = {
        "model": model,
        "messages": [
            {
                "role": "system",
                "content": "Translate the following text into Thai. Return ONLY the Thai translation.",
            },
            {"role": "user", "content": text},
        ],
        "temperature": 0.1,
        "max_tokens": 256,
    }

    logger.info(f"Benchmarking translation API ({model})...")

    for i in range(num_warmup):
        try:
            async def _warmup():
                async with httpx.AsyncClient(timeout=10) as client:
                    await client.post(url, json=payload, headers=headers)
            import asyncio
            asyncio.run(_warmup())
        except Exception as e:
            logger.warning(f"Warmup failed (non-fatal): {e}")

    for i in range(num_runs):
        t0 = time.perf_counter()
        try:
            async def _call():
                async with httpx.AsyncClient(timeout=10) as client:
                    resp = await client.post(url, json=payload, headers=headers)
                    resp.raise_for_status()
                    return resp.json()
            import asyncio
            data = asyncio.run(_call())
            elapsed = time.perf_counter() - t0
            result.times.append(elapsed)
            translated = data["choices"][0]["message"]["content"].strip()
            logger.debug(f"  Run {i+1}: {elapsed:.3f}s → '{translated[:40]}'")
        except Exception as e:
            logger.error(f"  Run {i+1} failed: {e}")

    return result


# ── TTS Benchmark ───────────────────────────────────────────────────────


def benchmark_tts(
    text: str = "สวัสดีครับ วันนี้อากาศดีมาก",
    model_dir: str = "models",
    model_name: str = "th_TH",
    num_warmup: int = 1,
    num_runs: int = NUM_BENCHMARK_RUNS,
) -> StepResult:
    """Benchmark Piper TTS synthesis."""
    result = StepResult("TTS")

    model_path = os.path.join(model_dir, f"{model_name}.onnx")
    config_path = os.path.join(model_dir, f"{model_name}.json")

    if not os.path.isfile(model_path) or not os.path.isfile(config_path):
        logger.warning(
            f"Piper model not found at {model_path} — using simulated timing (0.3s)"
        )
        for i in range(num_runs):
            result.times.append(0.3 + np.random.uniform(-0.05, 0.05))
        return result

    logger.info(f"Benchmarking Piper TTS ({model_name})...")

    # Warmup
    for i in range(num_warmup):
        try:
            subprocess.run(
                ["piper", "--model", model_path, "--config", config_path, "--output-raw"],
                input=text.strip(),
                capture_output=True,
                timeout=10,
                check=True,
            )
        except Exception as e:
            logger.warning(f"Warmup failed: {e}")

    # Benchmark
    for i in range(num_runs):
        t0 = time.perf_counter()
        try:
            result_proc = subprocess.run(
                ["piper", "--model", model_path, "--config", config_path, "--output-raw"],
                input=text.strip(),
                capture_output=True,
                timeout=10,
                check=True,
            )
            elapsed = time.perf_counter() - t0
            result.times.append(elapsed)
            audio_len = len(result_proc.stdout)
            logger.debug(
                f"  Run {i+1}: {elapsed:.3f}s → {audio_len}B "
                f"({audio_len / 32000:.1f}s PCM)"
            )
        except Exception as e:
            logger.error(f"  Run {i+1} failed: {e}")

    return result


# ── Cross-Device Comparison Report ──────────────────────────────────────


def print_comparison(cpu_result: BenchmarkResult, gpu_result: BenchmarkResult):
    """Print a side-by-side comparison table."""
    print()
    print("=" * 70)
    print("  Cross-Device Comparison: CPU vs GPU (T4)")
    print("=" * 70)
    print(
        f"  {'Step':<12} {'CPU':>10} {'GPU':>10} {'Speedup':>10} {'Target':>10}"
    )
    print(f"  {'-'*52}")
    for step_name, cpu_step, gpu_step in [
        ("ASR", cpu_result.asr, gpu_result.asr),
        ("Translate", cpu_result.translate, gpu_result.translate),
        ("TTS", cpu_result.tts, gpu_result.tts),
    ]:
        cpu_mean = cpu_step.mean
        gpu_mean = gpu_step.mean
        speedup = cpu_mean / gpu_mean if gpu_mean > 0 else 1.0
        target = "< 0.5s" if step_name == "ASR" else "< 1.0s" if step_name == "Translate" else "< 0.5s"
        print(
            f"  {step_name:<12} {cpu_mean:>8.3f}s {gpu_mean:>8.3f}s "
            f"{speedup:>8.1f}× {target:>10}"
        )
    print(f"  {'-'*52}")
    cpu_total = cpu_result.total_mean
    gpu_total = gpu_result.total_mean
    speedup = cpu_total / gpu_total if gpu_total > 0 else 1.0
    target_total = "< 2.0s"
    print(
        f"  {'TOTAL':<12} {cpu_total:>8.3f}s {gpu_total:>8.3f}s "
        f"{speedup:>8.1f}× {target_total:>10}"
    )
    print("=" * 70)
    print()

    # Recommendation
    if gpu_total <= 2.0:
        print("✅ GPU latency is within the 2s target — production-ready!")
    elif gpu_total <= 3.0:
        print("⚠️  GPU latency is acceptable but could improve with overlap strategies.")
    else:
        print("🔴 Latency exceeds target. Consider Module 12 overlap strategies.")

    if cpu_total <= 3.0:
        print("✅ CPU latency is acceptable — the HP machine without GPU may work.")
    elif cpu_total <= 5.0:
        print("⚠️  CPU-only latency may feel sluggish. Recommend GPU or overlap strategies.")
    else:
        print("🔴 CPU-only is too slow for real-time use. GPU strongly recommended.")


# ── Main ────────────────────────────────────────────────────────────────


def main():
    parser = argparse.ArgumentParser(
        description="Benchmark Thai Voice Translator pipeline latency"
    )
    parser.add_argument(
        "--device",
        choices=["cpu", "cuda", "both"],
        default="both",
        help="Device to benchmark (default: both)",
    )
    parser.add_argument(
        "--model-size",
        choices=["tiny", "base", "small"],
        default="base",
        help="Whisper model size (default: base)",
    )
    parser.add_argument(
        "--runs",
        type=int,
        default=NUM_BENCHMARK_RUNS,
        help=f"Number of benchmark runs (default: {NUM_BENCHMARK_RUNS})",
    )
    parser.add_argument(
        "--skip-translate",
        action="store_true",
        help="Skip translation API benchmark (no API key)",
    )
    parser.add_argument(
        "--output-json",
        type=str,
        default=None,
        help="Save results to JSON file",
    )
    args = parser.parse_args()

    print()
    print("=" * 60)
    print("  Thai Voice Translator — Latency Benchmark")
    print(f"  Model: {args.model_size}  |  Runs: {args.runs}  |  Chunk: {CHUNK_DURATION_SEC}s")
    print("=" * 60)

    # Generate test audio
    audio_bytes = generate_test_audio(CHUNK_DURATION_SEC)
    logger.info(f"Generated {len(audio_bytes)} bytes synthetic audio")

    results = {}

    for device in (["cpu", "cuda"] if args.device == "both" else [args.device]):
        if device == "cuda":
            # Check CUDA availability
            try:
                import torch
                if not torch.cuda.is_available():
                    logger.warning("CUDA not available — skipping GPU benchmark")
                    continue
                logger.info(f"CUDA device: {torch.cuda.get_device_name(0)}")
            except ImportError:
                logger.warning("torch not installed — cannot check CUDA, trying anyway")

        result = BenchmarkResult(device=device)

        # ── ASR ─────────────────────────────────────────────────────────
        asr_result = benchmark_asr(
            audio_bytes,
            device=device,
            model_size=args.model_size,
            num_runs=args.runs,
        )
        if asr_result.times:
            result.asr = asr_result
            print(f"  ASR ({device}): mean={asr_result.mean:.3f}s "
                  f"min={asr_result.min:.3f}s max={asr_result.max:.3f}s")

        # ── Translate ───────────────────────────────────────────────────
        if not args.skip_translate:
            translate_result = benchmark_translate(num_runs=args.runs)
            if translate_result.times:
                result.translate = translate_result
                print(f"  Translate ({device}): mean={translate_result.mean:.3f}s "
                      f"min={translate_result.min:.3f}s max={translate_result.max:.3f}s")

        # ── TTS ─────────────────────────────────────────────────────────
        tts_result = benchmark_tts(num_runs=args.runs)
        if tts_result.times:
            result.tts = tts_result
            print(f"  TTS ({device}): mean={tts_result.mean:.3f}s "
                  f"min={tts_result.min:.3f}s max={tts_result.max:.3f}s")

        print(result.report())
        results[device] = result

    # ── Cross-device comparison ─────────────────────────────────────────
    if "cpu" in results and "cuda" in results:
        print_comparison(results["cpu"], results["cuda"])
    elif len(results) == 1:
        device = list(results.keys())[0]
        result = results[device]
        print(f"\nOnly {device.upper()} results available.")
        if result.total_mean <= 2.0:
            print(f"✅ {device.upper()} latency {result.total_mean:.3f}s — within 2s target!")
        else:
            print(f"⚠️  {device.upper()} latency {result.total_mean:.3f}s — consider GPU or overlap strategies.")

    # ── Save JSON ───────────────────────────────────────────────────────
    if args.output_json:
        import json
        output = {}
        for device, result in results.items():
            output[device] = {
                "asr": {
                    "mean": result.asr.mean,
                    "min": result.asr.min,
                    "max": result.asr.max,
                },
                "translate": {
                    "mean": result.translate.mean,
                    "min": result.translate.min,
                    "max": result.translate.max,
                },
                "tts": {
                    "mean": result.tts.mean,
                    "min": result.tts.min,
                    "max": result.tts.max,
                },
                "total_mean": result.total_mean,
            }
        with open(args.output_json, "w") as f:
            json.dump(output, f, indent=2)
        logger.info(f"Results saved to {args.output_json}")


if __name__ == "__main__":
    main()
