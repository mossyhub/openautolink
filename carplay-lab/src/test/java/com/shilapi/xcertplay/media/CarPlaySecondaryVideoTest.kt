// SPDX-License-Identifier: GPL-3.0-only
package com.shilapi.xcertplay.media

import com.shilapi.xcertplay.airplay.*
import java.net.Socket
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32], manifest = Config.NONE)
class CarPlaySecondaryVideoTest {
    private fun session(engine: CarPlayMediaEngine, cluster: Boolean): AirPlaySession {
        val config = AirPlayConfig("test", "synthetic", "synthetic", "1", AirPlayDisplayConfig(1280,720),
            cluster = if (cluster) AirPlayDisplayConfig(1280,720) else null)
        return AirPlaySession(Socket(),config,AirPlayIdentity.generate(),PairingStore(),null,
            object : AirPlaySessionListener {},engine).also { session ->
            val verify = session.javaClass.getDeclaredField("pairVerify").apply { isAccessible = true }.get(session)
            verify.javaClass.getDeclaredField("sharedSecret").apply { isAccessible = true }.set(verify,ByteArray(32))
        }
    }
    private fun screenListeners(engine: CarPlayMediaEngine): List<ScreenStream.Listener> {
        val streams = engine.javaClass.getDeclaredField("streams").apply { isAccessible = true }.get(engine) as Map<*,*>
        return streams.values.filterIsInstance<ScreenStream>().map {
            it.javaClass.getDeclaredField("listener").apply { isAccessible = true }.get(it) as ScreenStream.Listener
        }
    }
    @Test fun predecessorCallbacksCannotReplaceSuccessorOnEitherScreen() {
        for (type in listOf(110,111)) {
            val calls = mutableListOf<String>()
            val engine = CarPlayMediaEngine(object : MediaSink {
                override fun onVideoCodec(type: Int, codec: VideoCodec) { calls.add("codec") }
                override fun onVideoConfig(type: Int, codecData: ByteArray) { calls.add("config") }
                override fun onVideoFrame(type: Int, naluBytes: ByteArray) { calls.add("frame") }
                override fun onScreenStreamActive(type: Int, active: Boolean) { calls.add("active=$active") }
            })
            val current = session(engine,true)
            try {
                assertNotNull(engine.onScreen(current,type,mapOf("streamConnectionID" to 1L)))
                val old = screenListeners(engine).single()
                assertNotNull(engine.onScreen(current,type,mapOf("streamConnectionID" to 2L)))
                calls.clear()
                old.onCodec(VideoCodec.H264); old.onConfig(byteArrayOf(1)); old.onFrame(byteArrayOf(2)); old.onClosed(null)
                assertTrue("predecessor callbacks are not successor work: $calls",calls.isEmpty())
                assertFalse(current.isClosed)
                val fresh = screenListeners(engine).single()
                fresh.onCodec(VideoCodec.H264); fresh.onConfig(byteArrayOf(1)); fresh.onFrame(byteArrayOf(2))
                assertEquals(listOf("codec","config","frame"),calls)
            } finally { engine.onSessionClosed(current); current.close() }
        }
    }
    @Test fun secondaryRecoveryTargetsAltUuidAndRetiredRecoveryIsSilent() {
        val recovery = mutableMapOf<Int,()->Unit>()
        val engine = CarPlayMediaEngine(object : MediaSink {
            override fun setVideoRecoveryHandler(type: Int, handler: () -> Unit) { recovery[type] = handler }
        })
        val current = session(engine,true)
        java.net.ServerSocket(0).use { server -> Socket("127.0.0.1",server.localPort).use { peer ->
            val event = server.accept()
            fun set(name: String, value: Any) { current.javaClass.getDeclaredField(name).apply { isAccessible = true }.set(current,value) }
            set("authenticated",java.util.concurrent.atomic.AtomicBoolean(true))
            set("eventSocket",event); set("eventCipher",ControlCipher(ByteArray(32),ByteArray(32)))
            try {
                assertNotNull(engine.onScreen(current,111,mapOf("streamConnectionID" to 1L)))
                val old = recovery.getValue(111)
                recovery.getValue(111).invoke()
                peer.soTimeout = 3000
                val input = peer.getInputStream(); val header = input.readNBytes(2)
                val size = (header[0].toInt() and 255) or ((header[1].toInt() and 255) shl 8)
                val bytes = ControlCipher(ByteArray(32),ByteArray(32)).decrypt(header + input.readNBytes(size+16)).data
                val split = bytes.toString(Charsets.ISO_8859_1).indexOf("\r\n\r\n")
                val command = BplistCodec.decode(bytes.copyOfRange(split+4,bytes.size)) as Map<*,*>
                assertEquals("forceKeyFrame",command["type"])
                assertEquals(AirPlayInfoPlist.ALT_UUID,(command["params"] as? Map<*,*>)?.get("uuid"))
                assertNotNull(engine.onScreen(current,111,mapOf("streamConnectionID" to 2L)))
                old.invoke()
                peer.soTimeout = 100
                assertThrows(java.net.SocketTimeoutException::class.java) { input.read() }
            } finally { engine.onSessionClosed(current); current.close(); event.close() }
        } }
    }
    @Test fun blockedRecoveryCannotWriteAfterReplacementOnEitherScreen() = blockedRecoveryIsRevoked(replace = true)

