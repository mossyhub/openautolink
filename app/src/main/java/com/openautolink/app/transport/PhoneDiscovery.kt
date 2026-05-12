package com.openautolink.app.transport

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import com.openautolink.app.diagnostics.OalLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.EnumSet

/**
 * Discovers companion phones on the local network.
 *
 * Two complementary mechanisms run in parallel:
 *
 *  - **mDNS** (passive): listens for [OalProtocol.MDNS_SERVICE_TYPE]
 *    services. Fast, zero-config, surfaces phones with TXT-record identity.
 *    Preferred transport, but can fail on multicast-filtered APs.
 *
 *  - **Subnet sweep** (active, on-demand): TCP-probes every host on the
 *    current /24 on [OalProtocol.IDENTITY_PORT]. Companions reply with
 *    `OAL!{phone_id}\t{friendly_name}\n`. Works on any AP that allows
 *    unicast between clients, even when multicast is filtered.
 *
 * Each [DiscoveredPhone] entry carries a [Source] tag so the UI can show
 * exactly which mechanism found it; phones found by both routes report
 * [Source.BOTH].
 *
 * Thread safety: all merge updates pass through [addOrUpdate] which is
 * synchronized on a private lock. NSD callbacks (main looper) and sweep
 * coroutines (IO dispatcher) can run concurrently without corrupting state.
 */
class PhoneDiscovery private constructor(private val context: Context) {

