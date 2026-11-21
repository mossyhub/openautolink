// SPDX-License-Identifier: GPL-3.0-only
package com.shilapi.xcertplay.airplay

import com.openautolink.carplaylab.media.CarPlayClusterContent
import com.openautolink.carplaylab.media.CarPlayClusterVideoConfiguration
import com.shilapi.xcertplay.media.CarPlayVideoSelection
import java.io.Closeable
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

/** Existing-588 API only. Real session control loop, PairVerify and encrypted event loopback.
 * The media seam admits a screen without creating a decoder; this is not physical Apple acceptance.
 * Missing feature assertions are accumulated so retirement/absent controls still execute in RED.
 */
class CarPlayClusterContentDiagnostics589Test {
    private val supported = listOf("maps:/car/instrumentcluster/map",
        "maps:/car/instrumentcluster/instructioncard", "maps:/car/instrumentcluster")
    private fun config(content: CarPlayClusterContent? = null): AirPlayConfig {
        val cluster = content?.let { CarPlayClusterVideoConfiguration.create(AirPlayInsets(),
            AirPlayInsets(5,5,50,50), CarPlayVideoSelection(1280,480,30,"fixture.avc"), content=it) }
        return AirPlayConfig("synthetic-content","synthetic-id","synthetic-mac","950.7.1",
            AirPlayDisplayConfig(800,480), cluster=cluster?.display, lpcmOnly=true)
    }
    private fun binary(config: AirPlayConfig): Map<*,*> {
        val bytes = BplistCodec.encode(AirPlayInfoPlist.build(config, emptySet()))
        assertEquals("bplist00", bytes.copyOfRange(0,8).toString(Charsets.US_ASCII))
        return BplistCodec.decode(bytes) as Map<*,*>
    }
    private fun displays(info: Map<*,*>) = (info["displays"] as List<*>).map { it as Map<*,*> }

    @Test fun diagnosticsDescribeActualInfoAndFlushedExactEpochSelectionWithoutRawUrls() {
        for (content in CarPlayClusterContent.entries) for (eventFirst in listOf(false, true)) {
            Fixture(content).use { f ->
                val info = f.info()
                assertEquals(supported, info["altScreenURLs"])
                f.controlFence()
                val owner = f.diagnostics.first { it.startsWith("BENCH_DIAG clusterOffer ") }.substringAfterLast(" owner=")
                assertTrue(owner.matches(Regex("[1-9][0-9]{0,18}")))
                assertTrue(f.diagnostics.contains("BENCH_DIAG clusterOffer content=${content.name} supported=true suggested=true instruments=true uuidMatch=true owner=$owner"))
                if (eventFirst) f.connectEvent()
                val gate = f.holdNextResponse()
                f.sendSetupCluster()
                assertTrue(gate.entered.await(2, TimeUnit.SECONDS))
                try {
                    assertTrue(f.diagnostics.none { it.startsWith("BENCH_DIAG clusterSelection ") })
                    if (eventFirst) assertTrue(f.fenceEvent().isEmpty())
                } finally { gate.release.countDown() }
                f.readSetupCluster()
                if (!eventFirst) f.connectEvent()
                f.controlFence()
                val commands = f.fenceEvent()
                assertEquals(listOf("showUI", "forceKeyFrame"), commands.map { it["type"] })
                val epoch = f.session.clusterStream
                assertTrue(f.diagnostics.contains("BENCH_DIAG clusterSetup epoch=$epoch requestUuid=absent target=alt owner=$owner"))
                val expected = "BENCH_DIAG clusterSelection epoch=$epoch content=${content.name} showWritten=true keyframeWritten=true owner=$owner"
                assertEquals(listOf(expected), f.diagnostics.filter { it.startsWith("BENCH_DIAG clusterSelection ") })
                f.controlFence()
                assertTrue(f.fenceEvent().isEmpty())
                assertEquals(1, f.diagnostics.count { it == expected })
                assertTrue(f.diagnostics.filter { "cluster" in it }.none { "maps:" in it || "?" in it || "phone-accepted" in it })
            }
        }
        Fixture(null).use { f ->
            f.info(); f.controlFence()
            assertTrue(f.diagnostics.any { it.matches(Regex("BENCH_DIAG clusterOffer content=absent supported=false suggested=false instruments=false uuidMatch=false owner=[1-9][0-9]{0,18}")) })
        }
    }

