package com.openautolink.app.transport.aasdk

import java.io.InputStream
import java.io.OutputStream

/**
 * Bidirectional byte pipe that the native aasdk transport reads/writes through.
 *
 * Backed by TCP streams to the companion app over the shared WiFi. The
 * native read thread calls [readBytes]
 * which blocks on the InputStream until data arrives. The native send strand
 * calls [writeBytes] to push data to the OutputStream.
 *
 * Thread safety: readBytes is called from the native read thread.
 * writeBytes is called from the native io_service strand. Both are safe
 * because they operate on independent streams.
 */
class AasdkTransportPipe(
    private val inputStream: InputStream,
    private val outputStream: OutputStream
) {
    /**
     * Read up to [maxSize] bytes from the TCP input stream.
     * Called from native read thread — blocks until data is available.
     * Returns null if the stream is closed.
     */
    fun readBytes(maxSize: Int): ByteArray? {
        val buffer = ByteArray(maxSize)
        val bytesRead = try {
            inputStream.read(buffer, 0, maxSize)
        } catch (e: Exception) {
            return null
        }
        if (bytesRead <= 0) return null
        return if (bytesRead == maxSize) buffer else buffer.copyOf(bytesRead)
    }

    /**
     * Write bytes to the TCP output stream.
     * Called from the native io_service send strand.
     */
    fun writeBytes(data: ByteArray) {
        outputStream.write(data)
        outputStream.flush()
    }

    fun close() {
        try { inputStream.close() } catch (_: Exception) {}
        try { outputStream.close() } catch (_: Exception) {}
    }
}
