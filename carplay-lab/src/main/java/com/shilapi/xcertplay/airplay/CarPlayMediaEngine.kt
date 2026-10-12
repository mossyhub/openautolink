// SPDX-License-Identifier: GPL-3.0-only
// Narrow adaptation of DiPlay e2fd8eadf5a6b705666f292055e72aa93387533d.
package com.shilapi.xcertplay.airplay

import com.openautolink.carplaylab.media.MediaPolicy
import com.shilapi.xcertplay.transport.BlockingDuplexByteStream
import java.io.Closeable
import java.math.BigInteger
import java.util.concurrent.ConcurrentHashMap

data class AudioStreamId(val type: Int, val audioType: String)
data class MicrophoneStreamId(val session: AirPlaySession, val type: Int, val audioType: String, val generation: Long)
interface MediaSink {
    fun onVideoCodec(type: Int, codec: VideoCodec) {}
    fun onVideoConfig(type: Int, codecData: ByteArray) {}
    fun onVideoFrame(type: Int, naluBytes: ByteArray) {}
    fun setVideoRecoveryHandler(type: Int, handler: () -> Unit) {}
    fun setVideoDiagnosticHandler(type: Int, handler: (String) -> Unit) {}
    fun onScreenStreamActive(type: Int, active: Boolean) {}
    fun supportsAudio(format: AudioFormat): Boolean = format.codec == AudioCodecKind.LPCM
    fun onAudioStarted(id: AudioStreamId, format: AudioFormat, firstSample: Int) {}
    fun onAudioRtp(id: AudioStreamId, format: AudioFormat, rtp: ByteArray, sample: Int) {}
    fun onAudioStopped(id: AudioStreamId) {}
    fun supportsMicrophone(format: AudioFormat): Boolean = false
    fun onMicrophoneStarted(id: MicrophoneStreamId, config: MicrophoneConfig) {}
    fun onMicrophoneStopped(id: MicrophoneStreamId) {}
}

