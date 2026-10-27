// SPDX-License-Identifier: GPL-3.0-only
package com.openautolink.carplaylab.media

import android.content.Context
import android.net.wifi.WifiManager
import android.view.Surface
import com.openautolink.carplaylab.pairing.PairingPersistence
import com.openautolink.carplaylab.network.*
import com.openautolink.carplaylab.intake.RuntimeCarDataLink
import com.shilapi.xcertplay.airplay.*
import com.shilapi.xcertplay.media.AndroidMediaSink
import com.shilapi.xcertplay.iap2.session.Iap2Session
import com.shilapi.xcertplay.iap2.wire.Iap2Frame
import com.shilapi.xcertplay.iap2.message.Iap2ControlMessages
import com.shilapi.xcertplay.mfi.MfiAuthenticator
import com.shilapi.xcertplay.mfi.Iap2MfiAuthenticationClient
import com.shilapi.xcertplay.transport.*
import java.io.Closeable
import java.io.File
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicBoolean

enum class RuntimeControlFailureStage { IDENTIFICATION, AUTHENTICATION, TRANSPORT }

/** Owns media, NOT caller Surface or MFi client. Close pairing probe before constructing this owner. */
class ProjectionReceiver(
    context: Context,
    private val btMac: String,
    surface: Surface,
    private val mfi: MfiAuthenticator,
    display: CarPlayDisplayPreferences = CarPlayDisplayPreferences(),
    // Production enrollment requires a bounded, authenticated bootstrap. Bench stays explicit.
    private val trustedOnly: Boolean = false,
    private val runtimeCarDataFactory: ((AirPlaySession, (Iap2Frame) -> Unit, (String) -> Unit) -> RuntimeCarDataLink)? = null,
    private val runtimeMetadataFactory: ((AirPlaySession, (String) -> Unit) -> com.openautolink.carplaylab.metadata.RuntimeMetadataLink)? = null,
    private val wired: Boolean = false,
    private val drivingRestrictions: Boolean = false,
    private val onCaptureTerminal: ((com.shilapi.xcertplay.media.CaptureTerminalSummary) -> Boolean)? = null,
    /** Optional wired Bluetooth association only; never the Ethernet or AirPlay device identity. */
    private val advertisedBluetoothAddress: String? = null,
    private val onStatus: (String) -> Unit,
) : Closeable {
    init {
        require(btMac.matches(Regex("[0-9a-fA-F]{2}(:[0-9a-fA-F]{2}){5}")))
        if (advertisedBluetoothAddress != null) {
            require(wired) { "Bluetooth advertisement override is wired-only" }
            require(advertisedBluetoothAddress.matches(Regex("[0-9A-F]{2}(:[0-9A-F]{2}){5}")) &&
                advertisedBluetoothAddress !in setOf("00:00:00:00:00:00", "FF:FF:FF:FF:FF:FF", "02:00:00:00:00:00")) {
                "Invalid head unit Bluetooth address"
            }
        }
        // Caller qualifies the initial presentation before handoff. It can be destroyed
        // during construction; reconcile exact receipts rather than abort the session owner.
    }
    private val videoSelection = CarPlayDisplayPolicy.videoSelection(display)
    private val displaySelection = CarPlayDisplaySelection(
        display.copy(resolution = CarPlayResolution.entries.firstOrNull {
            it.width == videoSelection.width && it.height == videoSelection.height
        } ?: CarPlayResolution.CUSTOM,
            customWidth = videoSelection.width, customHeight = videoSelection.height,
            fps = videoSelection.fps), videoSelection.avcDecoderName, videoSelection.usedFallback)
    /** Actual advertised/sink/input configuration, including a capability-qualified fallback. */
    val displayConfiguration: CarPlayDisplayPreferences get() = displaySelection.display
    private val microphoneRequest = com.shilapi.xcertplay.media.CarPlayMicrophoneExperiments.snapshot()
    private val microphoneIndependent = microphoneRequest?.independent
    private val microphoneExperiment = microphoneRequest?.takeIf { it.independent == null }
    private val microphoneQualification = microphoneIndependent?.qualificationProfile() ?: microphoneExperiment
    private val speechRecognitionProfile = microphoneIndependent?.captureProfile()
    private val captureLedger = com.shilapi.xcertplay.media.CaptureAcquisitionLedger(microphoneQualification != null)
    // Manual experiments retain their receiver-wide exact output inventory.
    // Normal calls qualify against actual output rows without changing noncall offers.
    private val outputAudioFormats = microphoneQualification?.let {
        com.shilapi.xcertplay.media.CaptureAcquisitionLedger.observe(captureLedger) {
            com.shilapi.xcertplay.media.AndroidAudioDecoderSupport.availableBits.toSet()
        }
    }
    private val microphoneCapability = com.shilapi.xcertplay.media.CaptureAcquisitionLedger.observe(captureLedger) {
        val profile = microphoneQualification
        if (microphoneIndependent != null) require(!(
            microphoneIndependent.history == com.shilapi.xcertplay.media.CarPlayMicrophoneQualificationHistory.MANUAL_VR48_TWO &&
            microphoneIndependent.speechCapture == com.shilapi.xcertplay.media.CarPlayMicrophoneSpeechCapture.VR24_MONO)) {
            "Independent capture not qualified by selected history"
        }
        // Keep shape evidence and its capability frozen together; no additional probes.
        val baseline = synchronized(com.shilapi.xcertplay.media.AndroidMicrophoneSupport) {
            val qualified = if (profile == null) com.shilapi.xcertplay.media.AndroidMicrophoneSupport.probe(context)
            else com.shilapi.xcertplay.media.AndroidMicrophoneSupport.experimentCapability(context, profile)
            require(microphoneIndependent == null || com.shilapi.xcertplay.media.AndroidMicrophoneSupport.supportsSpeechCapture(microphoneIndependent)) {
                "Independent capture not qualified by selected history"
            }
            qualified
        }
        try {
            val rows = com.shilapi.xcertplay.media.AudioOutputSupport.wirelessRows(
                outputAudioFormats ?: com.shilapi.xcertplay.media.AudioOutputSupport.implementedBits)
            val admitted = if (profile == null) {
                val callMask = com.shilapi.xcertplay.media.AudioOutputSupport.wirelessRows(
                    com.shilapi.xcertplay.media.AndroidAudioDecoderSupport.availableBits.toSet())
                    .filter { it["type"] == 100 && it["audioType"] == "telephony" }
                    .fold(0L) { bits, row -> bits or ((row["audioOutputFormats"] as? Number)?.toLong() ?: 0L) }
                com.shilapi.xcertplay.media.MicrophoneCapability.of(
                    listOf("default", "telephony", "speechrecognition").associateWith { kind ->
                        if (kind == "telephony") baseline.bits(kind).filter { it and callMask != 0L }.toSet()
                        else baseline.bits(kind)
                    })
            } else {
                // AUTO also needs an actual advertised input, not merely a capture/encoder.
                val offers = com.shilapi.xcertplay.media.MicrophoneSupport.addInputOffers(rows, baseline)
                com.shilapi.xcertplay.media.MicrophoneCapability.of(
                    listOf("default", "telephony", "speechrecognition").associateWith { kind ->
                        val mask = offers.filter { it["type"] == 100 &&
                            (it["audioType"] as? String)?.lowercase(java.util.Locale.ROOT) == kind }
                            .fold(0L) { bits, row -> bits or ((row["audioInputFormats"] as? Number)?.toLong() ?: 0L) }
                        // Independent history does not override the automatic default live source.
                        // Manual VR48 probes never qualify MIC24: withhold that input, never forge support.
                        if (microphoneIndependent != null && kind == "default") emptySet()
                        else baseline.bits(kind).filter { it and mask != 0L }.toSet()
                    })
            }
            val result = com.shilapi.xcertplay.media.CarPlayMicrophoneExperimentPolicy.resolveCapability(
                profile, wired, admitted, rows)
            require(profile == null || result.available)
            require(microphoneIndependent == null || result.bits("speechrecognition").isNotEmpty()) {
                "Independent capture has no admitted speech input"
            }
            result
        } catch (failure: IllegalArgumentException) {
            if (profile != null) runCatching { onStatus("media stage=microphone-experiment-rejected codec=${profile.codec} reason=no-qualified-input") }
            throw failure
        }
    }
    private val persistence = PairingPersistence.openReceiver(File(context.noBackupFilesDir, "pairing-probe"))
    private val trustedControllers = persistence.pairings.controllerIdentifiers()
    val requiresControllerEnrollment: Boolean get() = trustedControllers.isEmpty()
    val pairingId: String get() = persistence.identity.pairingId
    val publicKeyHex: String get() = persistence.identity.publicKeyHex
    @Volatile var port: Int = 0
        private set
    private val closed = AtomicBoolean()
    private val diagnosticContext = context.applicationContext
    private val telecomExperiment = com.openautolink.carplaylab.telecom.ManagedTelecomExperiment.get(context)
    private val runtimeIncoming = ConcurrentHashMap<AirPlaySession, com.openautolink.carplaylab.telecom.ManagedIncomingBridge>()
    private val admissionRevoked = AtomicBoolean()
    private val retirement = CountDownLatch(1)
    private val retirementRunning = AtomicBoolean()
    private val retirementRetryRequested = AtomicBoolean()
    private val retirementLock = Any()
    private val lock = Any()
    @Volatile private var hostUiHandler: (((() -> Boolean)) -> Unit)? = null
    /** Install before start. The host must recheck the exact receipt on its UI worker. */
    fun setHostUiHandler(handler: (isCurrent: () -> Boolean) -> Unit) = synchronized(lock) {
        check(!closed.get() && !admissionRevoked.get() && server == null)
        hostUiHandler = handler
    }
    private fun canRequestHostUi(session: AirPlaySession): Boolean =
        !closed.get() && !admissionRevoked.get() && active === session &&
            session in sessions && session.controlAuthenticated && !session.isClosed
    private var networkStartupDiagnosticStop: (() -> Unit)? = null
    /** Install before start. Numeric startup diagnostics only; no session/transport mutation. */
    fun setNetworkStartupDiagnosticStop(stop: () -> Unit) = synchronized(lock) {
        networkStartupDiagnosticStop = stop
    }
    private var server: ServerSocket? = null
    private var acceptThread: Thread? = null
    private var policy: MediaPolicy? = null
    private var network: CarPlayNetworkSelection? = null
    private var admissionSelection: CarPlayNetworkSelection? = null
    @Volatile private var peerAdmission: CarPlayPeerAdmission? = null
    private var firstEnrollmentWindowMillis = 0L
    private var admittedCount = 0
    private val sessions = ConcurrentHashMap.newKeySet<AirPlaySession>()
    private val allSessions = ConcurrentHashMap.newKeySet<AirPlaySession>()
    private val tunnels = ConcurrentHashMap<AirPlaySession, Iap2Session>()
    private val runtimeLinks = ConcurrentHashMap<AirPlaySession, RuntimeCarDataLink>()
    private val runtimeMetadata = ConcurrentHashMap<AirPlaySession, com.openautolink.carplaylab.metadata.RuntimeMetadataLink>()
    @Volatile private var runtimeOwner: AirPlaySession? = null
    @Volatile private var active: AirPlaySession? = null
    @Volatile var bluetoothHandoffRequested: Boolean = false
        private set
    @Volatile var runtimeReady: Boolean = false
        private set
    @Volatile var runtimeFailureStage: RuntimeControlFailureStage? = null
        private set
    private val statusQueueDrops = java.util.concurrent.atomic.AtomicLong()
    private val statuses = ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS, ArrayBlockingQueue(64),
        daemon("lab-media-status"), RejectedExecutionHandler { _, executor ->
            if (!executor.isShutdown) statusQueueDrops.incrementAndGet()
            throw RejectedExecutionException("bounded status queue unavailable")
        })
    init {
        captureLedger.bind(statuses, onStatus)
        captureLedger.bindTerminals(statuses) { summary -> onCaptureTerminal?.invoke(summary) == true }
        microphoneIndependent?.let { selection -> statuses.execute { runCatching { onStatus(selection.receipt(captureLedger.receiverOrdinal)) } } }
    }
    private val retentionStateQueued = AtomicBoolean()
    private val statusRetirementQueued = AtomicBoolean()
    // Passive-worker confined; finite (12 owners plus one aggregate), never arbitrary messages.
    private var lastRetentionState = emptyList<String>()
    private fun reportRetentionState(final: Boolean = false) {
        val snapshot = captureLedger.terminals.ownerStates() +
            captureLedger.terminals.status(captureLedger.receiverOrdinal, final)
        if (snapshot != lastRetentionState) {
            lastRetentionState = snapshot
            snapshot.forEach { line -> runCatching { onStatus(line) } }
        }
    }
    private fun queueRetentionState() {
        // Explicit persistent-close retry must never submit into the already-finalized worker.
        if (statusRetirementQueued.get() || statuses.isShutdown) return
        if (!retentionStateQueued.compareAndSet(false, true)) return
        try { statuses.execute {
            try { reportRetentionState() } finally { retentionStateQueued.set(false) }
        } } catch (_: RejectedExecutionException) { retentionStateQueued.set(false) }
    }
    private val diagnosticLock = Any()
    private var diagnosticOwner: AirPlaySession? = null
    private var diagnosticWindowNs = 0L
    private val diagnosticSeen = HashSet<String>()
    private val input = ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS, ArrayBlockingQueue(64),
        daemon("lab-media-input"), ThreadPoolExecutor.AbortPolicy())
    private val runtime = ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS, SynchronousQueue(),
        daemon("lab-iap-runtime"), ThreadPoolExecutor.AbortPolicy())
    private val deadlines = ScheduledThreadPoolExecutor(1, daemon("lab-media-handshake")).apply { removeOnCancelPolicy = true }
    private var runtimeIdentification: Iap2IdentificationConfig? = if (wired) null else runCatching {
        @Suppress("DEPRECATION")
        val ssid = context.applicationContext.getSystemService(WifiManager::class.java)?.connectionInfo?.ssid
            ?.removeSurrounding("\"")?.takeIf { it.isNotBlank() && it != "<unknown ssid>" }
        ssid?.let { defaultIdentification(it) }
    }.getOrNull()
    private val telemetry = ReceiverTelemetry(telemetryGenerations.incrementAndGet())
    fun telemetrySnapshot(): ReceiverTelemetrySnapshot = telemetry.snapshot(System.nanoTime())
    private val sink = AndroidMediaSink(context, surface, display = displaySelection, onStatus = ::status, telemetry = telemetry, videoSelection = videoSelection,
        microphoneExperiment = microphoneExperiment, microphoneCapability = microphoneCapability,
        captureLedger = captureLedger, audioDiagnostic = { line, current -> enqueueStatus(line, current) },
        speechRecognitionProfile = speechRecognitionProfile)
    /** Presentation receipts do not take the session/control lifecycle lock or retire transport. */
    fun attachSurface(surface: Surface) { if (!closed.get() && !admissionRevoked.get()) sink.attachSurface(surface) }
    fun detachSurface(surface: Surface) { sink.detachSurface(surface) }
    private val oemIcons = listOf(AirPlayIcon(192, 192,
        checkNotNull(ProjectionReceiver::class.java.getResourceAsStream("/oal_carplay_home.png")) {
            "Packaged OAL return icon missing"
        }.use { it.readBytes() }))
    private fun sessionConfig(): AirPlayConfig =
        benchReceiverConfig(btMac, port, microphoneCapability, displayConfiguration).copy(
            btMac = advertisedBluetoothAddress ?: btMac,
            hevc = videoSelection.hevcOffered, oemLabel = "OpenAutoLink", icons = oemIcons,
            drivingRestrictions = drivingRestrictions)
    private val engine = CarPlayMediaEngine(sink, microphoneEnabled = microphoneCapability.available,
        onDiagnostic = { session, message ->
            if (!closed.get() && !admissionRevoked.get() && session in sessions) status(message)
        }).apply {
        if (!wired) setRuntimeTunnelHandler(::startRuntime)
    }
    private val listener = object : AirPlaySessionListener {
        override fun onControlAuthenticated(session: AirPlaySession): Boolean {
            val accepted = !closed.get() && !admissionRevoked.get() && session in sessions &&
                (peerAdmission?.authenticate(session, session.controllerId)
                    ?: (!trustedOnly || session.controllerId in trustedControllers))
            status("CarPlay network stage=authentication accepted=$accepted")
            return accepted
        }
        override fun onSessionActive(session: AirPlaySession) {
            if (closed.get() || session !in sessions || (active != null && active !== session)) return
            active = session; status("media stage=record-active; rendering unverified")
        }
        override fun onSessionEnded(session: AirPlaySession) {
            peerAdmission?.release(session)
            sessions.remove(session)
            runtimeLinks[session]?.revoke()
            runtimeMetadata[session]?.revoke()
            runCatching { tunnels[session]?.close() }
            if (runtimeOwner === session) runtimeReady = false
            if (active === session) { active = null; telemetry.retire() }
            status("media stage=session-ended")
        }
        override fun onVideoFrameRendered(session: AirPlaySession) {
            if (!closed.get() && session in sessions) { active = session; status("media stage=video-first-frame-confirmed count=1") }
        }
        override fun onHostUiRequested(session: AirPlaySession) {
            if (!canRequestHostUi(session)) return
            // UI work is owned by the host; no Activity or task effects on this socket worker.
            runCatching { hostUiHandler?.invoke { canRequestHostUi(session) } }
        }
        override fun onCommand(session: AirPlaySession, type: String, params: Map<String, Any?>) {
            if (closed.get() || active !== session || session !in sessions) return
            val known = setOf("disableBluetooth", "modesChanged", "requestUI", "duckAudio", "unduckAudio")
            status("BENCH_DIAG incomingCommand=${if (type in known) type else "other"}")
            if (type.equals("disableBluetooth", true)) {
                bluetoothHandoffRequested = true
                // Receipt describes the existing disposition, not native profile disconnection.
                status("BENCH_DIAG bluetoothHandoff requested=true policy=observe-only handling=${if (wired) "unsupported-native-profile-handover" else "own-bootstrap-only"}")
            }
            if (type == "modesChanged") {
                // Numeric ownership only; never dump the command dictionary.
                val resources = params["resources"] as? List<*> ?: emptyList<Any>()
                resources.mapNotNull { it as? Map<*, *> }.forEach { resource ->
                    val id = (resource["resourceID"] as? Number)?.toLong()
                    if (id == 1L || id == 2L) status("BENCH_DIAG modeResource=$id entity=${(resource["entity"] as? Number)?.toLong()} permanent=${(resource["permanentEntity"] as? Number)?.toLong()}")
                }
            }
        }
        override fun onDebugLog(message: String) { if (message.startsWith("BENCH_DIAG ")) status(message) }
    }

    @Volatile private var runtimeReply: ((Iap2Frame) -> Iap2Frame?)? = null
    /** Bind only the caller's real EndpointOffer::replyTo, AFTER start has yielded the actual port.
     * No credentials or fabricated endpoint values are invented inside this receiver. */
    fun setRuntimeReply(reply: (Iap2Frame) -> Iap2Frame?) { check(!closed.get()); runtimeReply = reply }

    /** Prefer passing the exact identification used by PhoneActivation, with its real current SSID. */
    fun setRuntimeIdentification(config: Iap2IdentificationConfig) = synchronized(lock) {
        check(!closed.get() && server == null)
        require(if (wired) config.wireless == null else config.wireless?.bluetoothMac?.equals(btMac, ignoreCase = true) == true)
        runtimeIdentification = config.copy(locationInformationEnabled = false, vehicleStatusEnabled = false, vehicleSpeedEnabled = false)
    }

    /** Exact USB or selected wireless bootstrap has completed MFi authentication and consent.
     * Authorize at most one additional verified controller, without replacing existing phone trust.
     * No cross-transport key-binding guarantee is claimed; default remains trusted reconnect only. */
    fun authorizeControllerEnrollment(windowMillis: Long) = synchronized(lock) {
        check(!closed.get() && !admissionRevoked.get() && server == null && trustedOnly)
        require(windowMillis in 1..120_000)
        firstEnrollmentWindowMillis = windowMillis
    }

    /** Exact control admission, but retain the proven wildcard/auxiliary shared-IPv4 transport. */
    fun startSharedIpv4(selected: CarPlayNetworkSelection) {
        require(selected.local is java.net.Inet4Address && selected.manualPeer is java.net.Inet4Address)
        startInternal(MediaPolicy(checkNotNull(selected.manualPeer.hostAddress)), null, selected)
    }
    fun start(peerIp: String) = startInternal(MediaPolicy(peerIp), null)
    fun start(selected: CarPlayNetworkSelection) = startInternal(null, selected)
    private fun startInternal(selected: MediaPolicy?, selectedNetwork: CarPlayNetworkSelection?,
        admissionNetwork: CarPlayNetworkSelection? = selectedNetwork) = synchronized(lock) {
        check(!closed.get())
        check(!admissionRevoked.get()) { "Receiver admission revoked" }
        // Initial qualification is not a perpetual Surface lease. Caller reconciles newer
        // attach/detach receipts around construction/start; transport may start offscreen.
        if (server != null) {
            require(policy?.peer == selected?.peer && network === selectedNetwork && admissionSelection === admissionNetwork)
            return@synchronized
        }
        check(runtimeIdentification != null) { "Set exact transport identification before start" }
        status("BENCH_DIAG displayOffer codec=H264 width=${displayConfiguration.width} height=${displayConfiguration.height} fps=${displayConfiguration.fps} fallback=${displaySelection.usedFallback}")
        val admission = try {
            require(!trustedOnly || trustedControllers.isNotEmpty() || firstEnrollmentWindowMillis > 0) {
                "Existing controller trust or explicit first-enrollment authorization required"
            }
            require(!trustedOnly || admissionNetwork != null) { "Trusted admission requires exact local and peer policy" }
            require(firstEnrollmentWindowMillis == 0L || admissionNetwork != null) { "First enrollment requires exact interface admission" }
            admissionNetwork?.let { CarPlayPeerAdmission(it, trustedControllers, trustedOnly,
                enrollmentWindowMillis = firstEnrollmentWindowMillis) }
        }
        catch (failure: IllegalArgumentException) {
            status(if (trustedOnly) "CarPlay network stage=admission-unavailable reason=explicit-enrollment-authorization-required"
                else "CarPlay network stage=admission-unavailable reason=existing-controller-required")
            throw failure
        }
        val bound = ServerSocket()
        try {
            bound.reuseAddress = false
            network = selectedNetwork
            bound.bind(InetSocketAddress(network?.local ?: InetAddress.getByName("::"), 0), 2)
            peerAdmission = admission
            admissionSelection = admissionNetwork
            policy = selected; server = bound; port = bound.localPort
            acceptThread = Thread({ acceptLoop(bound) }, "lab-media-accept").apply { isDaemon = true; start() }
            status("CarPlay network stage=listener-bound family=${if (network == null) 0 else if (network?.local is java.net.Inet6Address) 6 else 4} admission=${if (selectedNetwork?.manualPeer == null && selectedNetwork != null) "trusted-controller" else "manual-peer"}; ingress unverified")
        } catch (failure: Exception) {
            status("CarPlay network stage=bind-failed type=${failure.javaClass.simpleName}")
            bound.close(); throw failure
        }
    }

    private fun acceptLoop(bound: ServerSocket) {
        try {
            while (!closed.get()) {
                val socket = bound.accept()
                // Capture the actual accept boundary, not the later asynchronous status delivery.
                val acceptedMonoNanos = System.nanoTime()
                var diagnosticStop: (() -> Unit)? = null
                val accepted = synchronized(lock) {
                    if (closed.get() || admissionRevoked.get() || network == null && policy?.allows(socket.inetAddress) != true ||
                        sessions.size >= (if (network == null) 2 else 1) || admittedCount >= 32) {
                        socket.close(); status("media stage=admission-declined")
                        false
                    } else {
                        admittedCount++
                        diagnosticStop = networkStartupDiagnosticStop
                        true
                    }
                }
                if (!accepted) continue
                // The accepted-owner diagnostic boundary precedes all fallible session setup.
                // No observer runs under the receiver lock; callback failure cannot undo admission.
                runCatching { diagnosticStop?.invoke() }
                synchronized(lock) {
                    // A reentrant observer may retire this receiver while the lock is released.
                    if (closed.get() || admissionRevoked.get()) {
                        socket.close()
                        return@synchronized
                    }
                    run {
                        socket.tcpNoDelay = true; socket.keepAlive = true
                        val config = sessionConfig()
                        telemetry.advertised(displayConfiguration.width, displayConfiguration.height)
                        status("BENCH_DIAG microphoneCapability=${microphoneCapability.available}")
                        val admission = peerAdmission
                        lateinit var session: AirPlaySession
                        session = AirPlaySession(socket, config, persistence.identity, persistence.pairings, mfi,
                            listener, engine,
                            socketBinding = network?.let { CarPlaySocketBinding(it.local, socket.inetAddress) },
                            allowPairSetup = (trustedOnly && admission != null) || !trustedOnly && (network?.manualPeer != null || network == null),
                            pairSetupAdmission = if (trustedOnly && admission != null) ({ owner -> admission.allowsPairSetup(owner) }) else null,
                            saveController = if (trustedOnly) ({ id, key ->
                                val committed = admission != null && admission.persistEnrollment(session, id, key, persistence.pairings)
                                status("carplay-enrollment stage=commit accepted=$committed")
                                committed
                            }) else null,
                            videoSelection = videoSelection, microphoneCapability = microphoneCapability,
                            availableAudioFormats = outputAudioFormats)
                        if (peerAdmission?.claim(session, socket.inetAddress, socket.localAddress) == false) {
                            socket.close(); status("CarPlay network stage=accept-declined reason=peer-interface-scope")
                            return@synchronized
                        }
                        sessions.add(session); allSessions.add(session)
                        status("CarPlay network stage=control-accepted authentication=pending acceptedMonoNs=$acceptedMonoNanos")
                        // Engine attachment receives this exact session, never the latest accepted one.
                        session.start()
                        deadlines.schedule({ if (!session.controlAuthenticated) session.close() }, 30, TimeUnit.SECONDS)
                    }
                }
            }
        } catch (_: Exception) { if (!closed.get()) status("media stage=listener-failed") }
    }

    /** Caller stops reading this exact authenticated USB CSM before handing it to this worker.
     * No second iAP2 consumer, tunnel identification, or duplicate metadata owner on wired. */
    fun startWiredRuntime(channel: Iap2Session): Boolean = synchronized(lock) {
        check(wired)
        val session = sessions.singleOrNull { it.controlAuthenticated && !it.isClosed } ?: return@synchronized false
        beginRuntime(session, channel)
    }
    fun offerWiredArtwork(transfer: Iap2ArtworkTransfer) {
        val session = runtimeOwner ?: return
        if (wired && runtimeReady && !closed.get() && !admissionRevoked.get())
            runtimeMetadata[session]?.offerArtwork(transfer)
    }
    private fun startRuntime(session: AirPlaySession, stream: BlockingDuplexByteStream): Boolean = synchronized(lock) {
        if (closed.get() || admissionRevoked.get() || session !in sessions || tunnels.isNotEmpty() || runtimeLinks.isNotEmpty()) return@synchronized false
        val config = runtimeIdentification ?: return@synchronized false
        val channel = Iap2Session.openTunnel(stream, onArtwork = { transfer ->
            if (!closed.get() && !admissionRevoked.get() && runtimeOwner === session &&
                session in sessions && session.controlAuthenticated && runtimeReady)
                runtimeMetadata[session]?.offerArtwork(transfer)
        }) // Default upstream trace is intentionally discarded.
        beginRuntime(session, channel)
    }
    private fun beginRuntime(session: AirPlaySession, channel: Iap2Session): Boolean = synchronized(lock) {
        if (closed.get() || admissionRevoked.get() || session !in sessions || tunnels.isNotEmpty() || runtimeLinks.isNotEmpty()) return@synchronized false
        val config = runtimeIdentification ?: return@synchronized false
        tunnels[session] = channel
        runtimeOwner = session
        runtimeFailureStage = null
        try {
            runtime.execute {
                var ownedCarData: RuntimeCarDataLink? = null
                var metadata: com.openautolink.carplaylab.metadata.RuntimeMetadataLink? = null
                var incoming: com.openautolink.carplaylab.telecom.ManagedIncomingBridge? = null
                var failureStage = RuntimeControlFailureStage.IDENTIFICATION
                try {
                    metadata = runtimeMetadataFactory?.invoke(session, { message ->
                        if (message.startsWith("media stage=media-command-ingress ") ||
                            message.startsWith("carplay-bt ") || message.startsWith("media stage=bluetooth-")) {
                            audioDiagnostic(session, message)
                        } else status(message)
                        Unit
                    })
                    synchronized(lock) {
                        check(!closed.get() && !admissionRevoked.get() && session in sessions)
                        metadata?.let { runtimeMetadata[session] = it }
                    }
                    val carData = runtimeCarDataFactory?.invoke(session, { frame ->
                        check(!closed.get() && !admissionRevoked.get() && session in sessions && !session.isClosed)
                        try { channel.sendAndAwaitWritten(frame, 5_000) }
                        catch (failure: Exception) { session.close(); throw failure }
                    }, ::status)
                    ownedCarData = carData
                    synchronized(lock) {
                        // Retain even a factory result arriving after cancellation. Its failed close
                        // must remain available to receiver retirement, not only this worker's finally.
                        if (carData != null) runtimeLinks[session] = carData
                        check(!closed.get() && !admissionRevoked.get() && session in sessions)
                    }
                    if (!wired) {
                        Iap2IdentificationClient(channel) { base -> carData?.identification(base) ?: base }.identify(config, 30_000)
                        status("media stage=runtime-identification-accepted")
                        failureStage = RuntimeControlFailureStage.AUTHENTICATION
                        Iap2MfiAuthenticationClient(mfi).run(channel, 30_000) // No raw progress callback.
                    }
                    check(!closed.get() && !admissionRevoked.get() && session in sessions && session.controlAuthenticated)
                    carData?.authenticate()
                    metadata?.authenticate()
                    runtimeReady = true
                    incoming = telecomExperiment.authenticatedLink(
                        controlSender = { command, fence -> queuePhoneControl(session, command, fence) },
                        rejectSender = { fence -> queueReject(session, channel, fence) },
                        diagnostic = { line -> audioDiagnostic(session, line) },
                    ) {
                        !closed.get() && !admissionRevoked.get() && runtimeReady && runtimeOwner === session &&
                            session in sessions && session.controlAuthenticated && !session.isClosed
                    }
                    incoming?.let { runtimeIncoming[session] = it }
                    failureStage = RuntimeControlFailureStage.TRANSPORT
                    status("media stage=runtime-authentication-accepted")
                    Iap2ControlMessages.subscriptions().forEach { channel.send(it) }
                    // Always request real authenticated call observations. Setup may finish after
                    // authentication; subscription is not Telecom admission and never seeds a call.
                    channel.send(com.openautolink.carplaylab.telecom.CallObservationSubscriptions.nonlegacyCalls())
                    status("media stage=runtime-subscriptions-sent count=6")
                    var received = 0L
                    val replyPolicy = com.openautolink.carplaylab.WirelessReplyPolicy()
                    while (!closed.get() && !channel.isClosed && !session.isClosed) {
                        carData?.tick(session.encryptedEventReady)
                        metadata?.tick()
                        val frame = channel.recv(carData?.pollTimeout(1000) ?: 1000)
                        if (frame != null) {
                            // Install against the live runtime owner at the next actual observation,
                            // never against a one-time authentication snapshot or fake replay.
                            if (incoming == null && (frame.messageId == 0x4155 || frame.messageId == 0x4158)) {
                                incoming = telecomExperiment.authenticatedLink(
                                    controlSender = { command, fence -> queuePhoneControl(session, command, fence) },
                                    rejectSender = { fence -> queueReject(session, channel, fence) },
                                    diagnostic = { line -> audioDiagnostic(session, line) },
                                ) {
                                    !closed.get() && !admissionRevoked.get() && runtimeReady && runtimeOwner === session &&
                                        session in sessions && session.controlAuthenticated && !session.isClosed
                                }
                                incoming?.let { runtimeIncoming[session] = it }
                            }
                            if (incoming?.handle(frame) != true && metadata?.handle(frame) != true && carData?.handle(frame) != true && replyPolicy.allow(frame.messageId)) {
                                val reply = runtimeReply?.invoke(frame)
                                if (reply != null) { channel.send(reply) }
                                else status("media stage=runtime-endpoint-reply-unavailable")
                            }
                            received++
                            if (received == 1L || received % 100 == 0L) status("media stage=runtime-message-received count=$received")
                        }
                        carData?.tick(session.encryptedEventReady)
                    }
                } catch (failure: Exception) {
                    if (!closed.get()) {
                        runtimeFailureStage = failureStage
                        status("media stage=runtime-control-failed type=${failure.javaClass.simpleName}")
                        generateSequence<Throwable>(failure) { it.cause }.take(4).forEach { cause ->
                            val at = cause.stackTrace.take(3).joinToString("|") { "${it.className.substringAfterLast('.')}.${it.methodName}:${it.lineNumber}" }
                            status("BENCH_DIAG failureType=${cause.javaClass.simpleName} at=$at")
                        }
                        if (failure is Iap2IdentificationException) status("media identification failure=${failure.message}")
                    }
                }
                finally {
                    if (runtimeOwner === session) runtimeReady = false
                    incoming?.close()
                    if (incoming?.awaitClosed(0) == true) runtimeIncoming.remove(session, incoming)
                    metadata?.close()
                    runtimeMetadata.remove(session)
                    val carData = ownedCarData
                    try { carData?.let { retireCarData(session, it) } }
                    finally {
                        // Keep tunnel registered until reporter and appearance retirement have acknowledged.
                        runCatching { com.openautolink.carplaylab.closeRuntime({ channel.close() }, { session.close() }) }
                        tunnels.remove(session, channel)
                        if (runtimeOwner === session) runtimeOwner = null
                    }
                }
            }
            true
        } catch (_: RejectedExecutionException) { tunnels.remove(session, channel); runtimeOwner = null; channel.close(); false }
    }
    /** Separately consented controlled-single-call transport; display consent alone DOES NOT enable it.
     * Exact call/consent/account/runtime fences survive the queue and are rechecked inside the writer.
     * Queue admission is not a write or phone acknowledgement. No automatic retry on any outcome.
     */
    private fun queuePhoneControl(session: AirPlaySession, control: com.shilapi.xcertplay.airplay.PhoneControl,
        commandCurrent: () -> Boolean): Boolean {
        if (!currentMediaOwner(session)) return false
        return try {
            input.execute {
                val outcome = session.sendPhoneControl(control) { currentMediaOwner(session) && commandCurrent() }
                val line = "media stage=phone-control command=${control.name} outcome=${outcome.name}"
                com.openautolink.carplaylab.telecom.NativeCallDiagnostics.record(diagnosticContext, line)
                audioDiagnostic(session, line)
            }
            true
        } catch (_: RejectedExecutionException) { false }
    }
    private val mediaPlayEpoch = java.util.concurrent.atomic.AtomicLong()
    /** Source-qualified communications EndCall(action0). Separate from active-call HID Drop. */
    private fun queueReject(session: AirPlaySession, channel: Iap2Session, commandCurrent: () -> Boolean): Boolean {
        fun current() = currentMediaOwner(session) && tunnels[session] === channel && commandCurrent()
        if (!current()) return false
        return try {
            input.execute {
                val outcome = if (!current()) "WITHHELD" else try {
                    channel.sendAndAwaitWritten(
                        com.openautolink.carplaylab.telecom.IncomingRejectMessage.frame(), 5_000, ::current)
                    "WRITTEN"
                } catch (_: Exception) { "FAILED" }
                val line = "media stage=phone-control command=REJECT outcome=$outcome"
                com.openautolink.carplaylab.telecom.NativeCallDiagnostics.record(diagnosticContext, line)
                audioDiagnostic(session, line)
            }
            true
        } catch (_: RejectedExecutionException) { false }
    }
    /** Queue only for the exact authenticated metadata owner; queue admission is not phone receipt. */
    fun mediaCommand(session: AirPlaySession, index: Int): Boolean {
        val playEpoch = mediaPlayEpoch.get()
        fun current() = currentMediaOwner(session) && (index != 1 || playEpoch == mediaPlayEpoch.get())
        if (index !in 1..5) { commandQueue(0, "declined", "invalid-index"); return false }
        if (!current()) { commandQueue(index, "declined", "stale-owner"); return false }
        if (index == 2) {
            // Cancel intent at ingress even if the bounded HID worker is blocked/full.
            mediaPlayEpoch.incrementAndGet()
            sink.cancelPendingMediaPlay()
        }
        return try {
            input.execute {
                // Neither a Binder focus request nor an event write may own the lifecycle monitor.
                // Revalidate the exact authenticated owner after focus, which can outlast revocation.
                if (!current()) { commandQueue(index, "declined", "stale-owner"); return@execute }
                val disposition = if (index == 1) sink.prepareUserPlay(::current) else null
                if (disposition != null) {
                    val live = current()
                    status("media stage=media-play-disposition result=${if (live) disposition.token else "stale-owner"} " +
                        "transport=${if (live && disposition.transportPermitted) "permitted" else "withheld"} " +
                        "output=${if (live && disposition.outputPermitted) "permitted" else "withheld"}")
                    // Retain old receipts for actual permission/denial, never label deferral a grant.
                    if (!live || disposition.outputPermitted || !disposition.transportPermitted)
                        status("media stage=media-focus-recovery reason=user-play result=${if (!live) "stale-owner" else if (disposition.outputPermitted) "permitted" else "denied"}")
                }
                val sent = (disposition?.transportPermitted != false) && current() && session.sendMedia(index)
                status("media stage=media-control index=$index outcome=${if (sent) "sent" else "failed"}")
            }
            commandQueue(index, "accepted", "admitted")
            true
        } catch (_: RejectedExecutionException) { commandQueue(index, "deferred", "queue-full"); false }
    }
    private fun commandQueue(index: Int, outcome: String, reason: String) {
        status("media stage=media-command-queue index=$index outcome=$outcome reason=$reason")
    }
    private fun currentMediaOwner(session: AirPlaySession) = !closed.get() && !admissionRevoked.get() &&
        active === session && runtimeOwner === session && runtimeReady && session in sessions &&
        !session.isClosed && session.controlAuthenticated && session.encryptedEventReady

    /** Finite, bounded, deduped observer admission. False is dropped, never an automatic retry. */
    fun audioDiagnostic(session: AirPlaySession, message: String): Boolean {
        if (message.length > 384 ||
            (com.openautolink.carplaylab.telecom.NativeCallDiagnostics.sanitize(message) == null &&
                !audioDiagnosticSchemas.any { it.matches(message) }) || !currentMediaOwner(session)) return false
        synchronized(diagnosticLock) {
            if (!currentMediaOwner(session)) return false
            val now = System.nanoTime()
            if (diagnosticOwner !== session || now - diagnosticWindowNs >= 1_000_000_000L) {
                diagnosticOwner = session; diagnosticWindowNs = now; diagnosticSeen.clear()
            }
            if (message in diagnosticSeen || diagnosticSeen.size >= 32) return false
            diagnosticSeen.add(message)
            val queued = enqueueStatus(message) { currentMediaOwner(session) }
            if (!queued) diagnosticSeen.remove(message)
            return queued
        }
    }
    /** Coordinates relative to the actual video Surface, not its parent/letterboxed viewport. */
    fun touch(action: Int, x: Float, y: Float, width: Int, height: Int) {
        if (closed.get()) return
        val point = MediaPolicy.touch(action, x, y, width, height) ?: return
        sendTouch(point)
    }
    /** Parent-coordinate variant for a video Surface laid out at this exact render rectangle. */
    fun touch(action: Int, x: Float, y: Float, renderRect: CarPlayRenderRect) {
        if (closed.get()) return
        val point = MediaPolicy.touch(action, x, y, renderRect) ?: return
        sendTouch(point)
    }
    private fun sendTouch(point: TouchPoint) {
        touchContacts(listOf(AirPlayContact(id = 0, x = point.x.toDouble(), y = point.y.toDouble(), down = point.down)))
    }
    fun touchContacts(contacts: List<AirPlayContact>) {
        if (contacts.isEmpty() || closed.get()) return
        val snapshot = contacts.take(2).map { it.copy() }
        val session = active ?: return
        try {
            input.execute {
                if (closed.get() || active !== session || session !in sessions || session.isClosed) return@execute
                if (session.sendTouch(snapshot))
                    status("media stage=touch-sent count=${snapshot.count { it.down }.coerceAtLeast(1)}")
            }
        } catch (_: RejectedExecutionException) { status("media stage=touch-queue-unavailable") }
    }
    private fun status(message: String) {
        enqueueStatus(message)
    }
    private fun enqueueStatus(message: String, stillCurrent: () -> Boolean = { true }): Boolean {
        if (statuses.isShutdown) return false
        return runCatching { statuses.execute {
            if (!stillCurrent()) return@execute
            val drops = statusQueueDrops.getAndSet(0)
            if (drops > 0) runCatching { onStatus("media stage=status-queue drops=$drops") }
            runCatching { onStatus(message) }
        }; true }.getOrDefault(false)
    }
    /** Cancel fences late authentication without releasing storage or waiting for control workers. */
    fun revokePendingAdmission() {
        admissionRevoked.set(true)
        // Never wait for the durable commit fence while holding the receiver's unrelated lock.
        peerAdmission?.retire()
        runtimeLinks.values.forEach { it.revoke() }
        runtimeMetadata.values.forEach { it.revoke() }
        runtimeIncoming.values.forEach { it.close() }
    }
    override fun close() {
        if (!closed.compareAndSet(false, true)) { retryRetirement(); return }
        revokePendingAdmission()
        telemetry.retire()
        val retiring = synchronized(lock) {
            runCatching { server?.close() }; server = null; port = 0; active = null
            allSessions.toList()
        }
        // Reach actual transport cancellation before any callback/OS cleanup can wait for focus.
        retiring.forEach { it.cancelPendingIo() }
        sink.revokePendingWork()
        deadlines.shutdownNow(); input.shutdownNow(); runtime.shutdownNow()
        retryRetirement()
    }
    /** Only the runtime worker, or retirement after that worker stops, closes exact car-data owners. */
    private fun retireCarData(session: AirPlaySession, owner: RuntimeCarDataLink) {
        if (runCatching { owner.close() }.isSuccess) runtimeLinks.remove(session, owner)
    }
    private fun retryRetirement() {
        synchronized(retirementLock) {
            if (retirement.count == 0L) return
            if (retirementRunning.get()) { retirementRetryRequested.set(true); return }
            retirementRunning.set(true)
            retirementRetryRequested.set(false)
        }
        // Control threads can still be persisting a pairing after close(); hold exact state lock until retired.
        Thread({
            try {
                // A blocked focus/write is cancellable, not acknowledged as retired. Keep the
                // pairing lock and every exact resource until the concrete input worker exits.
                while (!input.awaitTermination(1, TimeUnit.SECONDS)) { /* exact worker retained */ }
                allSessions.toList().forEach { it.close() }
                tunnels.values.forEach { it.close() }; tunnels.clear()
                allSessions.toList().forEach { it.awaitControlStopped() }
                // A slow OS worker is not a permanent loss of the retirement retry path.
                while (!runtime.awaitTermination(1, TimeUnit.SECONDS)) { /* exact owner retained */ }
                // Exact Telecom owners survive the runtime finally until disconnect/destroy ack.
                runtimeIncoming.entries.toList().forEach { (session, owner) ->
                    owner.close()
                    while (!owner.awaitClosed(1_000)) {
                        if (owner.cleanup == com.openautolink.carplaylab.telecom.ManagedIncomingBridge.Cleanup.FAILED)
                            return@Thread // retain owner and withhold parent ack; close() retries
                    }
                    runtimeIncoming.remove(session, owner)
                }
                sink.close()
                // Factories and their finally blocks have now stopped. Retry concrete cleanup once
                // per close lifecycle; a failed exact owner fences storage release and acknowledgment.
                runtimeLinks.entries.toList().forEach { (session, owner) -> retireCarData(session, owner) }
                if (runtimeLinks.isNotEmpty()) return@Thread
                queueRetentionState()
                while (!sink.awaitRetired(1_000)) {
                    queueRetentionState()
                    // Coalesce Retry onto this cleanup owner, never the lifecycle caller.
                    if (retirementRetryRequested.getAndSet(false)) sink.close()
                }
                // A terminal raw receipt is not release. Keep the same passive export worker
                // alive until the exact sink (including stalled microphone release) retires.
                // Retirement has one cleanup worker at a time. Once the final drain is
                // accepted and shutdown begins, a later storage-close retry must neither
                // duplicate RETIRED nor submit to this permanently closed executor.
                if (statusRetirementQueued.compareAndSet(false, true)) {
                    captureLedger.root.event(com.shilapi.xcertplay.media.CaptureAcquisitionLedger.Action.RETIRED,
                        com.shilapi.xcertplay.media.CaptureAcquisitionLedger.Result.OK)
                    val hasPendingTerminals = captureLedger.terminals.pending() > 0
                    while (true) {
                        try { statuses.execute {
                            try {
                                captureLedger.drain()
                                repeat(com.shilapi.xcertplay.media.CaptureTerminalLedger.MAX_ATTEMPTS) {
                                    if (captureLedger.terminals.pending() > 0) captureLedger.terminals.flush()
                                }
                                reportRetentionState(final = true)
                            } finally {
                                // Pending immutable results get their final bounded durability
                                // attempts BEFORE shutdown, on this exact passive worker.
                                if (hasPendingTerminals) statuses.shutdown()
                            }
                        }; break }
                        catch (_: RejectedExecutionException) { Thread.sleep(10) }
                    }
                    // Preserve 539 R1 storage-close retry ordering when no terminal work remains.
                    // Graceful shutdown still drains the accepted final status task.
                    if (!hasPendingTerminals) statuses.shutdown()
                }
                persistence.close()
                retirement.countDown()
            } catch (_: Exception) { /* Withhold acknowledgment; parent must not admit a successor. */ }
            finally {
                val retry = synchronized(retirementLock) {
                    retirementRunning.set(false)
                    retirementRetryRequested.getAndSet(false)
                }
                // A Retry delivered during failed cleanup must not be dropped with the worker.
                if (retry) retryRetirement()
            }
        }, "lab-media-state-release")
            .apply { isDaemon = true; start() }
    }
    /** Parent lifecycle acknowledgment; invoke off UI/receiver callback threads, after close(). */
    fun awaitClosed(timeoutMs: Long): Boolean {
        require(timeoutMs >= 0)
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        fun remainingNs() = (deadline - System.nanoTime()).coerceAtLeast(0)
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper() &&
            runtimeIncoming.values.any { !it.awaitClosed(0) }) return false
        if (!retirement.await(remainingNs(), TimeUnit.NANOSECONDS)) return false
        if (runtimeLinks.isNotEmpty()) return false
        if (!sink.awaitRetired(TimeUnit.NANOSECONDS.toMillis(remainingNs()))) return false
        acceptThread?.let { accepting ->
            if (accepting !== Thread.currentThread()) TimeUnit.NANOSECONDS.timedJoin(accepting, remainingNs())
            if (accepting.isAlive) return false
        }
        return listOf(runtime, input, deadlines, statuses).all {
            it.awaitTermination(remainingNs(), TimeUnit.NANOSECONDS)
        }
    }
    private fun defaultIdentification(ssid: String) = Iap2IdentificationConfig(
        name = "OAL CarPlay Lab", modelIdentifier = "OAL-auth-bench", manufacturer = "OpenAutoLink",
        serialNumber = "oal-lab-private", firmwareVersion = "0.1-auth-bench", hardwareVersion = "tablet",
        wireless = Iap2WirelessIdentification(btMac, ssid), externalAccessoryProtocol = "com.openautolink.carplaylab")
    private companion object {
        private const val btState = "(disconnected|connecting|connected|disconnecting|stopped|started|off|turning_off|on|turning_on|unknown)"
        val audioDiagnosticSchemas = listOf(
            "media stage=media-command-ingress command=(PLAY|PAUSE|STOP|NEXT|PREVIOUS) controller=(bluetooth|car-service|system-ui|oem|other|unknown) sequence=[0-9]{1,19} elapsedMs=[0-9]{1,19}",
            "carplay-bt event=(hfp-client|a2dp-sink) state=(disconnected|connecting|connected|disconnecting|unknown) previous=(disconnected|connecting|connected|disconnecting|unknown) scope=device owner=unknown",
            "carplay-bt event=hfp-audio state=(disconnected|connecting|connected|unknown) previous=(disconnected|connecting|connected|unknown) scope=device owner=unknown",
            "carplay-bt event=hfp-voice state=(started|stopped|unknown) previous=unknown scope=device owner=unknown",
            "carplay-bt event=adapter state=(on|off|turning-on|turning-off|unknown) previous=unknown scope=device owner=unknown",
            "media stage=bluetooth-event scope=device owner=unknown event=(hfp-connection|a2dp-connection|hfp-audio|hfp-voice|adapter) state=$btState previous=$btState epoch=[0-9]{1,19}",
            "media stage=bluetooth-observer scope=device owner=unknown enabled=(true|false) result=(attached|unavailable|disabled) epoch=[0-9]{1,19}"
        ).map(::Regex)
        val telemetryGenerations = java.util.concurrent.atomic.AtomicLong()
        fun daemon(name: String) = ThreadFactory { task -> Thread(task, name).apply { isDaemon = true } }
    }
}
