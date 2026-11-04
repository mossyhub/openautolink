package com.openautolink.app.secondary

import com.openautolink.app.diagnostics.DiagnosticLog
import java.util.ArrayDeque
import java.util.concurrent.Semaphore
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/** Only immutable captured diagnostic text crosses this boundary, never owners or cleanup work.
 * Producers never acquire a waiting mutex, execute a sink, or wait for worker/GPU/Main.
 * One process-wide FIFO preserves admitted callback/registry order. Overload loses observations,
 * not cleanup: cumulative loss is exposed and carried on every subsequently persisted record.
 */
internal class BoundedPageDiagnosticSink(
    private val capacity: Int = 128,
    private val persist: (String) -> Unit,
) : AutoCloseable {
    private data class Event(val sequence: Long, val message: String)
    private val lock = ReentrantLock()
    private val queue = ArrayDeque<Event>(capacity)
    private val wake = Semaphore(0)
    private val closed = AtomicBoolean(false)
    private val losses = AtomicLong()
    private var sequence = 0L
    val dropped: Long get() = losses.get()
    init {
        require(capacity > 0)
        Thread({
            while (true) {
                wake.acquire()
                if (closed.get()) return@Thread
                val event = lock.withLock { queue.pollFirst() } ?: continue
                // No queue lock is held across DiagnosticLog, file writer, or any observer.
                try { persist("${event.message} diagnosticSeq=${event.sequence} diagnosticDropped=${losses.get()}") }
                catch (_: Exception) { losses.incrementAndGet() }
            }
        }, "OalPageDiagnostic").apply { isDaemon = true; start() }
    }
    fun tryOffer(message: String): Boolean {
        if (closed.get() || message.length > 2048 || !lock.tryLock()) {
            losses.incrementAndGet(); return false
        }
        try {
            if (closed.get() || queue.size >= capacity) { losses.incrementAndGet(); return false }
            queue.addLast(Event(++sequence, message))
        } finally { lock.unlock() }
        wake.release()
        return true
    }
    override fun close() { closed.set(true); wake.release() }
}

/** Narrow shared sink; global DiagnosticLog/FileLogWriter semantics remain unchanged. */
internal object PageReturnDiagnostics {
    private val sink = BoundedPageDiagnosticSink { DiagnosticLog.i("secondary", it) }
    fun tryOffer(message: String): Boolean = sink.tryOffer(message)
    val dropped: Long get() = sink.dropped
}