/** H264 display + negotiated platform audio + optional permission-gated compressed uplink. */
class CarPlayMediaEngine(
    private val sink: MediaSink,
    private val microphoneEnabled: Boolean = false,
    private val onDiagnostic: (AirPlaySession, String) -> Unit = { _, _ -> },
) : AirPlayMediaHandler {
    internal fun reportDiagnostic(session: AirPlaySession, message: String) {
        if (owns(session) && !session.isClosed) runCatching { onDiagnostic(session, message) }
    }
    private data class Key(val session: AirPlaySession, val type: Int, val audioType: String = "")
    private data class AudioMeta(val format: AudioFormat, val connectionId: Any?,
        val latencyMs: Int, val microphoneId: MicrophoneStreamId,
        @Volatile var firstSample: Int? = null, @Volatile var originNs: Long? = null)
    private val microphoneGeneration = java.util.concurrent.atomic.AtomicLong()
    private val owner = java.util.concurrent.atomic.AtomicReference<AirPlaySession?>()
    fun owns(session: AirPlaySession): Boolean = owner.get() === session
    private fun claim(session: AirPlaySession): Boolean = owns(session) || owner.compareAndSet(null, session)
    private val streams = ConcurrentHashMap<Key, Closeable>()
    private val audio = ConcurrentHashMap<Key, AudioMeta>()
    private val pending = ConcurrentHashMap<AirPlaySession, AirPlayIapTunnelStream>()
    @Volatile private var tunnelHandler: ((AirPlaySession, BlockingDuplexByteStream) -> Boolean)? = null
    fun setRuntimeTunnelHandler(handler: (AirPlaySession, BlockingDuplexByteStream) -> Boolean) { tunnelHandler = handler }
    override fun setIapTunnelHandler(handler: ((BlockingDuplexByteStream) -> Boolean)?) {
        tunnelHandler = handler?.let { callback -> { _, stream -> callback(stream) } }
    }

    @Synchronized override fun onScreen(session: AirPlaySession, type: Int, stream: Map<String, Any?>): Int? {
        if (type != 110 || session.isClosed || streams.keys.any { it.type == 110 && it.session !== session }) return null
        val key = outputKey(session, stream) ?: return null
        if (!claim(session)) return null
        val id = Key(session, type)
        val screen = ScreenStream(key, onDiagnostic = { reportDiagnostic(session, it) },
            expectedPeer = session.remoteAddress, socketBinding = session.socketBinding)
        sink.setVideoDiagnosticHandler(type) { if (it == "first frame rendered") session.videoFrameRendered() }
        sink.setVideoRecoveryHandler(type) {
            if (streams[id] === screen) session.sendCommand(mapOf("type" to "forceKeyFrame"))
        }
        val port = screen.listen(object : ScreenStream.Listener {
            override fun onCodec(codec: VideoCodec) {
                require(session.acceptsVideoCodec(codec)) { "Video codec not negotiated for this session" }
                sink.onVideoCodec(type, codec)
            }
            override fun onConfig(codecData: ByteArray) = sink.onVideoConfig(type, codecData)
            override fun onFrame(naluBytes: ByteArray) = sink.onVideoFrame(type, naluBytes)
            override fun onClosed(cause: Throwable?) {
                session.logDebug("BENCH_DIAG screenClosedType=${cause?.javaClass?.simpleName ?: "EOF"}")
                if (streams.remove(id, screen)) {
                    sink.onScreenStreamActive(type, false)
                    session.close()
                }
            }
        })
        streams.put(id, screen)?.close()
        sink.onScreenStreamActive(type, true)
        return port
    }

    @Synchronized override fun onAudio(session: AirPlaySession, type: Int, stream: Map<String, Any?>): Map<String, Any?>? {
        val bits = (stream["audioFormat"] as? Number)?.toLong() ?: return null
        if (!MediaPolicy.supportsAudio(bits, type) || session.isClosed) return null
        val audioType = (stream["audioType"] as? String)?.lowercase(java.util.Locale.ROOT) ?: "default"
        if (audioType !in setOf("compatibility", "default", "alert", "media", "telephony", "speechrecognition")) return null
        val negotiated = com.shilapi.xcertplay.media.AudioOutputSupport.negotiate(bits, type, audioType) ?: return null
        val format = com.shilapi.xcertplay.media.AudioOutputSupport.withSamplesPerFrame(negotiated, stream["spf"]) ?: return null
        if (!sink.supportsAudio(format)) return null
        val key = outputKey(session, stream) ?: return null
        if (!claim(session)) return null
        val id = Key(session, type, audioType)
        val streamId = AudioStreamId(type, audioType)
        streams.remove(id)?.close()
        audio.remove(id)?.let { sink.onMicrophoneStopped(it.microphoneId) }
        sink.onAudioStopped(streamId)
        val microphoneId = MicrophoneStreamId(session,type,audioType,microphoneGeneration.incrementAndGet())
        if (streams.keys.count { it.session === session && it.type in setOf(100, 101, 102) } >= 8) return null
        val meta = AudioMeta(format, stream["streamConnectionID"],
            ((stream["audioLatencyMs"] as? Number)?.toInt() ?: 0).coerceIn(0, 2000), microphoneId)
        val receiver = AudioStream(key, type, onDiagnostic = { reportDiagnostic(session, it) },
            expectedPeer = session.remoteAddress, socketBinding = session.socketBinding)
        val microphone = microphoneConfig(session,type,stream,format)
        val (dataPort, controlPort) = receiver.listen(object : AudioStream.Listener {
            override fun onStarted(firstSample: Int) {
                synchronized(this@CarPlayMediaEngine) {
                    if (streams[id] !== receiver || session.isClosed) return
                    meta.firstSample = firstSample; meta.originNs = System.nanoTime()
                    sink.onAudioStarted(streamId, format, firstSample)
                    microphone?.let { sink.onMicrophoneStarted(microphoneId,it) }
                }
            }
            override fun onRtp(rtp: ByteArray, sample: Int) {
                synchronized(this@CarPlayMediaEngine) {
                    if (streams[id] === receiver && !session.isClosed) sink.onAudioRtp(streamId,format,rtp,sample)
                }
            }
        })
        streams[id] = receiver; audio[id] = meta
        return linkedMapOf("type" to type, "dataPort" to dataPort, "controlPort" to controlPort,
            "streamConnectionID" to unsignedPlistInteger(meta.connectionId))
    }

    @Synchronized override fun onDataStream(session: AirPlaySession, stream: Map<String, Any?>): Map<String, Any?>? {
        if ((stream["clientTypeUUID"] as? String)?.uppercase(java.util.Locale.ROOT) != IAP_UUID) return null
        if (session.isClosed || tunnelHandler == null) return null
        val shared = session.sharedSecret ?: return null
        val seed = unsignedPlistDecimal(stream["seed"]) ?: return null
        if (!claim(session)) return null
        val key = AirPlayCrypto.hkdfSha512(shared, "DataStream-Salt$seed".toByteArray(Charsets.US_ASCII),
            OUTPUT_KEY.toByteArray(Charsets.US_ASCII), 32)
        val bridge = AirPlayIapTunnelStream(session, IapTunnel(key, expectedPeer = session.remoteAddress,
            bindAddress = session.localAddress ?: java.net.InetAddress.getByName("::"), socketBinding = session.socketBinding))
        val port = try { bridge.listen() } catch (failure: Exception) { bridge.close(); throw failure }
        pending.put(session, bridge)?.close()
        streams.put(Key(session, 130), bridge)?.close()
        return linkedMapOf<String, Any?>("type" to 130, "streamID" to 1L, "dataPort" to port).apply {
            stream["streamConnectionID"]?.let { this["streamConnectionID"] = unsignedPlistInteger(it) }
        }
    }
    override fun onSetupResponseSent(session: AirPlaySession) {
        val bridge = pending.remove(session) ?: return
        if (tunnelHandler?.invoke(session, bridge) != true) { bridge.close(); session.close() }
    }
    override fun onFeedback(session: AirPlaySession): Map<String, Any?>? {
        val entries = audio.entries.filter { it.key.session === session }.map { (key, meta) ->
            linkedMapOf<String, Any?>("type" to key.type, "sampleRate" to meta.format.rtpClockRate).apply {
                val first = meta.firstSample; val origin = meta.originNs
                if (first != null && origin != null) {
                    val now = System.nanoTime()
                    val elapsed = maxOf(0.0, (now - origin) / 1e9 - meta.latencyMs / 1000.0)
                    this["streamConnectionID"] = unsignedPlistInteger(meta.connectionId)
                    this["timestamp"] = session.syncedNtp(); this["timestampRawNs"] = now
                    this["sampleTime"] = ((first.toLong() and 0xffffffffL) + Math.round(elapsed * meta.format.rtpClockRate)) and 0xffffffffL
                }
            }
        }
        return if (entries.isEmpty()) null else mapOf("streams" to entries)
    }
    @Synchronized override fun onTeardown(session: AirPlaySession, type: Int) {
        if (type == 130) pending.remove(session)
        streams.keys.filter { it.session === session && it.type == type }.forEach { key ->
            streams.remove(key)?.close()
            audio.remove(key)?.let { sink.onMicrophoneStopped(it.microphoneId) }
            if (type in setOf(100, 101, 102)) {
                sink.onAudioStopped(AudioStreamId(type, key.audioType))
            }
        }
        if (type == 110) sink.onScreenStreamActive(type, false)
    }
    @Synchronized override fun onSessionClosed(session: AirPlaySession) {
        pending.remove(session)
        streams.keys.filter { it.session === session }.map { it.type }.distinct().forEach { onTeardown(session, it) }
        owner.compareAndSet(session, null)
    }
    private fun microphoneConfig(session: AirPlaySession, type: Int, stream: Map<String,Any?>,
        format: AudioFormat): MicrophoneConfig? {
        if (!microphoneEnabled || !session.controlAuthenticated || !sink.supportsMicrophone(format)) return null
        val number = stream["dataPort"] as? Number ?: return null
        val port = number.toInt()
        if (number.toDouble() != port.toDouble() ||
            !com.shilapi.xcertplay.media.MicrophoneSupport.admits(format,type,port,stream["spf"])) return null
        // No caller-supplied host; PairVerify's session address is the only admissible destination.
        val host = session.remoteAddress ?: return null
        val shared = session.sharedSecret ?: return null
        val bits = com.shilapi.xcertplay.media.MicrophoneSupport.bits(format) ?: return null
        val key = MicrophonePacketizer.inputKey(shared,stream["streamConnectionID"]) ?: return null
        return MicrophoneConfig(format.audioType,host,port,key,bits,
            checkNotNull(com.shilapi.xcertplay.media.MicrophoneSupport.rtpSamples(bits)),
            if (bits == 0x20000000L) 64000 else 96000, socketBinding = session.socketBinding)
    }
    private fun outputKey(session: AirPlaySession, stream: Map<String, Any?>): ByteArray? {
        val shared = session.sharedSecret ?: return null
        val id = unsignedPlistDecimal(stream["streamConnectionID"]) ?: return null
        return AirPlayCrypto.hkdfSha512(shared, "DataStream-Salt$id".toByteArray(Charsets.US_ASCII),
            OUTPUT_KEY.toByteArray(Charsets.US_ASCII), 32)
    }
    private companion object {
        const val OUTPUT_KEY = "DataStream-Output-Encryption-Key"
        const val IAP_UUID = "E9459FD0-BCAD-4C45-820F-1E72447EF2F2"
    }
}
internal fun unsignedPlistDecimal(value: Any?): String? = when (value) {
    is Long -> java.lang.Long.toUnsignedString(value)
    is Int -> Integer.toUnsignedString(value)
    is BigInteger -> if (value.signum() >= 0 && value.bitLength() <= 64) value.toString() else null
    else -> null
}
internal fun unsignedPlistInteger(value: Any?): Any = when (value) {
    is Long -> if (value < 0) BigInteger(java.lang.Long.toUnsignedString(value)) else value
    is Int -> if (value < 0) BigInteger(Integer.toUnsignedString(value)) else value
    else -> value ?: 0L
}
