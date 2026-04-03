package com.openautolink.app.transport

import android.util.Log
import com.openautolink.app.video.VideoFrame
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import java.io.DataInputStream
import java.net.InetSocketAddress
import java.net.Socket
import kotlin.coroutines.coroutineContext

/**
 * TCP video channel — reads OAL binary video frames from the bridge (port 5290).
 * 16-byte header: payload_length(u32le) + width(u16le) + height(u16le) + pts_ms(u32le) + flags(u16le) + reserved(u16le)
 */
class TcpVideoChannel {

    companion object {
        private const val TAG = "TcpVideoChannel"
        private const val HEADER_SIZE = 16
        private const val MAX_PAYLOAD_SIZE = 4 * 1024 * 1024 // 4MB sanity limit
    }

    private var socket: Socket? = null
    private var input: DataInputStream? = null

    val isConnected: Boolean get() = socket?.isConnected == true && socket?.isClosed == false

    fun connect(host: String, port: Int, timeoutMs: Int = 5000) {
        close()
        val s = Socket()
        s.tcpNoDelay = true
        s.soTimeout = 0 // No read timeout — we block waiting for frames
        s.receiveBufferSize = 256 * 1024 // 256KB receive buffer for bursty keyframes
        s.connect(InetSocketAddress(host, port), timeoutMs)
        socket = s
        input = DataInputStream(s.getInputStream().buffered(65536))
        Log.i(TAG, "Connected to video channel at $host:$port")
    }

    /**
     * Read video frames as a Flow. Blocks on IO waiting for next frame.
     * Emits VideoFrame for each complete frame read from the TCP stream.
     */
    fun receiveFrames(): Flow<VideoFrame> = flow {
        val stream = input ?: throw IllegalStateException("Not connected")
        val headerBuf = ByteArray(HEADER_SIZE)

        while (coroutineContext.isActive) {
            // Read the 16-byte header
            stream.readFully(headerBuf)

            val payloadLength = readU32LE(headerBuf, 0)
            val width = readU16LE(headerBuf, 4)
            val height = readU16LE(headerBuf, 6)
            val ptsMs = readU32LE(headerBuf, 8)
            val flags = readU16LE(headerBuf, 12)
            // reserved at offset 14, ignored

            // Sanity check payload size
            if (payloadLength > MAX_PAYLOAD_SIZE) {
                Log.e(TAG, "Frame payload too large: $payloadLength bytes, dropping connection")
                break
            }

            if (payloadLength == 0L) {
                // Empty payload (e.g., EOS signal)
                emit(VideoFrame(width, height, ptsMs, flags, ByteArray(0)))
                continue
            }

            // Read the codec payload
            val payload = ByteArray(payloadLength.toInt())
            stream.readFully(payload)

            emit(VideoFrame(width, height, ptsMs, flags, payload))
        }
    }.flowOn(Dispatchers.IO)

    fun close() {
        try {
            input?.close()
        } catch (_: Exception) {}
        try {
            socket?.close()
        } catch (_: Exception) {}
        input = null
        socket = null
    }

    private fun readU16LE(buf: ByteArray, offset: Int): Int {
        return (buf[offset].toInt() and 0xFF) or
                ((buf[offset + 1].toInt() and 0xFF) shl 8)
    }

    private fun readU32LE(buf: ByteArray, offset: Int): Long {
        return (buf[offset].toInt() and 0xFF).toLong() or
                ((buf[offset + 1].toInt() and 0xFF).toLong() shl 8) or
                ((buf[offset + 2].toInt() and 0xFF).toLong() shl 16) or
                ((buf[offset + 3].toInt() and 0xFF).toLong() shl 24)
    }
}
