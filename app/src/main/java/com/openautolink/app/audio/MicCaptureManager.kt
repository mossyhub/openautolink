package com.openautolink.app.audio

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.os.Process
import android.util.Log
import com.openautolink.app.transport.AudioPurpose
import com.shilapi.xcertplay.media.MicrophoneCaptureFence
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Captures audio from the car's built-in mic (AAOS AudioRecord) and sends
 * PCM frames to the phone via the AA session.
 *
 * Only active when mic source preference is "car". When "phone", the phone
 * handles mic capture directly.
 *
 * The mic purpose is set based on the current call state:
 *   - IN_CALL → PHONE_CALL purpose
 *   - Otherwise → ASSISTANT purpose (AA voice recognition)
 *
 * Timer-based sampling at ~40ms intervals (~25 Hz), 512-sample circular reads.
 * Sample rate comes from mic_start control message (typically 16000 Hz).
 */
class MicCaptureManager(private val diagnostic: (String) -> Unit = {}, private val sendMicFrame: (AudioFrame) -> Unit) {

    companion object {
        private const val TAG = "MicCaptureManager"
        private val owners = java.util.concurrent.atomic.AtomicLong()
        private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        private const val AUDIO_ENCODING = AudioFormat.ENCODING_PCM_16BIT
        private const val SAMPLES_PER_READ = 512
    }

    private val capturing = AtomicBoolean(false)
    private var captureThread: Thread? = null
    private var audioRecord: AudioRecord? = null
    private var currentSampleRate: Int = 16000
    private var noiseSuppressor: NoiseSuppressor? = null
    private var agc: AutomaticGainControl? = null
    private var aec: AcousticEchoCanceler? = null

    /** Current purpose for outgoing mic frames. Updated by SessionManager on call state changes. */
    private val micPurpose = AtomicReference(AudioPurpose.ASSISTANT)

    /**
     * Update the purpose tag on outgoing mic frames.
     * Call when call state changes (IN_CALL → PHONE_CALL, otherwise → ASSISTANT).
     */
    fun setMicPurpose(purpose: AudioPurpose) {
        micPurpose.set(purpose)
        Log.d(TAG, "Mic purpose set to $purpose")
    }

    /**
     * Start mic capture from the car's AudioRecord.
     * No-op if already capturing.
     */
    fun start(sampleRate: Int) {
        if (capturing.getAndSet(true)) return

        val lease = MicrophoneCaptureFence.tryNormal()
        if (lease == null) { capturing.set(false); return }
        currentSampleRate = sampleRate
        try {
            captureThread = Thread({ captureLoop(sampleRate, lease) }, "MicCapture").also { it.start() }
        } catch (failure: Throwable) {
            lease.release(); capturing.set(false)
            throw failure
        }

        Log.i(TAG, "Mic capture started at ${sampleRate}Hz")
    }

    /**
     * Stop mic capture and release AudioRecord.
     */
    fun stop() {
        if (!capturing.getAndSet(false)) return

        captureThread?.interrupt()
        captureThread = null

        Log.i(TAG, "Mic capture stopped")
    }

    /** Release all resources. Call on session end. */
    fun release() {
        stop()
        micPurpose.set(AudioPurpose.ASSISTANT)
    }

    private fun captureLoop(sampleRate: Int, lease: MicrophoneCaptureFence.Lease) {
        // Keep this reservation local to the existing worker. stop()/interrupt is not release.
        var captureReleased = true
        try {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        val minBufSize = AudioRecord.getMinBufferSize(sampleRate, CHANNEL_CONFIG, AUDIO_ENCODING)
        if (minBufSize == AudioRecord.ERROR || minBufSize == AudioRecord.ERROR_BAD_VALUE) {
            Log.e(TAG, "Invalid AudioRecord buffer size for ${sampleRate}Hz")
            capturing.set(false)
            return
        }

        val bufferSize = maxOf(minBufSize * 2, SAMPLES_PER_READ * 2) // 16-bit = 2 bytes per sample

        // Use VOICE_RECOGNITION source — on AAOS this routes to the car's
        // cabin microphone and disables platform noise processing so the
        // phone's voice recognizer gets a clean signal.
        val recorder = try {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                sampleRate,
                CHANNEL_CONFIG,
                AUDIO_ENCODING,
                bufferSize
            )
        } catch (e: SecurityException) {
            Log.e(TAG, "RECORD_AUDIO permission denied", e)
            capturing.set(false)
            return
        }

        captureReleased = false
        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
            Log.e(TAG, "AudioRecord failed to initialize (source=VOICE_RECOGNITION, rate=$sampleRate)")
            recorder.release()
            captureReleased = true
            capturing.set(false)
            return
        }

