package com.thaivoice.translator.network

import android.os.Handler
import android.os.Looper
import android.util.Log
import okhttp3.*
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * OkHttp WebSocket client connecting to the Thai Voice Translator backend.
 *
 * Handles the full duplex audio stream:
 * - **Upload:** binary PCM chunks → backend (via [sendAudioChunk])
 * - **Download:** binary PCM audio ← backend (via [onAudioReceived] callback)
 * - **Status:** JSON ack/error messages ← backend (logged for debugging)
 *
 * # Connection URL
 * ```
 * ws://<host>:8000/ws/audio?api_key=<BACKEND_API_KEY>
 * ```
 * During development, point to the Codespaces forwarded URL.
 * In production (Module 13), switch to `wss://` via Cloudflare Tunnel.
 *
 * # Auto-Reconnect
 * If the connection drops unexpectedly, the client schedules a reconnect
 * with **exponential backoff**: 1s → 2s → 4s → 8s → 16s (max 5 attempts).
 * The counter resets on successful connection. Calling [disconnect] cancels
 * any pending reconnect.
 *
 * # Dependencies (add to app/build.gradle.kts)
 * ```kotlin
 * dependencies {
 *     implementation("com.squareup.okhttp3:okhttp:4.12.0")
 * }
 * ```
 * Note: [org.json.JSONObject] is part of the Android SDK — no extra dep needed.
 *
 * # Usage (combined with AudioRecorder from Module 9)
 * ```kotlin
 * val wsClient = TranslatorWebSocketClient().apply {
 *     onAudioReceived = { pcmBytes -> audioPlayer.playChunk(pcmBytes) }
 *     onConnectionStateChanged = { connected -> updateUI(connected) }
 * }
 *
 * wsClient.connect(
 *     serverUrl = "ws://10.0.2.2:8000",  // host.docker.internal for emulator
 *     apiKey = BuildConfig.BACKEND_API_KEY
 * )
 *
 * recorder.startRecording { chunk -> wsClient.sendAudioChunk(chunk) }
 * ```
 *
 * @see okhttp3.WebSocket
 * @see com.thaivoice.translator.audio.AudioRecorder
 */
class TranslatorWebSocketClient {

    // ── Connection State ────────────────────────────────────────────────

    /** Internal state machine for connection lifecycle. */
    private enum class State {
        DISCONNECTED,   // Not connected and not trying
        CONNECTING,     // Handshake in progress
        CONNECTED,      // WebSocket open and ready
        RECONNECTING    // Waiting for backoff timer before retry
    }

    @Volatile private var state = State.DISCONNECTED

    // ── OkHttp ──────────────────────────────────────────────────────────

