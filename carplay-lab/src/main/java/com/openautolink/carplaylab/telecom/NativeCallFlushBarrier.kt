package com.openautolink.carplaylab.telecom

import java.util.concurrent.ExecutionException
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

internal enum class NativeCallFlushOutcome {
    COMPLETE, TIMEOUT, REJECTED, INTERRUPTED, EXECUTION_FAILED, FAILED
}

/** Approximate executor facts at return, not disk IO or persistence acknowledgments. */
internal data class NativeCallFlushSnapshot(
    val outcome: NativeCallFlushOutcome,
    val queueDepth: Int,
    val activeWorkerCount: Int,
    val elapsedMs: Long,
) {
    fun toLogLine(): String = "media stage=native-call-flush outcome=$outcome " +
        "queueDepth=$queueDepth activeWorkerCount=$activeWorkerCount elapsedMs=$elapsedMs"
}

/** Uses the owner's existing bounded FIFO; never cancels timed-out barriers or queues telemetry. */
internal class NativeCallFlushBarrier(
    private val worker: ThreadPoolExecutor,
    private val report: (NativeCallFlushSnapshot) -> Unit,
) {
    fun flush(timeoutMs: Long = 2000): Boolean {
        val started = System.nanoTime()
        val outcome = try {
            worker.submit {}.get(timeoutMs, TimeUnit.MILLISECONDS)
            NativeCallFlushOutcome.COMPLETE
        } catch (_: TimeoutException) {
            NativeCallFlushOutcome.TIMEOUT
        } catch (_: RejectedExecutionException) {
            NativeCallFlushOutcome.REJECTED
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            NativeCallFlushOutcome.INTERRUPTED
        } catch (_: ExecutionException) {
            NativeCallFlushOutcome.EXECUTION_FAILED
        } catch (_: Exception) {
            NativeCallFlushOutcome.FAILED
        }
        val snapshot = NativeCallFlushSnapshot(
            outcome = outcome,
            queueDepth = worker.queue.size.coerceIn(0, 64),
            activeWorkerCount = worker.activeCount.coerceIn(0, 1),
            elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)
                .coerceIn(0L, Int.MAX_VALUE.toLong()),
        )
        // Observer failure cannot rewrite an already-decided barrier result. No Throwable text.
        try { report(snapshot) } catch (_: Exception) { }
        return outcome == NativeCallFlushOutcome.COMPLETE
    }
}
