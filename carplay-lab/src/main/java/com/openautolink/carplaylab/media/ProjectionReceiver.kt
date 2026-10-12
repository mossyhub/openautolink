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
    private val surface: Surface,
    private val mfi: MfiAuthenticator,
    display: CarPlayDisplayPreferences = CarPlayDisplayPreferences(),
    // Production cannot enroll until the selected BT link proves the controller key. Bench stays explicit.
    private val trustedOnly: Boolean = false,
    private val runtimeCarDataFactory: ((AirPlaySession, (Iap2Frame) -> Unit, (String) -> Unit) -> RuntimeCarDataLink)? = null,
    private val runtimeMetadataFactory: (((String) -> Unit) -> com.openautolink.carplaylab.metadata.RuntimeMetadataLink)? = null,
    private val wired: Boolean = false,
    private val onStatus: (String) -> Unit,
) : Closeable {
    init {
        require(btMac.matches(Regex("[0-9a-fA-F]{2}(:[0-9a-fA-F]{2}){5}")))
        require(surface.isValid) { "Valid Surface required before receiver construction" }
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
    private val persistence = PairingPersistence.openReceiver(File(context.noBackupFilesDir, "pairing-probe"))
    private val trustedControllers = persistence.pairings.controllerIdentifiers()
    val requiresControllerEnrollment: Boolean get() = trustedControllers.isEmpty()
    val pairingId: String get() = persistence.identity.pairingId
    val publicKeyHex: String get() = persistence.identity.publicKeyHex
    @Volatile var port: Int = 0
        private set
    private val closed = AtomicBoolean()
    private val admissionRevoked = AtomicBoolean()
    private val retirement = CountDownLatch(1)
    private val lock = Any()
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
        })
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
    private val microphoneCapability = com.shilapi.xcertplay.media.AndroidMicrophoneSupport.probe(context)
    private val telemetry = ReceiverTelemetry(telemetryGenerations.incrementAndGet())
    fun telemetrySnapshot(): ReceiverTelemetrySnapshot = telemetry.snapshot(System.nanoTime())
    private val sink = AndroidMediaSink(context, surface, display = displaySelection, onStatus = ::status, onSurfaceLost = ::close, telemetry = telemetry, videoSelection = videoSelection)
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
        override fun onCommand(session: AirPlaySession, type: String, params: Map<String, Any?>) {
            if (closed.get() || active !== session || session !in sessions) return
            val known = setOf("disableBluetooth", "modesChanged", "requestUI", "duckAudio", "unduckAudio")
            status("BENCH_DIAG incomingCommand=${if (type in known) type else "other"}")
            if (type.equals("disableBluetooth", true)) bluetoothHandoffRequested = true
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

    /** Explicit selected + MFi + native/OAL opt-in + real phone wireless request authorization.
     * No cross-transport key-binding guarantee is claimed; default remains trusted reconnect only. */
    fun authorizeFirstEnrollment(windowMillis: Long) = synchronized(lock) {
        check(!closed.get() && !admissionRevoked.get() && server == null && trustedOnly)
        require(trustedControllers.isEmpty() && windowMillis in 1..120_000)
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
        check(surface.isValid) { "Valid Surface required" }
        if (server != null) {
            require(policy?.peer == selected?.peer && network === selectedNetwork && admissionSelection === admissionNetwork)
            return@synchronized
        }
        check(runtimeIdentification != null) { "Set exact transport identification before start" }
        status("BENCH_DIAG displayOffer codec=H264 width=${displayConfiguration.width} height=${displayConfiguration.height} fps=${displayConfiguration.fps} fallback=${displaySelection.usedFallback}")
        val admission = try {
            require(!trustedOnly || trustedControllers.size == 1 || firstEnrollmentWindowMillis > 0) {
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
                synchronized(lock) {
                    if (closed.get() || admissionRevoked.get() || network == null && policy?.allows(socket.inetAddress) != true ||
                        sessions.size >= (if (network == null) 2 else 1) || admittedCount >= 32) {
                        socket.close(); status("media stage=admission-declined")
                    } else {
                        admittedCount++
                        socket.tcpNoDelay = true; socket.keepAlive = true
                        val config = benchReceiverConfig(btMac, port, microphoneCapability, displayConfiguration)
                            .copy(hevc = videoSelection.hevcOffered)
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
                            videoSelection = videoSelection)
                        if (peerAdmission?.claim(session, socket.inetAddress, socket.localAddress) == false) {
                            socket.close(); status("CarPlay network stage=accept-declined reason=peer-interface-scope")
                            return@synchronized
                        }
                        sessions.add(session); allSessions.add(session)
                        status("CarPlay network stage=control-accepted authentication=pending")
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
        if (closed.get() || admissionRevoked.get() || session !in sessions || tunnels.isNotEmpty()) return@synchronized false
        val config = runtimeIdentification ?: return@synchronized false
        val channel = Iap2Session.openTunnel(stream, onArtwork = { transfer ->
            if (!closed.get() && !admissionRevoked.get() && runtimeOwner === session &&
                session in sessions && session.controlAuthenticated && runtimeReady)
                runtimeMetadata[session]?.offerArtwork(transfer)
        }) // Default upstream trace is intentionally discarded.
        beginRuntime(session, channel)
    }
    private fun beginRuntime(session: AirPlaySession, channel: Iap2Session): Boolean = synchronized(lock) {
        if (closed.get() || admissionRevoked.get() || session !in sessions || tunnels.isNotEmpty()) return@synchronized false
        val config = runtimeIdentification ?: return@synchronized false
        tunnels[session] = channel
        runtimeOwner = session
        runtimeFailureStage = null
        try {
            runtime.execute {
                var ownedCarData: RuntimeCarDataLink? = null
                var metadata: com.openautolink.carplaylab.metadata.RuntimeMetadataLink? = null
                var failureStage = RuntimeControlFailureStage.IDENTIFICATION
                try {
                    metadata = runtimeMetadataFactory?.invoke(::status)
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
                        check(!closed.get() && !admissionRevoked.get() && session in sessions)
                        if (carData != null) runtimeLinks[session] = carData
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
                    failureStage = RuntimeControlFailureStage.TRANSPORT
                    status("media stage=runtime-authentication-accepted")
                    Iap2ControlMessages.subscriptions().forEach { channel.send(it) }
                    status("media stage=runtime-subscriptions-sent count=5")
                    var received = 0L
                    val replyPolicy = com.openautolink.carplaylab.WirelessReplyPolicy()
                    while (!closed.get() && !channel.isClosed && !session.isClosed) {
                        carData?.tick(session.encryptedEventReady)
                        metadata?.tick()
                        val frame = channel.recv(carData?.pollTimeout(1000) ?: 1000)
                        if (frame != null) {
                            if (metadata?.handle(frame) != true && carData?.handle(frame) != true && replyPolicy.allow(frame.messageId)) {
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
                    metadata?.close()
                    runtimeMetadata.remove(session)
                    val carData = ownedCarData
                    try { carData?.close() }
                    finally {
                        runtimeLinks.remove(session)
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
        if (statuses.isShutdown) return
        runCatching { statuses.execute {
            val drops = statusQueueDrops.getAndSet(0)
            if (drops > 0) runCatching { onStatus("media stage=status-queue drops=$drops") }
            runCatching { onStatus(message) }
        } }
    }
    /** Cancel fences late authentication without releasing storage or waiting for control workers. */
    fun revokePendingAdmission() {
        admissionRevoked.set(true)
        // Never wait for the durable commit fence while holding the receiver's unrelated lock.
        peerAdmission?.retire()
        runtimeLinks.values.forEach { it.revoke() }
        runtimeMetadata.values.forEach { it.revoke() }
    }
    override fun close() {
        if (!closed.compareAndSet(false, true)) { sink.close(); return }
        revokePendingAdmission()
        telemetry.retire()
        val retiring = synchronized(lock) {
            runCatching { server?.close() }; server = null; port = 0; active = null
            allSessions.toList()
        }
        retiring.forEach { runCatching { it.close() } }; tunnels.values.forEach { runCatching { it.close() } }; tunnels.clear()
        sink.close(); deadlines.shutdownNow(); input.shutdownNow(); runtime.shutdownNow(); statuses.shutdownNow()
        // Control threads can still be persisting a pairing after close(); hold exact state lock until retired.
        Thread({
            try {
                retiring.forEach { it.awaitControlStopped() }
                // A slow OS worker is not a permanent loss of the retirement retry path.
                while (!runtime.awaitTermination(1, TimeUnit.SECONDS)) { /* exact owner retained */ }
                while (!input.awaitTermination(1, TimeUnit.SECONDS)) { /* exact owner retained */ }
                persistence.close()
                retirement.countDown()
            } catch (_: Exception) { /* Withhold acknowledgment; parent must not admit a successor. */ }
        }, "lab-media-state-release")
            .apply { isDaemon = true; start() }
    }
    /** Parent lifecycle acknowledgment; invoke off UI/receiver callback threads, after close(). */
    fun awaitClosed(timeoutMs: Long): Boolean {
        require(timeoutMs >= 0)
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        fun remainingNs() = (deadline - System.nanoTime()).coerceAtLeast(0)
        if (!retirement.await(remainingNs(), TimeUnit.NANOSECONDS)) return false
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
        val telemetryGenerations = java.util.concurrent.atomic.AtomicLong()
        fun daemon(name: String) = ThreadFactory { task -> Thread(task, name).apply { isDaemon = true } }
    }
}
