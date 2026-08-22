package com.openautolink.app.session

import android.content.res.Configuration
import android.content.Context
import android.media.AudioManager
import android.os.SystemClock
import com.openautolink.app.diagnostics.OalLog
import com.openautolink.app.audio.AudioPlayer
import com.openautolink.app.audio.AudioPlayerImpl
import com.openautolink.app.audio.AudioStats
import com.openautolink.app.audio.CallState
import com.openautolink.app.audio.MicCaptureManager
import com.openautolink.app.cluster.ClusterNavigationState
import com.openautolink.app.data.AppPreferences
import com.openautolink.app.data.EvLearnedRateEstimator
import com.openautolink.app.data.EvProfilesRepository
import com.openautolink.app.diagnostics.DiagnosticLevel
import com.openautolink.app.diagnostics.DiagnosticLog
import com.openautolink.app.diagnostics.RemoteDiagnostics
import com.openautolink.app.diagnostics.RemoteDiagnosticsImpl
import com.openautolink.app.diagnostics.TelemetryCollector
import com.openautolink.app.input.GnssForwarder
import com.openautolink.app.input.GnssForwarderImpl
import com.openautolink.app.input.IgnitionMonitor
import com.openautolink.app.input.ImuForwarder
import com.openautolink.app.input.VehicleDataForwarder
import com.openautolink.app.input.VehicleDataForwarderImpl
import com.openautolink.app.media.OalMediaBrowserService
import com.openautolink.app.media.OalMediaSessionManager
import com.openautolink.app.navigation.ManeuverState
import com.openautolink.app.navigation.NavigationDisplay
import com.openautolink.app.navigation.NavigationDisplayImpl
import com.openautolink.app.navigation.VehicleEnergyForecast
import com.openautolink.app.navigation.VehicleEnergyForecastPolicy
import com.openautolink.app.transport.AudioPurpose
import com.openautolink.app.transport.ConnectionState
import com.openautolink.app.transport.ControlMessage
import com.openautolink.app.transport.aasdk.AasdkSession
import com.openautolink.app.transport.aasdk.AasdkSdrConfig
import com.openautolink.app.transport.bluetooth.AaWirelessBtControl
import com.openautolink.app.transport.usb.UsbConnectionManager
import com.openautolink.app.video.DecoderState
import com.openautolink.app.video.AutoDpiPolicy
import com.openautolink.app.video.AutoVideoTouchPolicy
import com.openautolink.app.video.MediaCodecDecoder
import com.openautolink.app.video.VideoDecoder
import com.openautolink.app.video.VideoStats
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch

/**
 * Session orchestrator -- connects component islands, manages lifecycle.
 * aasdk JNI mode -- native aasdk C++ handles AA protocol over TCP transport.
 */
