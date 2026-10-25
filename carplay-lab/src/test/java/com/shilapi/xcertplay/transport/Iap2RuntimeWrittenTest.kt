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
    @Test fun guardedRejectRevalidatesAfterRealLinkQueueImmediatelyBeforeStreamWrite() {
        val send = Iap2Session::class.java.methods.firstOrNull { it.name == "sendAndAwaitWritten" && it.parameterCount == 3 }
        assertNotNull("real socket writer must carry command generation fence", send)
        val listener = ServerSocket(0)
        val left = Stream(Socket("127.0.0.1", listener.localPort))
        val right = Stream(listener.accept()); listener.close()
        val a = Iap2Session.openTunnel(left); val b = Iap2Session.openTunnel(right)
        val current = java.util.concurrent.atomic.AtomicBoolean(true)
        val failure = java.util.concurrent.atomic.AtomicReference<Throwable>()
        var sender: Thread? = null
        try {
            assertTrue(a.awaitReady(3000)); assertTrue(b.awaitReady(3000))
            left.block = true
            a.send(Iap2Frame(0xA101, byteArrayOf(1)))
            assertTrue(left.entered.await(1, TimeUnit.SECONDS))
            val writtenBeforeRelease = left.writes.get()
            sender = Thread {
                try { send!!.invoke(a, Iap2Frame(0x415c, byteArrayOf(0, 5, 0, 0, 0)), 2000L, { current.get() }) }
                catch (t: Throwable) { failure.set(t) }
            }.apply { start() }
            val csm = a.javaClass.getDeclaredField("channel").apply { isAccessible = true }.get(a)
            val link = csm.javaClass.getDeclaredField("link").apply { isAccessible = true }.get(csm)
            val queued = link.javaClass.getDeclaredField("queuedSequence").apply { isAccessible = true }
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1)
            while ((queued.get(link) as Long) < 2 && System.nanoTime() < deadline) Thread.yield()
            assertEquals(2L, queued.get(link))
            current.set(false); left.release.countDown(); sender.join(3000)
            assertFalse(sender.isAlive); assertNotNull("revoked write claimed success", failure.get())
            assertEquals("only the earlier unguarded frame may write", writtenBeforeRelease + 1, left.writes.get())
            assertNull("revoked queued Reject bytes escaped", runCatching { b.recv(200) }.getOrNull())
        } finally { left.release.countDown(); runCatching { a.close() }; runCatching { b.close() }; sender?.join(3000) }
    }
    @Test fun guardIsRecheckedAfterEngineAdmissionBeforeActualSocketWrite() {
        val listener = ServerSocket(0)
        val left = Stream(Socket("127.0.0.1", listener.localPort))
        val right = Stream(listener.accept()); listener.close()
        val a = Iap2Session.openTunnel(left); val b = Iap2Session.openTunnel(right)
        try {
            assertTrue(a.awaitReady(3000)); assertTrue(b.awaitReady(3000))
            a.sendAndAwaitWritten(Iap2Frame(0xA101, byteArrayOf(1)), 1000)
            assertEquals(0xA101, b.recv(1000)?.messageId)
            val before = left.writes.get()
            val checks = java.util.concurrent.atomic.AtomicInteger()
            // First check admits to the engine; the authority generation retires before flush.
            val failure = runCatching {
                a.sendAndAwaitWritten(Iap2Frame(0x415c, byteArrayOf(0, 5, 0, 0, 0)), 1000) { checks.incrementAndGet() == 1 }
            }.exceptionOrNull()
            assertNotNull("last socket boundary ignored revoked authority", failure)
            assertEquals(2, checks.get())
            assertEquals("revoked engine-admitted Reject reached stream", before, left.writes.get())
        } finally { runCatching { a.close() }; runCatching { b.close() } }
    }
    @Test fun actualIdentificationAndMfiLoopsPreserveVehicleRequestsForExactAuthenticatedHandoff() {
        val identify=Iap2IdentificationClient::class.java.methods.firstOrNull {it.name=="identify" && it.parameterCount==3}
        val run=com.shilapi.xcertplay.mfi.Iap2MfiAuthenticationClient::class.java.methods.firstOrNull {it.name=="run" && it.parameterCount==4}
        assertNotNull("identification must expose bounded non-identification intake",identify)
        assertNotNull("MFi must expose bounded non-authentication intake",run)
        val listener=ServerSocket(0)
        val left=Stream(Socket("127.0.0.1",listener.localPort))
        val right=Stream(listener.accept()); listener.close()
        val a=Iap2Session.openTunnel(left); val b=Iap2Session.openTunnel(right)
        val caps=com.openautolink.carplaylab.intake.OalCarDataCapabilities(currentEnergy=true)
        val bootstrap=com.openautolink.carplaylab.intake.OalCarDataBootstrap(caps,{0},{})
        val done=CountDownLatch(1); val failure=java.util.concurrent.atomic.AtomicReference<Throwable>()
        val auth=object : com.shilapi.xcertplay.mfi.MfiAuthenticator {
            override fun protocolMajor()=3
            override fun readCertificate(maximumOutputLength: Int)=byteArrayOf(1) // synthetic OS-independent authenticator, never private assets
            override fun signChallenge(challenge: ByteArray)=byteArrayOf(1)
        }
        val handler: (Iap2Frame)->Boolean=bootstrap::handle
        val worker=Thread {
            try {
                identify!!.invoke(Iap2IdentificationClient(a,bootstrap::identification),
                    Iap2IdentificationConfig("test","test","test","private","1","1",0),3000L,handler)
                run!!.invoke(com.shilapi.xcertplay.mfi.Iap2MfiAuthenticationClient(auth),a,3000L,handler,{_: String ->})
            } catch (error: Throwable) {failure.set(error)} finally {done.countDown()}
        }
        try {
            worker.start(); assertTrue(b.awaitReady(3000))
            b.send(Iap2Frame(0x1D00,byteArrayOf()))
            val identification=com.shilapi.xcertplay.iap2.body.Iap2BodyReader.of(b.recv(1000)!!)
            assertTrue(identification.has(21)); assertFalse(identification.has(22))
            b.send(com.shilapi.xcertplay.iap2.message.Iap2Messages.buildRaw(0xA100) {void(21)})
            b.send(Iap2Frame(0x1D02,byteArrayOf()))
            b.send(Iap2Frame(0xA102,byteArrayOf()))
            b.send(com.shilapi.xcertplay.iap2.message.Iap2Messages.buildRaw(0xA100) {void(21)})
            b.send(Iap2Frame(0xAA05,byteArrayOf()))
            assertTrue(done.await(4,TimeUnit.SECONDS)); assertNull(failure.get())
            val reporter=com.openautolink.carplaylab.intake.OalIap2CarDataReporter(caps,{0},{null},
                {com.openautolink.carplaylab.intake.OalEvSnapshot(currentChargeWh=0)}, {}, {a.sendAndAwaitWritten(it,1000)}, {})
            val target=object : com.openautolink.carplaylab.intake.RuntimeCarDataLink {
                override fun identification(base: Iap2Frame)=bootstrap.identification(base)
                override fun authenticate()=reporter.authenticate()
                override fun handle(frame: Iap2Frame)=reporter.handle(frame)
                override fun tick(encryptedEventReady: Boolean)=reporter.tick()
                override fun pollTimeout(maximumMs: Long)=reporter.pollTimeout(maximumMs)
                override fun revoke()=reporter.close()
                override fun close()=reporter.close()
            }
            val exact=bootstrap.wrap(target)
            assertNull("deferred requests cannot send before authenticated runtime",b.recv(100))
            exact.authenticate()
            val update=b.recv(1000)
            assertEquals(0xA101,update?.messageId)
            assertEquals(0L,com.shilapi.xcertplay.iap2.body.Iap2BodyReader.of(update!!).u32(22))
            exact.close()
        } finally {bootstrap.close(); runCatching {a.close()}; runCatching {b.close()}; worker.join(1000)}
    }
    private class Stream(val socket: Socket) : BlockingDuplexByteStream {
        val writes = java.util.concurrent.atomic.AtomicInteger()
        @Volatile var block = false
        @Volatile var dropWrites = false
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        override fun send(data: ByteArray) {
            if (dropWrites) return
            if (block) { entered.countDown(); check(release.await(5, TimeUnit.SECONDS)) }
            socket.getOutputStream().write(data)
            writes.incrementAndGet()
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
            assertTrue("actual identification wire omits Reject", body.u16List(6).contains(0x415c))
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

    @Test fun wiredEndpointReceiptWaitsForExact4301StreamWrite() {
        val listener = ServerSocket(0)
        val left = Stream(Socket("127.0.0.1", listener.localPort))
        val right = Stream(listener.accept()); listener.close()
        val a = Iap2Session.open(left); val b = Iap2Session.open(right)
        val done = CountDownLatch(1)
        val failure = java.util.concurrent.atomic.AtomicReference<Throwable>()
        var worker: Thread? = null
        try {
            assertTrue(a.awaitReady(3000)); assertTrue(b.awaitReady(3000))
            left.block = true
            worker = Thread {
                try { a.sendAndAwaitWritten(Iap2Frame(0x4301, byteArrayOf(1, 2, 3)), 3000) }
                catch (t: Throwable) { failure.set(t) }
                finally { done.countDown() }
            }.apply { start() }
            assertTrue(left.entered.await(1, TimeUnit.SECONDS))
            assertFalse("queue admission must not receipt endpoint write", done.await(100, TimeUnit.MILLISECONDS))
            assertNull(b.recv(100))
            left.release.countDown()
            assertTrue(done.await(1, TimeUnit.SECONDS)); assertNull(failure.get())
            assertEquals(0x4301, b.recv(1000)?.messageId)
        } finally { left.release.countDown(); runCatching { a.close() }; runCatching { b.close() }; worker?.join(1000) }
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
