package com.openautolink.app.transport.carplay.wireless

import com.openautolink.app.diagnostics.OalLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface

private const val TAG = "CarPlayAnnouncer"

/**
 * Broadcasts the AAOS app's current IPv4 address + CarPlay TCP port on a
 * UDP datagram every [intervalMs] ms. The CPC200 broker listens with
 * `--target auto` and uses the payload to learn where to relay iPhone
 * CarPlay sessions — solves GM car AP's random-subnet-per-boot problem.
 *
 * Wire format (must match bridge/cpc200/oal-cp-broker/src/udp_discover.c):
 *   "OAL-CP-ANNOUNCE v=<ver> ip=<dotted> port=<n>\n"
 * Sent to 255.255.255.255:5278 from every non-virtual IPv4 interface.
 */
class CarPlayAnnouncer(
    private val tcpPort: Int = 5000,
    private val intervalMs: Long = 2_000L,
    private val version: String = "unknown",
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null

    fun start() {
        if (job?.isActive == true) {
            OalLog.i(TAG, "already running")
            return
        }
        OalLog.i(TAG, "starting CarPlay UDP announces on :$ANNOUNCE_PORT every ${intervalMs}ms")
        job = scope.launch { runLoop() }
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    private suspend fun runLoop() {
        while (scope.isActive) {
            try {
                broadcastOnce()
            } catch (e: Exception) {
                OalLog.w(TAG, "announce iteration failed: ${e.message}")
            }
            delay(intervalMs)
        }
    }

    private fun broadcastOnce() {
        val ifaces = currentIpv4Addresses()
        if (ifaces.isEmpty()) return

        for ((ifaceName, ip) in ifaces) {
            val bcast = broadcastFor24(ip) ?: continue
            val payload = ("$MAGIC v=$version ip=$ip port=$tcpPort\n").toByteArray()
            var sock: DatagramSocket? = null
            try {
                sock = DatagramSocket().apply { broadcast = true }
                val pkt = DatagramPacket(
                    payload, payload.size,
                    InetAddress.getByName(bcast), ANNOUNCE_PORT
                )
                sock.send(pkt)
                OalLog.d(TAG, "announce $ifaceName -> $bcast:$ANNOUNCE_PORT  ip=$ip port=$tcpPort")
            } catch (e: Exception) {
                OalLog.d(TAG, "announce $ifaceName/$ip failed: ${e.message}")
            } finally {
                try { sock?.close() } catch (_: Exception) {}
            }
        }
    }

    private data class IfaceAddress(val iface: String, val ip: String)

    private fun currentIpv4Addresses(): List<IfaceAddress> {
        val virtualPrefixes = listOf(
            "lo", "dummy", "tun", "tap", "sit", "ip6",
            "gre", "erspan", "ip_vti", "ifb", "hwsim", "rmnet",
            "vt", "veth", "docker", "br-",
        )
        val out = mutableListOf<IfaceAddress>()
        try {
            val ifaces = NetworkInterface.getNetworkInterfaces() ?: return emptyList()
            for (iface in ifaces) {
                try { if (iface.isLoopback || !iface.isUp) continue } catch (_: Exception) { continue }
                val name = iface.name ?: continue
                if (virtualPrefixes.any { name.startsWith(it) }) continue
                for (addr in iface.inetAddresses) {
                    if (addr is Inet4Address && !addr.isLoopbackAddress) {
                        addr.hostAddress?.let { out.add(IfaceAddress(name, it)) }
                    }
                }
            }
        } catch (_: Exception) {}
        return out
    }

    /** "192.168.1.50" -> "192.168.1.255". Returns null on malformed input. */
    private fun broadcastFor24(ip: String): String? {
        val parts = ip.split('.')
        if (parts.size != 4) return null
        return "${parts[0]}.${parts[1]}.${parts[2]}.255"
    }

    companion object {
        const val ANNOUNCE_PORT = 5278
        const val MAGIC = "OAL-CP-ANNOUNCE"
    }
}
