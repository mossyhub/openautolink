// SPDX-License-Identifier: GPL-3.0-only
package com.shilapi.xcertplay.media

import com.shilapi.xcertplay.airplay.*
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Synthetic paired key + real loopback TCP; only the decoder is replaced by a recording sink. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32], manifest = Config.NONE)
class CarPlayShortFrameTransportTest {
    private val payload = byteArrayOf(0, 0, 0, 2, 0x65, 0)
    private val annexB = byteArrayOf(0, 0, 0, 1, 0x65, 0)
    // Structurally valid avcC with one SPS and PPS; shorter than an AEAD tag, intentionally clear.
    private val config = byteArrayOf(1, 66, 0, 30, -1, -31, 0, 1, 0x67, 1, 0, 1, 0x68)
    private fun header(size: Int, opcode: Int = 0) = ByteBuffer.allocate(128)
        .order(ByteOrder.LITTLE_ENDIAN).putInt(size).put(opcode.toByte()).array()

    @Test fun directCodecRejectsEveryUndersizedBodyWithoutPlaintextFallback() {
        for (size in 0 until ScreenCodec.TAG_SIZE) {
            assertThrows("body size $size must require an authentication tag", IllegalArgumentException::class.java) {
                ScreenCodec.decryptFrame(ByteArray(32), 0, header(size), payload.copyOf(size))
            }
        }
        val key = ByteArray(32)
        val authenticatedHeader = header(payload.size + ScreenCodec.TAG_SIZE)
        val sealed = AirPlayCrypto.chachaSeal(key, AirPlayCrypto.nonce64(0), payload, authenticatedHeader)
        assertArrayEquals(payload, ScreenCodec.decryptFrame(key, 0, authenticatedHeader, sealed))
    }

    @Test fun shortSecondaryFrameRetiresOnlySecondaryAndMainEncryptedFramesContinue() = shortFrame(111)
    @Test fun shortMainFrameClosesSharedSessionWithoutAcceptingPlaintext() = shortFrame(110)

    private fun shortFrame(failedType: Int) {
        val frames = LinkedBlockingQueue<Pair<Int, ByteArray>>()
        val configs = LinkedBlockingQueue<Pair<Int, ByteArray>>()
        val stopped = CountDownLatch(1)
        val engine = CarPlayMediaEngine(object : MediaSink {
            override fun onVideoConfig(type: Int, codecData: ByteArray) { configs.add(type to codecData) }
            override fun onVideoFrame(type: Int, naluBytes: ByteArray) { frames.add(type to naluBytes) }
            override fun onScreenStreamActive(type: Int, active: Boolean) {
                if (type == failedType && !active) stopped.countDown()
            }
        })
        val current = AirPlaySession(Socket(), AirPlayConfig("test", "synthetic", "synthetic", "1",
            AirPlayDisplayConfig(1280, 720), cluster = AirPlayDisplayConfig(1280, 720)),
            AirPlayIdentity.generate(), PairingStore(), null, object : AirPlaySessionListener {}, engine)
        val verify = current.javaClass.getDeclaredField("pairVerify").apply { isAccessible = true }.get(current)
        verify.javaClass.getDeclaredField("sharedSecret").apply { isAccessible = true }.set(verify, ByteArray(32))
        fun streams(): List<ScreenStream> = (engine.javaClass.getDeclaredField("streams")
            .apply { isAccessible = true }.get(engine) as Map<*, *>).values.filterIsInstance<ScreenStream>()
        fun counter(stream: ScreenStream) = stream.javaClass.getDeclaredField("frameCounter")
            .apply { isAccessible = true }.get(stream) as AtomicLong
        fun send(socket: Socket, body: ByteArray, opcode: Int = 0) {
            socket.getOutputStream().apply { write(header(body.size, opcode)); write(body); flush() }
        }
        fun encrypted(socket: Socket, id: Int, nonce: Long) {
            val key = AirPlayCrypto.hkdfSha512(ByteArray(32), "DataStream-Salt$id".toByteArray(),
                "DataStream-Output-Encryption-Key".toByteArray(), 32)
            send(socket, AirPlayCrypto.chachaSeal(key, AirPlayCrypto.nonce64(nonce), payload,
                header(payload.size + ScreenCodec.TAG_SIZE)))
        }
        fun expectFrame(type: Int) {
            val frame = checkNotNull(frames.poll(3, TimeUnit.SECONDS)) { "authenticated frame missing" }
            assertEquals(type, frame.first); assertArrayEquals(annexB, frame.second)
        }
        try {
            val mainPort = checkNotNull(engine.onScreen(current, 110, mapOf("streamConnectionID" to 1L)))
            val mainStream = streams().single()
            val altPort = checkNotNull(engine.onScreen(current, 111, mapOf("streamConnectionID" to 2L)))
            val altStream = streams().single { it !== mainStream }
            Socket("127.0.0.1", mainPort).use { main -> Socket("127.0.0.1", altPort).use { alt ->
                for ((type, socket) in listOf(110 to main, 111 to alt)) {
                    send(socket, config, 1)
                    val received = checkNotNull(configs.poll(3, TimeUnit.SECONDS))
                    assertEquals(type, received.first); assertArrayEquals(config, received.second)
                }
                encrypted(main, 1, 0); expectFrame(110)
                encrypted(alt, 2, 0); expectFrame(111)
                send(if (failedType == 111) alt else main, payload)
                assertTrue("short frame must terminate its actual TCP stream", stopped.await(3, TimeUnit.SECONDS))
                synchronized(engine) {
                    assertEquals("only main failure closes the shared session", failedType == 110, current.isClosed)
                    assertEquals("unauthenticated data must not advance main nonce", 1L, counter(mainStream).get())
                    assertEquals("unauthenticated data must not advance ALT nonce", 1L, counter(altStream).get())
                    assertTrue("no unauthenticated IDR reaches the sink", frames.isEmpty())
                    if (failedType == 111) {
                        assertTrue(engine.owns(current)); assertEquals(listOf(mainStream), streams())
                    }
                }
                if (failedType == 111) {
                    encrypted(main, 1, 1); expectFrame(110)
                    assertEquals(2L, counter(mainStream).get())
                    assertTrue(frames.isEmpty())
                }
            } }
        } finally { engine.onSessionClosed(current); current.close() }
    }
}