    @Test fun queuedExplicitOldEpochCannotLeakAfterRealTeardownWhileWriterIsHeld() {
        Fixture(CarPlayClusterContent.MAP).use { f ->
            f.connectEvent(); f.setupCluster(); f.controlFence()
            assertEquals(listOf("showUI", "forceKeyFrame"), f.fenceEvent().map { it["type"] })
            val ended = CountDownLatch(1)
            var result: Boolean? = null
            val worker = Thread({ try { result = f.session.setClusterUiShown(true) } finally { ended.countDown() } }, "old-cluster-explicit").apply { isDaemon = true }
            try {
                synchronized(f.writerLock()) {
                    worker.start()
                    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
                    while (worker.state != Thread.State.BLOCKED && System.nanoTime() < deadline) Thread.yield()
                    assertEquals("actual helper is waiting on the held writer", Thread.State.BLOCKED, worker.state)
                    f.teardownCluster()
                    assertEquals(0, f.session.clusterStream)
                }
                assertTrue(ended.await(2, TimeUnit.SECONDS))
                assertEquals(false, result)
                assertTrue(f.fenceEvent().isEmpty())
                f.setupCluster(); f.controlFence()
                assertEquals(listOf("showUI", "forceKeyFrame"), f.fenceEvent().map { it["type"] })
            } finally { worker.join(2000); assertFalse(worker.isAlive) }
        }
    }