    @Test fun blockedRecoveryCannotWriteAfterTeardownOnEitherScreen() = blockedRecoveryIsRevoked(replace = false)

    private fun blockedRecoveryIsRevoked(replace: Boolean) {
        for (type in listOf(110, 111)) {
            val recovery = mutableMapOf<Int, () -> Unit>()
            val engine = CarPlayMediaEngine(object : MediaSink {
                override fun setVideoRecoveryHandler(type: Int, handler: () -> Unit) { recovery[type] = handler }
            })
            val current = session(engine, true)
            java.net.ServerSocket(0).use { server -> Socket("127.0.0.1", server.localPort).use { peer ->
                val event = server.accept()
                fun set(name: String, value: Any) { current.javaClass.getDeclaredField(name).apply { isAccessible = true }.set(current, value) }
                set("eventSocket", event)
                set("eventCipher", ControlCipher(ByteArray(32), ByteArray(32)))
                val eventLock = current.javaClass.getDeclaredField("eventWriteLock").apply { isAccessible = true }.get(current)
                var worker: Thread? = null
                val failure = java.util.concurrent.atomic.AtomicReference<Throwable?>()
                try {
                    assertNotNull(engine.onScreen(current, type, mapOf("streamConnectionID" to 1L)))
                    val old = recovery.getValue(type)
                    synchronized(eventLock) {
                        worker = Thread {
                            try { old() } catch (error: Throwable) { failure.set(error) }
                        }.apply { start() }
                        val deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(3)
                        while (worker!!.state != Thread.State.BLOCKED && System.nanoTime() < deadline) Thread.yield()
                        assertEquals("old recovery must be waiting at the actual event-write monitor", Thread.State.BLOCKED, worker!!.state)
                        if (replace) assertNotNull(engine.onScreen(current, type, mapOf("streamConnectionID" to 2L)))
                        else engine.onTeardown(current, type)
                    }
                    worker!!.join(3000)
                    assertFalse("recovery must finish without deadlock", worker!!.isAlive)
                    failure.get()?.let { throw AssertionError("recovery worker failed", it) }
                    peer.soTimeout = 100
                    val input = peer.getInputStream()
                    assertThrows("retired recovery cannot write to the successor", java.net.SocketTimeoutException::class.java) { input.read() }
                    // A current epoch must still send a real encrypted command, not drop all recovery.
                    if (!replace) assertNotNull(engine.onScreen(current, type, mapOf("streamConnectionID" to 3L)))
                    recovery.getValue(type)()
                    peer.soTimeout = 3000
                    val header = input.readNBytes(2)
                    val size = (header[0].toInt() and 255) or ((header[1].toInt() and 255) shl 8)
                    val bytes = ControlCipher(ByteArray(32), ByteArray(32)).decrypt(header + input.readNBytes(size + 16)).data
                    val split = bytes.toString(Charsets.ISO_8859_1).indexOf("\r\n\r\n")
                    val command = BplistCodec.decode(bytes.copyOfRange(split + 4, bytes.size)) as Map<*, *>
                    assertEquals("forceKeyFrame", command["type"])
                    if (type == 111) assertEquals(AirPlayInfoPlist.ALT_UUID, (command["params"] as Map<*, *>)["uuid"])
                    else assertFalse("main recovery retains its original command shape", command.containsKey("params"))
                } finally {
                    worker?.join(3000)
                    engine.onSessionClosed(current); current.close(); event.close()
                }
            } }
        }
    }

