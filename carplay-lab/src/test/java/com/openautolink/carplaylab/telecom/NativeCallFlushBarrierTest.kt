package com.openautolink.carplaylab.telecom

import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.FutureTask
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.RunnableFuture
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.*
import org.junit.Test

/** Instance-owned executor fixtures: never replace/reset the process diagnostics singleton. */
class NativeCallFlushBarrierTest {
    private class Worker(private val failBarrier: Boolean = false) : ThreadPoolExecutor(
        1, 1, 0, TimeUnit.MILLISECONDS, ArrayBlockingQueue<Runnable>(64),
        { task -> Thread(task, "flush-contract-test").apply { isDaemon = true } },
        ThreadPoolExecutor.AbortPolicy(),
    ) {
        val submitted = LinkedBlockingQueue<Runnable>()
        override fun execute(command: Runnable) {
            super.execute(command)
            submitted.add(command)
        }
        override fun <T : Any?> newTaskFor(runnable: Runnable, value: T): RunnableFuture<T> =
            if (failBarrier) FutureTask(Callable<T> { throw IllegalStateException(PRIVATE_ERROR) })
            else super.newTaskFor(runnable, value)
    }

    private class Fixture(failBarrier: Boolean = false) : AutoCloseable {
        val worker = Worker(failBarrier)
        val observations = LinkedBlockingQueue<NativeCallFlushSnapshot>()
        val barrier = NativeCallFlushBarrier(worker) { observations.add(it) }
        private val release = CountDownLatch(1)
        fun holdWriter() {
            val entered = CountDownLatch(1)
            worker.execute {
                entered.countDown()
                check(release.await(5, TimeUnit.SECONDS)) { "fixture writer not released" }
            }
            assertTrue("writer did not enter", entered.await(3, TimeUnit.SECONDS))
            assertNotNull(worker.submitted.poll(3, TimeUnit.SECONDS))
        }
        fun releaseWriter() = release.countDown()
        fun observation(outcome: NativeCallFlushOutcome): NativeCallFlushSnapshot {
            val result = observations.poll(3, TimeUnit.SECONDS)
            assertNotNull("no terminal flush observation", result)
            assertEquals(outcome, result!!.outcome)
            assertTrue(result.queueDepth in 0..64)
            assertTrue(result.activeWorkerCount in 0..1)
            assertTrue(result.elapsedMs in 0..Int.MAX_VALUE.toLong())
            assertTrue(result.toLogLine().matches(Regex(
                "media stage=native-call-flush outcome=(COMPLETE|TIMEOUT|REJECTED|INTERRUPTED|EXECUTION_FAILED|FAILED) queueDepth=([0-9]|[1-5][0-9]|6[0-4]) activeWorkerCount=[01] elapsedMs=[0-9]{1,10}",
            )))
            assertFalse(result.toLogLine().contains(PRIVATE_ERROR))
            assertTrue("more than one observation per flush", observations.isEmpty())
            return result
        }
        override fun close() {
            releaseWriter()
            worker.shutdown()
            if (!worker.awaitTermination(3, TimeUnit.SECONDS)) {
                worker.shutdownNow()
                assertTrue("fixture worker leaked", worker.awaitTermination(3, TimeUnit.SECONDS))
            }
        }
    }

    @Test fun completedBarrierObservesPriorWriterCompletion() {
        Fixture().use { f ->
            val completed = AtomicBoolean(false)
            f.worker.execute { completed.set(true) }
            assertTrue(f.barrier.flush())
            assertTrue("barrier bypassed accepted writer", completed.get())
            f.observation(NativeCallFlushOutcome.COMPLETE)
        }
    }

    @Test fun heldWriterProducesTimeoutWithoutClaimingCompletion() {
        Fixture().use { f ->
            f.holdWriter()
            assertFalse(f.barrier.flush(timeoutMs = 1))
            val result = f.observation(NativeCallFlushOutcome.TIMEOUT)
            assertEquals(1, result.queueDepth)
            assertEquals(1, result.activeWorkerCount)
            // Preserve existing semantics: timeout neither runs nor cancels queued writes.
            assertEquals(1, f.worker.queue.size)
            f.releaseWriter()
            assertTrue(f.barrier.flush())
            f.observation(NativeCallFlushOutcome.COMPLETE)
        }
    }

