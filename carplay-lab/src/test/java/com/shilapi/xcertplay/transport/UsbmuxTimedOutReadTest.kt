// SPDX-License-Identifier: GPL-3.0-only
package com.shilapi.xcertplay.transport

import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbRequest
import java.nio.ByteBuffer
import java.util.concurrent.TimeoutException
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.util.ReflectionHelpers
import org.robolectric.util.ReflectionHelpers.ClassParameter

/** Exercises the real session through Android USB boundary shadows, not a replacement reader. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 32], manifest = Config.NONE,
    shadows = [TimedReadRequestShadow::class, TimedReadConnectionShadow::class])
class UsbmuxTimedOutReadTest {
    @Before fun reset() { TimedReadReplay.reset() }

    @Test fun lateCompletionAfterRejectedCancelPreservesBytesAndNextRead() {
        val first = byteArrayOf(1, 2, 3)
        val second = byteArrayOf(4, 5)
        TimedReadReplay.cancelResult = false
        TimedReadReplay.waits.add { throw TimeoutException() }
        TimedReadReplay.complete(first)
        TimedReadReplay.complete(second)
        pipe().use { session ->
            assertArrayEquals(first, session.read(100))
            assertArrayEquals(second, session.read(100))
        }
        assertEquals(listOf(100L, 1_000L, 100L), TimedReadReplay.timeouts)
        assertEquals(2, TimedReadReplay.requests.size)
        assertNotSame(TimedReadReplay.requests[0], TimedReadReplay.requests[1])
        assertEquals(TimedReadReplay.requests, TimedReadReplay.closedRequests)
    }

    @Test fun closeDuringDrainFencesLateBytesAndQueuedRead() {
        val entered = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val queued = java.util.concurrent.CountDownLatch(1)
        TimedReadReplay.waits.add { throw TimeoutException() }
        TimedReadReplay.waits.add {
            entered.countDown()
            check(release.await(5, java.util.concurrent.TimeUnit.SECONDS))
            TimedReadReplay.buffer!!.put(byteArrayOf(9))
            TimedReadReplay.request
        }
        val workers = java.util.concurrent.Executors.newFixedThreadPool(2)
        val session = pipe()
        try {
            val reading = workers.submit<ByteArray?> { session.read(100) }
            assertTrue(entered.await(5, java.util.concurrent.TimeUnit.SECONDS))
            val next = workers.submit<ByteArray?> { queued.countDown(); session.read(100) }
            assertTrue(queued.await(5, java.util.concurrent.TimeUnit.SECONDS))
            session.close()
            release.countDown()
            for (result in listOf(reading, next)) {
                try {
                    result.get(5, java.util.concurrent.TimeUnit.SECONDS)
                    fail("Closed session must not publish bytes or accept another read")
                } catch (error: java.util.concurrent.ExecutionException) {
                    assertTrue(error.cause is IphoneUsbException.DeviceUnavailable)
                }
            }
            assertEquals(1, TimedReadReplay.requests.size)
            assertEquals(TimedReadReplay.requests, TimedReadReplay.closedRequests)
        } finally {
            release.countDown(); session.close(); workers.shutdownNow()
            assertTrue(workers.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS))
        }
        // A fresh owner can read normally; no old completion is reused.
        TimedReadReplay.complete(byteArrayOf(7))
        pipe().use { assertArrayEquals(byteArrayOf(7), it.read(100)) }
        assertEquals(2, TimedReadReplay.requests.size)
        assertNotSame(TimedReadReplay.requests[0], TimedReadReplay.requests[1])
    }

    @Test fun acceptedCancelStillPreservesCompletedBytes() {
        TimedReadReplay.waits.add { throw TimeoutException() }
        TimedReadReplay.complete(byteArrayOf(6, 8))
        pipe().use { assertArrayEquals(byteArrayOf(6, 8), it.read(100)) }
        assertEquals(listOf(100L, 1_000L), TimedReadReplay.timeouts)
        assertTrue(TimedReadReplay.cancels >= 1)
    }

    @Test fun emptyCompletedCancellationIsTimeoutForEitherCancelResult() {
        for (cancelResult in listOf(true, false)) {
            TimedReadReplay.reset()
            TimedReadReplay.cancelResult = cancelResult
            TimedReadReplay.waits.add { throw TimeoutException() }
            TimedReadReplay.complete(byteArrayOf())
            TimedReadReplay.complete(byteArrayOf(3))
            pipe().use {
                assertNull(it.read(100))
                assertArrayEquals(byteArrayOf(3), it.read(100))
            }
            assertEquals(listOf(100L, 1_000L, 100L), TimedReadReplay.timeouts)
        }
    }

    @Test fun nonCompletingDrainIsTerminalForEitherCancelResult() {
        for (cancelResult in listOf(true, false)) {
            TimedReadReplay.reset()
            TimedReadReplay.cancelResult = cancelResult
            TimedReadReplay.waits.add { throw TimeoutException() }
            TimedReadReplay.waits.add { throw TimeoutException() }
            pipe().use {
                val error = unavailable { it.read(100) }
                assertEquals(if (cancelResult) "Timed out draining cancelled USBMUX read request"
                    else "Android could not cancel timed out USBMUX read request", error.message)
                assertSame(error, unavailable { it.read(100) })
            }
            assertEquals(listOf(100L, 1_000L), TimedReadReplay.timeouts)
            assertEquals(1, TimedReadReplay.requests.size)
        }
    }

    @Test fun wrongOrNullCompletionIsTerminalInNormalWaitAndDrain() {
        for (timedOut in listOf(false, true)) for (wrong in listOf(false, true)) {
            TimedReadReplay.reset()
            if (timedOut) TimedReadReplay.waits.add { throw TimeoutException() }
            TimedReadReplay.waits.add { if (wrong) UsbRequest() else null }
            pipe().use {
                val error = unavailable { it.read(100) }
                assertSame(error, unavailable { it.read(100) })
                assertSame(error, unavailable { it.write(byteArrayOf(1), 100) })
            }
            assertEquals(if (timedOut) listOf(100L, 1_000L) else listOf(100L), TimedReadReplay.timeouts)
            assertEquals(TimedReadReplay.requests, TimedReadReplay.closedRequests)
        }
    }

    @Test fun drainRuntimeFailureIsTerminalNotAnIgnoredTimeout() {
        TimedReadReplay.waits.add { throw TimeoutException() }
        val cause = IllegalStateException("USB boundary error")
        TimedReadReplay.waits.add { throw cause }
        pipe().use {
            val error = unavailable { it.read(100) }
            assertSame(cause, error.cause)
            assertSame(error, unavailable { it.read(100) })
        }
    }

    @Test fun completionUsesActualPositionAtPrimaryAndRememberedFallbackLimits() {
        for (fallback in listOf(false, true)) {
            TimedReadReplay.reset()
            if (fallback) TimedReadReplay.queueRejects = 1
            val size = if (fallback) 16_384 else 65_536
            val bytes = ByteArray(size) { (it % 251).toByte() }
            TimedReadReplay.waits.add { throw TimeoutException() }
            TimedReadReplay.complete(bytes)
            TimedReadReplay.complete(byteArrayOf(1, 2))
            pipe().use {
                assertArrayEquals(bytes, it.read(100))
                assertArrayEquals(byteArrayOf(1, 2), it.read(100))
            }
            assertEquals(if (fallback) listOf(65_536, 16_384, 16_384)
                else listOf(65_536, 65_536), TimedReadReplay.queueSizes)
        }
    }

    @Test fun splitUsbmuxFrameSurvivesLateCompletionExactlyOnce() {
        val payload = byteArrayOf(3, 4, 5, 6)
        val frame = ByteBuffer.allocate(20).putInt(6).putInt(20).putInt(0)
            .putShort(7).putShort(0).put(payload).array()
        TimedReadReplay.complete(frame.copyOfRange(0, 10))
        TimedReadReplay.waits.add { throw TimeoutException() }
        TimedReadReplay.cancelResult = false
        TimedReadReplay.complete(frame.copyOfRange(10, frame.size))
        TimedReadReplay.complete(frame)
        pipe().use { session ->
            val framing = UsbMuxFrameBuffer()
            framing.append(session.read(100)!!)
            assertNull(framing.takeFrame())
            framing.append(session.read(100)!!)
            assertArrayEquals(payload, framing.takeFrame()!!.payload)
            assertNull(framing.takeFrame())
            assertEquals(0, framing.bufferedBytes)
            framing.append(session.read(100)!!)
            assertArrayEquals(payload, framing.takeFrame()!!.payload)
            assertNull(framing.takeFrame())
        }
    }

    @Test fun closeDuringNormalWaitFencesBytes() {
        val session = pipe()
        TimedReadReplay.waits.add {
            session.close()
            TimedReadReplay.buffer!!.put(byteArrayOf(9))
            TimedReadReplay.request
        }
        session.use {
            unavailable { it.read(100) }
            unavailable { it.read(100) }
        }
        assertEquals(1, TimedReadReplay.requests.size)
        assertEquals(1, TimedReadReplay.connectionCloses)
    }

    @Test fun closeDuringQueueFencesCompletionAndFutureReads() {
        val session = pipe()
        TimedReadReplay.onQueue = { session.close() }
        TimedReadReplay.complete(byteArrayOf(9))
        session.use {
            unavailable { it.read(100) }
            unavailable { it.read(100) }
        }
        assertEquals(1, TimedReadReplay.requests.size)
        assertEquals(TimedReadReplay.requests, TimedReadReplay.closedRequests)
    }

    @Test fun closeBeforeQueueAdmissionDoesNotQueue() {
        val session = pipe()
        TimedReadReplay.onInitialize = { session.close() }
        session.use { unavailable { it.read(100) } }
        assertTrue(TimedReadReplay.requests.isEmpty())
        assertTrue(TimedReadReplay.timeouts.isEmpty())
        assertEquals(1, TimedReadReplay.closedRequests.size)
    }

    @Test fun closeDuringEmptyDrainDoesNotPublishOrdinaryTimeout() {
        val session = pipe()
        TimedReadReplay.waits.add { throw TimeoutException() }
        TimedReadReplay.waits.add { session.close(); TimedReadReplay.request }
        session.use { unavailable { it.read(100) } }
        assertEquals(listOf(100L, 1_000L), TimedReadReplay.timeouts)
    }

    private fun unavailable(action: () -> Unit): IphoneUsbException.DeviceUnavailable {
        try { action(); fail("Expected terminal USB failure") }
        catch (error: IphoneUsbException.DeviceUnavailable) { return error }
        throw AssertionError("Expected terminal USB failure")
    }

    private fun pipe() = Iap2UsbSession(connection(), endpoint(0x04), endpoint(0x85))
    private fun connection(): UsbDeviceConnection = ReflectionHelpers.callConstructor(
        UsbDeviceConnection::class.java, ClassParameter.from(UsbDevice::class.java, null))
    private fun endpoint(address: Int): UsbEndpoint = ReflectionHelpers.callConstructor(
        UsbEndpoint::class.java, ClassParameter.from(Int::class.javaPrimitiveType, address),
        ClassParameter.from(Int::class.javaPrimitiveType, 2),
        ClassParameter.from(Int::class.javaPrimitiveType, 512),
        ClassParameter.from(Int::class.javaPrimitiveType, 0))
}

object TimedReadReplay {
    var request: UsbRequest? = null
    var buffer: ByteBuffer? = null
    var cancelResult = true
    val waits = java.util.ArrayDeque<() -> UsbRequest?>()
    val timeouts = mutableListOf<Long>()
    val requests = mutableListOf<UsbRequest>()
    // UsbRequest.finalize() may close an older fixture after the next test resets its replay.
    val initializedRequests = java.util.Collections.synchronizedSet(mutableSetOf<UsbRequest>())
    val closedRequests = mutableListOf<UsbRequest>()
    var onQueue: (() -> Unit)? = null
    var onInitialize: (() -> Unit)? = null
    var cancels = 0
    var queueRejects = 0
    val queueSizes = mutableListOf<Int>()
    var connectionCloses = 0
    fun reset() {
        request = null; buffer = null; cancelResult = true
        waits.clear(); timeouts.clear(); requests.clear(); initializedRequests.clear(); closedRequests.clear()
        onQueue = null; onInitialize = null; cancels = 0
        queueRejects = 0; queueSizes.clear(); connectionCloses = 0
    }
    fun complete(bytes: ByteArray) { waits.add { buffer!!.put(bytes); request } }
}

@Implements(UsbRequest::class)
class TimedReadRequestShadow {
    @org.robolectric.annotation.RealObject private lateinit var real: UsbRequest
    @Implementation fun initialize(connection: UsbDeviceConnection, endpoint: UsbEndpoint): Boolean {
        TimedReadReplay.initializedRequests.add(real)
        TimedReadReplay.onInitialize?.invoke()
        return true
    }
    @Implementation fun queue(buffer: ByteBuffer): Boolean {
        TimedReadReplay.queueSizes.add(buffer.remaining())
        if (TimedReadReplay.queueRejects-- > 0) return false
        TimedReadReplay.request = real
        TimedReadReplay.buffer = buffer
        TimedReadReplay.requests.add(real)
        TimedReadReplay.onQueue?.invoke()
        return true
    }
    @Implementation fun cancel(): Boolean {
        TimedReadReplay.cancels++
        return TimedReadReplay.cancelResult
    }
    @Implementation fun close() {
        if (real in TimedReadReplay.initializedRequests) TimedReadReplay.closedRequests.add(real)
    }
}

@Implements(UsbDeviceConnection::class)
class TimedReadConnectionShadow {
    @Implementation fun requestWait(timeoutMillis: Long): UsbRequest? {
        TimedReadReplay.timeouts.add(timeoutMillis)
        check(TimedReadReplay.waits.isNotEmpty()) { "Unexpected extra wait" }
        return TimedReadReplay.waits.removeFirst().invoke()
    }
    @Implementation fun close() { TimedReadReplay.connectionCloses++ }
}
