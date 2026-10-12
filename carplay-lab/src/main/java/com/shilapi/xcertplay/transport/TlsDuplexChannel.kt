// SPDX-License-Identifier: GPL-3.0-only
// Adapted from shilapi/DiPlay; upstream attribution and license: UPSTREAM-LICENSE.
package com.shilapi.xcertplay.transport

import java.nio.ByteBuffer
import java.security.GeneralSecurityException
import javax.net.ssl.SSLEngine
import javax.net.ssl.SSLEngineResult

/**
 * Blocking plaintext stream carried by one client-mode TLS engine and one owned byte stream.
 *
 * [open] completes the TLS handshake before returning. Calls must run on worker threads. This
 * class does not send Lockdown StartSession/StartService messages and does not persist records.
 */
class TlsDuplexChannel private constructor(
    private val underlying: BlockingDuplexByteStream,
    private val engine: SSLEngine,
) : BlockingDuplexByteStream {
    private val stateLock = Any()
    private val engineLock = Any()
    private val readLock = Any()
    private val writeLock = Any()
    private val encryptedInput = ByteQueue()
    private val plaintextInput = ByteQueue()
    private var closed = false
    private var inboundEnded = false
    private var failure: IphoneUsbException? = null

    override fun send(data: ByteArray) = synchronized(writeLock) {
        checkWritable()
        if (data.isEmpty()) return@synchronized
        try {
            val source = ByteBuffer.wrap(data)
            while (source.hasRemaining()) {
                val outcome = wrap(source)
                sendEncrypted(outcome.bytes)
                if (outcome.result.status == SSLEngineResult.Status.CLOSED) {
                    throw IphoneUsbException.Protocol("TLS outbound stream closed during send")
                }
                if (progressPostHandshake(outcome.result.handshakeStatus)) {
                    throw IphoneUsbException.Protocol("TLS send requires inbound post-handshake processing")
                }
                if (outcome.result.bytesConsumed() == 0 && outcome.bytes.isEmpty()) {
                    throw IphoneUsbException.Protocol("TLS send made no progress")
                }
            }
        } catch (error: Exception) {
            throw fail(error)
        }
    }

    override fun recv(maxBytes: Int, timeoutMillis: Long): ByteArray? = synchronized(readLock) {
        require(maxBytes > 0) { "maxBytes must be positive" }
        require(timeoutMillis > 0) { "timeoutMillis must be positive" }
        try {
            receiveLocked(maxBytes, Deadline(timeoutMillis))
        } catch (error: Exception) {
            throw fail(error)
        }
    }

    override fun close() {
        val shouldClose = synchronized(stateLock) {
            if (closed) false else {
                closed = true
                true
            }
        }
        if (!shouldClose) return
        try {
            synchronized(writeLock) {
                synchronized(engineLock) {
                    if (!engine.isOutboundDone) engine.closeOutbound()
                }
                var steps = 0
                while (steps++ < MAXIMUM_CLOSE_WRAP_STEPS &&
                    !synchronized(engineLock) { engine.isOutboundDone }
                ) {
                    val outcome = wrap(ByteBuffer.allocate(0))
                    sendEncrypted(outcome.bytes)
                    var status = outcome.result.handshakeStatus
                    if (status == SSLEngineResult.HandshakeStatus.NEED_TASK) {
                        status = runDelegatedTasks()
                    }
                    if (outcome.result.status == SSLEngineResult.Status.CLOSED ||
                        status != SSLEngineResult.HandshakeStatus.NEED_WRAP
                    ) {
                        break
                    }
                }
            }
        } catch (_: Exception) {
            // close_notify is best effort; closing the owned transport is the final boundary.
        } finally {
            try {
                underlying.close()
            } catch (_: Exception) {
                // The local state remains closed.
            }
        }
    }

    private fun performHandshake(timeoutMillis: Long) {
        val deadline = Deadline(timeoutMillis)
        var status = synchronized(engineLock) {
            engine.beginHandshake()
            engine.handshakeStatus
        }
        var noProgress = 0
        while (!isHandshakeComplete(status)) {
            deadline.requireRemainingMillis()
            when {
                status == SSLEngineResult.HandshakeStatus.NEED_TASK -> {
                    status = runDelegatedTasks()
                    noProgress = 0
                }
                status == SSLEngineResult.HandshakeStatus.NEED_WRAP -> {
                    val outcome = synchronized(writeLock) {
                        wrap(ByteBuffer.allocate(0)).also { sendEncrypted(it.bytes) }
                    }
                    if (outcome.result.status == SSLEngineResult.Status.CLOSED) {
                        throw IphoneUsbException.Protocol("TLS handshake closed while wrapping")
                    }
                    status = outcome.result.handshakeStatus
                    noProgress = checkProgress(outcome, noProgress)
                }
                isNeedUnwrap(status) -> {
                    val outcome = unwrap()
                    plaintextInput.append(outcome.bytes)
                    when (outcome.result.status) {
                        SSLEngineResult.Status.OK -> {
                            status = outcome.result.handshakeStatus
                            noProgress = checkProgress(outcome, noProgress)
                        }
                        SSLEngineResult.Status.BUFFER_UNDERFLOW -> {
                            val incoming = underlying.recv(RECEIVE_CHUNK_BYTES, deadline.requireRemainingMillis())
                                ?: throw IphoneUsbException.TimedOut("TLS handshake timed out")
                            if (incoming.isEmpty()) {
                                throw IphoneUsbException.Protocol("TLS transport ended during handshake")
                            }
                            encryptedInput.append(incoming)
                            noProgress = 0
                        }
                        SSLEngineResult.Status.CLOSED -> {
                            throw IphoneUsbException.Protocol("TLS peer closed during handshake")
                        }
                        SSLEngineResult.Status.BUFFER_OVERFLOW -> error("unwrap handles overflow internally")
                    }
                }
                else -> throw IphoneUsbException.Protocol("Unsupported TLS handshake state ${status.name}")
            }
        }
        deadline.requireRemainingMillis()
    }

    private fun receiveLocked(maxBytes: Int, deadline: Deadline): ByteArray? {
        plaintextInput.take(maxBytes)?.let { return it }
        synchronized(stateLock) {
            failure?.let { throw it }
            if (inboundEnded) return ByteArray(0)
            if (closed) throw IphoneUsbException.DeviceUnavailable("TLS channel is closed")
        }

        var noProgress = 0
        while (true) {
            if (deadline.remainingMillis() == null) return null
            val outcome = unwrap()
            plaintextInput.append(outcome.bytes)
            when (outcome.result.status) {
                SSLEngineResult.Status.OK -> {
                    noProgress = checkProgress(outcome, noProgress)
                    progressPostHandshake(outcome.result.handshakeStatus)
                }
                SSLEngineResult.Status.BUFFER_UNDERFLOW -> {
                    plaintextInput.take(maxBytes)?.let { return it }
                    val remaining = deadline.remainingMillis() ?: return null
                    val incoming = underlying.recv(RECEIVE_CHUNK_BYTES, remaining) ?: return null
                    if (incoming.isEmpty()) {
                        if (encryptedInput.size != 0) {
                            throw IphoneUsbException.Protocol("TLS transport ended inside a record")
                        }
                        throw IphoneUsbException.Protocol("TLS transport ended without close_notify")
                    }
                    encryptedInput.append(incoming)
                    noProgress = 0
                    continue
                }
                SSLEngineResult.Status.CLOSED -> {
                    plaintextInput.take(maxBytes)?.let {
                        finishInbound()
                        return it
                    }
                    finishInbound()
                    return ByteArray(0)
                }
                SSLEngineResult.Status.BUFFER_OVERFLOW -> error("unwrap handles overflow internally")
            }
            plaintextInput.take(maxBytes)?.let { return it }
        }
    }

    /** Returns true only when further inbound TLS processing is required. */
    private fun progressPostHandshake(initialStatus: SSLEngineResult.HandshakeStatus): Boolean {
        var status = initialStatus
        var steps = 0
        while (true) {
            if (++steps > MAXIMUM_CONTROL_STEPS) {
                throw IphoneUsbException.Protocol("TLS post-handshake processing did not converge")
            }
            when {
                status == SSLEngineResult.HandshakeStatus.NEED_TASK -> status = runDelegatedTasks()
                status == SSLEngineResult.HandshakeStatus.NEED_WRAP -> {
                    val outcome = synchronized(writeLock) {
                        wrap(ByteBuffer.allocate(0)).also { sendEncrypted(it.bytes) }
                    }
                    if (outcome.result.status == SSLEngineResult.Status.CLOSED) {
                        throw IphoneUsbException.Protocol("TLS outbound stream closed during post-handshake processing")
                    }
                    if (outcome.result.bytesConsumed() == 0 && outcome.bytes.isEmpty() &&
                        outcome.result.handshakeStatus == SSLEngineResult.HandshakeStatus.NEED_WRAP
                    ) {
                        throw IphoneUsbException.Protocol("TLS post-handshake wrap made no progress")
                    }
                    status = outcome.result.handshakeStatus
                }
                isNeedUnwrap(status) -> return true
                isHandshakeComplete(status) -> return false
                else -> throw IphoneUsbException.Protocol("Unsupported TLS post-handshake state ${status.name}")
            }
        }
    }

    private fun runDelegatedTasks(): SSLEngineResult.HandshakeStatus = synchronized(engineLock) {
        while (true) {
            val task = engine.delegatedTask ?: break
            task.run()
        }
        engine.handshakeStatus
    }

    private fun wrap(source: ByteBuffer): EngineOutcome {
        var capacity = synchronized(engineLock) { engine.session.packetBufferSize }.coerceAtLeast(1)
        while (true) {
            if (capacity > MAXIMUM_TLS_BUFFER_BYTES) {
                throw IphoneUsbException.Protocol("TLS packet buffer exceeds the defensive limit")
            }
            val output = ByteBuffer.allocate(capacity)
            val result = synchronized(engineLock) { engine.wrap(source, output) }
            if (result.status == SSLEngineResult.Status.BUFFER_OVERFLOW) {
                if (result.bytesConsumed() != 0 || result.bytesProduced() != 0) {
                    throw IphoneUsbException.Protocol("TLS wrap overflow consumed data")
                }
                capacity = grow(capacity)
                continue
            }
            if (result.status == SSLEngineResult.Status.BUFFER_UNDERFLOW) {
                throw IphoneUsbException.Protocol("TLS wrap reported buffer underflow")
            }
            output.flip()
            return EngineOutcome(result, ByteArray(output.remaining()).also(output::get))
        }
    }

    private fun unwrap(): EngineOutcome {
        var capacity = synchronized(engineLock) { engine.session.applicationBufferSize }.coerceAtLeast(1)
        while (true) {
            if (capacity > MAXIMUM_TLS_BUFFER_BYTES) {
                throw IphoneUsbException.Protocol("TLS application buffer exceeds the defensive limit")
            }
            val input = encryptedInput.asByteBuffer()
            val output = ByteBuffer.allocate(capacity)
            val result = synchronized(engineLock) { engine.unwrap(input, output) }
            if (result.status == SSLEngineResult.Status.BUFFER_OVERFLOW) {
                if (result.bytesConsumed() != 0 || result.bytesProduced() != 0) {
                    throw IphoneUsbException.Protocol("TLS unwrap overflow consumed data")
                }
                capacity = grow(capacity)
                continue
            }
            encryptedInput.discard(result.bytesConsumed())
            output.flip()
            return EngineOutcome(result, ByteArray(output.remaining()).also(output::get))
        }
    }

    private fun sendEncrypted(bytes: ByteArray) {
        if (bytes.isNotEmpty()) underlying.send(bytes)
    }

    private fun finishInbound() {
        synchronized(stateLock) { inboundEnded = true }
        close()
    }

    private fun checkWritable() = synchronized(stateLock) {
        failure?.let { throw it }
        if (closed) throw IphoneUsbException.DeviceUnavailable("TLS channel is closed")
    }

    private fun checkProgress(outcome: EngineOutcome, previous: Int): Int {
        if (outcome.result.bytesConsumed() != 0 || outcome.bytes.isNotEmpty()) return 0
        if (previous + 1 >= MAXIMUM_NO_PROGRESS_STEPS) {
            throw IphoneUsbException.Protocol("TLS engine made no progress")
        }
        return previous + 1
    }

    private fun fail(error: Exception): IphoneUsbException {
        val mapped = when (error) {
            is IphoneUsbException -> error
            else -> IphoneUsbException.Protocol("TLS channel failed (${error.javaClass.simpleName})")
        }
        val shouldClose = synchronized(stateLock) {
            if (failure == null) failure = mapped
            if (closed) false else {
                closed = true
                true
            }
        }
        if (shouldClose) {
            try {
                underlying.close()
            } catch (_: Exception) {
                // Preserve the original failure.
            }
        }
        return synchronized(stateLock) { failure ?: mapped }
    }

    private fun grow(capacity: Int): Int {
        if (capacity >= MAXIMUM_TLS_BUFFER_BYTES) return MAXIMUM_TLS_BUFFER_BYTES + 1
        return minOf(MAXIMUM_TLS_BUFFER_BYTES, capacity * 2)
    }

    private data class EngineOutcome(
        val result: SSLEngineResult,
        val bytes: ByteArray,
    )

    private class Deadline(timeoutMillis: Long) {
        private val startNanos = System.nanoTime()
        private val timeoutNanos = minOf(timeoutMillis, Long.MAX_VALUE / NANOS_PER_MILLISECOND) *
            NANOS_PER_MILLISECOND

        fun remainingMillis(): Long? {
            val remaining = timeoutNanos - (System.nanoTime() - startNanos)
            if (remaining <= 0) return null
            return (remaining - 1) / NANOS_PER_MILLISECOND + 1
        }

        fun requireRemainingMillis(): Long = remainingMillis()
            ?: throw IphoneUsbException.TimedOut("TLS handshake timed out")
    }

    private class ByteQueue {
        private var bytes = ByteArray(0)
        private var offset = 0

        val size: Int
            get() = bytes.size - offset

        fun append(value: ByteArray) {
            if (value.isEmpty()) return
            if (size > MAXIMUM_TLS_BUFFER_BYTES - value.size) {
                throw IphoneUsbException.Protocol("TLS buffered data exceeds the defensive limit")
            }
            val combined = ByteArray(size + value.size)
            bytes.copyInto(combined, 0, offset)
            value.copyInto(combined, size)
            bytes = combined
            offset = 0
        }

        fun asByteBuffer(): ByteBuffer = ByteBuffer.wrap(bytes, offset, size).slice()

        fun discard(count: Int) {
            require(count in 0..size) { "Invalid TLS queue discard" }
            offset += count
            if (offset == bytes.size) {
                bytes = ByteArray(0)
                offset = 0
            }
        }

        fun take(maxBytes: Int): ByteArray? {
            if (size == 0) return null
            val count = minOf(maxBytes, size)
            return bytes.copyOfRange(offset, offset + count).also { discard(count) }
        }
    }

    companion object {
        private val ALLOWED_PROTOCOLS = arrayOf("TLSv1.3", "TLSv1.2")
        private const val RECEIVE_CHUNK_BYTES = 16 * 1024
        private const val MAXIMUM_TLS_BUFFER_BYTES = 1024 * 1024
        private const val MAXIMUM_CONTROL_STEPS = 32
        private const val MAXIMUM_NO_PROGRESS_STEPS = 8
        private const val MAXIMUM_CLOSE_WRAP_STEPS = 4
        private const val NANOS_PER_MILLISECOND = 1_000_000L

        /** Creates and fully handshakes a TLS client stream, taking ownership of [underlying]. */
        @JvmStatic
        @Throws(IphoneUsbException::class, GeneralSecurityException::class)
        fun open(
            underlying: BlockingDuplexByteStream,
            pairRecord: LockdownPairRecord,
            handshakeTimeoutMillis: Long = 5_000,
        ): TlsDuplexChannel {
            require(handshakeTimeoutMillis > 0) { "handshakeTimeoutMillis must be positive" }
            try {
                val engine = LockdownTlsEngineFactory.create(pairRecord)
                val supported = engine.supportedProtocols.toSet()
                val enabled = ALLOWED_PROTOCOLS.filter(supported::contains).toTypedArray()
                if (enabled.isEmpty()) {
                    throw IphoneUsbException.Protocol("TLS engine supports neither TLSv1.2 nor TLSv1.3")
                }
                engine.enabledProtocols = enabled
                return TlsDuplexChannel(underlying, engine).also {
                    it.performHandshake(handshakeTimeoutMillis)
                }
            } catch (error: Exception) {
                try {
                    underlying.close()
                } catch (_: Exception) {
                    // Preserve the handshake failure.
                }
                throw error
            }
        }

        private fun isHandshakeComplete(status: SSLEngineResult.HandshakeStatus): Boolean =
            status == SSLEngineResult.HandshakeStatus.FINISHED ||
                status == SSLEngineResult.HandshakeStatus.NOT_HANDSHAKING

        private fun isNeedUnwrap(status: SSLEngineResult.HandshakeStatus): Boolean =
            status == SSLEngineResult.HandshakeStatus.NEED_UNWRAP || status.name == "NEED_UNWRAP_AGAIN"
    }
}
