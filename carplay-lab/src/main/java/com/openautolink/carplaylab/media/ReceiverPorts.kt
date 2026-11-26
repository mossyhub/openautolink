// SPDX-License-Identifier: GPL-3.0-only
package com.openautolink.carplaylab.media

import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket

/**
 * Which ports the receiver binds on.
 *
 * With no [plan] every bind is on port 0, as before. A car's built-in hotspot can firewall the
 * head unit down to a short allow list of ports, so the runtime can set a plan for a session on
 * that hotspot. Only sockets bound to [Plan.local] follow it; wired and shared Wi-Fi sessions use
 * other addresses and are untouched. When every planned port is taken a socket falls back to
 * port 0.
 */
object ReceiverPorts {
    /**
     * [control] takes the iPhone's first connection, [pool] serves every other listening socket
     * and datagram socket, [audio] serves the audio datagram sockets (a firewall can filter UDP on
     * a different list than TCP). With [audioOverBluetooth] the receiver tells the iPhone it plays
     * no sound, so the iPhone keeps audio on the car's Bluetooth.
     */
    class Plan(
        val local: InetAddress,
        val control: Int,
        val pool: List<Int>,
        val audio: List<Int> = pool,
        val audioOverBluetooth: Boolean = false,
    )

    @Volatile var plan: Plan? = null

    /** One status line per planned socket. Must not throw. */
    @Volatile var onStatus: (String) -> Unit = {}

    /** Called when a session starts with its audio on the car's Bluetooth. */
    @Volatile var onBluetoothAudioSession: () -> Unit = {}

    /** True when the session on [local] should send its audio over Bluetooth instead of CarPlay. */
    @JvmStatic
    fun audioOverBluetooth(local: InetAddress?): Boolean {
        val plan = plan ?: return false
        val elsewhere = plan.audioOverBluetooth && local != null && plan.local.address.contentEquals(local.address)
        if (elsewhere) {
            say("CarPlay network stage=audio-route route=bluetooth")
            runCatching { onBluetoothAudioSession() }
        }
        return elsewhere
    }

    /** The ports to try for [role] on [address], in order. Empty means port 0. */
    fun candidates(role: String, address: InetAddress): List<Int> {
        val plan = plan ?: return emptyList()
        if (!plan.local.address.contentEquals(address.address)) return emptyList()
        return when (role) {
            CONTROL -> listOf(plan.control) + plan.pool
            AUDIO -> plan.audio
            else -> plan.pool
        }
    }

    @JvmStatic
    fun bind(socket: ServerSocket, address: InetAddress, backlog: Int, role: String) {
        val wanted = candidates(role, address)
        if (wanted.isNotEmpty()) {
            // A planned port is reused attempt after attempt; the previous attempt's closing
            // connections can still hold it.
            val before = runCatching { socket.reuseAddress }.getOrDefault(true)
            runCatching { socket.reuseAddress = true }
            for (port in wanted) {
                if (runCatching { socket.bind(InetSocketAddress(address, port), backlog) }.isSuccess) {
                    say("CarPlay network stage=port-planned role=$role kind=tcp port=$port")
                    return
                }
            }
            runCatching { socket.reuseAddress = before }
            say("CarPlay network stage=port-planned role=$role kind=tcp port=any reason=planned-ports-taken")
        }
        socket.bind(InetSocketAddress(address, 0), backlog)
    }

    @JvmStatic
    fun server(backlog: Int, address: InetAddress, role: String): ServerSocket {
        val socket = ServerSocket()
        try { bind(socket, address, backlog, role) } catch (failure: Exception) { runCatching { socket.close() }; throw failure }
        return socket
    }

    /**
     * A datagram socket cannot try ports one after another: a failed bind closes it for good, and
     * two sockets that both allow address reuse can land on the same port and take each other's
     * packets. Each planned port is probed with a throwaway exclusive socket first, and the real
     * socket is bound only to a port that probe found free.
     */
    @JvmStatic
    fun bind(socket: DatagramSocket, address: InetAddress, role: String) {
        val wanted = candidates(role, address)
        val port = wanted.firstOrNull { free(address, it) }
        if (port != null) {
            socket.bind(InetSocketAddress(address, port))
            say("CarPlay network stage=port-planned role=$role kind=udp port=$port")
            return
        }
        if (wanted.isNotEmpty()) say("CarPlay network stage=port-planned role=$role kind=udp port=any reason=planned-ports-taken")
        socket.bind(InetSocketAddress(address, 0))
    }

    private fun free(address: InetAddress, port: Int): Boolean = runCatching {
        DatagramSocket(null).use { probe -> probe.reuseAddress = false; probe.bind(InetSocketAddress(address, port)) }
    }.isSuccess

    private fun say(text: String) { runCatching { onStatus(text) } }

    const val CONTROL = "control"
    const val EVENT = "event"
    const val KEEPALIVE = "keepalive"
    const val SCREEN = "screen"
    const val TUNNEL = "tunnel"
    const val TIMING = "timing"
    const val AUDIO = "audio"
}
