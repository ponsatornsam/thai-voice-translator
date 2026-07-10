package com.thaivoice.translator.network

import android.util.Log
import okhttp3.*
import okio.ByteString
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Comprehensive pipeline diagnostic tool.
 *
 * Runs a sequenced battery of checks against the Thai Voice Translator backend,
 * reporting each step's result to the UI via [onProgress] callback.
 *
 * # Checks (in order)
 * 1. **Server Health** — HTTP GET /health → verify `{"status":"ok"}`
 * 2. **API Authentication** — HTTP GET /admin/rate-limits?api_key=... → verify 200
 * 3. **WebSocket Handshake** — Open WS, confirm onOpen, close cleanly
 * 4. **ASR Pipeline** — Send test audio chunk, verify server responds with `result` JSON
 * 5. **Translation Ready** — Verify `thai` field present in response
 * 6. **TTS Ready** — Check `audio_bytes` field present (even if 0 for silence)
 *
 * # Usage
 * ```kotlin
 * val diag = Diagnostics()
 * diag.onProgress = { index, total, check ->
 *     runOnUiThread { updateDiagnosticsUI(check) }
 * }
 * diag.runAll(serverUrl, apiKey) { allPassed, results ->
 *     if (allPassed) startPipeline() else showErrors(results)
 * }
 * ```
 */
class Diagnostics {

    // ── Data Types ──────────────────────────────────────────────────────

    enum class CheckStatus { PENDING, RUNNING, PASS, FAIL, SKIPPED }

    data class CheckResult(
        val step: Int,
        val name: String,
        val emoji: String,
        val status: CheckStatus,
        val detail: String,
        val latencyMs: Long = -1
    )

    // ── Callback ───────────────────────────────────────────────────────

    /**
     * Fired after each individual check completes.
     * @param index 0-based index of the check just completed
     * @param total total number of checks
     * @param result the result of that check
     */
    var onProgress: ((Int, Int, CheckResult) -> Unit)? = null

    // ── Public API ─────────────────────────────────────────────────────

    /**
     * Run all diagnostic checks sequentially.
     *
     * @param serverUrl Backend URL, e.g. `"wss://comment-scrubber-ninja.ngrok-free.dev"`
     * @param apiKey    Shared secret for auth
     * @param onDone    Called with `(allPassed: Boolean, results: List<CheckResult>)`
     */
    fun runAll(
        serverUrl: String,
        apiKey: String,
        onDone: (Boolean, List<CheckResult>) -> Unit
    ) {
        val httpUrl = serverUrl
            .replace("wss://", "https://")
            .replace("ws://", "http://")

        Thread {
            val results = mutableListOf<CheckResult>()
            var allPassed = true
            val checks = buildCheckList()

            for ((index, template) in checks.withIndex()) {
                val result = runCheck(index, checks.size, template, httpUrl, serverUrl, apiKey)
                results.add(result)
                if (result.status == CheckStatus.FAIL) allPassed = false

                // Stop on auth failure (remaining checks will fail anyway)
                if (result.step == 1 && result.status == CheckStatus.FAIL) {
                    // Skip remaining checks
                    for (j in (index + 1) until checks.size) {
                        val skipped = checks[j]
                        val skipResult = CheckResult(
                            step = j, name = skipped.first, emoji = skipped.second,
                            status = CheckStatus.SKIPPED,
                            detail = "Skipped — authentication failed"
                        )
                        results.add(skipResult)
                        onProgress?.invoke(j, checks.size, skipResult)
                    }
                    break
                }
            }

            onDone(allPassed, results)
        }.start()
    }

    // ── Check Definitions ───────────────────────────────────────────────

    private fun buildCheckList(): List<Triple<String, String, String>> = listOf(
        Triple("Server Health", "🏥", "HTTP GET /health → 200 + {\"status\":\"ok\"}"),
        Triple("API Authentication", "🔑", "HTTP GET /admin/rate-limits → 200"),
        Triple("WebSocket Handshake", "🔌", "Open + close WebSocket → onOpen"),
        Triple("ASR (Whisper)", "🎙️", "Send test audio → result JSON with text field"),
        Triple("Translation API", "🌐", "Check translate service is wired (thai field)"),
        Triple("TTS (Piper)", "🔊", "Check TTS wired (audio_bytes field present)"),
    )

