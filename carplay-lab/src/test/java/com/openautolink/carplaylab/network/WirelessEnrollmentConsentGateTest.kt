package com.openautolink.carplaylab.network

import com.shilapi.xcertplay.iap2.message.Iap2Messages
import com.shilapi.xcertplay.iap2.wire.Iap2Frame
import org.junit.Assert.*
import org.junit.Test

class WirelessEnrollmentConsentGateTest {
    @Test fun actualWirelessRequestNotGenericNotificationQualifiesSelectedBootstrap() {
        val gate=WirelessEnrollmentConsentGate()
        assertFalse(gate.observe(Iap2Frame(0x4e0e,byteArrayOf())))
        assertFalse(gate.observe(Iap2Messages.buildRaw(0x4e0d) { u8(0,1) }))
        assertFalse(gate.observe(Iap2Frame(0x4300,byteArrayOf())))
        assertFalse(gate.observe(Iap2Messages.buildRaw(0x4300) { group(1) { u8(0,0) } }))
        assertFalse(gate.observe(Iap2Frame(0x5702,byteArrayOf(1))))
        assertTrue("source-backed actual Wi-Fi request",gate.observe(Iap2Frame(0x5702,byteArrayOf())))
        assertTrue(gate.observe(Iap2Messages.buildRaw(0x4300) { group(1) { u8(0,1) } }))
    }
}
