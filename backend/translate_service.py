"""
Thai Voice Translator — Translation Service
=============================================
Module 4: Translates transcribed text into Thai using an external LLM API
(OpenAI-compatible: GPT, Gemini via OpenAI endpoint, or any compatible provider).

Design:
  - API key from TRANSLATE_API_KEY env var (never hardcoded)
  - 5-second timeout to keep pipeline latency low
  - Falls back to original text on any error (does NOT crash the pipeline)
"""

import os
import logging
import httpx

logger = logging.getLogger("translator.translate")

# ── Configuration from environment ───────────────────────────────────
API_KEY = os.environ.get("TRANSLATE_API_KEY", "")
API_BASE = os.environ.get("TRANSLATE_API_BASE", "https://api.openai.com/v1")
MODEL = os.environ.get("TRANSLATE_MODEL", "gpt-4o-mini")  # fast & cheap
TIMEOUT_SEC = 5  # seconds — fail fast to keep total latency < 2s target

# ── Translation prompt ───────────────────────────────────────────────
SYSTEM_PROMPT = (
    "You are a professional translator. "
    "Translate the following text into Thai. "
    "Return ONLY the Thai translation, nothing else — no explanations, no notes, "
    "no romanization. Keep the translation natural and conversational, "
    "as if spoken by a native Thai speaker."
)


# ── Public API ───────────────────────────────────────────────────────
async def translate_to_thai(text: str, source_lang: str = "auto") -> str:
    """
    Translate text to Thai using an OpenAI-compatible API.

    Args:
        text: The source text to translate (any language).
        source_lang: Source language hint, or "auto" to let the model detect.
                     Currently informational — the system prompt handles detection.

    Returns:
        Thai translation string. On any error, returns the original text
        so the pipeline continues uninterrupted.
    """
    # ── Guard: empty input ───────────────────────────────────────────
    if not text or not text.strip():
        return ""

    # ── Guard: no API key configured ─────────────────────────────────
    if not API_KEY:
        logger.warning(
            "TRANSLATE_API_KEY not set — returning original text. "
            "Set this environment variable to enable translation."
        )
        return text

    # ── Build request ────────────────────────────────────────────────
    url = f"{API_BASE.rstrip('/')}/chat/completions"
    headers = {
        "Authorization": f"Bearer {API_KEY}",
        "Content-Type": "application/json",
    }
    payload = {
        "model": MODEL,
        "messages": [
            {"role": "system", "content": SYSTEM_PROMPT},
            {"role": "user", "content": text},
        ],
        "temperature": 0.1,           # Low temp = consistent translations
        "max_tokens": 512,            # More than enough for 1-2 sentences
    }

    # ── Call API with timeout ────────────────────────────────────────
    try:
        async with httpx.AsyncClient(timeout=TIMEOUT_SEC) as client:
            response = await client.post(url, json=payload, headers=headers)
            response.raise_for_status()

        data = response.json()
        translated = data["choices"][0]["message"]["content"].strip()
        logger.info(
            f"Translation: '{text[:50]}{'...' if len(text) > 50 else ''}' "
            f"→ '{translated[:80]}{'...' if len(translated) > 80 else ''}'"
        )
        return translated

    except httpx.TimeoutException:
        logger.warning(f"Translation API timeout after {TIMEOUT_SEC}s — returning original")
        return text

    except httpx.HTTPStatusError as e:
        logger.error(f"Translation API HTTP {e.response.status_code}: {e.response.text[:200]}")
        return text

    except Exception:
        logger.exception("Translation API unexpected error — returning original")
        return text


# ── Synchronous wrapper (for use with run_in_executor in Module 6) ───
def translate_to_thai_sync(text: str, source_lang: str = "auto") -> str:
    """
    Synchronous wrapper around translate_to_thai().
    Use this with loop.run_in_executor() to avoid blocking the event loop
    when calling the translation API from an async context.

    Args:
        text: Source text to translate.
        source_lang: Source language hint.

    Returns:
        Thai translation, or original text on error.
    """
    import asyncio

    try:
        # Try to get the running event loop
        loop = asyncio.get_running_loop()
    except RuntimeError:
        # No running loop — run async function directly
        return asyncio.run(translate_to_thai(text, source_lang))

    # We're inside an event loop — but we're being called from run_in_executor,
    # so we can safely create a new loop in this thread or just run synchronously.
    # Actually, since httpx.AsyncClient needs an event loop, create one:
    return asyncio.run(translate_to_thai(text, source_lang))
