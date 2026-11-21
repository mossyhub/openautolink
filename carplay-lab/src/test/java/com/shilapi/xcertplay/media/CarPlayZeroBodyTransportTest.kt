// SPDX-License-Identifier: GPL-3.0-only
package com.shilapi.xcertplay.media

import com.shilapi.xcertplay.airplay.*
import java.io.Closeable
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Constructed wire packets, NOT recovered phone headers/opcodes or physical decode evidence.
 * Uses production onScreen ports, stream keys, full 128-byte AAD and real TCP. Only MediaSink
 * is a recorder. Reflection reads existing stream/counter state and seeds synthetic PairVerify
 * sharedSecret exactly as the unchanged CarPlayShortFrameTransportTest fixture does.
 * No new production API, plaintext fallback, startup offer, event handshake or timing policy.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32], manifest = Config.NONE)
class CarPlayZeroBodyTransportTest {
    private val secret = ByteArray(32) { (it + 17).toByte() }
    private val payload = byteArrayOf(0, 0, 0, 2, 0x65, 0)
    private val annexB = byteArrayOf(0, 0, 0, 1, 0x65, 0)
    // Valid clear avcC, intentionally only 13 bytes; never send it as a video-frame body.
    private val config = byteArrayOf(1, 66, 0, 30, -1, -31, 0, 1, 0x67, 1, 0, 1, 0x68)
    private val types = listOf(110, 111)

    @Test fun mainZeroBodyVideoFrameDrainsBeforeNonceZero() = zeroBody(110, 0)
    @Test fun altZeroBodyVideoFrameDrainsBeforeNonceZero() = zeroBody(111, 0)
    @Test fun mainZeroBodyVideoConfigDrainsBeforeNonceZero() = zeroBody(110, 1)
    @Test fun altZeroBodyVideoConfigDrainsBeforeNonceZero() = zeroBody(111, 1)
    // Already-ignored positive discriminator: an unknown opcode must remain harmless.
    @Test fun mainZeroBodyUnknownOpcodeDrainsBeforeNonceZero() = zeroBody(110, 127)
    @Test fun altZeroBodyUnknownOpcodeDrainsBeforeNonceZero() = zeroBody(111, 127)

    @Test fun setupControlBothActualTransportsDeliverConfigAndNonceZero() {
        fixture { f ->
            // No zero-body input: failure here is fixture/setup, never behavioral RED.
            for (type in types) {
                f.encrypted(type, 0)
                f.expectFrame(type, "SETUP_CONTROL authenticated nonce0 type=$type")
                assertEquals(1L, f.counter(type))
            }
            f.assertLive()
            f.assertNoPendingDelivery("SETUP_CONTROL exact-once delivery")
        }
    }

    @Test fun setupControlTamperedFullHeaderAadRejectsMain() = badAad(110)
    @Test fun setupControlTamperedFullHeaderAadRejectsAltOnly() = badAad(111)

    @Test fun nonemptyShortMainBodiesOneThroughFifteenStillFailClosed() = nonemptyShort(110)
    @Test fun nonemptyShortAltBodiesOneThroughFifteenStillRetireAltOnly() = nonemptyShort(111)

    private fun zeroBody(target: Int, opcode: Int) = fixture { f ->
        val other = if (target == 110) 111 else 110
        f.encrypted(other, 0)
        f.expectFrame(other, "SETUP_CONTROL other-stream authenticated nonce0")
        f.assertLive()
        assertEquals("SETUP_CONTROL target has never consumed a frame nonce", 0L, f.counter(target))
        f.assertNoPendingDelivery("SETUP_CONTROL drained before zero-body stimulus")

        // Repeated messages followed by a config on the SAME connected socket. The later config
        // callback is a FIFO read/dispatch fence, not a sleep or a guessed absence window.
        f.sendZeroRunThenConfig(target, opcode)
        f.expectConfig(target, "ZERO_DRAIN type=$target opcode=$opcode: config FIFO fence after four zero headers")
        synchronized(f.engine) {
            f.assertLive()
            assertEquals("ZERO_DRAIN zero headers/config must not consume target nonce", 0L, f.counter(target))
            assertEquals("ZERO_DRAIN other-stream nonce unchanged", 1L, f.counter(other))
            f.assertNoPendingDelivery("ZERO_DRAIN no fake config, codec or pixels")
        }
        f.encrypted(target, 0)
        f.expectFrame(target, "ZERO_DRAIN same-socket authenticated nonce0 after zero headers")
        // A later config fences every prior target callback, proving exact-once, not just first hit.
        f.send(target, config, 1)
        f.expectConfig(target, "ZERO_DRAIN exact-once target frame fence")
        assertEquals(1L, f.counter(target))
        f.assertNoPendingDelivery("ZERO_DRAIN nonce0 delivered exactly once")

        f.encrypted(other, 1)
        f.expectFrame(other, "ZERO_DRAIN other stream continues authenticated next nonce")
        f.encrypted(target, 1)
        f.expectFrame(target, "ZERO_DRAIN target continues authenticated next nonce")
        for (type in types) {
            f.send(type, config, 1)
            f.expectConfig(type, "ZERO_DRAIN final FIFO fence type=$type")
            assertEquals(2L, f.counter(type))
        }
        f.assertLive()
        f.assertNoPendingDelivery("ZERO_DRAIN exact counts on both streams")
    }

