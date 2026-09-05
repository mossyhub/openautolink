package com.openautolink.companion.bluetooth

import java.io.Closeable
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull

/** Closing must be thread-safe and abort any pending connect, write, flush or read. */
internal interface WppConfigBtTransport : Closeable {
    fun connect()
    val inputStream: InputStream
    val outputStream: OutputStream
}

internal object WppConfigBtExchange {
    const val ATTEMPT_TIMEOUT_MILLIS = 5_000L

    /** Owns [transport], including on cancellation before the I/O worker starts. */
    suspend fun send(
        transport: WppConfigBtTransport,
        payload: ByteArray,
        timeoutMillis: Long = ATTEMPT_TIMEOUT_MILLIS,
    ): Boolean {
        val closed = AtomicBoolean()
        fun closeOnce() {
            if (closed.compareAndSet(false, true)) {
                runCatching { transport.close() }
            }
        }
        try {
            currentCoroutineContext().ensureActive()
            // One deadline covers dispatch, connect, the complete write and ACK read.
            return withTimeoutOrNull(timeoutMillis) {
                coroutineScope {
                    suspendCancellableCoroutine { continuation ->
                        // Runs on cancellation, not after the blocking worker completes.
                        // BluetoothSocket.close() aborts blocking I/O from another thread.
                        continuation.invokeOnCancellation { closeOnce() }
                        launch(Dispatchers.IO) {
                            val result = runCatching {
                                ensureActive()
                                transport.connect()
                                ensureActive()
                                val out = DataOutputStream(transport.outputStream)
                                out.writeInt(payload.size)
                                out.write(payload)
                                out.flush()
                                ensureActive()
                                val ack = DataInputStream(transport.inputStream).readUTF().trim()
                                ack == "OK" || ack == "ACK"
                            }
                            closeOnce()
                            continuation.resumeWith(result)
                        }
                    }
                }
            } ?: false
        } finally {
            // Also handles an expired deadline or cancellation before worker dispatch.
            closeOnce()
        }
    }
}
