package com.shilapi.xcertplay.airplay

import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.*
import org.junit.Test

class MicrophonePacketizerTest {
    @Test fun sealedPacketUsesClearRtpAuthenticatedTimestampAndLittleEndianNonce() {
        val key = ByteArray(32) { it.toByte() }
        val counters = MicrophoneCounters(65535, -480, 0x0102030405060708L)
        val body = byteArrayOf(0x78, 1, 2, 3)
        val packet = MicrophonePacketizer.sealPacket(key, counters, body, 480)
        assertEquals(12 + 16 + 8 + body.size, packet.size)
        assertArrayEquals(byteArrayOf(0x80.toByte(), 100, -1, -1), packet.copyOfRange(0, 4))
        assertEquals(-480, ByteBuffer.wrap(packet, 4, 4).int)
        assertEquals(0, ByteBuffer.wrap(packet, 8, 4).int)
        assertEquals(0x0102030405060708L, ByteBuffer.wrap(packet, packet.size - 8, 8).order(ByteOrder.LITTLE_ENDIAN).long)
        val nonce = ByteArray(12)
        packet.copyInto(nonce, 4, packet.size - 8)
        assertArrayEquals(body, AirPlayCrypto.chachaOpen(key, nonce, packet.copyOfRange(12, packet.size - 8), packet.copyOfRange(4, 12)))
        assertEquals(0, counters.sequence)
        assertEquals(0, counters.timestamp)
        assertEquals(0x0102030405060709L, counters.nonce)
        val altered = packet.copyOfRange(4, 12).also { it[0] = (it[0].toInt() xor 1).toByte() }
        assertTrue(runCatching { AirPlayCrypto.chachaOpen(key, nonce, packet.copyOfRange(12, packet.size - 8), altered) }.isFailure)
    }

    @Test fun inputKeyUsesUnsignedConnectionIdNotTunnelSeed() {
        val secret = ByteArray(32) { (it + 2).toByte() }
        val expected = AirPlayCrypto.hkdfSha512(secret, "DataStream-Salt18446744073709551615".toByteArray(),
            "DataStream-Input-Encryption-Key".toByteArray(), 32)
        assertArrayEquals(expected, MicrophonePacketizer.inputKey(secret, -1L))
        assertNull(MicrophonePacketizer.inputKey(secret, "18446744073709551615"))
    }

    @Test fun exhaustedNonceRejectsWithoutChangingCounters() {
        val counters = MicrophoneCounters(nonce = -1L)
        assertTrue(runCatching { MicrophonePacketizer.sealPacket(ByteArray(32), counters, byteArrayOf(1), 960) }.isFailure)
        assertEquals(-1L, counters.nonce)
        assertEquals(0, counters.sequence)
    }
}
