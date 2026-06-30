package com.openautolink.app.transport

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit coverage for [HostUsability] — the connect-path host filter.
 *
 * Regression anchor: issue #48, where the car dialed the phone's global IPv6
 * (`2607:fb91:1ecb:cdb3::9c`) over the hotspot and retried it ~54 times while
 * the reachable IPv4 sat unused, escalating into an AASDK-30 reconnect storm.
 */
class HostUsabilityTest {

    // ── IPv4: usable ────────────────────────────────────────────────────

    @Test
    fun `plain ipv4 is usable`() {
        assertTrue(HostUsability.isUsable("10.218.15.62"))
        assertFalse(HostUsability.isUnusable("10.218.15.62"))
    }

    @Test
    fun `ipv4 with port is usable`() {
        // One colon — must be preserved (ProjectionViewModel appends :port).
        assertTrue(HostUsability.isUsable("10.218.15.109:5277"))
    }

    @Test
    fun `ipv4 hotspot and gateway ranges are usable`() {
        assertTrue(HostUsability.isUsable("192.168.43.1"))
        assertTrue(HostUsability.isUsable("172.23.178.230"))
    }

    // ── IPv6: unusable ──────────────────────────────────────────────────

    @Test
    fun `global ipv6 from issue 48 is unusable`() {
        // The exact address the car dead-dialed 54 times on 2026-06-29.
        assertTrue(HostUsability.isUnusable("2607:fb91:1ecb:cdb3::9c"))
        assertFalse(HostUsability.isUsable("2607:fb91:1ecb:cdb3::9c"))
    }

    @Test
    fun `link-local ipv6 is unusable`() {
        assertTrue(HostUsability.isUnusable("fe80::1"))
        assertTrue(HostUsability.isUnusable("fe80::a00:27ff:fe4e:66a1"))
    }

    @Test
    fun `ipv6 with zone id is unusable`() {
        assertTrue(HostUsability.isUnusable("fe80::1%wlan0"))
    }

    @Test
    fun `bracketed ipv6 with port is unusable`() {
        assertTrue(HostUsability.isUnusable("[2607:fb91::9c]:5277"))
    }

    @Test
    fun `ipv6 loopback and unspecified are unusable`() {
        assertTrue(HostUsability.isUnusable("::1"))
        assertTrue(HostUsability.isUnusable("::"))
    }

    // ── Blank / null ────────────────────────────────────────────────────

    @Test
    fun `null and blank are unusable`() {
        assertTrue(HostUsability.isUnusable(null))
        assertTrue(HostUsability.isUnusable(""))
        assertTrue(HostUsability.isUnusable("   "))
    }
}
