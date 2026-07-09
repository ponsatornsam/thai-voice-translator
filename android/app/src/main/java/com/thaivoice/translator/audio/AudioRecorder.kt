package com.thaivoice.translator.audio

import android.Manifest
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Records audio from the microphone as raw PCM chunks for real-time streaming.
 *
 * # Audio Format
 * - Sample rate:   **16,000 Hz** (optimal for Faster-Whisper ASR)
 * - Channels:      **Mono** (1 channel)
 * - Encoding:      **PCM 16-bit signed integer**, little-endian
 * - Chunk size:    **~1 second** (≈32,000 bytes per chunk)
 *
 * # Threading
 * Recording runs on [Dispatchers.IO] via a coroutine — the calling Activity's
 * UI thread is never blocked. Chunks are delivered through a callback on the
 * IO dispatcher; the caller should switch to Main/Default if updating UI.
 *
 * # Lifecycle
 * Call [startRecording] to begin. Call [stopRecording] from any thread — it
 * cancels the coroutine and releases [AudioRecord] resources. The recorder
 * can be started again after stopping (a new coroutine is launched each time).
 *
 * # Permissions
 * Requires `android.permission.RECORD_AUDIO` (dangerous permission).
 * The caller MUST request this at runtime before calling [startRecording].
 * See [requestAudioPermission] helper and AndroidManifest.xml snippet below.
 *
 * # Usage (in an Activity or ViewModel)
 * ```
 * val recorder = AudioRecorder()
 *
 * // Request permission first (see requestAudioPermission helper)
 * if (hasRecordPermission) {
 *     recorder.startRecording { chunk: ByteArray ->
 *         // Send chunk to WebSocket / backend
 *         webSocketClient.sendAudioChunk(chunk)
 *     }
 * }
 *
 * // Later, when done...
 * recorder.stopRecording()
 * ```
 *
 * @see android.media.AudioRecord
 * @see java.util.concurrent.atomic.AtomicBoolean
 */
class AudioRecorder {

    // ── Audio Format Constants ─────────────────────────────────────────
    companion object {
        const val TAG = "AudioRecorder"

        /** Sample rate in Hz — must match backend Whisper expectations. */
        const val SAMPLE_RATE = 16_000

        /** Mono = 1 channel. */
        const val CHANNELS = AudioFormat.CHANNEL_IN_MONO

        /** 16-bit PCM signed integer. */
        const val ENCODING = AudioFormat.ENCODING_PCM_16BIT

        /**
         * Target chunk duration in milliseconds.
         * 1000 ms = 1 second → ~32,000 bytes at 16kHz 16-bit mono.
         */
        const val CHUNK_DURATION_MS = 1000L

        /** Bytes per sample: 2 (16-bit). */
        const val BYTES_PER_SAMPLE = 2

        /**
         * Target chunk size in bytes.
         * Formula: sampleRate * bytesPerSample * (chunkDurationMs / 1000)
         * 16000 * 2 * 1.0 = 32000 bytes
         */
        val TARGET_CHUNK_BYTES: Int = (SAMPLE_RATE * BYTES_PER_SAMPLE * CHUNK_DURATION_MS / 1000).toInt()
    }

    // ── State ──────────────────────────────────────────────────────────
    private var audioRecord: AudioRecord? = null
    private val isRecording = AtomicBoolean(false)
    private var recordingJob: Job? = null

