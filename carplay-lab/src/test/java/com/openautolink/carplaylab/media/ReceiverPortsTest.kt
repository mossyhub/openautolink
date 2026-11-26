// SPDX-License-Identifier: GPL-3.0-only
package com.openautolink.carplaylab.media

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.ServerSocket
import java.util.Collections

/** Planned ports when a plan fits the socket's address, port 0 otherwise. */
class ReceiverPortsTest {
    private val hotspot = InetAddress.getByName("127.0.0.1")
    private val elsewhere = InetAddress.getByName("127.0.0.2")
    private val said = Collections.synchronizedList(mutableListOf<String>())
    private val open = mutableListOf<AutoCloseable>()
    private fun free(count: Int): List<Int> = List(count) { ServerSocket(0, 1, hotspot) }.map { it.use { socket -> socket.localPort } }
    private fun <T : AutoCloseable> keep(resource: T): T = resource.also { open += it }

    @After fun tearDown() { open.forEach { runCatching { it.close() } }; ReceiverPorts.plan = null; ReceiverPorts.onStatus = {}; ReceiverPorts.onBluetoothAudioSession = {} }

    @Test fun withNoPlanEverySocketBindsAsUpstreamDidOnAnyFreePort() {
        ReceiverPorts.plan = null
        val socket = keep(ServerSocket())
        ReceiverPorts.bind(socket, hotspot, 2, "control")
        assertTrue(socket.localPort > 0)
        assertEquals(emptyList<Int>(), ReceiverPorts.candidates("control", hotspot))
    }

    @Test fun theSessionOpensOnTheControlPortAndTheRestTakeThePoolInOrder() {
        val (control, first, second) = free(3)
        ReceiverPorts.onStatus = { said += it }
        ReceiverPorts.plan = ReceiverPorts.Plan(hotspot, control, listOf(first, second))
        val session = keep(ServerSocket()).also { ReceiverPorts.bind(it, hotspot, 2, "control") }
        val screen = keep(ServerSocket()).also { ReceiverPorts.bind(it, hotspot, 50, "screen") }
        val event = keep(ReceiverPorts.server(50, hotspot, "event"))
        assertEquals(listOf(control, first, second), listOf(session.localPort, screen.localPort, event.localPort))
        // The pool is used up: the next socket still binds, on any free port, and says so.
        val extra = keep(ServerSocket()).also { ReceiverPorts.bind(it, hotspot, 50, "tunnel") }
        assertTrue(extra.localPort !in listOf(control, first, second) && extra.localPort > 0)
        assertEquals(listOf("CarPlay network stage=port-planned role=control kind=tcp port=$control", "CarPlay network stage=port-planned role=screen kind=tcp port=$first",
            "CarPlay network stage=port-planned role=event kind=tcp port=$second", "CarPlay network stage=port-planned role=tunnel kind=tcp port=any reason=planned-ports-taken"), said.toList())
        // Nothing reported carries an address.
        assertTrue(said.none { "127.0.0" in it })
    }

    @Test fun aSecondAttemptGetsTheControlPortBackOnceTheFirstHasClosed() {
        val (control, other) = free(2)
        ReceiverPorts.plan = ReceiverPorts.Plan(hotspot, control, listOf(other))
        ServerSocket().use { ReceiverPorts.bind(it, hotspot, 2, "control"); assertEquals(control, it.localPort) }
        val again = keep(ServerSocket()).also { ReceiverPorts.bind(it, hotspot, 2, "control") }
        assertEquals(control, again.localPort)
    }

    @Test fun socketsOnAnyOtherAddressIgnoreThePlan() {
        val (control, other) = free(2)
        ReceiverPorts.plan = ReceiverPorts.Plan(hotspot, control, listOf(other))
        // Wired CarPlay binds on its own cable network, never on the hotspot address.
        assertEquals(emptyList<Int>(), ReceiverPorts.candidates("control", elsewhere))
        assertEquals(emptyList<Int>(), ReceiverPorts.candidates("screen", InetAddress.getByName("::1")))
    }

