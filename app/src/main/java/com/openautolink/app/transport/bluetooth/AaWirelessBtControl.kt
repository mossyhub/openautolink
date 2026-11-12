package com.openautolink.app.transport.bluetooth

import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import com.openautolink.app.diagnostics.OalLog
import com.openautolink.app.transport.OalProtocol
import com.openautolink.app.transport.WppInterfacePolicy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock

/**
 * Process-scoped owner of [AaWirelessBtServer].
 *
 * Lives at Application scope on purpose. The advertiser's entire job is to make the
 * phone *initiate* a projection session, so hanging it off SessionManager — which
 * only exists once a session is already running — would be circular. That mistake
 * cost a test cycle: the DEBUG broadcast was delivered (`result=0`) but nothing
 * listened, because no session had started to register the receiver.
 *
 * Kept opt-in via broadcast while we establish whether gearhead reacts to our SDP
 * record at all. Once proven, the credentials should come from the live network
 * state rather than adb extras.
 *
 * ```
 * # start advertising + set the details handed to the phone
 * adb shell am broadcast -a com.openautolink.app.DEBUG_AAW_BT \
 *   --es ssid Fortress --es psk '<key>' --es bssid aa:bb:cc:dd:ee:ff \
 *   --es ip 192.168.1.100 --ei port 5277
 *
 * # stop
 * adb shell am broadcast -a com.openautolink.app.DEBUG_AAW_BT_STOP
 * ```
 */
object AaWirelessBtControl {

    private const val TAG = "AaWirelessBtControl"
    private const val ACTION_START = "com.openautolink.app.DEBUG_AAW_BT"
    private const val ACTION_STOP = "com.openautolink.app.DEBUG_AAW_BT_STOP"

    /**
     * Set the direct-transport preference. Lives here rather than in
     * SessionManager because SessionManager's debug receiver only registers once
     * a session is running — and switching to "wpp" is a precondition for the
     * session starting at all. Same circular-dependency trap the advertiser hit.
     */
    private const val ACTION_SET_TRANSPORT = "com.openautolink.app.DEBUG_SET_TRANSPORT"

    /** Companion's identity-probe port; it reports its AA proxy port here. */
    private const val IDENTITY_PORT = 5278

    /**
     * How long a phone's session claim blocks other phones.
     *
     * Long enough to cover a normal reconnect, short enough that a claim left
     * behind by a session that ended badly cannot lock the car to a phone that
     * has since left. A phone actively dialling beats a claim older than this.
     */
    private const val CLAIM_TIMEOUT_MS = 120_000L

    /**
     * Remembered addresses to try before scanning.
     *
     * Kept low because MAC randomisation (Android's default) invalidates them
     * regularly, and a failed probe costs 2500ms that the handshake does not
     * have to spare.
     */
    private const val MAX_PRESCAN_PROBES = 2

    /** A handshake older than this has finished, whatever the flag says. */
    private const val HANDSHAKE_MAX_MS = 30_000L

    /** Reserved MACs gearhead rejects outright — see pev.smali validation. */
    private const val ZERO_MAC = "00:00:00:00:00:00"
    private const val BROADCAST_MAC = "ff:ff:ff:ff:ff:ff"
    private val BSSID_RE = Regex("^([0-9a-fA-F]{2}:){5}[0-9a-fA-F]{2}$")


    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var btServer: AaWirelessBtServer? = null

    /** Only the current WPP protocol owner may make the phone act on SDP. */
    private val sessionAdmission = WppSessionAdmission()

    /** Cable-lifetime fence, independent of CarPlay reservation (Android USB needs it too). */
    class UsbHandoffToken internal constructor(val generation: Long) {
        internal var advertiserRetired = false
    }
    @Volatile private var usbHandoff: UsbHandoffToken? = null
    private val advertiserRetirementMutex = kotlinx.coroutines.sync.Mutex()
    private fun wirelessAdmissionBlocked() = com.shilapi.xcertplay.media.MicrophoneCaptureFence.projectionPaused() || usbHandoff != null ||
        com.openautolink.app.carplay.CarPlayOwnership.process.blocksAndroidAuto

    @Synchronized fun reserveUsbHandoff(generation: Long): UsbHandoffToken =
        UsbHandoffToken(generation).also { usbHandoff = it }

    /** Caller has also awaited SessionManager and any CarPlay owner, before restoring normal mode. */
    @Synchronized fun releaseUsbHandoff(token: UsbHandoffToken): Boolean {
        if (usbHandoff !== token || !token.advertiserRetired) return false
        usbHandoff = null
        return true
    }

    /**
     * Last IPv4 we saw the companion on, used to ask it for its proxy port.
     *
     * Set by whoever discovers or connects to the phone. Null until then, in
     * which case we advertise the car's own address.
     */
    /**
     * Invoked with the companion's address the moment the handshake decides to
     * advertise its proxy, so the session can dial it before Android Auto is told
     * to connect.
     */
    @Volatile
    var onCompanionSelected: ((String) -> Unit)? = null

    @Volatile
    var lastKnownPhoneIp: String? = null
        set(value) {
            field = value
            if (value != null) {
                synchronized(recentPhoneIps) {
                    recentPhoneIps.remove(value)
                    recentPhoneIps.add(0, value)
                    while (recentPhoneIps.size > 4) recentPhoneIps.removeAt(recentPhoneIps.size - 1)
                }
                persistKnownIps()
            }
        }

    /**
     * Recently-seen phone addresses, newest first.
     *
     * A phone's address changes for two reasons: it moved between networks (the
     * car's access point vs its own hotspot vs home WiFi), and — more often than
     * I first assumed — Android randomises the WiFi MAC per network by default,
     * which produces a new DHCP lease. The development phone happens to be set to
     * use its real MAC for the car's network, which is why its address looked
     * perfectly stable across four days of logs; that is the exception, not the
     * rule.
     *
     * So this is a hint, not a source of truth. Probing one or two is a cheap bet
     * against a ~5s scan; the scan is what actually has to work.
     */
    private val recentPhoneIps = mutableListOf<String>()

    /**
     * Persist the addresses so a restart does not start blind.
     *
     * These lived only in memory, so a fresh install or an app restart had
     * nothing to try and fell straight through to a ~5s subnet scan — logged as
     * "knownAddrs=none" on the run where a freshly-installed 0.1.413 failed to
     * reconnect. The car AP and the phone's DHCP lease have been stable across
     * every measured session, so a remembered address is worth keeping.
     */
    private fun persistKnownIps() {
        val ctx = appContext ?: return
        val ips = (listOfNotNull(lastKnownPhoneIp) +
                synchronized(recentPhoneIps) { recentPhoneIps.toList() }).distinct()
        scope.launch {
            runCatching {
                com.openautolink.app.data.AppPreferences.getInstance(ctx)
                    .setKnownPhoneIps(ips.joinToString(","))
            }
        }
    }

    /** Reload persisted addresses at startup, before the first handshake. */
    fun restoreKnownIps(context: Context) {
        scope.launch {
            runCatching {
                val saved = com.openautolink.app.data.AppPreferences.getInstance(context)
                    .knownPhoneIps.first()
                    .split(",").map { it.trim() }.filter { it.isNotEmpty() }
                if (saved.isEmpty()) return@runCatching
                synchronized(recentPhoneIps) {
                    saved.forEach { if (it !in recentPhoneIps) recentPhoneIps.add(it) }
                    while (recentPhoneIps.size > 4) recentPhoneIps.removeAt(recentPhoneIps.size - 1)
                }
                if (lastKnownPhoneIp == null) lastKnownPhoneIp = saved.first()
                OalLog.i(TAG, "Restored known phone addresses: ${saved.joinToString()}")
            }
        }
    }


    @Volatile
    private var started = false

    /**
     * Tear down and republish the SDP record so the phone dials back.
     *
     * The phone only initiates the WPP handshake when it sees our service appear,
     * and that handshake is the only mechanism that tells Android Auto where to
     * connect. After a network drop the phone believes it is still set up and
     * never re-dials, so the car must make the record disappear and return.
     */
    /**
     * Rebuild the advertiser after its RFCOMM socket died.
     *
     * Bluetooth going down (ignition off, adapter cycling) kills the listening
     * socket permanently — accept() then fails forever on an object that still
     * exists. The accept loop gives up, and without this the car simply stops
     * advertising: no SDP record, so the phone never dials back and never learns
     * which network to join. Measured: 12 minutes of silence after one ignition
     * cycle, through two manual Bluetooth toggles.
     *
     * Deliberately delayed. Republishing while the adapter is still coming back
     * just produces another dead socket.
     */
    /**
     * Make sure the SDP record is published, without disturbing a healthy one.
     *
     * Called on ignition ON as a safety net: Bluetooth cycles with the car, and
     * the advertiser otherwise only (re)starts when the transport preference
     * changes — so a socket killed during the off period stays dead and the car
     * never advertises again.
     */
    /**
     * Tear down the SDP record, as losing the Bluetooth radio would.
     *
     * Used by the ignition-cycle simulation so the advertiser genuinely has to
     * come back, rather than the test quietly skipping the step where every
     * real-world failure has happened.
     */
    /**
     * Set while the ignition-cycle simulation is in its "off" window.
     *
     * The app stays awake during the simulation, so without this the ordinary
     * reconnect logic dials the companion partway through the "off" period and
     * the simulation ends up tearing down a session it just created.
     */
    @Volatile
    var simulatedIgnitionOff = false

