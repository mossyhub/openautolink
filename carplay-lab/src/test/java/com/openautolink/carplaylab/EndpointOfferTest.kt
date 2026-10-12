package com.openautolink.carplaylab

import com.shilapi.xcertplay.iap2.message.Iap2Messages
import com.shilapi.xcertplay.iap2.message.Iap2CarPlayMessages
import org.junit.Assert.*
import org.junit.Test

class EndpointOfferTest {
    @Test fun wifiRequestUsesActualNetworkConfiguration() {
        val offer = EndpointOffer("Lab", "private-test-only", 36, 2, listOf("fe80::1234"), 41821, "pairing-test", "ab".repeat(32))
        val reply = offer.replyTo(Iap2Messages.buildRaw(0x5702) {})!!
        assertEquals(0x5703, reply.messageId)
        val body = Iap2Messages.reader(reply)
        assertEquals("Lab", body.string(1))
        assertEquals("private-test-only", body.string(2))
        assertEquals(2, body.u8(3))
        assertEquals(36, body.u8(4))
        assertNull(offer.replyTo(Iap2Messages.buildRaw(0x9999) {}))
    }
    @Test fun transportNotificationResendsTheSameWifiConfiguration() {
        val offer = EndpointOffer("Lab", "private-test-only", 36, 2, listOf("fe80::1234"), 41821, "pairing-test", "ab".repeat(32))
        val reply = offer.replyTo(Iap2Messages.buildRaw(0x4e0e) {})
        assertNotNull("Transport notification must resend Wi-Fi configuration", reply)
        assertEquals(0x5703, reply!!.messageId)
        val body = Iap2Messages.reader(reply)
        assertEquals("Lab", body.string(1))
        assertEquals("private-test-only", body.string(2))
        assertEquals(2, body.u8(3))
        assertEquals(36, body.u8(4))
    }
    @Test fun availabilityCarriesActualBoundPortAndIdentity() {
        val offer = EndpointOffer("Lab", "private-test-only", 36, 2,
            listOf("fe80::1234"), 41821, "pairing-test", "ab".repeat(32))
        val reply = offer.replyTo(Iap2Messages.buildRaw(0x4300) {})!!
        assertEquals(0x4301, reply.messageId)
        val body = Iap2Messages.reader(reply)
        assertEquals(41821L, body.u32(2))
        assertEquals("pairing-test", body.string(3))
        assertEquals("ab".repeat(32), body.string(4))
        assertEquals("fe80::1234", body.group(1).string(3))
    }
}
