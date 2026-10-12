package com.shilapi.xcertplay.airplay

import org.junit.Assert.*
import org.junit.Test
import java.net.*
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Real loopback transports and AEAD; generated test keys are not persistent receiver identities. */
class LabMediaTransportTest {
    @Test fun screenRejectsOtherPeerThenDecryptsSelectedPeerFrame() {
        val expected = InetAddress.getByName("127.0.0.1")
        val key = ByteArray(32) { (it + 1).toByte() }
        val screen = ScreenStream(key, expectedPeer = expected)
        val received = AtomicReference<ByteArray>()
        val latch = CountDownLatch(1)
        val port = screen.listen(object : ScreenStream.Listener {
            override fun onFrame(naluBytes: ByteArray) { received.set(naluBytes); latch.countDown() }
        })
        try {
            Socket().use { rejected ->
                rejected.bind(InetSocketAddress("127.0.0.2", 0))
                rejected.connect(InetSocketAddress(expected, port)); rejected.getOutputStream().write(ByteArray(128))
            }
            Socket().use { sender ->
                sender.bind(InetSocketAddress(expected, 0)); sender.connect(InetSocketAddress(expected, port))
                val payload = byteArrayOf(0,0,0,2,0x65,1)
                val header = ByteArray(128)
                ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN).putInt(payload.size + 16)
                val sealed = AirPlayCrypto.chachaSeal(key, AirPlayCrypto.nonce64(0), payload, header)
                sender.getOutputStream().write(header + sealed)
                assertTrue(latch.await(3, TimeUnit.SECONDS))
                assertArrayEquals(byteArrayOf(0,0,0,1,0x65,1), received.get())
            }
        } finally { screen.close() }
    }
    @Test fun audioRejectsOtherPeerThenAuthenticatesSelectedPeerPcm() {
        val expected = InetAddress.getByName("127.0.0.1")
        val key = ByteArray(32) { (it + 7).toByte() }
        val stream = AudioStream(key, 100, expectedPeer = expected)
        val received = AtomicReference<ByteArray>()
        val latch = CountDownLatch(1)
        val (port, _) = stream.listen(object : AudioStream.Listener {
            override fun onRtp(rtp: ByteArray, sample: Int) { received.set(rtp); latch.countDown() }
        })
        try {
            val header = byteArrayOf(0x80.toByte(), 100, 0, 1, 0, 0, 0, 123, 0, 0, 0, 1)
            val pcm = byteArrayOf(0x01, 0x02, 0x03, 0x04)
            val sealed = AirPlayCrypto.chachaSeal(key, ByteArray(12), pcm, header.copyOfRange(4, 12))
            val wire = header + sealed + ByteArray(8)
            DatagramSocket(InetSocketAddress("127.0.0.2",0)).use { sender ->
                sender.send(DatagramPacket(wire, wire.size, expected, port))
                assertFalse(latch.await(100, TimeUnit.MILLISECONDS))
            }
            DatagramSocket(InetSocketAddress(expected,0)).use { sender ->
                sender.send(DatagramPacket(wire, wire.size, expected, port))
                assertTrue(latch.await(3, TimeUnit.SECONDS))
                assertArrayEquals(header + pcm, received.get())
            }
        } finally { stream.close() }
    }
}
