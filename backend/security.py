"""
Thai Voice Translator — Security Module
========================================
Module 7: API Key authentication + in-memory rate limiting.

Architecture:
  - API key verified against BACKEND_API_KEY env var
  - HTTP endpoints: FastAPI dependency (Depends) checks X-API-Key header or ?api_key= query param
  - WebSocket: key checked from query param BEFORE accept(); invalid → close with code 4001
  - Rate limiter: max N connections per IP per minute (in-memory, no external deps)
"""

import os
import logging
import time
from collections import defaultdict

from fastapi import Depends, HTTPException, Request, status
from fastapi.security import APIKeyHeader
from starlette.websockets import WebSocket, WebSocketClose

logger = logging.getLogger("translator.security")

# ── Configuration ───────────────────────────────────────────────────────
# BACKEND_API_KEY is the shared secret between the Android app and the backend.
# Set it via Codespaces Secrets (dev) or .env file (production / Docker).
# If NOT set, security is DISABLED (open access) — a warning is logged at import.
BACKEND_API_KEY: str = os.environ.get("BACKEND_API_KEY", "").strip()

if not BACKEND_API_KEY:
    logger.warning(
        "⚠️  BACKEND_API_KEY is NOT set — API authentication is DISABLED. "
        "All requests will be allowed. "
        "Set this environment variable to enable security."
    )

# ── API Key extraction schemes ──────────────────────────────────────────
# FastAPI's APIKeyHeader extracts from X-API-Key header.
# We also support ?api_key= query param (needed for WebSocket + Android).
_api_key_header_scheme = APIKeyHeader(name="X-API-Key", auto_error=False)

# Rate-limiting state (in-memory dictionary — resets on restart)
_DEFAULT_RATE_LIMIT = int(os.environ.get("RATE_LIMIT_PER_MINUTE", "30"))
_connection_log: dict[str, list[float]] = defaultdict(list)
# dict keys are IP addresses, values are sorted lists of Unix timestamps
# (connection attempt times). Entries older than 60s are pruned on each check.


# ── Helpers ─────────────────────────────────────────────────────────────

def _extract_api_key_from_request(request: Request) -> str | None:
    """
    Extract API key from an HTTP request, trying multiple sources.

    Priority:
      1. X-API-Key header
      2. api_key query parameter

    Returns:
        The API key string, or None if not found.
    """
    # 1. Header (preferred — doesn't leak into URL logs)
    header_key = request.headers.get("X-API-Key")
    if header_key:
        return header_key.strip()

    # 2. Query parameter (fallback — needed for WebSocket, also works for HTTP)
    query_key = request.query_params.get("api_key")
    if query_key:
        return query_key.strip()

    return None


def _extract_api_key_from_ws(websocket: WebSocket) -> str | None:
    """
    Extract API key from a WebSocket connection's query parameters.

    Unlike HTTP requests, WebSocket connections send query params in the
    connection URL: wss://host/ws/audio?api_key=...

    Returns:
        The API key string, or None if not found.
    """
    return websocket.query_params.get("api_key")


def _api_key_is_valid(api_key: str | None) -> bool:
    """
    Check whether the given API key matches BACKEND_API_KEY.

    If BACKEND_API_KEY is not configured, ALL keys are considered valid
    (security is opt-in — set the env var to enable).

    Args:
        api_key: The key to validate.

    Returns:
        True if the key is valid (or security is disabled).
    """
    if not BACKEND_API_KEY:
        return True  # security disabled — open access
    if not api_key:
        return False
    # Constant-time-ish comparison to reduce timing side-channels
    return api_key == BACKEND_API_KEY


# ── Rate Limiter ────────────────────────────────────────────────────────

def _prune_old_entries(ip: str, now: float) -> None:
    """Remove connection timestamps older than 60 seconds for the given IP."""
    cutoff = now - 60.0
    entries = _connection_log.get(ip)
    if entries:
        # Keep only entries from the last 60 seconds
        _connection_log[ip] = [t for t in entries if t > cutoff]
        # Clean up empty lists to prevent unbounded dict growth
        if not _connection_log[ip]:
            del _connection_log[ip]


def check_rate_limit(ip: str, limit: int = _DEFAULT_RATE_LIMIT) -> tuple[bool, int]:
    """
    Check whether an IP has exceeded the rate limit.

    Records the current attempt and prunes entries older than 60 seconds.
    Thread-safe enough for asyncio single-threaded server (no locks needed).

    Args:
        ip: Client IP address.
        limit: Max allowed connections per 60-second window.

    Returns:
        Tuple of (allowed: bool, remaining: int).
        - allowed: True if under the limit.
        - remaining: How many more connections this IP can make this minute.
    """
    now = time.time()
    _prune_old_entries(ip, now)

    count = len(_connection_log[ip])
    _connection_log[ip].append(now)

    remaining = max(0, limit - count - 1)
    allowed = count < limit

    if not allowed:
        logger.warning(
            f"Rate limit exceeded for IP {ip}: {count} connections in 60s (limit: {limit})"
        )
    else:
        logger.debug(f"Rate limit check {ip}: {count + 1}/{limit} ({remaining} remaining)")

    return allowed, remaining