    // ── Individual Check Runners ────────────────────────────────────────

    private fun runCheck(
        index: Int,
        total: Int,
        template: Triple<String, String, String>,
        httpUrl: String,
        wsUrl: String,
        apiKey: String
    ): CheckResult {
        val (name, emoji, _) = template

        val result = when (index) {
            0 -> checkServerHealth(httpUrl, name, emoji)
            1 -> checkApiAuth(httpUrl, apiKey, name, emoji)
            2 -> checkWebSocket(wsUrl, apiKey, name, emoji)
            3, 4, 5 -> {
                // These three all come from the same pipeline test
                // Only run the actual test on step 3, cache result for 4 & 5
                if (index == 3) {
                    pipelineTestResult = checkPipeline(wsUrl, apiKey, name, emoji)
                }
                // Return the cached result with the current step's name/emoji
                val cached = pipelineTestResult ?: return CheckResult(
                    step = index, name = name, emoji = emoji,
                    status = CheckStatus.FAIL, detail = "Pipeline test did not run"
                )
                // Parse the relevant field for steps 4 & 5
                when (index) {
                    3 -> cached  // ASR — return as-is
                    4 -> checkTranslationField(cached, name, emoji)
                    5 -> checkTtsField(cached, name, emoji)
                    else -> cached
                }
            }
            else -> CheckResult(
                step = index, name = name, emoji = emoji,
                status = CheckStatus.FAIL, detail = "Unknown check"
            )
        }

        onProgress?.invoke(index, total, result)
        return result
    }

    // Cache pipeline test result so steps 4 & 5 don't re-run it
    private var pipelineTestResult: CheckResult? = null

    // ── Check 0: Server Health ──────────────────────────────────────────

    private fun checkServerHealth(
        httpUrl: String, name: String, emoji: String
    ): CheckResult {
        return try {
            val client = OkHttpClient.Builder()
                .connectTimeout(5, TimeUnit.SECONDS)
                .readTimeout(5, TimeUnit.SECONDS)
                .build()
            val request = Request.Builder()
                .url(httpUrl.trimEnd('/') + "/health")
                .header("ngrok-skip-browser-warning", "true")
                .build()

            val start = System.currentTimeMillis()
            val response = client.newCall(request).execute()
            val latency = System.currentTimeMillis() - start

            if (response.isSuccessful) {
                val body = response.body?.string() ?: ""
                if (body.contains("ok")) {
                    CheckResult(0, name, emoji, CheckStatus.PASS, "OK · ${latency}ms", latency)
                } else {
                    CheckResult(0, name, emoji, CheckStatus.FAIL,
                        "Unexpected response: ${body.take(100)}", latency)
                }
            } else {
                CheckResult(0, name, emoji, CheckStatus.FAIL,
                    "HTTP ${response.code}", latency)
            }
        } catch (e: IOException) {
            CheckResult(0, name, emoji, CheckStatus.FAIL, "Network error: ${e.message}")
        } catch (e: Exception) {
            CheckResult(0, name, emoji, CheckStatus.FAIL, "Error: ${e.message}")
        }
    }

    // ── Check 1: API Authentication ─────────────────────────────────────