    companion object {
        private const val TAG = "PhoneDiscovery"
        // Re-exported for convenience; canonical defs live in [OalProtocol].
        const val IDENTITY_PORT = OalProtocol.IDENTITY_PORT
        const val AA_PORT = OalProtocol.AA_PORT

        private const val SWEEP_CONNECT_TIMEOUT_MS = 400
        private const val SWEEP_READ_TIMEOUT_MS = 300
        /**
         * Sockets in flight per chunk. We do one chunk per /24 since the
         * goal is to minimize wall time, and Android's fd limit (1024+)
         * easily handles 254 simultaneous short-lived TCP connects. With
         * this set to ~254 the whole /24 sweep collapses to the per-probe
         * timeout (~400ms) rather than `chunks × timeout`.
         */
        private const val SWEEP_PARALLELISM = 128

        /**
         * Interfaces tried first on the phone-discovery sweep. On GM AAOS
         * head units the phone joins the car's AP via `ap_br_swlan0` (a
         * Linux bridge over the SoftAP NIC). Most cars share this naming.
         * Other entries cover known-real WiFi / Ethernet on non-GM hardware.
         */
        private val PREFERRED_AP_INTERFACES = listOf(
            "ap_br_swlan0", "ap_br_", // GM family
            "swlan0", "swlan",        // some AAOS variants
            "wlan0", "wlan",          // generic WiFi
            "ap0",                    // some OEMs name the AP NIC ap0
        )

        @Volatile
        private var INSTANCE: PhoneDiscovery? = null

        /**
         * Process-wide singleton. Required because Android NSD does not
         * reliably deliver `onServiceFound` callbacks to multiple concurrent
         * registrations of the same service type from the same process; the
         * second listener silently gets no events. Sharing a single instance
         * across the diagnostics screen and the projection screen avoids the
         * "diag scan finds my phone but projection chooser doesn't" trap.
         */
        fun getInstance(context: Context): PhoneDiscovery {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: PhoneDiscovery(context.applicationContext).also { INSTANCE = it }
            }
        }
    }

    enum class Source { MDNS, SWEEP, BOTH }

    /**
     * One discovered companion. [host] / [port] are populated only after
     * mDNS resolution completes — they may be null briefly after [serviceName]
     * first appears.
     */
    data class DiscoveredPhone(
        val serviceName: String,
        val phoneId: String?,
        val friendlyName: String?,
        val host: String?,
        val port: Int,
        val lastSeenMs: Long,
        val source: Source = Source.MDNS,
    ) {
        val isResolved: Boolean get() = host != null && port > 0
    }

    /**
     * Internal tracking entry — uses an [EnumSet] of sources so we can union
     * cleanly without ambiguity, and tracks the original mDNS service name
     * separately from the canonical merge key so [removeMdnsEntryByServiceName]
     * works after a merge.
     */
    private data class Entry(
        val phoneId: String?,
        val friendlyName: String?,
        val host: String?,
        val port: Int,
        val mdnsServiceName: String?,    // null if not seen via mDNS
        val sources: EnumSet<SourceBit>,
        val lastSeenMs: Long,
    ) {
        val displaySource: Source
            get() = when {
                sources.contains(SourceBit.MDNS) && sources.contains(SourceBit.SWEEP) -> Source.BOTH
                sources.contains(SourceBit.MDNS) -> Source.MDNS
                else -> Source.SWEEP
            }

        fun toPublic(): DiscoveredPhone = DiscoveredPhone(
            serviceName = mdnsServiceName ?: host?.let { "ip:$it" } ?: "phone:${phoneId.orEmpty()}",
            phoneId = phoneId,
            friendlyName = friendlyName,
            host = host,
            port = port,
            lastSeenMs = lastSeenMs,
            source = displaySource,
        )
    }

    private enum class SourceBit { MDNS, SWEEP }

    private val _phones = MutableStateFlow<List<DiscoveredPhone>>(emptyList())
    val phones: StateFlow<List<DiscoveredPhone>> = _phones

    private val _isDiscovering = MutableStateFlow(false)
    val isDiscovering: StateFlow<Boolean> = _isDiscovering

    private val _isSweeping = MutableStateFlow(false)
    val isSweeping: StateFlow<Boolean> = _isSweeping

    private val _sweepProgress = MutableStateFlow("")
    val sweepProgress: StateFlow<String> = _sweepProgress

    /**
     * Map keyed by the canonical merge key. All access is synchronized on
     * [lock]; a regular HashMap is sufficient since we never iterate without
     * holding the lock.
     */
    private val byKey = HashMap<String, Entry>()
    private val lock = Any()

    private val sweepScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var sweepJob: Job? = null

    private var nsdManager: NsdManager? = null
    private var discoveryListener: NsdManager.DiscoveryListener? = null

    fun start() {
        if (discoveryListener != null) return
        try {
            nsdManager = context.getSystemService(Context.NSD_SERVICE) as? NsdManager
            if (nsdManager == null) {
                OalLog.w(TAG, "NSD service unavailable")
                return
            }
            val listener = object : NsdManager.DiscoveryListener {
                override fun onDiscoveryStarted(serviceType: String) {
                    OalLog.i(TAG, "Discovery started for $serviceType")
                    _isDiscovering.value = true
                }

                override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                    OalLog.d(TAG, "Service found: ${serviceInfo.serviceName}")
                    addOrUpdate(
                        phoneId = null,
                        friendlyName = null,
                        host = null,
                        port = 0,
                        mdnsServiceName = serviceInfo.serviceName,
                        viaSource = SourceBit.MDNS,
                    )
                    resolve(serviceInfo)
                }

                override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                    OalLog.d(TAG, "Service lost: ${serviceInfo.serviceName}")
                    removeMdnsEntryByServiceName(serviceInfo.serviceName)
                }

                override fun onDiscoveryStopped(serviceType: String) {
                    OalLog.i(TAG, "Discovery stopped")
                    _isDiscovering.value = false
                }

                override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                    OalLog.w(TAG, "Discovery start failed: error $errorCode")
                    _isDiscovering.value = false
                }

                override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                    OalLog.w(TAG, "Discovery stop failed: error $errorCode")
                }
            }
            discoveryListener = listener
            nsdManager?.discoverServices(
                OalProtocol.MDNS_SERVICE_TYPE,
                NsdManager.PROTOCOL_DNS_SD,
                listener,
            )
        } catch (e: Exception) {
            OalLog.w(TAG, "Discovery init failed: ${e.message}")
            _isDiscovering.value = false
        }
    }

    fun stop() {
        try {
            discoveryListener?.let { nsdManager?.stopServiceDiscovery(it) }
        } catch (_: Exception) {}
        discoveryListener = null
        _isDiscovering.value = false
        // Also cancel any in-flight sweep — leaving 254 sockets running with
        // no UI consumer is wasteful.
        stopSweep()
    }

    /** Clear current results. */
    fun clear() {
        synchronized(lock) { byKey.clear() }
        publish()
    }

    private fun resolve(serviceInfo: NsdServiceInfo) {
        // On API 34+ (Android 14+) we can use registerServiceInfoCallback,
        // which surfaces ALL resolved addresses (both A and AAAA records)
        // via NsdServiceInfo.hostAddresses. That lets us pick the IPv4
        // explicitly instead of accepting whatever single address the
        // legacy resolveService() call returns first (often an unusable
        // IPv6 link-local on modern Android phones). Fall back to the
        // legacy path on older platforms.
        if (Build.VERSION.SDK_INT >= 34) {
            resolveAllAddresses(serviceInfo)
        } else {
            resolveLegacy(serviceInfo)
        }
    }

    @androidx.annotation.RequiresApi(34)
    private fun resolveAllAddresses(serviceInfo: NsdServiceInfo) {
        try {
            val mgr = nsdManager ?: return
            // The callback delivers the resolved info repeatedly as records
            // update. We pick the best address on each update and unregister
            // the callback once we have a usable IPv4 host.
            var unregistered = false
            val callback = object : NsdManager.ServiceInfoCallback {
                override fun onServiceInfoCallbackRegistrationFailed(errorCode: Int) {
                    OalLog.d(TAG, "ServiceInfoCallback register failed: $errorCode for ${serviceInfo.serviceName} — falling back to legacy resolve")
                    resolveLegacy(serviceInfo)
                }
                override fun onServiceUpdated(si: NsdServiceInfo) {
                    val addrs = try {
                        @Suppress("DEPRECATION") // hostAddresses is the modern API
                        si.hostAddresses
                    } catch (_: Throwable) { emptyList() }
                    val ipv4 = addrs.filterIsInstance<java.net.Inet4Address>()
                        .firstOrNull { !it.isLoopbackAddress }
                    val anyUsable = ipv4 ?: addrs.firstOrNull {
                        it !is java.net.Inet6Address || !it.isLinkLocalAddress
                    }
                    val host = anyUsable?.hostAddress
                    val port = si.port
                    val attrs = readTxt(si)
                    val phoneId = attrs["phone_id"]
                    val friendlyName = attrs["friendly_name"]
                    OalLog.i(
                        TAG,
                        "ServiceUpdated ${si.serviceName} → $host:$port (addrs=${addrs.size}, ipv4=${ipv4?.hostAddress}) phone_id=${phoneId?.take(8)} name=$friendlyName",
                    )
                    addOrUpdate(
                        phoneId = phoneId,
                        friendlyName = friendlyName,
                        host = host,
                        port = port,
                        mdnsServiceName = si.serviceName,
                        viaSource = SourceBit.MDNS,
                    )
                    // Once we have a usable IPv4, stop watching this service.
                    // Ongoing updates aren't useful and they keep a reference
                    // alive in NsdManager.
                    if (ipv4 != null && !unregistered) {
                        unregistered = true
                        try { mgr.unregisterServiceInfoCallback(this) } catch (_: Throwable) {}
                    }
                }
                override fun onServiceLost() {
                    OalLog.d(TAG, "ServiceInfoCallback lost: ${serviceInfo.serviceName}")
                }
                override fun onServiceInfoCallbackUnregistered() {
                    // expected on cleanup
                }
            }
            mgr.registerServiceInfoCallback(
                serviceInfo,
                java.util.concurrent.Executors.newSingleThreadExecutor(),
                callback,
            )
        } catch (e: Throwable) {
            OalLog.d(TAG, "registerServiceInfoCallback threw, falling back: ${e.message}")
            resolveLegacy(serviceInfo)
        }
    }

    @Suppress("DEPRECATION")
    private fun resolveLegacy(serviceInfo: NsdServiceInfo) {
        try {
            nsdManager?.resolveService(serviceInfo, object : NsdManager.ResolveListener {
                override fun onResolveFailed(si: NsdServiceInfo, errorCode: Int) {
                    OalLog.d(TAG, "Resolve failed for ${si.serviceName}: error $errorCode")
                }

                override fun onServiceResolved(si: NsdServiceInfo) {
                    val rawHost = si.host
                    // IPv6 link-local addresses (fe80::/10) require a scope ID
                    // (e.g. %wlan0) to connect. Android NSD doesn't surface
                    // the scope so the address is unusable. Reject and let
                    // the sweep mechanism find the IPv4 host instead.
                    val host: String? = when {
                        rawHost == null -> null
                        rawHost is java.net.Inet6Address && rawHost.isLinkLocalAddress -> {
                            OalLog.d(TAG, "Ignoring IPv6 link-local from mDNS: ${rawHost.hostAddress}")
                            null
                        }
                        else -> rawHost.hostAddress
                    }
                    val port = si.port
                    val attrs = readTxt(si)
                    val phoneId = attrs["phone_id"]
                    val friendlyName = attrs["friendly_name"]
                    OalLog.i(
                        TAG,
                        "Resolved ${si.serviceName} → $host:$port phone_id=${phoneId?.take(8)} name=$friendlyName",
                    )
                    addOrUpdate(
                        phoneId = phoneId,
                        friendlyName = friendlyName,
                        host = host,
                        port = port,
                        mdnsServiceName = si.serviceName,
                        viaSource = SourceBit.MDNS,
                    )
                }
            })
        } catch (e: Exception) {
            OalLog.d(TAG, "resolveService threw: ${e.message}")
        }
    }

    private fun readTxt(si: NsdServiceInfo): Map<String, String> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) return emptyMap()
        return try {
            si.attributes.mapValues { (_, v) -> v?.toString(Charsets.UTF_8) ?: "" }
        } catch (_: Exception) {
            emptyMap()
        }
    }

    // ── Subnet sweep ────────────────────────────────────────────────────

    fun startSweep() {
        startSweep(forcedInterfaceName = null)
    }

    /**
     * Run a sweep, optionally forced to a specific interface.
     *
     * When [forcedInterfaceName] is non-null/non-blank, only that interface's
     * /24 is scanned (no Phase 2 fallback). Used by Settings when the user
     * disables auto-detection. When null, the normal preferred → fallback
     * two-phase sweep runs.
     */
    fun startSweep(forcedInterfaceName: String?) {
        if (sweepJob?.isActive == true) {
            OalLog.d(TAG, "Sweep already running; ignoring duplicate request")
            return
        }
        val all = currentIpv4Addresses() // already filters out vt*, dummy*, etc.
        if (all.isEmpty()) {
            _sweepProgress.value = "No IPv4 on any interface — sweep skipped"
            OalLog.w(TAG, "Sweep aborted: no IPv4 address on any interface")
            return
        }

        // Manual override path: scan only the user-specified interface, no
        // fallback. If the named interface isn't present, log + abort.
        val forced = forcedInterfaceName?.takeIf { it.isNotBlank() }
        if (forced != null) {
            val match = all.filter { it.iface == forced }
            if (match.isEmpty()) {
                _sweepProgress.value = "Selected interface '$forced' not active"
                OalLog.w(TAG, "Forced sweep aborted: interface '$forced' not present in current IPv4 list")
                return
            }
            OalLog.i(TAG, "Sweep plan (forced): ${match.map { it.iface + "(" + it.ip + ")" }}")
            sweepJob = sweepScope.launch {
                _isSweeping.value = true
                try {
                    runSweepPhase("manual", match)
                } finally {
                    _isSweeping.value = false
                }
            }
            return
        }

        val preferred = all.filter { it.iface.startsWithAny(PREFERRED_AP_INTERFACES) }
        val fallback = all.filter { it !in preferred }
        OalLog.i(
            TAG,
            "Sweep plan: preferred=${preferred.map { it.iface + "(" + it.ip + ")" }} fallback=${fallback.map { it.iface + "(" + it.ip + ")" }}",
        )

        sweepJob = sweepScope.launch {
            _isSweeping.value = true
            try {
                val phase1Found = if (preferred.isNotEmpty()) {
                    runSweepPhase("preferred", preferred)
                } else 0
                if (phase1Found == 0 && fallback.isNotEmpty()) {
                    OalLog.i(TAG, "Phase 1 found nothing; running fallback sweep")
                    runSweepPhase("fallback", fallback)
                }
            } finally {
                _isSweeping.value = false
            }
        }
    }

    /**
     * Public snapshot of currently-up, non-virtual interfaces. Used by the
     * Settings dropdown so the user can pick which interface to scan when
     * auto-detection is disabled.
     */
    fun listRealInterfaces(): List<Pair<String, String>> =
        currentIpv4Addresses().map { it.iface to it.ip }

    /**
     * Run the sweep over [candidates], probing every host on each /24.
     * Returns the count of phones added to the discovery state.
     */
    private suspend fun runSweepPhase(
        label: String,
        candidates: List<IfaceAddress>,
    ): Int = kotlinx.coroutines.coroutineScope {
        val plans = candidates.mapNotNull { c ->
            val parts = c.ip.split('.')
            if (parts.size != 4) return@mapNotNull null
            val prefix = "${parts[0]}.${parts[1]}.${parts[2]}"
            val selfLastOctet = parts[3].toIntOrNull() ?: -1
            Triple(c.iface, prefix, selfLastOctet)
        }.distinctBy { it.second }
        if (plans.isEmpty()) return@coroutineScope 0
        var totalDone = 0
        var totalFound = 0
        val totalTargets = plans.size * 254
        plans.forEach { triple ->
            val iface = triple.first
            val prefix = triple.second
            val selfLastOctet = triple.third
            val targets: List<String> = (1..254).filter { it != selfLastOctet }.map { "$prefix.$it" }
            targets.chunked(SWEEP_PARALLELISM).forEach { chunk ->
                val deferreds = chunk.map { ip ->
                    async(Dispatchers.IO) {
                        val ident = probeHost(ip)
                        if (ident != null) {
                            addOrUpdate(
                                phoneId = ident.phoneId,
                                friendlyName = ident.friendlyName,
                                host = ip,
                                port = AA_PORT,
                                mdnsServiceName = null,
                                viaSource = SourceBit.SWEEP,
                            )
                            true
                        } else false
                    }
                }
                val results = deferreds.awaitAll()
                totalDone += chunk.size
                totalFound += results.count { it }
                _sweepProgress.value = "$label sweep $iface… $totalDone/$totalTargets ($totalFound found)"
            }
        }
        _sweepProgress.value = "$label sweep complete: $totalFound phone(s) on ${plans.map { it.second + ".0/24" }}"
        OalLog.i(TAG, "$label sweep complete: $totalFound phone(s)")
        totalFound
    }

    fun stopSweep() {
        sweepJob?.cancel()
        sweepJob = null
        _isSweeping.value = false
    }

    private data class IdentityResult(val phoneId: String?, val friendlyName: String?)

    private suspend fun probeHost(host: String): IdentityResult? = withContext(Dispatchers.IO) {
        withTimeoutOrNull((SWEEP_CONNECT_TIMEOUT_MS + SWEEP_READ_TIMEOUT_MS + 200).toLong()) {
            val socket = Socket()
            try {
                socket.connect(InetSocketAddress(host, IDENTITY_PORT), SWEEP_CONNECT_TIMEOUT_MS)
                socket.soTimeout = SWEEP_READ_TIMEOUT_MS
                val out = socket.getOutputStream()
                out.write(OalProtocol.IDENTITY_PROBE_REQUEST.toByteArray(Charsets.UTF_8))
                out.flush()
                val input = socket.getInputStream()
                val buf = ByteArray(256)
                var read = 0
                while (read < buf.size) {
                    val r = input.read(buf, read, buf.size - read)
                    if (r <= 0) break
                    read += r
                    if (buf[read - 1] == '\n'.code.toByte()) break
                }
                if (read <= 5) return@withTimeoutOrNull null
                val line = String(buf, 0, read, Charsets.UTF_8).trimEnd('\n', '\r')
                if (!line.startsWith(OalProtocol.IDENTITY_PROBE_RESPONSE_PREFIX)) {
                    return@withTimeoutOrNull null
                }
                val payload = line.removePrefix(OalProtocol.IDENTITY_PROBE_RESPONSE_PREFIX)
                val parts = payload.split('\t', limit = 2)
                IdentityResult(
                    phoneId = parts.getOrNull(0)?.takeIf { it.isNotBlank() },
                    friendlyName = parts.getOrNull(1)?.takeIf { it.isNotBlank() },
                )
            } catch (e: Exception) {
                // Most failures are "host unreachable" / timeout — expected
                // for ~250/254 IPs during a sweep, and also expected when
                // the idle warm-cache poller probes a known IP that's
                // currently asleep. Don't log timeouts at all (they are
                // the overwhelmingly common case and pollute the on-device
                // log viewer). Keep other exception classes at debug.
                if (e !is java.net.SocketTimeoutException &&
                    e !is java.net.ConnectException
                ) {
                    OalLog.d(TAG, "probeHost($host) failed: ${e.javaClass.simpleName}: ${e.message}")
                }
                null
            } finally {
                try { socket.close() } catch (_: Exception) {}
            }
        }
    }

    /**
     * Send a single UDP broadcast `OAL?` packet and listen for replies.
     *
     * Useful when mDNS is unavailable (AAOS 12/13 NSD often returns only
     * IPv6 link-local) but the AP still allows broadcast between clients.
     * Each running companion responds once with the same identity payload
     * the TCP probe returns. Sub-50ms typical wall time, so this layer
     * sits between mDNS and the /24 sweep in the connect pipeline.
     *
     * Source IPs of replies become the discovered hosts — no DHCP scope
     * assumption, no IP cache. Caller is expected to call this on each
     * usable broadcast interface (sweep already enumerates them via
     * [currentIpv4Addresses]).
     *
     * @param broadcastAddress dotted-quad broadcast for the target /24
     *   (e.g. `10.220.23.255`). If null, falls back to `255.255.255.255`
     *   which most APs forward but a few drop.
     * @param listenWindowMs how long to keep the socket open for late
     *   replies. 600ms is generous for any LAN; bump for higher-latency
     *   APs.
     * @return number of distinct phones added to the discovery flow.
     */
    suspend fun udpBroadcast(
        broadcastAddress: String? = null,
        listenWindowMs: Long = 600L,
    ): Int = withContext(Dispatchers.IO) {
        val target = try {
            if (broadcastAddress != null) InetAddress.getByName(broadcastAddress)
            else InetAddress.getByName("255.255.255.255")
        } catch (e: Exception) {
            OalLog.w(TAG, "udpBroadcast: bad target $broadcastAddress: ${e.message}")
            return@withContext 0
        }
        var socket: DatagramSocket? = null
        try {
            socket = DatagramSocket().apply {
                broadcast = true
                soTimeout = 200 // per-receive timeout; loop until window elapses
            }
            val payload = OalProtocol.IDENTITY_PROBE_REQUEST.toByteArray(Charsets.UTF_8)
            val outPacket = DatagramPacket(payload, payload.size, target, OalProtocol.UDP_DISCOVERY_PORT)
            socket.send(outPacket)
            OalLog.d(TAG, "udpBroadcast: sent ${payload.size}B to ${target.hostAddress}:${OalProtocol.UDP_DISCOVERY_PORT}")

            val deadline = System.currentTimeMillis() + listenWindowMs
            val seenIds = HashSet<String>()
            val recvBuf = ByteArray(512)
            while (System.currentTimeMillis() < deadline) {
                val recvPacket = DatagramPacket(recvBuf, recvBuf.size)
                try {
                    socket.receive(recvPacket)
                } catch (_: java.net.SocketTimeoutException) {
                    continue
                }
                val replyHost = recvPacket.address?.hostAddress ?: continue
                val replyText = String(
                    recvPacket.data, recvPacket.offset, recvPacket.length, Charsets.UTF_8,
                ).trimEnd('\n', '\r')
                if (!replyText.startsWith(OalProtocol.IDENTITY_PROBE_RESPONSE_PREFIX)) {
                    continue
                }
                val parts = replyText
                    .removePrefix(OalProtocol.IDENTITY_PROBE_RESPONSE_PREFIX)
                    .split('\t', limit = 2)
                val replyId = parts.getOrNull(0)?.takeIf { it.isNotBlank() } ?: continue
                if (!seenIds.add(replyId)) continue
                OalLog.i(
                    TAG,
                    "udpBroadcast: ${parts.getOrNull(1) ?: replyId.take(8)} @ $replyHost replied",
                )
                addOrUpdate(
                    phoneId = replyId,
                    friendlyName = parts.getOrNull(1)?.takeIf { it.isNotBlank() },
                    host = replyHost,
                    port = AA_PORT,
                    mdnsServiceName = null,
                    // Tag as SWEEP — same fast / direct-connect semantics
                    // as a TCP-probe hit. Source label in the chooser will
                    // read "via sweep"; that's fine, broadcast is just a
                    // faster sweep of the same address space.
                    viaSource = SourceBit.SWEEP,
                )
            }
            seenIds.size
        } catch (e: Exception) {
            OalLog.d(TAG, "udpBroadcast failed: ${e.javaClass.simpleName}: ${e.message}")
            0
        } finally {
            try { socket?.close() } catch (_: Exception) {}
        }
    }

    /**
     * Convenience: derive the broadcast address for a /24 subnet given any
     * IPv4 address inside it. Returns null on bad input.
     */
    private fun broadcastFor24(ip: String): String? {
        val parts = ip.split('.')
        if (parts.size != 4) return null
        return "${parts[0]}.${parts[1]}.${parts[2]}.255"
    }

    /**
     * Run a UDP broadcast on every usable /24 we have a local address on.
     * Mirrors the sweep's interface-enumeration logic but completes in a
     * fraction of the time. Returns total number of phones found across
     * all interfaces.
     */
    suspend fun udpBroadcastAllInterfaces(listenWindowMs: Long = 600L): Int {
        val ifaces = currentIpv4Addresses()
        if (ifaces.isEmpty()) return 0
        // Prefer AP-bridge interfaces first (GM `ap_br_swlan0` etc.) — same
        // ordering rationale as the sweep.
        val preferred = ifaces.filter { it.iface.startsWithAny(PREFERRED_AP_INTERFACES) }
        val rest = ifaces.filter { it !in preferred }
        var found = 0
        for (entry in preferred + rest) {
            val bcast = broadcastFor24(entry.ip) ?: continue
            found += udpBroadcast(broadcastAddress = bcast, listenWindowMs = listenWindowMs)
            if (found > 0 && entry in preferred) {
                // Got hits on a preferred interface; don't bother
                // broadcasting on the fallback ones (USB, etc.).
                break
            }
        }
        return found
    }

    /** (interface name, IPv4 address) pair for sweep planning. */
    private data class IfaceAddress(val iface: String, val ip: String)
    /**
     * Enumerate every plausible non-loopback IPv4 address the device has,
     * paired with its interface name. AAOS head units have lots of virtual
     * interfaces (e.g. `vt0`..`vt250`, `dummy*`, `tun*`, `rmnet*`). Sweeping
     * those /24s is wasted work — the only interfaces the phone could be
     * reachable on are the real WiFi/Ethernet/AP-bridge ones. We blacklist
     * known-virtual prefixes and accept the rest.
     */
    private fun currentIpv4Addresses(): List<IfaceAddress> {
        // Anything starting with one of these prefixes is virtual / not a
        // real LAN we'd find phones on. `vt*` covers GM AAOS's many
        // hypervisor links to the rest of the car's compute fabric.
        val virtualPrefixes = listOf(
            "lo", "dummy", "tun", "tap", "sit", "ip6",
            "gre", "erspan", "ip_vti", "ifb", "hwsim", "rmnet",
            "vt", "veth", "docker", "br-",
        )
        val out = mutableListOf<IfaceAddress>()
        try {
            val ifaces = java.net.NetworkInterface.getNetworkInterfaces() ?: return emptyList()
            for (iface in ifaces) {
                try {
                    if (iface.isLoopback || !iface.isUp) continue
                } catch (_: Exception) { continue }
                val name = iface.name ?: continue
                if (virtualPrefixes.any { name.startsWith(it) }) {
                    OalLog.d(TAG, "Skipping virtual interface: $name")
                    continue
                }
                for (addr in iface.inetAddresses) {
                    if (addr is java.net.Inet4Address && !addr.isLoopbackAddress) {
                        addr.hostAddress?.let {
                            OalLog.d(TAG, "Sweep candidate: $name → $it")
                            out.add(IfaceAddress(name, it))
                        }
                    }
                }
            }
        } catch (e: Exception) {
            OalLog.w(TAG, "currentIpv4Addresses failed: ${e.message}")
        }
        return out
    }

    private fun String.startsWithAny(prefixes: List<String>): Boolean =
        prefixes.any { this.startsWith(it) }

    // ── Internal merge / publish ────────────────────────────────────────

    /**
     * Canonical merge key. Prefer `phone_id` (stable, app-generated UUID).
     * Pre-resolve, fall back to mDNS service name or host IP, but keep both
     * in the same address space so collapse-on-resolve works correctly.
     */
    private fun keyFor(phoneId: String?, mdnsServiceName: String?, host: String?): String {
        if (!phoneId.isNullOrBlank()) return "id:$phoneId"
        if (mdnsServiceName != null) return "name:$mdnsServiceName"
        if (host != null) return "ip:$host"
        return "unknown"
    }

    private fun addOrUpdate(
        phoneId: String?,
        friendlyName: String?,
        host: String?,
        port: Int,
        mdnsServiceName: String?,
        viaSource: SourceBit,
    ) {
        synchronized(lock) {
            val canonicalKey = keyFor(phoneId, mdnsServiceName, host)
            var existing = byKey[canonicalKey]
            // If we just learned the phone_id for an entry that was previously
            // tracked by mdnsServiceName or host, find that pre-id entry and
            // collapse it into the canonical key.
            if (existing == null && !phoneId.isNullOrBlank()) {
                val collapsibleKey = byKey.entries.firstOrNull { (_, e) ->
                    e.phoneId.isNullOrBlank() && (
                        (mdnsServiceName != null && e.mdnsServiceName == mdnsServiceName) ||
                            (host != null && e.host == host)
                        )
                }?.key
                if (collapsibleKey != null) {
                    existing = byKey.remove(collapsibleKey)
                }
            }
            val mergedSources = EnumSet.noneOf(SourceBit::class.java).apply {
                existing?.let { addAll(it.sources) }
                add(viaSource)
            }
            val merged = Entry(
                phoneId = phoneId ?: existing?.phoneId,
                friendlyName = friendlyName ?: existing?.friendlyName,
                host = host ?: existing?.host,
                port = if (port > 0) port else (existing?.port ?: 0),
                mdnsServiceName = mdnsServiceName ?: existing?.mdnsServiceName,
                sources = mergedSources,
                lastSeenMs = System.currentTimeMillis(),
            )
            byKey[canonicalKey] = merged
        }
        publish()
    }

    /**
     * Inject a synthetic resolved phone, sourced as if mDNS had returned it.
     *
     * Intended for emulator integration testing: the AVD's NAT network
     * (10.0.2.0/24) prevents mDNS, UDP broadcast, and /24 sweep from ever
     * surfacing the phone on the host's home WiFi, but outbound TCP to a
     * home-WiFi IP still works (NAT'd through the host). Calling this with
     * the phone's home-WiFi IP makes the discovery flow look exactly like
     * the car: a resolved entry shows up in [phones], the picker renders
     * it, auto-reconnect-on-wake / chooser-auto-close / IP-cache paths
     * exercise normally, and the actual TCP connect lands via the AVD NAT.
     *
     * No-op when [host] is blank. Safe to call repeatedly — addOrUpdate
     * merges by canonical key, so the entry stays sticky across re-injects.
     */
    fun injectDebugPhone(host: String, port: Int = 5277, friendlyName: String = "Test Phone (debug)", phoneId: String = "debug_test_phone") {
        if (host.isBlank()) return
        OalLog.i(TAG, "injectDebugPhone host=$host port=$port name=$friendlyName")
        addOrUpdate(
            phoneId = phoneId,
            friendlyName = friendlyName,
            host = host,
            port = port,
            mdnsServiceName = null,
            viaSource = SourceBit.MDNS,
        )
    }

    private fun removeMdnsEntryByServiceName(serviceName: String) {
        synchronized(lock) {
            val match = byKey.entries.firstOrNull { it.value.mdnsServiceName == serviceName }
                ?: return
            val v = match.value
            if (!v.sources.contains(SourceBit.SWEEP)) {
                byKey.remove(match.key)
            } else {
                // Was BOTH; downgrade to SWEEP-only and forget the mDNS name
                // so a future onServiceFound creates a fresh entry instead of
                // re-merging into this one.
                val updatedSources = EnumSet.copyOf(v.sources).apply { remove(SourceBit.MDNS) }
                byKey[match.key] = v.copy(
                    mdnsServiceName = null,
                    sources = updatedSources,
                )
            }
        }
        publish()
    }

    private fun publish() {
        val snapshot = synchronized(lock) {
            byKey.values.map { it.toPublic() }
        }.sortedBy { it.friendlyName ?: it.serviceName }
        _phones.value = snapshot
    }
}
