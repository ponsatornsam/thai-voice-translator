package com.thaivoice.translator.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Build
import android.util.Log
import kotlinx.coroutines.*
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Plays raw PCM audio through the device speaker/headphones in real time,
 * with **Audio Focus Ducking** — automatically reduces the volume of other
 * apps (e.g. YouTube) while translated speech is playing.
 *
 * Designed to receive chunks from [TranslatorWebSocketClient.onAudioReceived][com.thaivoice.translator.network.TranslatorWebSocketClient]
 * and play them back with minimal latency — each chunk is written to the
 * [AudioTrack] as soon as the previous one finishes, creating a continuous
 * audio stream.
 *
 * # Audio Focus Ducking (v2 — Module 11)
 *
 * Before writing each chunk to [AudioTrack], we request transient audio focus
 * with [AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK]. The Android audio
 * system then:
 * 1. Lowers the volume of any other app currently playing audio (e.g. YouTube)
 * 2. Plays our translated speech at full volume
 * 3. When we abandon focus (after the chunk finishes), YouTube returns to
 *    its original volume automatically
 *
 * This is handled per-chunk (not per-session) so YouTube audio only ducks
 * while translated speech is actively playing — natural conversation flow.
 *
 * # Audio Format (must match backend Piper TTS output)
 * - Sample rate:   **16,000 Hz**
 * - Channels:      **Mono** (1 channel)
 * - Encoding:      **PCM 16-bit signed integer**, little-endian
 * - Mode:          **STREAM** (continuous playback, not static buffer)
 *
 * # Threading
 * [playChunk] is non-blocking — it enqueues the chunk to an internal
 * [ConcurrentLinkedQueue] and returns immediately (safe to call from
 * OkHttp callbacks or the main thread). A background coroutine on
 * [Dispatchers.IO] pulls chunks from the queue and writes them to
 * [AudioTrack] sequentially, preserving order.
 *
 * # Latency Buffer
 * The queue is capped to prevent unbounded growth (stale audio is useless
 * in real-time translation). If the queue exceeds [maxQueueChunks] items,
 * the **oldest** chunks are dropped to keep latency under control.
 *
 * # Usage
 * ```kotlin
 * val player = AudioPlayer(context)
 *
 * // Wire to WebSocket client
 * wsClient.onAudioReceived = { pcmBytes -> player.playChunk(pcmBytes) }
 *
 * // When done...
 * player.release()
 * ```
 *
 * @see android.media.AudioTrack
 * @see android.media.AudioManager
 * @see com.thaivoice.translator.network.TranslatorWebSocketClient
 */
class AudioPlayer(context: Context) {

    // ── Audio Format Constants ─────────────────────────────────────────
    companion object {
        const val TAG = "AudioPlayer"

        /** Must match Piper TTS output — 16,000 Hz mono. */
        const val SAMPLE_RATE = 16_000

        /** Mono = 1 channel. */
        const val CHANNEL_CONFIG = AudioFormat.CHANNEL_OUT_MONO

        /** 16-bit PCM signed integer. */
        const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT

        /** Bytes per sample: 2 (16-bit). */
        const val BYTES_PER_SAMPLE = 2
    }

    // ── Audio Focus (v2 Module 11: Ducking) ──────────────────────────
    private val audioManager: AudioManager =
        context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private var audioFocusRequest: AudioFocusRequest? = null
    private val hasAudioFocus = AtomicBoolean(false)

    /** Focus listener — logs changes; ducking is handled automatically by OS. */
    private val focusChangeListener = AudioManager.OnAudioFocusChangeListener { focusChange ->
        when (focusChange) {
            AudioManager.AUDIOFOCUS_GAIN -> {
                Log.d(TAG, "AudioFocus: GAIN — restored")
                hasAudioFocus.set(true)
            }
            AudioManager.AUDIOFOCUS_LOSS -> {
                Log.w(TAG, "AudioFocus: LOSS — stopping")
                hasAudioFocus.set(false)
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                Log.d(TAG, "AudioFocus: LOSS_TRANSIENT")
                hasAudioFocus.set(false)
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                Log.v(TAG, "AudioFocus: LOSS_TRANSIENT_CAN_DUCK")
                hasAudioFocus.set(false)
            }
        }
    }

    // ── AudioTrack ─────────────────────────────────────────────────────

    @Volatile private var audioTrack: AudioTrack? = null

