package com.shilapi.xcertplay.airplay

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import org.junit.Assert.*
import org.junit.Test

class NtpClockReadinessTest {
    @Test fun waitsForMatchedResponsesBeforeReady() {
        val loopback = InetAddress.getByName("127.0.0.1")
        val peer = DatagramSocket(0, loopback).apply { soTimeout = 4000 }
        val clock = NtpClock(loopback)
        val responder = Thread {
            try {
                repeat(2) {
                    val request = DatagramPacket(ByteArray(32), 32)
                    peer.receive(request)
                    val reply = ByteArray(32)
                    reply[0] = 0x80.toByte(); reply[1] = 211.toByte(); reply[3] = 7
                    request.data.copyInto(reply, 8, 24, 32)
                    request.data.copyInto(reply, 16, 24, 32)
                    request.data.copyInto(reply, 24, 24, 32)
                    peer.send(DatagramPacket(reply, 32, request.address, request.port))
                }
            } catch (_: Exception) {}
        }.apply { isDaemon = true }
        try {
            clock.listen()
            assertFalse(clock.awaitSynced(0))
            responder.start(); clock.start(loopback, peer.localPort)
            assertTrue("Matched timing replies must make clock ready", clock.awaitSynced(3500))
            assertTrue(clock.isSynced)
            assertEquals(2, clock.matchedResponses)
        } finally { clock.close(); peer.close(); responder.join(1000) }
    }
    @Test fun missingPeerTimesOutWithoutInventingReadiness() {
        val clock = NtpClock()
        try { clock.listen(); assertFalse(clock.awaitSynced(20)) } finally { clock.close() }
    }
}
