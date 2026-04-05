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
 * Ring buffer absorbs TCP jitter. Drain thread paces writes to AudioTrack
 * using playback head position tracking — never writes faster than real-time.
 */
class AudioPurposeSlot(
    val purpose: AudioPurpose,
    val sampleRate: Int,
    val channelCount: Int,
    private val bufferDurationMs: Int = 500
) {
    companion object {
        private const val TAG = "AudioPurposeSlot"
        private const val TRACK_BUFFER_MULTIPLIER = 16 // Large buffer absorbs SSH tunnel jitter
        private const val TRACK_BUFFER_MIN_BYTES = 76800 // ~200ms at 48kHz stereo 16-bit
    }

    private var audioTrack: AudioTrack? = null

    private val active = AtomicBoolean(false)
    private val released = AtomicBoolean(false)
    private val pausedByFocusLoss = AtomicBoolean(false)

    val framesWritten = AtomicLong(0)
    val underrunCount = AtomicLong(0)

    /**
     * Create the AudioTrack and ring buffer. Does NOT start playback.
     */
    fun initialize() {
        if (released.get()) return

        val channelMask = if (channelCount == 2)
            AudioFormat.CHANNEL_OUT_STEREO else AudioFormat.CHANNEL_OUT_MONO

        val minBufSize = AudioTrack.getMinBufferSize(
            sampleRate, channelMask, AudioFormat.ENCODING_PCM_16BIT
        )
        val trackBufSize = maxOf(minBufSize * TRACK_BUFFER_MULTIPLIER, TRACK_BUFFER_MIN_BYTES)

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

        Log.d(TAG, "Initialized $purpose: ${sampleRate}Hz ${channelCount}ch, track=${trackBufSize}B")
    }

    fun start() {
        if (released.get() || active.get()) return
        val track = audioTrack ?: return
        active.set(true)
        track.play()
        // No drain thread — feedPcm writes directly to AudioTrack
        // from the dedicated audioDispatcher thread (blocking write,
        // paced by AudioTrack to exactly real-time).
        Log.d(TAG, "$purpose playback started")
    }

    fun stop() {
        pausedByFocusLoss.set(false)
        if (!active.getAndSet(false)) return
        audioTrack?.pause()
        audioTrack?.flush()
        Log.d(TAG, "$purpose playback stopped")
    }

    fun pause() {
        if (!active.getAndSet(false)) return
        pausedByFocusLoss.set(true)
        audioTrack?.pause()
        Log.d(TAG, "$purpose playback paused (focus loss)")
    }

    fun resume() {
        if (released.get() || active.get()) return
        if (!pausedByFocusLoss.getAndSet(false)) return
        val track = audioTrack ?: return
        active.set(true)
        track.play()
        Log.d(TAG, "$purpose playback resumed (focus regain)")
    }

    val isPausedByFocus: Boolean get() = pausedByFocusLoss.get()

    /**
     * Write PCM data directly to AudioTrack (blocking).
     * Called from the dedicated audioDispatcher thread — blocking here
     * naturally paces to exactly the sample rate, same as stagefright.
     * The audioDispatcher processes frames sequentially, so TCP bursts
     * are absorbed by AudioTrack's internal buffer (8x minimum = ~160ms).
     */
    fun feedPcm(data: ByteArray) {
        val track = audioTrack ?: return
        if (!active.get()) return
        val bytesPerFrame = channelCount * 2
        track.write(data, 0, data.size) // Blocking — paced by AudioTrack
        framesWritten.addAndGet(data.size.toLong() / bytesPerFrame)
    }

    fun setVolume(volume: Float) {
        audioTrack?.setVolume(volume.coerceIn(0f, 1f))
    }

    fun release() {
        if (released.getAndSet(true)) return
        stop()
        audioTrack?.release()
        audioTrack = null
        Log.d(TAG, "$purpose slot released")
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
