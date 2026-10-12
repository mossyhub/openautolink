package com.shilapi.xcertplay.media

import com.shilapi.xcertplay.airplay.*
import org.junit.Assert.*
import org.junit.Test
import java.net.*
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Real loopback + AEAD with generated public test CSD, not an iPhone capture. */
class CarPlayHevcTransportTest {
    @Test fun hevcRecordSelectsActualCodecAndNegotiatedNalLengthThroughAead() {
        for (lengthSize in listOf(1,2,4)) {
            val key=ByteArray(32) { (it+4).toByte() }
            val stream=ScreenStream(key,expectedPeer=InetAddress.getLoopbackAddress())
            val received=AtomicReference<ByteArray>(); val codec=AtomicReference<VideoCodec>()
            val config=AtomicReference<ByteArray>(); val frame=CountDownLatch(1)
            val port=stream.listen(object: ScreenStream.Listener {
                override fun onCodec(value: VideoCodec) { codec.set(value) }
                override fun onConfig(bytes: ByteArray) { config.set(bytes) }
                override fun onFrame(bytes: ByteArray) { received.set(bytes); frame.countDown() }
            })
            try {
                Socket(InetAddress.getLoopbackAddress(),port).use { socket ->
                    val hvcc=GeneratedHevcFixture.hvcc.apply { this[21]=(this[21].toInt() and 0xfc or (lengthSize-1)).toByte() }
                    val header=ByteArray(128).apply { this[4]=1 }
                    ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN).putInt(hvcc.size)
                    val payload=ByteArray(lengthSize).apply { this[lengthSize-1]=2 } + byteArrayOf(0x26,1)
                    val frameHeader=ByteArray(128)
                    ByteBuffer.wrap(frameHeader).order(ByteOrder.LITTLE_ENDIAN).putInt(payload.size+16)
                    val sealed=AirPlayCrypto.chachaSeal(key,AirPlayCrypto.nonce64(0),payload,frameHeader)
                    socket.getOutputStream().write(header+hvcc+frameHeader+sealed)
                    assertTrue(frame.await(3,TimeUnit.SECONDS))
                    assertEquals(VideoCodec.H265,codec.get()); assertArrayEquals(hvcc,config.get())
                    assertArrayEquals("NAL prefix $lengthSize",byteArrayOf(0,0,0,1,0x26,1),received.get())
                }
            } finally { stream.close() }
        }
    }
}
