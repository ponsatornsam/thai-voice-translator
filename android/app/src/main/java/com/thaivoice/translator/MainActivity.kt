package com.thaivoice.translator

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.graphics.Color
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import com.thaivoice.translator.capture.AudioCaptureService
import com.thaivoice.translator.network.TranslatorWebSocketClient
import com.thaivoice.translator.network.Diagnostics
import com.thaivoice.translator.audio.AudioPlayer

/**
 * Main entry point for the Thai Voice Translator v2.
 *
 * # User Flow
 *
 * 1. App opens → screen shows "Start Translation" button
 * 2. User taps button → system dialog: "Allow Thai Translator to capture audio?"
 *    (This is the MediaProjection consent — identical to screen recording permission)
 * 3. User taps "Start now" → AudioCaptureService begins capturing system audio,
 *    foreground notification appears, translated Thai audio plays through headphones
 * 4. User taps "Stop" (in app or notification) → capture stops, service stops
 *
 * # Architecture
 *
 * ```
 * MainActivity
 *   │
 *   ├── MediaProjection consent → AudioCaptureService.startCapture()
 *   │
 *   └── Binds to AudioCaptureService (for status updates)
 *         │
 *         └── AudioCaptureService internally manages:
 *               ├── AudioPlaybackCapture (system audio)
 *               ├── TranslatorWebSocketClient (backend connection)
 *               └── AudioPlayer (playback + audio focus ducking)
 * ```
 *
 * # Permissions Required (AndroidManifest.xml)
 * - FOREGROUND_SERVICE
 * - FOREGROUND_SERVICE_MEDIA_PROJECTION
 * - POST_NOTIFICATIONS (Android 13+)
 * - INTERNET
 *
 * @see AudioCaptureService
 * @see TranslatorWebSocketClient
 * @see AudioPlayer
 */
class MainActivity : ComponentActivity() {

    companion object {
        const val TAG = "MainActivity"
    }

    // ── UI Elements ────────────────────────────────────────────────────
    private lateinit var startButton: Button
    private lateinit var stopButton: Button
    private lateinit var diagButton: Button
    private lateinit var statusDot: TextView         // Colored dot (●)
    private lateinit var statusText: TextView         // Status description
    private lateinit var latencyText: TextView        // "latency 45ms" or ""
    private lateinit var transcriptText: TextView
    private lateinit var connectionCard: LinearLayout // Grouped status card
    private lateinit var diagResultsContainer: LinearLayout // Diagnostics results
    private var diagRunning = false                   // Prevent double-tap

