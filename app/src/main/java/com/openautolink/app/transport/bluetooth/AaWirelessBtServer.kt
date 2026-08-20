package com.openautolink.app.transport.bluetooth

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.content.pm.PackageManager
import android.os.SystemClock
import androidx.core.content.ContextCompat
import com.openautolink.app.diagnostics.OalLog
import com.openautolink.app.proto.Wireless
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.DataInputStream
import java.io.IOException
import java.io.OutputStream
import java.nio.ByteBuffer
import java.util.UUID

/**
 * Advertises the head unit as an Android Auto Wireless source over Bluetooth, and
 * performs the small RFCOMM handshake that hands the phone our IP, port and WiFi
 * credentials.
 *
 * ## Why this exists
 *
 * Android Auto 17.4 shipped `WirelessStartupReceiver` with `android:enabled="false"`
 * and `WirelessStartupActivity` with `android:exported="false"`, which killed the
 * broadcast/intent route every third-party head unit used to start wireless
 * projection. That route is unrecoverable from app code — not even `adb pm enable`
 * can turn the receiver back on.
 *
 * The Bluetooth route that real OEM head units use is **untouched**. Verified in the
 * 17.4 teardown:
 *
 * ```
 * WifiBluetoothReceiver          exported="true"  enabled="true"  permission=(none)
 *   listens: android.bluetooth.device.action.ACL_CONNECTED
 *   emits:   com.google.android.projection.gearhead.START_WIRELESS_PROJECTION
 * WirelessSetupSharedService     exported="true"  enabled="true"
 * ```
 *
 * Gearhead decides whether a bonded device is "AAW capable" by running an SDP query
 * against it (`owv` / `CAR.BTCapsStore`: `fetchUuidsWithSdp()`, then matching against
 * [AA_UUID] or [AA_UUID_ALT]). If it matches, the phone dials **back** to us on that
 * same UUID (`ohp`: `createRfcommSocketToServiceRecord`) and expects the handshake
 * implemented below.
 *
 * So advertising alone is not enough — we must actually answer. Fortunately the
 * exchange is three messages.
 *
 * ## Wire format
 *
 * ```
 * [2 bytes payload length, big-endian][2 bytes message type][protobuf payload]
 * ```
 *
 * ## Exchange
 *
 * ```
 * car   -> type 1  WifiStartRequest  { ip_address, port, status }
 * phone -> type 2  (phone asks for the network's security details)
 * car   -> type 3  WifiInfoResponse  { ssid, key, bssid, security_mode, access_point_type }
 * ```
 *
 * Then the socket is held open while the phone associates and opens the TCP session.
 *
 * ## Note on "the phone is already on this network"
 *
 * The credentials we send do not have to describe a network the phone must *join*.
 * Gearhead short-circuits when it is already associated
 * (`pdp`: `"already connected to desired network: %s, starting"`), so naming the
 * network both devices already share is a valid, and in fact simpler, configuration.
 *
 * ## Scope
 *
 * This is a presence + handshake advertiser. It does not carry projection data —
 * once the phone has our IP and port it connects over TCP exactly as before.
 */
