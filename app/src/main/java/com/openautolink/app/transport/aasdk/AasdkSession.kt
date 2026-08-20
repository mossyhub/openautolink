package com.openautolink.app.transport.aasdk

import android.content.Context
import android.util.Log
import com.openautolink.app.audio.AudioFrame
import com.openautolink.app.diagnostics.OalLog
import com.openautolink.app.transport.AudioPurpose
import com.openautolink.app.transport.ConnectionState
import com.openautolink.app.transport.ControlMessage
import com.openautolink.app.transport.hotspot.TcpConnector
import com.openautolink.app.transport.usb.UsbConnectionManager
import com.openautolink.app.video.VideoFrame
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.InputStream
import java.io.OutputStream
import java.net.Socket

/**
 * AA session backed by native aasdk via JNI.
 *
 * The Kotlin layer owns transport (TCP socket to the phone-side companion app
 * over the shared WiFi — Car Hotspot or Phone Hotspot) and exposes the
 * resulting byte streams to the native aasdk C++ library, which speaks the
 * full AA wire protocol. Decoded frames + control messages come back through
 * JNI callbacks and are republished as Kotlin flows.
 *
 * Data flow:
 *   Phone ↔ TCP (companion app over shared WiFi) ↔ TcpConnector ↔ streams
 *     → AasdkTransportPipe → JNI → aasdk C++ → JNI callbacks
 *     → AasdkSession flows → SessionManager → VideoDecoder/AudioPlayer
 */
