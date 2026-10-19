// SPDX-License-Identifier: GPL-3.0-only
// Adapted from DiPlay e2fd8eadf5a6b705666f292055e72aa93387533d; see MEDIA-PROVENANCE.md.
package com.shilapi.xcertplay.airplay

import java.io.Closeable
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

enum class AudioCodecKind { AAC_LC, AAC_ELD, OPUS, LPCM }

data class AudioFormat(
    val codec: AudioCodecKind,
    val sampleRate: Int,
    val channels: Int,
    val payloadType: Int,
    val audioType: String = "media",
    /** CarPlay's negotiated sample clock can differ from Opus decoder PCM rate. */
    val rtpClockRate: Int = sampleRate,
    val samplesPerFrame: Int = when (codec) {
        AudioCodecKind.AAC_LC -> 1024
        AudioCodecKind.AAC_ELD -> 512
        else -> 0 // packet duration is self-described for Opus; PCM has no codec frame
    },
)

/**
 * Binds the RTP data and RTCP control UDP ports for one CarPlay audio stream.
 *
 * Wire layout follows LIVI `livi_audio_stream`: one RTP packet per datagram, a 12-byte header,
 * ciphertext, a 16-byte tag, then an 8-byte little-endian nonce. The header's last eight bytes
 * (timestamp + SSRC) are the AEAD associated data.
 */
