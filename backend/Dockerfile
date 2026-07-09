# ============================================================================
# Thai Voice Translator — Production Docker Image
# ============================================================================
# Module 8: Containerised deployment for the HP All-in-One home server.
#
# Build:
#   docker build -t thai-voice-translator .
#
# Run (standalone):
#   docker run -d -p 8000:8000 --env-file .env -v ./models:/app/models thai-voice-translator
#
# Run (compose — recommended):
#   docker compose up --build -d
# ============================================================================

FROM python:3.12-slim

# ── Metadata ──────────────────────────────────────────────────────────────
LABEL org.opencontainers.image.title="Thai Voice Translator"
LABEL org.opencontainers.image.description="Real-time speech-to-speech translation pipeline (ASR → Translate → TTS)"
LABEL org.opencontainers.image.version="0.1.0"

# ── System Dependencies ───────────────────────────────────────────────────
# ffmpeg         — audio format conversion (ffmpeg-python wrapper)
# libsndfile1    — audio file I/O (soundfile → faster-whisper)
# libgomp1       — OpenMP runtime (Piper TTS)
# curl / ca-certs — download Piper binary + HTTPS API calls
RUN apt-get update \
    && apt-get install -y --no-install-recommends \
        ffmpeg \
        libsndfile1 \
        libgomp1 \
        curl \
        ca-certificates \
    && apt-get clean \
    && rm -rf /var/lib/apt/lists/*

# ── Piper TTS Binary ──────────────────────────────────────────────────────
# Piper is the TTS engine that converts Thai text → PCM audio.
# We install the standalone Linux binary from GitHub so it's available as
# the `piper` CLI command (called via subprocess in tts_service.py).
#
# The pip package `piper-tts` is also installed (via requirements.txt) for
# the Python bindings, but the CLI binary is what tts_service.py shells out to.
ARG PIPER_VERSION=1.2.0
RUN curl -fsSL \
        "https://github.com/rhasspy/piper/releases/download/v${PIPER_VERSION}/piper_linux_x86_64.tar.gz" \
        -o /tmp/piper.tar.gz \
    && tar -xzf /tmp/piper.tar.gz -C /tmp/ \
    && ( \
        # Some releases unpack to piper/piper, others flat
        find /tmp -name piper -type f -executable -exec cp {} /usr/local/bin/piper \; \
    ) \
    && chmod +x /usr/local/bin/piper \
    && rm -rf /tmp/piper.tar.gz /tmp/piper \
    && echo "Piper binary installed at $(which piper)" \
    || echo "WARNING: Piper binary download failed — TTS will be unavailable until manually installed"

# ── Application ───────────────────────────────────────────────────────────
WORKDIR /app

# Python dependencies layer (cached separately — only rebuilds when requirements.txt changes)
COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

# Application code (changes frequently — copy last to maximise layer caching)
COPY *.py ./

# Model files directory (mounted as volume at runtime — keep as placeholder)
RUN mkdir -p /app/models

# ── Runtime ───────────────────────────────────────────────────────────────
EXPOSE 8000

# Health check — confirms the server is accepting connections
HEALTHCHECK --interval=30s --timeout=5s --start-period=10s --retries=3 \
    CMD curl -f http://localhost:8000/health || exit 1

# Security: run as non-root user (best practice for production)
RUN useradd --create-home --shell /bin/bash appuser \
    && chown -R appuser:appuser /app
USER appuser

# Start the FastAPI server
# --host 0.0.0.0 is REQUIRED in Docker (otherwise not reachable from outside the container)
# --workers 1 because we manage concurrency with asyncio + run_in_executor
CMD ["uvicorn", "main:app", "--host", "0.0.0.0", "--port", "8000"]
