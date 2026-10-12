// SPDX-License-Identifier: GPL-3.0-only
// Narrow adaptation of DiPlay e2fd8eadf5a6b705666f292055e72aa93387533d,
// shared/src/main/java/com/shilapi/xcertplay/media/MicrophoneUplink.kt. No raw audio/key logs.
package com.shilapi.xcertplay.media

import android.media.AudioRecord
import com.shilapi.xcertplay.airplay.MicrophoneConfig
import com.shilapi.xcertplay.airplay.MicrophoneCounters
import com.shilapi.xcertplay.airplay.MicrophonePacketizer
import java.io.Closeable
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.security.SecureRandom
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean

/** Capture/codec/socket release share one owner; at most four pending encoded 20ms frames. */
internal class MicrophoneUplink(private val config: MicrophoneConfig,
    private val report: (String) -> Unit = {}) : Closeable {
    private val closed = AtomicBoolean()
    private val retired = AtomicBoolean()
    private val started = AtomicBoolean()
    private val recordLock = Any()
    private val pending = ArrayBlockingQueue<OpusEncoder.Frame>(4)
    @Volatile private var recorder: AudioRecord? = null
    @Volatile private var socket: DatagramSocket? = null
    val isRetired: Boolean get() = retired.get()
    val isClosed: Boolean get() = closed.get()
    private val thread = Thread(::run, "lab-carplay-mic").apply { isDaemon = true }
    fun start() {
        if (!started.compareAndSet(false, true)) return
        if (closed.get() || !physicalOwner.compareAndSet(null, this)) {
            closed.set(true); config.key.fill(0); retired.set(true)
            report("media stage=microphone-owner-busy")
            return
        }
        try { thread.start() } catch (_: Exception) {
            physicalOwner.compareAndSet(this, null)
            closed.set(true); config.key.fill(0); retired.set(true)
            report("media stage=microphone-worker-start-failed")
        }
    }
    private fun run() {
        var encoder: OpusEncoder? = null
        var capture: AudioRecord? = null
        var transport: DatagramSocket? = null
        try {
            if (closed.get() || !AndroidMicrophoneSupport.permissionGranted()) return
            if (config.formatBits !in AndroidMicrophoneSupport.capability.bits(config.audioType)) return
            val name = AndroidMicrophoneSupport.encoderName(config.formatBits) ?: return
            val codec = OpusEncoder(name, config.bitrate)
            encoder = codec
            if (closed.get()) return
            // The helper wires MIC, VOICE_COMMUNICATION and VOICE_RECOGNITION to actual AudioRecord sources.
            val source = checkNotNull(MicrophoneSupport.source(config.audioType))
            val record = AndroidMicrophoneSupport.recorder(source)
            capture = record
            require(record.state == AudioRecord.STATE_INITIALIZED)
            val udp = if (config.socketBinding == null) DatagramSocket()
                else DatagramSocket(java.net.InetSocketAddress(config.socketBinding.local, 0))
            transport = udp; socket = udp
            udp.connect(config.host, config.port) // destination comes only from authenticated SETUP peer
            synchronized(recordLock) {
                if (closed.get() || !AndroidMicrophoneSupport.permissionGranted()) return
                recorder = record
                record.startRecording()
            }
            if (closed.get()) return
            check(record.recordingState == AudioRecord.RECORDSTATE_RECORDING)
            report("media stage=microphone-started type=100; uplink audibility unverified")
            val pcm = ByteArray(config.frameBytes)
            // Same connection-ID re-SETUP can reuse a key: randomized counters avoid restarting nonce0.
            val counters = MicrophoneCounters(nonce = SecureRandom().nextLong().ushr(1))
            var filled = 0
            var sent = 0L
            while (!closed.get() && AndroidMicrophoneSupport.permissionGranted()) {
                val count = record.read(pcm, filled, pcm.size - filled, AudioRecord.READ_BLOCKING)
                if (closed.get()) break
                check(count >= 0) { "Microphone read failed" }
                if (count == 0) { Thread.sleep(2); continue }
                filled += count
                if (filled != pcm.size) continue
                codec.encode(pcm).forEach { frame ->
                    if (!pending.offer(frame)) {
                        pending.poll(); pending.offer(frame)
                        report("media stage=microphone-queue-overflow")
                    }
                }
                while (!closed.get() && AndroidMicrophoneSupport.permissionGranted()) {
                    val encoded = pending.poll() ?: break
                    // Delayed/dropped output preserves original capture PTS on the negotiated RTP clock.
                    counters.timestamp = (encoded.presentationTimeUs * config.rtpSamplesPerPacket / 20000).toInt()
                    val wire = MicrophonePacketizer.sealPacket(config.key, 100, counters, encoded.bytes, config.rtpSamplesPerPacket)
                    udp.send(DatagramPacket(wire, wire.size))
                    sent++
                    if (sent == 1L || sent % 250 == 0L)
                        report("media stage=microphone-datagram-sent packets=$sent; phone receipt unverified")
                }
                filled = 0
            }
        } catch (_: InterruptedException) { /* retirement */
        } catch (failure: Exception) {
            if (!closed.get()) report("media stage=microphone-failed type=100 errorType=${failure.javaClass.simpleName}")
        } finally {
            closed.set(true); pending.clear()
            synchronized(recordLock) { recorder = null }
            runCatching { capture?.stop() }; runCatching { capture?.release() }
            runCatching { transport?.close() }; socket = null
            encoder?.close(); config.key.fill(0)
            physicalOwner.compareAndSet(this, null)
            retired.set(true)
        }
    }
    override fun close() {
        closed.set(true)
        // stop unblocks READ_BLOCKING. Only the owning worker releases recorder/codec.
        synchronized(recordLock) { runCatching { recorder?.stop() } }
        socket?.close(); thread.interrupt()
        if (Thread.currentThread() !== thread) {
            try { thread.join(500) } catch (_: InterruptedException) { Thread.currentThread().interrupt() }
        }
        if (!started.get()) { config.key.fill(0); retired.set(true) }
        if (!isRetired) report("media stage=microphone-retirement-pending")
    }
    private companion object {
        // A new sink must not bypass a stalled previous sink's physical capture retirement fence.
        val physicalOwner = java.util.concurrent.atomic.AtomicReference<MicrophoneUplink?>()
    }
}
