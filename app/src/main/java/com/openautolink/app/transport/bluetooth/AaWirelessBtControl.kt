package com.openautolink.app.transport.bluetooth

import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import com.openautolink.app.diagnostics.OalLog
import com.openautolink.app.transport.OalProtocol
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

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

    fun installSessionOwner(transportMode: String): WppSessionAdmission.Token {
        val token = synchronized(this) {
            sessionAdmission.installSession(transportMode)
        }
        OalLog.i(TAG, "Session admission installed: generation=${token.generation} " +
                "transport=${token.transportMode}")
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

    /**
     * Attempt-owned AP bootstrap discovery.
     *
     * WPP intentionally disables the UI's periodic PhoneDiscovery. This scanner is
     * narrower: it runs only after the first Bluetooth exchange has moved the
     * selected phone onto the car AP, scans even with an empty cache, and expires
     * before the companion proxy's 30-second car-socket waiter.
     */
    private fun startBootstrapCompanionDiscovery(
        attempt: BootstrapAttempt,
        candidates: List<String>,
        manualIp: String?,
    ) {
        scope.launch {
            val deadline = System.currentTimeMillis() + WppBootstrapPolicy.DISCOVERY_DEADLINE_MS
            var scanAttempt = 0
            while (System.currentTimeMillis() < deadline) {
                // A newer Bluetooth dial-back owns a different attempt. Let this
                // scanner die without consuming or dialling on its behalf.
                if (bootstrapAttempt.get() !== attempt ||
                    !phoneClaimStillOwned(attempt.phoneBtAddress, attempt.claimGeneration) ||
                    sessionIsStreaming?.invoke() == true
                ) {
                    return@launch
                }
                scanAttempt++
                var found: ResolvedCompanion? = null
                for (ip in candidates.take(MAX_PRESCAN_PROBES)) {
                    val probe = askCompanion(ip, connectTimeoutMs = 800)
                    if (probe != null && probeMatchesBluetoothOwner(
                            attempt.phoneBtAddress,
                            attempt.phoneBtName,
                            ip,
                            probe,
                            attempt.expectedPhoneId,
                        )) {
                        found = ResolvedCompanion(ip, probe)
                        break
                    }
                }
                if (found == null) {
                    val remainingMs = (deadline - System.currentTimeMillis())
                        .coerceIn(1L, 5_000L)
                        .toInt()
                    found = findCompanionOnAnySubnet(
                        manualIp = manualIp,
                        phoneBtAddress = attempt.phoneBtAddress,
                        phoneBtName = attempt.phoneBtName,
                        overallTimeoutMs = remainingMs,
                    )
                }
                if (found != null) {
                    val ip = found.host
                    val probe = found.probe
                    OalLog.i(TAG, "Bootstrap discovery identity-matched companion at $ip " +
                            "id=${probe.phoneId} on attempt $scanAttempt " +
                            "(proxy port ${probe.proxyPort})")
                    readvertiseForNewCompanionAddress(
                        host = ip,
                        reportedProxyPort = probe.proxyPort,
                        expectedAttempt = attempt,
                        reportedPhoneId = probe.phoneId,
                        reportedFriendlyName = probe.friendlyName,
                    )
                    return@launch
                }
                kotlinx.coroutines.delay(750)
            }
            OalLog.w(TAG, "Bootstrap companion discovery timed out after " +
                    "${WppBootstrapPolicy.DISCOVERY_DEADLINE_MS / 1000}s")
        }
    }

    /** Run a legacy companion re-advertise once the current handshake exits. */
    fun flushPendingReadvertise() {
        val (pending, admissionToken) = synchronized(handshakeStateLock) {
            if (activeHandshakeCount.get() > 0 || handshakeAdmissionBlocked) return
            val candidate = pendingLegacyReadvertise.get() ?: return
            val consumed = synchronized(phoneClaimLock) {
                activePhoneBt.equals(candidate.attempt.phoneBtAddress, ignoreCase = true) &&
                    phoneClaimGeneration == candidate.attempt.claimGeneration &&
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
        readvertise(admissionToken)
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
        val liveProbe = askCompanion(host, connectTimeoutMs = 800)
        val probe = liveProbe ?: reportedProxyPort?.let { port ->
            CompanionProbe(reportedPhoneId, reportedFriendlyName, port)
        } ?: return
        val attempt = expectedAttempt ?: bootstrapAttempt.get()
        if (expectedAttempt != null && bootstrapAttempt.get() !== expectedAttempt) return
        if (attempt != null &&
            !phoneClaimStillOwned(attempt.phoneBtAddress, attempt.claimGeneration)
        ) {
            OalLog.i(TAG, "Ignoring late companion for superseded claim ${attempt.phoneBtAddress}")
            return
        }
        if (attempt != null && !probeMatchesBluetoothOwner(
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
            bootstrapLoopbackPending = attempt != null,
            usesReservedProxyPort = proxyPort == OalProtocol.WPP_PROXY_PORT,
            handshakeInFlight = handshakeInFlight,
            sessionStreaming = sessionIsStreaming?.invoke() == true,
        )) {
            LateCompanionAction.DIAL_COMPANION -> {
                // Consume and dial under the same claim lock. A newer Bluetooth
                // claimant cannot overtake this completion between the generation
                // check and the connector replacement.
                if (attempt == null) return
                val dialled = synchronized(phoneClaimLock) {
                    if (!activePhoneBt.equals(attempt.phoneBtAddress, ignoreCase = true) ||
                        phoneClaimGeneration != attempt.claimGeneration ||
                        !bootstrapAttempt.compareAndSet(attempt, null)
                    ) {
                        false
                    } else {
                        lastKnownPhoneIp = host
                        lastAddressByPhone[attempt.phoneBtAddress] = host
                        activePhoneCompanionIp = host
                        OalLog.i(TAG, "Companion became reachable at $host after AP association — " +
                                "dialling it into reserved loopback port $proxyPort; no second WPP exchange")
                        onCompanionSelected?.invoke(host)
                        true
                    }
                }
                if (!dialled) {
                    OalLog.i(TAG, "Ignoring late dial from superseded phone claim ${attempt.phoneBtAddress}")
                }
            }
            LateCompanionAction.QUEUE_READVERTISE -> {
                OalLog.i(TAG, "Older companion at $host uses dynamic proxy port $proxyPort; " +
                        "queueing one compatibility re-advertise after this handshake")
                synchronized(handshakeStateLock) {
                    if (attempt == null || bootstrapAttempt.get() !== attempt) return
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
                    if (attempt == null || bootstrapAttempt.get() !== attempt) return
                    pendingLegacyReadvertise.set(
                        PendingLegacyReadvertise(host, proxyPort, attempt),
                    )
                }
                flushPendingReadvertise()
            }
            LateCompanionAction.IGNORE -> {
                if (sessionIsStreaming?.invoke() == true && attempt != null) {
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
    fun withIdentityValidatedCompanion(action: (String) -> Unit): String? =
        synchronized(phoneClaimLock) {
            val btAddress = activePhoneBt ?: return null
            if (phoneIdByBtAddress[btAddress].isNullOrBlank()) return null
            val address = lastAddressByPhone[btAddress] ?: return null
            action(address)
            address
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
        val targets = buildList {
            lastKnownPhoneIp?.let { add(it) }
            synchronized(recentPhoneIps) { addAll(recentPhoneIps) }
        }.distinct()
        notifyCompanionOfShutdown(targets)
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
    private fun notifyCompanionOfShutdown(targets: List<String>) {
        if (targets.isEmpty()) return
        for (ip in targets) {
            scope.launch {
                runCatching {
                    java.net.Socket().use { s ->
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

    fun init(context: Context) {
        appContext = context.applicationContext
        restoreKnownIps(context)
        registerBtDisconnectReceiver(context)
        synchronized(this) {
            if (started) return
            started = true
        }
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
            val apInterface = prefs.wppApInterface.first()

            // Common readiness chokepoint. Ignition/socket-death recovery used to
            // bypass this and could publish a telematics address before the car AP.
            awaitApInterface(apInterface)
            if (!sessionAdmission.isCurrent(expectedOwner)) {
                OalLog.i(TAG, "Not advertising — WPP owner changed while waiting for AP")
                return
            }

            val ssid = intent?.getStringExtra("ssid")?.takeIf { it.isNotBlank() }
                ?: prefs.hotspotSsid.first()
            val psk = intent?.getStringExtra("psk")
                ?: prefs.hotspotPassword.first()
            val bssid = intent?.getStringExtra("bssid")?.takeIf { it.isNotBlank() }
                ?: prefs.wppBssid.first()
            val port = intent?.getIntExtra("port", 5277) ?: 5277
            // The address the phone is told to dial. Detected from the live
            // interface rather than stored, because it changes with the network.
            val ip = intent?.getStringExtra("ip")?.takeIf { it.isNotBlank() }
                ?: prefs.wppLocalIp.first().takeIf { it.isNotBlank() }
                ?: localIpv4Address(apInterface)
                ?: ""

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
                manualIp = intent?.getStringExtra("ip")?.takeIf { it.isNotBlank() }
                    ?: prefs.wppLocalIp.first().takeIf { it.isNotBlank() },
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
                "advertising anyway with whatever address is available")
    }

    /**
     * Last-resort scan for the companion across EVERY network the head unit is on.
     *
     * A Blazer has two independent radios and they sit on different networks:
     *
     *   ap_br_swlan0  the telematics module's AP ("Blazing", 10.2.110.x) that the
     *                 phone joins — and whose inbound filtering is the reason the
     *                 loopback endpoint exists at all
     *   wlan0         the head unit's own Android WiFi, a client only. Observed on
     *                 home WiFi (192.168.0.104) and on the phone's hotspot
     *                 (10.187.47.188), never on Blazing
     *
     * An earlier version scanned only the telematics subnet and so missed the
     * companion entirely when it was reachable over wlan0: at 16:34:29 the scan of
     * 10.2.110.0/24 found nothing, and 25s later discovery reported the phone at
     * 10.187.47.73 — the phone-hotspot subnet, via the other radio.
     *
     * Which radio carries the traffic does not matter to the design; only that the
     * head unit can dial the companion. Reaching it over the phone's own hotspot is
     * arguably better, since the phone is the AP there and the telematics module's
     * filtering is bypassed completely.
     */
    private fun findCompanionOnAnySubnet(
        manualIp: String?,
        phoneBtAddress: String,
        phoneBtName: String?,
        overallTimeoutMs: Int = 20_000,
    ): ResolvedCompanion? {
        val localIps = buildList {
            manualIp?.takeIf { it.isNotBlank() }?.let { add(it) }
            addAll(allLocalIpv4())
        }.distinct()
        if (localIps.isEmpty()) return null

        val deadline = System.currentTimeMillis() + overallTimeoutMs
        for (ip in localIps) {
            val prefix = ip.substringBeforeLast('.', "")
            if (prefix.isEmpty()) continue
            val remainingMs = (deadline - System.currentTimeMillis()).coerceAtLeast(1L).toInt()
            OalLog.i(TAG, "Scanning $prefix.0/24 for the companion")
            scanSubnet(
                prefix = prefix,
                ourIp = ip,
                phoneBtAddress = phoneBtAddress,
                phoneBtName = phoneBtName,
                overallTimeoutMs = remainingMs,
            )?.let { return it }
            if (System.currentTimeMillis() >= deadline) break
        }
        OalLog.w(TAG, "No companion on any local subnet (${localIps.joinToString()})")
        return null
    }

    /** Every non-loopback IPv4 the head unit currently holds, across both radios. */
    private fun allLocalIpv4(): List<String> = runCatching {
        java.net.NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback && !it.isVirtual }
            // The telematics module also exposes several vt* interfaces on
            // 172.16.x that lead nowhere useful; scanning them wastes seconds.
            .filterNot { it.name.startsWith("vt") }
            .flatMap { nif ->
                nif.inetAddresses.toList()
                    .filterIsInstance<java.net.Inet4Address>()
                    .filter { !it.isLoopbackAddress && !it.isLinkLocalAddress }
                    .mapNotNull { it.hostAddress }
            }
    }.getOrDefault(emptyList())

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
    ): CompanionProbe? = runCatching {
        java.net.Socket().use { sock ->
            sock.connect(java.net.InetSocketAddress(phoneIp, IDENTITY_PORT), connectTimeoutMs)
            sock.soTimeout = connectTimeoutMs
            sock.getOutputStream().apply { write("OAL?\n".toByteArray()); flush() }
            val reply = sock.getInputStream().bufferedReader().readLine().orEmpty()
            CompanionIdentityPolicy.parseProbe(reply)
        }
    }.getOrNull()

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

    private fun localIpv4Address(apInterface: String): String? = runCatching {
        data class Candidate(val name: String, val addr: String)

        val candidates = java.net.NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback && !it.isVirtual }
            .flatMap { nif ->
                nif.inetAddresses.toList()
                    .filterIsInstance<java.net.Inet4Address>()
                    .filter { !it.isLoopbackAddress && !it.isLinkLocalAddress }
                    .mapNotNull { it.hostAddress?.let { a -> Candidate(nif.name, a) } }
            }
        if (candidates.isEmpty()) return@runCatching null

        // Interface tiers. The configured AP interface wins outright; the rest
        // are fallbacks for OEMs that name it differently, so a wrong setting
        // degrades to a guess rather than to nothing.
        fun tier(name: String): Int = when {
            name == apInterface -> 0
            name.startsWith("ap_br_") || name.startsWith("ap") ||
                name.startsWith("swlan") || name == "wlan1" -> 1
            name.startsWith("wlan") -> 2
            else -> 3
        }
        fun isPrivate(a: String): Boolean =
            a.startsWith("10.") || a.startsWith("192.168.") ||
                (a.startsWith("172.") && a.substringAfter('.').substringBefore('.').toIntOrNull()
                    ?.let { it in 16..31 } == true)

        // A gateway-looking address (x.y.z.1) is a strong signal for "this
        // interface IS the access point", since an AP is the gateway for its
        // clients. Ranks above interface-name guessing, which varies by OEM.
        fun looksLikeGateway(a: String) = a.endsWith(".1")

        val chosen = candidates.sortedWith(
            compareBy<Candidate> { tier(it.name) }
                .thenBy { if (looksLikeGateway(it.addr)) 0 else 1 }
                .thenBy { if (isPrivate(it.addr)) 0 else 1 }
        ).first()

        // Always log, not just when ambiguous: the car AP has used several
        // different subnets across multi-week epochs, so the value used by each
        // handshake must remain visible even though it is usually stable per cycle.
        run {
            OalLog.i(TAG, "Local address candidates: " +
                    candidates.joinToString { "${it.name}=${it.addr}" } +
                    " — advertising ${chosen.addr} (${chosen.name}). " +
                    "If the phone joins the AP but projection never starts, compare this " +
                    "against the phone's own address: they must share a subnet.")
        }
        chosen.addr
    }.getOrNull()

    private fun startAdvertising(
        context: Context,
        creds: AaWirelessBtServer.WifiCredentials,
        manualIp: String?,
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
            if (!sessionAdmission.isCurrent(expectedOwner)) {
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
        // Re-resolve on every handshake. The AP is usually stable across ignition
        // cycles but has changed across longer epochs, so no cached address is a
        // permanent source of truth.
        // Honour a manual override if one is set, otherwise look it up live.
        bt.setAddressResolver { manualIp ?: localIpv4Address(apInterface) }

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

            // Resolve the companion's address, preferring one discovery already
            // found. The sweep is the fallback, not the primary: it runs on the
            // BT dial-back, which is seconds after the phone associates and
            // often before it has an address or its servers are listening.
            // Measured in-vehicle: this sweep found nothing at 15:45:43 while
            // OAL's own discovery found the phone at 15:47:01, same subnet.
            //
            // A cached address is re-verified rather than trusted: the phone's
            // address changes when the AP is resubnetted each ignition cycle.
            // Try every address we know of, not just the newest. Toggling
            // Bluetooth drops WiFi, so the phone moves between the car's AP, its
            // own hotspot subnet and home WiFi within a single session — observed
            // in one log: 10.2.110.109, then 10.187.47.73, then the car itself on
            // 192.168.0.104. A single cached value is stale as often as not.
            // Only consider addresses that could still be valid on the network we
            // are on NOW.
            //
            // The telematics AP is reassigned a new subnet across a long park, so
            // if that ever happens the cached addresses are provably dead, being
            // on a /24 the car no longer has an interface on, and probing them
            // just burns time before the scan that was always going to be needed.
            //
            // Worth being precise about how likely that is: measured across 29
            // sessions and four days, including parks of 20h and 23h, the access
            // point stayed on 10.2.110.66 and the phone kept 10.2.110.109 every
            // single time. The one sighting of a different subnet
            // (172.16.101.100) was our own cold-start bug picking a vt*
            // telematics interface before ap_br_swlan0 existed, fixed in 0.1.401
            // — not the access point being reassigned.
            //
            // So this filter is insurance, not the common case. It stays because
            // it is a cheap fact about the current interfaces rather than a guess
            // about elapsed time, but the fast path — try the remembered address
            // first — is what actually matters.
            val localPrefixes = allLocalIpv4().mapNotNull {
                it.substringBeforeLast('.', "").takeIf(String::isNotEmpty)
            }.toSet()
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
                ip.substringBeforeLast('.', "") in localPrefixes
            }
            val dropped = allKnown - candidates.toSet()
            if (dropped.isNotEmpty()) {
                OalLog.i(TAG, "Ignoring ${dropped.joinToString()} — not on any subnet " +
                        "this car is currently on (${localPrefixes.joinToString()})")
            }
            // companionIp tracks WHICH address answered, from either path. It has
            // to be set everywhere proxyPort is, or the dial below is skipped and
            // the car listens forever while the phone waits for a car socket.
            var proxyPort: Int? = null
            var companionIp: String? = null
            // Known addresses get a much longer budget than a blind scan host.
            //
            // These came from discovery actually talking to the phone, so they
            // are worth waiting on: the telematics bridge is slow and 1200ms is
            // marginal for a real round trip, which is exactly why a companion
            // that discovery reached seconds earlier can look absent here.
            // Retry over a window rather than probing once, immediately.
            //
            // This probe runs 1-3s after the phone dialled back, i.e. seconds
            // after it re-associated to the car's access point — and the bridge
            // does not pass traffic to a just-associated client that quickly.
            // Measured: the handshake probe to 10.2.110.109 failed at 21:03:01
            // while discovery reached the SAME address at 21:03:06 and every 16s
            // before and after, all evening. The address was never wrong; we were
            // asking too early.
            //
            // Six attempts at 2s spacing covers ~14s, which is past the point
            // where discovery starts succeeding, and stops the moment one works.
            // Probe at most two remembered addresses before falling back to the
            // scan.
            //
            // Android randomises the WiFi MAC per network by default, and a new
            // MAC means a new DHCP lease — so for most users these addresses go
            // stale regularly. Walking four of them at 2500ms each would spend
            // 10s failing before a ~5s scan that was always going to find the
            // phone anyway, and the handshake is time-critical: take too long and
            // the phone gives up and waits out its own retry timer.
            //
            // One or two probes is a cheap bet on the address being unchanged.
            // More than that is a tax on everyone whose address did change.
            for (ip in candidates.take(MAX_PRESCAN_PROBES)) {
                val probe = askCompanion(ip, connectTimeoutMs = 2500)
                if (probe != null && probeMatchesBluetoothOwner(
                        phoneBtAddress, phoneBtName, ip, probe)) {
                    if (!phoneClaimStillOwned(phoneBtAddress, claimGeneration)) {
                        OalLog.i(TAG, "Ignoring identity result from superseded phone claim $phoneBtAddress")
                        return@setEndpointResolver null
                    }
                    OalLog.i(TAG, "Identity-matched companion at $ip " +
                            "id=${probe.phoneId} reports AA proxy on port ${probe.proxyPort}")
                    proxyPort = probe.proxyPort
                    companionIp = ip
                    lastKnownPhoneIp = ip
                    lastAddressByPhone[phoneBtAddress] = ip
                    break
                }
            }
            if (proxyPort == null) {
                if (candidates.isNotEmpty()) {
                    OalLog.i(TAG, "Companion did not answer at ${candidates.joinToString()} — re-scanning")
                    // Deliberately NOT clearing lastKnownPhoneIp. A failed probe
                    // here usually means the bridge was slow, not that the phone
                    // moved — and discarding the one address discovery gave us
                    // leaves the next attempt with nothing but a blind scan.
                }
                // The scan returns the port too — asking again would cost another
                // slow round trip over the telematics bridge.
                findCompanionOnAnySubnet(
                    manualIp = manualIp,
                    phoneBtAddress = phoneBtAddress,
                    phoneBtName = phoneBtName,
                )?.let { resolved ->
                    if (!phoneClaimStillOwned(phoneBtAddress, claimGeneration)) {
                        OalLog.i(TAG, "Ignoring scan result from superseded phone claim $phoneBtAddress")
                        return@setEndpointResolver null
                    }
                    val ip = resolved.host
                    val probe = resolved.probe
                    lastKnownPhoneIp = ip
                    // Cache only after identity has matched the Bluetooth owner.
                    lastAddressByPhone[phoneBtAddress] = ip
                    proxyPort = probe.proxyPort
                    companionIp = ip
                }
            }
            when {
                proxyPort != null -> {
                    if (!phoneClaimStillOwned(phoneBtAddress, claimGeneration)) {
                        OalLog.i(TAG, "Not dialing endpoint from superseded phone claim $phoneBtAddress")
                        return@setEndpointResolver null
                    }
                    // Open the car->companion socket BEFORE the handshake tells
                    // Android Auto where to connect. AA reaches the proxy within
                    // ~2s of the Bluetooth handshake; the car used to still be
                    // looking for the companion at that point, so the proxy sat
                    // holding an AA connection with no car behind it and gave up
                    // after 30s. Dialling here removes that race entirely.
                    // Non-null by construction: proxyPort is only set alongside
                    // companionIp. Guarded anyway, and loudly, because silently
                    // skipping the dial is the failure this caused — the car
                    // listened forever while the phone waited for a car socket.
                    // Remember which companion belongs to the Bluetooth-connected
                    // phone so the UI can mark it in the discovery list.
                    val dialTarget = companionIp
                    val accepted = synchronized(phoneClaimLock) {
                        if (!activePhoneBt.equals(phoneBtAddress, ignoreCase = true) ||
                            phoneClaimGeneration != claimGeneration
                        ) {
                            false
                        } else {
                            activePhoneCompanionIp = dialTarget
                            bootstrapAttempt.set(null)
                            OalLog.i(TAG, "CONNECT SUMMARY: endpoint=loopback proxy " +
                                    "127.0.0.1:$proxyPort via companion at $dialTarget " +
                                    "phone=$phoneBtAddress — this is the working path")
                            if (dialTarget.isNullOrBlank()) {
                                OalLog.e(TAG, "Have proxy port $proxyPort but no companion address — " +
                                        "cannot dial; the session will not connect")
                            } else {
                                onCompanionSelected?.invoke(dialTarget)
                            }
                            true
                        }
                    }
                    if (!accepted) {
                        OalLog.i(TAG, "Not dialing endpoint from superseded phone claim $phoneBtAddress")
                        return@setEndpointResolver null
                    }
                    AaWirelessBtServer.Endpoint.PhoneLoopback(proxyPort)
                }
                else -> {
                    if (!phoneClaimStillOwned(phoneBtAddress, claimGeneration)) {
                        OalLog.i(TAG, "Not starting bootstrap for superseded phone claim $phoneBtAddress")
                        return@setEndpointResolver null
                    }
                    // The phone is not on the car AP yet, so the companion cannot
                    // answer. Use the port both new apps reserve in advance. WPP
                    // moves the phone onto the AP, Android Auto can attach to its
                    // local proxy immediately, and discovery then supplies the
                    // car-side socket to that same waiting bridge.
                    val attempt = BootstrapAttempt(
                        phoneBtAddress = phoneBtAddress,
                        phoneBtName = phoneBtName,
                        expectedPhoneId = phoneIdByBtAddress[phoneBtAddress],
                        claimGeneration = claimGeneration,
                        generation = bootstrapAttemptSequence.incrementAndGet(),
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
                            "127.0.0.1:${OalProtocol.WPP_PROXY_PORT} phone=$phoneBtAddress " +
                            "knownAddrs=${allKnown.joinToString().ifEmpty { "none" }} " +
                            "usable=${candidates.joinToString().ifEmpty { "none" }} — " +
                            "waiting for companion discovery after AP association")
                    startBootstrapCompanionDiscovery(attempt, candidates, manualIp)
                    AaWirelessBtServer.Endpoint.PhoneLoopback(OalProtocol.WPP_PROXY_PORT)
                }
            }
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
        bt.start()
    }
}
