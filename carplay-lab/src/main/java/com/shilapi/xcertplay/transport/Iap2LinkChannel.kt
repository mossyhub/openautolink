package com.shilapi.xcertplay.transport

import java.io.IOException
import java.util.ArrayDeque
import kotlin.math.min

/**
 * Blocking facade for an iAP2 link over a caller-supplied byte stream.
 *
 * [open] starts one worker.  That worker is the only code that drives [Iap2LinkEngine] or calls
 * [BlockingDuplexByteStream.send]/[BlockingDuplexByteStream.recv].  Callers may concurrently wait
 * for readiness, queue complete session-10 payloads, receive session-10 payloads, or close this
 * channel.  This class owns and closes [underlying].
 *
 * It deliberately does not parse CSM control messages, EA, media, UI, or Lockdown setup. The link
 * worker does handle the bounded request/reply flow for Now Playing artwork file transfers so those
 * replies cannot be delayed behind a blocking control-session consumer.
 */
class Iap2LinkChannel private constructor(
    private val underlying: BlockingDuplexByteStream,
    private val linkConfig: Iap2LinkConfig,
    private val initiateNegotiation: Boolean,
    private val onArtwork: (Iap2ArtworkTransfer) -> Unit,
) : AutoCloseable {
    private data class Command(val sessionId: Int, val payload: ByteArray, val sequence: Long, val current: (() -> Boolean)?)
    private val writeGuards = ArrayList<() -> Boolean>() // link-worker only; retained through engine backpressure

    private val lock = Object()
    private val commands = ArrayDeque<Command>()
    private var commandBytes = 0
    private var queuedSequence = 0L
    private var drainedSequence = 0L // link-worker owned
    private var writtenSequence = 0L
    private val controls = ArrayDeque<ByteArray>()
    private var controlBytes = 0
    private val fileTransfers = Iap2FileTransferReceiver()

    private var ready = false
    private var peerMaxControlPayloadBytes: Int? = null
    private var closing = false
    private var terminated = false
    private var terminalFailure: Throwable? = null
    private var streamCloseStarted = false

    private val worker = Thread(::runPump, "xcertplay-iap2-link").apply {
        isDaemon = true
    }

    /** Waits until session 10 is writable; returns false on timeout or clean close, and throws on failure. */
    fun awaitReady(timeoutMillis: Long): Boolean {
        require(timeoutMillis >= 0) { "timeoutMillis must not be negative" }
        synchronized(lock) {
            waitFor(timeoutMillis) { (ready && !terminated) || terminated }
            throwTerminalFailureLocked()
            return ready && !terminated
        }
    }

    /**
     * The peer's negotiated maximum session-10 payload length, available after [awaitReady]
     * returns true.  It excludes the nine-byte iAP2 header and one-byte payload checksum.
     */
    fun peerMaxControlPayloadBytes(): Int? = synchronized(lock) { peerMaxControlPayloadBytes }

    /**
     * Copies and queues one raw session-10 payload.  Returns false after close/termination or when
     * the bounded command queue is full.  A peer LSP length violation is discovered by the worker
     * when it reaches the engine and terminates the channel.
     */
    fun sendControl(bytes: ByteArray): Boolean {
        require(bytes.size <= Iap2LinkEngine.MAX_PAYLOAD_BYTES) {
            "iAP2 control payload exceeds ${Iap2LinkEngine.MAX_PAYLOAD_BYTES} bytes"
        }
        val copy = bytes.copyOf()
        synchronized(lock) {
            if (terminated || closing) return false
            return enqueueCommandLocked(Iap2LinkEngine.CONTROL_SESSION_ID, copy)
        }
    }

    /**
     * Like [sendControl], but waits for command-queue capacity.  CSM uses this to queue every
     * fragment of one frame without converting link backpressure into a partial CSM write.
     */
    internal fun sendControlAwaitCapacity(bytes: ByteArray, timeoutMillis: Long): Boolean {
        require(bytes.size <= Iap2LinkEngine.MAX_PAYLOAD_BYTES) {
            "iAP2 control payload exceeds ${Iap2LinkEngine.MAX_PAYLOAD_BYTES} bytes"
        }
        require(timeoutMillis >= 0) { "timeoutMillis must not be negative" }
        val copy = bytes.copyOf()
        synchronized(lock) {
            waitFor(timeoutMillis) {
                terminated || closing || hasCommandCapacityLocked(copy.size)
            }
            throwTerminalFailureLocked()
            if (terminated || closing) return false
            return enqueueCommandLocked(Iap2LinkEngine.CONTROL_SESSION_ID, copy)
        }
    }

    /** Zero-ack runtime links only: acknowledge the actual byte-stream write, not queue admission.
     * A timeout terminally closes the link so the abandoned frame cannot later be transmitted. */
    internal fun sendControlAwaitWritten(bytes: ByteArray, timeoutMillis: Long, current: (() -> Boolean)? = null): Boolean {
        check(linkConfig.zeroAcknowledgements) { "Synchronous write receipts require a zero-ack tunnel" }
        require(bytes.size <= Iap2LinkEngine.MAX_PAYLOAD_BYTES && timeoutMillis > 0)
        val started = System.nanoTime()
        fun remaining() = (timeoutMillis - (System.nanoTime() - started) / 1_000_000).coerceAtLeast(0)
        val completed = synchronized(lock) {
            waitFor(remaining()) { terminated || closing || hasCommandCapacityLocked(bytes.size) }
            throwTerminalFailureLocked()
            if (terminated || closing || !enqueueCommandLocked(Iap2LinkEngine.CONTROL_SESSION_ID, bytes.copyOf(), current)) false
            else {
                val ticket = queuedSequence
                waitFor(remaining()) { terminated || closing || writtenSequence >= ticket }
                throwTerminalFailureLocked()
                !terminated && !closing && writtenSequence >= ticket
            }
        }
        if (!completed) close()
        return completed
    }

    /** Returns the next control payload; returns null on timeout or clean close, and throws on failure. */
    fun recvControl(timeoutMillis: Long): ByteArray? {
        require(timeoutMillis >= 0) { "timeoutMillis must not be negative" }
        synchronized(lock) {
            waitFor(timeoutMillis) { controls.isNotEmpty() || terminated }
            throwTerminalFailureLocked()
            if (controls.isEmpty()) return null
            val bytes = controls.removeFirst()
            controlBytes -= bytes.size
            return bytes
        }
    }

    /** Retirement outcome independent of a historical transport error rethrown by close(). */
    fun awaitClosed(timeoutMillis: Long): Boolean {
        if (Thread.currentThread() === worker) return false
        worker.join(timeoutMillis.coerceAtLeast(1))
        return synchronized(lock) { closing && !worker.isAlive }
    }
    /**
     * Marks the channel closed, directly closes its owned stream to break a blocking recv, then
     * waits a bounded time for the worker. Stream or worker failures are rethrown, including
     * [Error]s; they are never silently discarded.
     */
    override fun close() {
        synchronized(lock) {
            if (!closing) {
                closing = true
                terminalLocked(null)
            }
        }

        var failure = closeUnderlyingOnce()
        if (failure != null) {
            synchronized(lock) {
                terminalFailure = combineFailures(terminalFailure, failure)
                lock.notifyAll()
            }
        }
        try {
            worker.join(CLOSE_JOIN_MILLIS)
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
            failure = combineFailures(failure, interrupted)
        }
        if (worker.isAlive) {
            failure = combineFailures(failure, IOException("iAP2 link worker did not stop after close"))
        }
        synchronized(lock) {
            failure = combineFailures(failure, terminalFailure)
        }
        if (failure != null) throw failure
    }

    private fun runPump() {
        val engine = Iap2LinkEngine(linkConfig)
        try {
            engine.start(wiredInitiator = initiateNegotiation, nowMillis = nowMillis())
            while (!isClosing()) {
                drainCommands(engine)
                engine.advanceTime(nowMillis())
                flush(engine)
                if (!drainEvents(engine)) return
                if (isClosing()) return

                val next = engine.nextDeadlineMillis()
                val waitMillis = min(
                    RECEIVE_POLL_MILLIS,
                    next?.let { (it - nowMillis()).coerceAtLeast(0) } ?: RECEIVE_POLL_MILLIS,
                ).coerceAtLeast(MINIMUM_RECEIVE_POLL_MILLIS)
                val received = underlying.recv(RECEIVE_CHUNK_BYTES, waitMillis)
                if (isClosing()) return
                when {
                    received == null -> Unit
                    received.isEmpty() -> engine.feedEof()
                    else -> engine.feed(received, nowMillis())
                }
            }
            finish(null)
        } catch (failure: Throwable) {
            if (failure is Error) {
                finish(failure)
                throw failure
            }
            if (isClosing()) finish(null) else finish(failure)
        }
    }

    private fun drainCommands(engine: Iap2LinkEngine) {
        while (engine.writable()) {
            val command = synchronized(lock) {
                if (commands.isEmpty()) {
                    null
                } else {
                    commands.removeFirst().also {
                        commandBytes -= it.payload.size
                        lock.notifyAll()
                    }
                }
            } ?: return
            command.current?.let { guard ->
                if (!guard()) throw IOException("iAP2 command authority revoked")
                writeGuards.add(guard)
            }
            engine.sendSession(command.sessionId, command.payload, nowMillis())
            drainedSequence = command.sequence
            if (isClosing()) return
        }
    }

    private fun flush(engine: Iap2LinkEngine) {
        val bytes = engine.takeOutput()
        if (bytes.isNotEmpty()) {
            // Last boundary before the actual owned stream write: no account Binder calls here.
            // On revocation close this exact link, never replay the abandoned command elsewhere.
            if (writeGuards.any { !it() }) throw IOException("iAP2 command authority revoked before write")
            if (writeGuards.isEmpty()) underlying.send(bytes)
            else underlying.send(bytes) { writeGuards.all { it() } }
            if (linkConfig.zeroAcknowledgements && !engine.hasQueuedPackets) synchronized(lock) {
                writtenSequence = drainedSequence
                writeGuards.clear()
                lock.notifyAll()
            }
        }
    }

    /** Returns false when a link terminal event has been observed. */
    private fun drainEvents(engine: Iap2LinkEngine): Boolean {
        while (true) {
            when (val event = engine.pollEvent() ?: return true) {
                is Iap2LinkEngine.Event.Writable -> synchronized(lock) {
                    if (event.value) {
                        // The engine only emits writable after validating this is positive.
                        peerMaxControlPayloadBytes = engine.peerSynchronization().maxLength - 10
                    }
                    ready = event.value && !terminated
                    lock.notifyAll()
                }

                is Iap2LinkEngine.Event.Control -> {
                    val overflow = synchronized(lock) {
                        if (controls.size >= MAX_PENDING_CONTROLS ||
                            event.bytes.size > MAX_PENDING_CONTROL_BYTES - controlBytes
                        ) {
                            true
                        } else {
                            controls += event.bytes
                            controlBytes += event.bytes.size
                            lock.notifyAll()
                            false
                        }
                    }
                    if (overflow) {
                        finish(IOException("iAP2 received-control queue limit exceeded"))
                        return false
                    }
                }

                is Iap2LinkEngine.Event.Session -> {
                    if (event.sessionId != Iap2LinkEngine.FILE_TRANSFER_SESSION_ID) continue
                    val outcome = fileTransfers.accept(event.bytes)
                    outcome.replies.forEach { reply ->
                        engine.sendSession(Iap2LinkEngine.FILE_TRANSFER_SESSION_ID, reply, nowMillis())
                    }
                    outcome.completed?.let { completed ->
                        try {
                            onArtwork(completed)
                        } catch (_: Exception) {
                            // A UI/consumer callback cannot terminate the control link.
                        }
                    }
                }

                is Iap2LinkEngine.Event.Dead -> {
                    finish(IOException(event.reason ?: "iAP2 link ended"))
                    return false
                }
            }
        }
    }

    private fun finish(failure: Throwable?) {
        synchronized(lock) {
            finishLocked(failure)
        }
        closeUnderlyingOnce()?.let { closeFailure ->
            synchronized(lock) {
                terminalFailure = combineFailures(terminalFailure, closeFailure)
                lock.notifyAll()
            }
        }
    }

    private fun finishLocked(failure: Throwable?) {
        if (!terminated) terminalLocked(failure) else if (failure != null) {
            terminalFailure = combineFailures(terminalFailure, failure)
            lock.notifyAll()
        }
    }

    private fun terminalLocked(failure: Throwable?) {
        terminated = true
        ready = false
        commands.clear()
        commandBytes = 0
        controls.clear()
        controlBytes = 0
        fileTransfers.clear()
        terminalFailure = combineFailures(terminalFailure, failure)
        lock.notifyAll()
    }

    /** lock must already be held. */
    private fun throwTerminalFailureLocked() {
        terminalFailure?.let { throw it }
    }

    private fun isClosing(): Boolean = synchronized(lock) { closing || terminated }

    private fun closeUnderlyingOnce(): Throwable? {
        synchronized(lock) {
            if (streamCloseStarted) return null
            streamCloseStarted = true
        }
        return try {
            underlying.close()
            null
        } catch (failure: Throwable) {
            failure
        }
    }

    /** lock must already be held. */
    private fun hasCommandCapacityLocked(bytes: Int): Boolean =
        commands.size < MAX_PENDING_COMMANDS && bytes <= MAX_PENDING_COMMAND_BYTES - commandBytes

    /** lock must already be held. */
    private fun enqueueCommandLocked(sessionId: Int, bytes: ByteArray, current: (() -> Boolean)? = null): Boolean {
        if (!hasCommandCapacityLocked(bytes.size)) return false
        check(queuedSequence < Long.MAX_VALUE)
        commands += Command(sessionId, bytes, ++queuedSequence, current)
        commandBytes += bytes.size
        lock.notifyAll()
        return true
    }

    /** lock must already be held. */
    private fun waitFor(timeoutMillis: Long, readyPredicate: () -> Boolean): Boolean {
        if (readyPredicate()) return true
        if (timeoutMillis == 0L) return false
        val budgetNanos = timeoutMillis.coerceAtMost(MAX_WAIT_MILLIS) * NANOS_PER_MILLISECOND
        val startedNanos = System.nanoTime()
        while (!readyPredicate()) {
            val elapsedNanos = System.nanoTime() - startedNanos
            if (elapsedNanos >= budgetNanos) return false
            val remainingNanos = budgetNanos - elapsedNanos
            try {
                lock.wait(
                    remainingNanos / NANOS_PER_MILLISECOND,
                    (remainingNanos % NANOS_PER_MILLISECOND).toInt(),
                )
            } catch (interrupted: InterruptedException) {
                Thread.currentThread().interrupt()
                return false
            }
        }
        return true
    }

    private fun combineFailures(first: Throwable?, second: Throwable?): Throwable? {
        if (first == null) return second
        if (second != null && second !== first) first.addSuppressed(second)
        return first
    }

    companion object {
        private const val RECEIVE_CHUNK_BYTES = 8_192
        private const val RECEIVE_POLL_MILLIS = 100L
        private const val MINIMUM_RECEIVE_POLL_MILLIS = 1L
        private const val CLOSE_JOIN_MILLIS = 1_000L
        private const val NANOS_PER_MILLISECOND = 1_000_000L
        private const val MAX_WAIT_MILLIS = Long.MAX_VALUE / NANOS_PER_MILLISECOND
        private const val MAX_PENDING_COMMANDS = 64
        private const val MAX_PENDING_COMMAND_BYTES = 1_048_576
        private const val MAX_PENDING_CONTROLS = 64
        private const val MAX_PENDING_CONTROL_BYTES = 1_048_576

        private val WIRED_LINK_CONFIG = Iap2LinkConfig(
            maxOutgoing = 4,
            controlSessionVersion = 2,
            zeroAcknowledgements = true,
        )
        private val WIRELESS_LINK_CONFIG = Iap2LinkConfig(
            maxOutgoing = 4,
            controlSessionVersion = 2,
        )

        /** Opens and immediately starts a wired iAP2 link, taking ownership of the supplied stream. */
        fun open(
            underlying: BlockingDuplexByteStream,
            onArtwork: (Iap2ArtworkTransfer) -> Unit = {},
        ): Iap2LinkChannel =
            Iap2LinkChannel(underlying, WIRED_LINK_CONFIG, initiateNegotiation = true, onArtwork)
                .also { it.worker.start() }

        /**
         * Opens a wireless RFCOMM link. LIVI sends the iAP2 marker but lets the phone initiate
         * synchronization, and keeps acknowledgements enabled for Bluetooth.
         */
        fun openWireless(
            underlying: BlockingDuplexByteStream,
            onArtwork: (Iap2ArtworkTransfer) -> Unit = {},
        ): Iap2LinkChannel =
            Iap2LinkChannel(underlying, WIRELESS_LINK_CONFIG, initiateNegotiation = false, onArtwork)
                .also { it.worker.start() }

        /**
         * Opens the iAP2 link carried by an AirPlay type-130 tunnel. The accessory initiates
         * synchronization and zero-acknowledgement mode matches the Wi-Fi transport.
         */
        fun openTunnel(
            underlying: BlockingDuplexByteStream,
            onArtwork: (Iap2ArtworkTransfer) -> Unit = {},
        ): Iap2LinkChannel =
            Iap2LinkChannel(underlying, WIRED_LINK_CONFIG, initiateNegotiation = true, onArtwork)
                .also { it.worker.start() }

        private fun nowMillis(): Long = System.nanoTime() / NANOS_PER_MILLISECOND
    }
}
