package com.thaivoice.translator.capture

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.AudioManager
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.util.Log
import kotlinx.coroutines.*
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Foreground Service that captures system audio playback (e.g. YouTube) using
 * [AudioPlaybackCapture] API and streams raw PCM chunks to the backend via WebSocket.
 *
 * # Architecture (v2 — system audio capture)
 *
 * ```
 * YouTube/App  →  Android Audio System
 *                     ↓
 *              AudioPlaybackCapture (USAGE_MEDIA)
 *                     ↓
 *              AudioRecord (16kHz, 16-bit, mono)
 *                     ↓
 *              [TranslatorWebSocketClient.sendAudioChunk()]
 *                     ↓
 *              Backend: ASR → Translate → TTS
 *                     ↓
 *              [onAudioReceived → AudioPlayer.playChunk()]
 *                     ↓
 *              AudioTrack → หูฟัง (พร้อม Audio Focus Ducking)
 * ```
 *
 * # AudioPlaybackCapture Requirements
 * - **Android 10 (API 29)+** only — this API does not exist on older devices.
 * - Requires **MediaProjection** consent from the user (system dialog similar to
 *   screen recording permission).
 * - Must add `addMatchingUsage(AudioAttributes.USAGE_MEDIA)` to capture only
 *   media apps (YouTube, Spotify, etc.) — excludes phone calls, alarms, etc.
 * - The app **must** be signed with the same certificate in production; debug
 *   builds work for development.
 *
 * # Lifecycle
 * 1. Activity calls [MediaProjectionManager.createScreenCaptureIntent()]
 * 2. User approves the system dialog → Activity receives resultCode + data Intent
 * 3. Activity calls [AudioCaptureService.startCapture] via binder with the token
 * 4. Service creates AudioRecord → begins capturing → sends chunks via callback
 * 5. User presses "Stop" in notification or Activity → [stopCapture] called
 *
 * # Threading
 * Capture runs on a background coroutine (Dispatchers.IO). Audio chunks are
 * delivered via [onAudioChunkReady] callback on the same IO dispatcher.
 *
 * @see android.media.projection.MediaProjection
 * @see android.media.AudioPlaybackCaptureConfiguration
 * @see android.media.AudioRecord
 */
class AudioCaptureService : Service() {

    // ── Audio Format Constants ─────────────────────────────────────────
    companion object {
        const val TAG = "AudioCaptureService"
        const val CHANNEL_ID = "audio_capture_channel"
        const val NOTIFICATION_ID = 1001

        /** Must match backend Whisper expectations: 16,000 Hz. */
        const val SAMPLE_RATE = 16_000

        /** Mono = 1 channel. */
        const val CHANNELS = AudioFormat.CHANNEL_IN_MONO

        /** 16-bit PCM signed integer. */
        const val ENCODING = AudioFormat.ENCODING_PCM_16BIT

        /** Target chunk duration ~1 second. */
        const val CHUNK_DURATION_MS = 1000L

        /** Bytes per sample: 2 (16-bit). */
        const val BYTES_PER_SAMPLE = 2

        /** Target chunk size: 16000 * 2 * 1.0 = 32000 bytes. */
        val TARGET_CHUNK_BYTES: Int =
            (SAMPLE_RATE * BYTES_PER_SAMPLE * CHUNK_DURATION_MS / 1000).toInt()

        // ── Intent Actions ──────────────────────────────────────────────
        const val ACTION_STOP = "com.thaivoice.translator.action.STOP_CAPTURE"
    }

    // ── Binder ─────────────────────────────────────────────────────────
    inner class LocalBinder : Binder() {
        fun getService(): AudioCaptureService = this@AudioCaptureService
    }

    private val binder = LocalBinder()

    // ── State ──────────────────────────────────────────────────────────
    private var mediaProjection: MediaProjection? = null
    private var audioRecord: AudioRecord? = null
    private val isCapturing = AtomicBoolean(false)
    private var captureJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private var mediaProjectionManager: MediaProjectionManager? = null

    // ── Callbacks ──────────────────────────────────────────────────────

    /**
     * Invoked when a full ~1s audio chunk is ready from system audio capture.
     * Wire this to [TranslatorWebSocketClient.sendAudioChunk].
     */
    var onAudioChunkReady: ((ByteArray) -> Unit)? = null

