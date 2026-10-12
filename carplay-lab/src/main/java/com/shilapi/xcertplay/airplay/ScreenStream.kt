// SPDX-License-Identifier: GPL-3.0-only
// Adapted from DiPlay e2fd8eadf5a6b705666f292055e72aa93387533d; see MEDIA-PROVENANCE.md.
package com.shilapi.xcertplay.airplay

import android.util.Log
import java.io.Closeable
import java.io.InputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

enum class VideoCodec { H264, H265 }

/**
 * Receives one CarPlay screen stream on a TCP data port.
 *
 * Each message is a 128-byte AirPlayScreenHeader followed by a body: a clear VideoConfig
 * (avcC/hvcC) or a ChaCha20-Poly1305 sealed VideoFrame. The key is the DataStream output key
 * and the per-frame nonce is an 8-byte little-endian counter.
 */
class ScreenStream(private val key: ByteArray, private val onDiagnostic: (String) -> Unit = {}, private val expectedPeer: InetAddress? = null,
    private val socketBinding: com.openautolink.carplaylab.network.CarPlaySocketBinding? = null) : Closeable {
    interface Listener {
        fun onCodec(codec: VideoCodec) {}
        fun onConfig(codecData: ByteArray) {}
        fun onFrame(naluBytes: ByteArray) {}
        fun onClosed(cause: Throwable?) {}
    }

    private val closed = AtomicBoolean(false)
    private val frameCounter = AtomicLong(0)
    private var nalLengthSize = 4
    private val firstFrameLogged = AtomicBoolean(false)
    private var server: ServerSocket? = null
    private var socket: Socket? = null
    private var thread: Thread? = null
    @Volatile private var listener: Listener = object : Listener {}

    fun listen(listener: Listener): Int {
        this.listener = listener
        val bound = ServerSocket()
        bound.reuseAddress = true
        bound.bind(InetSocketAddress(socketBinding?.local ?: InetAddress.getByName("::"), 0))
        server = bound
        thread = Thread({ accept(bound) }, "airplay-screen").apply { isDaemon = true; start() }
        return bound.localPort
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        safeClose(socket)
        safeClose(server)
        thread?.interrupt()
    }

    private fun accept(bound: ServerSocket) {
        try {
            var accepted = bound.accept()
            while ((socketBinding?.let { !it.allowsPeer(accepted.inetAddress) || !it.allowsLocal(accepted.localAddress) }
                ?: (expectedPeer != null && accepted.inetAddress != expectedPeer)) && !closed.get()) {
                safeClose(accepted); accepted = bound.accept()
            }
            if (closed.get()) { safeClose(accepted); return }
            socket = accepted
            run(accepted)
        } catch (error: Exception) {
            if (!closed.get()) listener.onClosed(error)
        }
    }

    private fun run(sock: Socket) {
        var failure: Throwable? = null
        val stats = StreamReceiveStats("video", onDiagnostic)
        try {
            val input = sock.getInputStream()
            while (!closed.get()) {
                stats.reading()
                val header = readFully(input, HEADER_LEN) ?: break
                val bodySize = readU32Le(header, 0)
                if (bodySize > MAX_BODY) break
                val body = readFully(input, bodySize) ?: break
                stats.received(HEADER_LEN + bodySize)
                onMessage(header, body, stats)
                stats.processed()
            }
        } catch (error: Exception) {
            failure = error
        } finally {
            stats.flush(ended = true)
            if (socket === sock) socket = null
            safeClose(sock)
            if (!closed.get()) listener.onClosed(failure)
        }
    }

    private fun onMessage(header: ByteArray, body: ByteArray, stats: StreamReceiveStats) {
        when (header[OPCODE_OFFSET].toInt() and 0xff) {
            OP_VIDEO_FRAME -> {
                val payload = if (body.size >= ScreenCodec.TAG_SIZE) {
                    val start = System.nanoTime()
                    ScreenCodec.decryptFrame(key, frameCounter.get(), header, body)
                        .also {
                            stats.decrypted(System.nanoTime() - start, body.size)
                            frameCounter.incrementAndGet()
                        }
                } else {
                    body
                }
                if (firstFrameLogged.compareAndSet(false, true)) {
                    Unit
                }
                val frame = ScreenCodec.lengthPrefixedToAnnexB(payload,nalLengthSize)
                require(frame.isNotEmpty()) { "Invalid video NAL framing" }
                listener.onFrame(frame)
            }
            OP_VIDEO_CONFIG -> {
                val (codec, codecData) = ScreenCodec.detectConfig(body)
                nalLengthSize = if (codec == VideoCodec.H264) 1+(codecData[4].toInt() and 3)
                    else requireNotNull(com.shilapi.xcertplay.media.MediaCodecSupport.hevcConfiguration(codecData)).nalLengthSize
                require(nalLengthSize in listOf(1,2,4)) { "Unsupported NAL length field" }
                Unit
                listener.onCodec(codec)
                listener.onConfig(codecData)
            }
        }
    }

    private fun readFully(input: InputStream, length: Int): ByteArray? {
        if (length < 0) return null
        val output = ByteArray(length)
        var offset = 0
        while (offset < length) {
            val read = input.read(output, offset, length - offset)
            if (read < 0) return null
            offset += read
        }
        return output
    }

    private companion object {
        const val TAG = "xcertplay-usb"
        const val HEADER_LEN = 128
        const val OPCODE_OFFSET = 4
        const val OP_VIDEO_FRAME = 0
        const val OP_VIDEO_CONFIG = 1
        const val MAX_BODY = 8 * 1024 * 1024
    }
}