    @Test fun fullBoundedQueueProducesRejectionWithoutGrowingQueue() {
        Fixture().use { f ->
            f.holdWriter()
            val drained = CountDownLatch(64)
            repeat(64) { f.worker.execute { drained.countDown() } }
            assertEquals(64, f.worker.queue.size)
            repeat(3) {
                assertFalse(f.barrier.flush())
                val result = f.observation(NativeCallFlushOutcome.REJECTED)
                assertEquals(64, result.queueDepth)
                assertEquals(1, result.activeWorkerCount)
                assertEquals(64, f.worker.queue.size)
            }
            assertEquals(1, f.worker.largestPoolSize)
            f.releaseWriter()
            assertTrue("accepted queued tasks did not complete", drained.await(3, TimeUnit.SECONDS))
            assertTrue(f.barrier.flush())
            f.observation(NativeCallFlushOutcome.COMPLETE)
        }
    }

    @Test fun repeatedTimeoutsCannotCreateUnboundedBarrierTasks() {
        Fixture().use { f ->
            f.holdWriter()
            repeat(64) {
                assertFalse(f.barrier.flush(timeoutMs = 0))
                f.observation(NativeCallFlushOutcome.TIMEOUT)
            }
            assertEquals(64, f.worker.queue.size)
            assertFalse(f.barrier.flush(timeoutMs = 0))
            f.observation(NativeCallFlushOutcome.REJECTED)
            assertEquals(64, f.worker.queue.size)
        }
    }

    @Test fun interruptedWaitReturnsFalseAndRestoresCallerInterruptStatus() {
        Fixture().use { f ->
            f.holdWriter()
            val result = AtomicReference<Boolean>()
            val interrupted = AtomicBoolean(false)
            val caller = Thread {
                result.set(f.barrier.flush())
                interrupted.set(Thread.currentThread().isInterrupted)
            }.apply { isDaemon = true }
            try {
                caller.start()
                // This publication acknowledges the actual submitted barrier, not a sleep.
                assertNotNull("barrier never submitted", f.worker.submitted.poll(3, TimeUnit.SECONDS))
                assertEquals(1, f.worker.queue.size)
                caller.interrupt()
                caller.join(3000)
                assertFalse("flush caller leaked", caller.isAlive)
                assertEquals(false, result.get())
                assertTrue("interruption was swallowed", interrupted.get())
                val observed = f.observation(NativeCallFlushOutcome.INTERRUPTED)
                assertEquals(1, observed.activeWorkerCount)
                assertEquals(1, observed.queueDepth)
            } finally {
                f.releaseWriter()
                caller.interrupt()
                caller.join(3000)
            }
        }
    }

    @Test fun failedBarrierExecutionIsDistinctAndDoesNotExposeThrowableText() {
        Fixture(failBarrier = true).use { f ->
            assertFalse(f.barrier.flush())
            f.observation(NativeCallFlushOutcome.EXECUTION_FAILED)
        }
    }

    @Test fun shutdownExecutorRejectionDoesNotBecomeSuccess() {
        Fixture().use { f ->
            f.worker.shutdown()
            assertFalse(f.barrier.flush())
            val result = f.observation(NativeCallFlushOutcome.REJECTED)
            assertEquals(0, result.queueDepth)
            assertEquals(0, result.activeWorkerCount)
        }
    }

    @Test fun observerFailureCannotChangeCompletedBarrierBoolean() {
        Fixture().use { f ->
            val barrier = NativeCallFlushBarrier(f.worker) { throw IllegalStateException(PRIVATE_ERROR) }
            assertTrue(barrier.flush())
        }
    }

    @Test fun timedOutInstanceCannotReplaceOrContaminateSuccessorExecutor() {
        Fixture().use { a ->
            a.holdWriter()
            assertFalse(a.barrier.flush(timeoutMs = 0))
            a.observation(NativeCallFlushOutcome.TIMEOUT)
            Fixture().use { b ->
                assertTrue(b.barrier.flush())
                b.observation(NativeCallFlushOutcome.COMPLETE)
                assertEquals(1, a.worker.queue.size)
                a.releaseWriter()
                assertTrue(a.barrier.flush())
                a.observation(NativeCallFlushOutcome.COMPLETE)
                assertTrue(b.barrier.flush())
                b.observation(NativeCallFlushOutcome.COMPLETE)
            }
        }
    }

    companion object {
        private const val PRIVATE_ERROR = "private caller +15551234567 /private/path token=not-for-logs"
    }
}