    @Test fun recoveryAdmissionSerializesReplacementWithActualWrite() = admittedRecoveryHoldsReplacement(failWrite = false)

    @Test fun recoveryAdmissionWriteFailureClosesWithoutLockInversion() = admittedRecoveryHoldsReplacement(failWrite = true)

    private fun admittedRecoveryHoldsReplacement(failWrite: Boolean) {
        val recovery = mutableMapOf<Int, () -> Unit>()
        val engine = CarPlayMediaEngine(object : MediaSink {
            override fun setVideoRecoveryHandler(type: Int, handler: () -> Unit) { recovery[type] = handler }
        })
        val current = session(engine, true)
        val entered = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val failure = java.util.concurrent.atomic.AtomicReference<Throwable?>()
        var writer: Thread? = null
        var replacement: Thread? = null
        java.net.ServerSocket(0).use { server -> Socket("127.0.0.1", server.localPort).use { peer ->
            val event = server.accept()
            val blockedSocket = object : Socket() {
                override fun getOutputStream(): java.io.OutputStream = object : java.io.OutputStream() {
                    override fun write(value: Int) = error("bulk event write expected")
                    override fun write(bytes: ByteArray, offset: Int, length: Int) {
                        entered.countDown()
                        check(release.await(3, java.util.concurrent.TimeUnit.SECONDS))
                        if (failWrite) throw java.io.IOException("synthetic event failure")
                        event.getOutputStream().write(bytes, offset, length)
                    }
                }
                override fun close() { event.close() }
            }
            fun set(name: String, value: Any) { current.javaClass.getDeclaredField(name).apply { isAccessible = true }.set(current, value) }
            set("eventSocket", blockedSocket)
            set("eventCipher", ControlCipher(ByteArray(32), ByteArray(32)))
            try {
                assertNotNull(engine.onScreen(current, 111, mapOf("streamConnectionID" to 1L)))
                val old = recovery.getValue(111)
                writer = Thread { try { old() } catch (error: Throwable) { failure.set(error) } }.apply { isDaemon = true; start() }
                assertTrue("actual encrypted write entered", entered.await(3, java.util.concurrent.TimeUnit.SECONDS))
                replacement = Thread {
                    try { engine.onScreen(current, 111, mapOf("streamConnectionID" to 2L)) }
                    catch (error: Throwable) { failure.set(error) }
                }.apply { isDaemon = true; start() }
                fun blockedAtEngineMutation(): Boolean = replacement!!.state == Thread.State.BLOCKED &&
                    replacement!!.stackTrace.firstOrNull()?.let {
                        it.className == CarPlayMediaEngine::class.java.name && it.methodName == "onScreen"
                    } == true
                val deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(3)
                while (!blockedAtEngineMutation() && replacement!!.isAlive && System.nanoTime() < deadline) Thread.yield()
                assertTrue("replacement must wait at the engine mutation, not inside socket/crypto setup", blockedAtEngineMutation())
                release.countDown()
                writer!!.join(3000); replacement!!.join(3000)
                assertFalse("write/retirement must not invert engine and admission locks", writer!!.isAlive || replacement!!.isAlive)
                failure.get()?.let { throw AssertionError("worker failed", it) }
                if (failWrite) assertTrue("write errors preserve session close", current.isClosed)
                else {
                    peer.soTimeout = 3000
                    val input = peer.getInputStream(); val header = input.readNBytes(2)
                    val size = (header[0].toInt() and 255) or ((header[1].toInt() and 255) shl 8)
                    val bytes = ControlCipher(ByteArray(32), ByteArray(32)).decrypt(header + input.readNBytes(size + 16)).data
                    val split = bytes.toString(Charsets.ISO_8859_1).indexOf("\r\n\r\n")
                    val command = BplistCodec.decode(bytes.copyOfRange(split + 4, bytes.size)) as Map<*, *>
                    assertEquals("forceKeyFrame", command["type"])
                    assertEquals(AirPlayInfoPlist.ALT_UUID, (command["params"] as Map<*, *>)["uuid"])
                }
            } finally {
                release.countDown(); writer?.join(3000); replacement?.join(3000)
                event.close()
                // Do not turn a lock-inversion assertion into a permanently hung fixture cleanup.
                assertFalse("workers must retire before engine cleanup", writer?.isAlive == true || replacement?.isAlive == true)
                engine.onSessionClosed(current); current.close()
            }
        } }
    }