class SessionManager(
    externalScope: CoroutineScope,
    private val context: Context? = null,
    private val audioManager: AudioManager? = null
) {

    companion object {
        private const val TAG = "SessionManager"

        /**
         * How long to let a ByeBye flush before tearing the session down anyway.
         *
         * A single encrypted control frame on an already-open socket needs only
         * milliseconds, so this is generous. It must stay short: on ignition-off
         * the head unit may be seconds away from losing power, and a graceful
         * goodbye is never worth delaying shutdown for.
         */
        private const val BYEBYE_TIMEOUT_MS = 400

        // aasdk SensorType ordinals (app/src/main/proto/oal/sensors.proto).
        // Used to answer the phone's SensorStartRequest with current state.
        private const val SENSOR_TYPE_SPEED = 3
        private const val SENSOR_TYPE_PARKING_BRAKE = 7
        private const val SENSOR_TYPE_GEAR = 8
        private const val SENSOR_TYPE_NIGHT_MODE = 10
        private const val SENSOR_TYPE_DRIVING_STATUS = 13
        private const val SENSOR_TYPE_VEHICLE_ENERGY_MODEL = 23

        // Activity-sourced UI-mode snapshot that can be published before
        // SessionManager exists (ViewModel lazy creation path).
        @Volatile
        private var bootUiNightModeSnapshot: Boolean? = null

        @Volatile
        private var instance: SessionManager? = null

        fun getInstance(scope: CoroutineScope, context: Context, audioManager: AudioManager): SessionManager {
            return instance ?: synchronized(this) {
                instance ?: SessionManager(scope, context, audioManager).also { instance = it }
            }
        }

        fun instanceOrNull(): SessionManager? = instance

        fun noteUiNightMode(night: Boolean) {
            bootUiNightModeSnapshot = night
            instance?.lastKnownUiNightMode = night
        }
    }

    /**
     * Session work runs OFF the main thread.
     *
     * This scope drives the aasdk session, and much of that work enters JNI —
     * nativeStopSession, nativeShutdownGracefully, nativeRequestKeyframe — which
     * blocks until the native side releases its lock. On Dispatchers.Main that
     * parks the UI thread and Android kills the app. The archive holds 18 ANRs
     * back to 2026-06-26, and their last main-thread lines name this scope's
     * work: "JniTransport stopping" (5), "Reconnecting AA session" (3),
     * "Video stall — forcing reconnect" (4).
     *
     * Nothing here needs the main thread. The state it publishes is
     * MutableStateFlow, which is thread-safe; WindowManager metrics are readable
     * from any thread; and the GPS listener already passes an explicit main
     * Looper. Compose collects the flows on its own dispatcher regardless.
     */
    private val scope = CoroutineScope(SupervisorJob() + kotlinx.coroutines.Dispatchers.Default)

    // aasdk JNI session -- native C++ handles AA protocol
    private var aasdkSession: AasdkSession? = null
    private var retiringAasdkSession: AasdkSession? = null
    private val wirelessAdmissionLock = Any()
    private var wirelessSessionAdmission:
        com.openautolink.app.transport.bluetooth.WppSessionAdmission.Token? = null

    private fun installWirelessSessionAdmission(
        transportMode: String,
    ): com.openautolink.app.transport.bluetooth.WppSessionAdmission.Token =
        synchronized(wirelessAdmissionLock) {
            wirelessSessionAdmission?.let {
                AaWirelessBtControl.clearSessionOwner(it)
            }
            AaWirelessBtControl.installSessionOwner(transportMode).also { token ->
                wirelessSessionAdmission = token
            }
        }

    private fun clearWirelessSessionAdmission() {
        synchronized(wirelessAdmissionLock) {
            val token = wirelessSessionAdmission ?: return
            wirelessSessionAdmission = null
            com.openautolink.app.transport.bluetooth.AaWirelessBtControl.clearSessionOwner(token)
        }
    }

    /** Rate-limit for the "no session, dropping input" warning. */
    @Volatile
    private var lastNoSessionWarnAt = 0L

    private val NO_SESSION_WARN_INTERVAL_MS = 5_000L

    // Dedicated single-threaded dispatcher for video decode
    private val videoDispatcher = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
        Thread(r, "VideoDecodeInput").apply { isDaemon = true }
    }.asCoroutineDispatcher()

    // Dedicated dispatcher for audio
    private val audioDispatcher = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
        Thread(r, "AudioFrameInput").apply {
            isDaemon = true
            priority = Thread.MAX_PRIORITY
        }
    }.asCoroutineDispatcher()

    private val sessionStateLock = Any()

    private fun adoptSessionOwnership(session: AasdkSession) {
        synchronized(sessionStateLock) { aasdkSession = session }
    }

    /** Caller must hold [sessionStateLock]. */
    private fun revokeSessionOwnershipLocked(): AasdkSession? {
        val current = aasdkSession
        aasdkSession = null
        if (current != null) retiringAasdkSession = current
        return current
    }

    private fun completeSessionRetirement(session: AasdkSession?) {
        synchronized(sessionStateLock) {
            if (retiringAasdkSession === session) retiringAasdkSession = null
        }
    }

    private fun stopRetiringSession(session: AasdkSession?) {
        try {
            session?.stop()
        } finally {
            completeSessionRetirement(session)
        }
    }

    private val _sessionState = MutableStateFlow(SessionState.IDLE)
    val sessionState: StateFlow<SessionState> = _sessionState.asStateFlow()

    private val _statusMessage = MutableStateFlow("Ready")
    val statusMessage: StateFlow<String> = _statusMessage.asStateFlow()

    /**
     * Mirror of [AasdkSession.reconnectAttempt] hoisted to SessionManager so
     * observers (UI controller, status banner) don't have to track which
     * AasdkSession instance is current. Updated by the per-session collector
     * in [startSession] and reset to 0 whenever a new session is wired up.
     * 0 = no failures yet (or a session is healthy); N > 0 = currently in
     * the Nth consecutive reconnect attempt.
     */
    private val _reconnectAttempt = MutableStateFlow(0)
    val reconnectAttempt: StateFlow<Int> = _reconnectAttempt.asStateFlow()

    // Video decoder
    /**
     * Invoked right after a new video decoder is created.
     *
     * Lets the UI attach a surface that already exists. Attaching only on a
     * session-state change misses the case where the surface was recreated
     * before the session started, which leaves a healthy stream decoding to
     * nothing.
     */
    @Volatile
    var onDecoderCreated: (() -> Unit)? = null

    /**
     * The surface the UI last published, kept across decoder recreations.
     *
     * The decoder lives in this singleton; the surface belongs to an
     * activity-scoped ViewModel. Their lifecycles are independent, so a callback
     * from one into the other loses the surface whenever the ordering flips.
     */
    @Volatile
    private var lastKnownSurface: Triple<android.view.Surface, Int, Int>? = null

    /** Ownership token for callbacks emitted by replaceable decoder instances. */
    private val videoDecoderGeneration = java.util.concurrent.atomic.AtomicLong(0)

    /**
     * Decoder settings from the last full session setup, for rebuilding one.
     *
     * Seeded from AppPreferences' own defaults, not MediaCodecDecoder's
     * constructor defaults — those disagree on scaling mode (the decoder
     * defaults to letterbox, the app to crop), and the app's value is the one
     * the user is actually running.
     */
    @Volatile
    private var lastCodecPreference: String = AppPreferences.DEFAULT_VIDEO_CODEC

    @Volatile
    private var lastScalingMode: String = AppPreferences.DEFAULT_VIDEO_SCALING_MODE

    @Volatile
    private var lastVolumeOffsetMedia: Int = 0

    @Volatile
    private var lastVolumeOffsetNavigation: Int = 0

    @Volatile
    private var lastVolumeOffsetAssistant: Int = 0

    /**
     * Reject dead/stale Surface objects before they become the singleton's cached
     * output target. A stale target must not provoke a destructive session restart.
     */
    fun publishSurface(surface: android.view.Surface?, width: Int, height: Int) {
        val validSurface = surface?.takeIf { it.isValid && width > 0 && height > 0 }
        lastKnownSurface = validSurface?.let { Triple(it, width, height) }
        if (surface != null && validSurface == null) {
            OalLog.w(TAG, "Ignoring invalid video surface ${width}x${height}")
            return
        }
        if (validSurface != null) {
            com.openautolink.app.wake.PreWakeMonitor.reportSurfaceReady(width, height)
        }
        // attach() configures or swaps the MediaCodec output surface, which
        // blocks on the codec — and this is called from SurfaceView callbacks on
        // the main thread. One archived ANR's last main-thread line is exactly
        // "Surface attached", immediately after a forced reconnect began tearing
        // the session down on another thread.
        if (validSurface != null) {
            scope.launch { _videoDecoder?.attach(validSurface, width, height) }
        }
    }

    /**
     * Guarantee a video decoder exists before a native session starts.
     *
     * stop() releases the decoder and nulls it (ignition off does exactly that),
     * but the Bluetooth-handshake dial reaches the transport directly through
     * AasdkSession.startTcp() and never re-runs the full session setup where the
     * decoder is built. The result was a native session with no decoder at all:
     *
     *     15:36:44  Starting native aasdk session
     *     15:36:44  Cannot attach surface: pendingSurface=true decoder=false
     *
     * Every frame arrived and was discarded — a black screen behind a healthy
     * stream. Save & Reconnect appeared to "fix" it only because that path does
     * go through the full setup and therefore built a decoder.
     */
    /**
     * Guarantee an audio player exists before a session starts.
     *
     * stop() releases and nulls it, and the recovery path never re-runs the full
     * setup that creates one — the same asymmetry that left the video decoder
     * null. Frames would then arrive and be dropped by `_audioPlayer?.` with no
     * trace, and audioStats (which reads from the player) would show nothing,
     * making it look as though no audio was ever sent.
     */
    private fun ensureAudioPlayer() {
        if (_audioPlayer != null) return
        OalLog.i(TAG, "No audio player for this session — creating one")
        _audioPlayer = audioManager?.let { AudioPlayerImpl(it) }
        _audioPlayer?.initialize()
        (_audioPlayer as? AudioPlayerImpl)?.coordinator?.let { coord ->
            coord.volumeOffsetMedia = lastVolumeOffsetMedia
            coord.volumeOffsetNavigation = lastVolumeOffsetNavigation
            coord.volumeOffsetAssistant = lastVolumeOffsetAssistant
        }
        _telemetryCollector?.audioPlayer = _audioPlayer
    }

    /** Collectors bound to one AasdkSession, cancelled when it is replaced. */
    private var sessionCollectors: kotlinx.coroutines.Job? = null
    private var forecastExpiryJob: kotlinx.coroutines.Job? = null

    /**
     * Subscribe to everything a session produces: frames, control messages,
     * codec negotiation, reconnect counters.
     *
     * These used to be launched inline in the full session setup, which meant
     * they bound to whichever AasdkSession existed at that moment. A transport
     * restart creates a new native session without re-running that setup, so the
     * collectors stayed attached to the old one and the new session's output went
     * nowhere. Audio was the visible symptom — channels negotiated, codec agreed,
     * "Audio start" received, and not one frame reaching the player — but it is
     * the same fault that produced a black screen (no decoder), a blank screen
     * (no surface), and dead touch (no session reference) in earlier releases.
     *
     * Four instances of one pattern. Rebinding in a single place means the next
     * thing added to a session cannot silently miss a restart.
     */
    private fun bindSessionCollectors(session: AasdkSession) {
        sessionCollectors?.cancel()
        sessionCollectors = scope.launch {
            // Mirror per-session reconnect-attempt counter so observers (UI
            // banner, 3-failure picker escalation) don't have to track
            // AasdkSession identity.
            launch {
                session.reconnectAttempt.collect { attempt ->
                    _reconnectAttempt.value = attempt
                    // Also refresh the status message so the banner updates when
                    // the attempt counter advances without an accompanying state
                    // change.
                    if (attempt > 0 && _sessionState.value != SessionState.STREAMING) {
                        _statusMessage.value = "Reconnecting (attempt $attempt)…"
                    }
                }
            }
            launch {
                session.controlMessages.collect { message ->
                    lastActiveTimestamp = SystemClock.elapsedRealtime()
                    handleControlMessage(session, message)
                }
            }
            launch {
                session.vehicleEnergyForecast.collect { forecast ->
                    forecastExpiryJob?.cancel()
                    _vehicleEnergyForecast.value = forecast
                    ClusterNavigationState.vehicleEnergyForecast.value = forecast
                    forecastExpiryJob = if (forecast != null) {
                        scope.launch {
                            delay(VehicleEnergyForecastPolicy.MAX_AGE_MS)
                            if (_vehicleEnergyForecast.value === forecast) {
                                DiagnosticLog.i("vem", "Maps forecast expired after 120s")
                                _vehicleEnergyForecast.value = null
                                ClusterNavigationState.vehicleEnergyForecast.value = null
                            }
                        }
                    } else null
                }
            }
            launch(videoDispatcher) {
                session.videoFrames.collect { frame ->
                    lastVideoFrameArrivedMs = SystemClock.elapsedRealtime()
                    _videoDecoder?.onFrame(frame)
                }
            }
            launch {
                session.negotiatedCodecType.collect { codecType ->
                    if (codecType > 0) {
                        (_videoDecoder as? com.openautolink.app.video.MediaCodecDecoder)
                            ?.setNegotiatedCodec(codecType)
                    }
                }
            }
            launch(audioDispatcher) {
                session.audioFrames.collect { frame ->
                    _audioPlayer?.onAudioFrame(frame)
                }
            }
        }
    }

    private fun createVideoDecoder(
        codecPreference: String,
        scalingMode: String,
    ): MediaCodecDecoder {
        val decoderGeneration = videoDecoderGeneration.incrementAndGet()
        return MediaCodecDecoder(
            codecPreference = codecPreference,
            scalingMode = scalingMode,
            onSessionRestartNeeded = { reason ->
                val targetSession = aasdkSession
                OalLog.w(TAG, "Decoder cannot recover the output surface in-place — $reason")
                scope.launch(kotlinx.coroutines.Dispatchers.IO) {
                    if (decoderGeneration != videoDecoderGeneration.get()) {
                        OalLog.i(TAG, "Ignoring surface recovery from stale decoder generation $decoderGeneration")
                        return@launch
                    }
                    if (targetSession == null || aasdkSession !== targetSession) {
                        OalLog.i(TAG, "Ignoring surface recovery from a replaced session")
                        return@launch
                    }
                    targetSession.forceReconnect(reason)
                }
            },
        )
    }

    fun ensureVideoDecoder() {
        if (_videoDecoder != null) {
            // Say the decoder is fine AND whether it can draw. A decoder with no
            // surface produced a black screen behind a perfectly healthy stream,
            // and nothing in the log said so.
            val hasSurface = lastKnownSurface != null
            if (!hasSurface) {
                OalLog.w(TAG, "CONNECT SUMMARY: decoder exists but has NO SURFACE — " +
                        "video will decode to nothing (black screen)")
            }
            return
        }
        OalLog.i(TAG, "No video decoder for this session — creating one")
        _videoDecoder = createVideoDecoder(lastCodecPreference, lastScalingMode)
        lastKnownSurface?.let { (surface, w, h) ->
            if (surface.isValid) {
                OalLog.i(TAG, "Attaching the last known surface to the new decoder")
                _videoDecoder?.attach(surface, w, h)
            }
        }
        onDecoderCreated?.invoke()
    }

    private var _videoDecoder: VideoDecoder? = null
    val videoDecoder: VideoDecoder? get() = _videoDecoder
    val videoStats: StateFlow<VideoStats>? get() = _videoDecoder?.stats
    val decoderState: StateFlow<DecoderState>? get() = _videoDecoder?.decoderState

    // Touch coordinate space — matches the SDR input channel touchscreen dimensions.
    // NOT the video codec output dimensions (which may differ due to phone auto-negotiation).
    private val _touchWidth = MutableStateFlow(1920)
    private val _touchHeight = MutableStateFlow(1080)
    val touchWidth: StateFlow<Int> = _touchWidth.asStateFlow()
    val touchHeight: StateFlow<Int> = _touchHeight.asStateFlow()

    // Base density calculated from the saved resolution. In auto negotiation,
    // native derives the actual value per advertised tier. Diagnostic-only;
    // 0 until the first session has built its SDR.
    private val _effectiveDpi = MutableStateFlow(0)
    val effectiveDpi: StateFlow<Int> = _effectiveDpi.asStateFlow()

    // Last-known OS-reported safe area (system bars + display cutouts) in
    // pixels. Fed by ProjectionScreen via [setSystemInsets] whenever the
    // composition picks up new WindowInsets. Used as fallback for AA
    // content_insets when the user hasn't manually overridden them in
    // settings — so a vehicle with a curved screen reports its rounded
    // corners to AA out of the box, and the user only has to tweak if
    // they want extra margin beyond what AAOS reports.
    @Volatile private var sysInsetTop: Int = 0
    @Volatile private var sysInsetBottom: Int = 0
    @Volatile private var sysInsetLeft: Int = 0
    @Volatile private var sysInsetRight: Int = 0
    fun setSystemInsets(top: Int, bottom: Int, left: Int, right: Int) {
        sysInsetTop = top.coerceAtLeast(0)
        sysInsetBottom = bottom.coerceAtLeast(0)
        sysInsetLeft = left.coerceAtLeast(0)
        sysInsetRight = right.coerceAtLeast(0)
    }

    // Last-known live render-rect dimensions in pixels — the actual size
    // of the projection Box AFTER displayMode padding. In
    // fullscreen_immersive this is the full panel; in system_ui_visible
    // it's the panel minus AAOS chrome. Fed by ProjectionScreen via
    // [setRenderRect]. Used by auto-DPI to compute density that produces
    // the same physical size as native AAOS apps regardless of mode.
    @Volatile private var renderRectWPx: Int = 0
    @Volatile private var renderRectHPx: Int = 0
    @Volatile private var panelDensityDpi: Int = 0
    @Volatile private var lastDisplayMode: String = AppPreferences.DEFAULT_DISPLAY_MODE
    /** True once the renderer has measured, so auto-DPI can be computed. */
    val hasRenderRect: Boolean get() = renderRectWPx > 0 && renderRectHPx > 0

    /**
     * Wait (briefly) for the renderer to report its size.
     *
     * Auto-DPI needs the render rect, which Compose measures. Starting a session
     * before that ships the user's MANUAL dpi to the phone instead, and the whole
     * projected UI comes up at the wrong scale until something forces a rebuild.
     */
    suspend fun awaitRenderRect(timeoutMs: Long = 3_000): Boolean {
        if (hasRenderRect) return true
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            kotlinx.coroutines.delay(50)
            if (hasRenderRect) return true
        }
        return false
    }

    fun setRenderRect(widthPx: Int, heightPx: Int, panelDpi: Int, displayMode: String? = null) {
        renderRectWPx = widthPx.coerceAtLeast(0)
        renderRectHPx = heightPx.coerceAtLeast(0)
        panelDensityDpi = panelDpi.coerceAtLeast(0)
        if (!displayMode.isNullOrBlank()) lastDisplayMode = displayMode
    }

    // Audio player
    private var _audioPlayer: AudioPlayer? = null
    val audioPlayer: AudioPlayer? get() = _audioPlayer
    val audioStats: StateFlow<AudioStats>? get() = _audioPlayer?.stats

    // Mic capture
    private var _micCaptureManager: MicCaptureManager? = null
    private var micSource: String = "car"

    // HFP RFCOMM "presence" advertiser — mirrors headunit-revived's trick of
    // listening on the Hands-Free Profile UUID so the phone sees an
    // HFP-advertising device during BT scan. We never speak HFP AT commands
    // and never accept SCO; this is purely for discovery/handshake. See
    // [com.openautolink.app.transport.bluetooth.HfpPresenceServer].
    private var _hfpPresence: com.openautolink.app.transport.bluetooth.HfpPresenceServer? = null


    val callState: StateFlow<CallState>? get() = _audioPlayer?.callState

    // GNSS forwarder
    private var _gnssForwarder: GnssForwarder? = null

    // Vehicle data forwarder
    private var _vehicleDataForwarder: VehicleDataForwarder? = null
    val vehicleData: StateFlow<ControlMessage.VehicleData>?
        get() = _vehicleDataForwarder?.latestVehicleData

    /**
     * Throttle state for [DiagnosticLog] energy-model spam. The vehicle
     * data forwarder fires whenever any tracked VHAL property changes —
     * with EV charging that can be many times per second per Wh tick.
     * We only log when one of the headline numbers actually moved or at
     * least [VEM_LOG_MIN_GAP_MS] elapsed.
     */
    private var lastVemLogMs: Long = 0L
    private var lastVemBatteryWh: Int = Int.MIN_VALUE
    private var lastVemRangeM: Int = Int.MIN_VALUE
    private var lastVemChargeW: Int = Int.MIN_VALUE
    private val VEM_LOG_MIN_GAP_MS: Long = 30_000L

    /**
     * EV energy-model tuning snapshot — see docs/ev-energy-model-tuning-plan.md.
     * Updated by a coroutine that observes [AppPreferences] flows; read on the
     * VHAL hot path with no locking. When [evTuningEnabled] is false, all
     * `evXxx` overrides are ignored and we send today's hardcoded VEM values
     * (mirrors plan acceptance test #2).
     */
    @Volatile private var evTuningEnabled: Boolean = false
    @Volatile private var evDrivingMode: String = AppPreferences.DEFAULT_EV_DRIVING_MODE
    @Volatile private var evDrivingWhPerKm: Int = AppPreferences.DEFAULT_EV_DRIVING_WH_PER_KM
    @Volatile private var evDrivingMultiplierPct: Int = AppPreferences.DEFAULT_EV_DRIVING_MULTIPLIER_PCT
    @Volatile private var evAuxWhPerKmX10: Int = AppPreferences.DEFAULT_EV_AUX_WH_PER_KM_X10
    @Volatile private var evAeroCoefX100: Int = AppPreferences.DEFAULT_EV_AERO_COEF_X100
    @Volatile private var evReservePct: Int = AppPreferences.DEFAULT_EV_RESERVE_PCT
    @Volatile private var evMaxChargeKw: Int = AppPreferences.DEFAULT_EV_MAX_CHARGE_KW
    @Volatile private var evMaxDischargeKw: Int = AppPreferences.DEFAULT_EV_MAX_DISCHARGE_KW

    /** EPA / curated baseline (Phase 2). When non-null AND used (see flag),
     *  the C++ side gets these values when the user-tunable override would
     *  otherwise have been "derive on the C++ side". */
    @Volatile private var evUseEpaBaseline: Boolean = AppPreferences.DEFAULT_EV_USE_EPA_BASELINE
    @Volatile private var epaDrivingWhPerKm: Int? = null
    @Volatile private var epaMaxChargeKw: Int? = null

    /** Phase 3' — learned-rate estimator. Lazily initialized once context
     *  is available; null on the (rare) early ticks before that. The UI
     *  reads the same singleton directly via [EvLearnedRateEstimator.getInstance]
     *  so the EV screen works even when no session has started. */
    private var evLearnedEstimator: EvLearnedRateEstimator? = null
    val evLearnedSnapshot: StateFlow<EvLearnedRateEstimator.Snapshot>?
        get() = evLearnedEstimator?.activeSnapshot

    fun resetEvLearnedRate() {
        val snapshot = evLearnedEstimator?.activeSnapshot?.value
        evLearnedEstimator?.reset(snapshot?.key)
    }

    // IMU forwarder
    private var _imuForwarder: ImuForwarder? = null

    // Direct mode location listener
    private var _directLocationListener: android.location.LocationListener? = null
    private var gpsForwardingEnabled: Boolean = true

    // Navigation display
    private val _navigationDisplay: NavigationDisplay = NavigationDisplayImpl()
    val navigationDisplay: NavigationDisplay get() = _navigationDisplay

    private val _vehicleEnergyForecast = MutableStateFlow<VehicleEnergyForecast?>(null)
    val vehicleEnergyForecast: StateFlow<VehicleEnergyForecast?> =
        _vehicleEnergyForecast.asStateFlow()
    private val _vehicleBatteryCapacityWh = MutableStateFlow(0)
    val vehicleBatteryCapacityWh: StateFlow<Int> = _vehicleBatteryCapacityWh.asStateFlow()

    // Diagnostics
    private var _remoteDiagnostics: RemoteDiagnosticsImpl? = null
    val remoteDiagnostics: RemoteDiagnostics? get() = _remoteDiagnostics
    private var _telemetryCollector: TelemetryCollector? = null

    val currentManeuver: StateFlow<ManeuverState?>
        get() = _navigationDisplay.currentManeuver

    // Phone battery
    private val _phoneBatteryLevel = MutableStateFlow<Int?>(null)
    val phoneBatteryLevel: StateFlow<Int?> = _phoneBatteryLevel.asStateFlow()
    private val _phoneBatteryCritical = MutableStateFlow(false)
    val phoneBatteryCritical: StateFlow<Boolean> = _phoneBatteryCritical.asStateFlow()

    // Voice session
    private val _voiceSessionActive = MutableStateFlow(false)
    val voiceSessionActive: StateFlow<Boolean> = _voiceSessionActive.asStateFlow()

    // Phone signal
    private val _phoneSignalStrength = MutableStateFlow<Int?>(null)
    val phoneSignalStrength: StateFlow<Int?> = _phoneSignalStrength.asStateFlow()

    // WiFi frequency. Reserved for future use — the TCP hotspot path doesn't
    // currently report this. Kept as a flow so the stats overlay can keep its
    // existing wiring; reports 0 (unknown) until a producer is added.
    private val _wifiFrequencyMhz = MutableStateFlow(0)
    val wifiFrequencyMhz: StateFlow<Int> = _wifiFrequencyMhz.asStateFlow()

    // Current transport mode. Today only "hotspot" and "usb" are wired.
    private val _transportMode = MutableStateFlow("hotspot")
    val transportMode: StateFlow<String> = _transportMode.asStateFlow()

    // Multi-phone default. The currently-connected phone's friendly name is
    // resolved by ProjectionViewModel from PhoneDiscovery + knownPhonesStore.
    @Volatile private var _defaultPhoneName: String = ""

    /**
     * Last manualIpAddress used by start()/reconnect() for the current/most
     * recent session. Cached so that "Save & Reconnect" — which doesn't know
     * the resolved Car Hotspot IP — can keep dialing the same phone instead
     * of dropping to mDNS-only resolution (which times out without an IP).
     * Cleared by clearDefaultPhone().
     */
    @Volatile private var _lastManualIpAddress: String? = null

    /** Set the default phone name from preferences (called at session start). */
    fun setDefaultPhoneName(name: String) { _defaultPhoneName = name }

    /** Get the current default phone name. */
    fun getDefaultPhoneName(): String = _defaultPhoneName

    /** True while an exact WPP session token owns the process-scoped advertiser. */
    fun hasCurrentWppOwner(): Boolean = AaWirelessBtControl.hasCurrentWppOwner()

    /**
     * Re-check wake admission inside the lifecycle owner instead of transporting
     * a suspend callback across coroutine/default-argument state machines. The
     * 0.1.465 carried source=ignition into this owner while the compiled
     * callback argument was null, so no WPP owner was created.
     */
    private suspend fun currentWppRearmRejection(
        wppRearmSource: String?,
        stage: String,
    ): String? {
        if (wppRearmSource == null) return null
        val ctx = context
        val rejection = if (ctx == null) {
            "context-missing"
        } else {
            val currentTransport = AppPreferences.getInstance(ctx).directTransport.first()
            when {
                currentTransport != AppPreferences.DIRECT_TRANSPORT_WPP -> "transport-changed"
                IgnitionMonitor.isOffOrLocked() -> "ignition-off"
                sessionState.value != SessionState.IDLE -> "session-not-idle"
                AaWirelessBtControl.hasCurrentWppOwner() -> "session-owner-active"
                else -> null
            }
        }
        OalLog.i(
            TAG,
            "WPP rearm admission checked locally: source=$wppRearmSource stage=$stage result=${rejection ?: "accepted"}",
        )
        return rejection
    }

    /** Clear the default phone — next connection will pick any phone. */
    fun clearDefaultPhone() {
        _defaultPhoneName = ""
        _lastManualIpAddress = null
        scope.launch {
            context?.let { AppPreferences.getInstance(it).setDefaultPhoneName("") }
        }
    }

    /** Switch phone: disconnect, restart discovery in chooser mode. */
    fun switchPhone() {
        scope.launch { stop() }
    }

    // Media session. Holds a reference to the process-wide singleton (created
    // in OalApplication, not here). SessionManager only pushes metadata /
    // playback updates and resets the tile to idle on teardown — it never
    // creates or releases the session, so the cluster's token binding survives
    // across connects, sleep/wake, and phone switches.
    private var _mediaSessionManager: OalMediaSessionManager? = null
    /** Cache of the most recently observed [ControlMessage.MediaMetadata] so
     *  we can replay it to the cluster on reconnect. The phone sends metadata
     *  only on track change, so after an unrelated reconnect (sleep/wake,
     *  Error 30, etc.) the cluster would otherwise see no update and could
     *  stay stuck on stale data. */
    @Volatile private var lastMediaMetadata: ControlMessage.MediaMetadata? = null
    @Volatile private var lastMediaPlaybackState: ControlMessage.MediaPlaybackState? = null

    // Edge-trigger memory for low-cadence VHAL booleans + gear. The VHAL
    // forwarder sends a full bundle every ~100ms while connected, but the
    // phone only cares when these actually transition. Spamming them every
    // tick is wasted IPC at best, and at worst it might cause the phone to
    // re-render UI (e.g. NIGHT_MODE → AA theme change) too frequently. We
    // only forward to native when the value differs from the last sent.
    // Reset to null on every new phone session so the very first tick always fires.
    @Volatile private var lastKnownUiNightMode: Boolean? = bootUiNightModeSnapshot
    @Volatile private var lastSentNightMode: Boolean? = null
    @Volatile private var lastSentParkingBrake: Boolean? = null
    @Volatile private var lastSentDriving: Boolean? = null
    /**
     * When true, the phone is always told the car is parked, regardless of the
     * real VHAL gear. Off by default (real state is forwarded). Issue #61.
     */
    @Volatile private var alwaysInPark: Boolean = AppPreferences.DEFAULT_ALWAYS_IN_PARK
    @Volatile private var alwaysInParkObserverStarted = false
    @Volatile private var lastSentGearRaw: Int? = null

    // Cluster manager
    private var _clusterManager: com.openautolink.app.cluster.ClusterManager? = null

    private val startMutex = kotlinx.coroutines.sync.Mutex()
    @Volatile private var lifecycleGeneration = 0L

    /** Caller must already hold [startMutex]. */
    private suspend fun rollbackLifecycleFailure(
        phase: String,
        error: Throwable,
        cancelObserveJob: Boolean = true,
    ) {
        OalLog.e(TAG, "$phase lifecycle failed: ${error.message} — rolling back")
        try {
            stopWhileLifecycleLocked(cancelObserveJob = cancelObserveJob)
        } catch (cleanupError: Exception) {
            error.addSuppressed(cleanupError)
            OalLog.e(TAG, "$phase rollback failed: ${cleanupError.message}")
        }
    }

    private var observeJob: Job? = null
    private var decoderWatchJob: Job? = null
    private var keyframeWatchJob: Job? = null
    private var callStateJob: Job? = null
    private var videoStallWatchJob: Job? = null

    // -- Video-stall watchdog state --
    // Wall-clock (elapsedRealtime) of the last video frame that ARRIVED from the
    // phone (bumped in the videoFrames collector). Distinct from decode success:
    // catches the "frames simply stop arriving" freeze (half-open TCP / phone
    // AA video-flow wedge) that watchKeyframeNeeds cannot — that only fires on a
    // decode FAILURE. See report 2026-06-23_1700 + 0.1.364 log 2026-07-20
    // (great drive → froze, phone showed disconnected while still on car WiFi,
    // caught only by the native 9s ping timeout with no recovery).
    @Volatile private var lastVideoFrameArrivedMs = 0L
    // Set true while the user has navigated away / screen is off, so the
    // watchdog does not false-fire on a legitimate pause (cf. #43).
    @Volatile private var isGoingIdle = false
    private val VIDEO_STALL_KEYFRAME_MS = 4_000L    // no frame this long → nudge keyframe
    private val VIDEO_STALL_RECONNECT_MS = 7_000L   // still no frame → force reconnect
    private val VIDEO_STALL_WARMUP_MS = 6_000L      // grace after STREAMING before arming

    // Track last known active time for sleep/wake detection.
    // Single source of truth, updated on:
    //   - every incoming control message (proves we're running)
    //   - Activity.onPause / SCREEN_OFF via markGoingIdle ("freeze" the timestamp before suspend)
    //   - markWake itself (so a second wake signal within a few seconds doesn't recompute the same gap)
    private var lastActiveTimestamp = SystemClock.elapsedRealtime()

    /**
     * Wake-event flow. Emits whenever the system or activity transitions back
     * to running after a (possibly long) idle period. Fed from two redundant
     * sources — Activity.onResume and the SCREEN_ON broadcast — with dedupe in
     * [markWake] so observers see exactly one event per real wake.
     */
    data class WakeEvent(val reason: String, val gapMs: Long)
    private val _wakeEvents = MutableSharedFlow<WakeEvent>(extraBufferCapacity = 4)
    val wakeEvents: SharedFlow<WakeEvent> = _wakeEvents.asSharedFlow()

    /**
     * Emitted when the user taps the Exit button in the AA app launcher on
     * the phone (ByeByeReason.USER_SELECTION). MainActivity observes this and
     * backgrounds the entire app — user re-enters by tapping our icon in the
     * AAOS launcher, which starts a fresh session.
     */
    private val _userExitEvents = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val userExitEvents: SharedFlow<Unit> = _userExitEvents.asSharedFlow()

    /** Dedupe window for wake signals from multiple sources firing in quick succession. */
    private val WAKE_DEDUPE_MS = 2_000L
    /** Wake gap beyond which the current TCP socket is presumed dead and we force a reconnect. */
    private val LONG_WAKE_FORCE_RECONNECT_MS = 30_000L

    /**
     * Video newer than this proves the transport survived the gap.
     *
     * Generous, because the phone throttles encoding hard on a static screen —
     * measured windows of 2s at ~75kbps — so a short threshold would read a
     * healthy-but-idle stream as dead.
     */
    private val TRANSPORT_ALIVE_FRAME_WINDOW_MS = 10_000L
    /** Last elapsedRealtime we ran the full wake handler for. Used for dedupe. */
    @Volatile private var lastWakeHandledAtMs = 0L

    /** Reentrancy guard for reconnect() so rapid Save&Reconnect taps coalesce. */
    @Volatile private var reconnectInProgress = false

    /** When the current reconnect began, so the guard above cannot latch. */
    @Volatile private var reconnectStartedAt = 0L

    /** Longest a reconnect can plausibly take before it is presumed dead. */
    private val RECONNECT_MAX_MS = 60_000L

    /** SCREEN_OFF/_ON receiver registration tracker. */
    private var screenReceiver: android.content.BroadcastReceiver? = null

    private fun currentUiNightMode(): Boolean? {
        val nightMask = context?.resources?.configuration?.uiMode?.and(Configuration.UI_MODE_NIGHT_MASK)
            ?: return null
        return when (nightMask) {
            Configuration.UI_MODE_NIGHT_YES -> true
            Configuration.UI_MODE_NIGHT_NO -> false
            else -> null
        }
    }

    private fun resetLatchedVehicleSensorState(reason: String) {
        lastSentNightMode = null
        lastSentParkingBrake = null
        lastSentDriving = null
        lastSentGearRaw = null
        OalLog.d(TAG, "Reset latched vehicle sensor state: $reason")
    }

    /**
     * Observe the "always in park" override. Started from [start] so it is live
     * for the whole session; toggling mid-session re-pushes driving status so
     * the phone reacts without a reconnect. Issue #61.
     */
    private fun observeAlwaysInPark() {
        if (alwaysInParkObserverStarted) return
        val ctx = context ?: return
        alwaysInParkObserverStarted = true
        val prefs = AppPreferences.getInstance(ctx)
        scope.launch {
            prefs.alwaysInPark.collect { enabled ->
                val changed = alwaysInPark != enabled
                alwaysInPark = enabled
                if (changed) {
                    val real = _vehicleDataForwarder?.latestVehicleData?.value?.driving ?: false
                    val driving = if (enabled) false else real
                    lastSentDriving = driving
                    aasdkSession?.sendDrivingStatus(driving)
                    DiagnosticLog.i("vhal", "alwaysInPark=$enabled — re-pushed driving=$driving (real=$real)")
                }
            }
        }
    }

    @Synchronized
    private fun ensureVehicleDataForwarder(): VehicleDataForwarder? {
        _vehicleDataForwarder?.let { return it }
        val ctx = context ?: return null
        return VehicleDataForwarderImpl(
            ctx,
            sendMessage = ::forwardVehicleData,
            onIgnitionOn = { /* aasdk mode doesn't need ignition-based reconnect */ },
        ).also { _vehicleDataForwarder = it }
    }

    private fun forwardVehicleData(vd: ControlMessage.VehicleData) {
        val session = aasdkSession ?: return
        vd.speedKmh?.let { session.sendSpeed((it / 3.6f * 1000).toInt()) }
        // Edge-trigger low-cadence properties so each transition fires once.
        vd.gearRaw?.let {
            if (lastSentGearRaw != it) { lastSentGearRaw = it; session.sendGear(it) }
        }
        vd.parkingBrake?.let {
            if (lastSentParkingBrake != it) { lastSentParkingBrake = it; session.sendParkingBrake(it) }
        }
        vd.nightMode?.let {
            if (lastSentNightMode != it) { lastSentNightMode = it; session.sendNightMode(it) }
        }
        vd.driving?.let {
            val driving = if (alwaysInPark) false else it
            if (lastSentDriving != driving) {
                lastSentDriving = driving
                session.sendDrivingStatus(driving)
            }
        }
        if (vd.fuelLevelPct != null || vd.rangeKm != null) {
            session.sendFuel(
                vd.fuelLevelPct ?: 0,
                ((vd.rangeKm ?: 0f) * 1000).toInt(),
                vd.lowFuel ?: false,
            )
        }
        vd.rpmE3?.let { session.sendRpm(it) }
        if (vd.evBatteryLevelWh != null || vd.evBatteryCapacityWh != null) {
            val batteryWh = vd.evBatteryLevelWh?.toInt() ?: 0
            val capacityWh = vd.evBatteryCapacityWh?.toInt() ?: 0
            val rangeM = ((vd.rangeKm ?: 0f) * 1000).toInt()
            val chargeW = vd.evChargeRateW?.toInt() ?: 0
            if (capacityWh > 0) {
                _vehicleBatteryCapacityWh.value = capacityWh
                ClusterNavigationState.batteryCapacityWh = capacityWh
            }
            val now = SystemClock.elapsedRealtime()
            evLearnedEstimator?.onVehicleTick(vd, now)
            refreshEvProfileLookup(vd)
            val movedBattery = kotlin.math.abs(batteryWh - lastVemBatteryWh) >= 100
            val movedRange = kotlin.math.abs(rangeM - lastVemRangeM) >= 500
            val movedCharge = kotlin.math.abs(chargeW - lastVemChargeW) >= 100
            val firstEmit = lastVemLogMs == 0L
            val staleEnough = (now - lastVemLogMs) >= VEM_LOG_MIN_GAP_MS
            if (firstEmit || movedBattery || movedRange || movedCharge || staleEnough) {
                DiagnosticLog.i(
                    "vem",
                    "sendEnergyModel: level=${batteryWh}Wh cap=${capacityWh}Wh range=${rangeM}m charge=${chargeW}W${evTuningSummary(batteryWh, rangeM)}",
                )
                lastVemLogMs = now
                lastVemBatteryWh = batteryWh
                lastVemRangeM = rangeM
                lastVemChargeW = chargeW
            }
            sendEnergyModelWithTuning(session, batteryWh, capacityWh, rangeM, chargeW)
        }
    }

    /**
     * The phone subscribed to a sensor type. Push current vehicle state now.
     *
     * Gearhead defaults driving status to FULLY_RESTRICTED(31) and only leaves
     * that state on a real sample; a parked car emits no VHAL change events, so
     * a change-driven pipeline never answers and Maps blocks the keyboard for
     * the whole session. The latches are cleared first because the initial
     * VHAL burst fires ~130ms BEFORE the sensor channel opens — those sends are
     * dropped by the native `!streaming_ || !sensorChannel_` guard, yet still
     * recorded as sent, which would otherwise suppress this re-push. Issue #61.
     */
    private fun onPhoneSubscribedSensor(sensorType: Int) {
        if (sensorType == SENSOR_TYPE_VEHICLE_ENERGY_MODEL) {
            sendCurrentEnergyModel("sensor-subscribe")
            return
        }
        val session = aasdkSession ?: return
        val vd = _vehicleDataForwarder?.latestVehicleData?.value
        resetLatchedVehicleSensorState("sensor_subscribe_$sensorType")
        when (sensorType) {
            SENSOR_TYPE_DRIVING_STATUS -> {
                // Default to "parked" when VHAL has not reported a gear yet.
                // Being wrong here is strictly safer than silence: silence is
                // read by the phone as fully restricted, and a real gear change
                // corrects it within one VHAL event.
                val driving = if (alwaysInPark) false else (vd?.driving ?: false)
                lastSentDriving = driving
                session.sendDrivingStatus(driving)
                DiagnosticLog.i("vhal", "pushed driving=$driving on subscribe (gear=${vd?.gearRaw})")
            }
            SENSOR_TYPE_GEAR -> vd?.gearRaw?.let {
                lastSentGearRaw = it; session.sendGear(it)
            }
            SENSOR_TYPE_PARKING_BRAKE -> vd?.parkingBrake?.let {
                lastSentParkingBrake = it; session.sendParkingBrake(it)
            }
            SENSOR_TYPE_NIGHT_MODE -> {
                val night = vd?.nightMode ?: lastKnownUiNightMode ?: currentUiNightMode()
                night?.let { lastSentNightMode = it; session.sendNightMode(it) }
            }
            SENSOR_TYPE_SPEED -> vd?.speedKmh?.let {
                session.sendSpeed((it / 3.6f * 1000).toInt())
            }
        }
    }

    private fun seedCurrentUiNightMode(reason: String) {
        // Prefer Activity-reported state. Application-context config can be
        // stale on AAOS and default to day mode until a config callback fires.
        val night = lastKnownUiNightMode ?: currentUiNightMode()
        if (night == null) {
            OalLog.d(TAG, "UI night mode unknown — skipping seed ($reason)")
            return
        }
        lastKnownUiNightMode = night
        val session = aasdkSession ?: return
        lastSentNightMode = night
        OalLog.i(TAG, "UI night mode → $night (reason=$reason)")
        session.sendNightMode(night)
    }

    suspend fun start(
        codecPreference: String = "h264",
        micSourcePreference: String = "car",
        scalingMode: String = "letterbox",
        directTransport: String = "hotspot",
        hotspotSsid: String = "",
        hotspotPassword: String = "",
        videoAutoNegotiate: Boolean = true,
        aaResolution: String = "1080p",
        aaDpi: Int = 160,
        aaAutoDpi: Boolean = true,
        aaWidthMargin: Int = 0,
        aaHeightMargin: Int = 0,
        aaPixelAspect: Int = -1,
        aaTargetLayoutWidthDp: Int = 0,
        aaViewingDistanceMm: Int = 0,
        aaDecoderAdditionalDepth: Int = 0,
        aaAutoMargins: Boolean = true,
        videoFps: Int = 60,
        driveSide: String = "left",
        hideClock: Boolean = false,
        hideSignal: Boolean = false,
        hideBattery: Boolean = false,
        volumeOffsetMedia: Int = 0,
        volumeOffsetNavigation: Int = 0,
        volumeOffsetAssistant: Int = 0,
        manualIpAddress: String? = null,
        safeAreaTop: Int = 0,
        safeAreaBottom: Int = 0,
        safeAreaLeft: Int = 0,
        safeAreaRight: Int = 0,
        gpsForwarding: Boolean = true,
        galVersion: String = AppPreferences.DEFAULT_GAL_VERSION,
        wppRearmSource: String? = null,
    ) {
        val requestGeneration = lifecycleGeneration
        startMutex.lock()
        try {
        kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
        if (requestGeneration != lifecycleGeneration) {
            OalLog.i(TAG, "Start request rejected — lifecycle stop completed while waiting")
            return@withContext
        }
        val initialWppRejection = currentWppRearmRejection(wppRearmSource, stage = "initial")
        if (initialWppRejection != null) {
            OalLog.i(TAG, "WPP rearm rejected: source=$wppRearmSource " +
                "reason=$initialWppRejection")
            return@withContext
        }
        // Everything below mutates lifecycle-owned components. Any failure must
        // roll the whole replacement back before releasing the lifecycle mutex.
        try {
        // Cache for later reconnects that don't know the resolved IP (e.g.
        // Settings "Save & Reconnect" in Car Hotspot mode).
        if (!manualIpAddress.isNullOrBlank()) _lastManualIpAddress = manualIpAddress
        gpsForwardingEnabled = gpsForwarding
        micSource = micSourcePreference
        observeJob?.cancelAndJoin()

        // Create video decoder
        _videoDecoder?.release()
        // Remember these so a decoder can be rebuilt if a later transport restart
        // needs one without re-running this setup.
        lastCodecPreference = codecPreference
        lastScalingMode = scalingMode
        // Remembered for the same reason: an audio player rebuilt on a transport
        // restart must keep the user's volume offsets.
        lastVolumeOffsetMedia = volumeOffsetMedia
        lastVolumeOffsetNavigation = volumeOffsetNavigation
        lastVolumeOffsetAssistant = volumeOffsetAssistant
        _videoDecoder = createVideoDecoder(codecPreference, scalingMode)
        // Re-attach the surface the UI last reported, if there is one.
        //
        // Holding it here rather than only in the ViewModel removes the ordering
        // problem entirely: the decoder is created in this singleton, the
        // surface belongs to an activity-scoped ViewModel, and the two lifecycles
        // do not line up. After an ignition cycle the surface was recreated a
        // minute before the decoder and nothing joined them, so video ran into a
        // decoder with nothing to render to.
        lastKnownSurface?.let { (surface, w, h) ->
            if (surface.isValid) {
                OalLog.i(TAG, "Re-attaching the last known surface to the new decoder")
                _videoDecoder?.attach(surface, w, h)
            } else {
                OalLog.i(TAG, "Last known surface is no longer valid — waiting for a new one")
                lastKnownSurface = null
            }
        }
        // Hand the new decoder whatever surface already exists.
        //
        // The surface can be ready long before the session: after an ignition
        // cycle it was recreated 42s ahead of the decoder, so
        // `videoDecoder?.attach(...)` in onSurfaceAvailable was a silent no-op on
        // a null decoder, and the state-change collector that normally attaches
        // saw no transition. Video then streamed at 41fps into a decoder that had
        // no surface and was never configured — a black screen with a perfectly
        // healthy stream behind it.
        onDecoderCreated?.invoke()

        // Create audio player
        _audioPlayer?.release()
        _audioPlayer = audioManager?.let { AudioPlayerImpl(it) }
        _audioPlayer?.initialize()
        // Apply volume offsets to the audio coordinator
        (_audioPlayer as? AudioPlayerImpl)?.coordinator?.let { coord ->
            coord.volumeOffsetMedia = volumeOffsetMedia
            coord.volumeOffsetNavigation = volumeOffsetNavigation
            coord.volumeOffsetAssistant = volumeOffsetAssistant
        }

        // Create mic capture -- sends frames via AasdkSession
        _micCaptureManager?.release()
        _micCaptureManager = MicCaptureManager { frame ->
            aasdkSession?.let { session ->
                scope.launch { session.sendMicAudio(frame.data) }
            }
        }

        // Start HFP RFCOMM presence advertiser (no-op call audio path, just a
        // discovery hint for the phone). Recreated per-start so it survives
        // BT adapter cycles across sleep/wake.
        _hfpPresence?.stop()
        _hfpPresence = context?.let { ctx ->
            com.openautolink.app.transport.bluetooth.HfpPresenceServer(ctx, scope)
                .also { it.start() }
        }

        // Create GNSS forwarder (NMEA not used in direct mode -- LocationListener used instead)
        _gnssForwarder?.stop()
        _directLocationListener = null
        _gnssForwarder = context?.let { ctx ->
            GnssForwarderImpl(ctx) { _ -> /* NMEA not used in direct mode */ }
        }

        // Create vehicle data forwarder -- sends via AasdkSession
        _vehicleDataForwarder?.stop()
        _vehicleDataForwarder = null
        // Reset edge-trigger memory so the first tick of the new session
        // unconditionally publishes nightMode / parking / driving / gear to
        // the phone — the phone has no prior state from us.
        resetLatchedVehicleSensorState("start_session")
        observeAlwaysInPark()
        ensureVehicleDataForwarder()

        // Create IMU forwarder -- sends via AasdkSession
        _imuForwarder?.stop()
        _imuForwarder = context?.let { ctx ->
            ImuForwarder(ctx) { imuData ->
                val session = aasdkSession ?: return@ImuForwarder
                imuData.accelXe3?.let { x ->
                    session.sendAccelerometer(x, imuData.accelYe3 ?: 0, imuData.accelZe3 ?: 0)
                }
                imuData.gyroRxe3?.let { rx ->
                    session.sendGyroscope(rx, imuData.gyroRye3 ?: 0, imuData.gyroRze3 ?: 0)
                }
                imuData.compassBearingE6?.let { b ->
                    session.sendCompass(b, imuData.compassPitchE6 ?: 0, imuData.compassRollE6 ?: 0)
                }
            }
        }

        // Bind to the process-wide MediaSession (created in OalApplication).
        // We never create or release it here — the GM cluster's MediaController
        // binds to the token published once at process start, and recreating
        // the session would orphan that binding (the "music cluster frozen
        // after switching phones" bug). getInstance() is idempotent; the
        // initialize()/token-publish below are defensive no-ops that also cover
        // the rare path where a session starts before OalApplication finished.
        _mediaSessionManager = context?.let { OalMediaSessionManager.getInstance(it) }
        _mediaSessionManager?.let { media ->
            media.initialize()
            media.getSessionToken()?.let { token ->
                OalMediaBrowserService.updateSessionToken(token)
            }
            // Replay the most-recent metadata + playback state so the cluster
            // tile reflects the current (or freshly reconnected/switched) phone
            // rather than the idle placeholder or a previous phone's track.
            lastMediaMetadata?.let { m ->
                media.updateMetadata(
                    title = m.title, artist = m.artist, album = m.album,
                    durationMs = m.durationMs, albumArtBase64 = m.albumArtBase64,
                )
            }
            lastMediaPlaybackState?.let { p ->
                media.updatePlaybackState(p.playing, p.positionMs)
            }
        }

        // Cluster service is gated by user preference (default ON). When disabled,
        // the CarAppService component is disabled so Templates Host won't bind it.
        _clusterManager?.release()
        _clusterManager = null
        val clusterEnabled = context?.let { ctx ->
            try {
                kotlinx.coroutines.runBlocking {
                    AppPreferences.getInstance(ctx).clusterNavigation.first()
                }
            } catch (e: Exception) {
                OalLog.w(TAG, "Failed to read clusterNavigation pref: ${e.message}")
                AppPreferences.DEFAULT_CLUSTER_NAVIGATION
            }
        } ?: false
        if (clusterEnabled) {
            _clusterManager = context?.let { com.openautolink.app.cluster.ClusterManager(it) }
            _clusterManager?.setClusterEnabled(true)
            // Proactively launch cluster binding — Templates Host on GM doesn't auto-discover
            // the service via intent filter; it requires CarAppActivity to be launched first.
            _clusterManager?.launchClusterBinding()
            OalLog.i(TAG, "Cluster manager initialized and binding launched")
        } else {
            // Disable the component so Templates Host won't try to bind it on next boot.
            context?.let { ctx ->
                try {
                    com.openautolink.app.cluster.ClusterManager(ctx).setClusterEnabled(false)
                } catch (e: Exception) {
                    OalLog.w(TAG, "Failed to disable cluster component: ${e.message}")
                }
            }
            OalLog.i(TAG, "Cluster service disabled by user preference")
        }

        // Create diagnostics (local-only)
        _telemetryCollector?.stop()
        _remoteDiagnostics = RemoteDiagnosticsImpl()
        DiagnosticLog.instance = _remoteDiagnostics
        _telemetryCollector = TelemetryCollector(scope, _remoteDiagnostics!!, _sessionState)
        _telemetryCollector?.videoDecoder = _videoDecoder
        _telemetryCollector?.audioPlayer = _audioPlayer
        _telemetryCollector?.start()

        val startupOutcome = kotlinx.coroutines.CompletableDeferred<Unit>()
        val newObserveJob = scope.launch(start = kotlinx.coroutines.CoroutineStart.LAZY) {
            // Watch for decoder errors
            decoderWatchJob?.cancel()
            decoderWatchJob = launch { watchDecoderState() }

            // Watch for IDR starvation
            keyframeWatchJob?.cancel()
            keyframeWatchJob = launch { watchKeyframeNeeds() }

            // Watch for video-arrival stall (frames stop coming entirely)
            videoStallWatchJob?.cancel()
            // IO, not Main. This watchdog calls into the native session
            // (requestKeyframe -> nativeRequestKeyframe), and SessionManager's
            // scope is Dispatchers.Main. Measured 2026-08-11 09:13:44: the
            // watchdog escalated to forceReconnect, which began tearing the
            // native session down on IO, and the main thread's next native call
            // blocked behind that teardown for 16.4 seconds — ANR, tombstone,
            // app restart. Nothing on the main thread should enter JNI.
            videoStallWatchJob = launch(kotlinx.coroutines.Dispatchers.IO) { watchVideoStall() }

            // Watch call state for mic purpose routing
            callStateJob?.cancel()
            callStateJob = launch { watchCallState() }

            // Start direct mode session. Re-run WPP admission on this exact
            // serialized coroutine immediately before the destructive owner
            // transition; the caller may have suspended while reading settings
            // or waiting for its render rectangle.
            try {
                // Register before final admission so the same rollback block
                // owns both registration and unregistration.
                registerScreenReceiver()
                if (isDebuggableBuild()) registerDebugReceiver()
                val finalWppRejection = currentWppRearmRejection(wppRearmSource, stage = "final")
            if (finalWppRejection != null) {
                OalLog.i(TAG, "WPP rearm rejected: source=$wppRearmSource " +
                    "reason=$finalWppRejection")
                stopWhileLifecycleLocked(cancelObserveJob = false)
                startupOutcome.complete(Unit)
                return@launch
            }
                startSession(directTransport, hotspotSsid, hotspotPassword,
                    videoAutoNegotiate, codecPreference, aaResolution, aaDpi, aaAutoDpi,
                    aaWidthMargin, aaHeightMargin, aaPixelAspect, aaTargetLayoutWidthDp,
                    aaViewingDistanceMm, aaDecoderAdditionalDepth, aaAutoMargins,
                    videoFps,
                    driveSide, hideClock, hideSignal, hideBattery, scalingMode,
                    manualIpAddress,
                    safeAreaTop, safeAreaBottom, safeAreaLeft, safeAreaRight,
                    gpsForwarding, galVersion, wppRearmSource)
                startupOutcome.complete(Unit)
            } catch (e: Exception) {
                wppRearmSource?.let { source ->
                    OalLog.e(TAG, "WPP rearm outcome: source=$source " +
                        "ownerInstalled=false error=${e.message}")
                }
                startupOutcome.completeExceptionally(e)
            }
        }
        newObserveJob.invokeOnCompletion { cause ->
            if (!startupOutcome.isCompleted) {
                startupOutcome.completeExceptionally(
                    cause ?: IllegalStateException("session start ended before reporting outcome"),
                )
            }
        }
        observeJob = newObserveJob
        newObserveJob.start()

        kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
            startupOutcome.await()
        }
        } catch (e: Exception) {
            rollbackLifecycleFailure("start", e)
            throw e
        }
        }
        } finally {
            startMutex.unlock()
        }
    }

    private fun startSession(
        directTransport: String, hotspotSsid: String, hotspotPassword: String,
        videoAutoNegotiate: Boolean = true, codec: String = "h264",
        aaResolution: String = "1080p", aaDpi: Int = 160, aaAutoDpi: Boolean = true,
        aaWidthMargin: Int = 0, aaHeightMargin: Int = 0, aaPixelAspect: Int = -1,
        aaTargetLayoutWidthDp: Int = 0,
        aaViewingDistanceMm: Int = 0, aaDecoderAdditionalDepth: Int = 0,
        aaAutoMargins: Boolean = true,
        videoFps: Int = 60,
        driveSide: String = "left",
        hideClock: Boolean = false, hideSignal: Boolean = false, hideBattery: Boolean = false,
        scalingMode: String = "letterbox",
        manualIpAddress: String? = null,
        safeAreaTop: Int = 0, safeAreaBottom: Int = 0, safeAreaLeft: Int = 0, safeAreaRight: Int = 0,
        gpsForwarding: Boolean = true,
        galVersion: String = AppPreferences.DEFAULT_GAL_VERSION,
        wppRearmSource: String? = null,
    ) {
        aasdkSession?.stop()
        _transportMode.value = directTransport
        val ctx = context
        if (ctx == null) {
            wppRearmSource?.let { source ->
                OalLog.e(TAG, "WPP rearm outcome: source=$source " +
                    "ownerInstalled=false error=context-missing")
            }
            return
        }

        // Map resolution string to pixel dimensions. Strings are the AA
        // VideoCodecResolutionType enum values; portrait variants use the
        // `_p` suffix to distinguish from the same-pixel-count landscape
        // tier (e.g. 1080p = 1920×1080 vs 1080p_p = 1080×1920).
        val (resW, resH) = when (aaResolution) {
            "480p" -> 800 to 480
            "720p" -> 1280 to 720
            "1440p" -> 2560 to 1440
            "4k" -> 3840 to 2160
            "720p_p" -> 720 to 1280
            "1080p_p" -> 1080 to 1920
            "1440p_p" -> 1440 to 2560
            "4k_p" -> 2160 to 3840
            else -> 1920 to 1080 // "1080p" default
        }

        // Get BT MAC — BluetoothAdapter.getAddress() returns 02:00:00:00:00:00
        // on Android 8+ due to privacy. Try Settings.Secure first, then adapter.
        // GM AAOS returns literal "None" for missing properties. Some builds
        // refuse both: in that case the user can paste a real MAC into the
        // BT MAC override setting, which takes precedence over auto-detect.
        var btMac = ""
        try {
            val override = kotlinx.coroutines.runBlocking {
                AppPreferences.getInstance(ctx).btMacOverride.first().trim()
            }
            if (override.isNotEmpty()
                && override != "02:00:00:00:00:00"
                && !override.equals("none", ignoreCase = true)) {
                btMac = override.uppercase().replace('-', ':')
                OalLog.i(TAG, "BT MAC override in use: $btMac")
            }
        } catch (_: Exception) {}
        if (btMac.isEmpty()) {
            try {
                btMac = android.provider.Settings.Secure.getString(
                    ctx.contentResolver, "bluetooth_address") ?: ""
            } catch (_: Exception) {}
        }
        if (btMac.isEmpty() || btMac == "02:00:00:00:00:00"
            || btMac.equals("none", ignoreCase = true)) {
            btMac = ""
            try {
                @Suppress("MissingPermission")
                val btAdapter = android.bluetooth.BluetoothAdapter.getDefaultAdapter()
                val addr = btAdapter?.address ?: ""
                if (addr != "02:00:00:00:00:00"
                    && !addr.equals("none", ignoreCase = true)) btMac = addr
            } catch (_: Exception) {}
        }
        OalLog.i(TAG, "BT MAC for SDR: ${if (btMac.isNotEmpty()) btMac else "(none)"}")

        // One-line capability probe for picture-in-picture.
        //
        // PiP would be a better answer to "I switched apps and came back" than
        // any reconnect logic: the session would never be torn down at all. But
        // it is an OPTIONAL platform feature and automotive builds have
        // historically not shipped it. Rather than infer it from the Android
        // version, ask this head unit and record the answer in the log we already
        // collect, so the decision is made on evidence from the actual vehicle.
        runCatching {
            val pm = context?.packageManager ?: return@runCatching
            val pip = pm.hasSystemFeature(
                android.content.pm.PackageManager.FEATURE_PICTURE_IN_PICTURE)
            val freeform = pm.hasSystemFeature(
                android.content.pm.PackageManager.FEATURE_FREEFORM_WINDOW_MANAGEMENT)
            OalLog.i(TAG, "Windowing support: pictureInPicture=$pip " +
                    "freeform=$freeform sdk=${android.os.Build.VERSION.SDK_INT}")
        }.onFailure { OalLog.w(TAG, "Windowing probe failed: ${it.message}") }

        // Vehicle identity from VHAL.
        val vd = _vehicleDataForwarder?.latestVehicleData?.value
        val driverPos = if (driveSide == "right") 1 else 0

        // Fuel types and EV connector types from VHAL — needed for phone to
        // recognize this as an EV and request sensor type 23 (VehicleEnergyModel)
        val fuelTypes = vd?.fuelTypes ?: emptyList()
        val evConnectorTypes = vd?.evConnectorTypes ?: emptyList()
        OalLog.i(TAG, "SDR fuel=$fuelTypes ev_conn=$evConnectorTypes")

        // Auto-compute pixel_aspect_ratio for non-16:9 displays (crop mode).
        //
        // HISTORICAL NOTE: We used to compute pixel_aspect from display vs
        // video AR, hoping the phone would pre-shrink its UI horizontally so
        // a downstream non-uniform stretch would round circles back out. This
        // does not work — the phone ignores pixel_aspect_ratio_e4. GM's own
        // implementation hardcodes 10000 (= 1.0) on every config and instead
        // reserves UI chrome via width_margin/height_margin and lets the
        // car compositor scale the codec frame uniformly into the panel rect.
        //
        // We now mirror GM:
        //   - Default pixel_aspect = 10000 (1.0, square pixels).
        //   - User can manually override via the Pixel Aspect setting if a
        //     specific phone version actually responds to non-1.0 values.
        //   - The actual aspect-ratio fix is the Crop-mode margin-zoom render
        //     in ProjectionScreen: SurfaceView is inflated past the parent
        //     so codec margin pixels clip off-screen and the inner content
        //     rect lands on the panel with uniform square-pixel scaling.
        //
        // Manual margin and pixel_aspect overrides are still respected.
        val computedWidthMargin: Int = aaWidthMargin
        val computedHeightMargin: Int = aaHeightMargin
        val computedPixelAspect: Int = when {
            aaPixelAspect > 0 -> aaPixelAspect    // explicit manual override
            aaPixelAspect == 0 -> 0               // explicit "off" (omit field)
            else -> 10000                         // -1 (auto) → GM-default 1.0
        }

        // Per-tier DPI: in manual mode (single tier), compute DPI from target dp width
        // so the user doesn't have to do the math. In auto-negotiate, C++ handles it.
        val computedTargetLayoutWidthDp: Int
        val effectiveDpi: Int
        if (aaTargetLayoutWidthDp > 0 && !videoAutoNegotiate) {
            // Manual mode with target: compute DPI for the selected tier
            effectiveDpi = maxOf((resW * 160) / aaTargetLayoutWidthDp, 80)
            computedTargetLayoutWidthDp = 0 // C++ doesn't need it — single tier
            OalLog.i(TAG, "Per-tier DPI (manual): ${resW}px / ${aaTargetLayoutWidthDp}dp → DPI $effectiveDpi")
        } else if (aaAutoDpi) {
            // Mirror GM's GALDisplayManager.getScaledDensity:
            //   fWidth = renderRectWidthPx / (codecW - widthMargin)
            //   density = round(panelDpi / fWidth)
            // This makes AA UI elements come out the same physical size as
            // native AAOS apps on the same panel, regardless of:
            //   - panel aspect ratio (margins absorb the AR difference),
            //   - displayMode (renderRect shrinks for system_ui_visible),
            //   - chosen codec resolution (math uses the live codec dims).
            // Falls back to user's [aaDpi] when the renderer hasn't yet
            // reported a render rect (first connect before composition).
            val rrW = renderRectWPx
            val rrH = renderRectHPx
            val pDpi = if (panelDensityDpi > 0) panelDensityDpi else
                ctx.resources.displayMetrics.densityDpi
            // Auto negotiation advertises several codec widths. A single DPI
            // for all of them changes AA's logical width when Gearhead picks a
            // different tier (for example 1440p -> 4K). Preserve the measured
            // panel layout width and let native service discovery derive one
            // density per advertised tier.
            val autoLayoutWidthDp = when {
                aaTargetLayoutWidthDp > 0 -> aaTargetLayoutWidthDp
                videoAutoNegotiate -> AutoDpiPolicy.layoutWidthDp(rrW, pDpi)
                else -> 0
            }
            // Use the inner rect at the picked codec tier so margins are
            // accounted for. Same formula as MarginAutoCalc / C++ side.
            val (autoWm, autoHm) = if (rrW > 0 && rrH > 0) {
                // Use the actual render rect, not the panel rect, so
                // system_ui_visible mode (where the rect is smaller) gives
                // a different DPI than fullscreen.
                com.openautolink.app.video.MarginAutoCalc.compute(resW, resH, rrW, rrH)
            } else 0 to 0
            val innerW = (resW - (if (computedWidthMargin > 0) computedWidthMargin else autoWm)).coerceAtLeast(1)
            val auto = if (rrW > 0 && innerW > 0 && pDpi > 0) {
                val fWidth = rrW.toFloat() / innerW.toFloat()
                if (fWidth > 0f) (pDpi / fWidth).toInt().coerceAtLeast(96) else aaDpi
            } else aaDpi
            effectiveDpi = auto
            computedTargetLayoutWidthDp = if (videoAutoNegotiate && autoLayoutWidthDp > 0) {
                autoLayoutWidthDp
            } else {
                aaTargetLayoutWidthDp
            }
            if (rrW <= 0) {
                // Say so, loudly. Auto-DPI is on but the renderer has not
                // measured yet, so this silently shipped the MANUAL value to the
                // phone and the whole UI came up at the wrong scale. Measured
                // 2026-08-11: the first two sessions after launch sent dpi=175,
                // every later one sent the correct auto value of 131 — and the
                // only difference was whether Compose had run by then.
                OalLog.w(TAG, "Auto-DPI requested but the render rect is not " +
                        "measured yet — falling back to manual $aaDpi. Will " +
                        "re-apply once the renderer reports.")
            } else {
                OalLog.i(TAG, "Auto-DPI: renderRect=${rrW}x${rrH} panelDpi=$pDpi " +
                        "innerW=$innerW → baseDpi=$effectiveDpi " +
                        "perTierLayoutDp=$computedTargetLayoutWidthDp " +
                        "(user manual=$aaDpi ignored)")
            }
        } else {
            // Manual: user picked the DPI; honour exactly.
            effectiveDpi = aaDpi
            computedTargetLayoutWidthDp = aaTargetLayoutWidthDp
            OalLog.i(TAG, "Manual DPI: $effectiveDpi")
        }

        OalLog.i(TAG, "SDR AR config: scalingMode=$scalingMode marginW=$computedWidthMargin marginH=$computedHeightMargin pixelAspectE4=$computedPixelAspect")

        // Panel dims sent to C++ — these drive (a) landscape-vs-portrait
        // codec tier selection in auto-negotiate, and (b) per-tier
        // auto-margin calc.
        //
        // We send the LIVE RENDER RECT here, not the full panel. The
        // renderer (ProjectionScreen Crop mode) uses the same render rect
        // to compute its zoom factor; so by feeding both ends the same
        // rectangle, the codec margin AA bakes in matches what the
        // renderer crops away. In `system_ui_visible` mode this means the
        // C++ side computes margins for the chrome-free rect (e.g.
        // 2914×919), giving AA the right amount of unusable area at the
        // bottom of the codec frame so its dock/status stays visible. In
        // `fullscreen_immersive` mode renderRect equals the panel.
        //
        // Falls back to WindowManager when the renderer hasn't reported
        // yet (first connect before composition); mostly harmless because
        // a reconnect happens on first user action and the render rect is
        // populated by then.
        val (panelW, panelH) = if (renderRectWPx > 0 && renderRectHPx > 0) {
            renderRectWPx to renderRectHPx
        } else try {
            val wm = ctx.getSystemService(android.content.Context.WINDOW_SERVICE) as android.view.WindowManager
            val b = wm.currentWindowMetrics.bounds
            b.width() to b.height()
        } catch (_: Exception) {
            0 to 0
        }
        OalLog.i(TAG, "Panel dims: ${panelW}x${panelH} (mode=$lastDisplayMode)")
        val (protocolTouchW, protocolTouchH) = AutoVideoTouchPolicy.resolve(
            autoNegotiate = videoAutoNegotiate,
            codec = codec,
            panelWidth = panelW,
            panelHeight = panelH,
            selectedWidth = resW,
            selectedHeight = resH,
        )
        OalLog.i(
            TAG,
            "Protocol touchscreen: ${protocolTouchW}x${protocolTouchH} " +
                "autoNeg=$videoAutoNegotiate selected=${resW}x${resH}",
        )

        val session = AasdkSession(scope, ctx)
        session.transportMode = directTransport
        // Effective AA content_insets:
        //  - In `system_ui_visible` mode, the SurfaceView is already inside
        //    the chrome-free area (Compose padding handles it), so AA's
        //    content_insets must be 0 — pushing values would double-shrink
        //    the UI inside an already-shrunk surface. The user's safe-area
        //    pref is preserved in DataStore but ignored in this mode.
        //  - In `fullscreen_immersive` mode the user's pref applies (this
        //    is where curved-corner padding lives, since AAOS doesn't
        //    surface curves through WindowInsets).
        val applyUserSafeArea = lastDisplayMode != "system_ui_visible"
        val effSafeTop = if (applyUserSafeArea) safeAreaTop else 0
        val effSafeBottom = if (applyUserSafeArea) safeAreaBottom else 0
        val effSafeLeft = if (applyUserSafeArea) safeAreaLeft else 0
        val effSafeRight = if (applyUserSafeArea) safeAreaRight else 0
        OalLog.i(TAG, "Effective AA content_insets: top=$effSafeTop bottom=$effSafeBottom " +
                "left=$effSafeLeft right=$effSafeRight (mode=$lastDisplayMode, " +
                "userPref=$safeAreaTop/$safeAreaBottom/$safeAreaLeft/$safeAreaRight)")

        session.manualIpAddress = manualIpAddress
        session.sdrConfig = AasdkSdrConfig(
            videoWidth = resW,
            videoHeight = resH,
            videoFps = videoFps,
            videoDpi = effectiveDpi,
            marginWidth = computedWidthMargin,
            marginHeight = computedHeightMargin,
            pixelAspectE4 = computedPixelAspect,
            btMacAddress = btMac,
            vehicleMake = vd?.carMake ?: "OpenAutoLink",
            vehicleModel = vd?.carModel ?: "Direct",
            vehicleYear = vd?.carYear ?: "2024",
            driverPosition = driverPos,
            hideClock = hideClock,
            hideSignal = hideSignal,
            hideBattery = hideBattery,
            gpsForwarding = gpsForwarding,
            autoNegotiate = videoAutoNegotiate,
            videoCodec = codec,
            galVersion = galVersion,
            // realDensity removed — interferes with pixel_aspect_ratio_e4 on some AA versions
            safeAreaTop = effSafeTop,
            safeAreaBottom = effSafeBottom,
            safeAreaLeft = effSafeLeft,
            safeAreaRight = effSafeRight,
            targetLayoutWidthDp = computedTargetLayoutWidthDp,
            fuelTypes = fuelTypes.map { it }.toIntArray(),
            evConnectorTypes = evConnectorTypes.map { it }.toIntArray(),
            viewingDistanceMm = aaViewingDistanceMm,
            decoderAdditionalDepth = aaDecoderAdditionalDepth,
            panelWidth = panelW,
            panelHeight = panelH,
            autoMargins = aaAutoMargins,
        )
        _touchWidth.value = protocolTouchW
        _touchHeight.value = protocolTouchH
        _effectiveDpi.value = effectiveDpi

        adoptSessionOwnership(session)
        // Same surface guarantee for transport restarts, which reuse the decoder
        // and so never trigger onDecoderCreated.
        // A transport restart can begin a native session without re-running the
        // full setup, so make sure a decoder exists rather than assuming one does.
        // Surface slow wireless startup phases on the projection screen.
        com.openautolink.app.transport.bluetooth.AaWirelessBtControl.onStatus = { msg ->
            if (_sessionState.value != SessionState.STREAMING) {
                _statusMessage.value = msg
            }
        }
        session.onNativeSessionStarting = {
            prepareNativeSessionStart(session)
        }
        com.openautolink.app.transport.bluetooth.AaWirelessBtControl.sessionIsStreaming = {
            sessionState.value == SessionState.STREAMING
        }

        // Let the Bluetooth handshake dial the companion the instant it selects
        // the loopback endpoint. Waiting until session start is too early — the
        // companion's address is not known yet — and any later is too late, since
        // Android Auto connects to the proxy within about two seconds of the
        // handshake completing.
        com.openautolink.app.transport.bluetooth.AaWirelessBtControl.onCompanionSelected =
            { ip -> if (ip.isNotBlank()) session.dialCompanion(ip) }
        // Answer the phone's SensorStartRequest with current vehicle state.
        // Without this, a parked car never sends driving status and the phone
        // stays FULLY_RESTRICTED (no Maps keyboard). Issue #61.
        session.onSensorSubscribedListener = { type -> onPhoneSubscribedSensor(type) }
        // Reset the mirrored reconnect counter at session boundary. The
        // per-session collector below will republish updates as they fire.
        _reconnectAttempt.value = 0

        // Observe session state
        scope.launch {
            session.connectionState.collect { connState ->
                val reportedState = connState.toSessionState()
                val attempt = _reconnectAttempt.value
                synchronized(sessionStateLock) {
                    if (aasdkSession !== session) return@synchronized
                    val currentState = _sessionState.value
                    val startStreamingServices = shouldStartStreamingServices(
                        currentState,
                        reportedState,
                    )
                    reconcileTransportSessionState(currentState, reportedState).also {
                        _sessionState.value = it
                        _statusMessage.value = when (it) {
                            SessionState.IDLE ->
                                if (attempt > 0) "Reconnecting (attempt $attempt)…"
                                else when (directTransport) {
                                    "usb" -> "USB: ${UsbConnectionManager.status.value}"
                                    else -> "Searching for phone…"
                                }
                            SessionState.CONNECTING ->
                                if (attempt > 0) "Reconnecting (attempt $attempt)…"
                                else "Phone connecting..."
                            SessionState.CONNECTED -> "Handshake..."
                            SessionState.STREAMING -> "Streaming"
                            SessionState.ERROR ->
                                if (attempt > 0) "Reconnecting (attempt $attempt)…"
                                else "Error"
                        }
                    }
                    if (startStreamingServices) startStreamingServicesLocked(session)
                }
            }
        }

        bindSessionCollectors(session)

        // Observe USB status (only in usb mode)
        if (directTransport == "usb") {
            scope.launch {
                UsbConnectionManager.status.collect { usbStatus ->
                    if (_sessionState.value == SessionState.IDLE) {
                        _statusMessage.value = "USB: $usbStatus"
                    }
                }
            }
        }

        session.start()
        // Publish SDP only after this exact protocol owner has installed all
        // callbacks and bound its transport. This closes the cold-start and
        // USB→WPP preference races without delaying native negotiation itself.
        val ownerToken = installWirelessSessionAdmission(directTransport)
        wppRearmSource?.let { source ->
            val ownerInstalled = AaWirelessBtControl.isCurrentSessionOwner(ownerToken)
            val message = "WPP rearm outcome: source=$source " +
                "ownerInstalled=$ownerInstalled"
            if (ownerInstalled) OalLog.i(TAG, message) else OalLog.e(TAG, message)
        }
        com.openautolink.app.wake.PreWakeMonitor.reportSessionReady("admission-ready")
        OalLog.i(TAG, "aasdk JNI session started ($directTransport transport)")
    }

    private fun prepareNativeSessionStart(session: AasdkSession): Boolean {
        val admitted = synchronized(sessionStateLock) {
            if (retiringAasdkSession === session) {
                false
            } else {
                if (aasdkSession !== session) {
                    OalLog.i(TAG, "Re-adopting the session after a transport restart — " +
                            "without this, touch input and sensor data are silently dropped")
                }
                adoptSessionOwnership(session)
                true
            }
        }
        if (!admitted) {
            OalLog.i(TAG, "Rejecting native start while this session is retiring")
            return false
        }

        ensureVideoDecoder()
        ensureAudioPlayer()
        bindSessionCollectors(session)
        OalLog.i(TAG, "Native session dependencies ready: " +
                "decoder=${_videoDecoder != null} surface=${lastKnownSurface != null} " +
                "audio=${_audioPlayer != null} session=current collectors=bound")
        return true
    }

    /** Must be called while holding [sessionStateLock] for the exact current session. */
    private fun startStreamingServicesLocked(session: AasdkSession) {
        startLocationForwarding(session)
        _vehicleDataForwarder?.start()
        _imuForwarder?.start()
    }

    @android.annotation.SuppressLint("MissingPermission")
    private fun startLocationForwarding(session: AasdkSession) {
        stopDirectLocationForwarding()
        if (!gpsForwardingEnabled) {
            OalLog.i(TAG, "GPS forwarding disabled by setting — phone will use its own location")
            return
        }
        val ctx = context ?: return
        val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as? android.location.LocationManager ?: return
        if (!lm.isProviderEnabled(android.location.LocationManager.GPS_PROVIDER)) {
            OalLog.w(TAG, "GPS provider not enabled")
            return
        }

        val listener = android.location.LocationListener { location ->
            session.sendGpsLocation(
                location.latitude, location.longitude, location.altitude,
                location.speed, location.bearing, location.time
            )
        }
        try {
            lm.requestLocationUpdates(
                android.location.LocationManager.GPS_PROVIDER,
                500L, 0f, listener, android.os.Looper.getMainLooper(),
            )
            _directLocationListener = listener
            OalLog.i(TAG, "GPS forwarding started")
        } catch (e: SecurityException) {
            OalLog.w(TAG, "GPS permission denied: ${e.message}")
        }
    }

    private fun stopDirectLocationForwarding() {
        _directLocationListener?.let { listener ->
            val ctx = context ?: return
            val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as? android.location.LocationManager
            lm?.removeUpdates(listener)
        }
        _directLocationListener = null
    }

    /**
     * Tell the phone we are going away, then stop.
     *
     * Sends the Android Auto protocol ByeBye so the phone treats this as a clean,
     * expected teardown instead of waiting out its ~9s ping timeout and then
     * assuming it drove out of range. Use for EXPECTED disconnects — ignition
     * off, user exit — never for error paths, where the link is already gone.
     *
     * The native call returns immediately and completes the transport teardown on
     * its own worker once the ByeBye is acknowledged (or [timeoutMs] elapses), so
     * we must NOT call [stop] synchronously here — that would close the socket out
     * from under the frame we just queued and defeat the whole point. Instead we
     * let the ByeBye settle, then run the Kotlin-side cleanup (decoders, audio,
     * forwarders), which is safe to do after the native session has gone.
     */
    fun shutdownGracefully(reason: String, timeoutMs: Int = BYEBYE_TIMEOUT_MS) {
        val dispatched = runCatching {
            aasdkSession?.shutdownGracefully(reason, timeoutMs) ?: error("no active session")
        }.onFailure {
            OalLog.w(TAG, "ByeBye dispatch failed (${it.message}) — stopping immediately")
        }.isSuccess

        if (!dispatched) {
            scope.launch { stop() }
            return
        }

        scope.launch {
            // Give the ByeBye its bounded window to flush and be acknowledged.
            // The native watchdog uses the same budget, so by the time this
            // returns the native session has already torn itself down.
            delay(timeoutMs.toLong())
            stop()
        }
    }

    /**
     * Dial a companion at [ip] on the live session, without restarting it.
     *
     * Used by the phone picker in WPP mode: the user tapping a phone must be able
     * to recover a stuck session, and restarting would destroy the socket the
     * phone is about to connect to.
     */
    fun dialCompanionNow(ip: String) {
        val session = aasdkSession
        if (session == null) {
            OalLog.w(TAG, "No active session — cannot dial $ip")
            return
        }
        session.dialCompanion(ip)
    }

    suspend fun stop() {
        startMutex.lock()
        try {
            kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                lifecycleGeneration += 1L
                stopWhileLifecycleLocked()
            }
        } finally {
            startMutex.unlock()
        }
    }

    private suspend fun stopWhileLifecycleLocked(cancelObserveJob: Boolean = true) {
        clearWirelessSessionAdmission()
        // Let another phone claim the session. Without this the first phone to
        // dial holds it until the app restarts, so a second phone in the car can
        // never connect even after the first one leaves.
        com.openautolink.app.transport.bluetooth.AaWirelessBtControl.releaseActivePhone()

        unregisterScreenReceiver()
        unregisterDebugReceiver()
        if (cancelObserveJob) observeJob?.cancelAndJoin()
        observeJob = null
        decoderWatchJob?.cancelAndJoin()
        decoderWatchJob = null
        keyframeWatchJob?.cancelAndJoin()
        keyframeWatchJob = null
        videoStallWatchJob?.cancelAndJoin()
        videoStallWatchJob = null
        callStateJob?.cancelAndJoin()
        callStateJob = null
        // Revoke ownership before cancelling collectors or stopping retained
        // producers. A PhoneConnected callback already executing cannot be
        // cancelled mid-body; the shared lock makes it either finish its starts
        // before this teardown (which then stops them), or observe a stale owner
        // and do nothing afterward.
        val retiringSession = synchronized(sessionStateLock) {
            val current = revokeSessionOwnershipLocked()
            _sessionState.value = SessionState.IDLE
            _statusMessage.value = "Disconnected"
            current
        }
        sessionCollectors?.cancel()
        sessionCollectors = null
        stopRetiringSession(retiringSession)
        stopDirectLocationForwarding()
        _videoDecoder?.release()
        _videoDecoder = null
        _audioPlayer?.release()
        _audioPlayer = null
        _micCaptureManager?.release()
        _micCaptureManager = null
        _hfpPresence?.stop()
        _hfpPresence = null
        _gnssForwarder?.stop()
        _gnssForwarder = null
        // Preserve the stopped VHAL owner and its last complete EV snapshot across
        // ignition sleep. The native wake path restarts this same owner before the
        // phone's type-23 subscription, so Maps receives current battery data even
        // when the AAOS process survives and full start() is bypassed.
        _vehicleDataForwarder?.stop()
        _imuForwarder?.stop()
        _imuForwarder = null
        forecastExpiryJob?.cancel()
        forecastExpiryJob = null
        _vehicleEnergyForecast.value = null
        _navigationDisplay.clear()
        ClusterNavigationState.clear()
        // Do NOT release the MediaSession — it's the process-wide singleton and
        // the GM cluster is bound to its token. Just reset the now-playing tile
        // to idle so we don't keep showing the disconnected phone's track. The
        // reference is left intact; the next start() reuses the same singleton.
        _mediaSessionManager?.resetToIdle()
        lastMediaMetadata = null
        lastMediaPlaybackState = null
        _clusterManager?.release()
        _clusterManager = null
        _telemetryCollector?.stop()
        _telemetryCollector = null
        DiagnosticLog.instance = null
        _remoteDiagnostics = null
        _phoneBatteryLevel.value = null
        _phoneBatteryCritical.value = false
        _voiceSessionActive.value = false
        _phoneSignalStrength.value = null
    }

    /**
     * Reconnect the AA session with new SDR/settings without tearing down
     * component islands (audio, mic, GNSS, VHAL, IMU, cluster, diagnostics).
     *
     * Only the AA protocol session is restarted — the phone will reconnect
     * and renegotiate SDR with the new parameters. File logging, TCP log
     * streaming, telemetry, and all sensor forwarders stay alive.
     *
     * Falls back to full [start] if component islands haven't been initialized yet.
     */
    suspend fun reconnect(
        codecPreference: String = "h264",
        micSourcePreference: String = "car",
        scalingMode: String = "letterbox",
        directTransport: String = "hotspot",
        hotspotSsid: String = "",
        hotspotPassword: String = "",
        videoAutoNegotiate: Boolean = true,
        aaResolution: String = "1080p",
        aaDpi: Int = 160,
        aaAutoDpi: Boolean = true,
        aaWidthMargin: Int = 0,
        aaHeightMargin: Int = 0,
        aaPixelAspect: Int = -1,
        aaTargetLayoutWidthDp: Int = 0,
        aaViewingDistanceMm: Int = 0,
        aaDecoderAdditionalDepth: Int = 0,
        aaAutoMargins: Boolean = true,
        videoFps: Int = 60,
        driveSide: String = "left",
        hideClock: Boolean = false,
        hideSignal: Boolean = false,
        hideBattery: Boolean = false,
        volumeOffsetMedia: Int = 0,
        volumeOffsetNavigation: Int = 0,
        volumeOffsetAssistant: Int = 0,
        manualIpAddress: String? = null,
        safeAreaTop: Int = 0,
        safeAreaBottom: Int = 0,
        safeAreaLeft: Int = 0,
        safeAreaRight: Int = 0,
        gpsForwarding: Boolean = true,
        galVersion: String = AppPreferences.DEFAULT_GAL_VERSION,
    ) {
        // "Save & Reconnect" from Settings doesn't know the resolved Car
        // Hotspot IP — fall back to the last value we successfully used so
        // TcpConnector doesn't drop to mDNS-only mode and stall.
        val effectiveManualIp = manualIpAddress?.takeIf { it.isNotBlank() }
            ?: _lastManualIpAddress
        if (!effectiveManualIp.isNullOrBlank()) _lastManualIpAddress = effectiveManualIp

        // If islands were never initialized, do a full start
        if (_audioPlayer == null) {
            start(
                codecPreference, micSourcePreference, scalingMode, directTransport,
                hotspotSsid, hotspotPassword, videoAutoNegotiate, aaResolution,
                aaDpi, aaAutoDpi, aaWidthMargin, aaHeightMargin, aaPixelAspect, aaTargetLayoutWidthDp,
                aaViewingDistanceMm, aaDecoderAdditionalDepth, aaAutoMargins, videoFps,
                driveSide, hideClock, hideSignal, hideBattery,
                volumeOffsetMedia, volumeOffsetNavigation, volumeOffsetAssistant,
                effectiveManualIp, safeAreaTop, safeAreaBottom, safeAreaLeft, safeAreaRight,
                gpsForwarding, galVersion,
            )
            return
        }

        OalLog.i(TAG, "Reconnect requested")
        // Reentrancy guard: rapid Save&Reconnect taps used to fire 20+ in <30ms.
        //
        // Self-expiring: the `finally` that clears it can be skipped if the
        // coroutine is cancelled, and a latched guard would make Save & Reconnect
        // permanently dead — the same failure that took the advertiser out for
        // eight hours on 2026-08-10.
        if (reconnectInProgress &&
            reconnectStartedAt != 0L &&
            System.currentTimeMillis() - reconnectStartedAt > RECONNECT_MAX_MS
        ) {
            OalLog.w(TAG, "reconnect() has been 'in progress' for " +
                    "${(System.currentTimeMillis() - reconnectStartedAt) / 1000}s — " +
                    "assuming it died and allowing a new one")
            reconnectInProgress = false
        }
        if (reconnectInProgress) {
            OalLog.w(TAG, "reconnect() already in progress — ignoring duplicate call")
            return
        }
        // Never restart while a Bluetooth handshake is in flight. In WPP mode the
        // session owns the socket the phone is about to connect to, and a restart
        // destroys it. Observed in-vehicle:
        //     13:38:16.806  Phone dialled back on the AA Wireless UUID
        //     13:38:16.956  Reconnect requested
        //     13:38:16.959  WPP TCP server stopped
        // 150ms after the phone dialled, the listener it was about to use was gone.
        if (com.openautolink.app.transport.bluetooth.AaWirelessBtControl.handshakeInFlight) {
            OalLog.w(TAG, "reconnect() deferred — a Bluetooth handshake is in flight and " +
                    "restarting now would destroy the socket the phone is about to use")
            return
        }
        reconnectInProgress = true
        reconnectStartedAt = System.currentTimeMillis()
        val requestGeneration = lifecycleGeneration
        var lifecycleLockAcquired = false
        try {
            startMutex.lock()
            lifecycleLockAcquired = true
            kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
            if (requestGeneration != lifecycleGeneration) {
                OalLog.i(TAG, "Reconnect request rejected — lifecycle stop completed while waiting")
                return@withContext
            }
        gpsForwardingEnabled = gpsForwarding
        OalLog.i(TAG, "Reconnecting AA session with new settings (minimal restart)")
        micSource = micSourcePreference

        // 1. Cancel old session observer coroutines (await cancellation so the
        // new keyframeWatchJob doesn't race with the old one — the spam of
        // 20+ "Keyframe re-request #2" lines came from this exact race).
        // Run on IO so we don't block the Main thread (causes ANR — we wait on
        // cancelAndJoin and then aasdkSession.stop() which JNI-joins the io_thread).
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            try {
                // Say goodbye before tearing down, whenever a session exists.
                //
                // Without it the phone still believes the old session is live, so
                // the car's next dial is refused, its session dies, and the
                // Bluetooth advertiser re-handshakes — about 60 handshakes in 55
                // seconds in one log, and the car never reconnected.
                //
                // Deliberately not gated on whether video is flowing: a session
                // that exists at all is one the phone has state for.
                if (aasdkSession != null && sessionState.value != SessionState.IDLE) {
                    OalLog.i(TAG, "Reconnect — sending ByeBye so the phone tears down cleanly")
                    runCatching {
                        aasdkSession?.shutdownGracefully("reconnect", BYEBYE_TIMEOUT_MS)
                    }
                    // Give the ByeBye its window before we pull the transport out.
                    kotlinx.coroutines.delay(BYEBYE_TIMEOUT_MS.toLong())
                }
                try {
                    observeJob?.cancelAndJoin()
                    decoderWatchJob?.cancelAndJoin()
                    keyframeWatchJob?.cancelAndJoin()
                    videoStallWatchJob?.cancelAndJoin()
                    callStateJob?.cancelAndJoin()
                } catch (_: Exception) {}
                doReconnectAfterCancel(
                    codecPreference, micSourcePreference, scalingMode, directTransport,
                    hotspotSsid, hotspotPassword, videoAutoNegotiate, aaResolution,
                    aaDpi, aaAutoDpi, aaWidthMargin, aaHeightMargin, aaPixelAspect, aaTargetLayoutWidthDp,
                    aaViewingDistanceMm, aaDecoderAdditionalDepth, aaAutoMargins,
                    videoFps, driveSide, hideClock, hideSignal, hideBattery,
                    volumeOffsetMedia, volumeOffsetNavigation, volumeOffsetAssistant,
                    effectiveManualIp, safeAreaTop, safeAreaBottom, safeAreaLeft, safeAreaRight,
                    gpsForwarding, galVersion,
                )
            } catch (e: Exception) {
                OalLog.e(TAG, "reconnect() failed: ${e.message}")
                rollbackLifecycleFailure("reconnect", e)
            }
        }
        }
        } finally {
            // Always clear the guard so a future reconnect attempt can run.
            reconnectInProgress = false
            reconnectStartedAt = 0L
            if (lifecycleLockAcquired) startMutex.unlock()
        }
    }

    private suspend fun doReconnectAfterCancel(
        codecPreference: String, micSourcePreference: String, scalingMode: String,
        directTransport: String, hotspotSsid: String, hotspotPassword: String,
        videoAutoNegotiate: Boolean, aaResolution: String, aaDpi: Int,
        aaAutoDpi: Boolean,
        aaWidthMargin: Int, aaHeightMargin: Int, aaPixelAspect: Int,
        aaTargetLayoutWidthDp: Int,
        aaViewingDistanceMm: Int, aaDecoderAdditionalDepth: Int,
        aaAutoMargins: Boolean,
        videoFps: Int, driveSide: String,
        hideClock: Boolean, hideSignal: Boolean, hideBattery: Boolean,
        volumeOffsetMedia: Int, volumeOffsetNavigation: Int, volumeOffsetAssistant: Int,
        manualIpAddress: String?,
        safeAreaTop: Int, safeAreaBottom: Int, safeAreaLeft: Int, safeAreaRight: Int,
        gpsForwarding: Boolean,
        galVersion: String,
    ) {
        observeJob = null
        decoderWatchJob = null
        keyframeWatchJob = null
        videoStallWatchJob = null
        callStateJob = null

        // 2. Stop old AA session + location forwarding. Revoke SDP first so
        // the phone cannot enter through callbacks owned by the retiring session.
        clearWirelessSessionAdmission()
        val retiringSession = synchronized(sessionStateLock) {
            revokeSessionOwnershipLocked()
        }
        stopRetiringSession(retiringSession)
        stopDirectLocationForwarding()

        // 3. Flush video decoder (codec/scaling may have changed)
        _videoDecoder?.release()
        _videoDecoder = createVideoDecoder(codecPreference, scalingMode)
        _telemetryCollector?.videoDecoder = _videoDecoder

        // 4. Update audio volume offsets in-place (no release/recreate)
        (_audioPlayer as? AudioPlayerImpl)?.coordinator?.let { coord ->
            coord.volumeOffsetMedia = volumeOffsetMedia
            coord.volumeOffsetNavigation = volumeOffsetNavigation
            coord.volumeOffsetAssistant = volumeOffsetAssistant
        }

        // 5. Pause sensor forwarders — they'll restart when new session reaches STREAMING
        _vehicleDataForwarder?.stop()
        _imuForwarder?.stop()

        // 6. Clear stale navigation state
        _navigationDisplay.clear()
        ClusterNavigationState.clear()

        // 7. Update status — don't go to IDLE, just show reconnecting
        _statusMessage.value = "Reconnecting..."
        _phoneBatteryLevel.value = null
        _phoneBatteryCritical.value = false
        _voiceSessionActive.value = false
        _phoneSignalStrength.value = null

        // 8. Start the replacement protocol session synchronously while the
        // lifecycle mutex is held. Only after its exact owner is installed do
        // we publish the long-lived watcher job.
        startSession(directTransport, hotspotSsid, hotspotPassword,
            videoAutoNegotiate, codecPreference, aaResolution, aaDpi, aaAutoDpi,
            aaWidthMargin, aaHeightMargin, aaPixelAspect, aaTargetLayoutWidthDp,
            aaViewingDistanceMm, aaDecoderAdditionalDepth, aaAutoMargins,
            videoFps,
            driveSide, hideClock, hideSignal, hideBattery, scalingMode,
            manualIpAddress,
            safeAreaTop, safeAreaBottom, safeAreaLeft, safeAreaRight,
            gpsForwarding, galVersion)

        observeJob = scope.launch {
            decoderWatchJob = launch { watchDecoderState() }
            keyframeWatchJob = launch { watchKeyframeNeeds() }
            // IO, not Main. This watchdog calls into the native session
            // (requestKeyframe -> nativeRequestKeyframe), and SessionManager's
            // scope is Dispatchers.Main. Measured 2026-08-11 09:13:44: the
            // watchdog escalated to forceReconnect, which began tearing the
            // native session down on IO, and the main thread's next native call
            // blocked behind that teardown for 16.4 seconds — ANR, tombstone,
            // app restart. Nothing on the main thread should enter JNI.
            videoStallWatchJob = launch(kotlinx.coroutines.Dispatchers.IO) { watchVideoStall() }
            callStateJob = launch { watchCallState() }
        }

        // 9. Re-establish cluster binding — GM Templates Host may have killed
        // the session during sleep/suspend
        _clusterManager?.ensureAlive()

        // 10. Re-push MediaSession token — GM's media widget may have lost
        // the binding during sleep
        _mediaSessionManager?.getSessionToken()?.let { token ->
            OalMediaBrowserService.updateSessionToken(token)
        }
    }

    /**
     * Called from [MainActivity.onResume]. Belt-and-suspenders alongside the
     * SCREEN_ON broadcast receiver registered in [registerScreenReceiver] —
     * both call into [markWake], which dedupes via [WAKE_DEDUPE_MS] so only
     * one wake handler runs per real wake regardless of source.
     */
    fun onSystemWake() {
        markWake("activity_resume")
    }

    /**
     * Called from [MainActivity.onPause]. Mirrors [onSystemWake] on the sleep
     * side — both this and the SCREEN_OFF broadcast call [markGoingIdle],
     * which freezes [lastActiveTimestamp] so the gap on the next wake is
     * measured from this point rather than the last incoming control message.
     */
    fun onActivityPaused() {
        markGoingIdle("activity_pause")
    }

    /**
     * Called from [MainActivity.onConfigurationChanged] when the system
     * UI night-mode flag flips. On real GM hardware, the VHAL NIGHT_MODE
     * property drives the AA theme via [VehicleDataForwarder]; this hook
     * covers AAOS images (and emulators) where the head unit theme is
     * driven by [Configuration.uiMode] rather than VHAL.
     *
     * Always forwards — does NOT dedupe against [lastSentNightMode]. The
     * VHAL path emits its (possibly stale) value every ~100ms and would
     * otherwise reset [lastSentNightMode] between our UI events, silently
     * dropping the back-flip. UI changes are discrete user actions, so an
     * occasional duplicate event to the phone is harmless.
     */
    fun onUiNightModeChanged(night: Boolean) {
        lastKnownUiNightMode = night
        val session = aasdkSession ?: return
        lastSentNightMode = night
        OalLog.i(TAG, "UI night mode → $night (forwarding to phone)")
        // Off the caller's thread: MainActivity calls this directly from
        // onCreate/onResume, so sendNightMode() — a blocking JNI call — ran on
        // the UI thread. Two archived ANRs have a night-mode line immediately
        // before the last main-thread entry.
        scope.launch { session.sendNightMode(night) }
    }

    /**
     * Single entry point for "the system just woke up". Called from multiple
     * sources (Activity.onResume + SCREEN_ON / USER_PRESENT broadcasts);
     * deduplicated via [WAKE_DEDUPE_MS] so a second source firing within a
     * few seconds is a no-op.
     *
     * Behavior:
     *  - Emit a [WakeEvent] on [wakeEvents] for observers (e.g. ProjectionViewModel
     *    clears its in-memory active phone on a long gap).
     *  - Re-assert cluster binding + MediaSession token (cheap; GM cluster can
     *    drop these during suspend).
     *  - If the gap is "long" and we were not IDLE before sleep, force a clean
     *    reconnect — the TCP socket is almost certainly dead from the suspend.
     */
    fun markWake(reason: String) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastWakeHandledAtMs < WAKE_DEDUPE_MS) {
            OalLog.d(TAG, "Wake dedup (reason=$reason within ${WAKE_DEDUPE_MS}ms)")
            return
        }
        val gap = now - lastActiveTimestamp
        lastWakeHandledAtMs = now
        lastActiveTimestamp = now
        isGoingIdle = false

        val gapStr = formatGap(gap)
        OalLog.i(TAG, "Wake: reason=$reason gap=$gapStr")
        DiagnosticLog.i("transport", "Wake: $reason gap=$gapStr")

        _wakeEvents.tryEmit(WakeEvent(reason, gap))

        // Re-establish cluster + MediaSession bindings — GM Templates Host can
        // drop these during suspend even when our process survives.
        _clusterManager?.ensureAlive()
        _mediaSessionManager?.getSessionToken()?.let { token ->
            OalMediaBrowserService.updateSessionToken(token)
        }

        // A long gap USUALLY means the socket died while the head unit slept, so
        // reconnecting beats waiting for the keepalive watchdog.
        //
        // But elapsed time alone cannot tell "the car was powered down" from "the
        // user looked at another app for a minute" — backgrounding stops our
        // Activity, not the socket, the companion, or the phone. Keying on the
        // gap alone tore down a perfectly healthy session:
        //
        //     00:45:41.532  vflow frames=42 ~822kbps      <- streaming fine
        //     00:44:48.901  Going idle: activity_pause    (user left, 53s)
        //     00:45:42.027  Long wake gap (53s) — forcing clean reconnect
        //
        // Recent video frames are direct evidence the transport survived, so ask
        // the data rather than the clock. If frames stopped, the gap reading was
        // right and we reconnect as before.
        val sinceFrame = SystemClock.elapsedRealtime() - lastVideoFrameArrivedMs
        val transportLooksAlive = lastVideoFrameArrivedMs > 0 &&
            sinceFrame < TRANSPORT_ALIVE_FRAME_WINDOW_MS
        if (gap > LONG_WAKE_FORCE_RECONNECT_MS &&
            _sessionState.value != SessionState.IDLE) {
            if (transportLooksAlive) {
                OalLog.i(TAG, "Long wake gap ($gapStr) but video arrived " +
                        "${sinceFrame}ms ago — transport is alive, keeping the session")
            } else {
                OalLog.w(TAG, "Long wake gap ($gapStr), no video for ${sinceFrame}ms " +
                        "— forcing clean reconnect")
                scope.launch(kotlinx.coroutines.Dispatchers.IO) {
                    aasdkSession?.forceReconnect("wake gap $gapStr")
                }
            }
        }
    }

    /**
     * Single entry point for "system is going idle." Called from
     * Activity.onPause and SCREEN_OFF. Freezes [lastActiveTimestamp] so the
     * gap measured at the next wake reflects time-since-going-idle, not
     * time-since-last-control-message (the latter can be many seconds stale
     * during steady streaming).
     */
    fun markGoingIdle(reason: String) {
        lastActiveTimestamp = SystemClock.elapsedRealtime()
        isGoingIdle = true
        OalLog.i(TAG, "Going idle: $reason")
        DiagnosticLog.i("transport", "Going idle: $reason")
    }

    /**
     * Debug-only simulation of an AAOS car sleep → wake cycle.
     *
     * Drives the same code paths a real car sleep does:
     *   1. markGoingIdle (publishes the idle timestamp used to compute the
     *      next wake gap).
     *   2. Stop the live aasdkSession with explicitStop=true so the
     *      AasdkSession auto-reconnect loop does NOT fire during the
     *      simulated sleep window. This leaves SessionState at IDLE just
     *      like a real network loss would.
     *   3. Wait [durationMs] real wall-clock seconds.
     *   4. markWake → the WakeEvent flow fires with gap≈durationMs. The
     *      ProjectionViewModel wake collector picks it up and (when
     *      conditions match: Car Hotspot mode, default set, resolved phone
     *      in discovery, idle) kicks connect(). This is exactly the same
     *      auto-reconnect path real car-wake uses.
     *
     * Trigger from adb:
     *   adb shell am broadcast \
     *     -a com.openautolink.app.DEBUG_SIMULATE_SLEEP \
     *     --el duration_ms 60000 \
     *     com.openautolink.app
     */
    fun debugSimulateSleep(durationMs: Long) {
        OalLog.i(TAG, "DEBUG: simulating car sleep for ${formatGap(durationMs)}")
        markGoingIdle("debug_sleep_sim")
        // Force a clean shutdown with explicitStop=true (AasdkSession.stop()
        // sets that), so the AA session's own retry loop doesn't fire during
        // the sleep window. The wake side will reconnect via the
        // ProjectionViewModel wake collector.
        val retiringSession = synchronized(sessionStateLock) {
            revokeSessionOwnershipLocked()
        }
        stopRetiringSession(retiringSession)
        scope.launch {
            kotlinx.coroutines.delay(durationMs)
            OalLog.i(TAG, "DEBUG: simulating car wake (after ${formatGap(durationMs)})")
            markWake("debug_wake_sim")
        }
    }

    /**
     * Reproduces a full ignition cycle without turning the car off.
     *
     * The existing sleep simulation only stops the session, which exercises none
     * of the parts that actually break. A real cycle also tears down Bluetooth,
     * loses the access point, and forces the advertiser to republish — and every
     * failure in this area has been in that sequence, not in the session stop.
     *
     * So this runs the same steps ignition-off does, in the same order:
     *   1. graceful shutdown (ByeBye to the phone)
     *   2. reset for the next ignition: tell the companion, clear stale ports
     *   3. stop the Bluetooth advertiser, as losing the radio would
     *   4. wait, so the phone's state times out the way it does in a real park
     *   5. bring the advertiser back, exactly as the ignition-ON path does
     *
     * Doing this in the driveway turns a one-per-drive experiment into a
     * repeatable thirty-second test.
     */
    fun debugSimulateIgnitionCycle(offDurationMs: Long = 45_000) {
        OalLog.i(TAG, "DEBUG: simulating an ignition cycle — off for ${formatGap(offDurationMs)}")
        scope.launch {
            runCatching {
                OalLog.i(TAG, "DEBUG ignition sim: step 1 — graceful shutdown")
                if (sessionState.value != SessionState.IDLE) {
                    shutdownGracefully("debug_ignition_sim")
                    kotlinx.coroutines.delay(BYEBYE_TIMEOUT_MS.toLong() + 200)
                }

                OalLog.i(TAG, "DEBUG ignition sim: step 2 — reset for next ignition")
                com.openautolink.app.transport.bluetooth.AaWirelessBtControl
                    .resetForNextIgnition()

                OalLog.i(TAG, "DEBUG ignition sim: step 3 — stopping the Bluetooth advertiser")
                com.openautolink.app.transport.bluetooth.AaWirelessBtControl.stopAdvertising()

                OalLog.i(TAG, "DEBUG ignition sim: step 4 — off for ${formatGap(offDurationMs)}")
                markGoingIdle("debug_ignition_sim")
                // Hold the session down for the whole window.
                //
                // markGoingIdle() alone is not enough: the app stays fully awake,
                // so the ordinary reconnect logic fired 23s into the "off" period,
                // dialled the companion and started a native session while the
                // simulation still believed the car was off. markWake() then saw a
                // 45s gap and force-reconnected, tearing down the session that had
                // just come up. The simulation was breaking the thing it was
                // supposed to be testing.
                //
                // A real ignition cycle does not have this problem because AAOS
                // actually suspends the app.
                com.openautolink.app.transport.bluetooth.AaWirelessBtControl
                    .simulatedIgnitionOff = true
                stop()
                kotlinx.coroutines.delay(offDurationMs)
                com.openautolink.app.transport.bluetooth.AaWirelessBtControl
                    .simulatedIgnitionOff = false

                OalLog.i(TAG, "DEBUG ignition sim: step 5 — ignition back on")
                // Deliberately NOT markWake(): its long-gap handler force-
                // reconnects, which is right after a real sleep but here would
                // just tear down whatever the advertiser is about to establish.
                com.openautolink.app.transport.bluetooth.AaWirelessBtControl.ensureAdvertising()
                OalLog.i(TAG, "DEBUG ignition sim: complete — watching for reconnect")
                OalLog.i(TAG, "DEBUG ignition sim: NOTE — this cannot drop the head " +
                        "unit's Bluetooth radio (BluetoothAdapter.disable() is a " +
                        "system API), so the phone's link was never broken and it " +
                        "has no reason to re-dial on its own. Use this to exercise " +
                        "teardown and endpoint recovery; a real ignition cycle is " +
                        "still the only way to test the phone re-dialling.")
            }.onFailure {
                OalLog.e(TAG, "DEBUG ignition sim failed: ${it.message}")
            }
            // Always clear it. A flag that suppresses reconnects and is only
            // cleared on the happy path would leave wireless dead until the app
            // restarts — the same latch shape that cost a session earlier.
            com.openautolink.app.transport.bluetooth.AaWirelessBtControl
                .simulatedIgnitionOff = false
        }
    }

    private fun formatGap(ms: Long): String = when {
        ms < 1_000 -> "${ms}ms"
        ms < 60_000 -> "${ms / 1000}s"
        ms < 3_600_000 -> "${ms / 60_000}m${(ms % 60_000) / 1000}s"
        else -> "${ms / 3_600_000}h${(ms % 3_600_000) / 60_000}m"
    }

    /**
     * Register a receiver for ACTION_SCREEN_OFF / _ON / USER_PRESENT. These
     * broadcasts are not reliably delivered on every AAOS build (empirical:
     * never observed firing on the 2024 Blazer EV during driving sessions),
     * so the Activity.onResume / onPause callbacks in [MainActivity] are the
     * primary signal. This receiver is belt-and-suspenders: when broadcasts
     * do fire, [markWake]'s dedupe ensures we don't run the handler twice.
     */
    private fun registerScreenReceiver() {
        if (screenReceiver != null) return
        val ctx = context ?: return
        val r = object : android.content.BroadcastReceiver() {
            override fun onReceive(c: android.content.Context?, intent: android.content.Intent?) {
                when (intent?.action) {
                    android.content.Intent.ACTION_SCREEN_OFF ->
                        markGoingIdle("screen_off")
                    android.content.Intent.ACTION_SCREEN_ON,
                    android.content.Intent.ACTION_USER_PRESENT ->
                        markWake(intent.action ?: "screen_on")
                }
            }
        }
        val filter = android.content.IntentFilter().apply {
            addAction(android.content.Intent.ACTION_SCREEN_OFF)
            addAction(android.content.Intent.ACTION_SCREEN_ON)
            addAction(android.content.Intent.ACTION_USER_PRESENT)
        }
        try {
            // SCREEN_OFF / SCREEN_ON / USER_PRESENT are protected system
            // broadcasts so RECEIVER_NOT_EXPORTED is safe and required on
            // Android 14+ (target SDK 34+).
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                ctx.registerReceiver(r, filter, android.content.Context.RECEIVER_NOT_EXPORTED)
            } else {
                @Suppress("UnspecifiedRegisterReceiverFlag")
                ctx.registerReceiver(r, filter)
            }
            screenReceiver = r
            OalLog.i(TAG, "Screen on/off receiver registered")
        } catch (e: Exception) {
            OalLog.w(TAG, "Screen receiver registration failed: ${e.message}")
        }
    }

    private fun unregisterScreenReceiver() {
        val ctx = context ?: return
        screenReceiver?.let {
            try { ctx.unregisterReceiver(it) } catch (_: Exception) {}
        }
        screenReceiver = null
    }

    // ------------------------------------------------------------------
    // Debug-only: car sleep/wake simulation via broadcast.
    //
    // Trigger from a host running adb:
    //   adb shell am broadcast \
    //     -a com.openautolink.app.DEBUG_SIMULATE_SLEEP \
    //     --el duration_ms 60000 \
    //     com.openautolink.app
    //
    // Debug-only integration controls. Release builds never register this
    // receiver. Debug builds keep it exported so adb shell can drive the
    // ignition and phone-injection scenarios.
    // ------------------------------------------------------------------
    private var debugSleepReceiver: android.content.BroadcastReceiver? = null
    private fun isDebuggableBuild(): Boolean {
        val flags = context?.applicationInfo?.flags ?: return false
        return flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0
    }
    private fun registerDebugReceiver() {
        if (!isDebuggableBuild()) return
        if (debugSleepReceiver != null) return
        val ctx = context ?: return
        val r = object : android.content.BroadcastReceiver() {
            override fun onReceive(c: android.content.Context?, intent: android.content.Intent?) {
                when (intent?.action) {
                    "com.openautolink.app.DEBUG_SIMULATE_SLEEP" -> {
                        val ms = intent.getLongExtra("duration_ms", 60_000L).coerceAtLeast(1_000L)
                        debugSimulateSleep(ms)
                    }
                    "com.openautolink.app.DEBUG_INJECT_PHONE" -> {
                        val host = intent.getStringExtra("host")
                        if (host.isNullOrBlank()) {
                            OalLog.w(TAG, "DEBUG_INJECT_PHONE missing 'host' extra")
                            return
                        }
                        val port = intent.getIntExtra("port", 5277)
                        val phoneId = intent.getStringExtra("phone_id")
                            ?: "debug_inject_${host.replace('.', '_')}"
                        val friendlyName = intent.getStringExtra("name")
                            ?: "Test Phone @ $host"
                        val ctxApp = context
                        if (ctxApp != null) {
                            com.openautolink.app.transport.PhoneDiscovery.getInstance(ctxApp)
                                .injectDebugPhone(
                                    host = host,
                                    port = port,
                                    friendlyName = friendlyName,
                                    phoneId = phoneId,
                                )
                            OalLog.i(TAG, "DEBUG injected phone: $friendlyName ($phoneId) @ $host:$port")
                        }
                    }
                }
            }
        }
        val filter = android.content.IntentFilter().apply {
            addAction("com.openautolink.app.DEBUG_SIMULATE_SLEEP")
            addAction("com.openautolink.app.DEBUG_INJECT_PHONE")
        }
        try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                ctx.registerReceiver(r, filter, android.content.Context.RECEIVER_EXPORTED)
            } else {
                @Suppress("UnspecifiedRegisterReceiverFlag")
                ctx.registerReceiver(r, filter)
            }
            debugSleepReceiver = r
            OalLog.i(TAG, "Debug receivers registered (DEBUG_SIMULATE_SLEEP, DEBUG_INJECT_PHONE)")
        } catch (e: Exception) {
            OalLog.w(TAG, "Debug receiver registration failed: ${e.message}")
        }
    }
    private fun unregisterDebugReceiver() {
        val ctx = context ?: return
        debugSleepReceiver?.let {
            try { ctx.unregisterReceiver(it) } catch (_: Exception) {}
        }
        debugSleepReceiver = null
    }

    suspend fun requestKeyframe() {
        aasdkSession?.requestKeyframe()
    }

    private suspend fun syncLocalPreferences() {
        val ctx = context ?: return
        try {
            val prefs = AppPreferences.getInstance(ctx)
            ClusterNavigationState.distanceUnits = prefs.distanceUnits.first()
        } catch (e: Exception) {
            OalLog.w(TAG, "Failed to sync local preferences: ${e.message}")
        }
        // Start (idempotent-enough) EV tuning observer; coroutines just
        // re-read the same Volatiles on subsequent calls.
        observeEvTuningPrefs()
    }

    suspend fun sendControlMessage(message: ControlMessage) {
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) sendCtl@{
        // Never enter JNI from the main thread.
        //
        // Callers use viewModelScope, which is Dispatchers.Main, so every touch
        // and key event was a blocking native call on the UI thread. Harmless
        // while the session is healthy; when a teardown holds the native lock it
        // parks the main thread behind it. That is what produced the 16.4-second
        // main-thread stall and ANR on 2026-08-11.
        val session = aasdkSession
        if (session == null) {
            // Say so. This silent return meant every touch vanished while video
            // streamed perfectly and the log looked healthy — the user could see
            // maps animating and could not tap anything, and nothing in the log
            // said why. Rate-limited so a genuinely dead session cannot flood.
            val now = System.currentTimeMillis()
            if (now - lastNoSessionWarnAt > NO_SESSION_WARN_INTERVAL_MS) {
                lastNoSessionWarnAt = now
                OalLog.w(TAG, "Dropping ${message::class.simpleName} — no session " +
                        "reference. Input will not reach the phone even if video is " +
                        "streaming.")
            }
            return@sendCtl
        }
        when (message) {
            is ControlMessage.Touch -> {
                if (message.pointers != null && message.pointers.isNotEmpty()) {
                    // Multi-touch: send all pointers via native multi-touch API
                    val ids = message.pointers.map { it.id }.toIntArray()
                    val xs = message.pointers.map { it.x }.toFloatArray()
                    val ys = message.pointers.map { it.y }.toFloatArray()
                    session.sendMultiTouchEvent(
                        message.action, message.actionIndex ?: 0,
                        ids, xs, ys
                    )
                } else {
                    val x = message.x ?: return@sendCtl
                    val y = message.y ?: return@sendCtl
                    session.sendTouchEvent(
                        message.action, message.pointerId ?: 0, x, y, 1
                    )
                }
            }
            is ControlMessage.Button -> session.sendKeyEvent(message.keycode, message.down)
            is ControlMessage.KeyframeRequest -> session.requestKeyframe()
            is ControlMessage.VehicleData -> {
                message.speedKmh?.let { session.sendSpeed((it / 3.6f * 1000).toInt()) }
                // Same edge-trigger discipline as the inline sendMessage path
                // in start() — see lastSent* fields. Necessary because both
                // paths share these state vars; without it, manual injections
                // would force a resend even when value unchanged.
                message.gearRaw?.let {
                    if (lastSentGearRaw != it) { lastSentGearRaw = it; session.sendGear(it) }
                }
                message.parkingBrake?.let {
                    if (lastSentParkingBrake != it) { lastSentParkingBrake = it; session.sendParkingBrake(it) }
                }
                message.nightMode?.let {
                    if (lastSentNightMode != it) { lastSentNightMode = it; session.sendNightMode(it) }
                }
                message.driving?.let {
                    val drv = if (alwaysInPark) false else it
                    if (lastSentDriving != drv) { lastSentDriving = drv; session.sendDrivingStatus(drv) }
                }
            }
            is ControlMessage.Gnss -> {
                // GPS forwarded via LocationListener, not control messages
            }
            else -> {}
        }
        }
    }

    fun ensureClusterAlive() {
        _clusterManager?.ensureAlive()
    }

    private suspend fun watchDecoderState() {
        while (_videoDecoder == null) { delay(500) }
        _videoDecoder?.decoderState?.collect { state ->
            if (state == DecoderState.ERROR) {
                OalLog.w(TAG, "Decoder error -- initiating recovery")
                _remoteDiagnostics?.log(DiagnosticLevel.ERROR, "video", "Decoder error -- recovery")
                _statusMessage.value = "Video error -- recovering..."
                recoverDecoder()
            }
        }
    }

    private suspend fun recoverDecoder() {
        delay(500)
        _videoDecoder?.let { decoder ->
            decoder.resume()
            requestKeyframe()
            OalLog.i(TAG, "Decoder recovery: resumed codec, requested keyframe")
        }
    }

    private suspend fun watchKeyframeNeeds() {
        while (_videoDecoder == null) { delay(500) }
        val decoder = _videoDecoder ?: return
        decoder.needsKeyframe.collect { needed ->
            if (needed) {
                var attempt = 0
                while (decoder.needsKeyframe.value) {
                    attempt++
                    requestKeyframe()
                    if (attempt == 1) {
                        OalLog.i(TAG, "Keyframe re-request #$attempt")
                    } else {
                        OalLog.w(TAG, "Keyframe re-request #$attempt (still waiting)")
                        _remoteDiagnostics?.log(DiagnosticLevel.WARN, "video",
                            "Keyframe re-request #$attempt")
                    }
                    delay(2000)
                }
            }
        }
    }

    /**
     * Video-stall watchdog. Fires when video frames STOP ARRIVING from the
     * phone while we believe we're actively streaming — the freeze class that
     * [watchKeyframeNeeds] cannot catch (that only reacts to decode FAILURES,
     * but here the decoder is simply starved because nothing arrives).
     *
     * Two-stage recovery, both guarded against legitimate pauses:
     *   1. after [VIDEO_STALL_KEYFRAME_MS] with no frame → requestKeyframe()
     *      (cheap nudge; may un-wedge a video-only flow stall).
     *   2. after [VIDEO_STALL_RECONNECT_MS] with no frame → forceReconnect()
     *      (the half-open-TCP hammer; a keyframe can't cross a dead pipe).
     * A [VIDEO_STALL_WARMUP_MS] grace from entering STREAMING avoids racing the
     * first-frame warmup, and we skip entirely while going idle / not STREAMING.
     */
    private suspend fun watchVideoStall() {
        var reconnectRequested = false
        while (true) {
            delay(1000)
            if (_sessionState.value != SessionState.STREAMING || isGoingIdle) {
                reconnectRequested = false
                continue
            }
            val last = lastVideoFrameArrivedMs
            if (last == 0L) continue  // no frame yet this session — warmup
            val now = SystemClock.elapsedRealtime()
            val stallMs = now - last
            // Warmup grace: don't act until at least one frame is well past the
            // STREAMING transition (last is only set once frames actually flow).
            if (stallMs < VIDEO_STALL_KEYFRAME_MS) {
                reconnectRequested = false
                continue
            }
            if (stallMs >= VIDEO_STALL_RECONNECT_MS) {
                if (!reconnectRequested) {
                    reconnectRequested = true
                    OalLog.w(TAG, "Video stall ${stallMs}ms — forcing reconnect")
                    DiagnosticLog.w("video", "Video stall ${stallMs}ms (no frames) — forceReconnect")
                    scope.launch(kotlinx.coroutines.Dispatchers.IO) {
                        aasdkSession?.forceReconnect("video stall ${stallMs}ms")
                    }
                }
            } else {
                // 4s..7s window: cheap keyframe nudge, once per stall episode.
                OalLog.w(TAG, "Video stall ${stallMs}ms — requesting keyframe")
                DiagnosticLog.w("video", "Video stall ${stallMs}ms (no frames) — requestKeyframe")
                requestKeyframe()
            }
        }
    }

    private suspend fun watchCallState() {
        val player = _audioPlayer ?: return
        player.callState.collect { state ->
            val purpose = when (state) {
                CallState.IN_CALL -> AudioPurpose.PHONE_CALL
                else -> AudioPurpose.ASSISTANT
            }
            _micCaptureManager?.setMicPurpose(purpose)
            OalLog.d(TAG, "Call state: $state -- mic purpose: $purpose")
        }
    }

    private fun handleControlMessage(sourceSession: AasdkSession, message: ControlMessage) {
        synchronized(sessionStateLock) {
            if (aasdkSession !== sourceSession) {
                OalLog.i(TAG, "Ignoring control message from stale session: ${message::class.simpleName}")
                return@synchronized
            }
            when (message) {
            is ControlMessage.PhoneConnected -> {
                _remoteDiagnostics?.log(DiagnosticLevel.INFO, "session", "Phone connected: ${message.phoneName}")
                resetLatchedVehicleSensorState("phone_connected")
                seedCurrentUiNightMode("phone_connected")
                val accepted = synchronized(sessionStateLock) {
                    if (aasdkSession === sourceSession) {
                        val startStreamingServices = shouldStartStreamingServices(
                            _sessionState.value,
                            SessionState.STREAMING,
                        )
                        _sessionState.value = SessionState.STREAMING
                        _statusMessage.value = "Streaming"
                        if (startStreamingServices) {
                            startStreamingServicesLocked(sourceSession)
                        }
                        true
                    } else {
                        false
                    }
                }
                if (!accepted) {
                    OalLog.i(TAG, "Ignoring PhoneConnected effects from stale session")
                    return
                }
                // Reset the stall watchdog baseline: give the fresh session a
                // full warmup window before it can fire (avoids a stale
                // timestamp from a prior session tripping it immediately).
                lastVideoFrameArrivedMs = SystemClock.elapsedRealtime() + VIDEO_STALL_WARMUP_MS
            }
            is ControlMessage.PhoneDisconnected -> {
                _remoteDiagnostics?.log(DiagnosticLevel.INFO, "session", "Phone disconnected: ${message.reason}")
                _gnssForwarder?.stop()
                _vehicleDataForwarder?.stop()
                _imuForwarder?.stop()
                stopDirectLocationForwarding()
                _navigationDisplay.clear()
                ClusterNavigationState.clear()
                if (message.reason == "byebye_user_selection") {
                    OalLog.i(TAG, "User tapped Exit in AA launcher — tearing down cluster + session")
                    // Drive cluster + session teardown HERE rather than from
                    // MainActivity. AAOS force-finishes our Activity within
                    // ~1s of acknowledging VIDEO_FOCUS_NATIVE, which cancels
                    // its lifecycleScope before any collector can run. Doing
                    // the cleanup in SessionManager's own scope guarantees
                    // navigationEnded() reaches Templates Host before our
                    // process is wound down — otherwise Templates Host keeps
                    // its cluster Activity bound and respawns our process to
                    // serve it.
                    try {
                        com.openautolink.app.cluster.ClusterMainSession.endActiveNavigation()
                    } catch (e: Exception) {
                        OalLog.w(TAG, "endActiveNavigation() failed: ${e.message}")
                    }
                    try {
                        _clusterManager?.release()
                        _clusterManager = null
                    } catch (e: Exception) {
                        OalLog.w(TAG, "clusterManager.release() failed: ${e.message}")
                    }
                    // Finish every task this app owns. Doing this from
                    // SessionManager (app-scoped) rather than MainActivity
                    // (whose lifecycleScope is gone the moment the OS
                    // force-finishes us) makes sure cleanup actually runs.
                    try {
                        val ctx = context
                        if (ctx != null) {
                            val am = ctx.getSystemService(Context.ACTIVITY_SERVICE)
                                as? android.app.ActivityManager
                            am?.appTasks?.forEach { task ->
                                try { task.finishAndRemoveTask() } catch (_: Exception) {}
                            }
                            OalLog.i(TAG, "User exit: finished all app tasks")
                        }
                    } catch (e: Exception) {
                        OalLog.w(TAG, "appTasks teardown failed: ${e.message}")
                    }
                    _userExitEvents.tryEmit(Unit)
                }
            }
            is ControlMessage.NavState -> {
                _navigationDisplay.onNavState(message)
                _navigationDisplay.currentManeuver.value?.let { maneuver ->
                    ClusterNavigationState.update(maneuver)
                }
            }
            is ControlMessage.NavStateClear -> {
                _navigationDisplay.clear()
                _vehicleEnergyForecast.value = null
                ClusterNavigationState.clear()
            }
            is ControlMessage.MediaMetadata -> {
                lastMediaMetadata = message
                _mediaSessionManager?.updateMetadata(
                    title = message.title, artist = message.artist, album = message.album,
                    durationMs = message.durationMs, albumArtBase64 = message.albumArtBase64
                )
                if (message.playing != null) {
                    val pb = ControlMessage.MediaPlaybackState(
                        playing = message.playing, positionMs = message.positionMs ?: 0
                    )
                    lastMediaPlaybackState = pb
                    _mediaSessionManager?.updatePlaybackState(pb.playing, pb.positionMs)
                }
            }
            is ControlMessage.MediaPlaybackState -> {
                lastMediaPlaybackState = message
                _mediaSessionManager?.updatePlaybackState(
                    playing = message.playing, positionMs = message.positionMs
                )
            }
            is ControlMessage.AudioStart -> {
                _remoteDiagnostics?.log(DiagnosticLevel.INFO, "audio",
                    "Audio start: purpose=${message.purpose}, rate=${message.sampleRate}")
                _audioPlayer?.startPurpose(message.purpose, message.sampleRate, message.channels)
            }
            is ControlMessage.AudioStop -> {
                _audioPlayer?.stopPurpose(message.purpose)
            }
            is ControlMessage.MicStart -> {
                DiagnosticLog.i("mic", "MicStart: rate=${message.sampleRate}, source=$micSource")
                if (micSource == "car") {
                    _micCaptureManager?.start(message.sampleRate)
                }
            }
            is ControlMessage.MicStop -> {
                _micCaptureManager?.stop()
            }
            is ControlMessage.Error -> {
                OalLog.e(TAG, "Error ${message.code}: ${message.message}")
                // Don't surface raw aasdk error strings ("AASDK Error: 30, Native Code: 0")
                // to the user. We're auto-recovering. Just show a friendly status.
                if ("AASDK Error" in message.message) {
                    _statusMessage.value = "Reconnecting..."
                } else {
                    _statusMessage.value = "Error: ${message.message}"
                }
            }
            is ControlMessage.PhoneBattery -> {
                _phoneBatteryLevel.value = message.level
                _phoneBatteryCritical.value = message.critical
            }
            is ControlMessage.VoiceSession -> {
                _voiceSessionActive.value = message.started
            }
            is ControlMessage.PhoneStatus -> {
                _phoneSignalStrength.value = message.signalStrength
            }
            else -> {}
            }
        }
    }

    // ── EV energy-model tuning ──────────────────────────────────────

    @Volatile private var evTuningObserverStarted = false

    /**
     * Start observing tuning prefs and refreshing [evTuningEnabled] et al.
     * Safe to call repeatedly; only the first call starts the collectors.
     * See docs/ev-energy-model-tuning-plan.md.
     */
    private fun observeEvTuningPrefs() {
        if (evTuningObserverStarted) return
        val ctx = context ?: return
        evTuningObserverStarted = true
        val prefs = AppPreferences.getInstance(ctx)
        if (evLearnedEstimator == null) {
            evLearnedEstimator = EvLearnedRateEstimator.getInstance(prefs)
        }
        scope.launch { prefs.evTuningEnabled.collect { evTuningEnabled = it } }
        scope.launch { prefs.evDrivingMode.collect { evDrivingMode = it } }
        scope.launch { prefs.evDrivingWhPerKm.collect { evDrivingWhPerKm = it } }
        scope.launch { prefs.evDrivingMultiplierPct.collect { evDrivingMultiplierPct = it } }
        scope.launch { prefs.evAuxWhPerKmX10.collect { evAuxWhPerKmX10 = it } }
        scope.launch { prefs.evAeroCoefX100.collect { evAeroCoefX100 = it } }
        scope.launch { prefs.evReservePct.collect { evReservePct = it } }
        scope.launch { prefs.evMaxChargeKw.collect { evMaxChargeKw = it } }
        scope.launch { prefs.evMaxDischargeKw.collect { evMaxDischargeKw = it } }
        scope.launch { prefs.evUseEpaBaseline.collect { evUseEpaBaseline = it } }

        // Profile lookup runs inline in the VHAL sendMessage callback (see
        // the `refreshEvProfileLookup(vd)` call in start()). Doing it there
        // avoids a flaky retry-when-forwarder-arrives dance and guarantees
        // every identity tick is seen.
    }

    private fun refreshEvProfileLookup(vd: ControlMessage.VehicleData) {
        val ctx = context ?: return
        val repo = EvProfilesRepository.getInstance(ctx)
        val profile = repo.lookup(vd.carMake, vd.carModel, vd.carYear)
        val newDrv = profile?.drivingWhPerKm
        val newChg = profile?.maxChargeKw
        if (newDrv != epaDrivingWhPerKm || newChg != epaMaxChargeKw) {
            epaDrivingWhPerKm = newDrv
            epaMaxChargeKw = newChg
            if (profile != null) {
                DiagnosticLog.i(
                    "ev_profiles",
                    "matched ${profile.key}: drv=${newDrv}Wh/km chg=${newChg}kW",
                )
            }
        }
    }

    /**
     * Compute the driving Wh/km override the C++ side should use. Returns a
     * negative value when the C++ derived formula should be used instead
     * (`derived` mode). Callers must pass the same `batteryWh`/`rangeM` that
     * will reach [JniSession::sendEnergyModelSensor] so the multiplier mode
     * matches.
     */
    private fun computeDrivingOverride(batteryWh: Int, rangeM: Int): Float {
        if (!evTuningEnabled) return -1f
        return when (evDrivingMode) {
            AppPreferences.EV_DRIVING_MODE_MANUAL -> evDrivingWhPerKm.toFloat()
            AppPreferences.EV_DRIVING_MODE_MULTIPLIER -> {
                if (rangeM <= 0 || batteryWh <= 0) return -1f
                val derived = (batteryWh.toFloat() / rangeM.toFloat()) * 1000f
                derived * (evDrivingMultiplierPct / 100f)
            }
            AppPreferences.EV_DRIVING_MODE_LEARNED -> {
                // Fall back to legacy derived path until the EMA has enough
                // data to be trustworthy (>= 1 km of valid samples).
                val snap = evLearnedEstimator?.activeSnapshot?.value
                if (snap?.usable == true) snap.whPerKm else -1f
            }
            else -> -1f
        }
    }

    private fun sendEnergyModelWithTuning(
        session: AasdkSession, batteryWh: Int, capacityWh: Int, rangeM: Int, chargeW: Int,
    ) {
        // EPA baseline path: master tuning OFF, but user opted in to "use EPA
        // as baseline". Apply driving Wh/km and max charge kW from the bundled
        // profile when available. All other fields stay at hardcoded defaults
        // (i.e. < 0 → C++ derives) so behavior remains close to the legacy
        // path for unmatched vehicles.
        if (!evTuningEnabled) {
            if (!evUseEpaBaseline) {
                session.sendEnergyModel(batteryWh, capacityWh, rangeM, chargeW)
                return
            }
            val drv = epaDrivingWhPerKm?.toFloat() ?: -1f
            val chg = epaMaxChargeKw?.let { it * 1000 } ?: -1
            session.sendEnergyModel(
                batteryWh, capacityWh, rangeM, chargeW,
                drivingWhPerKm = drv,
                maxChargeW = chg,
            )
            return
        }
        session.sendEnergyModel(
            batteryWh, capacityWh, rangeM, chargeW,
            drivingWhPerKm = computeDrivingOverride(batteryWh, rangeM),
            auxWhPerKm = evAuxWhPerKmX10 / 10f,
            aeroCoef = evAeroCoefX100 / 100f,
            reservePct = evReservePct.toFloat(),
            maxChargeW = evMaxChargeKw * 1000,
            maxDischargeW = evMaxDischargeKw * 1000,
        )
    }

    private fun evTuningSummary(batteryWh: Int, rangeM: Int): String {
        if (!evTuningEnabled) return ""
        val drv = when (evDrivingMode) {
            AppPreferences.EV_DRIVING_MODE_MANUAL -> "manual:${evDrivingWhPerKm}"
            AppPreferences.EV_DRIVING_MODE_MULTIPLIER -> {
                val derived = if (rangeM > 0) (batteryWh.toFloat() / rangeM.toFloat()) * 1000f else 0f
                val effective = (derived * (evDrivingMultiplierPct / 100f)).toInt()
                "x${evDrivingMultiplierPct}%(${effective})"
            }
            AppPreferences.EV_DRIVING_MODE_LEARNED -> {
                val snap = evLearnedEstimator?.activeSnapshot?.value
                if (snap?.usable == true)
                    "learned:${snap.whPerKm.toInt()}(${"%.1f".format(snap.sampleKm)}km)"
                else "learned:warmup"
            }
            else -> "derived"
        }
        return " [tuning=ON drv=$drv aux=${evAuxWhPerKmX10 / 10f} aero=${evAeroCoefX100 / 100f}" +
            " res=${evReservePct}% chg=${evMaxChargeKw}kW]"
    }

    private fun rejectCurrentEnergyModel(reason: String, detail: String): Boolean {
        DiagnosticLog.w("vem", "sendCurrentEnergyModel[$reason] rejected: $detail")
        return false
    }

    private fun sendCurrentEnergyModel(reason: String): Boolean {
        val session = aasdkSession ?: return rejectCurrentEnergyModel(reason, "no-session")
        val vd = _vehicleDataForwarder?.latestVehicleData?.value
            ?: return rejectCurrentEnergyModel(reason, "no-forwarder")
        val batteryWh = vd.evBatteryLevelWh?.toInt() ?: 0
        val capacityWh = vd.evBatteryCapacityWh?.toInt() ?: 0
        val rangeM = ((vd.rangeKm ?: 0f) * 1000).toInt()
        if (batteryWh <= 0 || capacityWh <= 0 || rangeM <= 0) {
            return rejectCurrentEnergyModel(reason, "no-ev-snapshot")
        }
        val chargeW = vd.evChargeRateW?.toInt() ?: 0
        DiagnosticLog.i(
            "vem",
            "sendCurrentEnergyModel[$reason]: level=${batteryWh}Wh cap=${capacityWh}Wh range=${rangeM}m charge=${chargeW}W${evTuningSummary(batteryWh, rangeM)}",
        )
        sendEnergyModelWithTuning(session, batteryWh, capacityWh, rangeM, chargeW)
        return true
    }

    fun forceSendEnergyModel(): Boolean = sendCurrentEnergyModel("manual")

    /** Snapshot of the currently matched EV profile, for the tuning UI. */
    data class EvProfileMatch(
        val key: String?,
        val displayName: String?,
        val drivingWhPerKm: Int?,
        val maxChargeKw: Int?,
    )

    fun currentEvProfileMatch(): EvProfileMatch {
        val vd = _vehicleDataForwarder?.latestVehicleData?.value
        val ctx = context ?: return EvProfileMatch(null, null, null, null)
        val profile = EvProfilesRepository.getInstance(ctx)
            .lookup(vd?.carMake, vd?.carModel, vd?.carYear)
        return EvProfileMatch(
            key = profile?.key,
            displayName = profile?.displayName ?: profile?.key,
            drivingWhPerKm = profile?.drivingWhPerKm,
            maxChargeKw = profile?.maxChargeKw,
        )
    }
}

