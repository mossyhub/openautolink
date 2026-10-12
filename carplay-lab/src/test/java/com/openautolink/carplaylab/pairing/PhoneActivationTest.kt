// SPDX-License-Identifier: GPL-3.0-only
package com.openautolink.carplaylab.pairing

import org.junit.Assert.*
import org.junit.Test
import java.io.Closeable
import java.net.Inet6Address
import java.net.InetAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class PhoneActivationTest {
    @Test fun requestMatchesUpstreamConnectProbe() {
        assertEquals(
            "GET /ctrl-int/1/connect HTTP/1.1\r\n" +
                "Host: 192.168.1.47:49152\r\n" +
                "User-Agent: AirPlay/1.0\r\n" +
                "AirPlay-Receiver-Device-ID: 02aBcD000001\r\n" +
                "Connection: close\r\n\r\n",
            PhoneActivationProtocol.request("192.168.1.47", 49152, "02:aB:cD:00:00:01"),
        )
    }

    @Test fun ipv6HostHeaderIsBracketedAndScopeIsNotSent() {
        assertTrue(PhoneActivationProtocol.request("fe80::1234%7", 49153, "02:00:00:00:00:01")
            .contains("Host: [fe80::1234]:49153\r\n"))
    }

    @Test fun invalidRequestInputsCannotInjectHeadersOrTriggerDns() {
        for (host in listOf("localhost", "phone.local", "127.1", "192.168.1.999", "1.2.3.4\r\nX: y", "")) {
            rejects { PhoneActivationProtocol.request(host, 1234, "02:00:00:00:00:01") }
        }
        for (port in listOf(0, -1, 65536)) {
            rejects { PhoneActivationProtocol.request("192.168.1.47", port, "02:00:00:00:00:01") }
        }
        for (mac in listOf("", "not-a-mac", "02:00:00:00:00:01\r\nX: y")) {
            rejects { PhoneActivationProtocol.request("192.168.1.47", 1234, mac) }
        }
    }

    @Test fun onlySelectedNumericPeerMatches() {
        val peer = ActivationPeer("192.168.1.47")
        assertTrue(peer.matches(InetAddress.getByAddress(byteArrayOf(192.toByte(), 168.toByte(), 1, 47))))
        assertFalse(peer.matches(InetAddress.getByName("192.168.1.48")))
        assertFalse(peer.matches(InetAddress.getByName("::1")))
        rejects { ActivationPeer("phone.local") }
        rejects { ActivationPeer("0.0.0.0") }
        rejects { ActivationPeer("224.0.0.1") }
    }

    @Test fun ipv6MatchesBytesButNotDifferentScope() {
        val bytes = InetAddress.getByName("fe80::1234").address
        val peer = ActivationPeer("fe80::1234%7")
        assertTrue(peer.matches(Inet6Address.getByAddress(null, bytes, 7)))
        assertFalse(peer.matches(Inet6Address.getByAddress(null, bytes, 8)))
        assertFalse(peer.matches(Inet6Address.getByAddress(null, bytes, 0)))
        assertTrue(ActivationPeer("2001:db8::1").matches(InetAddress.getByName("2001:db8:0:0:0:0:0:1")))
    }

    @Test fun lossRevokesQueuedResolveConnectWriteAndStatusEffects() {
        val fence = ActivationFence()
        val old = fence.claim("service")!!
        var closures = 0
        assertTrue(fence.attach(old, Closeable { closures++ }))
        fence.revoke("service")
        var effects = 0
        repeat(4) { assertFalse(fence.run(old) { effects++ }) }
        assertEquals(0, effects)
        assertEquals(1, closures)
        val successor = fence.claim("service")!!
        assertFalse(fence.run(old) { effects++ })
        assertTrue(fence.run(successor) { effects++ })
        assertEquals(1, effects)
        fence.close()
    }

    @Test fun closeRejectsCallbacksResourcesAndRestartAdmission() {
        val fence = ActivationFence()
        val token = fence.claim("service")!!
        var closures = 0
        val resource = Closeable { closures++ }
        assertTrue(fence.attach(token, resource))
        fence.close()
        fence.close()
        assertEquals(1, closures)
        assertNull(fence.claim("later"))
        assertFalse(fence.run(token) { fail("stale callback") })
        assertFalse(fence.run(null) { fail("late discovery callback") })
        assertFalse(fence.attach(token, Closeable { closures++ }))
        assertEquals(2, closures)
    }

    @Test fun duplicateDiscoveryCannotReplaceLiveToken() {
        val fence = ActivationFence()
        val token = fence.claim("service")!!
        assertNull(fence.claim("service"))
        assertTrue(fence.run(token) {})
        fence.close()
    }

    @Test fun staleQueueRejectionCannotRevokeReplacement() {
        val fence = ActivationFence()
        val old = fence.claim("service")!!
        fence.revoke(old)
        val current = fence.claim("service")!!
        fence.revoke(old)
        assertTrue(fence.run(current) {})
        assertFalse(fence.run(old) {})
        fence.close()
    }

    @Test fun closeSerializesWithInFlightEffectThenClosesResource() {
        val fence = ActivationFence()
        val token = fence.claim("service")!!
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val closing = CountDownLatch(1)
        val closed = CountDownLatch(1)
        val effects = AtomicInteger()
        val closures = AtomicInteger()
        assertTrue(fence.attach(token, Closeable { closures.incrementAndGet() }))
        val writer = Thread {
            fence.run(token) {
                entered.countDown()
                if (release.await(2, TimeUnit.SECONDS)) effects.incrementAndGet()
            }
        }.apply { isDaemon = true; start() }
        val closer = Thread {
            closing.countDown()
            fence.close()
            closed.countDown()
        }.apply { isDaemon = true }
        try {
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            closer.start()
            assertTrue(closing.await(2, TimeUnit.SECONDS))
            assertFalse(closed.await(50, TimeUnit.MILLISECONDS))
            release.countDown()
            assertTrue(closed.await(2, TimeUnit.SECONDS))
            assertEquals(1, effects.get())
            assertEquals(1, closures.get())
            assertFalse(fence.run(token) { effects.incrementAndGet() })
            assertEquals(1, effects.get())
        } finally {
            release.countDown()
            writer.join(2000)
            if (closer.state != Thread.State.NEW) closer.join(2000)
            fence.close()
        }
    }

    @Test fun httpStatusParsingDoesNotExposeReasonOrHeaders() {
        assertEquals(200, PhoneActivationProtocol.statusCode("HTTP/1.1 200 private phone name"))
        assertNull(PhoneActivationProtocol.statusCode("untrusted text"))
        assertNull(PhoneActivationProtocol.statusCode("HTTP/1.1 2000 invalid"))
    }

    private fun rejects(action: () -> Unit) {
        try { action(); fail("invalid input accepted") } catch (_: IllegalArgumentException) { }
    }
}