    /** Minimum buffer size from hardware — typically ~4KB. */
    private val minBufferSize: Int by lazy {
        AudioTrack.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT).also {
            if (it <= 0) {
                Log.e(TAG, "AudioTrack.getMinBufferSize returned error: $it")
            } else {
                Log.d(TAG, "Min hardware buffer: $it bytes (${it * 1000 / (SAMPLE_RATE * BYTES_PER_SAMPLE)}ms)")
            }
        }
    }

    /** Actual AudioTrack buffer — 2× min for smooth streaming. */
    private val trackBufferSize: Int
        get() = minBufferSize.coerceAtLeast(4096) * 2

    // ── Queue & State ──────────────────────────────────────────────────

    /** Thread-safe queue holding PCM chunks waiting to be played. */
    private val chunkQueue = ConcurrentLinkedQueue<ByteArray>()

    /** Coroutine that drains [chunkQueue] into [AudioTrack]. */
    private var playerJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val isRunning = AtomicBoolean(false)
    private val isReleased = AtomicBoolean(false)

    /** Total bytes written to AudioTrack (for debugging/analytics). */
    private val totalBytesWritten = AtomicLong(0)

    /**
     * Maximum number of queued chunks before dropping old ones.
     * At ~32KB per chunk and 16KB/s playback, 5 chunks ≈ 5 seconds of audio.
     * Beyond this, real-time translation audio is too stale to be useful.
     */
    var maxQueueChunks: Int = 5

    /**
     * When `true`, dropping stale chunks produces a log warning.
     * Set to `false` to silence these during normal operation.
     */
    var logDroppedChunks: Boolean = true

    // ── Public API ─────────────────────────────────────────────────────

    /**
     * Queue a PCM audio chunk for playback.
     *
     * **Non-blocking** — enqueues the data and returns immediately.
     * A background coroutine drains the queue into [AudioTrack] in order.
     * If this is the first chunk after construction or [release], playback
     * starts automatically.
     *
     * If the internal queue exceeds [maxQueueChunks], the oldest chunks are
     * dropped to prevent unbounded latency growth.
     *
     * @param audioData Raw PCM 16kHz 16-bit mono bytes.
     *                  May be empty (no-op) but must not be null.
     */
    fun playChunk(audioData: ByteArray) {
        if (isReleased.get()) {
            Log.w(TAG, "playChunk called after release() — ignoring")
            return
        }

        if (audioData.isEmpty()) {
            Log.v(TAG, "playChunk: empty chunk — skipping")
            return
        }

        // ── Enforce queue cap — drop oldest if exceeded ─────────────────
        while (chunkQueue.size >= maxQueueChunks) {
            val dropped = chunkQueue.poll()
            if (dropped != null && logDroppedChunks) {
                Log.w(
                    TAG,
                    "Queue full (${maxQueueChunks} chunks) — dropping ${dropped.size}B " +
                        "(${dropped.size / 32000f}s) stale audio"
                )
            } else if (dropped == null) {
                break  // Queue shrank between check and poll
            }
        }

        chunkQueue.offer(audioData)
        Log.v(TAG, "Queued ${audioData.size}B (queue=${chunkQueue.size})")

        // ── Auto-start if needed ────────────────────────────────────────
        if (isRunning.compareAndSet(false, true)) {
            startPlayback()
        }
    }

    /**
     * Release all audio resources.
     *
     * Stops playback, clears the queue, and releases the [AudioTrack].
     * After calling this, the instance should not be reused — create a new
     * [AudioPlayer] if playback is needed again.
     *
     * Idempotent — safe to call multiple times.
     */
    fun release() {
        if (isReleased.getAndSet(true)) {
            Log.d(TAG, "release() called again — already released")
            return
        }

        Log.i(
            TAG,
            "Releasing AudioPlayer — total bytes written: ${totalBytesWritten.get()} " +
                "(${totalBytesWritten.get() / (SAMPLE_RATE * BYTES_PER_SAMPLE)}s PCM)"
        )

        // Abandon any lingering audio focus
        abandonAudioFocusAfterChunk()

        isRunning.set(false)

        // Cancel consumer coroutine
        playerJob?.cancel()
        playerJob = null
        scope.cancel()

        // Clear pending chunks
        chunkQueue.clear()

        // Release AudioTrack
        val track = audioTrack
        audioTrack = null
        if (track != null) {
            try {
                if (track.playState == AudioTrack.PLAYSTATE_PLAYING) {
                    track.stop()
                }
                track.release()
                Log.d(TAG, "AudioTrack released")
            } catch (e: Exception) {
                Log.w(TAG, "Error releasing AudioTrack: ${e.message}")
            }
        }
    }

    /**
     * Returns `true` if playback is currently active (AudioTrack playing,
     * coroutine running, or chunks still queued).
     */
    fun isPlaying(): Boolean = isRunning.get()

    /**
     * Returns the number of chunks currently waiting in the playback queue.
     * Values above 1-2 indicate the audio pipeline is producing faster than
     * the speaker can play (possible TTS overspeed or network burst).
     */
    fun queueSize(): Int = chunkQueue.size

    /**
     * Total bytes of PCM audio written to AudioTrack since construction.
     * Divide by 32000 to get approximate seconds of audio played.
     */
    fun totalBytesPlayed(): Long = totalBytesWritten.get()

    // ── Internal: Playback Consumer ────────────────────────────────────

    private fun startPlayback() {
        // ── Initialise AudioTrack (lazy — first chunk triggers it) ──────
        if (audioTrack == null) {
            val bufferSize = trackBufferSize
            if (bufferSize <= 0) {
                Log.e(TAG, "Invalid buffer size ($bufferSize) — cannot initialise AudioTrack")
                isRunning.set(false)
                return
            }

            try {
                audioTrack = AudioTrack.Builder()
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)          // Music/media playback
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)  // Speech content
                            .build()
                    )
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setSampleRate(SAMPLE_RATE)
                            .setChannelMask(CHANNEL_CONFIG)
                            .setEncoding(AUDIO_FORMAT)
                            .build()
                    )
                    .setBufferSizeInBytes(bufferSize)
                    .setTransferMode(AudioTrack.MODE_STREAM)  // Continuous streaming
                    .build()

                Log.i(
                    TAG,
                    "AudioTrack created: $SAMPLE_RATE Hz mono, " +
                        "buffer=${bufferSize}B, mode=STREAM"
                )
            } catch (e: Exception) {
                Log.e(TAG, "AudioTrack constructor failed: ${e.message}", e)
                isRunning.set(false)
                return
            }
        }

        val track = audioTrack ?: run {
            Log.e(TAG, "AudioTrack is null after initialisation")
            isRunning.set(false)
            return
        }

        // ── Launch consumer coroutine ───────────────────────────────────
        playerJob = scope.launch {
            try {
                track.play()
                Log.d(TAG, "Playback started")

                // ── Consumer loop ───────────────────────────────────────
                while (isActive && isRunning.get()) {

                    val chunk = chunkQueue.poll()

                    if (chunk != null && chunk.isNotEmpty()) {
                        // ── Audio Focus Ducking (v2 Module 11) ──────────
                        // Request transient focus with ducking before playing.
                        // This tells the OS to lower YouTube's volume while
                        // our translated speech plays.
                        requestAudioFocusForChunk()

                        // Write to AudioTrack — blocks until buffer has room
                        val bytesWritten = track.write(chunk, 0, chunk.size)

                        // ── Abandon focus after chunk wrote ─────────────
                        // Give audio focus back so YouTube returns to normal volume.
                        // Next chunk will request it again → natural ducking pattern.
                        abandonAudioFocusAfterChunk()

                        if (bytesWritten > 0) {
                            totalBytesWritten.addAndGet(bytesWritten.toLong())
                        }

                        // Diagnose underrun (write returned less than requested)
                        if (bytesWritten < chunk.size) {
                            Log.w(
                                TAG,
                                "AudioTrack underrun: wrote $bytesWritten of ${chunk.size} bytes " +
                                    "(queue=${chunkQueue.size})"
                            )
                        }

                        // Log latency indicator: how far behind is playback?
                        val queueLen = chunkQueue.size
                        if (queueLen > 2) {
                            Log.d(
                                TAG,
                                "Audio lag ~${queueLen * 1000 / SAMPLE_RATE * BYTES_PER_SAMPLE / 32000}s " +
                                    "(queue=$queueLen)"
                            )
                        }
                    } else {
                        // No data — brief yield to avoid busy-wait CPU burn
                        // If the producer has stopped entirely, we'll
                        // pause AudioTrack after a timeout
                        delay(5)  // 5ms sleep
                    }
                }

            } catch (e: CancellationException) {
                Log.d(TAG, "Playback coroutine cancelled")
                // Normal
            } catch (e: Exception) {
                Log.e(TAG, "Playback error: ${e.message}", e)
            } finally {
                // ── Flush remaining queued chunks before stopping ────────
                drainRemaining(track)
                try {
                    track.pause()   // Pause before stop to avoid click
                    track.flush()   // Clear AudioTrack's internal buffer
                    track.stop()
                } catch (e: Exception) {
                    Log.w(TAG, "Error stopping AudioTrack: ${e.message}")
                }
                Log.i(TAG, "Playback stopped — total written: ${totalBytesWritten.get()} bytes")
            }
        }
    }

    /**
     * Request transient audio focus with ducking before playing a chunk.
     *
     * Uses [AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK] which tells the OS:
     * "I'm about to play short speech audio — lower other apps' volume, but
     * don't kill them entirely."
     *
     * Falls back gracefully on API < 26 (Oreo) by using the deprecated
     * [AudioManager.requestAudioFocus] method.
     */
    private fun requestAudioFocusForChunk() {
        if (hasAudioFocus.get()) return  // Already have focus

        val result = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioFocusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setOnAudioFocusChangeListener(focusChangeListener)
                .build()
            audioManager.requestAudioFocus(audioFocusRequest!!)
        } else {
            @Suppress("DEPRECATION")
            audioManager.requestAudioFocus(
                focusChangeListener,
                AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK
            )
        }

        if (result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            hasAudioFocus.set(true)
            Log.v(TAG, "AudioFocus: GRANTED (ducking YouTube)")
        } else {
            Log.w(TAG, "AudioFocus: DENIED (result=$result)")
        }
    }

    /**
     * Abandon audio focus after the chunk finishes playing.
     * This restores YouTube (or other apps) to their original volume.
     *
     * Idempotent — safe to call when focus is not held.
     */
    private fun abandonAudioFocusAfterChunk() {
        if (!hasAudioFocus.getAndSet(false)) return  // Don't have focus

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                audioFocusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
            } else {
                @Suppress("DEPRECATION")
                audioManager.abandonAudioFocus(focusChangeListener)
            }
            Log.v(TAG, "AudioFocus: abandoned — YouTube volume restored")
        } catch (e: Exception) {
            Log.w(TAG, "Error abandoning audio focus: ${e.message}")
        }
    }

    /**
     * Flush any remaining chunks in the queue to AudioTrack when stopping.
     * Avoids chopping off the last second of audio when the user stops recording.
     */
    private suspend fun drainRemaining(track: AudioTrack) {
        var drained = 0
        while (true) {
            val chunk = chunkQueue.poll() ?: break
            if (chunk.isEmpty()) continue
            val written = track.write(chunk, 0, chunk.size)
            if (written > 0) {
                totalBytesWritten.addAndGet(written.toLong())
                drained += written
            }
        }
        if (drained > 0) {
            Log.d(TAG, "Drained $drained remaining bytes (${drained / 32}ms) before stop")
        }
    }
}