    /** Set by the session layer so slow startup phases can be shown on screen. */
    @Volatile
    var onStatus: ((String) -> Unit)? = null

    fun isCurrentSessionOwner(token: WppSessionAdmission.Token): Boolean =
        !wirelessAdmissionBlocked() && token.transportMode == "wpp" && sessionAdmission.isCurrent(token)

    fun hasCurrentWppOwner(): Boolean = sessionAdmission.currentWppOwner() != null

    fun installSessionOwner(
        transportMode: String,
        wppInterfaceName: String? = null,
    ): WppSessionAdmission.Token {
        val token = synchronized(this) {
            check(transportMode != "wpp" || !wirelessAdmissionBlocked()) { "Wireless admission reserved" }
            sessionAdmission.installSession(transportMode, wppInterfaceName)
        }
        OalLog.i(TAG, "Session admission installed: generation=${token.generation} " +
                "transport=${token.transportMode} interface=${token.wppInterfaceName ?: "none"}")
        if (transportMode == "wpp") {
            ensureAdvertising()
        } else {
            stopAdvertisingNow("non-WPP owner", expectedOwner = token)
        }
        return token
    }

    fun clearSessionOwner(token: WppSessionAdmission.Token) {
        val server = synchronized(this) {
            if (!sessionAdmission.clearSession(token)) {
                OalLog.i(TAG, "Ignoring stale session admission clear: generation=${token.generation}")
                return
            }
            val current = btServer
            btServer = null
            current
        }
        server?.stop()
        OalLog.i(TAG, "Session admission cleared and advertiser stopped: " +
                "generation=${token.generation}")
    }

    private fun stopAdvertisingNow(
        reason: String,
        expectedOwner: WppSessionAdmission.Token? = null,
    ): Boolean {
        val server = synchronized(this) {
            if (expectedOwner != null && !sessionAdmission.isCurrent(expectedOwner)) {
                OalLog.i(TAG, "Advertiser stop cancelled ($reason) — session owner changed")
                return false
            }
            val current = btServer
            btServer = null
            current
        }
        server?.stop()
        OalLog.i(TAG, "Advertiser stopped ($reason)")
        return true
    }

    fun stopAdvertising() {
        scope.launch(kotlinx.coroutines.NonCancellable) {
            runCatching {
                stopAdvertisingNow("request")
            }.onFailure { OalLog.w(TAG, "stopAdvertising failed: ${it.message}") }
        }
    }

    fun ensureAdvertising() {
        if (wirelessAdmissionBlocked()) return
        val owner = sessionAdmission.currentWppOwner()
        if (owner == null) {
            OalLog.i(TAG, "Advertiser deferred — no current WPP session owner")
            return
        }
        val ctx = appContext ?: return
        if (btServer?.isRunning == true) {
            OalLog.i(TAG, "Bluetooth advertiser already live — ignition re-arm not needed")
            return
        }
        // Single-flight: ignition ON arrives as 4 -> 5 -> 4 within ~350ms, which
        // fired this three times in one cycle. Each one raced the others into a
        // Bluetooth stack that was still down.
        //
        // Self-expiring, because the `finally` that clears it is not guaranteed
        // to run. Measured 2026-08-10: the flag was set at ignition-off while
        // waiting for the Bluetooth radio, the car then sat parked for 7h56m, and
        // the coroutine died without reaching its finally. On the next ignition
        // ON the compareAndSet below refused — silently, before the first log
        // line — so the advertiser never started, no SDP record was published,
        // and the phone was never told to join the car's network. Eight hours of
        // latch from one skipped cleanup.
        val startedAt = ensureInFlightSince
        if (startedAt != 0L && System.currentTimeMillis() - startedAt > ENSURE_MAX_MS) {
            OalLog.w(TAG, "Advertiser start has been 'in flight' for " +
                    "${(System.currentTimeMillis() - startedAt) / 1000}s — assuming it " +
                    "died and starting a new one")
            ensureInFlight.set(false)
            ensureInFlightSince = 0L
        }
        if (!ensureInFlight.compareAndSet(false, true)) {
            // Say so. This returned silently, which is why an eight-hour failure
            // produced no evidence at all.
            OalLog.i(TAG, "Advertiser start already in flight — not starting another")
            return
        }
        ensureInFlightSince = System.currentTimeMillis()
        OalLog.i(TAG, "No Bluetooth advertiser running — starting one")
        scope.launch {
            try {
                // Same reason as republishAfterSocketDeath: the radio goes down
                // with the ignition and comes back on its own schedule, so wait
                // for it rather than publishing into a disabled adapter.
                if (!awaitBluetoothEnabled()) {
                    OalLog.w(TAG, "Bluetooth never came back — not advertising")
                    return@launch
                }
                if (!sessionAdmission.isCurrent(owner)) {
                    OalLog.i(TAG, "Advertiser start cancelled — session owner changed")
                    return@launch
                }
                if (btServer?.isRunning == true) return@launch
                runCatching { startFromPreferences(ctx, expectedOwner = owner) }
                    .onFailure { OalLog.w(TAG, "ensureAdvertising failed: ${it.message}") }
            } finally {
                val replacementWaiting = !sessionAdmission.isCurrent(owner) &&
                    sessionAdmission.currentWppOwner() != null
                ensureInFlight.set(false)
                ensureInFlightSince = 0L
                if (replacementWaiting) {
                    OalLog.i(TAG, "A replacement WPP owner arrived during advertiser start — retrying")
                    ensureAdvertising()
                }
            }
        }
    }

    private val ensureInFlight = java.util.concurrent.atomic.AtomicBoolean(false)

    /** When the in-flight advertiser start began, so the guard cannot latch. */
    @Volatile
    private var ensureInFlightSince = 0L

    /**
     * Longest a start attempt can plausibly take: the Bluetooth wait is capped at
     * 180s, plus room for the publish itself. Anything past this is a corpse.
     */
    private const val ENSURE_MAX_MS = 240_000L

