package com.openautolink.companion.service

import android.content.Context
import android.content.Intent
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import com.openautolink.companion.connection.AaProxy
import com.openautolink.companion.diagnostics.CompanionLog
import com.openautolink.companion.trigger.TransparentTriggerActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket

/**
 * TCP transport for car ←→ phone AA connection.
 *
 * Listens on [PORT] for incoming TCP connections from the car app.
 * Also registers an mDNS/NSD service so the car can discover the phone
 * on any shared network (hotspot or home WiFi).
 */
class TcpAdvertiser(
    private val context: Context,
    private val stateListener: StateListener,
) {
    /** Lifecycle callbacks for the AA proxy bridge. */
    interface StateListener {
        fun onConnecting()
        fun onProxyConnected()
        fun onProxyDisconnected()
        fun onLaunchTimeout()
    }

    companion object {
        private const val TAG = "OAL_TcpAdv"
        const val PORT = 5277
        /**
         * Dedicated single-purpose port for the identity probe. The companion
         * answers `OAL?\n` with `OAL!{phone_id}\t{friendly_name}\n`. Used by
         * the car's subnet sweep + last-known-IP fallback when mDNS is
         * unavailable. Kept separate from [PORT] so we never risk consuming
         * bytes from a real AA stream.
         */
        const val IDENTITY_PORT = 5278
        /**
         * UDP discovery port. The car broadcasts a single `OAL?\n` packet to
         * `255.255.255.255:UDP_DISCOVERY_PORT`; we respond once per packet
         * with the same identity payload as the TCP probe. Lets the car
         * find this phone in <50ms when mDNS is broken (AAOS 12/13 NSD
         * IPv6-only) but the AP still allows broadcast between clients.
         */
        const val UDP_DISCOVERY_PORT = 5279
        const val NSD_SERVICE_TYPE = "_openautolink._tcp"
        const val NSD_SERVICE_NAME = "OpenAutoLink"

        /** How long we wait for Google AA to connect to our proxy before retrying. */
        private const val AA_CONNECT_TIMEOUT_MS = 8_000L
        /** Re-fire the AA launch intent up to this many times before resetting the car socket. */
        private const val MAX_AA_LAUNCH_RETRIES = 3
        /** Times to retry port bind on EADDRINUSE before giving up. */
        private const val BIND_RETRY_MAX = 10
        /** Delay between bind retries (ms). 10 retries × 300ms = 3s max wait. */
        private const val BIND_RETRY_DELAY_MS = 300L
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var serverSocket: ServerSocket? = null
    private var identityServerSocket: ServerSocket? = null
    private var udpDiscoverySocket: java.net.DatagramSocket? = null
    private var activeProxy: AaProxy? = null
    private var activeCarSocket: Socket? = null
    private var nsdManager: NsdManager? = null
    private var nsdRegistrationListener: NsdManager.RegistrationListener? = null
    private var aaConnectWatchdog: Job? = null

    @Volatile
    private var isRunning = false

    @Volatile
    private var isLaunching = false

    /** Number of times we've re-launched AA without it connecting back to the proxy. */
    @Volatile
    private var aaLaunchAttempts = 0

    fun start() {
        if (isRunning) return
        isRunning = true
        CompanionLog.i(TAG, "Starting TCP server on port $PORT")

        scope.launch {
            try {
                // Retry bind briefly to handle EADDRINUSE race when the service
                // is restarted quickly (e.g. BT reconnect triggers start within
                // milliseconds of the previous stop). The OS may not have released
                // the port yet even though we called serverSocket.close().
                val server = ServerSocket()
                server.reuseAddress = true
                var bindAttempt = 0
                while (true) {
                    try {
                        server.bind(InetSocketAddress(PORT))
                        break
                    } catch (e: java.net.BindException) {
                        bindAttempt++
                        if (bindAttempt >= BIND_RETRY_MAX) throw e
                        CompanionLog.w(TAG, "Port $PORT in use, retrying in ${BIND_RETRY_DELAY_MS}ms (attempt $bindAttempt/$BIND_RETRY_MAX)")
                        delay(BIND_RETRY_DELAY_MS)
                    }
                }
                serverSocket = server
                CompanionLog.i(TAG, "Listening on 0.0.0.0:$PORT")

                registerNsd()
                startIdentityServer()
                startUdpDiscoveryServer()

                while (isRunning) {
                    val carSocket = server.accept()
                    val remoteIp = carSocket.inetAddress?.hostAddress ?: "unknown"
                    CompanionLog.i(TAG, "Car connected from $remoteIp")
                    stateListener.onConnecting()
                    handleCarConnection(carSocket)
                }
            } catch (e: Exception) {
                if (isRunning) {
                    CompanionLog.e(TAG, "TCP server error: ${e.message}")
                }
            }
        }
    }

    /**
     * Spin up the local AA proxy and broadcast the AA-launch intent BEFORE
     * any car has connected. Used on car-presence signals (BT ACL_CONNECTED
     * to the head unit, etc.) so Android Auto's ~10s cold-start happens
     * while the car is still booting + joining WiFi — by the time the car
     * actually TCP-connects, AA is already warm and the bridge lights up
     * in milliseconds.
     *
     * Idempotent. If a proxy is already running and idle, re-fires the
     * launch intent (in case AA missed the previous one). If a bridge is
     * already active, no-op.
     */
    fun preWarmAaPipeline() {
        if (!isRunning) {
            CompanionLog.w(TAG, "preWarmAaPipeline ignored — TcpAdvertiser not running")
            return
        }
        val existing = activeProxy
        if (existing != null) {
            if (existing.hasActiveBridge()) {
                CompanionLog.i(TAG, "preWarmAaPipeline: bridge already active, skipping")
                return
            }
            CompanionLog.i(TAG, "preWarmAaPipeline: re-firing AA launch on existing warm proxy ${existing.localPort}")
            fireAaLaunchIntent(existing.localPort)
            return
        }
        CompanionLog.i(TAG, "preWarmAaPipeline: creating warm proxy (no car socket yet)")
        scope.launch {
            try {
                val proxy = AaProxy(
                    preConnectedSocket = null,
                    listener = object : AaProxy.Listener {
                        override fun onConnected() {
                            CompanionLog.i(TAG, "AA flowing through warm proxy")
                            aaConnectWatchdog?.cancel()
                            aaConnectWatchdog = null
                            aaLaunchAttempts = 0
                            stateListener.onProxyConnected()
                        }
                        override fun onDisconnected(unexpected: Boolean) {
                            CompanionLog.i(TAG, "Warm proxy disconnected (unexpected=$unexpected)")
                            stateListener.onProxyDisconnected()
                            isLaunching = false
                        }
                    },
                )
                activeProxy = proxy
                isLaunching = true
                val localPort = proxy.start()
                fireAaLaunchIntent(localPort)
                // No car-socket watchdog yet — that's started by
                // handleCarConnection when the car arrives.
            } catch (e: Exception) {
                CompanionLog.e(TAG, "preWarmAaPipeline failed: ${e.message}")
                isLaunching = false
            }
        }
    }

    /**
     * Run a tiny dedicated server on [IDENTITY_PORT] that answers identity
     * probes. Each connection: peer sends `OAL?\n`, we reply with
     * `OAL!{phone_id}\t{friendly_name}\n` and close. Single-purpose — never
     * carries AA traffic. Used by the car-side subnet sweep + last-known-IP
     * verification when mDNS is unavailable.
     */
    private fun startIdentityServer() {
        scope.launch {
            try {
                val server = ServerSocket()
                server.reuseAddress = true
                server.bind(InetSocketAddress(IDENTITY_PORT))
                identityServerSocket = server
                CompanionLog.i(TAG, "Identity probe server listening on 0.0.0.0:$IDENTITY_PORT")
                while (isRunning) {
                    val client = server.accept()
                    val remoteIp = client.inetAddress?.hostAddress ?: "unknown"
                    // Bound the entire probe lifecycle (including the write
                    // back) so a tarpitted peer can't pin a coroutine forever.
                    scope.launch {
                        kotlinx.coroutines.withTimeoutOrNull(2_000) {
                            respondIdentityProbe(client, remoteIp)
                        } ?: run {
                            CompanionLog.d(TAG, "Identity probe timed out for $remoteIp")
                            try { client.close() } catch (_: Exception) {}
                        }
                    }
                }
            } catch (e: Exception) {
                if (isRunning) CompanionLog.w(TAG, "Identity server error: ${e.message}")
            }
        }
    }

    private fun respondIdentityProbe(socket: Socket, remoteIp: String) {
        try {
            socket.soTimeout = 1000
            val input = socket.getInputStream()
            val buf = ByteArray(5)
            var read = 0
            while (read < 5) {
                val r = try { input.read(buf, read, 5 - read) } catch (_: Exception) { -1 }
                if (r <= 0) break
                read += r
            }
            val isProbe = read == 5 &&
                buf[0] == 'O'.code.toByte() && buf[1] == 'A'.code.toByte() &&
                buf[2] == 'L'.code.toByte() && buf[3] == '?'.code.toByte() &&
                buf[4] == '\n'.code.toByte()
            if (!isProbe) {
                CompanionLog.d(TAG, "Identity probe from $remoteIp: bad/empty request ($read bytes)")
                return
            }
            val prefs = context.getSharedPreferences(
                com.openautolink.companion.CompanionPrefs.NAME,
                Context.MODE_PRIVATE,
            )
            val phoneId = com.openautolink.companion.CompanionPrefs.getOrCreatePhoneId(prefs)
            val friendlyName = com.openautolink.companion.CompanionPrefs.getFriendlyName(prefs)
            val response = "OAL!$phoneId\t$friendlyName\n".toByteArray(Charsets.UTF_8)
            socket.getOutputStream().apply {
                write(response)
                flush()
            }
            CompanionLog.d(TAG, "Identity probe answered for $remoteIp")
        } catch (e: Exception) {
            CompanionLog.d(TAG, "Identity probe failed for $remoteIp: ${e.message}")
        } finally {
            try { socket.close() } catch (_: Exception) {}
        }
    }

    /**
     * UDP broadcast discovery responder. Listens on
     * [UDP_DISCOVERY_PORT] for `OAL?\n` packets (typically broadcast to
     * `255.255.255.255` by the car), replies once per packet to the source
     * address with the same identity payload as the TCP probe.
     *
     * Sub-50ms round-trip when the AP allows broadcast — fastest path the
     * car has to find this phone, used when mDNS is broken (AAOS 12/13
     * NSD often returns IPv6 link-local only).
     */
    private fun startUdpDiscoveryServer() {
        scope.launch {
            try {
                val socket = java.net.DatagramSocket(null).apply {
                    reuseAddress = true
                    broadcast = true
                    bind(InetSocketAddress(UDP_DISCOVERY_PORT))
                }
                udpDiscoverySocket = socket
                CompanionLog.i(TAG, "UDP discovery server listening on 0.0.0.0:$UDP_DISCOVERY_PORT")
                val recvBuf = ByteArray(64)
                while (isRunning) {
                    val packet = java.net.DatagramPacket(recvBuf, recvBuf.size)
                    try {
                        socket.receive(packet)
                    } catch (e: Exception) {
                        if (!isRunning) break
                        CompanionLog.d(TAG, "UDP receive: ${e.message}")
                        continue
                    }
                    val remoteIp = packet.address?.hostAddress ?: continue
                    val text = String(packet.data, packet.offset, packet.length, Charsets.UTF_8)
                        .trimEnd('\n', '\r')
                    if (text != "OAL?") {
                        CompanionLog.d(TAG, "UDP probe from $remoteIp: bad payload '$text'")
                        continue
                    }
                    val prefs = context.getSharedPreferences(
                        com.openautolink.companion.CompanionPrefs.NAME,
                        Context.MODE_PRIVATE,
                    )
                    val phoneId = com.openautolink.companion.CompanionPrefs.getOrCreatePhoneId(prefs)
                    val friendlyName = com.openautolink.companion.CompanionPrefs.getFriendlyName(prefs)
                    val reply = "OAL!$phoneId\t$friendlyName\n".toByteArray(Charsets.UTF_8)
                    val replyPacket = java.net.DatagramPacket(
                        reply, reply.size, packet.address, packet.port,
                    )
                    try {
                        socket.send(replyPacket)
                        CompanionLog.d(TAG, "UDP probe answered for $remoteIp")
                    } catch (e: Exception) {
                        CompanionLog.d(TAG, "UDP reply to $remoteIp failed: ${e.message}")
                    }
                }
            } catch (e: Exception) {
                if (isRunning) CompanionLog.w(TAG, "UDP discovery server error: ${e.message}")
            }
        }
    }

    private fun handleCarConnection(carSocket: Socket) {
        val proxy = activeProxy
        // If a proxy is already listening and AA hasn't connected yet, reuse it:
        // swap in the new car socket so the next AA connection bridges to the
        // freshest car TCP session. This avoids throwing away a proxy whose port
        // we already broadcast to AA — AA may still be cold-starting and will
        // connect to that same port imminently. This is also the connection
        // point for the pre-warm path: a proxy created by preWarmAaPipeline()
        // has no car socket yet, and lands here when the car finally arrives.
        if (proxy != null && !proxy.hasActiveBridge()) {
            CompanionLog.i(TAG, "Car connection landing on warm proxy on port ${proxy.localPort}")
            activeCarSocket?.let { runCatching { it.close() } }
            activeCarSocket = carSocket
            proxy.updateCarSocket(carSocket)
            // Re-fire the trigger in case AA missed the previous one
            fireAaLaunchIntent(proxy.localPort)
            // Reset the watchdog with the full budget
            startAaConnectWatchdog(carSocket)
            return
        }

        // AA was connected (bridge active) or no proxy exists — start fresh
        activeProxy?.stop()
        activeCarSocket?.let { runCatching { it.close() } }
        activeCarSocket = carSocket
        isLaunching = false
        aaLaunchAttempts = 0

        launchAndroidAuto(carSocket)
    }

    private fun launchAndroidAuto(carSocket: Socket) {
        if (isLaunching) return
        isLaunching = true

        scope.launch {
            try {
                val proxy = AaProxy(
                    preConnectedSocket = carSocket,
                    listener = object : AaProxy.Listener {
                        override fun onConnected() {
                            CompanionLog.i(TAG, "AA flowing through TCP proxy")
                            // AA is alive — cancel the watchdog and reset retry counter.
                            aaConnectWatchdog?.cancel()
                            aaConnectWatchdog = null
                            aaLaunchAttempts = 0
                            stateListener.onProxyConnected()
                        }

                        override fun onDisconnected(unexpected: Boolean) {
                            CompanionLog.i(TAG, "AA TCP proxy disconnected (unexpected=$unexpected)")
                            stateListener.onProxyDisconnected()
                            // Re-accept next connection
                            isLaunching = false
                            // Root-cause recovery for the mid-drive freeze: when
                            // gearhead drops its localhost socket mid-session the
                            // car TCP socket is left half-open and the car sits
                            // frozen until its ~9s ping-timeout. Proactively (a)
                            // close the car socket so the car gets a clean reset
                            // and its single-flight reconnect fires immediately,
                            // and (b) re-fire the AA launch so the phone rebuilds
                            // the bridge as soon as the car reconnects. Only on an
                            // UNEXPECTED break (not an intentional stop / user exit
                            // / car-driven reconnect, which manage the socket
                            // themselves).
                            if (unexpected && isRunning) {
                                CompanionLog.i(TAG,
                                    "Unexpected bridge break — resetting car socket + relaunching AA")
                                activeCarSocket?.let { runCatching { it.close() } }
                                activeCarSocket = null
                                activeProxy?.localPort?.takeIf { it > 0 }?.let { fireAaLaunchIntent(it) }
                            }
                        }
                    },
                )
                activeProxy = proxy
                val localPort = proxy.start()

                fireAaLaunchIntent(localPort)
                startAaConnectWatchdog(carSocket)
            } catch (e: Exception) {
                CompanionLog.e(TAG, "Failed to launch AA: ${e.message}")
                isLaunching = false
                stateListener.onProxyDisconnected()
            }
        }
    }

    private fun fireAaLaunchIntent(localPort: Int) {
        CompanionLog.i(TAG, "Launching AA, proxy port=$localPort")

        // Send the broadcast directly from the service context — this works
        // from background and locked screen without BAL restrictions.
        // The TransparentTriggerActivity path is kept as a supplement for
        // AA versions that need the activity launch instead of the broadcast.
        try {
            val broadcastIntent = Intent().apply {
                setClassName(
                    "com.google.android.projection.gearhead",
                    "com.google.android.apps.auto.wireless.setup.receiver.WirelessStartupReceiver"
                )
                action = "com.google.android.apps.auto.wireless.setup.receiver.wirelessstartup.START"
                putExtra("ip_address", "127.0.0.1")
                putExtra("projection_port", localPort)
                addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
            }
            context.sendBroadcast(broadcastIntent)
            CompanionLog.i(TAG, "AA broadcast sent (port=$localPort)")
        } catch (e: Exception) {
            CompanionLog.w(TAG, "AA broadcast failed: ${e.message}")
        }

        // Also attempt the activity path (works when foregrounded, no-op when locked).
        val aaIntent = Intent().apply {
            setClassName(
                "com.google.android.projection.gearhead",
                "com.google.android.apps.auto.wireless.setup.service.impl.WirelessStartupActivity"
            )
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP
            )
            putExtra("PARAM_HOST_ADDRESS", "127.0.0.1")
            putExtra("PARAM_SERVICE_PORT", localPort)
            putExtra("ip_address", "127.0.0.1")
            putExtra("projection_port", localPort)
        }
        val triggerIntent =
            Intent(context, TransparentTriggerActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
                putExtra("intent", aaIntent)
            }
        try {
            context.startActivity(triggerIntent)
        } catch (e: Exception) {
            CompanionLog.d(TAG, "Activity trigger skipped (locked/background): ${e.message}")
        }
    }

    /**
     * Watchdog that re-fires the AA launch trigger if AA hasn't connected within
     * [AA_CONNECT_TIMEOUT_MS]. After [MAX_AA_LAUNCH_RETRIES] quick retries,
     * closes the car socket so the car reconnects — but keeps the proxy alive
     * on its existing port so that any delayed AA connection still lands.
     * AA can take 60-90s to cold-start; letting the proxy survive across car
     * reconnects means the first successful AA launch connects regardless of
     * which car socket cycle it arrives on.
     */
    private fun startAaConnectWatchdog(carSocket: Socket) {
        aaConnectWatchdog?.cancel()
        aaConnectWatchdog = scope.launch {
            delay(AA_CONNECT_TIMEOUT_MS)
            val proxy = activeProxy
            if (proxy == null || proxy.hasActiveBridge() || !isRunning) return@launch
            aaLaunchAttempts++
            if (aaLaunchAttempts <= MAX_AA_LAUNCH_RETRIES) {
                CompanionLog.w(TAG,
                    "AA didn't connect to proxy in ${AA_CONNECT_TIMEOUT_MS}ms — retry #$aaLaunchAttempts")
                val port = proxy.localPort
                if (port > 0) fireAaLaunchIntent(port)
                startAaConnectWatchdog(carSocket)
            } else {
                // Out of quick retries. Close the car socket so the car reconnects
                // (which will call handleCarConnection → reuse proxy path above).
                // Do NOT stop the proxy — AA may still be starting and will connect
                // to the same proxy port when ready.
                CompanionLog.w(TAG,
                    "AA still not connected after $MAX_AA_LAUNCH_RETRIES retries — " +
                    "cycling car socket, keeping proxy alive on port ${proxy.localPort}")
                aaLaunchAttempts = 0
                runCatching { carSocket.close() }
                // activeCarSocket will be refreshed when the car reconnects.
                // Do not null out activeProxy — we want to reuse it.
            }
        }
    }

    fun stop() {
        CompanionLog.i(TAG, "Stopping TCP server")
        isRunning = false
        aaConnectWatchdog?.cancel()
        aaConnectWatchdog = null
        unregisterNsd()
        activeProxy?.stop()
        activeProxy = null
        activeCarSocket?.let { runCatching { it.close() } }
        activeCarSocket = null
        runCatching { serverSocket?.close() }
        serverSocket = null
        runCatching { identityServerSocket?.close() }
        identityServerSocket = null
        runCatching { udpDiscoverySocket?.close() }
        udpDiscoverySocket = null
        scope.cancel()
    }

    private fun registerNsd() {
        try {
            nsdManager = context.getSystemService(Context.NSD_SERVICE) as? NsdManager
            val prefs = context.getSharedPreferences(
                com.openautolink.companion.CompanionPrefs.NAME,
                Context.MODE_PRIVATE,
            )
            val phoneId = com.openautolink.companion.CompanionPrefs.getOrCreatePhoneId(prefs)
            val friendlyName = com.openautolink.companion.CompanionPrefs.getFriendlyName(prefs)

            // Per-phone unique service name so two phones on the same car AP don't
            // collide. NSD will further disambiguate with " (1)" suffixes if needed.
            val uniqueServiceName = "$NSD_SERVICE_NAME-${phoneId.take(8)}"

            val serviceInfo = NsdServiceInfo().apply {
                serviceName = uniqueServiceName
                serviceType = NSD_SERVICE_TYPE
                port = PORT
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
                    setAttribute("phone_id", phoneId)
                    setAttribute("friendly_name", friendlyName)
                    setAttribute("version", android.os.Build.MODEL ?: "")
                    setAttribute("proto", "1")
                }
            }
            nsdRegistrationListener = object : NsdManager.RegistrationListener {
                override fun onRegistrationFailed(si: NsdServiceInfo, errorCode: Int) {
                    CompanionLog.w(TAG, "mDNS registration failed: error $errorCode")
                }
                override fun onUnregistrationFailed(si: NsdServiceInfo, errorCode: Int) {
                    CompanionLog.w(TAG, "mDNS unregistration failed: error $errorCode")
                }
                override fun onServiceRegistered(si: NsdServiceInfo) {
                    CompanionLog.i(TAG, "mDNS registered: ${si.serviceName} on port $PORT (phone_id=${phoneId.take(8)}, name=$friendlyName)")
                }
                override fun onServiceUnregistered(si: NsdServiceInfo) {
                    CompanionLog.d(TAG, "mDNS unregistered")
                }
            }
            nsdManager?.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, nsdRegistrationListener)
        } catch (e: Exception) {
            CompanionLog.w(TAG, "mDNS registration failed: ${e.message}")
        }
    }

    private fun unregisterNsd() {
        try {
            nsdRegistrationListener?.let { nsdManager?.unregisterService(it) }
        } catch (_: Exception) {}
        nsdRegistrationListener = null
    }
}
