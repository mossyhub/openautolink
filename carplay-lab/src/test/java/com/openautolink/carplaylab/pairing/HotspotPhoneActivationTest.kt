// SPDX-License-Identifier: GPL-3.0-only
package com.openautolink.carplaylab.pairing

import com.openautolink.carplaylab.pairing.HotspotPhoneActivation.Protocol
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.net.Inet6Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.util.Collections
import kotlin.concurrent.thread

/** Finding the iPhone on the hotspot and asking it to connect: the wire formats and one real request. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class HotspotPhoneActivationTest {
    private fun name(out: ByteArrayOutputStream, vararg labels: String) {
        labels.forEach { out.write(it.length); out.write(it.toByteArray()) }
        out.write(0)
    }
    private fun u16(out: ByteArrayOutputStream, value: Int) { out.write(value shr 8); out.write(value and 0xff) }

    /** An answer the way an iPhone sends it: a PTR answer, then SRV in the additional records, names compressed. */
    private fun answer(port: Int, withSrv: Boolean = true, service: String = "_carplay-ctrl"): ByteArray = ByteArrayOutputStream().apply {
        u16(this, 0); u16(this, 0x8400); u16(this, 0); u16(this, 1); u16(this, 0); u16(this, if (withSrv) 1 else 0)
        val serviceAt = size()
        name(this, service, "_tcp", "local")
        u16(this, 12); u16(this, 1); u16(this, 0); u16(this, 120)
        val instance = "Phone"
        u16(this, 1 + instance.length + 2)
        val instanceAt = size()
        write(instance.length); write(instance.toByteArray()); write(0xc0); write(serviceAt)
        if (withSrv) {
            write(0xc0); write(instanceAt)
            u16(this, 33); u16(this, 0x8001); u16(this, 0); u16(this, 120)
            u16(this, 6 + 2); u16(this, 0); u16(this, 0); u16(this, port); write(0xc0); write(serviceAt)
        }
    }.toByteArray()

    @Test fun theQueryAsksWhoOffersTheControlServiceOnTheLink() {
        val query = Protocol.query(Protocol.SERVICE, Protocol.TYPE_PTR, unicastReply = false)
        assertEquals(1, query[5].toInt()) // one question
        assertEquals("\u000d_carplay-ctrl\u0004_tcp\u0005local\u0000", String(query, 12, query.size - 16, Charsets.US_ASCII))
        assertEquals(listOf(0, 12, 0, 1), query.takeLast(4).map { it.toInt() and 0xff })
        // From a private port the answer has to come straight back, so the query says so.
        assertEquals(0x80, Protocol.query(Protocol.SERVICE, Protocol.TYPE_PTR, unicastReply = true).let { it[it.size - 2].toInt() and 0xff })
    }

    @Test fun anAnswerGivesThePortAndSurvivesNameCompression() {
        assertEquals(listOf(49152), Protocol.read(answer(49152)).ports)
        // The service is named but not where it listens: the instance comes back for a follow-up question.
        val named = Protocol.read(answer(1, withSrv = false))
        assertTrue(named.ports.isEmpty())
        assertEquals(listOf(listOf("Phone", "_carplay-ctrl", "_tcp", "local")), named.instances)
        // The follow-up is an SRV question for exactly that instance.
        val followUp = Protocol.query(named.instances.single(), Protocol.TYPE_SRV, unicastReply = false)
        assertEquals(33, followUp[followUp.size - 3].toInt())
    }

    @Test fun otherServicesAndBrokenPacketsGiveNothingAndNeverThrow() {
        assertTrue(Protocol.read(answer(7000, service = "_airplay")).ports.isEmpty())
        assertTrue(Protocol.read(ByteArray(0)).ports.isEmpty())
        assertTrue(Protocol.read(ByteArray(12)).ports.isEmpty())
        val good = answer(49152)
        // Cut anywhere, and with a pointer that loops on itself: still an empty answer.
        for (cut in 0 until good.size) Protocol.read(good, cut)
        val loop = good.copyOf().also { it[12] = 0xc0.toByte(); it[13] = 12 }
        assertTrue(Protocol.read(loop).ports.isEmpty())
        assertTrue(Protocol.read(ByteArray(64) { 0xff.toByte() }).ports.isEmpty())
    }

    @Test fun aLinkLocalAddressIsPinnedToTheHotspotInterface() {
        val bytes = InetAddress.getByName("fe80::1").address
        val pinned = Protocol.onInterface(Inet6Address.getByAddress(null, bytes, 0), 7) as Inet6Address
        assertEquals(7, pinned.scopeId)
        assertEquals(7, (Protocol.onInterface(Inet6Address.getByAddress(null, bytes, 7), 7) as Inet6Address).scopeId)
        // Heard on a different interface: it is not on the hotspot.
        assertNull(Protocol.onInterface(Inet6Address.getByAddress(null, bytes, 3), 7))
        val v4 = InetAddress.getByName("192.168.1.20")
        assertEquals(v4, Protocol.onInterface(v4, 7))
    }

    @Test fun onlySendersOnTheHotspotCount() {
        val subnets = listOf(InetAddress.getByName("10.108.224.1") to 24, InetAddress.getByName("fe80::1") to 64)
        assertTrue(Protocol.onLink(InetAddress.getByName("10.108.224.119"), 7, subnets))
        // One of the car's other internal networks, heard on the same shared port.
        assertFalse(Protocol.onLink(InetAddress.getByName("192.168.5.4"), 7, subnets))
        val linkLocal = InetAddress.getByName("fe80::abcd").address
        assertTrue(Protocol.onLink(Inet6Address.getByAddress(null, linkLocal, 7), 7, subnets))
        assertFalse(Protocol.onLink(Inet6Address.getByAddress(null, linkLocal, 3), 7, subnets))
        assertFalse(Protocol.onLink(InetAddress.getByName("10.108.224.119"), 7, emptyList()))
    }

    @Test fun onlyTheCarPlayControlServiceTypeMatches() {
        assertTrue(Protocol.isControl("_carplay-ctrl._tcp"))
        assertTrue(Protocol.isControl("._carplay-ctrl._tcp."))
        assertFalse(Protocol.isControl("_airplay._tcp."))
    }

    @Test fun askingSendsOneRequestAndReportsThePhonesAnswerWithoutAddresses() {
        val seen = Collections.synchronizedList(mutableListOf<String>())
        val notes = Collections.synchronizedList(mutableListOf<String>())
        val loopback = InetAddress.getByName("127.0.0.1")
        ServerSocket(0, 1, loopback).use { server ->
            val phone = thread {
                server.accept().use { socket ->
                    val reader = socket.getInputStream().bufferedReader()
                    while (true) { val line = reader.readLine() ?: break; if (line.isEmpty()) break; seen += line }
                    socket.getOutputStream().apply { write("HTTP/1.1 200 OK\r\n\r\n".toByteArray()); flush() }
                }
            }
            val index = NetworkInterface.getByInetAddress(loopback).index
            HotspotPhoneActivation(RuntimeEnvironment.getApplication(), "AA:BB:CC:DD:EE:FF", loopback, index) { notes += it }.use { activation ->
                assertEquals(200, activation.ask(loopback, server.localPort))
            }
            phone.join(5_000)
        }
        assertEquals("GET /ctrl-int/1/connect HTTP/1.1", seen.first())
        assertTrue("AirPlay-Receiver-Device-ID: AABBCCDDEEFF" in seen)
        // Nothing reported ever carries an address.
        assertTrue(notes.none { "127.0.0.1" in it })
    }
}