    /**
     * Polls until the Bluetooth adapter is enabled, or the budget runs out.
     *
     * Deliberately generous: an ignition cycle can leave the radio off for over a
     * minute, and there is no point publishing a service record before then. Note
     * the phone may show "connected" during this window because the OEM stack
     * handles calls and media separately — that says nothing about whether we can
     * publish our own record.
     */
    private suspend fun awaitBluetoothEnabled(timeoutMs: Long = 180_000): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        var waited = false
        while (System.currentTimeMillis() < deadline) {
            @Suppress("DEPRECATION")
            val adapter = android.bluetooth.BluetoothAdapter.getDefaultAdapter()
            if (adapter != null && adapter.isEnabled) {
                if (waited) OalLog.i(TAG, "Bluetooth is back — republishing")
                return true
            }
            if (!waited) {
                OalLog.i(TAG, "Bluetooth is off — waiting for it before advertising")
                waited = true
            }
            kotlinx.coroutines.delay(2_000)
        }
        return false
    }

    /** Identity work overlaps association, never holds up the WPP credential handoff. */
    private fun startBootstrapCompanionDiscovery(
        attempt: BootstrapAttempt,
        candidates: List<String>,
        selectedLocalIpv4: String,
    ) {
        // Arm once, synchronously at the exact endpoint's credential handoff.
        // Repeated/retained callbacks cannot renew this startup opportunity.
        synchronized(this) {
            synchronized(phoneClaimLock) {
                if (attempt.discoveryDeadlineMs != null || !bootstrapStillOwned(attempt) ||
                    android.os.SystemClock.elapsedRealtime() - attempt.createdAtMs >= HANDSHAKE_MAX_MS
                ) return
                attempt.discoveryDeadlineMs = android.os.SystemClock.elapsedRealtime() +
                    WppBootstrapPolicy.DISCOVERY_DEADLINE_MS
            }
        }
        scope.launch {
            val deadline = attempt.discoveryDeadlineMs ?: return@launch
            var scanAttempt = 0
            try {
                while (android.os.SystemClock.elapsedRealtime() < deadline) {
                    if (!bootstrapStillOwned(attempt)) return@launch
                    scanAttempt++
                    var found: ResolvedCompanion? = null
                    for (ip in candidates.take(MAX_PRESCAN_PROBES)) {
                        if (!bootstrapStillOwned(attempt)) return@launch
                        // Socket connect AND read each use this budget.
                        val budget = ((deadline - android.os.SystemClock.elapsedRealtime()) / 2)
                            .coerceIn(1L, 800L).toInt()
                        val probe = askCompanion(ip, connectTimeoutMs = budget)
                        if (probe != null && probeMatchesBluetoothOwner(
                                attempt.phoneBtAddress, attempt.phoneBtName, ip, probe,
                                attempt.expectedPhoneId,
                            )) {
                            found = ResolvedCompanion(ip, probe)
                            break
                        }
                    }
                    if (!bootstrapStillOwned(attempt)) return@launch
                    if (found == null) {
                        val remainingMs = (deadline - android.os.SystemClock.elapsedRealtime())
                            .coerceIn(1L, 5_000L).toInt()
                        found = findCompanionOnSelectedInterface(
                            selectedLocalIpv4 = selectedLocalIpv4,
                            phoneBtAddress = attempt.phoneBtAddress,
                            phoneBtName = attempt.phoneBtName,
                            overallTimeoutMs = remainingMs,
                        )
                    }
                    if (found != null) {
                        val probe = found.probe
                        OalLog.i(TAG, "Bootstrap discovery identity-matched companion at ${found.host} " +
                                "on attempt $scanAttempt (proxy port ${probe.proxyPort})")
                        readvertiseForNewCompanionAddress(
                            host = found.host,
                            reportedProxyPort = probe.proxyPort,
                            expectedAttempt = attempt,
                            reportedPhoneId = probe.phoneId,
                            reportedFriendlyName = probe.friendlyName,
                        )
                        return@launch
                    }
                    kotlinx.coroutines.delay(minOf(750L,
                        (deadline - android.os.SystemClock.elapsedRealtime()).coerceAtLeast(1L)))
                }
                OalLog.w(TAG, "Bootstrap companion startup opportunity expired")
            } finally {
                // A queued legacy compatibility refresh retains the exact attempt
                // until the handshake lease finishes; no successor may be cleared.
                if (pendingLegacyReadvertise.get()?.attempt !== attempt) {
                    bootstrapAttempt.compareAndSet(attempt, null)
                }
            }
        }
    }

    private fun bootstrapStillOwned(attempt: BootstrapAttempt): Boolean =
        bootstrapAttempt.get() === attempt && bootstrapContextStillOwned(attempt)

    private fun bootstrapContextStillOwned(attempt: BootstrapAttempt): Boolean =
        !attempt.controlClosed && isCurrentSessionOwner(attempt.owner) && btServer === attempt.server &&
            attempt.server.isRunning && !simulatedIgnitionOff &&
            selectedWppInterfaceName == attempt.interfaceName &&
            currentSelectedWppIpv4() == attempt.localIpv4 &&
            phoneClaimStillOwned(attempt.phoneBtAddress, attempt.claimGeneration) &&
            sessionIsStreaming?.invoke() != true &&
            (attempt.discoveryDeadlineMs?.let { android.os.SystemClock.elapsedRealtime() < it } != false)

    /** Run a legacy companion re-advertise once the current handshake exits. */
    fun flushPendingReadvertise() {
        val (pending, admissionToken) = synchronized(handshakeStateLock) {
            if (activeHandshakeCount.get() > 0 || handshakeAdmissionBlocked) return
            val candidate = pendingLegacyReadvertise.get() ?: return
            val consumed = synchronized(phoneClaimLock) {
                bootstrapStillOwned(candidate.attempt) &&
                    bootstrapAttempt.compareAndSet(candidate.attempt, null)
            }
            if (!consumed) {
                pendingLegacyReadvertise.compareAndSet(candidate, null)
                return
            }
            pendingLegacyReadvertise.compareAndSet(candidate, null)
            val token = handshakeAdmissionSequence.incrementAndGet()
            handshakeAdmissionBlocked = true
            handshakeAdmissionToken = token
            candidate to token
        }
        lastKnownPhoneIp = pending.host
        lastAddressByPhone[pending.attempt.phoneBtAddress] = pending.host
        OalLog.i(TAG, "Handshakes finished — re-advertising legacy companion " +
                "${pending.host}:${pending.proxyPort}")
        val dispatched = synchronized(this) {
            synchronized(phoneClaimLock) {
                if (bootstrapContextStillOwned(pending.attempt)) {
                    legacyEndpointForHandshake.set(pending)
                    readvertise(admissionToken)
                    true
                } else {
                    false
                }
            }
        }
        // Never acquire handshakeStateLock while holding phoneClaimLock: queue
        // reservation takes those locks in the opposite order.
        if (!dispatched) releaseHandshakeAdmission(admissionToken)
    }

    fun readvertiseForNewCompanionAddress(
        host: String,
        reportedProxyPort: Int? = null,
        reportedPhoneId: String? = null,
        reportedFriendlyName: String? = null,
    ) {
        readvertiseForNewCompanionAddress(
            host = host,
            reportedProxyPort = reportedProxyPort,
            expectedAttempt = null,
            reportedPhoneId = reportedPhoneId,
            reportedFriendlyName = reportedFriendlyName,
        )
    }

    private fun readvertiseForNewCompanionAddress(
        host: String,
        reportedProxyPort: Int? = null,
        expectedAttempt: BootstrapAttempt? = null,
        reportedPhoneId: String? = null,
        reportedFriendlyName: String? = null,
    ) {
        val selectedLocalIpv4 = currentSelectedWppIpv4()
        if (selectedLocalIpv4 == null ||
            !WppInterfacePolicy.isPeerOnSelectedSubnet(selectedLocalIpv4, host)
        ) {
            OalLog.i(TAG, "Rejecting companion at $host — outside selected WPP interface " +
                    "subnet (${selectedLocalIpv4 ?: "unavailable"})")
            return
        }
        val attempt = expectedAttempt ?: bootstrapAttempt.get() ?: return
        if (!bootstrapStillOwned(attempt) || attempt.discoveryDeadlineMs == null) return
        val liveProbe = askCompanion(host, connectTimeoutMs = 800)
        val probe = liveProbe ?: reportedProxyPort?.let { port ->
            CompanionProbe(reportedPhoneId, reportedFriendlyName, port)
        } ?: return
        if (!bootstrapStillOwned(attempt)) return
        if (!probeMatchesBluetoothOwner(
                attempt.phoneBtAddress,
                attempt.phoneBtName,
                host,
                probe,
                attempt.expectedPhoneId,
            )) {
            OalLog.i(TAG, "Ignoring late companion at $host — identity belongs to another phone")
            return
        }
        val proxyPort = probe.proxyPort
        when (WppBootstrapPolicy.onCompanionReachable(
            bootstrapLoopbackPending = true,
            usesReservedProxyPort = proxyPort == attempt.advertisedProxyPort,
            handshakeInFlight = handshakeInFlight,
            sessionStreaming = sessionIsStreaming?.invoke() == true,
        )) {
            LateCompanionAction.DIAL_COMPANION -> {
                // Consume and dial under the same claim lock. A newer Bluetooth
                // claimant cannot overtake this completion between the generation
                // check and the connector replacement.
                val dialled = synchronized(this) { synchronized(phoneClaimLock) {
                    if (!bootstrapStillOwned(attempt) ||
                        !bootstrapAttempt.compareAndSet(attempt, null)
                    ) {
                        false
                    } else {
                        lastKnownPhoneIp = host
                        lastAddressByPhone[attempt.phoneBtAddress] = host
                        activePhoneCompanionIp = host
                        OalLog.i(TAG, "Companion became reachable at $host after AP association — " +
                                "dialling it into advertised loopback port $proxyPort; no second WPP exchange")
                        onCompanionSelected?.invoke(host)
                        true
                    }
                } }
                if (!dialled) {
                    OalLog.i(TAG, "Ignoring late dial from superseded phone claim ${attempt.phoneBtAddress}")
                }
            }
            LateCompanionAction.QUEUE_READVERTISE -> {
                OalLog.i(TAG, "Older companion at $host uses dynamic proxy port $proxyPort; " +
                        "queueing one compatibility re-advertise after this handshake")
                synchronized(handshakeStateLock) {
                    if (!bootstrapStillOwned(attempt)) return
                    pendingLegacyReadvertise.set(
                        PendingLegacyReadvertise(host, proxyPort, attempt),
                    )
                }
                // Close the race where the final handshake ended between the
                // policy read and publishing this queue entry.
                flushPendingReadvertise()
            }
            LateCompanionAction.READVERTISE -> {
                OalLog.i(TAG, "Older companion at $host uses dynamic proxy port $proxyPort — " +
                        "reserving one compatibility re-advertise")
                synchronized(handshakeStateLock) {
                    if (!bootstrapStillOwned(attempt)) return
                    pendingLegacyReadvertise.set(
                        PendingLegacyReadvertise(host, proxyPort, attempt),
                    )
                }
                flushPendingReadvertise()
            }
            LateCompanionAction.IGNORE -> {
                if (sessionIsStreaming?.invoke() == true) {
                    bootstrapAttempt.compareAndSet(attempt, null)
                }
            }
        }
    }

    private data class BootstrapAttempt(
        val phoneBtAddress: String,
        val phoneBtName: String?,
        val expectedPhoneId: String?,
        val claimGeneration: Long,
        val generation: Long,
        val owner: WppSessionAdmission.Token,
        val server: AaWirelessBtServer,
        val interfaceName: String,
        val localIpv4: String,
        val advertisedProxyPort: Int = OalProtocol.WPP_PROXY_PORT,
        val createdAtMs: Long = android.os.SystemClock.elapsedRealtime(),
        @Volatile var discoveryDeadlineMs: Long? = null,
        @Volatile var controlClosed: Boolean = false,
    )

    private val bootstrapAttemptSequence = java.util.concurrent.atomic.AtomicLong(0)
    private val bootstrapAttempt =
        java.util.concurrent.atomic.AtomicReference<BootstrapAttempt?>(null)

    /**
     * Reports whether projection is actually running.
     *
     * Must mean "data is flowing", not "a session object exists" — a session
     * that is merely listening is the stalled state we need to break out of.
     */
    @Volatile
    var sessionIsStreaming: (() -> Boolean)? = null

    /** A legacy dynamic endpoint tied to the exact attempt that discovered it. */
    private data class PendingLegacyReadvertise(
        val host: String,
        val proxyPort: Int,
        val attempt: BootstrapAttempt,
    )

    private val pendingLegacyReadvertise =
        java.util.concurrent.atomic.AtomicReference<PendingLegacyReadvertise?>(null)

    /** One proven dynamic loopback offer for the exact compatibility follow-up. */
    private val legacyEndpointForHandshake =
        java.util.concurrent.atomic.AtomicReference<PendingLegacyReadvertise?>(null)

    private fun releaseHandshakeAdmission(admissionToken: Long?) = synchronized(handshakeStateLock) {
        if (admissionToken != null && handshakeAdmissionToken == admissionToken) {
            handshakeAdmissionBlocked = false
            handshakeAdmissionToken = 0L
        }
    }

    fun readvertise() = readvertise(null)

    private fun readvertise(admissionToken: Long?) {
        val owner = sessionAdmission.currentWppOwner()
        if (owner == null) {
            OalLog.i(TAG, "Re-advertise deferred — no current WPP session owner")
            releaseHandshakeAdmission(admissionToken)
            return
        }
        val ctx = appContext
        if (ctx == null) {
            OalLog.w(TAG, "Cannot re-advertise — no context yet")
            releaseHandshakeAdmission(admissionToken)
            return
        }
        // NonCancellable: this is stop-then-start, and the stop used to cancel
        // the very coroutine performing it, so the restart never ran and nothing
        // said why. The advertiser must come back even if something cancels us
        // mid-sequence.
        scope.launch(kotlinx.coroutines.NonCancellable) {
            try {
                runCatching {
                    if (!sessionAdmission.isCurrent(owner)) {
                        OalLog.i(TAG, "Re-advertise cancelled — session owner changed")
                        return@runCatching
                    }
                    OalLog.i(TAG, "Re-advertising: stopping the current SDP record")
                    if (!stopAdvertisingNow("re-advertise", expectedOwner = owner)) {
                        return@runCatching
                    }
                    kotlinx.coroutines.delay(1_000)
                    startFromPreferences(ctx, expectedOwner = owner)
                    if (btServer?.isRunning == true) {
                        OalLog.i(TAG, "Re-advertise complete — SDP record is live again")
                    } else {
                        OalLog.w(TAG, "Re-advertise ran but no advertiser is running")
                    }
                }.onFailure { OalLog.w(TAG, "Re-advertise failed: ${it.message}") }
            } finally {
                releaseHandshakeAdmission(admissionToken)
            }
        }
    }

    @Volatile
    private var appContext: Context? = null

    /** The address each phone's companion last answered on, keyed by BT MAC. */
    private val lastAddressByPhone = java.util.concurrent.ConcurrentHashMap<String, String>()

    /** Stable companion phone_id learned after a Bluetooth-name identity match. */
    private val phoneIdByBtAddress = java.util.concurrent.ConcurrentHashMap<String, String>()

    private data class ResolvedCompanion(
        val host: String,
        val probe: CompanionProbe,
    )

    /**
     * The phone currently holding the projection session, by BT address.
     *
     * Android Auto is single-session per head unit, so a second phone dialling
     * mid-session must be turned away rather than allowed to take over by
     * accident. Cleared when the session ends so the next phone can claim it.
     */
    /**
     * True from the moment a phone dials the SDP record until its handshake ends.
     *
     * The session owns the listener the phone is about to connect to, so anything
     * that would restart the session must wait. A settings-triggered reconnect
     * landed 150ms into a handshake and tore the listener down underneath it.
     */
    @Volatile
    private var handshakeStartedAtMs: Long = 0L
    private val activeHandshakeCount = java.util.concurrent.atomic.AtomicInteger(0)
    private val handshakeStateLock = Any()
    private var handshakeEpoch = 0L
    private val handshakeAdmissionSequence = java.util.concurrent.atomic.AtomicLong(0)
    @Volatile private var handshakeAdmissionBlocked = false
    @Volatile private var handshakeAdmissionToken = 0L

    /**
     * True while a Bluetooth handshake is genuinely running.
     *
     * Backed by a timestamp rather than a plain flag, because the flag was
     * cleared in a coroutine's `finally` — and stopping the advertiser cancels
     * that coroutine, which can happen before the body is ever dispatched, so
     * the finally never ran and the flag latched on. Everything guarded by it
     * then stopped working silently: discovery found the phone four times after
     * a failed handshake and no re-advertise fired, because this still read true
     * from a handshake that had ended a minute earlier.
     *
     * A handshake takes 0-9s in every successful run, so anything older than 30s
     * is finished regardless of what set it.
     */
    val handshakeInFlight: Boolean
        get() = synchronized(handshakeStateLock) {
            if (activeHandshakeCount.get() <= 0) return false
            val started = handshakeStartedAtMs
            if (started != 0L && System.currentTimeMillis() - started > HANDSHAKE_MAX_MS) {
                OalLog.w(TAG, "handshakeInFlight still had ${activeHandshakeCount.get()} " +
                        "owner(s) from ${(System.currentTimeMillis() - started) / 1000}s ago — " +
                        "treating them as finished")
                activeHandshakeCount.set(0)
                handshakeStartedAtMs = 0L
                handshakeEpoch++
                return false
            }
            true
        }

    class HandshakeLease internal constructor(private val leaseEpoch: Long) {
        private val finished = java.util.concurrent.atomic.AtomicBoolean(false)

        fun finish() {
            if (finished.compareAndSet(false, true)) {
                AaWirelessBtControl.endHandshake(leaseEpoch)
            }
        }
    }

    fun beginHandshake(): HandshakeLease? = synchronized(handshakeStateLock) {
        if (wirelessAdmissionBlocked()) return null
        if (!sessionAdmission.canAdvertise()) return null
        if (sessionIsStreaming?.invoke() == true) {
            OalLog.i(TAG, "Suppressing redundant WPP activation — projection is already streaming")
            return null
        }
        if (handshakeAdmissionBlocked) return null
        if (activeHandshakeCount.incrementAndGet() == 1) {
            handshakeStartedAtMs = System.currentTimeMillis()
        }
        HandshakeLease(handshakeEpoch)
    }

    private fun endHandshake(leaseEpoch: Long) = synchronized(handshakeStateLock) {
        if (leaseEpoch != handshakeEpoch) return
        val remaining = activeHandshakeCount.updateAndGet { (it - 1).coerceAtLeast(0) }
        if (remaining == 0) handshakeStartedAtMs = 0L
    }

    @Volatile
    var activePhoneBt: String? = null

    private val phoneClaimLock = Any()
    private var phoneClaimGeneration = 0L

    /** When [activePhoneBt] took the session, for ageing out a stale claim. */
    @Volatile
    private var activePhoneClaimedAt: Long = 0L

    private fun claimPhoneForHandshake(
        phoneBtAddress: String,
        holderIsStreaming: Boolean,
    ): Long? = synchronized(phoneClaimLock) {
        val claimed = activePhoneBt
        val claimAgeMs = System.currentTimeMillis() - activePhoneClaimedAt
        if (claimed != null &&
            !claimed.equals(phoneBtAddress, ignoreCase = true) &&
            claimAgeMs < CLAIM_TIMEOUT_MS &&
            holderIsStreaming
        ) {
            OalLog.i(TAG, "Not switching to $phoneBtAddress — $claimed is " +
                    "streaming (claimed ${claimAgeMs / 1000}s ago). Switch phones " +
                    "in the car's Bluetooth settings; disconnecting $claimed " +
                    "releases the session immediately.")
            return null
        }
        if (claimed != null && !claimed.equals(phoneBtAddress, ignoreCase = true)) {
            OalLog.i(TAG, "Handing the session from $claimed to $phoneBtAddress, " +
                    "which is dialling now (previous claim ${claimAgeMs / 1000}s old, " +
                    "streaming=$holderIsStreaming)")
        }
        activePhoneBt = phoneBtAddress
        activePhoneClaimedAt = System.currentTimeMillis()
        phoneClaimGeneration++
        phoneClaimGeneration
    }

    private fun phoneClaimStillOwned(phoneBtAddress: String, generation: Long): Boolean =
        synchronized(phoneClaimLock) {
            activePhoneBt.equals(phoneBtAddress, ignoreCase = true) &&
                phoneClaimGeneration == generation
        }

    /**
     * Returns only the companion address proven to belong to the Bluetooth phone
     * currently holding the WPP claim.
     *
     * Discovery's global address history is intentionally excluded: it can contain
     * every nearby phone and was the source of cross-phone speculative dials.
     */
    fun withIdentityValidatedCompanion(action: (String) -> Unit): String? {
        val selectedLocalIpv4 = currentSelectedWppIpv4() ?: return null
        return synchronized(phoneClaimLock) {
            val btAddress = activePhoneBt ?: return null
            if (phoneIdByBtAddress[btAddress].isNullOrBlank()) return null
            val address = lastAddressByPhone[btAddress] ?: return null
            if (!WppInterfacePolicy.isPeerOnSelectedSubnet(selectedLocalIpv4, address)) {
                OalLog.i(TAG, "Rejecting cached companion at $address — outside selected " +
                        "WPP interface subnet ($selectedLocalIpv4)")
                return null
            }
            action(address)
            address
        }
    }

    /**
     * Reset attempt ownership on ignition off while retaining address hints.
     *
     * Address hints are retained because the AP is stable across ordinary ignition
     * cycles and stale hints cost only a bounded probe before the attempt-owned
     * scan. The subnet can still change across longer epochs, so every hint is
     * filtered against the interfaces the car holds now.
     *
     * The companion proxy port is reserved and stable; reset only attempt-owned
     * endpoint state while retaining address hints for the next ignition.
     */
    fun resetForNextIgnition() {
        // Snapshot the addresses BEFORE clearing them — the notify is async and
        // would otherwise race the clear and find nothing to send to.
        val selectedLocalIpv4 = currentSelectedWppIpv4()
        val targets = buildList {
            lastKnownPhoneIp?.let { add(it) }
            synchronized(recentPhoneIps) { addAll(recentPhoneIps) }
        }.distinct().filter { target ->
            selectedLocalIpv4 != null &&
                WppInterfacePolicy.isPeerOnSelectedSubnet(selectedLocalIpv4, target)
        }
        notifyCompanionOfShutdown(targets, selectedLocalIpv4)
        releaseActivePhone()
        // Keep address hints across ignition. A stale address costs one bounded
        // probe before the attempt-owned scan; clearing every address forces every
        // restart to begin with a full blind /24 scan. Proxy ports are no longer
        // cached: current companions reserve 5280, while an older companion's
        // dynamic value is accepted only from a live identity reply.
        bootstrapAttempt.set(null)
        pendingLegacyReadvertise.set(null)
        synchronized(handshakeStateLock) {
            activeHandshakeCount.set(0)
            handshakeStartedAtMs = 0L
            handshakeEpoch++
            // Do not clear an owned compatibility admission barrier here. Its
            // re-advertise job is NonCancellable and only its generation token
            // may reopen handshakes after the listener replacement completes.
        }
        OalLog.i(TAG, "Reset WPP attempt state, keeping known addresses " +
                "(${(listOfNotNull(lastKnownPhoneIp) + recentPhoneIps).distinct().joinToString()})")
    }

    /**
     * Tell the companion we are powering down, so it drops the bridge.
     *
     * Strictly fire-and-forget on a very short timeout. Measured on a real
     * power-down, the app had about 800ms of life left after IGNITION_STATE
     * reached OFF — the last log line was 816ms later. Anything that blocks
     * longer than that simply never happens.
     *
     * Best-effort by design: failing to deliver this costs nothing, because the
     * companion still discovers the loss the slow way. Delivering it saves the
     * next connection from a pooled socket to a car that no longer exists.
     */
    private fun notifyCompanionOfShutdown(targets: List<String>, sourceIpv4: String?) {
        if (targets.isEmpty() || sourceIpv4 == null) return
        for (ip in targets) {
            scope.launch {
                runCatching {
                    java.net.Socket().use { s ->
                        s.bind(java.net.InetSocketAddress(sourceIpv4, 0))
                        s.connect(java.net.InetSocketAddress(ip, IDENTITY_PORT), 250)
                        s.getOutputStream().apply { write("BYE!\n".toByteArray()); flush() }
                    }
                    OalLog.i(TAG, "Told the companion at $ip we are shutting down")
                }
            }
        }
    }

    /** Release the session claim so another phone can take it. */
    /**
     * Release the session claim as soon as the holding phone leaves Bluetooth.
     *
     * Without this the only way a claim ended was a 120s timeout, so switching
     * phones in the car's own Bluetooth settings — the only way to switch, since
     * the privileged APIs are closed to us — was ignored for up to two minutes.
     * The car's Bluetooth stack knows the moment the phone disconnects; we just
     * were not asking.
     *
     * Deliberately only releases the claim. It does not stop the session: a brief
     * Bluetooth flap should not tear down working projection, and if the phone
     * really is gone the session dies on its own.
     */
    private fun registerBtDisconnectReceiver(context: Context) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, intent: Intent?) {
                if (intent?.action != BluetoothDevice.ACTION_ACL_DISCONNECTED) return
                val device = intent.getParcelableExtra<BluetoothDevice>(
                    BluetoothDevice.EXTRA_DEVICE,
                )
                val addr = runCatching { device?.address }.getOrNull() ?: return
                val holder = activePhoneBt
                if (holder != null && holder.equals(addr, ignoreCase = true)) {
                    OalLog.i(TAG, "$addr left Bluetooth — releasing its session claim " +
                            "so another phone can connect immediately")
                    releaseActivePhone()
                }
            }
        }
        runCatching {
            androidx.core.content.ContextCompat.registerReceiver(
                context,
                receiver,
                IntentFilter(BluetoothDevice.ACTION_ACL_DISCONNECTED),
                androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED,
            )
        }.onFailure {
            OalLog.w(TAG, "Could not watch for Bluetooth disconnects: ${it.message}")
        }
    }

    fun releaseActivePhone() = synchronized(phoneClaimLock) {
        activePhoneBt?.let { OalLog.i(TAG, "Released the session claim held by $it") }
        activePhoneBt = null
        activePhoneClaimedAt = 0L
        activePhoneCompanionIp = null
        bootstrapAttempt.set(null)
        pendingLegacyReadvertise.set(null)
        legacyEndpointForHandshake.set(null)
        phoneClaimGeneration++
    }

    /**
     * IP of the companion belonging to the Bluetooth-connected phone, or null.
     *
     * This is the only phone that can start Android Auto — projection begins with
     * that phone's own Bluetooth handshake — so the UI can mark it and stop
     * implying the others are equivalent.
     *
     * Derived from the handshake rather than reported by the companion: a phone
     * app cannot read its own Bluetooth MAC (getAddress() returns
     * 02:00:00:00:00:00 without LOCAL_MAC_ADDRESS, which is signature|privileged
     * — the same wall that leaves the car's own "BT MAC for SDR: (none)"). But the
     * car already learns both halves during the handshake: the dialling phone's
     * Bluetooth address from the RFCOMM socket, and its companion's IP from the
     * endpoint lookup. Pairing them here needs nothing new on the phone side.
     */
    @Volatile
    var activePhoneCompanionIp: String? = null
        private set

    /** Name and IPv4 currently owned by the explicit WPP interface selection. */
    @Volatile
    private var selectedWppInterfaceName: String? = null

    @Volatile
    var selectedWppInterfaceIpv4: String? = null
        private set

    private fun isDebuggableBuild(context: Context): Boolean =
        context.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0

    fun init(context: Context) {
        appContext = context.applicationContext
        restoreKnownIps(context)
        registerBtDisconnectReceiver(context)
        synchronized(this) {
            if (started) return
            started = true
        }
        if (isDebuggableBuild(context)) {
            val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, intent: Intent?) {
                when (intent?.action) {
                    ACTION_START -> handleStart(context, intent)
                    ACTION_SET_TRANSPORT -> {
                        val mode = intent.getStringExtra("mode").orEmpty()
                        if (mode.isBlank()) {
                            OalLog.w(TAG, "$ACTION_SET_TRANSPORT missing 'mode' extra")
                            return
                        }
                        scope.launch {
                            com.openautolink.app.data.AppPreferences.getInstance(context)
                                .setDirectTransport(mode)
                            OalLog.i(TAG, "Direct transport set to '$mode'")
                        }
                    }
                    ACTION_STOP -> {
                        OalLog.i(TAG, "Stopping AA wireless BT advertiser on request")
                        btServer?.stop()
                        btServer = null
                    }
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction(ACTION_START)
            addAction(ACTION_STOP)
            addAction(ACTION_SET_TRANSPORT)
        }
        // Exported so it can be driven from adb during bring-up. This is a debug
        // affordance; when the advertiser starts automatically it should stop being
        // externally triggerable.
            androidx.core.content.ContextCompat.registerReceiver(
                context, receiver, filter,
                androidx.core.content.ContextCompat.RECEIVER_EXPORTED,
            )
            OalLog.i(TAG, "Debug AA wireless controls registered")
        } else {
            OalLog.i(TAG, "Debug AA wireless controls disabled in release build")
        }

        // Start advertising automatically whenever WPP is the selected transport.
        //
        // Selecting Settings → Transport → Wireless (WPP) binds the TCP listener
        // (AasdkSession.startWpp) but that alone publishes nothing over Bluetooth,
        // so the phone has nothing to discover and reports "no Android Auto". The
        // SDP record is what makes the head unit visible; it must come up on the
        // same trigger as the listener, not from a debug broadcast.
        //
        // Observed in-vehicle before this fix: "AA wireless BT control ready" and
        // "WPP TCP server listening on 0.0.0.0:5277", but never
        // "Listening on Android Auto Wireless UUID" — the advertiser sat waiting
        // for a broadcast that only ever came from adb.
        scope.launch {
            val prefs = com.openautolink.app.data.AppPreferences.getInstance(context)
            prefs.directTransport
                .map { it == com.openautolink.app.data.AppPreferences.DIRECT_TRANSPORT_WPP }
                .distinctUntilChanged()
                .collect { isWpp ->
                    if (isWpp) {
                        OalLog.i(TAG, "WPP transport selected — requesting Bluetooth advertiser")
                        // The preference changes before Save & Reconnect has
                        // replaced the old USB session. Only the active protocol
                        // owner may publish, or the phone dials a stale callback.
                        ensureAdvertising()
                    } else if (btServer != null) {
                        OalLog.i(TAG, "Transport is no longer WPP — stopping Bluetooth advertiser")
                        btServer?.stop()
                        btServer = null
                    }
                }
        }

        OalLog.i(TAG, "AA wireless BT control ready")
    }

    /**
     * Start advertising using the credentials stored in Settings.
     *
     * Intent extras are honoured when present, but are only a bring-up
     * convenience — the normal path is Settings → Wireless (WPP), because in a
     * real vehicle nobody can run `adb` mid-drive.
     *
     * ### Why the credentials must be typed in
     *
     * These describe the network the phone should join to reach the head unit.
     * On AAOS that is usually the car's own hotspot, and an unprivileged app
     * cannot read a running SoftAP's SSID or passphrase:
     * `WifiManager.getWifiApConfiguration()` needs a system signature, and
     * `LocalOnlyHotspot` only reports credentials for an AP the app itself
     * started — not the vehicle's. So the values come from the user.
     *
     * Missing or malformed credentials are a hard failure on the phone side, so
     * they are validated before anything is advertised:
     *   - empty SSID              → nothing to join
     *   - empty/zero/broadcast BSSID → `WIFI_INVALID_BSSID`
     *   - empty PSK on a secured network → `WIFI_SECURITY_NOT_SUPPORTED`
     *     (we send `securityMode=OPEN`, which gearhead rejects for a WPA2 AP)
     */
    private fun handleStart(context: Context, intent: Intent?) {
        scope.launch { startFromPreferences(context, intent) }
    }

    /**
     * Load credentials from Settings (optionally overridden by intent extras)
     * and begin advertising. Shared by the automatic transport trigger and the
     * adb bring-up broadcast so both validate identically.
     */
    private suspend fun startFromPreferences(
        context: Context,
        intent: Intent? = null,
        expectedOwner: WppSessionAdmission.Token? = sessionAdmission.currentWppOwner(),
    ) {
            if (expectedOwner == null || !sessionAdmission.isCurrent(expectedOwner)) {
                OalLog.i(TAG, "Not advertising — current protocol owner is not WPP")
                return
            }
            val prefs = com.openautolink.app.data.AppPreferences.getInstance(context)
            val apInterface = expectedOwner.wppInterfaceName
            if (apInterface.isNullOrBlank()) {
                OalLog.e(TAG, "Not advertising — WPP session owner has no interface")
                return
            }
            selectedWppInterfaceName = apInterface

            // Common readiness chokepoint. The selected interface is authoritative:
            // never advertise or dial through another live NIC when it is absent.
            awaitApInterface(apInterface)
            if (!sessionAdmission.isCurrent(expectedOwner)) {
                OalLog.i(TAG, "Not advertising — WPP owner changed while waiting for AP")
                return
            }
            val interfaceIpv4 = localIpv4Address(apInterface)
            if (interfaceIpv4 == null) {
                selectedWppInterfaceIpv4 = null
                OalLog.w(TAG, "Not advertising — selected WPP interface '$apInterface' " +
                        "is unavailable or has no IPv4 address")
                return
            }
            selectedWppInterfaceIpv4 = interfaceIpv4

            val ssid = intent?.getStringExtra("ssid")?.takeIf { it.isNotBlank() }
                ?: prefs.hotspotSsid.first()
            val psk = intent?.getStringExtra("psk")
                ?: prefs.hotspotPassword.first()
            val bssid = intent?.getStringExtra("bssid")?.takeIf { it.isNotBlank() }
                ?: prefs.wppBssid.first()
            val port = intent?.getIntExtra("port", 5277) ?: 5277
            val requestedIp = intent?.getStringExtra("ip")?.takeIf { it.isNotBlank() }
            if (requestedIp != null && requestedIp != interfaceIpv4) {
                OalLog.w(TAG, "Ignoring WPP IP override $requestedIp — selected interface " +
                        "$apInterface owns $interfaceIpv4")
            }
            val ip = interfaceIpv4

            val problems = buildList {
                if (ssid.isBlank()) add("SSID is empty")
                if (bssid.isBlank()) add("BSSID is empty")
                else if (!BSSID_RE.matches(bssid)) add("BSSID '$bssid' is not a MAC address")
                else if (bssid.equals(ZERO_MAC, true) || bssid.equals(BROADCAST_MAC, true)) {
                    add("BSSID $bssid is reserved (zero/broadcast)")
                }
                if (ip.isBlank()) add("could not determine this device's IPv4 address")
            }
            if (problems.isNotEmpty()) {
                OalLog.w(TAG, "Not advertising — ${problems.joinToString("; ")}. " +
                        "Set these in Settings → Transport → Wireless (WPP).")
                return
            }

            val creds = AaWirelessBtServer.WifiCredentials(
                ssid = ssid, psk = psk, bssid = bssid, ip = ip, port = port,
                channelsMhz = apChannelsMhz(prefs.wppChannelMhz.first()),
            )
            startAdvertising(
                context, creds,
                apInterface = apInterface,
                expectedOwner = expectedOwner,
            )
    }

    /**
     * Best-effort local IPv4 for the access-point interface.
     *
     * Picking "the first non-loopback address" is wrong on a real head unit. A
     * connected car has several interfaces up at once — modem, telematics,
     * ethernet, plus the SoftAP — and the first one enumerated is rarely the one
     * the phone can reach. Observed in-vehicle: the car advertised
     * `172.16.101.100` (an unrelated internal interface) while the phone, having
     * correctly joined the car's AP, sat on `10.2.110.109`. Association and
     * credentials were perfect; the TCP connect simply had nowhere to go.
     *
     * Preference order:
     *  1. an interface that looks like a SoftAP (`ap*`, `wlan1`, `swlan*`, …)
     *  2. any other wlan interface
     *  3. anything else routable
     *
     * Within each tier, RFC1918 addresses are preferred, since an AP hands out
     * private addresses. This is still a heuristic — hence the manual override.
     */
    /**
     * Waits for the car's access-point interface to exist and hold an address.
     *
     * The telematics module serves "Blazing" independently of Android, and on a
     * cold start it is not ready when the app is. Advertising before then
     * publishes whatever interface happens to be up — observed: a 172.16.x
     * telematics address, unreachable from the phone.
     *
     * Bounded, and non-fatal on timeout: if the interface never appears we carry
     * on and let the normal fallback pick an address, rather than never
     * advertising at all.
     */
    private suspend fun awaitApInterface(apInterface: String, timeoutMs: Long = 45_000) {
        val deadline = System.currentTimeMillis() + timeoutMs
        var waited = false
        while (System.currentTimeMillis() < deadline) {
            val addr = runCatching {
                java.net.NetworkInterface.getByName(apInterface)
                    ?.takeIf { it.isUp }
                    ?.inetAddresses?.toList()
                    ?.filterIsInstance<java.net.Inet4Address>()
                    ?.firstOrNull { !it.isLoopbackAddress && !it.isLinkLocalAddress }
                    ?.hostAddress
            }.getOrNull()
            if (addr != null) {
                if (waited) {
                    OalLog.i(TAG, "$apInterface is up at $addr — advertising now")
                    onStatus?.invoke("Car WiFi ready \u2014 connecting\u2026")
                }
                return
            }
            if (!waited) {
                OalLog.i(TAG, "Waiting for $apInterface — the car's hotspot is not serving yet")
                // Tell the user too.
                //
                // Measured 2026-08-10: ignition ON at 08:20:08, the AP interface
                // did not hold an address until 08:21:54 — 106 seconds during
                // which the screen showed an idle state and the phone was never
                // asked to switch networks, because the SSID and key only travel
                // inside the Bluetooth handshake we cannot publish yet. Looked
                // exactly like nothing was happening.
                onStatus?.invoke("Waiting for the car's WiFi\u2026")
                waited = true
            }
            kotlinx.coroutines.delay(1_000)
        }
        OalLog.w(TAG, "$apInterface did not appear within ${timeoutMs}ms — " +
                "strict WPP interface selection prevents fallback advertising")
    }

    /** Scan only the explicitly selected WPP interface subnet. */
    private fun findCompanionOnSelectedInterface(
        selectedLocalIpv4: String,
        phoneBtAddress: String,
        phoneBtName: String?,
        overallTimeoutMs: Int = 20_000,
    ): ResolvedCompanion? {
        val prefix = selectedLocalIpv4.substringBeforeLast('.', "")
        if (prefix.isEmpty()) return null
        OalLog.i(TAG, "Scanning selected WPP interface subnet $prefix.0/24 for the companion")
        return scanSubnet(
            prefix = prefix,
            ourIp = selectedLocalIpv4,
            phoneBtAddress = phoneBtAddress,
            phoneBtName = phoneBtName,
            overallTimeoutMs = overallTimeoutMs,
        ).also {
            if (it == null) {
                OalLog.w(TAG, "No companion on selected WPP interface subnet $prefix.0/24")
            }
        }
    }

    /**
     * Scans one /24 for the companion, returning its address AND proxy port.
     *
     * Does the whole identity exchange in the scan rather than connecting once to
     * test the port and again to ask the question. The second round trip cost ~5s
     * over the telematics bridge — measured at 17:01, where the scan gave up at
     * 39.2s and the companion's reply only completed at 44.3s.
     *
     * Timeout is 1200ms, not the 250ms of the previous revision. Two in-vehicle
     * runs bracket the right value: at 900ms the companion was found but the scan
     * took 7.2s; at 250ms the scan took 1.06s and found nothing, on a subnet where
     * the companion demonstrably answered five seconds later. The telematics
     * bridge is simply slow. With 128 threads a /24 still completes in about 2s
     * even when every address times out.
     */
    private fun scanSubnet(
        prefix: String,
        ourIp: String,
        phoneBtAddress: String,
        phoneBtName: String?,
        overallTimeoutMs: Int = 20_000,
    ): ResolvedCompanion? {
        val pool = java.util.concurrent.Executors.newFixedThreadPool(128)
        return try {
            val tasks = (1..254)
                .map { "$prefix.$it" }
                .filter { it != ourIp }
                .map { ip ->
                    java.util.concurrent.Callable {
                        val probe = askCompanion(ip, connectTimeoutMs = 1200)
                        if (probe != null && probeMatchesBluetoothOwner(
                                phoneBtAddress, phoneBtName, ip, probe)) {
                            ResolvedCompanion(ip, probe)
                        } else null
                    }
                }
            // Budget must cover the whole /24 at the chosen timeout, or the scan
            // reports "no companion" for one that is plainly there. Measured:
            // 254 hosts / 128 threads = 2 waves x 1200ms = 2.4s of pure connect
            // time, and a 3s cap cut it off at 2.48s while the companion was
            // answering probes 4s either side of that moment.
            // Budget has to exceed the worst case, not approximate it. 254 hosts
            // over 128 threads is 2 waves; at a 1200ms connect timeout that is
            // 2.4s of connect alone, plus the identity exchange on any hit. A
            // 3s cap cut a sweep off at 2.47s and reported "no companion" for one
            // the car had connected to 0.85s earlier. 20s leaves real headroom
            // and still returns immediately on the first hit.
            pool.invokeAll(
                tasks,
                overallTimeoutMs.toLong(),
                java.util.concurrent.TimeUnit.MILLISECONDS,
            )
                .asSequence()
                .mapNotNull { runCatching { it.get() }.getOrNull() }
                .firstOrNull()
                ?.also { OalLog.i(TAG, "Identity-matched companion found at ${it.host} " +
                        "id=${it.probe.phoneId} with proxy port ${it.probe.proxyPort}") }
        } catch (e: Exception) {
            OalLog.w(TAG, "Scan of $prefix.0/24 failed: ${e.message}")
            null
        } finally {
            pool.shutdownNow()
        }
    }

    /**
     * Asks the companion at [phoneIp] for its complete identity and live proxy.
     *
     * Identity is mandatory: accepting only `wpp=<port>` allowed whichever phone
     * answered first to impersonate the Bluetooth owner of the current attempt.
     */
    private fun askCompanion(
        phoneIp: String,
        connectTimeoutMs: Int = 1200,
    ): CompanionProbe? {
        val sourceIpv4 = selectedWppInterfaceIpv4 ?: return null
        if (!WppInterfacePolicy.isPeerOnSelectedSubnet(sourceIpv4, phoneIp)) return null
        return runCatching {
            java.net.Socket().use { sock ->
                sock.bind(java.net.InetSocketAddress(sourceIpv4, 0))
                sock.connect(java.net.InetSocketAddress(phoneIp, IDENTITY_PORT), connectTimeoutMs)
                sock.soTimeout = connectTimeoutMs
                sock.getOutputStream().apply { write("OAL?\n".toByteArray()); flush() }
                val reply = sock.getInputStream().bufferedReader().readLine().orEmpty()
                CompanionIdentityPolicy.parseProbe(reply)
            }
        }.getOrNull()
    }

    private fun probeMatchesBluetoothOwner(
        phoneBtAddress: String,
        phoneBtName: String?,
        phoneIp: String,
        probe: CompanionProbe,
        expectedPhoneIdOverride: String? = null,
    ): Boolean {
        val expectedPhoneId = expectedPhoneIdOverride ?: phoneIdByBtAddress[phoneBtAddress]
        val matchBasis = CompanionIdentityPolicy.matchBasis(
            expectedPhoneId = expectedPhoneId,
            bluetoothDeviceName = phoneBtName,
            probe = probe,
        )
        if (matchBasis == CompanionIdentityMatch.NONE) {
            OalLog.i(
                TAG,
                "Rejected companion at $phoneIp id=${probe.phoneId ?: "unknown"} " +
                    "name=${probe.friendlyName ?: "unknown"} " +
                    "reportedBluetoothName=${probe.bluetoothName ?: "unknown"} — " +
                    "Bluetooth owner is $phoneBtAddress name=${phoneBtName ?: "unknown"} " +
                    "expectedId=${expectedPhoneId ?: "unlearned"}",
            )
            return false
        }
        if (matchBasis == CompanionIdentityMatch.BLUETOOTH_NAME) {
            OalLog.i(
                TAG,
                "Bluetooth-name matched companion at $phoneIp " +
                    "reportedBluetoothName=${probe.bluetoothName ?: "unknown"} " +
                    "to owner=$phoneBtAddress name=${phoneBtName ?: "unknown"}; " +
                    "learning stableId=${probe.phoneId ?: "unknown"}",
            )
        }
        probe.phoneId?.let { phoneIdByBtAddress[phoneBtAddress] = it }
        return true
    }

    /**
     * Frequencies (MHz) advertised as supported by the head unit's access point.
     *
     * Must not be empty. The phone intersects this list with its own scan
     * results; an empty list produces
     *   "WiFi channels not supported: []" -> NO_COMPATIBLE_WIFI_CHANNEL_FOUND
     * and the connection is abandoned even though everything else succeeded.
     *
     * An unprivileged app cannot read a running SoftAP's channel
     * (getWifiApConfiguration is signature-gated), so:
     *   1. use the configured override if set — the reliable answer
     *   2. otherwise advertise the common 5 GHz set, which at least gives the
     *      intersection a chance of being non-empty
     *
     * The fallback is a guess and is logged as one.
     */
    private fun apChannelsMhz(override: Int): List<Int> {
        if (override > 0) {
            OalLog.i(TAG, "AP channel from settings: $override MHz")
            return listOf(override)
        }
        // The phone's own scan reported [5180, 5200, 5220, 5240, 5745, 5765,
        // 5785, 5805, 5825, ...], so covering that range keeps the intersection
        // non-empty for a head unit that lands anywhere in it.
        val fallback = listOf(5180, 5200, 5220, 5240, 5745, 5765, 5785, 5805, 5825)
        OalLog.w(TAG, "AP channel not configured — advertising the common 5GHz set $fallback. " +
                "If projection fails with NO_COMPATIBLE_WIFI_CHANNEL_FOUND, set the exact " +
                "channel in Settings.")
        return fallback
    }

    private fun currentSelectedWppIpv4(): String? {
        val interfaceName = selectedWppInterfaceName ?: return null
        return localIpv4Address(interfaceName).also { selectedWppInterfaceIpv4 = it }
    }

    /** Resolve only the explicitly selected interface. */
    private fun localIpv4Address(apInterface: String): String? = runCatching {
        val candidates = java.net.NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback && !it.isVirtual }
            .flatMap { nif ->
                nif.inetAddresses.toList()
                    .filterIsInstance<java.net.Inet4Address>()
                    .filter { !it.isLoopbackAddress && !it.isLinkLocalAddress }
                    .mapNotNull { it.hostAddress?.let { address ->
                        WppInterfacePolicy.InterfaceIpv4(nif.name, address)
                    } }
            }
        val chosen = WppInterfacePolicy.selectedIpv4(apInterface, candidates)
        OalLog.i(TAG, "Local address candidates: " +
                candidates.joinToString { "${it.name}=${it.address}" } +
                " — selected $apInterface=${chosen ?: "unavailable"}; no fallback allowed")
        chosen
    }.getOrNull()

    private fun startAdvertising(
        context: Context,
        creds: AaWirelessBtServer.WifiCredentials,
        apInterface: String,
        expectedOwner: WppSessionAdmission.Token,
    ) {
        if (!sessionAdmission.isCurrent(expectedOwner)) {
            OalLog.i(TAG, "SDP publish cancelled — no current WPP session owner")
            return
        }
        // Switching the session into WPP mode is what binds the listener — see
        // AasdkSession.startWpp(). Doing it here rather than starting our own
        // server avoids two components racing for the same port.
        //
        // The session must already be running in "wpp" transport mode for the
        // advertised port to be listening when the phone dials in. Set
        // Settings -> Direct transport -> wpp (or DEBUG_SET_TRANSPORT) first.
        OalLog.i(TAG, "Advertising ${creds.ip}:${creds.port} — session must be in 'wpp' " +
                "transport mode for that port to be bound")

        val bt = AaWirelessBtServer(
            context = context,
            parentScope = scope,
            onUnexpectedAcceptLoopExit = {
                // Socket-death and ignition recovery share ensureAdvertising's
                // single-flight guard, so they cannot replace each other's new
                // SDP listener when Bluetooth returns.
                ensureAdvertising()
            },
            onSdpPublished = { elapsedMs ->
                com.openautolink.app.wake.PreWakeMonitor.reportSdpPublished(elapsedMs)
            },
            onPhoneDialback = { elapsedMs ->
                com.openautolink.app.wake.PreWakeMonitor.reportPhoneDialback(elapsedMs)
            },
        )
        synchronized(this) {
            if (wirelessAdmissionBlocked() ||
                !sessionAdmission.isCurrent(expectedOwner)) {
                OalLog.i(TAG, "SDP publish cancelled before ownership swap")
                bt.stop()
                return
            }
            btServer?.stop()
            btServer = bt
        }
        // Probe first: if the BT stack refuses to publish the record we want that
        // stated plainly in the log, not inferred later from the phone's silence.
        val canAdvertise = bt.canAdvertise()
        OalLog.i(TAG, "SDP advertise capability: $canAdvertise")
        // Re-resolve the exact selected interface on every handshake. No fallback.
        bt.setAddressResolver { localIpv4Address(apInterface) }

        // Endpoint selection, evaluated per handshake.
        //
        // Prefer the companion's loopback proxy: the phone connects to itself, so
        // the car's access point is never asked to accept an inbound connection —
        // which it refuses. Falling back to the car's own address preserves the
        // shared-network case (proven working on a tablet) and costs nothing.
        bt.setEndpointResolver { phoneBtAddress, phoneBtName ->
            // Only serve the phone the head unit is actually paired-and-connected
            // to for projection. Two phones can dial the SDP record concurrently,
            // and the endpoint is per-phone: 127.0.0.1:<port> only means anything
            // on the device whose companion owns that port. Handing phone B the
            // port belonging to phone A's companion sends B's Android Auto to a
            // closed socket on its own loopback.
            //
            // Android Auto is single-session per head unit, so serving one phone
            // is correct — but the choice must be deliberate, not first-to-dial.
            // A claim only means something while it is fresh. Observed
            // in-vehicle: a phone claimed at 14:33 was still blocking a different
            // phone at 18:20, hours after that session had ended — the claim
            // outlived the thing it was protecting.
            //
            // A phone that dials the SDP record IS present and IS trying to
            // connect, so an old claim should lose to a live handshake.
            val holderIsStreaming = sessionIsStreaming?.invoke() == true
            val claimGeneration = claimPhoneForHandshake(
                phoneBtAddress = phoneBtAddress,
                holderIsStreaming = holderIsStreaming,
            ) ?: return@setEndpointResolver null

            // Reserve the phone-local endpoint without waiting for a phone which
            // has not yet been instructed to associate. Hints are for the bounded
            // asynchronous identity worker, never authority to dial a target.
            val selectedLocalIpv4 = localIpv4Address(apInterface)
                ?: return@setEndpointResolver null
            selectedWppInterfaceIpv4 = selectedLocalIpv4
            // The address this phone used last time goes first.
            //
            // The address history is shared across phones, so with two phones in
            // the car the other one's address can sit at the front of the list
            // and waste a probe. Keying the preferred address by Bluetooth MAC
            // costs nothing and makes the common case — same phone, same
            // address — a single fast probe.
            val preferredForThisPhone = lastAddressByPhone[phoneBtAddress]
            val allKnown = buildList {
                preferredForThisPhone?.let { add(it) }
                lastKnownPhoneIp?.let { add(it) }
                synchronized(recentPhoneIps) { addAll(recentPhoneIps) }
            }.distinct()
            val candidates = allKnown.filter { ip ->
                WppInterfacePolicy.isPeerOnSelectedSubnet(selectedLocalIpv4, ip)
            }
            val dropped = allKnown - candidates.toSet()
            if (dropped.isNotEmpty()) {
                OalLog.i(TAG, "Ignoring ${dropped.joinToString()} — outside selected WPP " +
                        "interface $apInterface subnet ($selectedLocalIpv4)")
            }
            // Reserve the stable local proxy before asking the phone to associate.
            // All identity probes run asynchronously; unreachable pre-association
            // addresses must not hold up WifiVersion/WifiStart.
            if (!phoneClaimStillOwned(phoneBtAddress, claimGeneration)) {
                OalLog.i(TAG, "Not starting bootstrap for superseded phone claim $phoneBtAddress")
                return@setEndpointResolver null
            }
            // The phone is not on the car AP yet, so the companion cannot
            // answer. Use the port both new apps reserve in advance. WPP
            // moves the phone onto the AP, Android Auto can attach to its
            // local proxy immediately, and discovery then supplies the
            // car-side socket to that same waiting bridge.
            // A legacy compatibility refresh may carry forward only the
            // exact phone/owner/interface's bounded proven loopback port.
            // It does not authorize a target dial: discovery must re-prove
            // the identity and matching port in this new attempt.
            val legacy = legacyEndpointForHandshake.getAndSet(null)?.takeIf {
                it.attempt.owner === expectedOwner &&
                    it.attempt.phoneBtAddress.equals(phoneBtAddress, ignoreCase = true) &&
                    it.attempt.interfaceName == apInterface &&
                    it.attempt.localIpv4 == selectedLocalIpv4 &&
                    (it.attempt.discoveryDeadlineMs ?: 0L) > android.os.SystemClock.elapsedRealtime()
            }
            val offeredPort = legacy?.proxyPort ?: OalProtocol.WPP_PROXY_PORT
            val attempt = BootstrapAttempt(
                phoneBtAddress = phoneBtAddress,
                phoneBtName = phoneBtName,
                expectedPhoneId = phoneIdByBtAddress[phoneBtAddress],
                claimGeneration = claimGeneration,
                generation = bootstrapAttemptSequence.incrementAndGet(),
                owner = expectedOwner,
                server = bt,
                interfaceName = apInterface,
                localIpv4 = selectedLocalIpv4,
                advertisedProxyPort = offeredPort,
            )
            val published = synchronized(phoneClaimLock) {
                if (!activePhoneBt.equals(phoneBtAddress, ignoreCase = true) ||
                    phoneClaimGeneration != claimGeneration
                ) {
                    false
                } else {
                    bootstrapAttempt.set(attempt)
                    true
                }
            }
            if (!published) {
                OalLog.i(TAG, "Not publishing bootstrap for superseded phone claim $phoneBtAddress")
                return@setEndpointResolver null
            }
            OalLog.i(TAG, "CONNECT SUMMARY: endpoint=bootstrap-loopback " +
                    "127.0.0.1:$offeredPort phone=$phoneBtAddress " +
                    "knownAddrs=${allKnown.joinToString().ifEmpty { "none" }} " +
                    "usable=${candidates.joinToString().ifEmpty { "none" }} — " +
                    "waiting for companion discovery after AP association")
            AaWirelessBtServer.Endpoint.PhoneLoopback(
                offeredPort,
                onAssociationReady = {
                    startBootstrapCompanionDiscovery(attempt, candidates, selectedLocalIpv4)
                },
                onControlClosed = {
                    synchronized(this) {
                        synchronized(phoneClaimLock) {
                            attempt.controlClosed = true
                            bootstrapAttempt.compareAndSet(attempt, null)
                        }
                    }
                },
            )
        }
        bt.updateCredentials(creds)
        if (!sessionAdmission.isCurrent(expectedOwner)) {
            OalLog.i(TAG, "SDP publish cancelled at final boundary — session owner changed")
            bt.stop()
            synchronized(this) {
                if (btServer === bt) btServer = null
            }
            return
        }
        synchronized(this) {
            if (btServer !== bt || wirelessAdmissionBlocked() ||
                !sessionAdmission.isCurrent(expectedOwner)) {
                bt.stop()
                if (btServer === bt) btServer = null
                return
            }
            bt.start()
        }
    }
    private var carPlayRetiringAdvertiser: AaWirelessBtServer? = null
    /** Off UI, after CP admission reservation. Failed-stop server is retained for retry. */
    suspend fun retireForCarPlay() {
        check(com.openautolink.app.carplay.CarPlayOwnership.process.blocksAndroidAuto)
        awaitAdvertiserRetirement()
    }

    /** Diagnostic Stop pauses only projection admission; never toggles/disconnects phone BT profiles. */
    suspend fun retireForMicrophoneDiagnostic() {
        check(com.shilapi.xcertplay.media.MicrophoneCaptureFence.projectionPaused())
        awaitAdvertiserRetirement()
    }

    /** USB Switch reserves its own exact lease, not a fictitious CarPlay owner. */
    suspend fun retireForUsb(token: UsbHandoffToken) {
        check(usbHandoff === token) { "Stale USB handoff" }
        awaitAdvertiserRetirement()
        synchronized(this) {
            check(usbHandoff === token) { "USB handoff superseded during retirement" }
            token.advertiserRetired = true
        }
    }

    private suspend fun awaitAdvertiserRetirement() = advertiserRetirementMutex.withLock {
        val retiring = synchronized(this) {
            carPlayRetiringAdvertiser ?: btServer?.also {
                carPlayRetiringAdvertiser = it
                btServer = null
            }
        }
        retiring?.stopAndAwait()
        synchronized(this) { if (carPlayRetiringAdvertiser === retiring) carPlayRetiringAdvertiser = null }
    }
}