class AudioStream(
    private val key: ByteArray,
    private val streamType: Int = -1,
    private val onDiagnostic: (String) -> Unit = {},
    private val expectedPeer: InetAddress? = null,
    private val socketBinding: com.openautolink.carplaylab.network.CarPlaySocketBinding? = null,
    private val format: AudioFormat? = null,
) : Closeable {
    interface Listener {
        fun onStarted(firstSample: Int) {}
        fun onRtp(rtp: ByteArray, sample: Int) {}
        fun onPacket(
            wire: ByteArray,
            rtp: ByteArray?,
            sample: Int?,
            error: Throwable?,
        ) {}
    }

    private val closed = AtomicBoolean(false)
    private val receivedPackets = AtomicInteger()
    private val decryptedPackets = AtomicInteger()
    private val authenticationFailures = AtomicInteger()
    private var dataSocket: DatagramSocket? = null
    private var controlSocket: DatagramSocket? = null
    private var dataThread: Thread? = null
    private var controlThread: Thread? = null
    private var started = false

    fun listen(listener: Listener): Pair<Int, Int> {
        val data = bindAnyPort()
        // Keep short Wi-Fi bursts in the kernel while decrypting or scheduling pauses
        // the receive thread. The platform may cap this request; log the actual size.
        val originalBufferBytes = runCatching { data.receiveBufferSize }.getOrDefault(0)
        if (originalBufferBytes < AUDIO_RECEIVE_BUFFER_BYTES) {
            runCatching { data.receiveBufferSize = AUDIO_RECEIVE_BUFFER_BYTES }
        }
        Unit
        val control = bindAnyPort()
        dataSocket = data
        controlSocket = control
        dataThread = Thread({ runData(data, listener) }, "airplay-audio-rx").apply {
            isDaemon = true
            start()
        }
        controlThread = Thread({ runControl(control) }, "airplay-rtcp-rx").apply {
            isDaemon = true
            start()
        }
        return data.localPort to control.localPort
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        dataSocket?.close()
        controlSocket?.close()
        dataThread?.interrupt()
        controlThread?.interrupt()
    }

    private fun runData(socket: DatagramSocket, listener: Listener) {
        val stats = StreamReceiveStats("audio type=$streamType", onDiagnostic)
        val buffer = ByteArray(DATAGRAM_BYTES)
        try {
            while (!closed.get()) {
                val packet = DatagramPacket(buffer, buffer.size)
                try {
                    stats.reading()
                    socket.receive(packet)
                } catch (_: Exception) {
                    if (closed.get()) return else continue
                }
                if (socketBinding?.let { !it.allowsPeer(packet.address) } ?: (expectedPeer != null && packet.address != expectedPeer)) continue
                stats.received(
                    size = packet.length,
                    sequence = if (packet.length >= RTP_HEADER_LEN) {
                        ((buffer[2].toInt() and 0xff) shl 8) or (buffer[3].toInt() and 0xff)
                    } else null,
                    timestamp = if (packet.length >= RTP_HEADER_LEN) readU32Be(buffer, 4) else null,
                )
                val wire = packet.data.copyOf(packet.length)
                val packetNumber = receivedPackets.incrementAndGet()
                if (wire.size < RTP_HEADER_LEN + TAIL_LEN) {
                    if (packetNumber == 1) {
                        Unit
                    }
                    listener.onPacket(
                        wire,
                        null,
                        null,
                        IOException("audio packet shorter than RTP header plus tail"),
                    )
                    stats.processed()
                    continue
                }

                val aad = wire.copyOfRange(4, RTP_HEADER_LEN)
                val sealedEnd = wire.size - NONCE_LEN
                val sealed = wire.copyOfRange(RTP_HEADER_LEN, sealedEnd)
                val shortNonce = wire.copyOfRange(sealedEnd, wire.size)
                val nonce = ByteArray(12).also { shortNonce.copyInto(it, 4) }
                val sample = readU32Be(wire, 4)

                val payload = try {
                    AirPlayCrypto.chachaOpen(key, nonce, sealed, aad)
                } catch (error: Exception) {
                    val failureNumber = authenticationFailures.incrementAndGet()
                    if (failureNumber == 1) {
                        Unit
                    }
                    listener.onPacket(wire, null, sample, error)
                    stats.processed()
                    continue
                }
                val rtp = wire.copyOf(RTP_HEADER_LEN) + payload
                // Authentication alone does not validate clear RTP flags or the codec payload.
                // Use the sink's existing validator before first-packet capture activation.
                if (format != null && com.shilapi.xcertplay.media.AudioOutputSupport.payload(format, rtp) == null) {
                    listener.onPacket(wire, null, sample, IOException("invalid audio RTP or payload"))
                    stats.processed()
                    continue
                }
                val decryptedNumber = decryptedPackets.incrementAndGet()
                if (decryptedNumber <= FIRST_PACKET_LOG_COUNT) {
                    Unit
                } else if (decryptedNumber % PACKET_LOG_INTERVAL == 0) {
                    Unit
                }
                listener.onPacket(wire, rtp, sample, null)
                if (!started) {
                    started = true
                    listener.onStarted(sample)
                }
                listener.onRtp(rtp, sample)
                stats.processed()
            }
        } finally { stats.flush(ended = true) }
    }

    private fun runControl(socket: DatagramSocket) {
        val buffer = ByteArray(DATAGRAM_BYTES)
        while (!closed.get()) {
            try {
                val packet = DatagramPacket(buffer, buffer.size)
                socket.receive(packet)
                if (socketBinding?.let { !it.allowsPeer(packet.address) } ?: (expectedPeer != null && packet.address != expectedPeer)) continue
            } catch (_: Exception) {
                if (closed.get()) return
            }
        }
    }

    private fun bindAnyPort(): DatagramSocket {
        val socket = DatagramSocket(null)
        // Each live data/control socket must exclusively reserve its UDP endpoint.
        socket.reuseAddress = false
        socket.bind(InetSocketAddress(socketBinding?.local ?: InetAddress.getByName("::"), 0))
        return socket
    }

    private fun readU32Be(source: ByteArray, offset: Int): Int =
        ((source[offset].toInt() and 0xff) shl 24) or
            ((source[offset + 1].toInt() and 0xff) shl 16) or
            ((source[offset + 2].toInt() and 0xff) shl 8) or
            (source[offset + 3].toInt() and 0xff)

    private fun ByteArray.toHexString(): String =
        joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }

    private companion object {
        const val TAG = "xcertplay-usb"
        const val DATAGRAM_BYTES = 4_096
        const val AUDIO_RECEIVE_BUFFER_BYTES = 512 * 1024
        const val RTP_HEADER_LEN = 12
        const val TAG_LEN = 16
        const val NONCE_LEN = 8
        const val TAIL_LEN = TAG_LEN + NONCE_LEN
        const val FIRST_PACKET_LOG_COUNT = 3
        const val PACKET_LOG_INTERVAL = 100
    }
}

/** Maps the phone's negotiated audioFormat bits to a decode/render format. */
object AudioStreamCodec {
    fun fromFormatBits(bits: Long, payloadType: Int, audioType: String = "media"): AudioFormat =
        requireNotNull(com.shilapi.xcertplay.media.AudioOutputSupport.format(bits, payloadType, audioType)) {
            "Unsupported exact audio format"
        }
}