    private fun checkApiAuth(
        httpUrl: String, apiKey: String, name: String, emoji: String
    ): CheckResult {
        return try {
            val client = OkHttpClient.Builder()
                .connectTimeout(5, TimeUnit.SECONDS)
                .readTimeout(5, TimeUnit.SECONDS)
                .build()
            val request = Request.Builder()
                .url(httpUrl.trimEnd('/') + "/admin/rate-limits?api_key=" + apiKey)
                .header("ngrok-skip-browser-warning", "true")
                .build()

            val start = System.currentTimeMillis()
            val response = client.newCall(request).execute()
            val latency = System.currentTimeMillis() - start

            when (response.code) {
                200 -> {
                    val body = response.body?.string() ?: ""
                    val json = try { JSONObject(body) } catch (_: Exception) { null }
                    val authEnabled = json?.optBoolean("auth_enabled", false) ?: false
                    val limit = json?.getJSONObject("stats")?.optInt("limit_per_ip", -1) ?: -1

                    val detail = buildString {
                        append("OK · ${latency}ms")
                        if (authEnabled) append(" · auth=on")
                        if (limit > 0) append(" · limit=$limit/IP")
                    }
                    CheckResult(1, name, emoji, CheckStatus.PASS, detail, latency)
                }
                401, 403 -> CheckResult(1, name, emoji, CheckStatus.FAIL,
                    "HTTP ${response.code} — invalid API key", latency)
                else -> CheckResult(1, name, emoji, CheckStatus.FAIL,
                    "HTTP ${response.code}", latency)
            }
        } catch (e: IOException) {
            CheckResult(1, name, emoji, CheckStatus.FAIL, "Network error: ${e.message}")
        } catch (e: Exception) {
            CheckResult(1, name, emoji, CheckStatus.FAIL, "Error: ${e.message}")
        }
    }

    // ── Check 2: WebSocket Handshake ────────────────────────────────────