    private class WriteGate {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
    }
    private inner class Fixture(private val content: CarPlayClusterContent?) : Closeable {
        private val server = ServerSocket(0,1,InetAddress.getByName("127.0.0.1"))
        private val phone = Socket("127.0.0.1",server.localPort).apply { soTimeout=2000 }
        private val control = server.accept()
        val diagnostics = java.util.concurrent.CopyOnWriteArrayList<String>()
        private val eventConnected = CountDownLatch(1)
        @Volatile private var nextGate: WriteGate? = null
        private val output = object : OutputStream() {
            override fun write(b: Int) = write(byteArrayOf(b.toByte()),0,1)
            override fun write(b: ByteArray, off: Int, len: Int) {
                val gate = nextGate
                if (gate != null) {
                    nextGate = null; gate.entered.countDown()
                    check(gate.release.await(2,TimeUnit.SECONDS)) { "fixture response hold expired" }
                }
                control.getOutputStream().write(b,off,len)
            }
            override fun flush() = control.getOutputStream().flush()
        }
        private val wrapped = object : Socket() {
            override fun getInputStream() = control.getInputStream()
            override fun getOutputStream(): OutputStream = output
            override fun getInetAddress(): InetAddress = control.inetAddress
            override fun getLocalAddress(): InetAddress = control.localAddress
            override fun getRemoteSocketAddress() = control.remoteSocketAddress
            override fun setTcpNoDelay(on: Boolean) { control.tcpNoDelay=on }
            override fun close() = control.close()
        }
        private val store = PairingStore()
        val session = AirPlaySession(wrapped,config(content),AirPlayIdentity.generate(),store,null,
            object : AirPlaySessionListener {
                override fun onDebugLog(message: String) {
                    diagnostics += message
                    if (message == "BENCH_DIAG eventConnected") eventConnected.countDown()
                }
            }, object : AirPlayMediaHandler {
                override fun onScreen(session: AirPlaySession,type: Int,stream: Map<String,Any?>): Int? =
                    if (session.acceptsScreenStream(type)) 6000 else null
            }, availableAudioFormats=emptySet())
        private val controlWire: Wire
        private val eventKeyRead: ByteArray
        private val eventKeyWrite: ByteArray
        private var event: Socket? = null
        private var eventWire: Wire? = null
        private val eventPort: Int
        init {
            // Real stored-key proof; no private verified/authenticated fields are forced.
            val controller = AirPlayCrypto.ed25519Generate()
            val id = "synthetic-content-controller".toByteArray()
            store.save(id.toString(Charsets.UTF_8),controller.publicKey)
            val eph = AirPlayCrypto.x25519Generate()
            fun tlv(vararg pairs: Pair<Int,ByteArray>) = Tlv8Codec.encode(pairs.map { Tlv8Item(it.first,it.second) })
            val m2 = Tlv8Codec.decode(session.pairVerify.handle(tlv(6 to byteArrayOf(1),3 to eph.publicKey)))
            val remote = m2.getValue(3)
            val shared = AirPlayCrypto.x25519Shared(eph.privateKey,remote)
            val key = AirPlayCrypto.hkdfSha512(shared,"Pair-Verify-Encrypt-Salt".toByteArray(),"Pair-Verify-Encrypt-Info".toByteArray())
            val sig = AirPlayCrypto.ed25519Sign(controller.privateKey,eph.publicKey+id+remote)
            session.pairVerify.handle(tlv(6 to byteArrayOf(3),5 to AirPlayCrypto.chachaSeal(key,
                AirPlayCrypto.nonceLabel("PV-Msg03"),tlv(1 to id,10 to sig))))
            check(session.finishControlAuthentication())
            val keys = checkNotNull(session.pairVerify.controlKeys)
            controlWire = Wire(phone,ControlCipher(keys.writeKey,keys.readKey))
            eventKeyRead = AirPlayCrypto.hkdfSha512(shared,"Events-Salt".toByteArray(),"Events-Write-Encryption-Key".toByteArray(),32)
            eventKeyWrite = AirPlayCrypto.hkdfSha512(shared,"Events-Salt".toByteArray(),"Events-Read-Encryption-Key".toByteArray(),32)
            session.start()
            val reply = request("SETUP",emptyMap())
            eventPort = ((BplistCodec.decode(reply.body) as Map<*,*>)["eventPort"] as Number).toInt()
            controlFence()
        }
        fun info(): Map<*,*> {
            controlWire.send("GET", null, "/info")
            val response = controlWire.read()
            assertEquals("200", response.path)
            return BplistCodec.decode(response.body) as Map<*,*>
        }
        fun writerLock(): Any = session.javaClass.getDeclaredField("eventWriteLock").apply { isAccessible=true }.get(session)
        fun holdNextResponse(): WriteGate = WriteGate().also { nextGate=it }
        fun connectEvent(waitReady: Boolean = true) {
            event = Socket("127.0.0.1",eventPort).apply { soTimeout=2000 }
            eventWire = Wire(checkNotNull(event),ControlCipher(eventKeyRead,eventKeyWrite))
            if (waitReady) awaitEventConnected()
        }
        fun awaitEventConnected() { assertTrue(eventConnected.await(2,TimeUnit.SECONDS)) }
        fun sendSetupCluster() = controlWire.send("SETUP",mapOf("streams" to listOf(mapOf("type" to 111))))
        fun readSetupCluster(): List<*> {
            val reply = controlWire.read()
            assertEquals("200",reply.path)
            return (BplistCodec.decode(reply.body) as Map<*,*>)["streams"] as List<*>
        }
        fun setupCluster(): List<*> {
            sendSetupCluster()
            val streams = readSetupCluster()
            if (content != null) {
                assertEquals(1,streams.size)
                assertEquals(111,((streams.single() as Map<*,*>)["type"] as Number).toInt())
                assertTrue(session.clusterStream > 0)
            }
            return streams
        }
        fun teardownCluster() { request("TEARDOWN",mapOf("streams" to listOf(mapOf("type" to 111)))); controlFence() }
        fun controlFence() { request("OPTIONS",null) }
        private fun request(method: String, body: Map<String,Any?>?): RtspMessage.Request {
            controlWire.send(method,body)
            return controlWire.read().also { assertEquals("200",it.path) }
        }
        fun fenceEvent(): List<Map<*,*>> {
            val wire = checkNotNull(eventWire)
            wire.send("OPTIONS",null)
            val commands = arrayListOf<Map<*,*>>()
            repeat(8) {
                val message = wire.read()
                if (message.method == "RTSP/1.0") { assertEquals("200",message.path); return commands }
                assertEquals("POST",message.method)
                assertEquals("/command",message.path)
                commands += BplistCodec.decode(message.body) as Map<*,*>
            }
            fail("event fence exceeded finite command budget")
            return commands
        }
        fun assertEventEofWithoutCommands() {
            try { assertEquals("cancelled owner wrote bytes",-1,checkNotNull(event).getInputStream().read()) }
            catch (_: java.net.SocketException) { /* SO_LINGER(0) retirement may reset instead of FIN. */ }
        }
        override fun close() {
            nextGate?.release?.countDown()
            session.close()
            val joined = CountDownLatch(1)
            Thread({ session.awaitControlStopped(); joined.countDown() },"content-fixture-cleanup").apply {
                isDaemon=true; start()
            }
            assertTrue("actual session control retirement",joined.await(2,TimeUnit.SECONDS))
            event?.close(); phone.close(); control.close(); server.close()
        }
    }
    /** Reads complete actual AEAD records and RTSP frames; never synthesizes command output. */
    private class Wire(private val socket: Socket, private val cipher: ControlCipher) {
        private var plain = ByteArray(0)
        private val pending = java.util.ArrayDeque<RtspMessage.Request>()
        private var seq = 0
        fun send(method: String, body: Map<String,Any?>?, path: String = "/") {
            val bytes = body?.let(BplistCodec::encode) ?: ByteArray(0)
            val head = "$method $path RTSP/1.0\r\nCSeq: ${++seq}\r\nContent-Type: application/x-apple-binary-plist\r\nContent-Length: ${bytes.size}\r\n\r\n"
            socket.getOutputStream().write(cipher.encrypt(head.toByteArray(Charsets.US_ASCII)+bytes))
            socket.getOutputStream().flush()
        }
        fun read(): RtspMessage.Request {
            repeat(16) {
                if (pending.isNotEmpty()) return pending.removeFirst()
                val input = socket.getInputStream()
                val header = input.readNBytes(2)
                check(header.size == 2) { "unexpected encrypted EOF" }
                val n = (header[0].toInt() and 255) or ((header[1].toInt() and 255) shl 8)
                val rest = input.readNBytes(n+16)
                check(rest.size == n+16) { "short encrypted record" }
                plain += cipher.decrypt(header+rest).data
                val parsed = RtspMessage.parseMessages(plain)
                plain = parsed.rest; pending.addAll(parsed.messages)
            }
            error("finite RTSP record budget exceeded")
        }
    }
}