    /**
     * Invoked when an error occurs during capture (e.g. AudioRecord failure).
     */
    var onCaptureError: ((String) -> Unit)? = null

    // ── Lifecycle ──────────────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Service onCreate")
        createNotificationChannel()
        mediaProjectionManager =
            getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
    }

    override fun onBind(intent: Intent?): IBinder {
        Log.d(TAG, "Service onBind")
        return binder
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                Log.i(TAG, "Stop action received via intent")
                stopCapture()
                stopSelf()
            }
        }
        // Restart if killed — we want the service to persist
        return START_STICKY
    }

    override fun onDestroy() {
        Log.d(TAG, "Service onDestroy")
        stopCapture()
        scope.cancel()
        super.onDestroy()
    }

    // ── Notification ───────────────────────────────────────────────────

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "กำลังแปลเสียง",
                NotificationManager.IMPORTANCE_LOW  // Low = no sound, just status bar icon
            ).apply {
                description = "แสดงขณะกำลังดักเสียงเพื่อแปลภาษา"
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        // PendingIntent for "Stop" action
        val stopIntent = Intent(this, AudioCaptureService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(
            this, 0, stopIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("กำลังแปลเสียง")
                .setContentText("ดักเสียงจากแอปอื่นและแปลเป็นภาษาไทยอยู่...")
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setOngoing(true)         // Cannot be swiped away
                .setPriority(Notification.PRIORITY_LOW)
                .addAction(android.R.drawable.ic_media_pause, "หยุด", stopPendingIntent)
                .build()
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
                .setContentTitle("กำลังแปลเสียง")
                .setContentText("ดักเสียงจากแอปอื่นและแปลเป็นภาษาไทยอยู่...")
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setOngoing(true)
                .setPriority(Notification.PRIORITY_LOW)
                .build()
        }
    }

    // ── Public API ─────────────────────────────────────────────────────

    /**
     * Start capturing system audio using the MediaProjection token obtained
     * from the user consent dialog.
     *
     * Call this from the Activity after receiving the result from
     * [android.app.Activity.onActivityResult] with RESULT_OK.
     *
     * @param resultCode  The resultCode from MediaProjection consent dialog.
     * @param data        The data Intent from MediaProjection consent dialog.
     */
    fun startCapture(resultCode: Int, data: Intent) {
        if (isCapturing.getAndSet(true)) {
            Log.w(TAG, "startCapture called but already capturing — ignoring")
            return
        }

        Log.i(TAG, "Starting system audio capture...")

        // ── Start foreground notification ──────────────────────────────
        startForeground(NOTIFICATION_ID, buildNotification())

        // ── Obtain MediaProjection token ───────────────────────────────
        val mpManager = mediaProjectionManager
        if (mpManager == null) {
            Log.e(TAG, "MediaProjectionManager is null")
            onCaptureError?.invoke("MediaProjectionManager unavailable")
            stopCapture()
            return
        }

        mediaProjection = mpManager.getMediaProjection(resultCode, data)
        val mp = mediaProjection
        if (mp == null) {
            Log.e(TAG, "Failed to obtain MediaProjection token (resultCode=$resultCode)")
            onCaptureError?.invoke("MediaProjection token rejected")
            stopCapture()
            return
        }

        // Register a callback to stop capture if the user revokes the
        // projection (e.g. from quick settings tile)
        mp.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                Log.w(TAG, "MediaProjection revoked by user — stopping capture")
                stopCapture()
                stopSelf()
            }
        }, null)

        // ── Build AudioPlaybackCaptureConfiguration ────────────────────
        val captureConfig = AudioPlaybackCaptureConfiguration.Builder(mp)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)     // YouTube, music apps
            .addMatchingUsage(AudioAttributes.USAGE_GAME)      // Games (optional)
            .build()

        // ── Calculate buffer sizes ─────────────────────────────────────
        val minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNELS, ENCODING)
        if (minBuf <= 0) {
            Log.e(TAG, "Invalid min buffer size ($minBuf) — audio capture unavailable")
            onCaptureError?.invoke("AudioRecord buffer size error: $minBuf")
            stopCapture()
            return
        }
        val bufferSize = minBuf.coerceAtLeast(4096) * 2

        // ── Create AudioRecord ─────────────────────────────────────────
        try {
            audioRecord = AudioRecord.Builder()
                .setAudioPlaybackCaptureConfig(captureConfig)
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(SAMPLE_RATE)
                        .setChannelMask(CHANNELS)
                        .setEncoding(ENCODING)
                        .build()
                )
                .setBufferSizeInBytes(bufferSize)
                .build()
        } catch (e: Exception) {
            Log.e(TAG, "AudioRecord.Builder failed: ${e.message}", e)
            onCaptureError?.invoke("AudioRecord creation failed: ${e.message}")
            stopCapture()
            return
        }

        val record = audioRecord
        if (record == null || record.state != AudioRecord.STATE_INITIALIZED) {
            Log.e(TAG, "AudioRecord failed to initialise (state=${record?.state})")
            onCaptureError?.invoke("AudioRecord initialisation failed")
            stopCapture()
            return
        }

        Log.i(
            TAG,
            "AudioRecord initialised: $SAMPLE_RATE Hz mono, " +
                "buffer=${bufferSize}B, " +
                "chunk=${TARGET_CHUNK_BYTES}B (~${CHUNK_DURATION_MS}ms)"
        )

        // ── Start capture coroutine ────────────────────────────────────
        captureJob = scope.launch {
            try {
                record.startRecording()
                Log.d(TAG, "System audio capture started")

                val chunkBuffer = ByteArrayOutputStream(TARGET_CHUNK_BYTES)
                val readBuffer = ByteArray(bufferSize)

                while (isActive && isCapturing.get()) {

                    val bytesRead = record.read(readBuffer, 0, readBuffer.size)

                    when {
                        bytesRead == AudioRecord.ERROR_INVALID_OPERATION -> {
                            Log.e(TAG, "read() ERROR_INVALID_OPERATION — stopping")
                            break
                        }
                        bytesRead == AudioRecord.ERROR_BAD_VALUE -> {
                            Log.e(TAG, "read() ERROR_BAD_VALUE — stopping")
                            break
                        }
                        bytesRead == AudioRecord.ERROR_DEAD_OBJECT -> {
                            Log.e(TAG, "read() ERROR_DEAD_OBJECT — capture source gone?")
                            break
                        }
                        bytesRead > 0 -> {
                            chunkBuffer.write(readBuffer, 0, bytesRead)

                            if (chunkBuffer.size() >= TARGET_CHUNK_BYTES) {
                                val chunk = chunkBuffer.toByteArray()
                                chunkBuffer.reset()

                                try {
                                    onAudioChunkReady?.invoke(chunk)
                                } catch (e: Exception) {
                                    Log.w(TAG, "onAudioChunkReady callback threw: ${e.message}")
                                }
                            }
                        }
                        // bytesRead == 0 → no data, loop again
                    }
                }

                // Emit remaining partial chunk
                if (chunkBuffer.size() > 0) {
                    try {
                        onAudioChunkReady?.invoke(chunkBuffer.toByteArray())
                    } catch (e: Exception) {
                        Log.w(TAG, "Final chunk callback threw: ${e.message}")
                    }
                }

            } catch (e: CancellationException) {
                Log.d(TAG, "Capture coroutine cancelled")
            } catch (e: Exception) {
                Log.e(TAG, "Capture error: ${e.message}", e)
                onCaptureError?.invoke("Capture failed: ${e.message}")
            } finally {
                Log.i(TAG, "Capture loop ended")
                cleanupAudioRecord()
                isCapturing.set(false)
            }
        }
    }

    /**
     * Stop capturing system audio and release all resources.
     *
     * Safe to call from any thread. Idempotent — calling when not capturing
     * is a no-op.
     */
    fun stopCapture() {
        Log.d(TAG, "stopCapture() called")
        isCapturing.set(false)

        captureJob?.cancel()
        captureJob = null

        cleanupAudioRecord()

        // Release MediaProjection
        mediaProjection?.stop()
        mediaProjection = null

        // Remove foreground notification
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    /** Returns `true` if audio capture is currently active. */
    fun isActive(): Boolean = isCapturing.get()

    // ── Internal ───────────────────────────────────────────────────────

    private fun cleanupAudioRecord() {
        val record = audioRecord
        if (record != null) {
            try {
                if (record.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                    record.stop()
                }
                record.release()
            } catch (e: Exception) {
                Log.w(TAG, "Error releasing AudioRecord: ${e.message}")
            } finally {
                audioRecord = null
            }
        }
    }
}
