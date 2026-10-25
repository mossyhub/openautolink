package com.shilapi.xcertplay.transport

import java.io.Closeable

/**
 * A blocking, ordered, full-duplex byte stream.
 *
 * [recv] returns `null` only when its timeout expires and an empty array after the peer has
 * ended its output. Implementations may split or coalesce writes arbitrarily. Calls belong on a
 * worker thread, never Android's main thread.
 */
interface BlockingDuplexByteStream : Closeable {
    fun send(data: ByteArray)

    /** Recheck command authority at the implementation's last nonblocking write boundary.
     * Wrappers with blocking write-monitor acquisition must override this overload. */
    fun send(data: ByteArray, current: () -> Boolean) {
        if (!current()) throw java.io.IOException("iAP2 command authority revoked before write")
        send(data)
    }

    fun recv(maxBytes: Int, timeoutMillis: Long): ByteArray?

    override fun close()
}