        Log.i(TAG, "AudioRecord initialized: source=VOICE_RECOGNITION rate=$sampleRate bufSize=$bufferSize")
        audioRecord = recorder
        recorder.startRecording()

        // Attach hardware DSP audio processing effects for voice quality
        attachAudioEffects(recorder.audioSessionId)

        val readBuf = ByteArray(SAMPLES_PER_READ * 2) // 16-bit PCM = 2 bytes/sample
        var frameCount = 0L
        var silentFrameCount = 0L
        val signal = AaMicSignal()
        val owner = owners.incrementAndGet()

        try {
            while (capturing.get() && !Thread.currentThread().isInterrupted) {
                val bytesRead = recorder.read(readBuf, 0, readBuf.size)
                if (bytesRead > 0) {
                    frameCount++
                    signal.read(readBuf, bytesRead)

                    // Calculate RMS to detect silence (check first few frames + periodic)
                    if (frameCount <= 5 || frameCount % 500 == 0L) {
                        val rms = computeRms(readBuf, bytesRead)
                        if (rms < 10) silentFrameCount++
                        Log.d(TAG, "Mic frame #$frameCount: ${bytesRead}B rms=$rms silent=$silentFrameCount/$frameCount")
                    }

                    val frame = AudioFrame(
                        direction = AudioFrame.DIRECTION_MIC,
                        purpose = micPurpose.get(),
                        sampleRate = sampleRate,
                        channels = 1,
                        data = readBuf.copyOf(bytesRead)
                    )
                    sendMicFrame(frame)
                } else if (bytesRead < 0) {
                    signal.readError()
                    Log.w(TAG, "AudioRecord.read error: $bytesRead")
                    break
                }
            }
        } catch (_: InterruptedException) {
            // Expected on stop
        } finally {
            Log.i(TAG, "Mic capture ending: $frameCount frames captured, $silentFrameCount silent")
            runCatching { diagnostic(signal.line(owner, sampleRate)) }
            releaseAudioEffects()
            try {
                recorder.stop()
            } catch (_: IllegalStateException) {}
            recorder.release()
            captureReleased = true
            runCatching { diagnostic("AA_MIC owner=$owner state=released") }
            audioRecord = null
            capturing.set(false)
        }
        } finally {
            if (captureReleased) lease.release()
        }
    }

    /**
     * Attach hardware-accelerated audio processing effects to the AudioRecord session.
     * NoiseSuppressor reduces road/engine noise, AGC normalizes volume for different
     * speaker distances, AEC prevents the phone from hearing its own audio output
     * through the car speakers (echo during calls).
     */
    private fun attachAudioEffects(audioSessionId: Int) {
        if (NoiseSuppressor.isAvailable()) {
            try {
                noiseSuppressor = NoiseSuppressor.create(audioSessionId)?.also { it.enabled = true }
                Log.i(TAG, "NoiseSuppressor attached (enabled=${noiseSuppressor?.enabled})")
            } catch (e: Exception) {
                Log.w(TAG, "Failed to create NoiseSuppressor: ${e.message}")
            }
        } else {
            Log.d(TAG, "NoiseSuppressor not available on this device")
        }

        if (AutomaticGainControl.isAvailable()) {
            try {
                agc = AutomaticGainControl.create(audioSessionId)?.also { it.enabled = true }
                Log.i(TAG, "AutomaticGainControl attached (enabled=${agc?.enabled})")
            } catch (e: Exception) {
                Log.w(TAG, "Failed to create AGC: ${e.message}")
            }
        } else {
            Log.d(TAG, "AutomaticGainControl not available on this device")
        }

        if (AcousticEchoCanceler.isAvailable()) {
            try {
                aec = AcousticEchoCanceler.create(audioSessionId)?.also { it.enabled = true }
                Log.i(TAG, "AcousticEchoCanceler attached (enabled=${aec?.enabled})")
            } catch (e: Exception) {
                Log.w(TAG, "Failed to create AEC: ${e.message}")
            }
        } else {
            Log.d(TAG, "AcousticEchoCanceler not available on this device")
        }
    }

    private fun releaseAudioEffects() {
        noiseSuppressor?.release(); noiseSuppressor = null
        agc?.release(); agc = null
        aec?.release(); aec = null
    }

    /** Compute RMS of 16-bit LE PCM samples for silence detection. */
    private fun computeRms(buf: ByteArray, length: Int): Int {
        val numSamples = length / 2
        if (numSamples == 0) return 0
        var sumSquares = 0L
        for (i in 0 until numSamples) {
            val lo = buf[i * 2].toInt() and 0xFF
            val hi = buf[i * 2 + 1].toInt()
            val sample = (hi shl 8) or lo // signed 16-bit LE
            sumSquares += sample.toLong() * sample.toLong()
        }
        return kotlin.math.sqrt(sumSquares.toDouble() / numSamples).toInt()
    }
}
