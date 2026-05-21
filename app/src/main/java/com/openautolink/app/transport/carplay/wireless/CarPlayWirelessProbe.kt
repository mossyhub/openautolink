package com.openautolink.app.transport.carplay.wireless

import android.content.Context
import com.openautolink.app.diagnostics.OalLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.net.Inet4Address
import java.net.NetworkInterface

private const val TAG = "CarPlayWireless"

/**
 * Top-level orchestrator for the **wireless CarPlay probe** — a purely
 * passive listener that proves we can be reached from the iPhone on the
 * CPC200's WiFi subnet.
 *
 * Composes:
 *   - [CarPlayMdnsResponder]: advertises `_carplay._tcp` on [port]
 *   - [CarPlayTcpListener]: accepts TCP connections, logs incoming bytes
 *
 * Used by the "CarPlay Wireless" tab in the Diagnostics screen. Not yet
 * wired into the main session orchestration — that comes in a later PR
 * once we can drive a real iAP2-over-TCP session.
 */
class CarPlayWirelessProbe(
    private val context: Context,
    private val port: Int = 5000,
) {
    val mdns = CarPlayMdnsResponder(context, port = port)
    val tcp  = CarPlayTcpListener(port = port)
    val announcer = CarPlayAnnouncer(
        tcpPort = port,
        version = runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "unknown"
        }.getOrDefault("unknown"),
    )

    val tcpEvents: SharedFlow<CarPlayTcpListener.Event> get() = tcp.events
    val mdnsState: StateFlow<CarPlayMdnsResponder.State>  get() = mdns.state
    val tcpState:  StateFlow<CarPlayTcpListener.State>   get() = tcp.state

    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running.asStateFlow()

    fun start() {
        if (_running.value) {
            OalLog.i(TAG, "start() called but already running")
            return
        }
        OalLog.i(TAG, "starting wireless probe on port $port")
        tcp.start()
        mdns.register()
        announcer.start()
        _running.value = true
    }

    fun stop() {
        if (!_running.value) return
        OalLog.i(TAG, "stopping wireless probe")
        mdns.unregister()
        tcp.stop()
        announcer.stop()
        _running.value = false
    }

    /**
     * Returns the first non-loopback IPv4 address visible on any
     * interface. Used to show the user where the listener is reachable.
     */
    fun localIp4Addresses(): List<String> {
        val out = mutableListOf<String>()
        try {
            val ifaces = NetworkInterface.getNetworkInterfaces() ?: return emptyList()
            for (iface in ifaces) {
                if (!iface.isUp || iface.isLoopback) continue
                for (addr in iface.inetAddresses) {
                    if (addr is Inet4Address && !addr.isLinkLocalAddress) {
                        out.add("${iface.name}: ${addr.hostAddress}")
                    }
                }
            }
        } catch (e: Exception) {
            OalLog.e(TAG, "localIp4Addresses failed: ${e.message}")
        }
        return out
    }
}