    private fun badAad(target: Int) = fixture { f ->
        val other = if (target == 110) 111 else 110
        // First prove this exact target's transport/config/key/nonce0 are functional.
        f.encrypted(target, 0)
        f.expectFrame(target, "SETUP_CONTROL pre-tamper target nonce0")
        f.encrypted(other, 0)
        f.expectFrame(other, "SETUP_CONTROL pre-tamper other nonce0")
        // Seal with the correct entire header, then change byte127 on the wire only.
        // This negative setup discriminator must fail authentication, not valid setup.
        f.encrypted(target, 1, tamperHeader = true)
        f.expectRetired(target, "SETUP_NEGATIVE tampered header AAD")
        assertEquals("failed authentication cannot advance nonce", 1L, f.counter(target))
        f.assertNoPendingDelivery("SETUP_NEGATIVE no tampered frame reaches sink")
        f.assertRetirementScope(target)
        if (target == 111) {
            f.encrypted(110, 1)
            f.expectFrame(110, "SETUP_NEGATIVE ALT-only retirement keeps main nonce1")
            assertEquals(2L, f.counter(110))
        }
    }

    private fun nonemptyShort(target: Int) {
        // Real transport coverage for every genuine 1..15-byte opcode0 body, not the zero-body
        // exception. Six bytes are a valid *unsealed* length-prefixed IDR, never valid ciphertext.
        for (size in 1 until ScreenCodec.TAG_SIZE) fixture { f ->
            for (type in types) {
                f.encrypted(type, 0)
                f.expectFrame(type, "SECURITY_CONTROL size=$size authenticated setup type=$type")
            }
            val body = if (size == payload.size) payload else ByteArray(size) { 0x55.toByte() }
            f.send(target, body, 0)
            f.expectRetired(target, "SECURITY_CONTROL nonempty short opcode0 size=$size")
            for (type in types) assertEquals("short input cannot advance either nonce", 1L, f.counter(type))
            f.assertNoPendingDelivery("SECURITY_CONTROL no unauthenticated IDR or other short body delivered")
            f.assertRetirementScope(target)
            if (target == 111) {
                f.encrypted(110, 1)
                f.expectFrame(110, "SECURITY_CONTROL main authenticated nonce1 after ALT size=$size")
                assertEquals(2L, f.counter(110))
                f.assertNoPendingDelivery("SECURITY_CONTROL continued main exact-once")
            }
        }
    }

    private fun header(size: Int, opcode: Int): ByteArray = ByteArray(128) { (it * 3 + 7).toByte() }.apply {
        ByteBuffer.wrap(this).order(ByteOrder.LITTLE_ENDIAN).putInt(size).put(opcode.toByte())
    }

    private fun fixture(block: (Fixture) -> Unit) {
        val f = Fixture()
        try { f.start(); block(f) } finally { f.close() }
    }

