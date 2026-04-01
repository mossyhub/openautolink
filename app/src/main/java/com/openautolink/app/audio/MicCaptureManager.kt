package com.openautolink.app.audio

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Process
import android.util.Log
import com.openautolink.app.transport.AudioPurpose
import com.openautolink.app.transport.BridgeConnection
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Captures audio from the car's built-in mic (AAOS AudioRecord) and sends
 * PCM frames to the bridge on the audio channel (direction=1).
 *
 * Only active when mic source preference is "car". When "phone", the bridge
 * handles mic capture via BT HFP from the phone directly.
 *
 * The mic purpose is set based on the current call state:
 *   - IN_CALL → PHONE_CALL purpose (bridge routes to BT SCO)
 *   - Otherwise → ASSISTANT purpose (bridge routes to aasdk for AA voice)
 *
 * Timer-based sampling at ~40ms intervals (~25 Hz), 512-sample circular reads.
 * Sample rate comes from bridge mic_start control message (typically 16000 Hz).
 */
class MicCaptureManager(private val bridgeConnection: BridgeConnection) {

    companion object {
        private const val TAG = "MicCaptureManager"
        private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        private const val AUDIO_ENCODING = AudioFormat.ENCODING_PCM_16BIT
        private const val SAMPLES_PER_READ = 512
    }

    private val capturing = AtomicBoolean(false)
    private var captureThread: Thread? = null
    private var audioRecord: AudioRecord? = null
    private var currentSampleRate: Int = 16000

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

        currentSampleRate = sampleRate
        captureThread = Thread({
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
            captureLoop(sampleRate)
        }, "MicCapture").also { it.start() }

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

    private fun captureLoop(sampleRate: Int) {
        val minBufSize = AudioRecord.getMinBufferSize(sampleRate, CHANNEL_CONFIG, AUDIO_ENCODING)
        if (minBufSize == AudioRecord.ERROR || minBufSize == AudioRecord.ERROR_BAD_VALUE) {
            Log.e(TAG, "Invalid AudioRecord buffer size for ${sampleRate}Hz")
            capturing.set(false)
            return
        }

        val bufferSize = maxOf(minBufSize * 2, SAMPLES_PER_READ * 2) // 16-bit = 2 bytes per sample
        val recorder = try {
            AudioRecord(
                MediaRecorder.AudioSource.MIC,
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

        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
            Log.e(TAG, "AudioRecord failed to initialize")
            recorder.release()
            capturing.set(false)
            return
        }

        audioRecord = recorder
        recorder.startRecording()

        val readBuf = ByteArray(SAMPLES_PER_READ * 2) // 16-bit PCM = 2 bytes/sample

        try {
            while (capturing.get() && !Thread.currentThread().isInterrupted) {
                val bytesRead = recorder.read(readBuf, 0, readBuf.size)
                if (bytesRead > 0) {
                    val frame = AudioFrame(
                        direction = AudioFrame.DIRECTION_MIC,
                        purpose = micPurpose.get(),
                        sampleRate = sampleRate,
                        channels = 1,
                        data = readBuf.copyOf(bytesRead)
                    )
                    bridgeConnection.sendMicAudio(frame)
                } else if (bytesRead < 0) {
                    Log.w(TAG, "AudioRecord.read error: $bytesRead")
                    break
                }
            }
        } catch (_: InterruptedException) {
            // Expected on stop
        } finally {
            try {
                recorder.stop()
            } catch (_: IllegalStateException) {}
            recorder.release()
            audioRecord = null
            capturing.set(false)
        }
    }
}