    // ── Service Binding ────────────────────────────────────────────────
    private var captureService: AudioCaptureService? = null
    private var isServiceBound = false

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as? AudioCaptureService.LocalBinder
            if (binder != null) {
                captureService = binder.getService()
                isServiceBound = true
                Log.d(TAG, "Bound to AudioCaptureService")

                // Wire service callbacks
                wireServiceCallbacks()
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            captureService = null
            isServiceBound = false
            Log.d(TAG, "AudioCaptureService disconnected")
        }
    }

    // ── WebSocket & Audio (managed by service internally, refs for wiring) ──
    // Must use `by lazy` — AudioPlayer calls getSystemService() which requires
    // the Activity to have passed through onCreate() first.
    private val wsClient by lazy { TranslatorWebSocketClient() }
    private val audioPlayer by lazy { AudioPlayer(this) }

    // ── MediaProjection Launcher ───────────────────────────────────────
    private lateinit var mediaProjectionLauncher: ActivityResultLauncher<Intent>

    // ── Lifecycle ──────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Simple programmatic UI (replace with layout XML in production)
        setupUI()

        // Register MediaProjection result handler
        mediaProjectionLauncher = registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { result ->
            handleMediaProjectionResult(result.resultCode, result.data)
        }

        // Request notification permission on Android 13+
        requestNotificationPermissionIfNeeded()
    }

    override fun onStart() {
        super.onStart()
        // Bind to the capture service
        bindService()
    }

    override fun onStop() {
        super.onStop()
        // Unbind from service (but keep it running in foreground)
        if (isServiceBound) {
            unbindService(serviceConnection)
            isServiceBound = false
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        audioPlayer.release()
        wsClient.disconnect()
        wsClient.shutdown()
    }

    // ── UI Setup ───────────────────────────────────────────────────────

    private fun setupUI() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 96, 48, 48)
        }

        // Title
        root.addView(TextView(this).apply {
            text = "Thai Voice Translator"
            textSize = 22f
            setPadding(0, 0, 0, 24)
        })

        // ── Connection Status Card ──────────────────────────────────────
        connectionCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#F5F5F5"))
            setPadding(20, 16, 20, 16)
            // Rounded corners on API 31+ is set programmatically below
        }
        // Round the card corners
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            connectionCard.setBackgroundColor(Color.parseColor("#F5F5F5"))
            // Simple background — clip not needed for solid color card
        }

        // Row: dot + status text
        val statusRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        statusDot = TextView(this).apply {
            text = "●"
            textSize = 18f
            setTextColor(Color.GRAY)
            setPadding(0, 0, 12, 0)
        }

        statusText = TextView(this).apply {
            text = "Not connected"
            textSize = 15f
            setTextColor(Color.DKGRAY)
        }

        statusRow.addView(statusDot)
        statusRow.addView(statusText)
        connectionCard.addView(statusRow)

        // Latency detail
        latencyText = TextView(this).apply {
            text = ""
            textSize = 12f
            setTextColor(Color.GRAY)
            setPadding(30, 4, 0, 0)  // Indented under the dot+text
        }
        connectionCard.addView(latencyText)

        root.addView(connectionCard)
        root.addView(TextView(this).apply { setPadding(0, 0, 0, 12) }) // spacer

        // ── Diagnostics Button ──────────────────────────────────────────
        diagButton = Button(this).apply {
            text = "Run Diagnostics"
            isEnabled = true
            setOnClickListener { runDiagnostics() }
        }
        root.addView(diagButton)

        // ── Diagnostics Results ─────────────────────────────────────────
        diagResultsContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = android.view.View.GONE
            setPadding(8, 8, 8, 8)
        }
        root.addView(diagResultsContainer)
        root.addView(TextView(this).apply { setPadding(0, 0, 0, 16) }) // spacer

        // ── Transcript preview ──────────────────────────────────────────
        transcriptText = TextView(this).apply {
            text = ""
            textSize = 14f
            setPadding(0, 0, 0, 24)
            minLines = 3
            setTextColor(Color.DKGRAY)
        }
        root.addView(transcriptText)

        // ── Buttons ─────────────────────────────────────────────────────
        startButton = Button(this).apply {
            text = "Start Translation"
            isEnabled = false  // Disabled until API is ready
            setOnClickListener { requestMediaProjection() }
        }
        root.addView(startButton)

        stopButton = Button(this).apply {
            text = "Stop Translation"
            isEnabled = false
            setOnClickListener { stopCapture() }
        }
        root.addView(stopButton)

        setContentView(root)
    }

    // ── Notification Permission (Android 13+) ──────────────────────────

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val launcher = registerForActivityResult(
                ActivityResultContracts.RequestPermission()
            ) { granted ->
                if (granted) {
                    Log.d(TAG, "Notification permission granted")
                } else {
                    Toast.makeText(
                        this,
                        "Notification permission recommended — shows capture status",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
            launcher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    // ── MediaProjection Flow ───────────────────────────────────────────

    /**
     * Step 1: Ask user for MediaProjection consent.
     *
     * Displays a system dialog identical to "Start recording or casting?"
     * The user must tap "Start now" for us to capture system audio.
     */
    private fun requestMediaProjection() {
        val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE)
            as MediaProjectionManager

        val intent = manager.createScreenCaptureIntent()
        mediaProjectionLauncher.launch(intent)
    }

    /**
     * Step 2: Handle the user's response to the MediaProjection dialog.
     */
    private fun handleMediaProjectionResult(resultCode: Int, data: Intent?) {
        if (resultCode != Activity.RESULT_OK) {
            Toast.makeText(this, "MediaProjection permission denied", Toast.LENGTH_SHORT).show()
            Log.w(TAG, "MediaProjection denied (resultCode=$resultCode)")
            return
        }

        if (data == null) {
            Toast.makeText(this, "MediaProjection data is null", Toast.LENGTH_SHORT).show()
            Log.e(TAG, "MediaProjection data Intent is null")
            return
        }

        Log.i(TAG, "MediaProjection permission granted")

        // Start the capture service (creates foreground notification)
        startCaptureService()

        // Pass the MediaProjection token to the service
        // (small delay to ensure service is bound)
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            captureService?.startCapture(resultCode, data)
            updateUI(isCapturing = true)
        }, 300)
    }

    // ── Service Management ─────────────────────────────────────────────

    private fun bindService() {
        val intent = Intent(this, AudioCaptureService::class.java)
        bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
    }

    private fun startCaptureService() {
        val intent = Intent(this, AudioCaptureService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    private fun stopCapture() {
        captureService?.stopCapture()
        stopService(Intent(this, AudioCaptureService::class.java))
        updateUI(isCapturing = false)
    }

    // ── Wiring: Service Callbacks → WebSocket → AudioPlayer ────────────

    /**
     * Wire the full pipeline:
     * AudioCapture → WebSocket.sendAudioChunk → Backend →
     * WebSocket.onAudioReceived → AudioPlayer.playChunk
     */
    private fun wireServiceCallbacks() {
        val service = captureService ?: return

        // ── 1. Health Check First ────────────────────────────────────────
        val serverUrl = BuildConfig.BACKEND_SERVER_URL
        val apiKey = BuildConfig.BACKEND_API_KEY

        updateConnectionUI(TranslatorWebSocketClient.ConnectionStatus.CHECKING_API, "Checking API...", "")

        wsClient.checkServerHealth(serverUrl) { result ->
            runOnUiThread {
                if (result.reachable) {
                    val latencyInfo = "latency ${result.latencyMs}ms"
                    updateConnectionUI(
                        TranslatorWebSocketClient.ConnectionStatus.API_READY,
                        "API ready",
                        latencyInfo
                    )
                    Log.i(TAG, "Health check OK — ${result.latencyMs}ms, connecting WebSocket...")

                    // Now connect WebSocket
                    wsClient.connect(serverUrl, apiKey)
                } else {
                    val errMsg = result.error ?: "HTTP ${result.httpCode}"
                    updateConnectionUI(
                        TranslatorWebSocketClient.ConnectionStatus.API_UNREACHABLE,
                        "API unreachable",
                        errMsg
                    )
                    Log.e(TAG, "Health check FAILED: $errMsg")
                    Toast.makeText(
                        this@MainActivity,
                        "Cannot reach server: $errMsg",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }

        // ── 2. Rich connection status → UI ──────────────────────────────
        wsClient.onConnectionStatusChanged = { status, detail ->
            runOnUiThread {
                when (status) {
                    TranslatorWebSocketClient.ConnectionStatus.CONNECTED -> {
                        updateConnectionUI(status, detail, "") // latency already shown
                        startButton.isEnabled = true
                    }
                    TranslatorWebSocketClient.ConnectionStatus.AUTH_FAILED -> {
                        updateConnectionUI(status, detail, "")
                        Toast.makeText(this, detail, Toast.LENGTH_LONG).show()
                    }
                    TranslatorWebSocketClient.ConnectionStatus.RECONNECTING -> {
                        updateConnectionUI(status, detail, "")
                    }
                    TranslatorWebSocketClient.ConnectionStatus.DISCONNECTED -> {
                        updateConnectionUI(status, detail, "")
                        startButton.isEnabled = false
                    }
                    else -> {
                        updateConnectionUI(status, detail, "")
                    }
                }
            }
        }

        // Also keep simple boolean callback for backward compat
        wsClient.onConnectionStateChanged = { connected ->
            Log.d(TAG, "WebSocket ${if (connected) "connected" else "disconnected"}")
        }

        // ── 3. Received Thai audio → AudioPlayer (with ducking) ────────
        wsClient.onAudioReceived = { pcmBytes ->
            audioPlayer.playChunk(pcmBytes)
        }

        // ── 4. Server errors → UI ───────────────────────────────────────
        wsClient.onError = { msg ->
            runOnUiThread {
                Toast.makeText(this, "Error: $msg", Toast.LENGTH_SHORT).show()
            }
            Log.w(TAG, "Server error: $msg")
        }

        // ── 5. Captured audio chunks → WebSocket ─────────────────────────
        service.onAudioChunkReady = { chunk ->
            wsClient.sendAudioChunk(chunk)
        }

        // ── 6. Capture errors → UI ──────────────────────────────────────
        service.onCaptureError = { error ->
            runOnUiThread {
                Toast.makeText(this, "Capture error: $error", Toast.LENGTH_LONG).show()
            }
            Log.e(TAG, "Capture error: $error")
        }

        Log.i(TAG, "Full pipeline wired: HealthCheck → WS → Backend → AudioPlayer")
    }

    // ── UI State ───────────────────────────────────────────────────────

    /** Update the connection-status card (dot color + text + latency detail). */
    private fun updateConnectionUI(
        status: TranslatorWebSocketClient.ConnectionStatus,
        message: String,
        detail: String
    ) {
        val (color, dotColor) = when (status) {
            TranslatorWebSocketClient.ConnectionStatus.IDLE -> "Not connected" to Color.GRAY
            TranslatorWebSocketClient.ConnectionStatus.CHECKING_API -> "Checking..." to Color.parseColor("#FFA500") // orange
            TranslatorWebSocketClient.ConnectionStatus.API_READY -> "API ready" to Color.parseColor("#4CAF50") // green
            TranslatorWebSocketClient.ConnectionStatus.API_UNREACHABLE -> "Unreachable" to Color.RED
            TranslatorWebSocketClient.ConnectionStatus.CONNECTING -> "Connecting..." to Color.parseColor("#FFA500")
            TranslatorWebSocketClient.ConnectionStatus.CONNECTED -> "Connected" to Color.parseColor("#4CAF50")
            TranslatorWebSocketClient.ConnectionStatus.DISCONNECTED -> "Disconnected" to Color.RED
            TranslatorWebSocketClient.ConnectionStatus.RECONNECTING -> "Reconnecting..." to Color.parseColor("#FF9800")
            TranslatorWebSocketClient.ConnectionStatus.AUTH_FAILED -> "Auth Failed" to Color.RED
        }

        statusDot.setTextColor(dotColor)
        statusText.text = message
        if (detail.isNotEmpty()) {
            latencyText.text = detail
            latencyText.visibility = TextView.VISIBLE
        } else {
            latencyText.visibility = TextView.GONE
        }
    }

    private fun updateUI(isCapturing: Boolean) {
        runOnUiThread {
            startButton.isEnabled = !isCapturing
            stopButton.isEnabled = isCapturing
            if (isCapturing) {
                statusText.text = "Capturing audio..."
                statusDot.setTextColor(Color.parseColor("#4CAF50"))
            }
        }
    }

    // ── Diagnostics ─────────────────────────────────────────────────────

    /**
     * Run the full pipeline diagnostic and display results step by step.
     *
     * Checks: Health → Auth → WebSocket → ASR → Translation → TTS
     * Each result appears in real-time as it completes.
     */
    private fun runDiagnostics() {
        if (diagRunning) {
            Log.w(TAG, "Diagnostics already running — ignoring")
            return
        }
        diagRunning = true
        diagButton.isEnabled = false
        diagButton.text = "Running Diagnostics..."
        diagResultsContainer.removeAllViews()
        diagResultsContainer.visibility = android.view.View.VISIBLE

        val diag = Diagnostics()

        diag.onProgress = { index, total, result ->
            runOnUiThread { addDiagResult(result) }
        }

        val serverUrl = BuildConfig.BACKEND_SERVER_URL
        val apiKey = BuildConfig.BACKEND_API_KEY

        // Update connection card to show we're checking
        updateConnectionUI(
            TranslatorWebSocketClient.ConnectionStatus.CHECKING_API,
            "Running diagnostics...",
            ""
        )

        diag.runAll(serverUrl, apiKey) { allPassed, results ->
            runOnUiThread {
                diagRunning = false
                diagButton.text = "Run Diagnostics"
                diagButton.isEnabled = true

                val passed = results.count { it.status == Diagnostics.CheckStatus.PASS }
                val failed = results.count { it.status == Diagnostics.CheckStatus.FAIL }
                val skipped = results.count { it.status == Diagnostics.CheckStatus.SKIPPED }

                // Summary toast
                val summary = buildString {
                    append("✅ $passed passed")
                    if (failed > 0) append(" · ❌ $failed failed")
                    if (skipped > 0) append(" · ⏭️ $skipped skipped")
                }
                Toast.makeText(this, summary, Toast.LENGTH_LONG).show()

                // Update connection status based on results
                if (allPassed) {
                    updateConnectionUI(
                        TranslatorWebSocketClient.ConnectionStatus.API_READY,
                        "All checks passed",
                        ""
                    )
                    // Auto-connect if everything is good
                    wsClient.connect(serverUrl, apiKey)
                } else {
                    updateConnectionUI(
                        TranslatorWebSocketClient.ConnectionStatus.API_UNREACHABLE,
                        "$failed check(s) failed",
                        "Tap 'Run Diagnostics' to retry"
                    )
                }
            }
        }
    }

    /** Add a single diagnostic check result row to the UI. */
    private fun addDiagResult(result: Diagnostics.CheckResult) {
        val rowColor = when (result.status) {
            Diagnostics.CheckStatus.PASS -> Color.parseColor("#2E7D32")     // dark green
            Diagnostics.CheckStatus.FAIL -> Color.parseColor("#C62828")     // dark red
            Diagnostics.CheckStatus.SKIPPED -> Color.parseColor("#9E9E9E")  // grey
            Diagnostics.CheckStatus.RUNNING -> Color.parseColor("#FF8F00")  // amber
            Diagnostics.CheckStatus.PENDING -> Color.GRAY
        }

        val statusIcon = when (result.status) {
            Diagnostics.CheckStatus.PASS -> "✅"
            Diagnostics.CheckStatus.FAIL -> "❌"
            Diagnostics.CheckStatus.SKIPPED -> "⏭️"
            Diagnostics.CheckStatus.RUNNING -> "⏳"
            Diagnostics.CheckStatus.PENDING -> "⬜"
        }

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(4, 6, 4, 6)
        }

        // Icon + name
        val label = TextView(this).apply {
            text = "${result.emoji} ${result.name}"
            textSize = 13f
            setTextColor(rowColor)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        row.addView(label)

        // Status + detail
        val detail = TextView(this).apply {
            text = "$statusIcon ${result.detail}"
            textSize = 12f
            setTextColor(rowColor)
            gravity = Gravity.END
        }
        row.addView(detail)

        diagResultsContainer.addView(row)
    }
}
