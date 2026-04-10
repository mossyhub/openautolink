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
    }

    private var audioTrack: AudioTrack? = null

    private val active = AtomicBoolean(false)
    private val released = AtomicBoolean(false)
    private val pausedByFocusLoss = AtomicBoolean(false)

    val framesWritten = AtomicLong(0)
    val underrunCount = AtomicLong(0)

    // Diagnostic counters
    @Volatile var lastFeedTimeNs: Long = 0L
    @Volatile var maxWriteMs: Long = 0L
    @Volatile var maxGapMs: Long = 0L
    @Volatile var totalWriteCalls: Long = 0L
    @Volatile var slowWriteCount: Long = 0L  // writes > 60ms
    @Volatile var hwUnderrunCount: Long = 0L

    fun initialize() {
        if (released.get()) return

        val channelMask = if (channelCount == 2)
            AudioFormat.CHANNEL_OUT_STEREO else AudioFormat.CHANNEL_OUT_MONO

        val minBufSize = AudioTrack.getMinBufferSize(
            sampleRate, channelMask, AudioFormat.ENCODING_PCM_16BIT
        )
        // Use 4x min buffer — same as app_v1 production
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
            .build()

        Log.d(TAG, "Initialized $purpose: ${sampleRate}Hz ${channelCount}ch, track=${trackBufSize}B")
    }

    fun start() {
        if (released.get() || active.get()) return
        val track = audioTrack ?: return
        active.set(true)
        track.play()
        Log.d(TAG, "$purpose started")
    }

    fun stop() {
        pausedByFocusLoss.set(false)
        if (!active.getAndSet(false)) return
        audioTrack?.pause()
        audioTrack?.flush()
        Log.d(TAG, "$purpose stopped")
    }

    fun pause() {
        if (!active.getAndSet(false)) return
        pausedByFocusLoss.set(true)
        audioTrack?.pause()
        Log.d(TAG, "$purpose paused")
    }

    fun resume() {
        if (released.get() || active.get()) return
        if (!pausedByFocusLoss.getAndSet(false)) return
        val track = audioTrack ?: return
        active.set(true)
        track.play()
        Log.d(TAG, "$purpose resumed")
    }

    val isPausedByFocus: Boolean get() = pausedByFocusLoss.get()

    /**
     * Write PCM directly to AudioTrack. Called from audioDispatcher thread.
     * Blocking write — AudioTrack paces to real-time.
     * Each 8192-byte frame from bridge = 42.7ms of audio.
     * The per-write overhead (~50ms on emulator) is acceptable for 42ms chunks.
     */
    fun feedPcm(data: ByteArray) {
        val track = audioTrack ?: return
        if (!active.get()) return

        // Measure inter-frame gap
        val nowNs = System.nanoTime()
        val prevNs = lastFeedTimeNs
        lastFeedTimeNs = nowNs
        if (prevNs > 0) {
            val gapMs = (nowNs - prevNs) / 1_000_000
            if (gapMs > maxGapMs) maxGapMs = gapMs
        }

        // Measure AudioTrack.write() blocking duration
        val writeStartNs = System.nanoTime()
        track.write(data, 0, data.size) // blocking
        val writeMs = (System.nanoTime() - writeStartNs) / 1_000_000

        totalWriteCalls++
        framesWritten.addAndGet(data.size.toLong() / (channelCount * 2))
        if (writeMs > maxWriteMs) maxWriteMs = writeMs
        if (writeMs > 60) slowWriteCount++

        // Sample HW underrun count periodically
        if (totalWriteCalls % 50 == 0L) {
            try {
                hwUnderrunCount = track.underrunCount.toLong()
            } catch (_: Exception) {}
        }
    }

    fun setVolume(volume: Float) {
        audioTrack?.setVolume(volume.coerceIn(0f, 1f))
    }

    fun release() {
        if (released.getAndSet(true)) return
        stop()
        audioTrack?.release()
        audioTrack = null
        Log.d(TAG, "$purpose released")
    }

    val isActive: Boolean get() = active.get()
    val ringBufferAvailable: Int get() = 0
    val ringBufferCapacity: Int get() = 0

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