// ============================================================================
// Quick-Start Example: Wiring Modules 9–11 Together
// ============================================================================
//
// ```kotlin
// class MainActivity : AppCompatActivity() {
//
//     private val recorder = AudioRecorder()
//     private val wsClient = TranslatorWebSocketClient()
//     private val player = AudioPlayer()
//
//     override fun onCreate(savedInstanceState: Bundle?) {
//         super.onCreate(savedInstanceState)
//         setContentView(R.layout.activity_main)
//
//         // Wire WS → AudioPlayer: received Thai audio → play immediately
//         wsClient.onAudioReceived = { pcmBytes -> player.playChunk(pcmBytes) }
//
//         // Wire WS state → UI
//         wsClient.onConnectionStateChanged = { connected ->
//             runOnUiThread { statusDot.setColor(if (connected) GREEN else RED) }
//         }
//
//         // Connect to backend
//         wsClient.connect(
//             serverUrl = "ws://10.0.2.2:8000",   // Emulator
//             apiKey = BuildConfig.BACKEND_API_KEY
//         )
//
//         // Start recording → WS
//         if (hasRecordPermission(this)) {
//             recorder.startRecording { chunk -> wsClient.sendAudioChunk(chunk) }
//         }
//     }
//
//     override fun onDestroy() {
//         super.onDestroy()
//         recorder.stopRecording()
//         wsClient.disconnect()
//         player.release()   // ← Always release AudioTrack
//     }
// }
// ```