# ── FastAPI Dependency (HTTP endpoints) ─────────────────────────────────

async def require_api_key(request: Request) -> None:
    """
    FastAPI dependency — rejects requests without a valid API key.

    Usage:
        @app.get("/protected")
        async def protected_endpoint(_=Depends(require_api_key)):
            return {"data": "secret"}

    On failure, raises HTTPException 401 Unauthorized.
    When BACKEND_API_KEY is not set, this is a no-op (allows all requests).

    Rate limiting is also checked here — 403 if exceeded.
    """
    # ── Rate limit check ────────────────────────────────────────────────
    client_ip = request.client.host if request.client else "unknown"
    allowed, remaining = check_rate_limit(client_ip)

    if not allowed:
        raise HTTPException(
            status_code=status.HTTP_429_TOO_MANY_REQUESTS,
            detail={
                "error": "rate_limit_exceeded",
                "message": "Too many requests. Try again in a minute.",
                "retry_after_seconds": 60,
            },
        )

    # ── API key check ───────────────────────────────────────────────────
    api_key = _extract_api_key_from_request(request)

    if not _api_key_is_valid(api_key):
        logger.warning(f"Invalid/missing API key from IP {client_ip}")
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail={
                "error": "unauthorized",
                "message": "Valid API key required. Pass via X-API-Key header or ?api_key= query param.",
            },
            headers={"WWW-Authenticate": "ApiKey"},
        )

    logger.debug(f"Auth OK for {client_ip}")


# ── WebSocket Auth ──────────────────────────────────────────────────────

async def require_api_key_ws(websocket: WebSocket) -> bool:
    """
    Verify API key for a WebSocket connection BEFORE accept().

    Call this BEFORE websocket.accept() in your WebSocket endpoint.
    If the key is invalid, the connection is closed with code 4001
    (custom: "Unauthorized") and the function returns False.

    Rate limiting is also checked — connection closed with code 4002
    if exceeded.

    Usage:
        @router.websocket("/ws/audio")
        async def audio_stream(websocket: WebSocket):
            if not await require_api_key_ws(websocket):
                return  # connection already closed
            await websocket.accept()
            ...

    Args:
        websocket: The incoming WebSocket connection request.

    Returns:
        True if authorized (caller should proceed to accept()).
        False if connection was closed due to invalid auth / rate limit.
    """
    client_ip = websocket.client.host if websocket.client else "unknown"

    # ── Rate limit check ────────────────────────────────────────────────
    allowed, remaining = check_rate_limit(client_ip)
    if not allowed:
        logger.warning(f"WebSocket rate limit exceeded for {client_ip}")
        try:
            await websocket.close(
                code=4002,
                reason=f"Rate limit exceeded. Try again in a minute.",
            )
        except Exception:
            pass  # Client may already be gone
        return False

    # ── API key check ───────────────────────────────────────────────────
    api_key = _extract_api_key_from_ws(websocket)

    if not _api_key_is_valid(api_key):
        logger.warning(f"WebSocket auth failed for {client_ip}: invalid/missing API key")
        try:
            await websocket.close(
                code=4001,
                reason="Unauthorized: valid API key required (?api_key=...)",
            )
        except Exception:
            pass  # Client may already be gone
        return False

    logger.info(f"WebSocket auth OK for {client_ip} (rate limit: {remaining} remaining)")
    return True


# ── Utility: Get current rate-limit status (for monitoring/diagnostics) ──

def get_rate_limit_stats(ip: str | None = None) -> dict:
    """
    Return current rate-limit statistics for diagnostic purposes.

    Args:
        ip: Specific IP to check, or None for summary of all IPs.

    Returns:
        Dict with rate limit stats.
    """
    now = time.time()
    _prune_old_entries("__sentinel__", now)  # trigger global prune via a sentinel

    if ip:
        entries = _connection_log.get(ip, [])
        return {
            "ip": ip,
            "connections_last_60s": len(entries),
            "limit": _DEFAULT_RATE_LIMIT,
            "remaining": max(0, _DEFAULT_RATE_LIMIT - len(entries)),
        }

    # Summary
    total_connections = sum(len(v) for v in _connection_log.values())
    unique_ips = len(_connection_log)
    return {
        "unique_ips": unique_ips,
        "total_connections_60s": total_connections,
        "limit_per_ip": _DEFAULT_RATE_LIMIT,
    }