class AasdkSession(
    private val scope: CoroutineScope,
    private val context: Context,
) : AasdkSessionCallback {

    companion object {
        private const val TAG = "AasdkSession"
        private const val FORCE_RECONNECT_GUARD_MS = 3_000L

        /** Process-wide native onError coalescer.
         *
         *  Lives in the companion object (not on instances) so it survives
         *  AasdkSession instance churn — every startSession() in
         *  SessionManager creates a new AasdkSession, and per-instance state
         *  would reset the dedup window on each new instance. Production logs
         *  showed 14+ identical "Native error: AASDK Error: 30" lines at the
         *  same ms because the storm spans several rapid session recreations.
         *
         *  At most one log per second per (instance-agnostic) message text.
         */
        @Volatile private var lastOnErrorLogMs: Long = 0
        @Volatile private var lastOnErrorMsg: String = ""
        private var onErrorSuppressedCount = 0
        private val onErrorLock = Any()
    }

    // -- Output flows (consumed by SessionManager) --

    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _videoFrames = MutableSharedFlow<VideoFrame>(extraBufferCapacity = 30)
    val videoFrames: SharedFlow<VideoFrame> = _videoFrames.asSharedFlow()

    /** Negotiated video codec type from phone. 3=H.264, 5=H.264_BP, 7=H.265 */
    private val _negotiatedCodecType = MutableStateFlow(0)
    val negotiatedCodecType: StateFlow<Int> = _negotiatedCodecType.asStateFlow()

    private val _audioFrames = MutableSharedFlow<AudioFrame>(extraBufferCapacity = 60)
    val audioFrames: SharedFlow<AudioFrame> = _audioFrames.asSharedFlow()

    private val _controlMessages = MutableSharedFlow<ControlMessage>(extraBufferCapacity = 64)
    val controlMessages: Flow<ControlMessage> = _controlMessages.asSharedFlow()

    // -- Config (set before start()) --

    var sdrConfig = AasdkSdrConfig()

    // TCP connector — used in "hotspot" transport mode (Car Hotspot / Phone Hotspot)
    @Volatile private var _tcpConnector: TcpConnector? = null
    /** Serializes connector ownership, socket handoff, and synchronous teardown. */
    private val connectionStartLock = Any()

    /**
     * WPP inbound listener. Only non-null in "wpp" transport mode, where the phone
     * connects to us rather than the other way round.
     */
    private var _wppServer: com.openautolink.app.transport.hotspot.WppTcpServer? = null

    // -- (A) Video frame-flow diagnostic counters --
    // Periodically summarize inbound frame flow into the triaged DiagnosticLog
    // so a black-video gap definitively shows whether bytes are still arriving
    // (P-frames flowing but no IDR to anchor) vs the encoder being silent.
    // All touched only from onVideoFrame (native io thread) + reset on start.
    private var vfWindowStartMs: Long = 0L
    private var vfFrameCount: Int = 0
    private var vfKeyframeCount: Int = 0
    private var vfByteCount: Long = 0L
    private var vfLastFrameMs: Long = 0L
    private val vfLogIntervalMs = 2000L

    // USB connection manager — only used in "usb" transport mode
    private var _usbConnectionManager: UsbConnectionManager? = null

    /** Current transport mode: "hotspot" or "usb" */
    var transportMode: String = "hotspot"

    /** Manual IP address for testing (emulator). Overrides gateway/mDNS discovery. */
    var manualIpAddress: String? = null

    /** True when stop() was called explicitly (user-initiated). False when session died on its own. */
    @Volatile
    private var explicitStop = false

    /** Consecutive reconnect failures — drives exponential backoff. Also
     *  exposed as a StateFlow so the UI controller can escalate (e.g., open
     *  the phone picker) after a threshold of repeated failures. */
    @Volatile
    private var consecutiveReconnectFailures = 0
    private val _reconnectAttempt = MutableStateFlow(0)
    val reconnectAttempt: StateFlow<Int> = _reconnectAttempt.asStateFlow()

    /** True when the last failure was an AA protocol/handshake error (Error 30). */
    @Volatile
    private var lastFailureWasProtocolError = false

    private var transportPipe: AasdkTransportPipe? = null

    /**
     * Handle to the single pending auto-reconnect coroutine (the backoff delay +
     * transport restart scheduled from [onSessionStopped]). Tracked so that (a)
     * a new teardown cancels an already-pending retry instead of stacking a
     * second timer, and (b) a successful [onSessionStarted] cancels any pending
     * retry so a STALE timer can't tear down the healthy session it raced with.
     *
     * Root cause of the deep-sleep reconnect storm (0.1.363 log 2026-07-19):
     * several rapid teardowns during the wake window each armed their own
     * fire-and-forget `scope.launch { delay(); startTcp() }` with no handle, so
     * a leftover timer restarted the transport on top of an already-connected
     * session — killing it and scheduling yet another retry (self-perpetuating).
     * Guarded by [reconnectLock]; assignments and cancels are serialized.
     */
    @Volatile
    private var reconnectJob: kotlinx.coroutines.Job? = null
    private val reconnectLock = Any()
    private val forceReconnectGate = ReconnectSingleFlightGate()

    private fun cancelPendingReconnect(why: String) {
        synchronized(reconnectLock) {
            reconnectJob?.let {
                if (it.isActive) {
                    it.cancel()
                    OalLog.i(TAG, "Cancelled pending reconnect ($why)")
                }
            }
            reconnectJob = null
        }
    }

    fun start() {
        explicitStop = false
        consecutiveReconnectFailures = 0
        _reconnectAttempt.value = 0
        lastFailureWasProtocolError = false
        _connectionState.value = ConnectionState.DISCONNECTED

        when (transportMode) {
            "usb" -> startUsb()
            "wpp" -> startWpp()
            else -> startTcp()
        }
    }

    /**
     * Google WiFi Projection Protocol: the phone connects to US.
     *
     * The opposite direction to [startTcp]. In WPP the head unit advertises its
     * `{ip, port}` over Bluetooth RFCOMM (see `AaWirelessBtServer`) and the phone
     * then opens the TCP connection inbound. Proven against AA 17.4 on 2026-08-06:
     * full BT handshake, WiFi association, then an inbound connection from the phone.
     *
     * The native session neither knows nor cares which side dialled — it needs a
     * connected socket — so this reuses [handleConnection] unchanged.
     */
    private fun startWpp() {
        // Which side dials depends on which endpoint we advertised over Bluetooth.
        //
        //   companion loopback  -> the COMPANION is the server (it listens on 5277
        //                          for us, and bridges AA's localhost connection to
        //                          that socket). We must DIAL OUT, exactly as in
        //                          ordinary hotspot mode.
        //   car's own address   -> WE are the server and the phone dials in.
        //
        // Getting this wrong deadlocks silently with both ends listening: observed
        // in-vehicle at 16:18, where AA connected to the companion's proxy and the
        // companion then timed out with "No car socket within 30000ms — AA
        // connected but no car ready", because the car was listening too.
        // If we already know where the companion is, dial it now.
        //
        // Save & Reconnect restarts the session, and the restart came up in
        // listen mode waiting for an inbound connection the car's AP will not
        // permit. No new Bluetooth handshake follows a restart — the phone is
        // already connected and has no reason to re-dial — so the car sat
        // listening forever showing "searching for phone" while the phone showed
        // "connected". Discovery even found the phone; nothing acted on it,
        // because in WPP mode only the handshake triggers a dial.
        val known = com.openautolink.app.transport.bluetooth.AaWirelessBtControl
            .lastKnownPhoneIp
        if (known != null) {
            OalLog.i(TAG, "WPP restart with a known companion at $known — dialling " +
                    "rather than waiting for a handshake that is not coming")
            startTcp(manualIp = known)
            // Dialling the companion is only half the connection. Android Auto's
            // previous localhost socket belonged to the bridge that just ended;
            // reconnecting the car socket does not by itself make AA open a new
            // one, even though the reserved loopback port is now stable. Re-run
            // the Bluetooth exchange to provoke that fresh AA attach.
            com.openautolink.app.transport.bluetooth.AaWirelessBtControl.readvertise()
            return
        }
        // NOTE: the ByeBye for this path lives in startTcp(), not here — Save &
        // Reconnect reaches the transport through connect(), never through
        // SessionManager.reconnect(), so a goodbye added there is never sent.
        // Measured: "stop() closing transport WITHOUT ByeBye reason=stopped"
        // followed by ~50 handshakes in 60s with no reconnection.

        // Otherwise wait for the handshake to tell us where to go.
        // Deliberately NOT deciding listen-vs-dial here.
        //
        // At session start the companion's address is almost always unknown -
        // discovery has not run yet - so this used to pick "listen" every time and
        // could never change its mind. Measured: the listener bound at 16:16:43,
        // the companion was located at 16:21:35, and in between the car completed
        // ten handshakes advertising the companion's proxy while never dialling it
        // once. Android Auto connected to that proxy ten times and timed out ten
        // times.
        //
        // The dial is now triggered by the Bluetooth handshake instead, at the
        // moment it selects the loopback endpoint and therefore knows the address.
        // See AaWirelessBtControl -> onCompanionSelected.

        OalLog.i(TAG, "Starting aasdk session (WPP transport — phone connects to us)")
        _wppServer?.stop()
        _wppServer = com.openautolink.app.transport.hotspot.WppTcpServer(
            scope = scope,
            onSocketReady = { wppSocket ->
                scope.launch(Dispatchers.IO) {
                    OalLog.i(TAG, "WPP socket accepted — starting aasdk native session")
                    handleConnection(wppSocket)
                }
            },
        )
        _wppServer?.start()
    }

    /**
     * Dial a companion that the Bluetooth handshake just located.
     *
     * Called when the handshake selects the loopback endpoint, so the car socket
     * is opened before Android Auto is told to connect - rather than the proxy
     * holding an AA connection open waiting for a car that never dials.
     */
    /**
     * Invoked immediately before each native session start, so the UI can make
     * sure the decoder still has a surface.
     */
    @Volatile
    var onNativeSessionStarting: (() -> Unit)? = null

    /** Tracks the TCP target across every start path, including WPP restart. */
    private val companionDialState = CompanionDialState()

    /**
     * True briefly around a deliberate session replacement.
     *
     * The native error that follows our own teardown looks identical to a real
     * protocol rejection, and misreading it inflates the reconnect backoff.
     */
    @Volatile
    private var selfInflictedTeardown = false

    fun dialCompanion(ip: String) {
        // The ignition-cycle simulation holds the session down deliberately;
        // reconnecting mid-window makes the test meaningless.
        if (com.openautolink.app.transport.bluetooth.AaWirelessBtControl.simulatedIgnitionOff) {
            OalLog.i(TAG, "Ignition simulation is in its off window — not dialling $ip")
            return
        }
        // Refuse only a same-target dial that is genuinely in flight. An active
        // connector can also be an endless retry to a phone that has left the car;
        // when Bluetooth resolves a different phone, that retry must be replaced.
        // Refusing whenever a connector merely EXISTS makes stale ownership
        // permanent and is why Liz's reachable companion was ignored all day.
        val existing = _tcpConnector
        val existingIsActive = existing?.isActive == true
        if (companionDialState.shouldIgnoreActiveDial(ip, existingIsActive)) {
            OalLog.i(TAG, "Already dialling $ip — ignoring the same-target request while " +
                    "that connector is still active")
            return
        }
        // A live native session outranks a fresh handshake asking us to dial.
        //
        // Re-advertising (0.1.422) makes the phone re-dial over Bluetooth, which
        // produces a new handshake, which resolves the endpoint and calls back
        // here. Dialling then tears down the session that handshake was for —
        // handleConnection() replaces the live pipe — which surfaces as a
        // protocol error, schedules a retry, and the retry re-advertises again.
        // Measured 2026-08-10 08:21-08:24: 13 endpoint resolutions, 21 session
        // deaths, sessions dying 8ms after being created, for three minutes.
        //
        // It only stopped because the backoff grew to 30s and finally left a gap
        // long enough for one session to outlive the next handshake.
        //
        // If a session is already carrying data to this same companion, the
        // handshake is telling us something we have already acted on.
        if (companionDialState.shouldIgnoreRedial(ip, transportPipe != null)) {
            OalLog.i(TAG, "Already connected to $ip — ignoring the re-dial from " +
                    "this handshake rather than tearing down a live session")
            return
        }
        if (existing != null) {
            if (existingIsActive) {
                OalLog.i(TAG, "Replacing active connector target " +
                        "${existing.manualIp ?: "discovery"} with different phone $ip")
            } else {
                OalLog.i(TAG, "Replacing a dead connector to dial $ip")
            }
        }
        OalLog.i(TAG, "Companion at $ip — dialling its proxy now (companion is the server)")
        _wppServer?.stop()
        _wppServer = null
        startTcp(manualIp = ip)
    }

    private fun startTcp(manualIp: String? = null) {
        OalLog.i(TAG, "Starting aasdk session (TCP/hotspot transport)")
        synchronized(connectionStartLock) {
            // Record at the common ownership boundary. WPP restart calls startTcp()
            // directly, so recording only in dialCompanion() leaves the guard stale.
            companionDialState.recordStartTcpTarget(manualIp)
            if (!manualIp.isNullOrBlank()) {
                OalLog.i(TAG, "Recorded TCP target $manualIp for the live-session re-dial guard")
            }

            _tcpConnector?.stop()
            _tcpConnector = null
            lateinit var connector: TcpConnector
            connector = TcpConnector(
                context,
                scope,
                onSocketReady = { tcpSocket ->
                    scope.launch(Dispatchers.IO) {
                        synchronized(connectionStartLock) {
                            if (_tcpConnector !== connector) {
                                OalLog.i(TAG, "Superseded connector delivered a late socket — closing it")
                                runCatching { tcpSocket.close() }
                            } else {
                                OalLog.i(TAG, "TCP socket ready — starting aasdk native session")
                                handleConnection(tcpSocket)
                            }
                        }
                    }
                },
                onConnectFailure = {
                    synchronized(connectionStartLock) {
                        if (_tcpConnector === connector) {
                            // Drive the same reconnectAttempt counter the session-stopped
                            // path uses, even when TCP never reaches native aasdk.
                            consecutiveReconnectFailures++
                            _reconnectAttempt.value = consecutiveReconnectFailures
                        } else {
                            OalLog.i(TAG, "Ignoring connect failure from a superseded connector")
                        }
                    }
                },
            )
            // An explicit dial target wins over the configured manual IP.
            connector.manualIp = manualIp ?: manualIpAddress
            _tcpConnector = connector
            connector.start()
        }
    }

    private fun startUsb() {
        OalLog.i(TAG, "Starting aasdk session (USB transport)")
        _usbConnectionManager?.stop()
        _usbConnectionManager = UsbConnectionManager(context, scope) { usbTransportPipe ->
            scope.launch(Dispatchers.IO) {
                OalLog.i(TAG, "USB transport ready — starting aasdk native session")
                handleUsbConnection(usbTransportPipe)
            }
        }
        _usbConnectionManager?.start()
    }

    private fun handleUsbConnection(pipe: AasdkTransportPipe) {
        _connectionState.value = ConnectionState.CONNECTING

        OalLog.i(TAG, "Starting native aasdk session (USB): ${sdrConfig.videoWidth}x${sdrConfig.videoHeight}")
        onNativeSessionStarting?.invoke()

        transportPipe = pipe

        try {
            AasdkNative.nativeCreateSession()
            AasdkNative.nativeStartSession(pipe, this, sdrConfig)
        } catch (e: Exception) {
            OalLog.e(TAG, "Native session start failed (USB): ${e.message}")
            pipe.close()
            transportPipe = null
            _connectionState.value = ConnectionState.DISCONNECTED
        }
    }

    private fun handleConnection(socket: Socket) {
        _connectionState.value = ConnectionState.CONNECTING

        // Say goodbye to the phone before replacing a live native session.
        //
        // Every teardown on this path logged "stop() closing transport WITHOUT
        // ByeBye", so the phone kept believing the old session was alive and
        // refused the new one — about 50 handshakes in 60 seconds with no
        // reconnection. Save & Reconnect reaches the transport through
        // connect(), never through SessionManager.reconnect(), which is why a
        // goodbye added there was never sent.
        if (transportPipe != null) {
            OalLog.i(TAG, "Replacing a live session — sending ByeBye first")
            selfInflictedTeardown = true
            runCatching { AasdkNative.nativeShutdownGracefully("reconnect", 400) }
            Thread.sleep(400)
            // Give the native error that follows our own stop() a moment to be
            // attributed correctly, then go back to trusting Error 30.
            scope.launch {
                kotlinx.coroutines.delay(1500)
                selfInflictedTeardown = false
            }
        }

        try {
            socket.soTimeout = AasdkTransportPipe.READ_POLL_TIMEOUT_MS
        } catch (e: Exception) {
            OalLog.e(TAG, "Cannot bound TCP read for safe teardown: ${e.message}")
            runCatching { socket.close() }
            _connectionState.value = ConnectionState.DISCONNECTED
            return
        }
        val input = socket.getInputStream()
        val output = socket.getOutputStream()

        OalLog.i(TAG, "Starting native aasdk session: ${sdrConfig.videoWidth}x${sdrConfig.videoHeight}")
        // Re-attach the surface on EVERY native session start, not only when a
        // decoder is constructed. A transport restart (retry, or a dial from the
        // Bluetooth handshake) reuses the existing decoder, so the
        // decoder-created hook never fires — and the reused decoder has no
        // surface after the old one was destroyed. Measured: video ran at 41fps
        // for 25s into a decoder with nothing to render to.
        onNativeSessionStarting?.invoke()

        transportPipe = AasdkTransportPipe(input, output)

        try {
            AasdkNative.nativeCreateSession()
            AasdkNative.nativeStartSession(transportPipe!!, this, sdrConfig)
        } catch (e: Exception) {
            OalLog.e(TAG, "Native session start failed: ${e.message}")
            transportPipe?.close()
            transportPipe = null
            _connectionState.value = ConnectionState.DISCONNECTED
        }
    }

    /**
     * Queue a ByeBye to the phone. Returns immediately; the native side sends the
     * frame and then tears the transport down on its own worker once the phone
     * acknowledges or [timeoutMs] elapses.
     *
     * Marks the stop as explicit so the reconnect logic treats the resulting
     * disconnect as intentional rather than a dropout to be recovered from.
     */
    fun shutdownGracefully(reason: String, timeoutMs: Int) {
        explicitStop = true
        OalLog.i(TAG, "Graceful shutdown requested (reason=$reason, timeout=${timeoutMs}ms)")
        cancelPendingReconnect("graceful shutdown")
        AasdkNative.nativeShutdownGracefully(reason, timeoutMs)
    }

    fun stop() {
        explicitStop = true
        OalLog.i(TAG, "Stopping aasdk session")
        cancelPendingReconnect("explicit stop")
        synchronized(connectionStartLock) {
            _tcpConnector?.stop()
            _tcpConnector = null
            _wppServer?.stop()
            _wppServer = null
            _usbConnectionManager?.stop()
            _usbConnectionManager = null
            transportPipe = NativeTransportTeardown.closePipeBeforeNativeStop(transportPipe) {
                OalLog.i(TAG, "Transport pipe closed — stopping native session")
                AasdkNative.nativeStopSession()
                OalLog.i(TAG, "Native session stop completed after transport close")
            }
        }
        _connectionState.value = ConnectionState.DISCONNECTED
    }

    /**
     * Force a clean reconnect without setting [explicitStop]. Used for sleep/wake
     * recovery and for the JNI abort path. The native session is torn down (which
     * fires onSessionStopped → auto-reconnect path) and the transport connector
     * is restarted.
     */
    fun forceReconnect(reason: String) {
        if (!forceReconnectGate.tryStart()) {
            OalLog.i(TAG, "Force reconnect already in flight — ignoring duplicate: $reason")
            return
        }
        try {
            OalLog.w(TAG, "Force reconnect: $reason")
            // Treat the upcoming nativeStopSession() as an explicit stop so the
            // onSessionStopped handler doesn't schedule its own auto-reconnect 3s
            // later — we're doing the restart ourselves immediately. Without this
            // both reconnects race and one fails with "Native session start failed".
            explicitStop = true
            cancelPendingReconnect("force reconnect")
            synchronized(connectionStartLock) {
                _tcpConnector?.stop()
                _tcpConnector = null
                _wppServer?.stop()
                _wppServer = null
                _usbConnectionManager?.stop()
                _usbConnectionManager = null
                transportPipe = NativeTransportTeardown.closePipeBeforeNativeStop(transportPipe) {
                    OalLog.i(TAG, "Transport pipe closed — stopping native session")
                    AasdkNative.nativeStopSession()
                    OalLog.i(TAG, "Native session stop completed after transport close")
                }
            }
            _connectionState.value = ConnectionState.DISCONNECTED
            // Now clear the explicitStop flag so the freshly-started session can
            // auto-reconnect normally if its connection later dies.
            explicitStop = false
            // Restart transport.
            when (transportMode) {
                "usb" -> startUsb()
                "wpp" -> startWpp()
                else -> {
                    // Same reason as the retry path: without the known address this
                    // falls through to the gateway heuristic and dials the house
                    // router.
                    val known = com.openautolink.app.transport.bluetooth
                        .AaWirelessBtControl.lastKnownPhoneIp
                    startTcp(manualIp = known)
                }
            }
        } finally {
            explicitStop = false
            // Keep the gate through the native teardown/start overlap, then allow
            // a genuinely later recovery request. This also prevents a surface,
            // wake and stall trigger from stacking synchronous native restarts.
            scope.launch {
                delay(FORCE_RECONNECT_GUARD_MS)
                forceReconnectGate.finish()
            }
        }
    }

    // -- Input forwarding (app → phone via native aasdk) --

    fun sendTouchEvent(action: Int, pointerId: Int, x: Float, y: Float, pointerCount: Int) {
        AasdkNative.nativeSendTouchEvent(action, pointerId, x, y, pointerCount)
    }

    fun sendMultiTouchEvent(action: Int, actionIndex: Int, ids: IntArray, xs: FloatArray, ys: FloatArray) {
        AasdkNative.nativeSendMultiTouchEvent(action, actionIndex, ids, xs, ys)
    }

    fun sendKeyEvent(keyCode: Int, isDown: Boolean) {
        AasdkNative.nativeSendKeyEvent(keyCode, isDown)
    }

    fun sendGpsLocation(lat: Double, lon: Double, alt: Double,
                        speed: Float, bearing: Float, timestampMs: Long) {
        AasdkNative.nativeSendGpsLocation(lat, lon, alt, speed, bearing, timestampMs)
    }

    fun sendVehicleSensor(sensorType: Int, data: ByteArray) {
        AasdkNative.nativeSendVehicleSensor(sensorType, data)
    }

    fun sendSpeed(speedMmPerS: Int) = AasdkNative.nativeSendSpeed(speedMmPerS)
    fun sendGear(gear: Int) = AasdkNative.nativeSendGear(gear)
    fun sendParkingBrake(engaged: Boolean) = AasdkNative.nativeSendParkingBrake(engaged)
    fun sendNightMode(night: Boolean) = AasdkNative.nativeSendNightMode(night)
    fun sendDrivingStatus(moving: Boolean) = AasdkNative.nativeSendDrivingStatus(moving)
    fun sendFuel(levelPct: Int, rangeM: Int, lowFuel: Boolean) = AasdkNative.nativeSendFuel(levelPct, rangeM, lowFuel)
    /**
     * Send VEM sensor batch. Override args with `< 0` (or `Float.NaN`) mean
     * "derive on the C++ side from the legacy formula / hardcoded value".
     */
    fun sendEnergyModel(
        batteryLevelWh: Int, batteryCapacityWh: Int, rangeM: Int, chargeRateW: Int,
        drivingWhPerKm: Float = -1f, auxWhPerKm: Float = -1f, aeroCoef: Float = -1f,
        reservePct: Float = -1f, maxChargeW: Int = -1, maxDischargeW: Int = -1,
    ) = AasdkNative.nativeSendEnergyModel(
        batteryLevelWh, batteryCapacityWh, rangeM, chargeRateW,
        drivingWhPerKm, auxWhPerKm, aeroCoef, reservePct, maxChargeW, maxDischargeW,
    )
    fun sendAccelerometer(xE3: Int, yE3: Int, zE3: Int) = AasdkNative.nativeSendAccelerometer(xE3, yE3, zE3)
    fun sendGyroscope(rxE3: Int, ryE3: Int, rzE3: Int) = AasdkNative.nativeSendGyroscope(rxE3, ryE3, rzE3)
    fun sendCompass(bearingE6: Int, pitchE6: Int, rollE6: Int) = AasdkNative.nativeSendCompass(bearingE6, pitchE6, rollE6)
    fun sendRpm(rpmE3: Int) = AasdkNative.nativeSendRpm(rpmE3)

    fun sendMicAudio(data: ByteArray) {
        AasdkNative.nativeSendMicAudio(data)
    }

    fun requestKeyframe() {
        AasdkNative.nativeRequestKeyframe()
    }

    /**
     * Invoked when the phone subscribes to a sensor type, so the owner
     * (SessionManager) can push current vehicle state immediately instead of
     * waiting for a VHAL change event that a parked car never produces.
     * See issue #61.
     */
    @Volatile var onSensorSubscribedListener: ((Int) -> Unit)? = null

    // -- AasdkSessionCallback (called from native thread → dispatch to flows) --

    override fun onSessionStarted() {
        OalLog.i(TAG, "AA session started (native)")
        // The outcome line. Everything before this is intent; this says the
        // session actually came up, which is what a log reader needs to see.
        OalLog.i(TAG, "CONNECT SUMMARY: result=session started — " +
                "video should follow within a few seconds")
        com.openautolink.app.wake.PreWakeMonitor.reportSessionReady("native-session-started")
        // Cancel any pending auto-reconnect timer: this session is now healthy,
        // so a leftover retry from an earlier teardown must NOT fire and restart
        // the transport underneath us (the deep-sleep reconnect-storm root cause).
        cancelPendingReconnect("session started")
        consecutiveReconnectFailures = 0
        _reconnectAttempt.value = 0
        lastFailureWasProtocolError = false
        // (A) Reset frame-flow counters so each session's video trace is clean.
        vfWindowStartMs = 0L
        vfFrameCount = 0
        vfKeyframeCount = 0
        vfByteCount = 0L
        vfLastFrameMs = 0L
        scope.launch {
            _connectionState.value = ConnectionState.CONNECTED
            _controlMessages.emit(ControlMessage.PhoneConnected(phoneName = "", phoneType = "wireless"))
        }
    }

    override fun onSessionStopped(reason: String) {
        OalLog.i(TAG, "AA session stopped: $reason")
        // Clean up dead transport
        transportPipe?.close()
        transportPipe = null

        // Phone-initiated user exit (Exit button in AA app launcher) — treat
        // as an explicit stop so the auto-reconnect path below is skipped.
        // MainActivity will background the app in response to the
        // PhoneDisconnected(reason) message; user re-launches our icon to come
        // back, which starts a fresh session.
        if (reason == "byebye_user_selection") {
            explicitStop = true
        }

        // Auto-reconnect if this wasn't an explicit stop (e.g., car sleep/wake,
        // phone disconnect). Restart the transport connector after a delay so it
        // retries connecting once WiFi comes back.
        //
        // Single-flight: cancel any already-pending retry before arming a new
        // one, and track this coroutine in [reconnectJob] so a later
        // onSessionStarted() can cancel it. Without this, several rapid
        // teardowns during a deep-sleep wake each armed an independent timer;
        // leftover timers then restarted the transport on top of a healthy
        // session → reconnect storm (0.1.363 log 2026-07-19).
        if (!explicitStop) {
            consecutiveReconnectFailures++
            _reconnectAttempt.value = consecutiveReconnectFailures

            // Exponential backoff: 3s base, longer if protocol error (phone
            // needs time to tear down old SSL session). Cap at 30s.
            val baseDelayMs = if (lastFailureWasProtocolError) 5000L else 3000L
            val backoffMs = (baseDelayMs * (1L shl (consecutiveReconnectFailures - 1).coerceAtMost(3)))
                .coerceAtMost(30_000L)
            OalLog.i(TAG, "Session died unexpectedly — retry #$consecutiveReconnectFailures in ${backoffMs}ms" +
                if (lastFailureWasProtocolError) " (protocol error, extended backoff)" else "")
            lastFailureWasProtocolError = false

            // Re-advertise over Bluetooth after repeated failures in WPP mode.
            //
            // Reconnecting the TCP transport is not enough here. The car's socket
            // to the companion comes back every time, but Android Auto is gone —
            // it disconnected when the network dropped and nothing can summon it.
            // The AA-launch broadcast targets WirelessStartupReceiver, which 17.4
            // disables, so it is a no-op. The ONLY working way to tell AA where to
            // connect is the Bluetooth WPP handshake, and that only runs when the
            // PHONE dials back on the AA UUID.
            //
            // Bouncing the SDP record makes the phone re-dial, which re-runs the
            // handshake with a freshly-resolved endpoint. Without this, any
            // network blip is unrecoverable: observed as 217 keyframe re-requests
            // and nine retries over eight minutes, TCP connecting happily each
            // time with no peer behind it.
            if (transportMode == "wpp" && consecutiveReconnectFailures >= 2) {
                OalLog.i(TAG, "WPP: re-advertising over Bluetooth so the phone re-dials " +
                        "and Android Auto is told where to connect")
                com.openautolink.app.transport.bluetooth.AaWirelessBtControl.readvertise()
            }

            cancelPendingReconnect("superseded by newer teardown")
            val job = scope.launch {
                _connectionState.value = ConnectionState.DISCONNECTED
                _controlMessages.emit(ControlMessage.PhoneDisconnected(reason = reason))

                kotlinx.coroutines.delay(backoffMs)
                // Guard: if a session already reconnected while we waited (the
                // CONNECTED guard) or an explicit stop landed, do NOT restart —
                // restarting would tear down a working session.
                if (explicitStop) {
                    OalLog.i(TAG, "Pending reconnect aborted (explicit stop)")
                } else if (_connectionState.value == ConnectionState.CONNECTED) {
                    OalLog.i(TAG, "Pending reconnect skipped (already CONNECTED)")
                } else {
                    OalLog.i(TAG, "Restarting transport connector")
                    when (transportMode) {
                        "usb" -> startUsb()
                        else -> {
                            // Keep the companion's address on a retry.
                            //
                            // Without it startTcp() falls through to the gateway
                            // heuristic, which on a head unit sitting on home
                            // WiFi means repeatedly dialling the house router:
                            //     Connecting to 192.168.0.1:5277 (gateway)  x10
                            // The companion is never at the gateway in WPP mode.
                            val known = com.openautolink.app.transport.bluetooth
                                .AaWirelessBtControl.lastKnownPhoneIp
                            startTcp(manualIp = known)
                        }
                    }
                }
            }
            synchronized(reconnectLock) { reconnectJob = job }
        } else {
            scope.launch {
                _connectionState.value = ConnectionState.DISCONNECTED
                _controlMessages.emit(ControlMessage.PhoneDisconnected(reason = reason))
            }
        }
    }

    override fun onVideoFrame(data: ByteArray, timestampUs: Long, width: Int, height: Int, flags: Int) {
        recordVideoFrameFlow(data.size, flags)
        val frame = VideoFrame(
            width = width,
            height = height,
            ptsMs = timestampUs / 1000,
            flags = flags,
            data = data
        )
        _videoFrames.tryEmit(frame)
    }

    /**
     * (A) Frame-flow diagnostic. Accumulates inbound frames and emits a
     * DiagnosticLog summary at most every [vfLogIntervalMs]. The gap-since-last
     * frame is the key signal: during a black-video gap this shows whether the
     * phone's encoder is still sending bytes (P-frames, no IDR) or is silent.
     * Called on the native io thread; cheap and lock-free (single producer).
     */
    private fun recordVideoFrameFlow(size: Int, flags: Int) {
        val now = android.os.SystemClock.elapsedRealtime()
        val isKeyframe = (flags and 0x0001) != 0
        val gap = if (vfLastFrameMs == 0L) 0L else now - vfLastFrameMs
        vfLastFrameMs = now
        if (vfWindowStartMs == 0L) vfWindowStartMs = now
        vfFrameCount++
        if (isKeyframe) vfKeyframeCount++
        vfByteCount += size
        val elapsed = now - vfWindowStartMs
        // Flush a summary every interval, or immediately after a >1s starvation
        // gap between frames (so a stall is captured even at low frame rates).
        if (elapsed >= vfLogIntervalMs || gap > 1000L) {
            val kbps = if (elapsed > 0) (vfByteCount * 8 / elapsed) else 0
            com.openautolink.app.diagnostics.DiagnosticLog.i(
                "vflow",
                "frames=$vfFrameCount idr=$vfKeyframeCount bytes=$vfByteCount " +
                    "~${kbps}kbps window=${elapsed}ms maxGap=${gap}ms"
            )
            vfWindowStartMs = now
            vfFrameCount = 0
            vfKeyframeCount = 0
            vfByteCount = 0L
        }
    }

    override fun onVideoCodecConfigured(codecType: Int) {
        OalLog.i(TAG, "Phone negotiated codec type: $codecType")
        _negotiatedCodecType.value = codecType
    }

    override fun onAudioFrame(data: ByteArray, purpose: Int, sampleRate: Int, channels: Int) {
        val audioPurpose = when (purpose) {
            0 -> AudioPurpose.MEDIA
            1 -> AudioPurpose.NAVIGATION
            2 -> AudioPurpose.ALERT
            3 -> AudioPurpose.ASSISTANT
            4 -> AudioPurpose.PHONE_CALL
            else -> AudioPurpose.MEDIA
        }
        val frame = AudioFrame(
            direction = AudioFrame.DIRECTION_PLAYBACK,
            data = data,
            purpose = audioPurpose,
            sampleRate = sampleRate,
            channels = channels
        )
        _audioFrames.tryEmit(frame)
    }

    override fun onMicRequest(open: Boolean) {
        scope.launch {
            if (open) _controlMessages.emit(ControlMessage.MicStart(sampleRate = 16000))
            else _controlMessages.emit(ControlMessage.MicStop)
        }
    }

    override fun onNavigationStatus(status: Int) {
        scope.launch {
            com.openautolink.app.diagnostics.DiagnosticLog.i("nav", "Status: $status (${if (status == 1) "ACTIVE" else "INACTIVE"})")
            if (status != 1) { // not ACTIVE
                _controlMessages.emit(ControlMessage.NavStateClear)
            }
        }
    }

    override fun onNavigationTurn(maneuver: String, road: String, iconPng: ByteArray?) {
        scope.launch {
            val iconBase64 = iconPng?.let { android.util.Base64.encodeToString(it, android.util.Base64.NO_WRAP) }
            com.openautolink.app.diagnostics.DiagnosticLog.d("nav", "Turn: $maneuver road=$road icon=${iconPng?.size ?: 0}B")
            _controlMessages.emit(ControlMessage.NavState(
                maneuver = maneuver,
                distanceMeters = null,
                road = road,
                etaSeconds = null,
                navImageBase64 = iconBase64
            ))
        }
    }

    override fun onNavigationDistance(distanceMeters: Int, etaSeconds: Int,
                                      displayDistance: String?, displayUnit: String?) {
        scope.launch {
            com.openautolink.app.diagnostics.DiagnosticLog.d("nav", "Distance: ${distanceMeters}m eta=${etaSeconds}s display=$displayDistance $displayUnit")
            _controlMessages.emit(ControlMessage.NavState(
                maneuver = null,
                distanceMeters = distanceMeters,
                road = null,
                etaSeconds = etaSeconds,
                displayDistance = displayDistance,
                displayDistanceUnit = displayUnit
            ))
        }
    }

    override fun onNavigationFullState(
        maneuver: String?, road: String?, iconPng: ByteArray?,
        distanceMeters: Int, etaSeconds: Int,
        displayDistance: String?, displayUnit: String?,
        lanes: String?, cue: String?, roundaboutExitNumber: Int,
        currentRoad: String?, destination: String?, etaFormatted: String?,
        timeToArrivalSeconds: Long, destDistanceMeters: Int,
        destDistDisplay: String?, destDistUnit: String?
    ) {
        scope.launch {
            val iconBase64 = iconPng?.let { android.util.Base64.encodeToString(it, android.util.Base64.NO_WRAP) }
            val parsedLanes = parseLanesString(lanes)
            com.openautolink.app.diagnostics.DiagnosticLog.d("nav", "FullState: $maneuver road=$road cue=$cue lanes=${parsedLanes?.size ?: 0} dest=$destination dist=${distanceMeters}m")
            _controlMessages.emit(ControlMessage.NavState(
                maneuver = maneuver,
                distanceMeters = if (distanceMeters > 0) distanceMeters else null,
                road = road,
                etaSeconds = if (etaSeconds > 0) etaSeconds else null,
                navImageBase64 = iconBase64,
                lanes = parsedLanes,
                cue = cue,
                roundaboutExitNumber = if (roundaboutExitNumber >= 0) roundaboutExitNumber else null,
                displayDistance = displayDistance,
                displayDistanceUnit = displayUnit,
                currentRoad = currentRoad,
                destination = destination,
                etaFormatted = etaFormatted,
                timeToArrivalSeconds = if (timeToArrivalSeconds > 0) timeToArrivalSeconds else null,
                destDistanceMeters = if (destDistanceMeters > 0) destDistanceMeters else null,
                destDistanceDisplay = destDistDisplay,
                destDistanceUnit = destDistUnit
            ))
        }
    }

    /**
     * Parse serialized lane string from C++ JNI.
     * Format: "shape:highlighted,shape:highlighted|shape:highlighted,..."
     * Pipes separate lanes, commas separate directions within a lane.
     */
    private fun parseLanesString(lanes: String?): List<ControlMessage.NavLane>? {
        if (lanes.isNullOrEmpty()) return null
        return lanes.split('|').map { laneStr ->
            val directions = laneStr.split(',').map { dirStr ->
                val parts = dirStr.split(':')
                ControlMessage.NavLaneDirection(
                    shape = parts.getOrElse(0) { "unknown" },
                    highlighted = parts.getOrElse(1) { "0" } == "1"
                )
            }
            ControlMessage.NavLane(directions)
        }
    }

    override fun onMediaMetadata(title: String, artist: String, album: String, albumArt: ByteArray?) {
        scope.launch {
            val artBase64 = albumArt?.let { android.util.Base64.encodeToString(it, android.util.Base64.NO_WRAP) }
            com.openautolink.app.diagnostics.DiagnosticLog.i("media", "Metadata: title=$title artist=$artist art=${albumArt?.size ?: 0}B")
            _controlMessages.emit(ControlMessage.MediaMetadata(
                title = title, artist = artist, album = album,
                albumArtBase64 = artBase64, playing = null, positionMs = null, durationMs = null
            ))
        }
    }

    override fun onMediaPlayback(state: Int, positionMs: Long) {
        scope.launch {
            // AA proto: STOPPED=1, PLAYING=2, PAUSED=3
            val playing = state == 2
            com.openautolink.app.diagnostics.DiagnosticLog.d("media", "Playback: state=$state ${if (playing) "PLAYING" else if (state == 3) "PAUSED" else "STOPPED"} pos=${positionMs}ms")
            _controlMessages.emit(ControlMessage.MediaPlaybackState(
                playing = playing, positionMs = positionMs
            ))
        }
    }

    override fun onPhoneStatus(signalStrength: Int, callState: Int) {
        scope.launch {
            _controlMessages.emit(ControlMessage.PhoneStatus(signalStrength = signalStrength, calls = emptyList()))
        }
    }

    override fun onPhoneBattery(level: Int, charging: Boolean) {
        scope.launch {
            _controlMessages.emit(ControlMessage.PhoneBattery(level = level, timeRemainingSeconds = 0, critical = level < 10))
        }
    }

    override fun onVoiceSession(active: Boolean) {
        scope.launch {
            _controlMessages.emit(ControlMessage.VoiceSession(started = active))
        }
    }

    override fun onAudioFocusRequest(focusType: Int) {
        // Audio focus is handled by the native layer — always grants
    }

    /** Coalesce native onError log spam: at most one log per second per
     *  message text, deduplicated across instance churn (the state lives in
     *  the companion object, see [Companion.onErrorLock]). */
    override fun onError(message: String) {
        val now = android.os.SystemClock.elapsedRealtime()
        var shouldEmit = false
        synchronized(onErrorLock) {
            if (message == lastOnErrorMsg && (now - lastOnErrorLogMs) < 1000) {
                onErrorSuppressedCount++
                return
            }
            val suppressed = onErrorSuppressedCount
            lastOnErrorMsg = message
            lastOnErrorLogMs = now
            onErrorSuppressedCount = 0
            if (suppressed > 0) OalLog.e(TAG, "Native error (×${suppressed + 1}): $message")
            else OalLog.e(TAG, "Native error: $message")
            shouldEmit = true
        }
        // Flag protocol/handshake errors so reconnect uses extended backoff.
        // AASDK Error 30 = SSL handshake rejected (phone still holds old session).
        //
        // But not when WE caused it. Replacing a live session in
        // handleConnection() tears down the old transport, and the native layer
        // reports that as Error 30 milliseconds later — indistinguishable from a
        // genuine rejection. Treating our own teardown as a protocol failure
        // escalated the backoff to 5s, 10s, 20s, 30s during the 08:21-08:24 loop,
        // punishing a session that had nothing wrong with it.
        if ("AASDK Error: 30" in message && !selfInflictedTeardown) {
            lastFailureWasProtocolError = true
        }
        // Only emit at most once per second (matches the log coalescing). Auto-reconnect
        // is wired to onSessionStopped, not onError, so suppressing extra emits doesn't
        // hurt recovery — it just keeps the UI from flickering "Error" 100 times.
        if (shouldEmit) {
            scope.launch {
                _controlMessages.emit(ControlMessage.Error(code = -1, message = message))
            }
        }
    }

    override fun onNativeLog(level: Int, tag: String, message: String) {
        when (level) {
            0 -> com.openautolink.app.diagnostics.DiagnosticLog.d(tag, message)
            2 -> com.openautolink.app.diagnostics.DiagnosticLog.w(tag, message)
            3 -> com.openautolink.app.diagnostics.DiagnosticLog.e(tag, message)
            else -> com.openautolink.app.diagnostics.DiagnosticLog.i(tag, message)
        }
    }

    override fun onSensorSubscribed(sensorType: Int) {
        com.openautolink.app.diagnostics.DiagnosticLog.i(
            "vhal", "phone subscribed sensor type=$sensorType — pushing current state"
        )
        try {
            onSensorSubscribedListener?.invoke(sensorType)
        } catch (t: Throwable) {
            OalLog.w(TAG, "onSensorSubscribed($sensorType): ${t.message}")
        }
    }
}
