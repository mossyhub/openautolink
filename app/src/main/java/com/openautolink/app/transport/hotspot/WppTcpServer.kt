package com.openautolink.app.transport.hotspot

import android.os.ParcelFileDescriptor
import android.system.Os
import com.openautolink.app.diagnostics.OalLog
import com.openautolink.app.transport.WppInterfacePolicy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
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
 * This is the standards-compatible inbound path and the fallback while the
 * companion endpoint is unknown. OAL's preferred GM path still uses companion
 * discovery and advertises a phone-local loopback proxy; that handshake stops
 * this listener and makes the car dial the companion from the same selected
 * interface because the vehicle AP blocks phone-to-car inbound traffic.
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
    /** Called only after the selected-interface socket has actually bound. */
    private val onBound: () -> Unit = {},
    /** Immutable interface name owned by this WPP session generation. */
    private val bindInterfaceName: String,
    /** Port to bind. Must match the port advertised in the WifiStartRequest. */
    private val port: Int = DEFAULT_PORT,
    private val admissionCheck: () -> Boolean = { true },
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
        private const val BIND_ADDRESS_WAIT_MS = 46_000L
    }

    private enum class LifecycleState { NEW, RUNNING, STOPPED }

    private val lifecycleLock = Any()
    private var serverSocket: ServerSocket? = null
    private var acceptJob: Job? = null
    private val bindOutcome = CompletableDeferred<Boolean>()

    @Volatile
    private var lifecycleState = LifecycleState.NEW

    private fun isRunning(): Boolean = lifecycleState == LifecycleState.RUNNING

    /** True while a session owns the accepted socket. Guards against duplicates. */
    private val sessionActive = java.util.concurrent.atomic.AtomicBoolean(false)

    /** The address the phone should be told to connect to, once bound. */
    @Volatile
    var boundPort: Int = 0
        private set

    fun start() {
        synchronized(lifecycleLock) {
            if (lifecycleState != LifecycleState.NEW || !admissionCheck()) { bindOutcome.complete(false); return }
            lifecycleState = LifecycleState.RUNNING
            acceptJob = scope.launch(Dispatchers.IO) { acceptLoop() }
        }
    }

    /** Positive readiness signal used to prevent SDP publication before bind. */
    suspend fun awaitBound(): Boolean = bindOutcome.await()

    private suspend fun acceptLoop() {
        var uncommittedSocket: ServerSocket? = null
        try {
            val deadline = System.currentTimeMillis() + BIND_ADDRESS_WAIT_MS
            var selectedAddress = WppInterfacePolicy.liveIpv4(bindInterfaceName)
            if (selectedAddress == null) {
                OalLog.i(TAG, "Waiting for selected WPP interface before binding TCP server")
            }
            while (isRunning() && scope.isActive && admissionCheck() && selectedAddress == null &&
                System.currentTimeMillis() < deadline
            ) {
                delay(250)
                selectedAddress = WppInterfacePolicy.liveIpv4(bindInterfaceName)
            }
            if (!admissionCheck()) { stop(); return }
            val bindAddress = selectedAddress
            if (bindAddress == null) {
                OalLog.e(TAG, "Selected WPP interface unavailable — TCP server not bound")
                synchronized(lifecycleLock) {
                    if (lifecycleState == LifecycleState.RUNNING) {
                        lifecycleState = LifecycleState.STOPPED
                    }
                    bindOutcome.complete(false)
                }
                return
            }

            val candidate = ServerSocket()
            uncommittedSocket = candidate
            candidate.reuseAddress = true
            candidate.bind(InetSocketAddress(bindAddress, port), BACKLOG)
            val committed = synchronized(lifecycleLock) {
                if (lifecycleState != LifecycleState.RUNNING || !admissionCheck()) {
                    false
                } else {
                    serverSocket = candidate
                    boundPort = candidate.localPort
                    uncommittedSocket = null
                    true
                }
            }
            if (!committed) {
                runCatching { candidate.close() }
                bindOutcome.complete(false)
                return
            }

            synchronized(lifecycleLock) {
                if (lifecycleState != LifecycleState.RUNNING || serverSocket !== candidate || !admissionCheck()) {
                    bindOutcome.complete(false)
                    stop()
                    return
                }
                OalLog.i(TAG, "WPP selected-interface inbound listener ready on " +
                        "$bindAddress:$boundPort")
                bindOutcome.complete(true)
                runCatching { onBound() }
                    .onFailure { OalLog.w(TAG, "WPP bind-ready callback failed: ${it.message}") }
            }
        } catch (e: Exception) {
            runCatching { uncommittedSocket?.close() }
            OalLog.e(TAG, "Failed to bind WPP TCP port $port: ${e.message}")
            synchronized(lifecycleLock) {
                if (lifecycleState == LifecycleState.RUNNING) {
                    lifecycleState = LifecycleState.STOPPED
                }
                bindOutcome.complete(false)
            }
            return
        }

        while (isRunning() && scope.isActive && admissionCheck()) {
            val listeningSocket = synchronized(lifecycleLock) {
                serverSocket?.takeIf { lifecycleState == LifecycleState.RUNNING }
            } ?: break
            val socket = try {
                listeningSocket.accept()
            } catch (e: Exception) {
                if (isRunning()) OalLog.w(TAG, "WPP accept() failed: ${e.message}")
                null
            } ?: break

            if (!admissionCheck()) { runCatching { socket.close() }; break }
            val remoteHost = runCatching { socket.inetAddress?.hostAddress }.getOrNull()
            val remote = runCatching { socket.remoteSocketAddress?.toString() ?: "?" }
                .getOrDefault("?")
            val bindAddress = listeningSocket.inetAddress?.hostAddress
            if (remoteHost == null || bindAddress == null ||
                !WppInterfacePolicy.isPeerOnSelectedSubnet(bindAddress, remoteHost)
            ) {
                OalLog.w(TAG, "Rejecting WPP connection from $remote — outside selected " +
                        "interface subnet (${bindAddress ?: "unavailable"})")
                runCatching { socket.close() }
                continue
            }
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
        stop()
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
        var socketToClose: ServerSocket? = null
        var jobToCancel: Job? = null
        val transitioned = synchronized(lifecycleLock) {
            if (lifecycleState == LifecycleState.STOPPED) {
                false
            } else {
                lifecycleState = LifecycleState.STOPPED
                socketToClose = serverSocket
                serverSocket = null
                jobToCancel = acceptJob
                acceptJob = null
                boundPort = 0
                sessionActive.set(false)
                bindOutcome.complete(false)
                true
            }
        }
        if (!transitioned) return
        runCatching { socketToClose?.close() }
        jobToCancel?.cancel()
        OalLog.i(TAG, "WPP TCP server stopped")
    }
}
