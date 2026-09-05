package com.openautolink.companion.bluetooth

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

class WppConfigBtExchangeTest {
    @Test fun deadlineClosesBlockedConnect() = assertDeadline(Stage.CONNECT)
    @Test fun deadlineClosesBlockedWrite() = assertDeadline(Stage.WRITE)
    @Test fun deadlineClosesBlockedFlush() = assertDeadline(Stage.FLUSH)
    @Test fun deadlineClosesBlockedAckRead() = assertDeadline(Stage.READ)

    private fun assertDeadline(stage: Stage) = runBlocking {
        val transport = ControlledTransport(blocked = stage)
        val send = async(Dispatchers.Default) {
            WppConfigBtExchange.send(transport, payload, timeoutMillis = 300)
        }
        try {
            assertTrue("$stage entered", transport.entered.await(2, TimeUnit.SECONDS))
            assertTrue("deadline actively closes socket", transport.closed.await(2, TimeUnit.SECONDS))
            assertFalse(withTimeout(2_000) { send.await() })
            assertTrue("blocking I/O exited", transport.exited.await(2, TimeUnit.SECONDS))
            assertEquals(1, transport.closeCount.get())
        } finally {
            transport.release.countDown()
            send.cancel()
        }
    }

    @Test
    fun ackDoesNotGetANewDeadlineAfterSlowConnect() = runBlocking {
        val transport = ControlledTransport(blocked = Stage.READ, connectDelayMillis = 1_000)
        val send = async(Dispatchers.Default) {
            WppConfigBtExchange.send(transport, payload, timeoutMillis = 1_500)
        }
        try {
            assertTrue("ACK read reached after slow connect", transport.entered.await(2, TimeUnit.SECONDS))
            assertTrue("connect must consume the same deadline as ACK read", transport.closed.await(900, TimeUnit.MILLISECONDS))
            assertFalse(withTimeout(2_000) { send.await() })
            assertEquals(1, transport.closeCount.get())
        } finally {
            transport.release.countDown()
            send.cancel()
        }
    }

    @Test fun callerCancellationClosesBlockedConnect() = assertCancellation(Stage.CONNECT)
    @Test fun callerCancellationClosesBlockedWrite() = assertCancellation(Stage.WRITE)
    @Test fun callerCancellationClosesBlockedFlush() = assertCancellation(Stage.FLUSH)
    @Test fun callerCancellationClosesBlockedAckRead() = assertCancellation(Stage.READ)

    private fun assertCancellation(stage: Stage) = runBlocking {
        val transport = ControlledTransport(blocked = stage)
        val cancellation = CancellationException("caller stopped")
        val send = async(Dispatchers.Default) {
            WppConfigBtExchange.send(transport, payload, timeoutMillis = 60_000)
        }
        try {
            assertTrue("$stage entered", transport.entered.await(2, TimeUnit.SECONDS))
            send.cancel(cancellation)
            assertTrue("caller cancellation closes without waiting for deadline", transport.closed.await(1, TimeUnit.SECONDS))
            withTimeout(2_000) { send.join() }
            try {
                send.await()
                fail("cancellation must not become a rejection")
            } catch (actual: CancellationException) {
                assertPropagated(cancellation, actual)
            }
            assertTrue("blocking worker exited", transport.exited.await(1, TimeUnit.SECONDS))
            assertEquals(1, transport.closeCount.get())
        } finally {
            transport.release.countDown()
            send.cancel()
        }
    }