private fun ByteArray.hexPrefix(length: Int): String =
    take(length).joinToString("") { "%02x".format(it.toInt() and 0xff) }

/** Extracts the avcC/hvcC codec-data record from a VideoConfig payload. */
object ScreenCodec {
    fun decryptFrame(key: ByteArray, counter: Long, header: ByteArray, body: ByteArray): ByteArray =
        if (body.size < TAG_SIZE) body
        else AirPlayCrypto.chachaOpen(key, AirPlayCrypto.nonce64(counter), body, header)

    /** Convert the exact length width declared by avcC/hvcC. Invalid framing fails closed. */
    fun lengthPrefixedToAnnexB(payload: ByteArray, lengthSize: Int = 4): ByteArray =
        com.shilapi.xcertplay.media.MediaCodecSupport.toAnnexB(payload,lengthSize,allowAnnexB=false)

    fun detectConfig(payload: ByteArray): Pair<VideoCodec, ByteArray> {
        require(payload.size in 7..65536) { "Invalid video configuration size" }
        // A validated raw record wins over coincidental fourcc bytes in its NAL/SEI payload.
        val (sps, pps) = com.shilapi.xcertplay.media.MediaCodecSupport.avcParameterSets(payload)
        if (sps.isNotEmpty() && pps.isNotEmpty()) return VideoCodec.H264 to payload
        if (com.shilapi.xcertplay.media.MediaCodecSupport.hevcConfiguration(payload) != null)
            return VideoCodec.H265 to payload
        // Sample-entry containers may have leading fields. Honor the enclosing codec box size,
        // not every byte after a coincidental fourcc (including following sibling boxes).
        for (index in 4..payload.size - 4) {
            val fourcc = String(payload, index, 4, Charsets.US_ASCII)
            if (fourcc != "avcC" && fourcc != "hvcC") continue
            val size = readU32Be(payload, index - 4)
            require(size >= 8 && size <= payload.size - (index - 4)) { "Invalid codec box" }
            val data = payload.copyOfRange(index + 4, index - 4 + size)
            return validatedConfig(if (fourcc == "avcC") VideoCodec.H264 else VideoCodec.H265, data)
        }
        return if (looksLikeAvcC(payload)) validatedConfig(VideoCodec.H264, payload)
            else validatedConfig(VideoCodec.H265, payload)
    }

    private fun validatedConfig(codec: VideoCodec, data: ByteArray): Pair<VideoCodec, ByteArray> {
        val valid = if (codec == VideoCodec.H264) {
            val (sps, pps) = com.shilapi.xcertplay.media.MediaCodecSupport.avcParameterSets(data)
            data.firstOrNull() == 1.toByte() && sps.isNotEmpty() && pps.isNotEmpty() &&
                sps[0].toInt() and 31 == 7 && pps[0].toInt() and 31 == 8
        } else com.shilapi.xcertplay.media.MediaCodecSupport.hevcConfiguration(data) != null
        require(valid) { "Invalid video configuration record" }
        return codec to data
    }

    private fun looksLikeAvcC(payload: ByteArray): Boolean {
        if (payload.size < 9) return false
        if ((payload[5].toInt() and 0x1f) < 1) return false
        val spsLength = readU16Be(payload, 6)
        if (8 + spsLength > payload.size) return false
        return (payload[8].toInt() and 0x1f) == 7
    }

    private fun readU16Be(source: ByteArray, offset: Int): Int =
        ((source[offset].toInt() and 0xff) shl 8) or (source[offset + 1].toInt() and 0xff)

    const val TAG_SIZE = 16
}

private fun ByteArray.startsWithStartCode(): Boolean =
    size >= 4 &&
        this[0] == 0.toByte() &&
        this[1] == 0.toByte() &&
        this[2] == 0.toByte() &&
        this[3] == 1.toByte()

private fun readU32Be(source: ByteArray, offset: Int): Int =
    ((source[offset].toInt() and 0xff) shl 24) or
        ((source[offset + 1].toInt() and 0xff) shl 16) or
        ((source[offset + 2].toInt() and 0xff) shl 8) or
        (source[offset + 3].toInt() and 0xff)

private fun readU32Le(source: ByteArray, offset: Int): Int =
    (source[offset].toInt() and 0xff) or
        ((source[offset + 1].toInt() and 0xff) shl 8) or
        ((source[offset + 2].toInt() and 0xff) shl 16) or
        ((source[offset + 3].toInt() and 0xff) shl 24)