    private inner class Fixture : Closeable {
        val frames = LinkedBlockingQueue<Pair<Int, ByteArray>>()
        val configs = LinkedBlockingQueue<Pair<Int, ByteArray>>()
        val codecs = LinkedBlockingQueue<Pair<Int, VideoCodec>>()
        val retired = LinkedBlockingQueue<Int>()
        val engine = CarPlayMediaEngine(object : MediaSink {
            override fun onVideoCodec(type: Int, codec: VideoCodec) { codecs.add(type to codec) }
            override fun onVideoConfig(type: Int, codecData: ByteArray) { configs.add(type to codecData.copyOf()) }
            override fun onVideoFrame(type: Int, naluBytes: ByteArray) { frames.add(type to naluBytes.copyOf()) }
            override fun onScreenStreamActive(type: Int, active: Boolean) { if (!active) retired.add(type) }
        })
        val session = AirPlaySession(Socket(), AirPlayConfig("test", "synthetic", "synthetic", "1",
            AirPlayDisplayConfig(1280, 720), cluster = AirPlayDisplayConfig(1280, 720)),
            AirPlayIdentity.generate(), PairingStore(), null, object : AirPlaySessionListener {}, engine)
        private val owned = linkedMapOf<Int, ScreenStream>()
        private val sockets = linkedMapOf<Int, Socket>()
        private val ids = mapOf(110 to 101L, 111 to 202L)

        fun start() {
            val verify = session.javaClass.getDeclaredField("pairVerify").apply { isAccessible = true }.get(session)
            verify.javaClass.getDeclaredField("sharedSecret").apply { isAccessible = true }.set(verify, secret.copyOf())
            for (type in types) {
                val before = streams()
                val port = checkNotNull(engine.onScreen(session, type, mapOf("streamConnectionID" to ids.getValue(type)))) {
                    "SETUP_FAILURE onScreen rejected type=$type"
                }
                assertTrue("SETUP_FAILURE invalid production port", port in 1..65535)
                owned[type] = streams().single { it !in before }
                // Register before fallible connect so finally owns every created socket.
                val socket = Socket()
                sockets[type] = socket
                socket.connect(InetSocketAddress("127.0.0.1", port), 3000)
                send(type, config, 1)
                expectConfig(type, "SETUP_FAILURE valid transport/config type=$type")
                assertEquals("SETUP_FAILURE config consumes no frame nonce", 0L, counter(type))
            }
            assertLive()
            assertNoPendingDelivery("SETUP_FAILURE fixture must start without unsolicited delivery")
        }

        private fun streams(): List<ScreenStream> = (engine.javaClass.getDeclaredField("streams")
            .apply { isAccessible = true }.get(engine) as Map<*, *>).values.filterIsInstance<ScreenStream>()

        fun counter(type: Int): Long = (owned.getValue(type).javaClass.getDeclaredField("frameCounter")
            .apply { isAccessible = true }.get(owned.getValue(type)) as AtomicLong).get()

        fun sendZeroRunThenConfig(type: Int, opcode: Int) {
            // One small socket write prevents expected baseline closure racing a second zero
            // write into BrokenPipe instead of the intended missing-config FIFO assertion.
            val wire = java.io.ByteArrayOutputStream()
            repeat(4) { wire.write(header(0, opcode)) }
            wire.write(header(config.size, 1))
            wire.write(config)
            sockets.getValue(type).getOutputStream().apply { write(wire.toByteArray()); flush() }
        }
        fun send(type: Int, body: ByteArray, opcode: Int) = write(type, header(body.size, opcode), body)
        private fun write(type: Int, wireHeader: ByteArray, body: ByteArray) {
            sockets.getValue(type).getOutputStream().apply { write(wireHeader); write(body); flush() }
        }
        fun encrypted(type: Int, nonce: Long, tamperHeader: Boolean = false) {
            val key = AirPlayCrypto.hkdfSha512(secret, "DataStream-Salt${ids.getValue(type)}".toByteArray(Charsets.US_ASCII),
                "DataStream-Output-Encryption-Key".toByteArray(Charsets.US_ASCII), 32)
            val aad = header(payload.size + ScreenCodec.TAG_SIZE, 0)
            val sealed = AirPlayCrypto.chachaSeal(key, AirPlayCrypto.nonce64(nonce), payload, aad)
            val wireHeader = aad.copyOf()
            if (tamperHeader) wireHeader[127] = (wireHeader[127].toInt() xor 1).toByte()
            write(type, wireHeader, sealed)
        }
        fun expectConfig(type: Int, label: String) {
            val received = configs.poll(3, TimeUnit.SECONDS)
            assertNotNull("$label: valid config missing; closed=${session.isClosed}, retired=$retired", received)
            assertEquals(label, type, received!!.first)
            assertArrayEquals(label, config, received.second)
            val codec = codecs.poll(3, TimeUnit.SECONDS)
            assertNotNull("$label: codec callback missing", codec)
            assertEquals(label, type, codec!!.first)
            assertEquals(label, VideoCodec.H264, codec.second)
        }
        fun expectFrame(type: Int, label: String) {
            val received = frames.poll(3, TimeUnit.SECONDS)
            assertNotNull("$label: authenticated frame missing; closed=${session.isClosed}, retired=$retired", received)
            assertEquals(label, type, received!!.first)
            assertArrayEquals(label, annexB, received.second)
        }
        fun expectRetired(type: Int, label: String) {
            assertEquals(label, type, retired.poll(3, TimeUnit.SECONDS))
        }
        fun assertLive() = synchronized(engine) {
            assertFalse("shared session must remain open", session.isClosed)
            assertTrue("exact session must still own engine", engine.owns(session))
            assertEquals("both exact stream instances must remain current", owned.values.toSet(), streams().toSet())
            assertTrue("no stream retirement", retired.isEmpty())
        }
        fun assertRetirementScope(target: Int) = synchronized(engine) {
            assertEquals("only main failure closes shared session", target == 110, session.isClosed)
            if (target == 111) {
                assertTrue(engine.owns(session))
                assertEquals(listOf(owned.getValue(110)), streams())
            }
        }
        fun assertNoPendingDelivery(label: String) {
            assertTrue("$label: extra/fake frame", frames.isEmpty())
            assertTrue("$label: extra/fake config", configs.isEmpty())
            assertTrue("$label: extra/fake codec", codecs.isEmpty())
        }
        override fun close() {
            try {
                sockets.values.forEach { runCatching { it.close() } }
            } finally {
                try { engine.onSessionClosed(session) } finally { session.close() }
            }
        }
    }
}