    /** Single shared OkHttpClient — reused across reconnects. */
    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)   // TCP handshake timeout
            .readTimeout(30, TimeUnit.SECONDS)      // Idle read timeout (long — audio stream)
            .writeTimeout(10, TimeUnit.SECONDS)     // Write timeout
            .pingInterval(30, TimeUnit.SECONDS)     // Keep-alive ping every 30s
            .retryOnConnectionFailure(false)         // We handle retries ourselves
            .build()
    }

    @Volatile private var webSocket: WebSocket? = null

    // ── Reconnect State ─────────────────────────────────────────────────

    private val reconnectAttempts = AtomicInteger(0)
    private val reconnectHandler = Handler(Looper.getMainLooper())
    private var reconnectRunnable: Runnable? = null
    private var pendingServerUrl: String? = null
    private var pendingApiKey: String? = null
    private val isDisconnecting = AtomicBoolean(false)

    /** Max reconnect attempts before giving up. */
    var maxReconnectAttempts: Int = 5

    /** Base delay in milliseconds for exponential backoff (1s). */
    var reconnectBaseDelayMs: Long = 1_000L

    /** Max delay cap — no single delay exceeds this (30s). */
    var reconnectMaxDelayMs: Long = 30_000L

    // ── Callbacks ───────────────────────────────────────────────────────

    /**
     * Invoked when synthesized Thai audio is received from the backend.
     * The [ByteArray] contains raw PCM 16kHz 16-bit mono audio — feed
     * directly into [AudioTrack][android.media.AudioTrack] for playback.
     */
    var onAudioReceived: ((ByteArray) -> Unit)? = null

    /**
     * Invoked whenever the connection state changes.
     * `true` = connected and ready; `false` = disconnected.
     */
    var onConnectionStateChanged: ((Boolean) -> Unit)? = null

    /**
     * Invoked on non-fatal errors or server-side error messages.
     * Example: translation API failure, TTS timeout, etc.
     */
    var onError: ((String) -> Unit)? = null

    // ── Public API ─────────────────────────────────────────────────────

    /**
     * Open a WebSocket connection to the backend server.
     *
     * The API key is appended as a query parameter to the URL:
     * ```
     * ws://host:8000/ws/audio?api_key=...
     * ```
     *
     * Calling [connect] while already connected or connecting is a no-op.
     * Call [disconnect] first to switch servers.
     *
     * @param serverUrl Base URL of the backend, e.g. `"ws://10.0.2.2:8000"`.
     *                  Protocol (`ws://` or `wss://`) must be included.
     * @param apiKey   Shared secret matching BACKEND_API_KEY on the server.
     */
    fun connect(serverUrl: String, apiKey: String) {
        // Guard: already connected/connecting
        if (state == State.CONNECTED || state == State.CONNECTING) {
            Log.w(TAG, "Already ${state.name.lowercase()} — call disconnect() first to reconnect")
            return
        }

        // Cancel any pending reconnect
        cancelReconnect()

        isDisconnecting.set(false)
        reconnectAttempts.set(0)
        pendingServerUrl = serverUrl
        pendingApiKey = apiKey

        openConnection(serverUrl, apiKey)
    }

    /**
     * Send a raw PCM audio chunk to the backend as a binary WebSocket frame.
     *
     * The backend pipeline will: transcribe → translate → synthesize → return audio.
     * Expected ~1-second latency per chunk.
     *
     * Safe to call from any thread. If not connected, logs a warning and drops
     * the chunk (no buffering — real-time audio is useless if delayed).
     *
     * @param chunk Raw PCM 16kHz 16-bit mono audio bytes (~32,000 bytes per second).
     */
    fun sendAudioChunk(chunk: ByteArray) {
        val ws = webSocket
        if (state != State.CONNECTED || ws == null) {
            Log.w(TAG, "sendAudioChunk: not connected — dropping ${chunk.size}B chunk")
            return
        }

        val sent = ws.send(ByteString.of(*chunk))
        if (!sent) {
            Log.w(TAG, "sendAudioChunk: WebSocket buffer full — dropping ${chunk.size}B chunk")
            // OkHttp queues internally; false = queue is full (unlikely with small chunks)
        } else {
            Log.v(TAG, "Sent ${chunk.size}B PCM audio chunk")
        }
    }

    /**
     * Gracefully close the WebSocket connection and cancel any pending reconnect.
     *
     * Sends a close frame (code 1000 — Normal Closure) to the server.
     * Idempotent — safe to call from any thread, even if already disconnected.
     *
     * After calling this, [connect] can be called again with a different URL/key.
     */
    fun disconnect() {
        Log.d(TAG, "disconnect() called (state=$state)")
        isDisconnecting.set(true)
        cancelReconnect()

        val ws = webSocket
        webSocket = null

        if (ws != null) {
            try {
                ws.close(1000, "Client disconnect")
            } catch (e: Exception) {
                Log.w(TAG, "Error closing WebSocket: ${e.message}")
            }
        }

        state = State.DISCONNECTED
        notifyConnectionState(false)
    }

    /** Returns `true` if the WebSocket is currently open and ready. */
    fun isConnected(): Boolean = state == State.CONNECTED

    // ── Internal ───────────────────────────────────────────────────────

    private fun openConnection(serverUrl: String, apiKey: String) {
        state = State.CONNECTING
        notifyConnectionState(false)

        // Append API key as query param (checked by security.py before accept())
        val url = buildUrl(serverUrl, apiKey)
        val request = Request.Builder()
            .url(url)
            .header("ngrok-skip-browser-warning", "true")  // Bypass ngrok free-tier interstitial
            .build()

        Log.i(TAG, "Connecting to $serverUrl ...")

        webSocket = httpClient.newWebSocket(request, object : WebSocketListener() {

            // ── Lifecycle ───────────────────────────────────────────────

            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.i(TAG, "WebSocket opened — connected to ${response.request.url}")
                state = State.CONNECTED
                reconnectAttempts.set(0)  // Reset backoff on success
                notifyConnectionState(true)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.i(TAG, "WebSocket closed: code=$code reason=\"$reason\"")
                handleDisconnect()
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                val responseCode = response?.code?.let { "HTTP $it" } ?: "no response"
                Log.e(TAG, "WebSocket failure: ${t.message} ($responseCode)", t)

                // Distinguish auth errors from network errors
                when (response?.code) {
                    401, 403 -> {
                        // Auth failure — don't retry (wrong API key)
                        Log.e(TAG, "Authentication failed — check BACKEND_API_KEY")
                        onError?.invoke("Authentication failed: ${t.message}")
                        state = State.DISCONNECTED
                        notifyConnectionState(false)
                        return  // Skip reconnect
                    }
                }

                handleDisconnect()
            }

            // ── Messages ────────────────────────────────────────────────

            override fun onMessage(webSocket: WebSocket, text: String) {
                // JSON status from backend: ack, result, or error
                handleTextMessage(text)
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                // Binary = synthesized Thai audio (PCM) from backend TTS
                val audioBytes = bytes.toByteArray()
                Log.v(TAG, "Received ${audioBytes.size}B PCM audio (${audioBytes.size / 32000f}s)")
                try {
                    onAudioReceived?.invoke(audioBytes)
                } catch (e: Exception) {
                    Log.w(TAG, "onAudioReceived callback threw: ${e.message}")
                }
            }
        })
    }

    // ── Reconnect Logic ─────────────────────────────────────────────────

    private fun handleDisconnect() {
        // If we initiated the disconnect, stay disconnected
        if (isDisconnecting.get()) {
            Log.d(TAG, "Client-initiated disconnect — not reconnecting")
            state = State.DISCONNECTED
            notifyConnectionState(false)
            return
        }

        // Check reconnect budget
        val attempts = reconnectAttempts.incrementAndGet()
        if (attempts > maxReconnectAttempts) {
            Log.e(TAG, "Max reconnect attempts ($maxReconnectAttempts) reached — giving up")
            state = State.DISCONNECTED
            notifyConnectionState(false)
            onError?.invoke("Connection lost — max reconnect attempts exceeded")
            return
        }

        // Exponential backoff: baseDelay * 2^(attempts-1), capped at maxDelay
        val delayMs = (reconnectBaseDelayMs * (1L shl (attempts - 1)))
            .coerceAtMost(reconnectMaxDelayMs)

        Log.i(TAG, "Reconnect #$attempts in ${delayMs}ms (backoff: ${delayMs}ms)")

        state = State.RECONNECTING

        // Schedule reconnect on main thread looper
        val serverUrl = pendingServerUrl
        val apiKey = pendingApiKey

        if (serverUrl == null || apiKey == null) {
            Log.e(TAG, "No saved connection params — cannot reconnect")
            state = State.DISCONNECTED
            notifyConnectionState(false)
            return
        }

        val runnable = Runnable {
            if (!isDisconnecting.get() && state == State.RECONNECTING) {
                Log.d(TAG, "Attempting reconnect #$attempts...")
                openConnection(serverUrl, apiKey)
            }
        }

        reconnectRunnable = runnable
        reconnectHandler.postDelayed(runnable, delayMs)
    }

    private fun cancelReconnect() {
        reconnectRunnable?.let { reconnectHandler.removeCallbacks(it) }
        reconnectRunnable = null
    }

    // ── Message Handling ────────────────────────────────────────────────

    private fun handleTextMessage(text: String) {
        try {
            val json = JSONObject(text)
            val type = json.optString("type", "unknown")

            when (type) {
                "result" -> {
                    // Full pipeline result — includes text, thai, timings, errors
                    val receivedBytes = json.optInt("received_bytes", 0)
                    val originalText = json.optString("text", "")
                    val thaiText = json.optString("thai", "")
                    val audioBytes = json.optInt("audio_bytes", 0)
                    val timings = json.optJSONObject("timings")
                    val errors = json.optJSONArray("errors")

                    Log.i(
                        TAG,
                        "Result: recv=${receivedBytes}B text='${originalText.take(40)}' " +
                            "thai='${thaiText.take(40)}' audio=${audioBytes}B " +
                            "timings=${timings?.toString() ?: "n/a"}"
                    )

                    // Forward server-side errors to callback
                    if (errors != null && errors.length() > 0) {
                        for (i in 0 until errors.length()) {
                            val errMsg = errors.optString(i) ?: continue
                            Log.w(TAG, "Server pipeline error: $errMsg")
                            onError?.invoke(errMsg)
                        }
                    }
                }

                "ack" -> {
                    // Simple acknowledgement (from early Module 2 protocol)
                    val recvBytes = json.optInt("received_bytes", 0)
                    Log.v(TAG, "Server ack: $recvBytes bytes received")
                }

                "error" -> {
                    // Fatal server error — server is closing the connection
                    val message = json.optString("message", "Unknown server error")
                    Log.e(TAG, "Server error: $message")
                    onError?.invoke(message)
                }

                else -> {
                    Log.d(TAG, "Unknown JSON message type='$type': ${text.take(200)}")
                }
            }
        } catch (e: Exception) {
            // Not JSON — probably a debug message or plain text
            Log.d(TAG, "Non-JSON text message: ${text.take(200)}")
        }
    }

    // ── Helpers ─────────────────────────────────────────────────────────

    private fun buildUrl(serverUrl: String, apiKey: String): String {
        val base = serverUrl.trimEnd('/')
        val path = "/ws/audio"
        return "$base$path?api_key=$apiKey"
    }

    private fun notifyConnectionState(connected: Boolean) {
        try {
            onConnectionStateChanged?.invoke(connected)
        } catch (e: Exception) {
            Log.w(TAG, "onConnectionStateChanged callback threw: ${e.message}")
        }
    }

    // ── Logging ─────────────────────────────────────────────────────────

    companion object {
        const val TAG = "TranslatorWS"
    }
}

