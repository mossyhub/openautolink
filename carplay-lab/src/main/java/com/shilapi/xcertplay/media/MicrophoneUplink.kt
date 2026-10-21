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
import com.shilapi.xcertplay.media.CaptureAcquisitionLedger.Action
import com.shilapi.xcertplay.media.CaptureAcquisitionLedger.Result

/** Capture/codec/socket release share one owner; at most four pending wire-ready 20ms frames. */
internal class MicrophoneUplink(private val config: MicrophoneConfig,
    private val report: (String) -> Unit = {},
    private val experiment: CarPlayMicrophoneExperimentProfile? = null,
    private val qualifiedCapability: MicrophoneCapability? = null,
    private val captureLedger: CaptureAcquisitionLedger? = null,
    private val speechRecognitionProfile: CarPlayMicrophoneExperimentProfile? = null) : Closeable {
    private val closed = AtomicBoolean()
    private val retired = AtomicBoolean()
    private val started = AtomicBoolean()
    private val recordLock = Any()
    private var captureLease: MicrophoneCaptureFence.Lease? = null
    private val pending = ArrayBlockingQueue<OpusEncoder.Frame>(4)
    @Volatile private var recorder: AudioRecord? = null
    @Volatile private var socket: DatagramSocket? = null
    @Volatile private var policy: MicrophonePolicyDiagnostics? = null
    val isRetired: Boolean get() = retired.get()
    val isClosed: Boolean get() = closed.get()
    private val thread = Thread(::run, "lab-carplay-mic").apply { isDaemon = true }
    constructor(config: MicrophoneConfig, report: (String) -> Unit) : this(config, report, null, null)
    fun start() {
        if (!started.compareAndSet(false, true)) return
        if (closed.get() || !physicalOwner.compareAndSet(null, this)) {
            closed.set(true); config.key.fill(0); retired.set(true)
            report("media stage=microphone-owner-busy")
            return
        }
        captureLease = MicrophoneCaptureFence.tryNormal()
        if (captureLease == null) {
            physicalOwner.compareAndSet(this, null)
            closed.set(true); config.key.fill(0); retired.set(true)
            report("media stage=microphone-owner-busy")
            return
        }
        try { thread.start() } catch (_: Exception) {
            captureLease?.release()
            physicalOwner.compareAndSet(this, null)
            closed.set(true); config.key.fill(0); retired.set(true)
            report("media stage=microphone-worker-start-failed")
        }
    }
    private fun run() {
        var encoder: OpusEncoder? = null
        var capture: AudioRecord? = null
        var transport: DatagramSocket? = null

        var capturedFrames = 0L
        var nonzeroFrames = 0L
        var filled = 0
        var observation: CaptureAcquisitionLedger.Owner? = null
        var rawWindow: Pcm16Window? = null
        var convertedWindow: Pcm16Window? = null
        var observedCaptureRate = 0
        var observedSource: MicrophoneSource? = null
        var observedSelection = CaptureTerminalSummary.Selection.AVERAGE
        var observedExperiment = false
        var captureBlockBytes = 0
        var termination = CaptureAcquisitionLedger.Termination.INELIGIBLE
        fun pcmReceipt(phase: CaptureAcquisitionLedger.Phase) {
            rawWindow?.snapshot()?.let { observation?.pcm(CaptureAcquisitionLedger.Boundary.RAW,phase,observedCaptureRate,it) }
            convertedWindow?.snapshot()?.let { observation?.pcm(CaptureAcquisitionLedger.Boundary.CONVERTED,phase,config.sampleRate,it) }
        }
        fun captureReceipt(ended: Boolean) {
            // Only aggregate signal presence and public routing policy, never samples/levels,
            // device identifiers, addresses or recording configuration dumps.
            // AudioRecord.getRecordingState reads local state; avoid potentially stalled public
            // Binder route/configuration queries on the capture/transport owner.
            val state = runCatching { capture?.recordingState }.getOrNull()
                ?.takeIf { it == AudioRecord.RECORDSTATE_STOPPED || it == AudioRecord.RECORDSTATE_RECORDING }
                ?.toString() ?: "unknown"
            runCatching { report("media stage=microphone-capture type=100 audioType=${config.audioType} frames=$capturedFrames nonzeroFrames=$nonzeroFrames state=$state ended=$ended") }
        }
        try {
            if (closed.get() || !AndroidMicrophoneSupport.permissionGranted()) return
            if (config.formatBits !in (qualifiedCapability ?: AndroidMicrophoneSupport.capability).bits(config.audioType)) return
            val codec = if (config.codec == com.shilapi.xcertplay.airplay.AudioCodecKind.OPUS) {
                val name = (if (experiment == null && speechRecognitionProfile == null) AndroidMicrophoneSupport.encoderName(config.formatBits)
                    else AndroidMicrophoneSupport.experimentEncoderName(config.formatBits)) ?: return
                OpusEncoder(name, config.bitrate)
            } else null
            encoder = codec
            if (closed.get()) return
            val profile = if (config.audioType.equals("speechrecognition", ignoreCase = true))
                speechRecognitionProfile ?: CarPlayMicrophoneCapturePolicy.profile(config.audioType, experiment)
                else CarPlayMicrophoneCapturePolicy.profile(config.audioType, experiment)
            val source = profile?.source ?: checkNotNull(MicrophoneSupport.source(config.audioType))
            val converter = profile?.let { CarPlayMicrophonePcmConverter(it, config.sampleRate) }
            val captureRate = converter?.captureRate ?: config.sampleRate
            val captureChannels = converter?.captureChannels ?: config.channels
            observation = captureLedger?.owner(CaptureAcquisitionLedger.Kind.LIVE, source, captureRate, captureChannels)
            observedCaptureRate = captureRate
            observedSource = source
            observedSelection = CaptureTerminalSummary.Selection.valueOf(profile?.selection?.name ?: "AVERAGE")
            observedExperiment = profile != null
            if (observation != null) {
                rawWindow = Pcm16Window(captureChannels)
                convertedWindow = Pcm16Window(config.channels)
            }
            observation?.event(Action.CREATE)
            val record = AndroidMicrophoneSupport.recorder(source, captureRate, captureChannels, observation)
            capture = record
            require(record.state == AudioRecord.STATE_INITIALIZED && record.sampleRate == captureRate &&
                record.channelCount == captureChannels && record.audioFormat == android.media.AudioFormat.ENCODING_PCM_16BIT)
            observation?.event(Action.VALIDATE, Result.OK)
            val udp = if (config.socketBinding == null) DatagramSocket()
                else DatagramSocket(java.net.InetSocketAddress(config.socketBinding.local, 0))
            transport = udp; socket = udp
            udp.connect(config.host, config.port) // destination comes only from authenticated SETUP peer
            synchronized(recordLock) {
                if (closed.get() || !AndroidMicrophoneSupport.permissionGranted()) return
                recorder = record
                record.startRecording()
            }
            observation?.event(Action.START, Result.OK)
            if (closed.get()) return
            check(record.recordingState == AudioRecord.RECORDSTATE_RECORDING)
            report("media stage=microphone-format type=100 audioType=${config.audioType} codec=${config.codec} rate=${config.sampleRate} channels=${config.channels} spf=${config.rtpSamplesPerPacket} source=$source")
            report("media stage=microphone-started type=100; uplink audibility unverified")
            if (experiment != null) report("media stage=microphone-experiment source=$source captureRate=$captureRate captureChannels=$captureChannels selection=${experiment.selection} wireRate=${config.sampleRate}")
            policy = AndroidMicrophoneSupport.policyDiagnostics(record, source, captureRate) { message ->
                report(message)
                observation?.receipt(message)
            }
            if (closed.get()) { policy?.close(); return }
            val pcm = ByteArray(converter?.captureFrameBytes ?: config.frameBytes)
            captureBlockBytes = pcm.size
            // Same connection-ID re-SETUP can reuse a key: randomized counters avoid restarting nonce0.
            val counters = MicrophoneCounters(nonce = SecureRandom().nextLong().ushr(1))
            var sent = 0L
            while (!closed.get() && AndroidMicrophoneSupport.permissionGranted()) {
                val count = record.read(pcm, filled, pcm.size - filled, AudioRecord.READ_BLOCKING)
                if (closed.get()) break
                check(count >= 0) { "Microphone read failed" }
                if (count == 0) { Thread.sleep(2); continue }
                filled += count
                if (filled != pcm.size) continue
                capturedFrames++
                if (pcm.any { it != 0.toByte() }) nonzeroFrames++
                rawWindow?.observe(pcm)
                if (capturedFrames == 1L || capturedFrames == 250L || capturedFrames == 500L) policy?.sample()
                if (capturedFrames == 1L || capturedFrames % 250 == 0L) captureReceipt(false)
                val wirePcm = converter?.convert(pcm) ?: pcm
                convertedWindow?.observe(wirePcm)
                if (capturedFrames == 1L) pcmReceipt(CaptureAcquisitionLedger.Phase.FIRST)
                val frames = if (codec == null) {
                    // AudioRecord PCM16 is little endian; CarPlay LPCM payloads are big endian.
                    val body = wirePcm.copyOf()
                    for (i in body.indices step 2) { val b = body[i]; body[i] = body[i+1]; body[i+1] = b }
                    listOf(OpusEncoder.Frame(body, sent * 20000))
                } else codec.encode(wirePcm)
                frames.forEach { frame ->
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
        } catch (_: InterruptedException) { termination = CaptureAcquisitionLedger.Termination.CANCELLED
        } catch (failure: Exception) {
            termination = CaptureAcquisitionLedger.Termination.FAILED
            if (!closed.get()) report("media stage=microphone-failed type=100 errorType=${failure.javaClass.simpleName}")
        } finally {
            policy?.close() // revokes best-effort receipts, never joins an OS query
            if (closed.get()) termination = CaptureAcquisitionLedger.Termination.CANCELLED
            else if (!AndroidMicrophoneSupport.permissionGranted()) termination = CaptureAcquisitionLedger.Termination.PERMISSION
            observation?.event(Action.END, if (termination == CaptureAcquisitionLedger.Termination.FAILED) Result.FAILED else Result.CANCELLED)
            val partialBytes = if (filled == captureBlockBytes) 0 else filled
            observation?.signal(capturedFrames, nonzeroFrames, partialBytes, termination, false)
            pcmReceipt(CaptureAcquisitionLedger.Phase.ENDED)
            if (capturedFrames > 0) captureReceipt(true)
            closed.set(true); pending.clear()
            synchronized(recordLock) { recorder = null }
            observation?.event(Action.STOP)
            runCatching { capture?.stop() }
            observation?.event(Action.RELEASE_BEGIN, if (capture == null) Result.ABSENT else Result.ATTEMPT)
            if (capture != null) observation?.let { captureLedger?.terminals?.releasePending(captureLedger.receiverOrdinal,it.ordinal) }
            val captureReleased = runCatching { capture?.release() }.isSuccess
            if (!captureReleased) observation?.let { captureLedger?.terminals?.releaseFailed(captureLedger.receiverOrdinal,it.ordinal) }
            observation?.event(if (captureReleased) Action.RELEASED else Action.RELEASE_FAILED,
                if (!captureReleased) Result.FAILED else if (capture == null) Result.ABSENT else Result.OK)
            if (captureReleased) {
                observation?.signal(capturedFrames, nonzeroFrames, partialBytes, termination, true)
                pcmReceipt(CaptureAcquisitionLedger.Phase.RELEASED)
            }
            val transportReleased = runCatching { transport?.close() }.isSuccess; socket = null
            encoder?.close(); config.key.fill(0)
            if (captureReleased) {
                // Immutable independent completion allowance, only after actual resource release.
                // Enqueue is not durable acknowledgment and never releases the capture fence.
                val ledger = captureLedger
                val owner = observation
                val raw = rawWindow?.snapshot()
                val converted = convertedWindow?.snapshot()
                val source = observedSource
                if (capture != null && transportReleased && ledger != null && owner != null &&
                    raw != null && converted != null && source != null) {
                    ledger.terminals.complete(CaptureTerminalSummary(ledger.receiverOrdinal, owner.ordinal,
                        if (ledger.manualHistory) CaptureTerminalSummary.History.MANUAL else CaptureTerminalSummary.History.NORMAL,
                        if (observedExperiment) CaptureTerminalSummary.Profile.EXPERIMENT else CaptureTerminalSummary.Profile.DEFAULT,
                        source, observedCaptureRate, raw.channels.size, config.sampleRate, observedSelection,
                        termination, capturedFrames, nonzeroFrames, partialBytes, raw, converted))
                }
                captureLease?.release()
                physicalOwner.compareAndSet(this, null)
                retired.set(true)
                observation?.event(Action.RETIRED, Result.OK)
            }
        }
    }
    override fun close() {
        closed.set(true)
        policy?.close() // revoke diagnostics even while the exact recorder read is stalled
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
