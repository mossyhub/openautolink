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

        /** Minimum dwell a native session must stay up to count as a genuine
         *  connection (vs. an Error-30 flap). A session that dies sooner does
         *  NOT reset consecutiveReconnectFailures, so backoff keeps escalating
         *  (issue #30). 20s comfortably exceeds the few-seconds flap window seen
         *  in the storm logs while staying well under any normal drive segment. */
        private const val STABLE_DWELL_MS = 20_000L

        /** Backoff floor for protocol/handshake errors (AASDK Error 30 — phone
         *  still holds the old SSL session). Higher than the generic 3s base
         *  because a 5s re-dial frequently beat the phone's own teardown and
         *  re-provoked the rejection, sustaining the storm (issue #30). */
        private const val PROTOCOL_ERROR_BASE_MS = 8_000L

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

    /**
     * Wall-clock ([SystemClock.elapsedRealtime]) of the most recent inbound
     * video frame. The stall watchdog in SessionManager reads this to detect a
     * wedged AA video channel — frames stop arriving while the TCP socket stays
     * alive and other channels (nav) keep flowing, so the native layer never
     * fires onSessionStopped and nothing recovers (issue #34). 0 = no frame yet.
     */
    @Volatile
    var lastVideoFrameMs: Long = 0L
        private set

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
    private var _tcpConnector: TcpConnector? = null

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

    /** [SystemClock.elapsedRealtime] when the current native session last
     *  started, or 0 when no session is up. Used to measure session dwell so a
     *  *flapping* session (dies seconds after start) does NOT reset the
     *  reconnect-failure counter — only a session that stayed up past
     *  [STABLE_DWELL_MS] is treated as a genuine recovery (issue #30). Without
     *  this gate every flap re-entered backoff as "retry #1" at the 5s floor and
     *  the exponential ramp never engaged, re-provoking Error 30 in a storm. */
    @Volatile
    private var sessionStartedAtMs: Long = 0L

    private var transportPipe: AasdkTransportPipe? = null

    // -- Lifecycle --

    fun start() {
        explicitStop = false
        consecutiveReconnectFailures = 0
        _reconnectAttempt.value = 0
        lastFailureWasProtocolError = false
        _connectionState.value = ConnectionState.DISCONNECTED

        when (transportMode) {
            "usb" -> startUsb()
            else -> startTcp()
        }
    }

    private fun startTcp() {
        OalLog.i(TAG, "Starting aasdk session (TCP/hotspot transport)")
        _tcpConnector?.stop()
        _tcpConnector = TcpConnector(
            context,
            scope,
            onSocketReady = { tcpSocket ->
                scope.launch(Dispatchers.IO) {
                    OalLog.i(TAG, "TCP socket ready — starting aasdk native session")
                    handleConnection(tcpSocket)
                }
            },
            onConnectFailure = {
                // Drive the same reconnectAttempt counter the session-stopped
                // path uses, so picker escalation works even when we never
                // got to handshake (companion not listening, phone off-net).
                consecutiveReconnectFailures++
                _reconnectAttempt.value = consecutiveReconnectFailures
            },
        )
        _tcpConnector?.manualIp = manualIpAddress
        _tcpConnector?.start()
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

        val input = socket.getInputStream()
        val output = socket.getOutputStream()

        OalLog.i(TAG, "Starting native aasdk session: ${sdrConfig.videoWidth}x${sdrConfig.videoHeight}")

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

    fun stop() {
        explicitStop = true
        OalLog.i(TAG, "Stopping aasdk session")
        _tcpConnector?.stop()
        _tcpConnector = null
        _usbConnectionManager?.stop()
        _usbConnectionManager = null
        AasdkNative.nativeStopSession()
        transportPipe?.close()
        transportPipe = null
        _connectionState.value = ConnectionState.DISCONNECTED
    }

    /**
     * Force a clean reconnect without setting [explicitStop]. Used for sleep/wake
     * recovery and for the JNI abort path. The native session is torn down (which
     * fires onSessionStopped → auto-reconnect path) and the transport connector
     * is restarted.
     */
    fun forceReconnect(reason: String) {
        OalLog.w(TAG, "Force reconnect: $reason")
        // Treat the upcoming nativeStopSession() as an explicit stop so the
        // onSessionStopped handler doesn't schedule its own auto-reconnect 3s
        // later — we're doing the restart ourselves immediately. Without this
        // both reconnects race and one fails with "Native session start failed".
        explicitStop = true
        _tcpConnector?.stop()
        _tcpConnector = null
        _usbConnectionManager?.stop()
        _usbConnectionManager = null
        AasdkNative.nativeStopSession()
        transportPipe?.close()
        transportPipe = null
        _connectionState.value = ConnectionState.DISCONNECTED
        // Now clear the explicitStop flag so the freshly-started session can
        // auto-reconnect normally if its connection later dies.
        explicitStop = false
        // Restart transport.
        when (transportMode) {
            "usb" -> startUsb()
            else -> startTcp()
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
     * Force a fresh IDR via a real video-focus transition (issue #35). Use when a
     * plain [requestKeyframe] may be ignored because the phone already believes we
     * hold projected focus (e.g. after a phone-call / GM-UI takeover).
     */
    fun requestKeyframeForceFocus() {
        AasdkNative.nativeRequestKeyframeForceFocus()
    }

    // -- AasdkSessionCallback (called from native thread → dispatch to flows) --

    override fun onSessionStarted() {
        OalLog.i(TAG, "AA session started (native)")
        // NOTE: do NOT reset consecutiveReconnectFailures here. A session that
        // dies seconds after starting (Error-30 flap) would otherwise re-enter
        // backoff as "retry #1" forever and never escalate (issue #30). The
        // counter is reset in onSessionStopped() only if THIS session stayed up
        // past STABLE_DWELL_MS, i.e. it was a genuine recovery, not a flap.
        sessionStartedAtMs = android.os.SystemClock.elapsedRealtime()
        lastFailureWasProtocolError = false
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

        scope.launch {
            _connectionState.value = ConnectionState.DISCONNECTED
            _controlMessages.emit(ControlMessage.PhoneDisconnected(reason = reason))

            // Auto-reconnect if this wasn't an explicit stop (e.g., car sleep/wake,
            // phone disconnect). Restart the transport connector after a delay so it
            // retries connecting once WiFi comes back.
            if (!explicitStop) {
                // Dwell gate (issue #30): if the session that just died had been up
                // longer than STABLE_DWELL_MS it was a genuine connection, so this
                // is a FRESH failure series — reset the counter. If it died fast
                // (flap), keep accumulating so backoff escalates toward the 30s cap
                // instead of pinning at "retry #1" and re-provoking Error 30.
                val dwellMs = if (sessionStartedAtMs > 0L)
                    android.os.SystemClock.elapsedRealtime() - sessionStartedAtMs
                else
                    Long.MAX_VALUE
                if (dwellMs >= STABLE_DWELL_MS) {
                    consecutiveReconnectFailures = 0
                }
                sessionStartedAtMs = 0L

                consecutiveReconnectFailures++
                _reconnectAttempt.value = consecutiveReconnectFailures

                // Exponential backoff: 3s base, longer if protocol error (phone
                // needs time to tear down old SSL session). Cap at 30s. Error 30
                // (stale SSL session) gets a higher floor — a 5s re-dial often
                // beat the phone's own teardown and re-provoked the rejection.
                val baseDelayMs = if (lastFailureWasProtocolError) PROTOCOL_ERROR_BASE_MS else 3000L
                val backoffMs = (baseDelayMs * (1L shl (consecutiveReconnectFailures - 1).coerceAtMost(3)))
                    .coerceAtMost(30_000L)
                OalLog.i(TAG, "Session died unexpectedly — retry #$consecutiveReconnectFailures in ${backoffMs}ms" +
                    if (lastFailureWasProtocolError) " (protocol error, extended backoff)" else "")
                lastFailureWasProtocolError = false

                kotlinx.coroutines.delay(backoffMs)
                if (!explicitStop) {
                    OalLog.i(TAG, "Restarting transport connector")
                    when (transportMode) {
                        "usb" -> startUsb()
                        else -> startTcp()
                    }
                }
            }
        }
    }

    override fun onVideoFrame(data: ByteArray, timestampUs: Long, width: Int, height: Int, flags: Int) {
        lastVideoFrameMs = android.os.SystemClock.elapsedRealtime()
        val frame = VideoFrame(
            width = width,
            height = height,
            ptsMs = timestampUs / 1000,
            flags = flags,
            data = data
        )
        _videoFrames.tryEmit(frame)
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
        if ("AASDK Error: 30" in message) {
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
}