    @Test fun foreignTeardownCannotClearCurrentScreenOwner() {
        val changes = mutableListOf<Int>()
        val engine = CarPlayMediaEngine(object : MediaSink {
            override fun onScreenStreamActive(type: Int, active: Boolean) { changes.add(type) }
        })
        val current = session(engine,true); val foreign = session(engine,true)
        try {
            assertNotNull(engine.onScreen(current,110,mapOf("streamConnectionID" to 1L)))
            assertNotNull(engine.onScreen(current,111,mapOf("streamConnectionID" to 2L)))
            changes.clear()
            engine.onTeardown(foreign,110); engine.onTeardown(foreign,111)
            assertTrue("foreign teardown cannot reset a successor's decoder/config",changes.isEmpty())
        } finally { engine.onSessionClosed(current); current.close(); foreign.close() }
    }
    @Test fun secondaryEofRetiresOnlySecondary() {
        val stopped = java.util.concurrent.CountDownLatch(1)
        val engine = CarPlayMediaEngine(object : MediaSink {
            override fun onScreenStreamActive(type: Int, active: Boolean) { if (type == 111 && !active) stopped.countDown() }
        })
        val current = session(engine,true)
        try {
            assertNotNull(engine.onScreen(current,110,mapOf("streamConnectionID" to 1L)))
            val port = checkNotNull(engine.onScreen(current,111,mapOf("streamConnectionID" to 2L)))
            Socket("127.0.0.1",port).close()
            assertTrue(stopped.await(3,java.util.concurrent.TimeUnit.SECONDS))
            // onClosed has completed before querying under the engine's owner monitor.
            synchronized(engine) { assertFalse("ALT EOF is not control/main EOF",current.isClosed) }
        } finally { engine.onSessionClosed(current); current.close() }
    }
    @Test fun configuredSecondaryIsAdmittedIndependentlyAndUnsolicitedSecondaryIsRejected() {
        val engine = CarPlayMediaEngine(object : MediaSink {})
        val absent = session(engine,false)
        try {
            assertNull(engine.onScreen(absent,111,mapOf("streamConnectionID" to 1L)))
            assertFalse(engine.owns(absent))
        } finally { absent.close() }
        val configured = session(engine,true)
        try {
            assertNotNull("qualified configured secondary stream must get its own listener",engine.onScreen(configured,111,mapOf("streamConnectionID" to 2L)))
            assertNotNull(engine.onScreen(configured,110,mapOf("streamConnectionID" to 3L)))
            engine.onTeardown(configured,111)
            assertFalse("secondary teardown cannot close shared session",configured.isClosed)
            assertTrue(engine.owns(configured))
        } finally { engine.onSessionClosed(configured); configured.close() }
    }
}
