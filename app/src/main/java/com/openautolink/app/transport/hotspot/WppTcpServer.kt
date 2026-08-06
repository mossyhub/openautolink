package com.openautolink.app.transport.hotspot

import android.os.ParcelFileDescriptor
import android.system.Os
import com.openautolink.app.diagnostics.OalLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket

/**
 * TCP **server** for Google's WiFi Projection Protocol (WPP).
 *
 * ## Why this exists
 *
 * OAL's original wireless design has the directions reversed relative to WPP:
 *
 * ```
 *                     OAL (companion)        WPP (Google)
 *   phone             ServerSocket           connects out
 *   head unit         connects out           ServerSocket   <-- this class
 * ```
 *
 * The companion app listens on 5277 and the car dials it. That works fine for
 * OAL's own discovery scheme, but WPP is the opposite: the head unit sends its
 * own `{ip_address, port}` in a `WifiStartRequest` over Bluetooth RFCOMM, and the
 * **phone** then opens the TCP connection to the head unit and wraps it in SSL
 * (`gearhead:WPP-TCP` — `"Starting attempt %d to create raw socket"` →
 * `"Creating SSL wrapped socket"`).
 *
 * Observed directly (2026-08-06, AA 17.4): gearhead accepted our SDP advert, dialled
 * our RFCOMM socket, accepted `WifiStartRequest` with `STATUS_SUCCESS`, parsed our
 * `WifiInfoResponse` and validated the BSSID — then went quiet, because we had
 * advertised a port with nothing bound to it. `/proc/net/tcp` on the head unit
 * showed no listener on 5277.
 *
 * ## Why this matters beyond fixing the test
 *
 * With a listener in place the phone connects **directly to the head unit**. The
 * companion app is not in the wireless path at all — no mDNS, no UDP probe, no
 * identity port, no warm proxy. That removes the entire class of companion-side
 * bridge faults from wireless projection.
 *
 * ## Contract
 *
 * Deliberately mirrors [TcpConnector]: hand a connected [Socket] to
 * [onSocketReady] and let the existing aasdk session path take it from there. The
 * session does not care which side dialled — it needs a connected socket.
 */
class WppTcpServer(
    private val scope: CoroutineScope,
    private val onSocketReady: (Socket) -> Unit,
    /** Port to bind. Must match the port advertised in the WifiStartRequest. */
    private val port: Int = DEFAULT_PORT,
) {
    companion object {
        private const val TAG = "OAL-WppServer"

        /**
         * Default WPP listen port. Deliberately the same 5277 the companion uses
         * so existing setup docs and firewall expectations still hold, but note
         * the roles are inverted here: this binds, the phone connects.
         */
        const val DEFAULT_PORT = 5277

        /** Accept backlog. One phone at a time; a small backlog absorbs retries. */
        private const val BACKLOG = 4
    }

    private var serverSocket: ServerSocket? = null
    private var acceptJob: Job? = null

    @Volatile
    private var isRunning = false

    /** True while a session owns the accepted socket. Guards against duplicates. */
    private val sessionActive = java.util.concurrent.atomic.AtomicBoolean(false)

    /** The address the phone should be told to connect to, once bound. */
    @Volatile
    var boundPort: Int = 0
        private set

    fun start() {
        if (isRunning) return
        isRunning = true
        acceptJob = scope.launch(Dispatchers.IO) { acceptLoop() }
    }

    private suspend fun acceptLoop() {
        try {
            // Bind on all interfaces: in WPP the phone may reach us over the car's
            // AP, the phone's own hotspot, or a shared network, and we do not know
            // which local address it will use until it arrives.
            serverSocket = ServerSocket().apply {
                reuseAddress = true
                bind(InetSocketAddress(port), BACKLOG)
            }
            boundPort = serverSocket?.localPort ?: 0
            OalLog.i(TAG, "WPP TCP server listening on 0.0.0.0:$boundPort — " +
                    "phone will connect here after the Bluetooth handshake")
        } catch (e: Exception) {
            OalLog.e(TAG, "Failed to bind WPP TCP port $port: ${e.message}")
            isRunning = false
            return
        }

        while (isRunning && scope.isActive) {
            val socket = try {
                serverSocket?.accept()
            } catch (e: Exception) {
                if (isRunning) OalLog.w(TAG, "WPP accept() failed: ${e.message}")
                null
            } ?: run {
                // Socket closed under us — nothing to recover to.
                if (isRunning && serverSocket == null) return
                return
            }

            val remote = runCatching { socket.remoteSocketAddress?.toString() ?: "?" }
                .getOrDefault("?")
            OalLog.i(TAG, "Phone connected over WPP from $remote")
            // Log the peer address explicitly: on a flat /23 an open port attracts
            // unrelated LAN traffic, and gearhead may reconnect the phone under a
            // DIFFERENT address than the one adb uses (observed: .1.174 -> .0.35).
            // Never assume an inbound connection is the phone without checking.

            // Keep listening. An earlier revision stopped accepting after the
            // first connection to prevent a competing session — but that made the
            // listener trivially killable: a single stray probe (or gearhead's own
            // retry, which is routine) consumed the slot and tore the server down,
            // so the real connection arrived at a closed port. Instead stay bound
            // and refuse EXTRA connections while one session is live.
            if (!sessionActive.compareAndSet(false, true)) {
                OalLog.w(TAG, "Rejecting extra WPP connection from $remote — session already active")
                runCatching { socket.close() }
                continue
            }

            runCatching {
                socket.tcpNoDelay = true
                socket.keepAlive = true
                // Same aggressive dead-peer detection as the dial-out path: the
                // kernel default (~2h idle) is useless for sleep/wake recovery.
                setKeepAliveParams(socket, idleSec = 5, intervalSec = 2, count = 3)
            }.onFailure {
                OalLog.d(TAG, "TCP tuning unavailable: ${it.message}")
            }

            onSocketReady(socket)
        }
    }

    /**
     * Release the single-session latch so the next incoming connection is accepted.
     * Call when the projection session ends; without it, reconnects are refused.
     */
    fun onSessionEnded() {
        if (sessionActive.compareAndSet(true, false)) {
            OalLog.i(TAG, "WPP session released — ready to accept the next connection")
        }
    }

    /**
     * See TcpConnector.setKeepAliveParams — Java exposes only the on/off switch,
     * so the timing constants need a native setsockopt via a dup'd FD. No hidden
     * APIs; options set on the dup apply to the same kernel socket.
     */
    private fun setKeepAliveParams(socket: Socket, idleSec: Int, intervalSec: Int, count: Int) {
        val IPPROTO_TCP = 6
        val TCP_KEEPIDLE = 4
        val TCP_KEEPINTVL = 5
        val TCP_KEEPCNT = 6
        val pfd = ParcelFileDescriptor.fromSocket(socket)
        try {
            val fd = pfd.fileDescriptor
            Os.setsockoptInt(fd, IPPROTO_TCP, TCP_KEEPIDLE, idleSec)
            Os.setsockoptInt(fd, IPPROTO_TCP, TCP_KEEPINTVL, intervalSec)
            Os.setsockoptInt(fd, IPPROTO_TCP, TCP_KEEPCNT, count)
        } finally {
            pfd.close()
        }
    }

    fun stop() {
        if (!isRunning && serverSocket == null) return
        isRunning = false
        runCatching { serverSocket?.close() }
        serverSocket = null
        acceptJob?.cancel()
        acceptJob = null
        boundPort = 0
        sessionActive.set(false)
        OalLog.i(TAG, "WPP TCP server stopped")
    }
}
