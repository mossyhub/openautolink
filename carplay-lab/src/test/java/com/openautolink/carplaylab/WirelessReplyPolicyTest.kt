package com.openautolink.carplaylab

import org.junit.Assert.*
import org.junit.Test

class WirelessReplyPolicyTest {
    @Test fun wifiExhaustionCannotSuppressCarplayAvailability() {
        val policy = WirelessReplyPolicy()
        repeat(20) { policy.allow(0x5702) }
        repeat(20) { assertTrue(policy.allow(0x4300)) }
    }
    @Test fun wifiBudgetsAreSeparateBeforeAndAfterTransportNotification() {
        val policy = WirelessReplyPolicy()
        repeat(5) { assertTrue(policy.allow(0x5702)) }
        assertFalse(policy.allow(0x5702))
        assertTrue(policy.allow(0x4e0e))
        assertTrue(policy.allow(0x5702))
        assertFalse(policy.allow(0x5702))
        assertFalse(policy.allow(0x4e0e))
        assertTrue(policy.allow(0x4300))
        assertFalse(policy.allow(0x9999))
    }
    @Test fun failedChannelCloseStillClosesTheSession() {
        var closed = false
        val expected = java.io.IOException("synthetic test failure")
        try {
            closeRuntime({ throw expected }, { closed = true })
            fail("Expected channel failure")
        } catch (failure: java.io.IOException) {
            assertSame(expected, failure)
        }
        assertTrue("AirPlay session must retire even when channel.close throws", closed)
    }
}