    @Test fun datagramSocketsEachGetTheirOwnPlannedPortEvenWhenTheyAllowSharing() {
        val (control, first, second) = free(3)
        ReceiverPorts.onStatus = { said += it }
        ReceiverPorts.plan = ReceiverPorts.Plan(hotspot, control, listOf(first, second))
        // Upstream's timing and keep-alive sockets both allow address reuse. Bound directly they
        // would share one port, as they did in the car on the first try.
        val timing = keep(DatagramSocket(null)).also { it.reuseAddress = true; ReceiverPorts.bind(it, hotspot, "timing") }
        val keepAlive = keep(DatagramSocket(null)).also { it.reuseAddress = true; ReceiverPorts.bind(it, hotspot, "keepalive") }
        assertEquals(listOf(first, second), listOf(timing.localPort, keepAlive.localPort))
    }

    @Test fun aDatagramSocketSurvivesWhenEveryPlannedPortIsTaken() {
        val (control, only) = free(2)
        ReceiverPorts.onStatus = { said += it }
        ReceiverPorts.plan = ReceiverPorts.Plan(hotspot, control, listOf(only))
        keep(DatagramSocket(null)).also { ReceiverPorts.bind(it, hotspot, "timing") }
        // The audio socket refuses sharing. A refused bind would close it and end the session,
        // which is what happened in the car. It must come out open, on some other port.
        val audio = keep(DatagramSocket(null)).also { it.reuseAddress = false; ReceiverPorts.bind(it, hotspot, "audio") }
        assertTrue(!audio.isClosed && audio.isBound)
        assertNotEquals(only, audio.localPort)
        assertEquals("CarPlay network stage=port-planned role=audio kind=udp port=any reason=planned-ports-taken", said.last())
    }

    @Test fun audioGoesToBluetoothOnlyForSessionsOnThePlannedAddressAndOnlyWhenPlanned() {
        val (control, other) = free(2)
        ReceiverPorts.onStatus = { said += it }
        ReceiverPorts.plan = ReceiverPorts.Plan(hotspot, control, listOf(other), audioOverBluetooth = true)
        assertTrue(ReceiverPorts.audioOverBluetooth(hotspot))
        assertEquals("CarPlay network stage=audio-route route=bluetooth", said.last())
        // Wired CarPlay runs on its own network and keeps CarPlay audio.
        assertTrue(!ReceiverPorts.audioOverBluetooth(elsewhere) && !ReceiverPorts.audioOverBluetooth(null))
        ReceiverPorts.plan = ReceiverPorts.Plan(hotspot, control, listOf(other), audioOverBluetooth = false)
        assertTrue(!ReceiverPorts.audioOverBluetooth(hotspot))
        ReceiverPorts.plan = null
        assertTrue(!ReceiverPorts.audioOverBluetooth(hotspot))
    }

    @Test fun audioSocketsUseTheirOwnListAndEverythingElseThePool() {
        val (control, pooled, audible) = free(3)
        ReceiverPorts.plan = ReceiverPorts.Plan(hotspot, control, listOf(pooled), listOf(audible))
        assertEquals(listOf(audible), ReceiverPorts.candidates("audio", hotspot))
        assertEquals(listOf(pooled), ReceiverPorts.candidates("timing", hotspot))
        assertEquals(listOf(control, pooled), ReceiverPorts.candidates("control", hotspot))
        val audio = keep(DatagramSocket(null)).also { ReceiverPorts.bind(it, hotspot, "audio") }
        assertEquals(audible, audio.localPort)
    }

    @Test fun onlyABluetoothAudioSessionOnThePlannedAddressCallsTheHook() {
        var asked = 0
        ReceiverPorts.onBluetoothAudioSession = { asked++ }
        ReceiverPorts.plan = ReceiverPorts.Plan(hotspot, 7000, free(2), audioOverBluetooth = true)
        ReceiverPorts.audioOverBluetooth(elsewhere)
        ReceiverPorts.audioOverBluetooth(null)
        assertEquals(0, asked)
        ReceiverPorts.audioOverBluetooth(hotspot)
        assertEquals(1, asked)
        // A failing hook never stops the session.
        ReceiverPorts.onBluetoothAudioSession = { error("boom") }
        assertTrue(ReceiverPorts.audioOverBluetooth(hotspot))
    }
}
