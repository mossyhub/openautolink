package com.openautolink.app.carplay

import android.Manifest
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.content.pm.PackageManager
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.location.LocationManager
import android.os.SystemClock
import com.openautolink.app.input.GnssForwarderImpl
import android.view.Surface
import android.util.Log
import com.openautolink.app.diagnostics.DiagnosticLog
import com.openautolink.carplaylab.EndpointOffer
import com.openautolink.carplaylab.SavedWifiPassword

import com.openautolink.carplaylab.media.CarPlayDisplayPreferences
import com.openautolink.carplaylab.media.ProjectionReceiver
import com.openautolink.carplaylab.pairing.PhoneActivation
import com.openautolink.carplaylab.network.CarPlayInterface
import com.openautolink.carplaylab.network.CarPlayNetworkConfig
import com.openautolink.carplaylab.network.CarPlayNetworkPolicy
import com.openautolink.carplaylab.network.CarPlaySocketBinding
import com.openautolink.carplaylab.network.WirelessEnrollmentConsentGate
import com.shilapi.xcertplay.iap2.wire.Iap2Frame
import com.shilapi.xcertplay.iap2.message.Iap2ControlMessages
import com.shilapi.xcertplay.iap2.session.Iap2Session
import com.shilapi.xcertplay.mfi.Iap2MfiAuthenticationClient
import com.shilapi.xcertplay.mfi.LocalMfiAuthenticationClient
import com.shilapi.xcertplay.transport.BluetoothRfcommDuplexStream
import com.shilapi.xcertplay.transport.Iap2IdentificationClient
import com.shilapi.xcertplay.transport.Iap2IdentificationConfig
import com.shilapi.xcertplay.transport.Iap2WirelessIdentification
import java.io.Closeable
import java.io.File
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** No secrets in preferences, bundles, logs, toString, or shipped assets. This snapshot is next-start only. */
class CarPlayRuntimeConfig(
    val phoneAddress: String,
    val peerAddress: String,
    val interfaceName: String,
    val sharedWifiOverride: Boolean,
    val ssid: String,
    val receiverAddress: String,
    val apFrequencyMhz: Int,
    val display: CarPlayDisplayPreferences,
    val networkFamily: String = "auto",
    val apSecurity: String = "wpa2",
    val localAddress: String = "",
)

/** Actual OAL Surface adapter for the copied receiver. There is no Activity/Intent bootstrap.
 * Initial experimental admission is an exact, user-selected BONDED phone, not a name heuristic.
 * Phone identity/authentication acceptance is established by the real iAP2 transcript each attempt.
 * Uses only the configured interface; ordinary-app vehicle ingress remains unverified.
 */
