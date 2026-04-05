package com.openautolink.app.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Process
import android.util.Log
import com.openautolink.app.transport.AudioPurpose
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * One AudioTrack + AudioRingBuffer per audio purpose.
 * Based on app_v1's production-proven approach:
 * - Ring buffer absorbs TCP/network jitter (500ms capacity)
 * - Dedicated URGENT_AUDIO thread drains at steady rate
 * - Pre-fill 80ms before calling AudioTrack.play()
 * - Non-blocking writes with residual tracking (no data loss)
 * - Steady 10ms write pacing from dedicated thread
 */
class AudioPurposeSlot(
    val purpose: AudioPurpose,
    val sampleRate: Int,
    val channelCount: Int,
    private val bufferDurationMs: Int = 500
) {
    companion object {
        private const val TAG = "AudioPurposeSlot"
        private const val PREFILL_MS = 80
        private const val DRAIN_CHUNK_MS = 10
    }

    private var audioTrack: AudioTrack? = null
    private var ringBuffer: AudioRingBuffer? = null
    private var playbackThread: Thread? = null

    private val active = AtomicBoolean(false)
    private val released = AtomicBoolean(false)
    private val pausedByFocusLoss = AtomicBoolean(false)
    @Volatile private var trackPlaying = false

    val framesWritten = AtomicLong(0)
    val underrunCount = AtomicLong(0)

    fun initialize() {
        if (released.get()) return

        val channelMask = if (channelCount == 2)
            AudioFormat.CHANNEL_OUT_STEREO else AudioFormat.CHANNEL_OUT_MONO

        val minBufSize = AudioTrack.getMinBufferSize(
            sampleRate, channelMask, AudioFormat.ENCODING_PCM_16BIT
        )
        val trackBufSize = maxOf(minBufSize * 4, 16384)

        audioTrack = AudioTrack.Builder()
            .setAudioAttributes(buildAudioAttributes(purpose))
            .setAudioFormat(AudioFormat.Builder()
                .setSampleRate(sampleRate)
                .setChannelMask(channelMask)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .build())
            .setBufferSizeInBytes(trackBufSize)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
            .build()

        val bytesPerSample = 2
        val ringCapacity = sampleRate * channelCount * bytesPerSample * bufferDurationMs / 1000
        ringBuffer = AudioRingBuffer(ringCapacity)

        Log.d(TAG, "Initialized $purpose: ${sampleRate}Hz ${channelCount}ch, " +
                "track=${trackBufSize}B, ring=${ringCapacity}B")
    }

    fun start() {
        if (released.get() || active.get()) return
        audioTrack ?: return
        active.set(true)
        trackPlaying = false
        playbackThread = Thread({
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
            drainLoop()
        }, "AudioSlot-${purpose.name}").also { it.start() }
        Log.d(TAG, "$purpose active (prefilling ${PREFILL_MS}ms)")
    }

    fun stop() {
        pausedByFocusLoss.set(false)
        if (!active.getAndSet(false)) return
        playbackThread?.join(1000)
        playbackThread = null
        audioTrack?.pause()
        audioTrack?.flush()
        ringBuffer?.clear()
        trackPlaying = false
        Log.d(TAG, "$purpose stopped")
    }

    fun pause() {
        if (!active.getAndSet(false)) return
        pausedByFocusLoss.set(true)
        playbackThread?.join(1000)
        playbackThread = null
        audioTrack?.pause()
        trackPlaying = false
        Log.d(TAG, "$purpose paused (focus loss)")
    }

    fun resume() {
        if (released.get() || active.get()) return
        if (!pausedByFocusLoss.getAndSet(false)) return
        audioTrack ?: return
        active.set(true)
        trackPlaying = false
        playbackThread = Thread({
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
            drainLoop()
        }, "AudioSlot-${purpose.name}").also { it.start() }
        Log.d(TAG, "$purpose resumed")
    }

    val isPausedByFocus: Boolean get() = pausedByFocusLoss.get()

    fun feedPcm(data: ByteArray) {
        ringBuffer?.write(data)
    }

    fun setVolume(volume: Float) {
        audioTrack?.setVolume(volume.coerceIn(0f, 1f))
    }

    fun release() {
        if (released.getAndSet(true)) return
        stop()
        audioTrack?.release()
        audioTrack = null
        ringBuffer = null
        Log.d(TAG, "$purpose released")
    }

    val isActive: Boolean get() = active.get()
    val ringBufferAvailable: Int get() = ringBuffer?.available ?: 0
    val ringBufferCapacity: Int get() = ringBuffer?.capacity ?: 0

    private fun drainLoop() {
        val track = audioTrack ?: return
        val ring = ringBuffer ?: return
        val bytesPerFrame = channelCount * 2
        val bytesPerMs = sampleRate * bytesPerFrame / 1000
        val prefillBytes = bytesPerMs * PREFILL_MS
        val chunkBytes = bytesPerMs * DRAIN_CHUNK_MS
        val chunk = ByteArray(chunkBytes)
        var residualBuf: ByteArray? = null
        var residualOff = 0
        var residualLen = 0

        while (active.get()) {
            if (!trackPlaying) {
                if (ring.available >= prefillBytes) {
                    track.play()
                    trackPlaying = true
                    Log.d(TAG, "$purpose prefill done, playing")
                } else {
                    Thread.sleep(5)
                    continue
                }
            }

            if (residualBuf != null && residualLen > 0) {
                val w = track.write(residualBuf!!, residualOff, residualLen,
                    AudioTrack.WRITE_NON_BLOCKING)
                if (w > 0) {
                    framesWritten.addAndGet(w.toLong() / bytesPerFrame)
                    residualOff += w
                    residualLen -= w
                }
                if (residualLen <= 0) {
                    residualBuf = null
                    residualOff = 0
                    residualLen = 0
                } else {
                    Thread.sleep(1)
                    continue
                }
            }

            val avail = ring.available
            if (avail >= chunkBytes) {
                val read = ring.read(chunk)
                if (read > 0) {
                    val w = track.write(chunk, 0, read, AudioTrack.WRITE_NON_BLOCKING)
                    if (w > 0) {
                        framesWritten.addAndGet(w.toLong() / bytesPerFrame)
                    }
                    if (w in 0 until read) {
                        residualBuf = chunk.copyOf()
                        residualOff = maxOf(w, 0)
                        residualLen = read - maxOf(w, 0)
                    }
                }
                Thread.sleep(DRAIN_CHUNK_MS.toLong())
            } else if (avail > 0) {
                val partial = ByteArray(avail)
                val read = ring.read(partial)
                if (read > 0) {
                    val w = track.write(partial, 0, read, AudioTrack.WRITE_NON_BLOCKING)
                    if (w > 0) framesWritten.addAndGet(w.toLong() / bytesPerFrame)
                    if (w in 0 until read) {
                        residualBuf = partial
                        residualOff = maxOf(w, 0)
                        residualLen = read - maxOf(w, 0)
                    }
                }
                Thread.sleep(DRAIN_CHUNK_MS.toLong())
            } else {
                underrunCount.incrementAndGet()
                Thread.sleep(5)
            }
        }
    }

    private fun buildAudioAttributes(purpose: AudioPurpose): AudioAttributes {
        val usage = when (purpose) {
            AudioPurpose.MEDIA -> AudioAttributes.USAGE_MEDIA
            AudioPurpose.NAVIGATION -> AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE
            AudioPurpose.ASSISTANT -> AudioAttributes.USAGE_ASSISTANT
            AudioPurpose.PHONE_CALL -> AudioAttributes.USAGE_VOICE_COMMUNICATION
            AudioPurpose.ALERT -> AudioAttributes.USAGE_NOTIFICATION_RINGTONE
        }
        val contentType = when (purpose) {
            AudioPurpose.MEDIA -> AudioAttributes.CONTENT_TYPE_MUSIC
            AudioPurpose.PHONE_CALL -> AudioAttributes.CONTENT_TYPE_SPEECH
            AudioPurpose.ASSISTANT -> AudioAttributes.CONTENT_TYPE_SPEECH
            AudioPurpose.NAVIGATION -> AudioAttributes.CONTENT_TYPE_SPEECH
            AudioPurpose.ALERT -> AudioAttributes.CONTENT_TYPE_SONIFICATION
        }
        return AudioAttributes.Builder()
            .setUsage(usage)
            .setContentType(contentType)
            .build()
    }
}