    @Test
    fun alreadyCancelledCallerIsNotConvertedToTimeoutFailure() = runBlocking {
        val transport = ControlledTransport()
        val cancellation = CancellationException("caller cancelled")
        val parent = Job().apply { cancel(cancellation) }
        val observed = CompletableDeferred<Throwable?>()
        CoroutineScope(parent).launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                WppConfigBtExchange.send(transport, payload, timeoutMillis = 0)
                observed.complete(null)
            } catch (failure: Throwable) {
                observed.complete(failure)
            }
        }.join()
        assertSame("caller cancellation must escape, not return false", cancellation, observed.await())
        assertEquals(1, transport.closeCount.get())
        assertEquals(0, transport.connectCount.get())
    }

    @Test
    fun expiredDeadlineClosesWithoutStartingConnect() = runBlocking {
        val transport = ControlledTransport()
        assertFalse(WppConfigBtExchange.send(transport, payload, timeoutMillis = 0))
        assertEquals(1, transport.closeCount.get())
        assertEquals(0, transport.connectCount.get())
    }

    @Test(timeout = 5_000)
    fun successfulAckWritesLengthPrefixedPayloadAndClosesWithoutWaitingForDeadline() = runBlocking {
        val transport = ControlledTransport(ackText = "OK")
        assertTrue(WppConfigBtExchange.send(transport, payload, timeoutMillis = 60_000))
        val written = DataInputStream(ByteArrayInputStream(transport.bytes.toByteArray()))
        assertEquals(payload.size, written.readInt())
        val body = ByteArray(payload.size)
        written.readFully(body)
        assertArrayEquals(payload, body)
        assertEquals(-1, written.read())
        assertEquals(1, transport.flushCount.get())
        assertEquals(1, transport.closeCount.get())
    }

    @Test
    fun alternateAckIsAccepted() = runBlocking {
        val transport = ControlledTransport(ackText = " ACK ")
        assertTrue(WppConfigBtExchange.send(transport, payload))
        assertEquals(1, transport.closeCount.get())
    }

    @Test
    fun rejectionClosesAndReturnsFalse() = runBlocking {
        val transport = ControlledTransport(ackText = "ERR")
        assertFalse(WppConfigBtExchange.send(transport, payload))
        assertEquals(1, transport.closeCount.get())
    }

    @Test
    fun unknownAckClosesAndReturnsFalse() = runBlocking {
        val transport = ControlledTransport(ackText = "unexpected")
        assertFalse(WppConfigBtExchange.send(transport, payload))
        assertEquals(1, transport.closeCount.get())
    }

    @Test fun connectFailureClosesTransport() = assertFailure(Stage.CONNECT)
    @Test fun writeFailureClosesTransport() = assertFailure(Stage.WRITE)
    @Test fun flushFailureClosesTransport() = assertFailure(Stage.FLUSH)
    @Test fun readFailureClosesTransport() = assertFailure(Stage.READ)

    private fun assertFailure(stage: Stage) = runBlocking {
        val failure = IOException("$stage failed")
        val transport = ControlledTransport(failed = stage, failure = failure)
        try {
            WppConfigBtExchange.send(transport, payload)
            fail("I/O failure must propagate to the client")
        } catch (actual: IOException) {
            assertPropagated(failure, actual)
        }
        assertEquals(1, transport.closeCount.get())
    }

    @Test
    fun transportCancellationIsPreserved() = runBlocking {
        val cancellation = CancellationException("transport cancelled")
        val transport = ControlledTransport(failed = Stage.CONNECT, failure = cancellation)
        try {
            WppConfigBtExchange.send(transport, payload)
            fail("transport cancellation must not become a rejection")
        } catch (actual: CancellationException) {
            assertPropagated(cancellation, actual)
        }
        assertEquals(1, transport.closeCount.get())
    }

    @Test
    fun cleanupFailureDoesNotMaskSuccess() = runBlocking {
        val transport = ControlledTransport(closeFails = true)
        assertTrue(WppConfigBtExchange.send(transport, payload))
        assertEquals(1, transport.closeCount.get())
    }

    @Test
    fun nextAttemptWorksAfterTimedOutTransport() = runBlocking {
        val expired = ControlledTransport()
        assertFalse(WppConfigBtExchange.send(expired, payload, timeoutMillis = 0))
        val next = ControlledTransport()
        assertTrue(WppConfigBtExchange.send(next, payload))
        assertEquals(1, expired.closeCount.get())
        assertEquals(1, next.closeCount.get())
    }

    private fun assertPropagated(expected: Throwable, actual: Throwable) {
        // Gradle enables assertions: coroutine stack-trace recovery may copy an
        // exception, retaining the original as its cause. Identity alone is not
        // the propagation contract; type/message and original provenance are.
        assertEquals(expected.javaClass, actual.javaClass)
        assertEquals(expected.message, actual.message)
        assertTrue("original exception must be retained", generateSequence(actual) { it.cause }.any { it === expected })
    }

    private enum class Stage { CONNECT, WRITE, FLUSH, READ }

    /** Unlike a suspending fake, only close/release can unblock this transport. */
    private class ControlledTransport(
        private val blocked: Stage? = null,
        ackText: String = "OK",
        private val failed: Stage? = null,
        private val failure: Throwable = IOException("failed"),
        private val connectDelayMillis: Long = 0,
        private val closeFails: Boolean = false,
    ) : WppConfigBtTransport {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val closed = CountDownLatch(1)
        val exited = CountDownLatch(1)
        val closeCount = AtomicInteger()
        val connectCount = AtomicInteger()
        val flushCount = AtomicInteger()
        val bytes = ByteArrayOutputStream()
        private val ack = ByteArrayInputStream(ByteArrayOutputStream().also {
            DataOutputStream(it).writeUTF(ackText)
        }.toByteArray())

        private fun step(stage: Stage) {
            if (stage == blocked) {
                entered.countDown()
                try {
                    check(release.await(8, TimeUnit.SECONDS)) { "test guard expired" }
                } finally {
                    exited.countDown()
                }
            }
            if (closed.count == 0L) throw IOException("transport closed")
            if (stage == failed) throw failure
        }
        override fun connect() {
            connectCount.incrementAndGet()
            if (connectDelayMillis > 0) Thread.sleep(connectDelayMillis)
            step(Stage.CONNECT)
        }
        override val outputStream = object : OutputStream() {
            override fun write(value: Int) { step(Stage.WRITE); bytes.write(value) }
            override fun flush() { step(Stage.FLUSH); flushCount.incrementAndGet() }
        }
        override val inputStream = object : InputStream() {
            override fun read(): Int { step(Stage.READ); return ack.read() }
        }
        override fun close() {
            closeCount.incrementAndGet()
            closed.countDown()
            release.countDown()
            if (closeFails) throw IOException("close failed")
        }
    }

    private companion object {
        val payload = "{\"ssid\":\"Car\",\"bssid\":\"00:11:22:33:44:55\"}".toByteArray()
    }
}
