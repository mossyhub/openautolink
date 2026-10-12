package com.shilapi.xcertplay.transport

import com.shilapi.xcertplay.iap2.session.Iap2Session
import com.shilapi.xcertplay.iap2.wire.Iap2Frame
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

class Iap2RuntimeWrittenTest {
    private class Stream(val socket: Socket) : BlockingDuplexByteStream {
        @Volatile var block = false
        @Volatile var dropWrites = false
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        override fun send(data: ByteArray) {
            if (dropWrites) return
            if (block) { entered.countDown(); check(release.await(5, TimeUnit.SECONDS)) }
            socket.getOutputStream().write(data)
        }
        override fun recv(maxBytes: Int, timeoutMillis: Long): ByteArray? {
            socket.soTimeout = timeoutMillis.toInt().coerceAtLeast(1)
            val b = ByteArray(maxBytes)
            return try { val n = socket.getInputStream().read(b); if (n < 0) byteArrayOf() else b.copyOf(n) }
            catch (_: SocketTimeoutException) { null }
        }
        override fun close() { release.countDown(); socket.close() }
    }
    @Test fun realIdentificationExchangeUsesFrozenRuntimeTransformer() {
        val listener = ServerSocket(0)
        val left = Stream(Socket("127.0.0.1", listener.localPort))
        val right = Stream(listener.accept()); listener.close()
        val a = Iap2Session.openTunnel(left)
        val b = Iap2Session.openTunnel(right)
        val done = CountDownLatch(1)
        try {
            val caps = com.openautolink.carplaylab.intake.OalCarDataCapabilities(location = true, currentEnergy = true)
            val worker = Thread {
                Iap2IdentificationClient(a) { base -> com.openautolink.carplaylab.intake.OalCarDataIdentification.apply(base,caps) }
                    .identify(Iap2IdentificationConfig("test","test","test","private","1","1",0), 3000)
                done.countDown()
            }.apply { start() }
            assertTrue(b.awaitReady(3000))
            b.send(Iap2Frame(0x1D00, byteArrayOf()))
            val body = com.shilapi.xcertplay.iap2.body.Iap2BodyReader.of(b.recv(1000)!!)
            assertTrue("actual tunnel 1D01 must be transformed", body.has(22))
            assertTrue(body.has(21))
            b.send(Iap2Frame(0x1D02,byteArrayOf()))
            assertTrue(done.await(1,TimeUnit.SECONDS)); worker.join(1000)
        } finally { runCatching { a.close() }; runCatching { b.close() } }
    }

    @Test fun mixedLegacyQueueCannotAcknowledgeUnwrittenRuntimeFrameInSameFlushBatch() {
        val listener = ServerSocket(0)
        val left = Stream(Socket("127.0.0.1", listener.localPort))
        val right = Stream(listener.accept()); listener.close()
        val a = Iap2Session.openTunnel(left)
        val b = Iap2Session.openWireless(right)
        val done = CountDownLatch(1)
        val failure = java.util.concurrent.atomic.AtomicReference<Throwable>()
        try {
            assertTrue(a.awaitReady(3000)); assertTrue(b.awaitReady(3000))
            right.dropWrites = true
            left.block = true
            a.send(Iap2Frame(0xA101,byteArrayOf(0)))
            assertTrue(left.entered.await(1,TimeUnit.SECONDS))
            for (i in 1..4) a.send(Iap2Frame(0xA101,byteArrayOf(i.toByte())))
            val worker = Thread {
                try { a.sendAndAwaitWritten(Iap2Frame(0xA101,byteArrayOf(99)), 1000) }
                catch (t: Throwable) { failure.set(t) }
                finally { done.countDown() }
            }.apply { start() }
            // Wait for the synchronous command to enter the real bounded command queue.
            val sessionChannel = a.javaClass.getDeclaredField("channel").apply { isAccessible=true }.get(a)
            val link = sessionChannel.javaClass.getDeclaredField("link").apply { isAccessible=true }.get(sessionChannel)
            val queued = link.javaClass.getDeclaredField("queuedSequence").apply { isAccessible=true }
            val limit = System.nanoTime() + TimeUnit.SECONDS.toNanos(1)
            while ((queued.get(link) as Long) < 6 && System.nanoTime() < limit) Thread.yield()
            assertEquals(6L,queued.get(link))
            left.release.countDown()
            assertFalse("a prior flushed packet cannot receipt the engine-queued sixth frame", done.await(200,TimeUnit.MILLISECONDS))
            assertTrue(done.await(2,TimeUnit.SECONDS)); assertNotNull(failure.get())
            worker.join(1000)
        } finally { runCatching { a.close() }; runCatching { b.close() } }
    }

    @Test fun peerAckBackpressureCannotTurnAnEngineQueuedPacketIntoWrittenReceipt() {
        val listener = ServerSocket(0)
        val left = Stream(Socket("127.0.0.1", listener.localPort))
        val right = Stream(listener.accept()); listener.close()
        val a = Iap2Session.openTunnel(left)
        val b = Iap2Session.openWireless(right)
        var failedClosed = false
        try {
            assertTrue(a.awaitReady(3000)); assertTrue(b.awaitReady(3000))
            right.dropWrites = true // Source-contract fault injection: peer stops acknowledging.
            for (i in 1..12) {
                try { a.sendAndAwaitWritten(Iap2Frame(0xA101,byteArrayOf(i.toByte())), 150) }
                catch (_: java.io.IOException) { failedClosed = true; break }
                assertEquals("a write receipt must not acknowledge engine backlog", i, b.recv(250)?.payload?.first()?.toInt())
            }
            assertTrue("bounded backpressure must close rather than queue stale telemetry", failedClosed)
            assertTrue("facade must expose terminal runtime write failure", a.isClosed)
        } finally { runCatching { a.close() }; runCatching { b.close() } }
    }

    @Test fun synchronousRuntimeSendCannotClaimTransmissionBeforeActualStreamWrite() {
        val listener = ServerSocket(0)
        val left = Stream(Socket("127.0.0.1", listener.localPort))
        val right = Stream(listener.accept()); listener.close()
        val a = Iap2Session.openTunnel(left)
        val b = Iap2Session.openTunnel(right)
        val done = CountDownLatch(1)
        try {
            assertTrue(a.awaitReady(3000)); assertTrue(b.awaitReady(3000))
            left.block = true
            val thread = Thread { a.sendAndAwaitWritten(Iap2Frame(0xA101, byteArrayOf()), 3000); done.countDown() }.apply { start() }
            assertTrue(left.entered.await(1, TimeUnit.SECONDS))
            assertFalse("queued is NOT transmitted", done.await(100, TimeUnit.MILLISECONDS))
            left.release.countDown()
            assertTrue(done.await(1, TimeUnit.SECONDS))
            assertEquals(0xA101, b.recv(1000)?.messageId)
            thread.join(1000)
        } finally { runCatching { a.close() }; runCatching { b.close() } }
    }
}
