package com.thaivoice.translator

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import com.thaivoice.translator.capture.AudioCaptureService
import com.thaivoice.translator.network.TranslatorWebSocketClient
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
    private lateinit var statusText: TextView
    private lateinit var transcriptText: TextView

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
    private val wsClient = TranslatorWebSocketClient()
    private val audioPlayer = AudioPlayer(this)  // Context needed for AudioManager (ducking)

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
    }

    // ── UI Setup ───────────────────────────────────────────────────────

    private fun setupUI() {
        // Create a simple vertical layout programmatically.
        // In production, replace with res/layout/activity_main.xml
        val linearLayout = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(64, 128, 64, 64)
        }

        // Title
        val titleView = TextView(this).apply {
            text = "Thai Voice Translator"
            textSize = 24f
            setPadding(0, 0, 0, 32)
        }
        linearLayout.addView(titleView)

        // Status indicator
        statusText = TextView(this).apply {
            text = "Status: Ready"
            textSize = 16f
            setPadding(0, 0, 0, 32)
        }
        linearLayout.addView(statusText)

        // Transcript preview
        transcriptText = TextView(this).apply {
            text = ""
            textSize = 14f
            setPadding(0, 0, 0, 32)
            minLines = 3
        }
        linearLayout.addView(transcriptText)

        // Start button
        startButton = Button(this).apply {
            text = "Start Translation"
            isEnabled = true
            setOnClickListener { requestMediaProjection() }
        }
        linearLayout.addView(startButton)

        // Stop button
        stopButton = Button(this).apply {
            text = "Stop Translation"
            isEnabled = false
            setOnClickListener { stopCapture() }
        }
        linearLayout.addView(stopButton)

        setContentView(linearLayout)
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

        // 1. Connect WebSocket to backend
        //    URL: use 10.0.2.2 for emulator, or your server's real IP/domain
        val serverUrl = "ws://10.0.2.2:8000"  // TODO: make configurable
        val apiKey = "dev-key"                // TODO: use BuildConfig.BACKEND_API_KEY
        wsClient.connect(serverUrl, apiKey)

        // 2. WebSocket connection status → UI
        wsClient.onConnectionStateChanged = { connected ->
            runOnUiThread {
                statusText.text = if (connected) "Status: Connected" else "Status: Disconnected"
                Log.d(TAG, "WebSocket ${if (connected) "connected" else "disconnected"}")
            }
        }

        // 3. Received Thai audio → AudioPlayer (with ducking)
        wsClient.onAudioReceived = { pcmBytes ->
            audioPlayer.playChunk(pcmBytes)
        }

        // 4. Server errors → UI
        wsClient.onError = { msg ->
            runOnUiThread {
                Toast.makeText(this, "Error: $msg", Toast.LENGTH_SHORT).show()
            }
            Log.w(TAG, "Server error: $msg")
        }

        // 5. Captured audio chunks → WebSocket
        service.onAudioChunkReady = { chunk ->
            wsClient.sendAudioChunk(chunk)
        }

        // 6. Capture errors → UI
        service.onCaptureError = { error ->
            runOnUiThread {
                Toast.makeText(this, "Capture error: $error", Toast.LENGTH_LONG).show()
            }
            Log.e(TAG, "Capture error: $error")
        }

        Log.i(TAG, "Full pipeline wired: Capture → WS → Backend → AudioPlayer")
    }

    // ── UI State ───────────────────────────────────────────────────────

    private fun updateUI(isCapturing: Boolean) {
        runOnUiThread {
            startButton.isEnabled = !isCapturing
            stopButton.isEnabled = isCapturing
            statusText.text = if (isCapturing) "Status: Capturing..." else "Status: Ready"
        }
    }
}