    // ── Buffer sizes ───────────────────────────────────────────────────
    /** Minimum buffer size required by the hardware (typically ~640–4096 bytes). */
    private val minBufferSize: Int by lazy {
        AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNELS, ENCODING).also {
            if (it == AudioRecord.ERROR_BAD_VALUE || it == AudioRecord.ERROR) {
                Log.e(TAG, "AudioRecord.getMinBufferSize returned error: $it")
            } else {
                Log.d(TAG, "Min hardware buffer: $it bytes (${it * 1000 / (SAMPLE_RATE * BYTES_PER_SAMPLE)}ms)")
            }
        }
    }

    /** Buffer used for individual read() calls — 2× min size for safety. */
    private val readBufferSize: Int
        get() = minBufferSize.coerceAtLeast(4096) * 2

    // ── Public API ─────────────────────────────────────────────────────

    /**
     * Start recording audio from the microphone.
     *
     * Launches a coroutine on [Dispatchers.IO] that continuously reads PCM audio,
     * accumulates it into ~1-second chunks, and delivers each chunk via [onChunkReady].
     *
     * **Must be called after RECORD_AUDIO permission is granted.** If the microphone
     * is unavailable or initialization fails, an error is logged and no chunks are emitted.
     *
     * This method is a no-op if recording is already in progress.
     *
     * @param onChunkReady Lambda invoked on the IO dispatcher with each ~1s audio chunk
     *                     as a [ByteArray] of raw PCM 16-bit mono data.
     *                     **Do NOT** block this callback — it runs on the recording thread.
     */
    fun startRecording(onChunkReady: (ByteArray) -> Unit) {
        // Guard: already recording
        if (!isRecording.compareAndSet(false, true)) {
            Log.w(TAG, "startRecording called but already recording — ignoring")
            return
        }

        // Validate buffer size
        if (minBufferSize <= 0) {
            Log.e(TAG, "Invalid minBufferSize ($minBufferSize) — audio hardware unavailable?")
            isRecording.set(false)
            return
        }

        // ── Initialize AudioRecord ─────────────────────────────────────
        try {
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.MIC,   // Default microphone
                SAMPLE_RATE,
                CHANNELS,
                ENCODING,
                readBufferSize
            )
        } catch (e: IllegalArgumentException) {
            Log.e(TAG, "AudioRecord constructor failed: ${e.message}", e)
            isRecording.set(false)
            return
        }

        // Check initialisation state
        val record = audioRecord
        if (record == null || record.state != AudioRecord.STATE_INITIALIZED) {
            Log.e(TAG, "AudioRecord failed to initialise (state=${record?.state})")
            cleanupAudioRecord()
            isRecording.set(false)
            return
        }

        Log.i(
            TAG,
            "AudioRecord initialised: $SAMPLE_RATE Hz, " +
                "buffer=${readBufferSize}B, chunk=${TARGET_CHUNK_BYTES}B (~${CHUNK_DURATION_MS}ms)"
        )

        // ── Start recording coroutine ──────────────────────────────────
        recordingJob = CoroutineScope(Dispatchers.IO).launch {

            try {
                record.startRecording()
                Log.d(TAG, "Recording started")

                // Accumulator for building ~1s chunks
                val chunkBuffer = ByteArrayOutputStream(TARGET_CHUNK_BYTES)
                val readBuffer = ByteArray(readBufferSize)

                // ── Read loop ───────────────────────────────────────────
                while (isActive && isRecording.get()) {

                    // Read raw PCM from microphone (blocks until buffer full or error)
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
                            Log.e(TAG, "read() ERROR_DEAD_OBJECT — microphone disconnected?")
                            break
                        }
                        bytesRead > 0 -> {
                            // Accumulate into chunk
                            chunkBuffer.write(readBuffer, 0, bytesRead)

                            // Emit chunk when we have ~1 second of audio
                            if (chunkBuffer.size() >= TARGET_CHUNK_BYTES) {
                                val chunk = chunkBuffer.toByteArray()
                                chunkBuffer.reset()  // Clear for next chunk

                                // Deliver to callback (on IO dispatcher — don't block)
                                try {
                                    onChunkReady(chunk)
                                } catch (e: Exception) {
                                    Log.w(TAG, "onChunkReady callback threw: ${e.message}")
                                }
                            }
                        }
                        // bytesRead == 0 → no data available, loop again
                    }
                }

                // ── Emit remaining partial chunk (if any) ────────────────
                if (chunkBuffer.size() > 0) {
                    val finalChunk = chunkBuffer.toByteArray()
                    Log.d(TAG, "Emitting final partial chunk: ${finalChunk.size} bytes")
                    try {
                        onChunkReady(finalChunk)
                    } catch (e: Exception) {
                        Log.w(TAG, "onChunkReady callback threw on final chunk: ${e.message}")
                    }
                }

            } catch (e: CancellationException) {
                Log.d(TAG, "Recording coroutine cancelled")
                // Normal — don't rethrow
            } catch (e: Exception) {
                Log.e(TAG, "Recording error: ${e.message}", e)
            } finally {
                Log.i(TAG, "Recording loop ended")
                cleanupAudioRecord()
                isRecording.set(false)
            }
        }
    }

    /**
     * Stop recording and release all audio resources.
     *
     * Safe to call from any thread. This cancels the recording coroutine,
     * stops the [AudioRecord], and frees the microphone hardware.
     *
     * Calling [startRecording] afterwards will start a fresh session.
     *
     * Idempotent — calling when not recording is a no-op.
     */
    fun stopRecording() {
        Log.d(TAG, "stopRecording() called")
        isRecording.set(false)

        // Cancel coroutine (non-blocking — the finally block handles cleanup)
        recordingJob?.cancel()
        recordingJob = null

        // Also stop AudioRecord directly (defence in depth)
        // The coroutine finally block does this too, but if the coroutine
        // is stuck, this ensures the mic is released immediately.
        cleanupAudioRecord()
    }

    /**
     * Returns `true` if recording is currently active.
     */
    fun isActive(): Boolean = isRecording.get()

    // ── Internal ───────────────────────────────────────────────────────

    /** Release AudioRecord resources. Safe to call multiple times. */
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

// ============================================================================
// Permission Helper
// ============================================================================

/**
 * Helper to request [Manifest.permission.RECORD_AUDIO] at runtime using the
 * modern Activity Result API ([androidx.activity.result.ActivityResultContracts.RequestPermission]).
 *
 * # Usage (in Activity or Fragment)
 *
 * ```kotlin
 * // 1. Register the launcher
 * private val audioPermissionLauncher = registerForActivityResult(
 *     ActivityResultContracts.RequestPermission()
 * ) { granted ->
 *     if (granted) {
 *         recorder.startRecording { chunk -> /* send chunk */ }
 *     } else {
 *         Toast.makeText(this, "Microphone permission required", Toast.LENGTH_LONG).show()
 *     }
 * }
 *
 * // 2. Check and request
 * if (hasRecordPermission(this)) {
 *     recorder.startRecording { chunk -> /* send chunk */ }
 * } else {
 *     audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
 * }
 * ```
 *
 * @param context Android context (Activity, Fragment, etc.)
 * @return `true` if RECORD_AUDIO permission is already granted.
 */
fun hasRecordPermission(context: android.content.Context): Boolean {
    return ContextCompat.checkSelfPermission(
        context,
        Manifest.permission.RECORD_AUDIO
    ) == PackageManager.PERMISSION_GRANTED
}

// ============================================================================
// AndroidManifest.xml Required Declaration
// ============================================================================
//
// Add the following inside <manifest> (before <application>):
//
// ```xml
// <!-- Required for microphone access — AudioRecorder -->
// <uses-permission android:name="android.permission.RECORD_AUDIO" />
// ```
//
// RECORD_AUDIO is a *dangerous* permission → must be requested at runtime
// on Android 6.0 (API 23) and above. Use the [hasRecordPermission] helper
// above with ActivityResultContracts.RequestPermission to request it.