class OalCarPlayRuntime(
    private val context: Context,
    private val config: CarPlayRuntimeConfig,
    private val surface: Surface,
    private val onStatus: (String) -> Unit,
    private val onDisplayQualified: (CarPlayDisplayPreferences) -> Unit = {},
) : CarPlayRuntime {
    private val canceled = AtomicBoolean()
    private val resourcesLock = Any()
    private val closeLock = Any()
    private val resources = mutableListOf<AutoCloseable>()
    @Volatile private var receiver: ProjectionReceiver? = null
    private val deadlines = Executors.newSingleThreadScheduledExecutor { Thread(it, "oal-carplay-deadline").apply { isDaemon = true } }

    private fun active() { check(!canceled.get() && surface.isValid) { "CarPlay attempt revoked" } }
    private fun <T : AutoCloseable> own(create: () -> T): T = synchronized(resourcesLock) {
        // Creation belongs to the installation boundary: close's canceled snapshot must not
        // race an already-created owner that has not yet reached this list.
        check(!canceled.get()) { "CarPlay attempt revoked" }
        val resource = create()
        resources.add(resource)
        // cancel() is nonblocking and may have revoked the attempt during construction.
        // Keep that exact resource for close/retry rather than losing it on this exception.
        check(!canceled.get()) { "CarPlay attempt revoked" }
        resource
    }
    // Process-local runtime-session sequence, never pairing identity, phone address or persistence data.
    private val audioDiagnostics = CarPlayAudioDiagnosticReporter(
        diagnosticSessions.incrementAndGet(), System::nanoTime,
    ) { line ->
        // Receiver diagnostics arrive on its bounded status worker; failures cannot affect media/control.
        runCatching { DiagnosticLog.i("carplay-audio", line) }
        runCatching { Log.i("OalCarPlayAudio", line) }
    }
    private fun status(message: String) {
        if (canceled.get()) return
        audioDiagnostics.record(message)
        onStatus(message)
    }

    private var lastConnectionStage = "permissions"
    private fun connectionStage(stage: String) {
        lastConnectionStage = stage
        status("CarPlay connection stage=$stage result=started")
    }
    override fun run() {
        try { runConnection() }
        catch (failure: Exception) {
            val reason = (failure as? CarPlayConnectionFailure)?.kind?.name ?: "SETUP"
            val type = when (failure) {
                is SecurityException -> "SecurityException"
                is IllegalArgumentException -> "IllegalArgumentException"
                is IllegalStateException -> "IllegalStateException"
                is java.net.SocketTimeoutException -> "SocketTimeoutException"
                is java.io.IOException -> "IOException"
                else -> "IllegalStateException"
            }
            status("CarPlay connection stage=$lastConnectionStage result=failed type=$type reason=$reason")
            throw failure
        }
    }
    @Suppress("MissingPermission", "DEPRECATION")
    private fun runConnection() {
        connectionStage("permissions")
        active()
        check(context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED)
        check(context.checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED)
        connectionStage("bluetooth")
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter ?: error("Bluetooth unavailable")
        check(adapter.isEnabled)
        connectionStage("selected-bond")
        val device = adapter.bondedDevices.singleOrNull { it.address == config.phoneAddress }
            ?: error("Explicit selected bonded phone required")
        check(device.bondState == BluetoothDevice.BOND_BONDED)
        connectionStage("receiver-identity")
        require(config.receiverAddress.matches(Regex("[0-9a-fA-F]{2}(:[0-9a-fA-F]{2}){5}")))
        require(config.receiverAddress !in setOf("02:00:00:00:00:00", "00:00:00:00:00:00", "FF:FF:FF:FF:FF:FF"))
        connectionStage("interface")
        require(config.ssid.isNotBlank() && config.interfaceName.isNotBlank())
        val nic = NetworkInterface.getByName(config.interfaceName) ?: error("Selected interface unavailable")
        val addresses = nic.inetAddresses.toList().filterIsInstance<Inet4Address>()
            .filterNot { it.isLoopbackAddress || it.isLinkLocalAddress }.map { requireNotNull(it.hostAddress) }
        connectionStage("wifi-profile")
        val frequency: Int
        val security: Int
        var wifiLocalAddress: String? = null
        if (config.sharedWifiOverride) {
            val wifi = context.getSystemService(WifiManager::class.java).connectionInfo
            check(wifi.ssid.removeSurrounding("\"") == config.ssid) { "Shared Wi-Fi SSID mismatch" }
            val currentIp = java.net.InetAddress.getByAddress(byteArrayOf(wifi.ipAddress.toByte(), (wifi.ipAddress shr 8).toByte(), (wifi.ipAddress shr 16).toByte(), (wifi.ipAddress shr 24).toByte())).hostAddress
            check(requireNotNull(currentIp) in addresses) { "Selected interface does not own shared Wi-Fi address" }
            wifiLocalAddress = currentIp
            frequency = wifi.frequency
            security = when (wifi.currentSecurityType) {
                WifiInfo.SECURITY_TYPE_PSK -> 2
                WifiInfo.SECURITY_TYPE_SAE -> 4
                else -> error("Unsupported Wi-Fi security; no guessed profile")
            }
        } else {
            // Deliberate configured AP profile, not a claim of actual OEM radio/network readiness.
            frequency = config.apFrequencyMhz
            security = when (config.apSecurity) {
                "wpa2" -> 2
                "wpa3" -> 4
                else -> error("Unsupported configured AP security")
            }
        }
        connectionStage("network-selection")
        val network = try { CarPlayNetworkPolicy.select(CarPlayNetworkConfig(
            config.sharedWifiOverride, config.networkFamily, config.interfaceName,
            config.localAddress.ifEmpty { if (config.sharedWifiOverride && config.networkFamily != "ipv6") wifiLocalAddress.orEmpty() else "" },
            config.peerAddress,
        ), listOf(CarPlayInterface(nic.name, nic.index, nic.isUp, nic.isPointToPoint, nic.inetAddresses.toList()))) }
        catch (failure: IllegalArgumentException) {
            status("CarPlay network stage=address-selection-failed type=${failure.javaClass.simpleName}")
            throw CarPlayConnectionFailure(CarPlayFailureKind.NETWORK_SELECTION, config.peerAddress.isNotEmpty(), failure)
        }
        if (config.sharedWifiOverride && network.local is Inet4Address)
            check(network.local.hostAddress == wifiLocalAddress) { "Selected local address does not match shared Wi-Fi" }
        status("CarPlay network stage=address-selected family=${if (network.local is Inet4Address) 4 else 6} mode=${if (config.sharedWifiOverride) "shared" else "ap"}; ingress unverified")
        val channelNumber = when {
            frequency == 2484 -> 14
            frequency in 2412..2472 && (frequency - 2407) % 5 == 0 -> (frequency - 2407) / 5
            frequency in 5000..5900 && frequency % 5 == 0 -> (frequency - 5000) / 5
            else -> error("Configured Wi-Fi channel unavailable")
        }
        connectionStage("password")
        val password = SavedWifiPassword(context).load()
        check(password.isNotEmpty()) { "Saved encrypted Wi-Fi password required" }
        // First-use only: preserve existing credentials; bundled input still requires real crypto validation.
        connectionStage("identity-provisioning")
        val bundledNames by lazy { context.assets.list("offline-mfi").orEmpty().toSet() }
        val provisioning = com.openautolink.app.ui.settings.CarPlayMfiAssetBootstrap(context.noBackupFilesDir) { path ->
            if (path.substringAfterLast('/') in bundledNames) context.assets.open(path) else null
        }.install()
        check(provisioning != com.openautolink.app.ui.settings.CarPlayMfiAssetBootstrap.Result.INVALID_BUNDLE &&
            provisioning != com.openautolink.app.ui.settings.CarPlayMfiAssetBootstrap.Result.INVALID_EXISTING) {
            "CarPlay authentication identity provisioning failed"
        }
        val auth = LocalMfiAuthenticationClient.load(File(context.noBackupFilesDir, LocalMfiAuthenticationClient.DIRECTORY)) { }
        val identification = Iap2IdentificationConfig(
            name = "OpenAutoLink", modelIdentifier = "OAL-native-CarPlay", manufacturer = "OpenAutoLink",
            serialNumber = "oal-private-receiver", firmwareVersion = "0.1", hardwareVersion = "Android",
            wireless = Iap2WirelessIdentification(config.receiverAddress, config.ssid),
            externalAccessoryProtocol = "com.openautolink.carplaylab",
        )
        active()
        adapter.cancelDiscovery()
        connectionStage("rfcomm")
        val socket = own { device.createRfcommSocketToServiceRecord(UUID.fromString(IAP2_PHONE_UUID)) }
        val timeout = deadlines.schedule({ runCatching { socket.close() } }, 20, TimeUnit.SECONDS)
        status("CarPlay RFCOMM connecting · timeout 20s")
        active()
        try { socket.connect() } finally { timeout.cancel(false) }
        status("CarPlay RFCOMM connected · authentication pending")
        active()
        val bootstrapStream = own { BluetoothRfcommDuplexStream(socket) { } }
        val bootstrap = own { Iap2Session.openWireless(bootstrapStream) }
        connectionStage("bootstrap-identification")
        try { Iap2IdentificationClient(bootstrap, com.openautolink.carplaylab.metadata.CarPlayMetadataIdentification::bootstrap).identify(identification, 30_000) }
        catch (failure: Exception) { throw CarPlayConnectionFailure(CarPlayFailureKind.BOOTSTRAP_IDENTIFICATION, network.manualPeer != null, failure) }
        active()
        status("CarPlay phone identification accepted")
        connectionStage("bootstrap-authentication")
        try { Iap2MfiAuthenticationClient(auth).run(bootstrap, 30_000) { } }
        catch (failure: Exception) { throw CarPlayConnectionFailure(CarPlayFailureKind.BOOTSTRAP_AUTHENTICATION, network.manualPeer != null, failure) }
        active()
        status("CarPlay receiver MFi authentication accepted · phone AirPlay trust pending")
        val projection = own { ProjectionReceiver(context, config.receiverAddress, surface, auth, config.display,
            trustedOnly = true, runtimeCarDataFactory = { session, send, receipt ->
                // Freeze truthful implemented capability flags per actual Wi-Fi link, not Bluetooth.
                val qualifiedGps = context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED &&
                    runCatching { context.getSystemService(LocationManager::class.java)?.isProviderEnabled(LocationManager.GPS_PROVIDER) == true }.getOrDefault(false)
                OalCarPlayIntakeLink(qualifiedGps, OalCarPlayVehicleSnapshotAdapter(),
                    OalCarPlayGnssSnapshotAdapter(SystemClock::elapsedRealtime) { sink ->
                        GnssForwarderImpl(context.applicationContext, sink)
                    }, SystemClock::elapsedRealtime, CarPlayAppearanceBridge(),
                    session::setNightModeIfEventReady, send, receipt)
            }, runtimeMetadataFactory = { receipt -> own { active(); OalCarPlayMetadataLink(SystemClock::elapsedRealtime, receipt, OalCarPlayMetadataHost(context.applicationContext)) } }, onStatus = ::status).also { receiver = it } }
        // Log the actual decoder-qualified offer, not an earlier requested preset or coded viewport.
        active()
        onDisplayQualified(projection.displayConfiguration)
        val scaleSnapshot = com.openautolink.carplaylab.media.CarPlayDisplayPolicy.scaleDiagnostic(projection.displayConfiguration)
        DiagnosticLog.i("carplay-scale", scaleSnapshot)
        Log.i("OalCarPlayScale", scaleSnapshot)
        projection.setRuntimeIdentification(identification)
        active()
        com.openautolink.carplaylab.metadata.CarPlayMetadataIdentification.bootstrapSubscriptions(Iap2ControlMessages.subscriptions())
            .forEach { active(); bootstrap.send(it, 5000) }
        var selectedConsentRequest: Iap2Frame? = null
        var selectedTransportNotification: Iap2Frame? = null
        connectionStage("enrollment")
        if (projection.requiresControllerEnrollment) {
            // The existing explicit OAL CarPlay enable/selection supplies accessory-side consent.
            // Automatic setup also waits for the phone's wireless service request. The user's
            // explicit joined-AP/numeric-peer fallback supplies the alternative setup choice.
            // Neither path proactively discloses credentials or treats a generic bond as consent.
            if (network.manualPeer == null) {
                status("carplay-enrollment stage=awaiting-selected-bootstrap-request")
                val consent = WirelessEnrollmentConsentGate()
                val consentDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(120)
                while (selectedConsentRequest == null) {
                    active()
                    if (System.nanoTime() >= consentDeadline)
                        throw CarPlayConnectionFailure(CarPlayFailureKind.WIRELESS_REQUEST_TIMEOUT, false)
                    val frame = bootstrap.recv(1000) ?: continue
                    if (frame.messageId == 0x4e0e) selectedTransportNotification = frame
                    if (consent.observe(frame)) selectedConsentRequest = frame
                }
            }
            active()
            projection.authorizeFirstEnrollment(120_000)
            status(if (network.manualPeer == null)
                "carplay-enrollment stage=authorized windowMs=120000 boundary=selected-bootstrap-request; first-client race remains possible"
                else "carplay-enrollment stage=authorized windowMs=120000 boundary=explicit-manual-peer; first-client race remains possible")
        }
        connectionStage("listener")
        // Shared IPv4 retains wildcard/auxiliary transport, with exact bounded control admission.
        try {
            if (config.sharedWifiOverride && network.local is Inet4Address) projection.startSharedIpv4(network)
            else projection.start(network)
        } catch (failure: java.net.BindException) {
            throw CarPlayConnectionFailure(CarPlayFailureKind.LISTENER_BIND, network.manualPeer != null, failure)
        } catch (failure: IllegalArgumentException) {
            throw CarPlayConnectionFailure(CarPlayFailureKind.ADMISSION_POLICY, network.manualPeer != null, failure)
        }
        connectionStage("endpoint")
        val offer = EndpointOffer(config.ssid, password, channelNumber, security,
            if (config.sharedWifiOverride && network.local is Inet4Address) addresses else network.advertisedAddresses,
            projection.port, projection.pairingId, projection.publicKeyHex)
        projection.setRuntimeReply(offer::replyTo)
        val reply = offer.newReplyChannel()
        // Constant-space retained request/transport notification, never proactive secret disclosure.
        listOfNotNull(selectedConsentRequest, selectedTransportNotification).forEach { frame ->
            reply(frame)?.let { response -> active(); bootstrap.send(response, 5000); status("CarPlay endpoint reply id=${frame.messageId} result=sent") }
        }
        network.manualPeer?.let { peer ->
            val binding = if (config.sharedWifiOverride && network.local is Inet4Address) null else CarPlaySocketBinding(network.local, peer)
            val activation = own { PhoneActivation(context, checkNotNull(peer.hostAddress), config.receiverAddress, binding, ::status) }
            active()
            activation.start()
        } ?: status("CarPlay network stage=activation-skipped reason=trusted-control-peer-pending; phone initiation unverified")
        status("CarPlay endpoint listening · awaiting real phone / render evidence")
        connectionStage("runtime")
        val readyDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(120)
        var handoff = false
        var wasReady = false
        fun retireBootstrap() {
            runCatching { bootstrap.close() } // Historical EOF can be rethrown after worker completion.
            socket.close()
            check(bootstrap.awaitClosed(5_000) && bootstrapStream.awaitClosed(5_000)) {
                "Bootstrap retirement unacknowledged"
            }
            handoff = true
            status("CarPlay bootstrap retired · authenticated wireless runtime retained; HFP unchanged")
        }
        while (!canceled.get()) {
            active()
            projection.runtimeFailureStage?.let { stage ->
                val kind = when (stage) {
                    com.openautolink.carplaylab.media.RuntimeControlFailureStage.IDENTIFICATION -> CarPlayFailureKind.RUNTIME_IDENTIFICATION
                    com.openautolink.carplaylab.media.RuntimeControlFailureStage.AUTHENTICATION -> CarPlayFailureKind.RUNTIME_AUTHENTICATION
                    com.openautolink.carplaylab.media.RuntimeControlFailureStage.TRANSPORT -> CarPlayFailureKind.RUNTIME_TRANSPORT
                }
                throw CarPlayConnectionFailure(kind, network.manualPeer != null)
            }
            if (projection.runtimeReady) wasReady = true
            if (wasReady && !projection.runtimeReady) break
            if (!wasReady && System.nanoTime() > readyDeadline)
                throw CarPlayConnectionFailure(CarPlayFailureKind.RUNTIME_STARTUP_TIMEOUT, network.manualPeer != null)
            if (!handoff && projection.runtimeReady && projection.bluetoothHandoffRequested) {
                retireBootstrap()
            }
            if (handoff) { Thread.sleep(100); continue }
            val frame = try { bootstrap.recv(1000) } catch (failure: java.io.IOException) {
                if (!projection.runtimeReady) throw failure
                retireBootstrap()
                continue
            }
            if (frame != null) {
                val response = reply(frame)
                if (response != null) { active(); bootstrap.send(response, 5000); status("CarPlay endpoint reply id=${frame.messageId} result=sent") }
            }
            if (bootstrap.isClosed) {
                check(projection.runtimeReady) { "Bootstrap closed before wireless authentication" }
                retireBootstrap()
            }
        }
    }

    private val touchMapper = CarPlayTouchMapper()
    fun touch(action: Int, actionIndex: Int, pointers: List<CarPlayPointer>, width: Int, height: Int) {
        if (!canceled.get()) receiver?.touchContacts(touchMapper.map(action, actionIndex, pointers, width, height))
    }
    fun touch(action: Int, x: Float, y: Float, width: Int, height: Int) {
        touch(action, 0, listOf(CarPlayPointer(0, x, y)), width, height)
    }
    override fun telemetrySnapshot() = if (canceled.get()) null else receiver?.telemetrySnapshot()
    override fun cancel() { canceled.set(true); receiver?.revokePendingAdmission() }
    override fun close() = synchronized(closeLock) {
        cancel()
        val retiring = synchronized(resourcesLock) { resources.toList().asReversed() }
        val closeFailures = java.util.IdentityHashMap<AutoCloseable, Exception>()
        // Revoke every transport before waiting for workers that can depend on one another.
        retiring.forEach { resource ->
            try { resource.close() } catch (error: Exception) { closeFailures[resource] = error }
        }
        var failure: Exception? = null
        retiring.forEach { resource ->
            try {
                when (resource) {
                    // These facades can rethrow a historical EOF after their workers retired.
                    is Iap2Session -> {
                        check(resource.awaitClosed(5_000)) { "Bootstrap worker still alive" }
                        closeFailures.remove(resource)
                    }
                    is BluetoothRfcommDuplexStream -> {
                        check(resource.awaitClosed(5_000)) { "RFCOMM reader still alive" }
                        closeFailures.remove(resource)
                    }
                    // Pairing ownership persists until all receiver control work terminates.
                    is ProjectionReceiver -> check(resource.awaitClosed(10_000)) { "Receiver retirement timed out" }
                }
                closeFailures[resource]?.let { throw it }
                // Forget only the exact owner whose close AND worker acknowledgement succeeded.
                // A failed owner remains available for retry; successful owners are never retried.
                synchronized(resourcesLock) { resources.removeAll { it === resource } }
            } catch (error: Exception) {
                if (failure == null) failure = error
            }
        }
        deadlines.shutdownNow()
        failure?.let { throw it }
        receiver = null
        Unit
    }
    private companion object {
        const val IAP2_PHONE_UUID = "00000000-deca-fade-deca-deafdecacafe"
        val diagnosticSessions = java.util.concurrent.atomic.AtomicLong()
    }
}