class AaWirelessBtServer(
    private val context: Context,
    parentScope: CoroutineScope,
    private val onUnexpectedAcceptLoopExit: () -> Unit = {},
    onSdpPublished: (Long) -> Unit = {},
    onPhoneDialback: (Long) -> Unit = {},
) {
    // Deliberately does NOT inherit parentScope.coroutineContext.
    //
    // Inheriting it made this SupervisorJob a child of the caller's job, so
    // stop() -> scope.cancel() tore down the caller too. A re-advertise, which
    // is "stop the server, wait, start it again" running on the control's scope,
    // therefore cancelled itself at the first suspension point and silently
    // never restarted the advertiser:
    //
    //     21:13:31  Companion answered on attempt 2 — re-advertising
    //     (nothing — no "advertiser stopped", no "Listening on ... UUID")
    //
    // Only the dispatcher is taken from the parent. Lifecycle is ours, and stop()
    // now affects nothing but this server.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val outcomeObserver = AsyncBluetoothOutcomeObserver(
        enqueue = { observation ->
            scope.launch(CoroutineName("AaWirelessBt-OutcomeObserver")) { observation() }
        },
        onSdpPublished = onSdpPublished,
        onPhoneDialback = onPhoneDialback,
        onFailure = { error ->
            OalLog.w(TAG, "Bluetooth outcome observer failed: ${error.message}")
        },
    )

    private var aaServerSocket: BluetoothServerSocket? = null
    private var hfpServerSocket: BluetoothServerSocket? = null
    private var acceptJob: Job? = null
    private var hfpJob: Job? = null

    @Volatile
    private var running = false

    /**
     * True only while the advertiser was started AND its accept loop is alive.
     *
     * Bluetooth going down leaves the server object in memory. The accept loop
     * exits after ten failures, but [running] historically stayed true, so the
     * next ignition's ensureAdvertising() silently treated a dead SDP record as
     * healthy and never told the phone which Wi-Fi to join.
     */
    val isRunning: Boolean
        get() = AdvertiserLiveness.isLive(
            startRequested = running,
            acceptLoopActive = acceptJob?.isActive == true,
        )

    @Volatile
    private var credentials: WifiCredentials? = null

    /**
     * Network details handed to the phone during the handshake.
     *
     * @param ssid    network the phone should be on to reach us
     * @param psk     pre-shared key; may be empty when the phone is already associated
     * @param bssid   AP BSSID; may be empty if unknown
     * @param ip      address the phone should open the projection socket to (ours)
     * @param port    projection TCP port (OAL's companion/direct port)
     */
    data class WifiCredentials(
        val ssid: String,
        val psk: String,
        val bssid: String,
        val ip: String,
        val port: Int,
        /**
         * Frequencies (MHz) the head unit's AP can use, e.g. [5805].
         *
         * Must be non-empty: the phone intersects this with its own scan results
         * and fails with NO_COMPATIBLE_WIFI_CHANNEL_FOUND when the intersection
         * is empty. Detected from the live AP rather than hardcoded, because the
         * car picks its channel at boot.
         */
        val channelsMhz: List<Int> = emptyList(),
    )

    /**
     * Supply/refresh the details sent on the next handshake. Safe to call at any
     * time; a handshake that arrives before this is set is rejected rather than
     * answered with placeholder data the phone would act on.
     */
    fun updateCredentials(creds: WifiCredentials) {
        credentials = creds
        OalLog.i(TAG, "Credentials set: ssid=${creds.ssid} ip=${creds.ip}:${creds.port} " +
                "bssid=${creds.bssid.ifEmpty { "(none)" }} psk=${if (creds.psk.isEmpty()) "(open/none)" else "****"}")
    }

    /**
     * Probe whether this device can publish an SDP record on the Android Auto UUID
     * at all. Cheap: opens a listener and immediately closes it.
     *
     * Worth calling before relying on this path — on a locked-down BT stack the
     * `listenUsingRfcommWithServiceRecord` call is where it would fail, and knowing
     * that is far more useful than a silent no-op.
     */
    @SuppressLint("MissingPermission")
    fun canAdvertise(): Boolean {
        if (!hasBtPermission()) return false
        @Suppress("DEPRECATION")
        val adapter = BluetoothAdapter.getDefaultAdapter() ?: return false
        if (!adapter.isEnabled) return false
        return try {
            adapter.listenUsingRfcommWithServiceRecord("OAL AAW Probe", AA_UUID).close()
            OalLog.i(TAG, "SDP advertise probe OK — this device can publish the AA Wireless UUID")
            true
        } catch (e: Exception) {
            OalLog.w(TAG, "SDP advertise probe FAILED: ${e.message}")
            false
        }
    }

    fun start() {
        if (running) return
        if (!hasBtPermission()) {
            OalLog.w(TAG, "BLUETOOTH_CONNECT not granted — AA wireless BT advertising disabled")
            return
        }
        running = true
        acceptJob = scope.launch(CoroutineName("AaWirelessBt-Accept")) {
            var socketDied = false
            try {
                socketDied = acceptLoop()
            } finally {
                // This finally belongs to the exact accept Job that stopped. Do
                // not poll AaWirelessBtControl.btServer: ignition recovery can
                // replace that global reference while this older Job unwinds.
                val startWasStillRequested = running
                running = false
                if (AdvertiserLiveness.shouldRecoverAfterExit(
                        startRequested = startWasStillRequested,
                        socketDied = socketDied,
                    )
                ) {
                    OalLog.i(TAG, "Dead Bluetooth accept loop fully exited — requesting advertiser recovery")
                    onUnexpectedAcceptLoopExit()
                }
            }
        }
        hfpJob = scope.launch(CoroutineName("AaWirelessBt-Hfp")) { hfpPresenceLoop() }
    }

    /**
     * Secondary RFCOMM listener on the Hands-Free Profile UUID.
     *
     * Purpose is narrow and worth stating, because it is easy to mistake for a car
     * feature: on a **non-automotive** device (a tablet standing in for a head unit)
     * nothing else claims an audio profile, so the phone tears the ACL link down
     * moments after pairing — before gearhead can act on `AAW status (SUPPORTED)`.
     * Holding an HFP service record makes the tablet look enough like a hands-free
     * device for the link to persist.
     *
     * In a real head unit this is redundant: the OEM Bluetooth stack owns HFP/A2DP
     * and keeps the link up. Confirmed from vehicle logs — the car's own
     * `HfpPresenceServer` publishes its record on every session, and the phone has
     * connected to it **zero** times, because it is already talking to the genuine
     * hands-free device.
     *
     * This is presence only. We never speak AT commands and never accept SCO; call
     * audio from an unprivileged app is gated behind `BLUETOOTH_PRIVILEGED`.
     */
    @SuppressLint("MissingPermission")
    private suspend fun hfpPresenceLoop() {
        @Suppress("DEPRECATION")
        val adapter = BluetoothAdapter.getDefaultAdapter() ?: return
        if (!adapter.isEnabled) return
        try {
            hfpServerSocket = adapter.listenUsingRfcommWithServiceRecord("Hands-Free Unit", HFP_UUID)
            OalLog.i(TAG, "HFP presence record published (holds the ACL link on non-automotive devices)")
        } catch (e: Exception) {
            OalLog.w(TAG, "HFP presence record failed: ${e.message}")
            return
        }
        var hfpFailures = 0
        while (running && scope.isActive) {
            val client = try {
                hfpServerSocket?.accept()
            } catch (e: Exception) {
                // Same dead-socket problem as the AA loop: when Bluetooth goes
                // away the socket object survives, so this retried forever and
                // contributed half of the 7,736 accept-failure lines in one
                // vehicle log. Give up rather than spin.
                hfpFailures++
                if (running && hfpFailures <= 3) {
                    OalLog.w(TAG, "HFP accept() failed: ${e.message}")
                } else if (running && hfpFailures == MAX_ACCEPT_FAILURES) {
                    OalLog.w(TAG, "HFP accept() still failing after $hfpFailures tries — " +
                            "giving up on the HFP presence record")
                }
                if (hfpFailures >= MAX_ACCEPT_FAILURES) break
                try { delay(1000) } catch (_: Throwable) { break }
                if (hfpServerSocket == null) break
                continue
            }
            if (client != null) {
                val remote = runCatching { client.remoteDevice?.address ?: "?" }.getOrDefault("?")
                OalLog.i(TAG, "HFP presence connection from $remote — draining and holding briefly")
                scope.launch {
                    runCatching {
                        // Read once so the phone sees a live peer rather than an
                        // instant close, then let it go. We cannot serve HFP.
                        client.inputStream.read(ByteArray(1024))
                    }
                    runCatching { client.close() }
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun acceptLoop(): Boolean {
        @Suppress("DEPRECATION")
        val adapter = BluetoothAdapter.getDefaultAdapter()
        if (adapter == null || !adapter.isEnabled) {
            OalLog.w(TAG, "No BT adapter or adapter disabled — AA wireless BT not started")
            running = false
            return false
        }

        try {
            aaServerSocket = adapter.listenUsingRfcommWithServiceRecord(SDP_NAME, AA_UUID)
        } catch (e: Exception) {
            OalLog.w(TAG, "Failed to publish AA Wireless SDP record: ${e.message}")
            running = false
            return false
        }
        outcomeObserver.sdpPublishedAt(SystemClock.elapsedRealtime())
        OalLog.i(TAG, "Listening on Android Auto Wireless UUID $AA_UUID — " +
                "phone should now see this head unit as AAW capable")

        var consecutiveAcceptFailures = 0
        while (running && scope.isActive) {
            var threw = false
            val client: BluetoothSocket? = try {
                aaServerSocket?.accept()
            } catch (e: Exception) {
                // Log the first few only: a dead socket produces one of these per
                // retry, and the detail is identical every time.
                if (running && consecutiveAcceptFailures < 3) {
                    OalLog.w(TAG, "AA BT accept() failed: ${e.message}")
                }
                threw = true
                null
            }

            if (client != null) {
                outcomeObserver.phoneDialbackAt(SystemClock.elapsedRealtime())
                val remoteDevice = runCatching { client.remoteDevice }.getOrNull()
                val remote = runCatching { remoteDevice?.address ?: "?" }.getOrDefault("?")
                val remoteName = runCatching { remoteDevice?.name }.getOrNull()
                OalLog.i(TAG, "Phone dialled back on the AA Wireless UUID from $remote" +
                        (remoteName?.let { " name=$it" } ?: " name=unknown"))
                // Flag it here rather than inside handleHandshake: the window that
                // matters starts the instant the phone dials, and the coroutine
                // may not be scheduled for several milliseconds.
                val handshakeLease = AaWirelessBtControl.beginHandshake()
                if (handshakeLease == null) {
                    OalLog.i(TAG, "Rejecting dial-back while compatibility re-advertise replaces SDP")
                    runCatching { client.close() }
                    continue
                }
                val handshakeJob = scope.launch(CoroutineName("AaWirelessBt-Handshake")) {
                    handleHandshake(client, remote, remoteName, handshakeLease)
                }
                handshakeJob.invokeOnCompletion {
                    // This also runs when the coroutine is cancelled before its
                    // body starts, so accepted sockets cannot leak ownership.
                    handshakeLease.finish()
                    AaWirelessBtControl.flushPendingReadvertise()
                }
                continue
            }

            // accept() returned null or threw.
            //
            // A 1s backoff alone is not enough: when Bluetooth goes away the
            // socket object still exists, so aaServerSocket != null and the loop
            // retried a permanently dead socket forever. One in-vehicle log
            // carried 7,736 of these lines — hours of ~2/second across this loop
            // and the HFP one — burning CPU and drowning everything else.
            //
            // Give up after a run of consecutive failures and republish the SDP
            // record instead: a fresh listener is the only thing that recovers a
            // dead one, and republishing is also what makes the phone dial back.
            if (aaServerSocket == null) {
                if (running) OalLog.w(TAG, "AA BT server socket closed — exiting accept loop")
                break
            }
            if (threw) {
                consecutiveAcceptFailures++
                if (consecutiveAcceptFailures >= MAX_ACCEPT_FAILURES) {
                    OalLog.w(TAG, "AA BT accept() failed $consecutiveAcceptFailures times — " +
                            "the socket is dead, republishing the SDP record")
                    // Republish HERE rather than merely exiting. The previous
                    // revision broke out of the loop and left a comment saying
                    // the advertiser would republish — nothing did. The
                    // advertiser only (re)starts on a transport-preference
                    // change, so after an ignition cycle killed the socket the
                    // car silently stopped advertising for the rest of the
                    // session: no SDP record, so the phone had nothing to dial
                    // back to and was never told which WiFi to join.
                    // Report socket death only after this exact accept Job exits;
                    // the server-owned finally decides whether recovery is still
                    // wanted and invokes the callback once.
                    return true
                }
                try { delay(1000) } catch (_: Throwable) { break }
            } else {
                consecutiveAcceptFailures = 0
            }
        }
        return false
    }

    /**
     * Resolves the head unit's current IPv4 at handshake time.
     *
     * Not a stored value. The car AP is stable across ordinary ignition cycles but
     * has used multiple subnets across multi-week epochs, so resolve at the moment
     * of use instead of treating any historical address as permanent.
     */
    fun interface AddressResolver {
        fun currentIpv4(): String?
    }

    /**
     * Endpoint advertised to Android Auto: where the phone should open TCP.
     *
     * Two shapes, and the second is the important one:
     *
     *  - [CarDirect] — the car's own address. Requires the phone to open an
     *    inbound connection across the car's access point, which GM's AP does
     *    not permit. Correct on a shared network, useless on the car's own.
     *
     *  - [PhoneLoopback] — 127.0.0.1 and the companion's local proxy port. The
     *    phone connects to itself, so no packet crosses the air and no AP can
     *    interfere. The bytes then travel over the TCP connection the CAR
     *    opened outbound to the companion, which is permitted.
     *
     * The loopback shape is not a new trick: it is exactly what OAL sent in the
     * `WirelessStartupReceiver` broadcast (ip_address=127.0.0.1,
     * projection_port=<proxy>) which worked in production until 17.4 disabled
     * that receiver. This carries the same values over the Bluetooth handshake
     * instead.
     */
    sealed interface Endpoint {
        val ip: String
        val port: Int

        data class CarDirect(override val ip: String, override val port: Int) : Endpoint
        data class PhoneLoopback(override val port: Int) : Endpoint {
            override val ip: String get() = "127.0.0.1"
        }
    }

    /**
     * Supplies the endpoint at handshake time.
     *
     * Resolved per handshake rather than trusting a cached companion address: the
     * phone's AP address can change even though the reserved loopback port is stable,
     * and the car's AP subnet can change across ignition cycles.
     */
    fun interface EndpointResolver {
        /**
         * @param phoneBtAddress the Bluetooth address of the phone that dialled,
         *   so two phones in the car cannot be handed each other's proxy port.
         *   The port is meaningful only on the phone that owns it — 127.0.0.1
         *   resolves on whichever device connects — so a mixed-up port sends a
         *   phone's Android Auto to a closed socket on its own loopback.
         * @param phoneBtName the remote Bluetooth device name from the same socket.
         *   Companion identity must agree with this owner (or a previously learned
         *   phone_id binding) before an endpoint is accepted.
         */
        fun currentEndpoint(phoneBtAddress: String, phoneBtName: String?): Endpoint?
    }

    @Volatile
    private var endpointResolver: EndpointResolver? = null

    fun setEndpointResolver(resolver: EndpointResolver) {
        endpointResolver = resolver
    }

    @Volatile
    private var addressResolver: AddressResolver? = null

    /** Supply the live-address lookup used for every handshake. */
    fun setAddressResolver(resolver: AddressResolver) {
        addressResolver = resolver
    }

    private suspend fun handleHandshake(
        socket: BluetoothSocket,
        phoneBtAddress: String,
        phoneBtName: String?,
        handshakeLease: AaWirelessBtControl.HandshakeLease,
    ) {
        val stored = credentials
        if (stored == null) {
            OalLog.w(TAG, "Handshake attempted with no credentials set — closing. " +
                    "updateCredentials() must be called before the phone connects.")
            runCatching { socket.close() }
            return
        }

        // Re-read the address now, not at start-up. The AP subnet changes on every
        // ignition cycle; sending a stale address means the phone dials into
        // nothing and silently gives up.
        // Prefer the companion's loopback proxy when one is available: it is the
        // only endpoint the car's access point cannot block, because the phone
        // connects to itself.
        val endpoint = endpointResolver?.currentEndpoint(phoneBtAddress, phoneBtName)
        val creds = when (endpoint) {
            is Endpoint.PhoneLoopback -> {
                OalLog.i(TAG, "Advertising the companion's loopback proxy " +
                        "${endpoint.ip}:${endpoint.port} — the phone connects to itself, " +
                        "so the car's AP never has to accept an inbound connection")
                stored.copy(ip = endpoint.ip, port = endpoint.port)
            }
            is Endpoint.CarDirect -> {
                OalLog.i(TAG, "No companion proxy — advertising this head unit at " +
                        "${endpoint.ip}:${endpoint.port}. This needs the AP to allow " +
                        "inbound connections; if projection never starts, that is why.")
                stored.copy(ip = endpoint.ip, port = endpoint.port)
            }
            null -> {
                // Fall back to the old address-only resolution rather than
                // failing the handshake outright.
                val liveIp = addressResolver?.currentIpv4()
                when {
                    liveIp.isNullOrBlank() -> {
                        OalLog.w(TAG, "Could not resolve an endpoint — falling back to ${stored.ip}")
                        stored
                    }
                    liveIp != stored.ip -> {
                        OalLog.i(TAG, "Head unit address changed ${stored.ip} -> $liveIp " +
                                "(AP resubnetted since last start) — advertising the current one")
                        stored.copy(ip = liveIp)
                    }
                    else -> stored
                }
            }
        }

        try {
            val input = DataInputStream(socket.inputStream)
            val output = socket.outputStream

            // Version exchange FIRST. gearhead warns
            //   "Trying to proceed with WifiStartRequest before we received
            //    WifiVersionRequest"
            // on every session when this is skipped — but the real cost is
            // silent: WifiVersionRequest is the ONLY carrier of
            // WifiProjectionProtocolInfo, so without it the phone never learns
            // our TCP endpoint and logs
            //   "No WPP on TCP configuration found in storage for the head unit"
            // and falls back to the WiFi-credential path.
            OalLog.i(TAG, "Handshake: sending WifiVersionRequest (type $MSG_WIFI_VERSION_REQUEST) " +
                    "v$WPP_VERSION_MAJOR.$WPP_VERSION_MINOR with WPP-over-TCP endpoint " +
                    "${creds.ip}:${creds.port}")
            sendMessage(
                output,
                MSG_WIFI_VERSION_REQUEST,
                Wireless.WifiVersionRequest.newBuilder()
                    .setVersionMajor(WPP_VERSION_MAJOR)
                    .setVersionMinor(WPP_VERSION_MINOR)
                    .setWppInfo(
                        Wireless.WifiProjectionProtocolInfo.newBuilder()
                            .setIpAddress(creds.ip)
                            .setPort(creds.port)
                            .build()
                    )
                    .build()
                    .toByteArray(),
            )

            // The phone replies with WifiVersionResponse (type 5). Read it so it
            // does not sit in the stream and get mistaken for the reply to
            // WifiStartRequest below.
            val versionReply = readMessage(input)
            // Log the payload, not just its size. A WifiVersionResponse carries a
            // status field, and gearhead has values like STATUS_NO_COMPATIBLE_VERSION
            // and NO_COMPATIBLE_WIFI_VERSION_FOUND — a reply that merely *arrives*
            // tells us nothing about whether it accepted us. 140 bytes is also far
            // larger than a bare ack, so there is content here worth reading.
            OalLog.i(TAG, "Handshake: version reply type=${versionReply.type} " +
                    "(${versionReply.payload.size} bytes) hex=" +
                    versionReply.payload.take(64).joinToString("") { "%02x".format(it) })
            runCatching {
                val vr = Wireless.WifiVersionResponse.parseFrom(versionReply.payload)
                OalLog.i(TAG, "Handshake: phone WPP version " +
                        "${vr.versionMajor}.${vr.versionMinor} status=${vr.status}")
            }.onFailure {
                OalLog.w(TAG, "Handshake: could not parse WifiVersionResponse: ${it.message}")
            }

            OalLog.i(TAG, "Handshake: sending WifiStartRequest (type $MSG_WIFI_START_REQUEST) -> ${creds.ip}:${creds.port}")
            sendMessage(
                output,
                MSG_WIFI_START_REQUEST,
                Wireless.WifiStartRequest.newBuilder()
                    .setIpAddress(creds.ip)
                    .setPort(creds.port)
                    .setStatus(0)
                    // The endpoint also rides here, not only on WifiVersionRequest:
                    // xnu field 4 is the same WifiProjectionProtocolInfo message.
                    .setWppInfo(
                        Wireless.WifiProjectionProtocolInfo.newBuilder()
                            .setIpAddress(creds.ip)
                            .setPort(creds.port)
                            .build()
                    )
                    // Without a channel list the phone intersects ours (empty)
                    // with its own and reports
                    //   "WiFi channels not supported: []"
                    //   State changed to NO_COMPATIBLE_WIFI_CHANNEL_FOUND
                    // and abandons the connection.
                    .setAccessPointInfo(
                        Wireless.AccessPointInfo.newBuilder()
                            .setSsid(creds.ssid)
                            .setKey(creds.psk)
                            .setBssid(creds.bssid)
                            .setWifiSecurityMode(
                                if (creds.psk.isEmpty()) Wireless.SecurityMode.OPEN
                                else Wireless.SecurityMode.WPA2_PERSONAL
                            )
                            .addAllSupportedWifiChannels(creds.channelsMhz)
                            .build()
                    )
                    .build()
                    .toByteArray(),
            )

            val reply = readMessage(input)
            OalLog.i(TAG, "Handshake: phone replied type=${reply.type} (${reply.payload.size} bytes)")

            if (reply.type != MSG_WIFI_INFO_REQUEST) {
                OalLog.w(TAG, "Handshake: unexpected reply type ${reply.type}, expected $MSG_WIFI_INFO_REQUEST — aborting")
                return
            }

            OalLog.i(TAG, "Handshake: phone requested security info — sending WifiInfoResponse (type 3)")
            sendMessage(
                output,
                MSG_WIFI_INFO_RESPONSE,
                Wireless.WifiInfoResponse.newBuilder()
                    .setSsid(creds.ssid)
                    .setKey(creds.psk)
                    .setBssid(creds.bssid)
                    .setSecurityMode(
                        if (creds.psk.isEmpty()) Wireless.SecurityMode.OPEN
                        else Wireless.SecurityMode.WPA2_PERSONAL
                    )
                    .setAccessPointType(Wireless.AccessPointType.STATIC)
                    .build()
                    .toByteArray(),
            )

            OalLog.i(TAG, "Handshake complete — phone should now associate and open the projection socket")

            // The protocol exchange is done, so anything waiting on it can run.
            //
            // This flag used to be cleared in the coroutine's `finally`, which is
            // reached only when the RFCOMM socket closes — and that socket is
            // deliberately held open below for the life of the session. So
            // "handshake in flight" meant "the control link is open", while every
            // guard using it assumed "the protobuf exchange is mid-flight". The
            // difference is 140ms versus half a minute:
            //
            //     00:09:44.686  Handshake complete
            //     00:09:47.582  companion found — queued, waiting for the handshake
            //     00:10:20.339  handshake "finished" — queued re-advertise finally ran
            //
            // The window the guard protects ends here: the phone has the endpoint
            // and is about to dial it.
            handshakeLease.finish()
            AaWirelessBtControl.flushPendingReadvertise()

            // Hold the RFCOMM socket open. The phone keeps it as the control link
            // while it associates and dials TCP; dropping it here makes the phone
            // treat the head unit as having gone away mid-setup.
            while (running && scope.isActive && socket.isConnected) {
                delay(2000)
            }
            OalLog.i(TAG, "Handshake socket closing (running=$running connected=${socket.isConnected})")
        } catch (e: IOException) {
            OalLog.w(TAG, "Handshake I/O error: ${e.message}")
        } catch (e: Exception) {
            OalLog.w(TAG, "Handshake failed: ${e.message}")
        } finally {
            runCatching { socket.close() }
        }
    }

    private fun sendMessage(output: OutputStream, type: Int, payload: ByteArray) {
        val buf = ByteBuffer.allocate(payload.size + HEADER_BYTES)
        buf.put((payload.size shr 8).toByte())
        buf.put((payload.size and 0xFF).toByte())
        buf.put((type shr 8).toByte())
        buf.put((type and 0xFF).toByte())
        buf.put(payload)
        output.write(buf.array())
        output.flush()
    }

    private fun readMessage(input: DataInputStream): Message {
        val header = ByteArray(HEADER_BYTES)
        input.readFully(header)
        val size = ((header[0].toInt() and 0xFF) shl 8) or (header[1].toInt() and 0xFF)
        val type = ((header[2].toInt() and 0xFF) shl 8) or (header[3].toInt() and 0xFF)
        val payload = if (size > 0) ByteArray(size).also { input.readFully(it) } else ByteArray(0)
        return Message(type, payload)
    }

    private data class Message(val type: Int, val payload: ByteArray)

    private fun hasBtPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) ==
                PackageManager.PERMISSION_GRANTED

    fun stop() {
        // Cleanup is deliberately idempotent. A dead accept loop clears
        // `running` before recovery replaces this server; returning early here
        // would leak its sockets/jobs and let the old HFP listener conflict with
        // the replacement instance.
        running = false
        runCatching { aaServerSocket?.close() }
        aaServerSocket = null
        runCatching { hfpServerSocket?.close() }
        hfpServerSocket = null
        acceptJob?.cancel()
        acceptJob = null
        hfpJob?.cancel()
        hfpJob = null
        scope.cancel()
        OalLog.i(TAG, "AA wireless BT advertiser stopped")
    }

    companion object {
        private const val TAG = "AaWirelessBt"

        /** Name shown in the SDP record. Cosmetic. */
        private const val SDP_NAME = "Android Auto"

        /**
         * The Android Auto Wireless service UUID. Gearhead matches on exactly this
         * (`jki.a` / `owv.b` in the 17.4 teardown) when deciding whether a bonded
         * device is a wireless-capable head unit, and dials back to it.
         */
        val AA_UUID: UUID = UUID.fromString("4de17a00-52cb-11e6-bdf4-0800200c9a66")

        /**
         * Second UUID gearhead accepts (`owv.c`). Not advertised by default — one
         * record is enough and two risks confusing the SDP query — but kept here
         * because it is the obvious next thing to try if the primary is ignored.
         */
        val AA_UUID_ALT: UUID = UUID.fromString("669a0c20-0008-f4bd-e611-cb52007ae14d")

        /**
         * Hands-Free Profile (HF unit role). Advertised only to keep the Bluetooth
         * ACL link alive on non-automotive test devices — see [hfpPresenceLoop].
         */
        val HFP_UUID: UUID = UUID.fromString("0000111e-0000-1000-8000-00805f9b34fb")

        private const val HEADER_BYTES = 4
        // Wire message types, recovered from the AA 17.4 teardown (enum `xnp`,
        // third constructor argument). Types 1/2/3 are confirmed correct by our
        // own production logs, which validates the whole table.
        private const val MSG_WIFI_START_REQUEST = 1      // MESSAGE_WIFI_REQUEST_START_BT
        private const val MSG_WIFI_INFO_REQUEST = 2       // MESSAGE_WIFI_REQUEST_INFO_BT
        private const val MSG_WIFI_INFO_RESPONSE = 3      // MESSAGE_WIFI_RESPONSE_INFO_BT
        private const val MSG_WIFI_VERSION_REQUEST = 4    // MESSAGE_WIFI_VERSION_REQUEST_BT
        private const val MSG_WIFI_VERSION_RESPONSE = 5   // MESSAGE_WIFI_VERSION_RESPONSE_BT

        /**
         * WPP protocol version we advertise.
         *
         * Must be at least 4.1: gearhead gates WifiProjectionProtocolInfo on
         * `pev.d = jaj(4, 1)` and otherwise logs "Skip handling
         * WifiProjectionProtocolInfo as the protocol version is too low."
         */
        /**
         * Consecutive accept() failures before the loop gives up.
         *
         * A dead Bluetooth socket never becomes live again, so retrying it is
         * pure waste. Ten seconds of failures is well past any transient.
         */
        private const val MAX_ACCEPT_FAILURES = 10

        private const val WPP_VERSION_MAJOR = 4
        private const val WPP_VERSION_MINOR = 1
    }
}
