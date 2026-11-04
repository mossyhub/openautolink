package com.shilapi.xcertplay.media

import com.shilapi.xcertplay.airplay.*
import org.junit.Assert.*
import org.junit.Test
import java.net.*
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.CopyOnWriteArrayList

/** Actual loopback/AEAD for both identities, not phone acceptance. */
class ScreenStreamIdentityTest {
    @Test fun dualEncryptedStreamsKeepReceiveConfigAndFirstFrameIdentity() {
        val lines = CopyOnWriteArrayList<String>()
        val ended = CountDownLatch(2)
        val frames = CountDownLatch(2)
        val streams = listOf(110,111).map { type ->
            val key = ByteArray(32) { (it + type).toByte() }
            val stream = ScreenStream(key, onDiagnostic = { lines += it; if ("stage=receive" in it && "ended=true" in it) ended.countDown() },
                expectedPeer = InetAddress.getLoopbackAddress(), streamType = type)
            val port = stream.listen(object : ScreenStream.Listener {
                override fun onFrame(bytes: ByteArray) { assertArrayEquals(byteArrayOf(0,0,0,1,0x65,1),bytes); frames.countDown() }
            })
            Triple(stream,port,key)
        }
        try {
            streams.forEach { (_,port,key) ->
                Socket(InetAddress.getLoopbackAddress(),port).use { socket ->
                    val avcc = GeneratedHevcFixture.avcc
                    val configHeader = ByteArray(128).apply { this[4]=1 }
                    ByteBuffer.wrap(configHeader).order(ByteOrder.LITTLE_ENDIAN).putInt(avcc.size)
                    val payload = byteArrayOf(0,0,0,2,0x65,1)
                    val frameHeader = ByteArray(128)
                    ByteBuffer.wrap(frameHeader).order(ByteOrder.LITTLE_ENDIAN).putInt(payload.size+16)
                    val sealed = AirPlayCrypto.chachaSeal(key,AirPlayCrypto.nonce64(0),payload,frameHeader)
                    socket.getOutputStream().write(configHeader+avcc+frameHeader+sealed)
                }
            }
            assertTrue(frames.await(3,TimeUnit.SECONDS))
            assertTrue(ended.await(3,TimeUnit.SECONDS))
            for (type in listOf(110,111)) {
                for (stage in listOf("tcp-attached","config-delivered","authenticated-frame-delivered"))
                    assertEquals("$stage type=$type",1,lines.count { "stage=$stage kind=video type=$type" in it })
                assertEquals(1,lines.count { "stage=receive kind=video type=$type packets=2" in it && "ended=true" in it })
                assertEquals(1, lines.count { "stage=screen-terminal kind=video type=$type generation=" in it && "reason=eof" in it })
            }
            assertTrue(lines.none { "127.0.0.1" in it })
        } finally { streams.forEach { it.first.close() } }
    }
}