    private fun checkWebSocket(
        wsUrl: String, apiKey: String, name: String, emoji: String
    ): CheckResult {
        val latch = CountDownLatch(1)
        val opened = AtomicBoolean(false)
        val errorMsg = AtomicReference<String?>(null)

        val client = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .build()

        val url = wsUrl.trimEnd('/') + "/ws/audio?api_key=" + apiKey
        val request = Request.Builder()
            .url(url)
            .header("ngrok-skip-browser-warning", "true")
            .build()

        val start = System.currentTimeMillis()
        var latency = -1L

        val ws = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                latency = System.currentTimeMillis() - start
                opened.set(true)
                // Close immediately — this is just a handshake test
                webSocket.close(1000, "Diagnostics handshake test")
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                latch.countDown()
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                errorMsg.set(
                    when (response?.code) {
                        401, 403 -> "HTTP ${response.code} — invalid API key"
                        404 -> "HTTP 404 — /ws/audio endpoint not found"
                        else -> t.message ?: "WebSocket error"
                    }
                )
                latch.countDown()
            }
        })

        // Wait up to 15s for handshake
        val timedOut = !latch.await(15, TimeUnit.SECONDS)

        return when {
            timedOut -> {
                ws.close(1000, "timeout")
                CheckResult(2, name, emoji, CheckStatus.FAIL,
                    "Timeout — no response in 15s")
            }
            opened.get() -> {
                CheckResult(2, name, emoji, CheckStatus.PASS,
                    "OK · handshake ${latency}ms", latency)
            }
            else -> {
                val err = errorMsg.get() ?: "Unknown error"
                CheckResult(2, name, emoji, CheckStatus.FAIL, err)
            }
        }
    }

    // ── Check 3-5: Pipeline Test (ASR → Translate → TTS) ────────────────

    // Fields extracted from the pipeline result for steps 4 & 5
    @Volatile private var pipelineThaiField: String? = null
    @Volatile private var pipelineAudioBytes: Int = -1

    private fun checkPipeline(
        wsUrl: String, apiKey: String, name: String, emoji: String
    ): CheckResult {
        val latch = CountDownLatch(1)
        val resultJson = AtomicReference<JSONObject?>(null)
        val errorMsg = AtomicReference<String?>(null)
        val opened = AtomicBoolean(false)
        val start = System.currentTimeMillis()
        var latency = -1L

        val client = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build()

        val url = wsUrl.trimEnd('/') + "/ws/audio?api_key=" + apiKey
        val request = Request.Builder()
            .url(url)
            .header("ngrok-skip-browser-warning", "true")
            .build()

        val ws = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                opened.set(true)
                // Send a small chunk of near-silence (very low amplitude)
                // Server will run it through the full pipeline: ASR → Translate → TTS
                val silenceChunk = ByteArray(16000) // 0.5s @ 16kHz 16-bit mono
                // (all zeros = pure silence — Whisper returns empty text)
                webSocket.send(ByteString.of(*silenceChunk))
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                try {
                    val json = JSONObject(text)
                    val type = json.optString("type", "")
                    if (type == "result") {
                        latency = System.currentTimeMillis() - start
                        resultJson.set(json)
                        // Extract fields for later checks
                        pipelineThaiField = json.optString("thai", null)
                        pipelineAudioBytes = json.optInt("audio_bytes", -1)
                        webSocket.close(1000, "Diagnostics pipeline test done")
                    }
                } catch (_: Exception) {
                    // Not JSON or unexpected format — wait for next message
                }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                latch.countDown()
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (!opened.get()) {
                    errorMsg.set("WebSocket failed: ${t.message}")
                }
                latch.countDown()
            }
        })

        val timedOut = !latch.await(20, TimeUnit.SECONDS)

        return when {
            timedOut -> {
                ws.close(1000, "timeout")
                CheckResult(3, name, emoji, CheckStatus.FAIL,
                    "Timeout — pipeline did not respond in 20s")
            }
            !opened.get() -> {
                CheckResult(3, name, emoji, CheckStatus.FAIL,
                    errorMsg.get() ?: "WebSocket did not open")
            }
            resultJson.get() != null -> {
                val json = resultJson.get()!!
                val text = json.optString("text", "")
                val asrTime = json.optJSONObject("timings")?.optDouble("asr", -1.0) ?: -1.0
                val totalTime = json.optJSONObject("timings")?.optDouble("total", -1.0) ?: -1.0

                val detail = buildString {
                    append("Pipeline OK · ${latency}ms total")
                    if (asrTime > 0) append(" · ASR=${(asrTime * 1000).toInt()}ms")
                    if (text.isNotEmpty()) append(" · text=\"${text.take(30)}\"")
                }
                CheckResult(3, name, emoji, CheckStatus.PASS, detail, latency)
            }
            else -> {
                CheckResult(3, name, emoji, CheckStatus.FAIL,
                    "No result JSON received — pipeline may be broken")
            }
        }
    }

    // ── Check 4: Translation Field ──────────────────────────────────────

    private fun checkTranslationField(
        pipelineResult: CheckResult, name: String, emoji: String
    ): CheckResult {
        val thaiField = pipelineThaiField
        return when {
            thaiField == null -> {
                CheckResult(4, name, emoji, CheckStatus.FAIL,
                    "No 'thai' field in pipeline response")
            }
            thaiField.isNotEmpty() -> {
                CheckResult(4, name, emoji, CheckStatus.PASS,
                    "Thai output: \"${thaiField.take(40)}\"")
            }
            else -> {
                // Empty is expected for silence input — translate is wired
                CheckResult(4, name, emoji, CheckStatus.PASS,
                    "Wired (empty for silence input)")
            }
        }
    }

    // ── Check 5: TTS Field ──────────────────────────────────────────────

    private fun checkTtsField(
        pipelineResult: CheckResult, name: String, emoji: String
    ): CheckResult {
        val audioBytes = pipelineAudioBytes
        return when {
            audioBytes < 0 -> {
                CheckResult(5, name, emoji, CheckStatus.FAIL,
                    "No 'audio_bytes' field in pipeline response")
            }
            audioBytes > 0 -> {
                CheckResult(5, name, emoji, CheckStatus.PASS,
                    "TTS generating audio ($audioBytes bytes)")
            }
            else -> {
                // 0 bytes is expected for silence input — TTS is wired
                CheckResult(5, name, emoji, CheckStatus.PASS,
                    "Wired (0B for silence input)")
            }
        }
    }

    // ── Logging ─────────────────────────────────────────────────────────

    companion object {
        const val TAG = "Diagnostics"
    }
}
