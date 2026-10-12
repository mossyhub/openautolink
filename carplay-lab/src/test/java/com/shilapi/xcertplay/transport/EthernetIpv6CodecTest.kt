// SPDX-License-Identifier: GPL-3.0-only
// From shilapi/DiPlay; see UPSTREAM-LICENSE.
package com.shilapi.xcertplay.transport

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EthernetIpv6CodecTest {
    @Test
    fun `maps IPv6 multicast destination to Ethernet multicast`() {
        val packet = ByteArray(40)
        packet[0] = 0x60
        val destination = byteArrayOf(
            0xff.toByte(), 0x02, 0, 0, 0, 0, 0, 0,
            0, 0, 0, 1, 0xff.toByte(), 0, 0, 2,
        )
        destination.copyInto(packet, 24)

        assertArrayEquals(
            byteArrayOf(0x33, 0x33, 0xff.toByte(), 0, 0, 2),
            EthernetIpv6Codec.multicastDestinationMac(packet),
        )
    }

    @Test
    fun `does not map IPv6 unicast destination`() {
        val packet = ByteArray(40)
        packet[0] = 0x60
        packet[24] = 0xfe.toByte()
        packet[25] = 0x80.toByte()

        assertNull(EthernetIpv6Codec.multicastDestinationMac(packet))
    }

    @Test
    fun `rejects truncated or non IPv6 packets`() {
        assertNull(EthernetIpv6Codec.multicastDestinationMac(ByteArray(39)))
        assertNull(EthernetIpv6Codec.multicastDestinationMac(ByteArray(40)))
    }

    @Test
    fun `adds target link layer option to tun neighbor advertisement`() {
        val packet = ByteArray(64)
        packet[0] = 0x60
        packet[5] = 24
        packet[6] = 58
        byteArrayOf(0xfe.toByte(), 0x80.toByte(), 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 2)
            .copyInto(packet, 8)
        byteArrayOf(0xfe.toByte(), 0x80.toByte(), 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 3)
            .copyInto(packet, 24)
        packet[40] = 136.toByte()
        packet[44] = 0x60
        val mac = byteArrayOf(0x02, 0, 0, 0, 0, 2)

        val result = EthernetIpv6Codec.addNeighborAdvertisementTargetMac(packet, mac)

        assertEquals(72, result.size)
        assertEquals(32, ((result[4].toInt() and 0xff) shl 8) or (result[5].toInt() and 0xff))
        assertEquals(2, result[64].toInt() and 0xff)
        assertEquals(1, result[65].toInt() and 0xff)
        assertArrayEquals(mac, result.copyOfRange(66, 72))
        assertEquals(0xffff, ipv6IcmpChecksumSum(result))
    }

    private fun ipv6IcmpChecksumSum(packet: ByteArray): Int {
        val payloadBytes = ((packet[4].toInt() and 0xff) shl 8) or (packet[5].toInt() and 0xff)
        var sum = 0L
        fun words(start: Int, bytes: Int) {
            var offset = start
            val end = start + bytes
            while (offset + 1 < end) {
                sum += (((packet[offset].toInt() and 0xff) shl 8) or
                    (packet[offset + 1].toInt() and 0xff)).toLong()
                offset += 2
            }
        }
        words(8, 32)
        sum += payloadBytes
        sum += 58
        words(40, payloadBytes)
        while (sum ushr 16 != 0L) sum = (sum and 0xffff) + (sum ushr 16)
        return sum.toInt()
    }
}