// ============================================================================
// Integration Guide: AudioRecorder → WebSocket → AudioPlayer (Modules 9–11)
// ============================================================================
//
// Below is a sketch showing how all three modules connect in a typical
// Activity. This pattern avoids coupling classes together — each module
// owns one responsibility.
//
// ```kotlin
// class MainActivity : AppCompatActivity() {
//
//     private val recorder = AudioRecorder()
//     private val wsClient = TranslatorWebSocketClient()
//     private val player = AudioPlayer()  // Module 11
//
//     override fun onCreate(savedInstanceState: Bundle?) {
//         super.onCreate(savedInstanceState)
//
//         // 1. Configure WebSocket callbacks
//         wsClient.apply {
//             onAudioReceived = { pcmBytes ->
//                 // Received Thai audio from backend → play immediately
//                 player.playChunk(pcmBytes)
//             }
//             onConnectionStateChanged = { connected ->
//                 runOnUiThread {
//                     statusText.text = if (connected) "🟢 Connected" else "🔴 Disconnected"
//                 }
//             }
//             onError = { msg ->
//                 runOnUiThread { Toast.makeText(this@MainActivity, msg, Toast.LENGTH_SHORT).show() }
//             }
//         }
//
//         // 2. Connect to backend (URL depends on environment)
//         wsClient.connect(
//             serverUrl = "ws://10.0.2.2:8000",  // Emulator → host machine
//             // For physical device: "wss://your-domain.com" (Cloudflare Tunnel)
//             apiKey = BuildConfig.BACKEND_API_KEY
//         )
//
//         // 3. Start recording → chunks → WebSocket
//         if (hasRecordPermission(this)) {
//             recorder.startRecording { chunk -> wsClient.sendAudioChunk(chunk) }
//         }
//     }
//
//     override fun onDestroy() {
//         super.onDestroy()
//         recorder.stopRecording()
//         wsClient.disconnect()
//         player.release()
//     }
// }
// ```
//
// # build.gradle.kts (app-level) dependencies needed:
// ```kotlin
// dependencies {
//     implementation("com.squareup.okhttp3:okhttp:4.12.0")
//     implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
//     implementation("androidx.core:core-ktx:1.13.1")
//     implementation("androidx.activity:activity-ktx:1.9.0")
// }
// ```
//
// # BuildConfig.BACKEND_API_KEY
// To avoid hardcoding the key, add to app/build.gradle.kts:
// ```kotlin
// android {
//     defaultConfig {
//         buildConfigField("String", "BACKEND_API_KEY", "\"${System.getenv("BACKEND_API_KEY") ?: "dev-key"}\"")
//     }
// }
// ```
