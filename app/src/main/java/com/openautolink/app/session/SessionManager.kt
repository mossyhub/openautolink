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
import com.openautolink.app.input.ImuForwarder
import com.openautolink.app.input.VehicleDataForwarder
import com.openautolink.app.input.VehicleDataForwarderImpl
import com.openautolink.app.media.OalMediaBrowserService
import com.openautolink.app.media.OalMediaSessionManager
import com.openautolink.app.navigation.ManeuverState
import com.openautolink.app.navigation.NavigationDisplay
import com.openautolink.app.navigation.NavigationDisplayImpl
import com.openautolink.app.transport.AudioPurpose
import com.openautolink.app.transport.ConnectionState
import com.openautolink.app.transport.ControlMessage
import com.openautolink.app.transport.aasdk.AasdkSession
import com.openautolink.app.transport.aasdk.AasdkSdrConfig
import com.openautolink.app.transport.usb.UsbConnectionManager
import com.openautolink.app.video.DecoderState
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
         * Video-stall watchdog (issue #34). If no inbound AA video frame arrives
         * for this long while we're STREAMING and foreground, the AA video
         * channel has wedged (native layer blocked on a dead flow, never fires
         * onSessionStopped) — force a reconnect. Generous enough not to trip on
         * brief network hiccups or the phone's periodic-IDR gap (~2-2.5 min
         * between keyframes, but P-frames flow continuously in between, so a
         * healthy stream never goes this long without ANY frame).
         */
        private const val VIDEO_STALL_THRESHOLD_MS = 8_000L
        private const val VIDEO_STALL_POLL_MS = 1_000L

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

    private val scope = CoroutineScope(SupervisorJob() + kotlinx.coroutines.Dispatchers.Main)

    // aasdk JNI session -- native C++ handles AA protocol
    private var aasdkSession: AasdkSession? = null

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

    // Last density we sent in the SDR. Diagnostic-only — surfaced in the
    // Stats-for-nerds overlay so the user can see what auto-DPI picked vs.
    // their manual slider. 0 until the first session has built its SDR.
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

    // Navigation display
    private val _navigationDisplay: NavigationDisplay = NavigationDisplayImpl()
    val navigationDisplay: NavigationDisplay get() = _navigationDisplay

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
        stop()
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
    @Volatile private var lastSentGearRaw: Int? = null

    // Cluster manager
    private var _clusterManager: com.openautolink.app.cluster.ClusterManager? = null

    private var observeJob: Job? = null
    private var decoderWatchJob: Job? = null
    private var keyframeWatchJob: Job? = null
    private var callStateJob: Job? = null
    private var videoStallWatchJob: Job? = null

    /**
     * True while the app is intentionally idle (Activity paused / screen off /
     * backgrounded to AAOS home). The video-stall watchdog must NOT fire a
     * reconnect during these windows — video legitimately stops when we're not
     * foreground. Set in [markGoingIdle], cleared on the next wake/start.
     */
    @Volatile private var isGoingIdle = false

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
    /** Last elapsedRealtime we ran the full wake handler for. Used for dedupe. */
    @Volatile private var lastWakeHandledAtMs = 0L

    /** Reentrancy guard for reconnect() so rapid Save&Reconnect taps coalesce. */
    @Volatile private var reconnectInProgress = false

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

    fun start(
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
    ) {
        // Cache for later reconnects that don't know the resolved IP (e.g.
        // Settings "Save & Reconnect" in Car Hotspot mode).
        if (!manualIpAddress.isNullOrBlank()) _lastManualIpAddress = manualIpAddress
        micSource = micSourcePreference
        observeJob?.cancel()

        // Create video decoder
        _videoDecoder?.release()
        _videoDecoder = MediaCodecDecoder(codecPreference, scalingMode)

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
        // Reset edge-trigger memory so the first tick of the new session
        // unconditionally publishes nightMode / parking / driving / gear to
        // the phone — the phone has no prior state from us.
        resetLatchedVehicleSensorState("start_session")
        _vehicleDataForwarder = context?.let { ctx ->
            VehicleDataForwarderImpl(
                ctx,
                sendMessage = { vd ->
                    val session = aasdkSession ?: return@VehicleDataForwarderImpl
                    vd.speedKmh?.let { session.sendSpeed((it / 3.6f * 1000).toInt()) }
                    // Edge-trigger the low-cadence properties so each
                    // transition fires the phone exactly once. Spamming
                    // nightMode 10×/s while it's steady-state may have
                    // contributed to issue #6 (day/night theme transition →
                    // black video). Same defense for the others — cheap and
                    // strictly correct.
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
                        if (lastSentDriving != it) { lastSentDriving = it; session.sendDrivingStatus(it) }
                    }
                    if (vd.fuelLevelPct != null || vd.rangeKm != null) {
                        session.sendFuel(
                            vd.fuelLevelPct ?: 0,
                            ((vd.rangeKm ?: 0f) * 1000).toInt(),
                            vd.lowFuel ?: false
                        )
                    }
                    vd.rpmE3?.let { session.sendRpm(it) }
                    if (vd.evBatteryLevelWh != null || vd.evBatteryCapacityWh != null) {
                        val batteryWh = vd.evBatteryLevelWh?.toInt() ?: 0
                        val capacityWh = vd.evBatteryCapacityWh?.toInt() ?: 0
                        val rangeM = ((vd.rangeKm ?: 0f) * 1000).toInt()
                        val chargeW = vd.evChargeRateW?.toInt() ?: 0
                        val now = SystemClock.elapsedRealtime()
                        // Feed the learned-rate estimator before VEM send so
                        // the override (if learned mode is selected) reflects
                        // the latest tick.
                        evLearnedEstimator?.onVehicleTick(vd, now)
                        // Refresh the EPA / curated profile match. Cheap —
                        // early-outs when carMake/Model/Year haven't changed.
                        refreshEvProfileLookup(vd)
                        // Suppress identical / near-identical emits — log only
                        // when a value moved meaningfully or [VEM_LOG_MIN_GAP_MS]
                        // elapsed. First emit always logs.
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
                },
                onIgnitionOn = { /* aasdk mode doesn't need ignition-based reconnect */ }
            )
        }

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

        observeJob = scope.launch {
            // Watch for decoder errors
            decoderWatchJob?.cancel()
            decoderWatchJob = launch { watchDecoderState() }

            // Watch for IDR starvation
            keyframeWatchJob?.cancel()
            keyframeWatchJob = launch { watchKeyframeNeeds() }

            // Watch call state for mic purpose routing
            callStateJob?.cancel()
            callStateJob = launch { watchCallState() }

            // Watch for a wedged AA video channel (issue #34)
            videoStallWatchJob?.cancel()
            videoStallWatchJob = launch { watchVideoStall() }

            // Start direct mode session
            startSession(directTransport, hotspotSsid, hotspotPassword,
                videoAutoNegotiate, codecPreference, aaResolution, aaDpi, aaAutoDpi,
                aaWidthMargin, aaHeightMargin, aaPixelAspect, aaTargetLayoutWidthDp,
                aaViewingDistanceMm, aaDecoderAdditionalDepth, aaAutoMargins,
                videoFps,
                driveSide, hideClock, hideSignal, hideBattery, scalingMode,
                manualIpAddress,
                safeAreaTop, safeAreaBottom, safeAreaLeft, safeAreaRight)
        }

        // Listen for system sleep so we can gracefully tear down before the
        // socket goes stale. Wake is handled in MainActivity.onResume().
        registerScreenReceiver()
        registerDebugReceiver()
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
    ) {
        aasdkSession?.stop()
        _transportMode.value = directTransport
        val ctx = context ?: return

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
            computedTargetLayoutWidthDp = aaTargetLayoutWidthDp
            OalLog.i(TAG, "Auto-DPI: renderRect=${rrW}x${rrH} panelDpi=$pDpi " +
                    "innerW=$innerW → DPI $effectiveDpi (user manual=$aaDpi ignored)")
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
            autoNegotiate = videoAutoNegotiate,
            videoCodec = codec,
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
        _touchWidth.value = resW
        _touchHeight.value = resH
        _effectiveDpi.value = effectiveDpi

        aasdkSession = session
        // Reset the mirrored reconnect counter at session boundary. The
        // per-session collector below will republish updates as they fire.
        _reconnectAttempt.value = 0

        // Observe session state
        scope.launch {
            session.connectionState.collect { connState ->
                val newState = connState.toSessionState()
                _sessionState.value = newState
                val attempt = _reconnectAttempt.value
                _statusMessage.value = when (newState) {
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
                if (newState == SessionState.STREAMING) {
                    startLocationForwarding(session)
                    _vehicleDataForwarder?.start()
                    _imuForwarder?.start()
                }
            }
        }

        // Mirror per-session reconnect-attempt counter so observers (UI banner,
        // 3-failure picker escalation) don't have to track AasdkSession identity.
        scope.launch {
            session.reconnectAttempt.collect { attempt ->
                _reconnectAttempt.value = attempt
                // Also refresh the status message so the banner updates when
                // the attempt counter advances without an accompanying state
                // change (the connection-state collector above only fires on
                // state transitions).
                if (attempt > 0 && _sessionState.value != SessionState.STREAMING) {
                    _statusMessage.value = "Reconnecting (attempt $attempt)…"
                }
            }
        }

        // Observe control messages
        scope.launch {
            session.controlMessages.collect { message ->
                lastActiveTimestamp = SystemClock.elapsedRealtime()
                handleControlMessage(message)
            }
        }

        // Collect video frames
        scope.launch(videoDispatcher) {
            session.videoFrames.collect { frame ->
                _videoDecoder?.onFrame(frame)
            }
        }

        // Update decoder when phone negotiates codec type
        scope.launch {
            session.negotiatedCodecType.collect { codecType ->
                if (codecType > 0) {
                    (_videoDecoder as? com.openautolink.app.video.MediaCodecDecoder)
                        ?.setNegotiatedCodec(codecType)
                }
            }
        }

        // Collect audio frames
        scope.launch(audioDispatcher) {
            session.audioFrames.collect { frame ->
                _audioPlayer?.onAudioFrame(frame)
            }
        }

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
        OalLog.i(TAG, "aasdk JNI session started ($directTransport transport)")
    }

    @android.annotation.SuppressLint("MissingPermission")
    private fun startLocationForwarding(session: AasdkSession) {
        stopDirectLocationForwarding()
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

    fun stop() {
        unregisterScreenReceiver()
        unregisterDebugReceiver()
        observeJob?.cancel()
        observeJob = null
        decoderWatchJob?.cancel()
        decoderWatchJob = null
        keyframeWatchJob?.cancel()
        keyframeWatchJob = null
        callStateJob?.cancel()
        callStateJob = null
        videoStallWatchJob?.cancel()
        videoStallWatchJob = null
        aasdkSession?.stop()
        aasdkSession = null
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
        _vehicleDataForwarder?.stop()
        _vehicleDataForwarder = null
        _imuForwarder?.stop()
        _imuForwarder = null
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
        _sessionState.value = SessionState.IDLE
        _statusMessage.value = "Disconnected"
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
    fun reconnect(
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
            )
            return
        }

        OalLog.i(TAG, "Reconnect requested")
        // Reentrancy guard: rapid Save&Reconnect taps used to fire 20+ in <30ms.
        if (reconnectInProgress) {
            OalLog.w(TAG, "reconnect() already in progress — ignoring duplicate call")
            return
        }
        reconnectInProgress = true
        OalLog.i(TAG, "Reconnecting AA session with new settings (minimal restart)")
        micSource = micSourcePreference

        // 1. Cancel old session observer coroutines (await cancellation so the
        // new keyframeWatchJob doesn't race with the old one — the spam of
        // 20+ "Keyframe re-request #2" lines came from this exact race).
        // Run on IO so we don't block the Main thread (causes ANR — we wait on
        // cancelAndJoin and then aasdkSession.stop() which JNI-joins the io_thread).
        scope.launch(kotlinx.coroutines.Dispatchers.IO) {
            try {
                try {
                    observeJob?.cancelAndJoin()
                    decoderWatchJob?.cancelAndJoin()
                    keyframeWatchJob?.cancelAndJoin()
                    callStateJob?.cancelAndJoin()
                    videoStallWatchJob?.cancelAndJoin()
                } catch (_: Exception) {}
                doReconnectAfterCancel(
                    codecPreference, micSourcePreference, scalingMode, directTransport,
                    hotspotSsid, hotspotPassword, videoAutoNegotiate, aaResolution,
                    aaDpi, aaAutoDpi, aaWidthMargin, aaHeightMargin, aaPixelAspect, aaTargetLayoutWidthDp,
                    aaViewingDistanceMm, aaDecoderAdditionalDepth, aaAutoMargins,
                    videoFps, driveSide, hideClock, hideSignal, hideBattery,
                    volumeOffsetMedia, volumeOffsetNavigation, volumeOffsetAssistant,
                    effectiveManualIp, safeAreaTop, safeAreaBottom, safeAreaLeft, safeAreaRight,
                )
            } catch (e: Exception) {
                OalLog.e(TAG, "reconnect() failed: ${e.message}")
            } finally {
                // Always clear the guard so a future reconnect attempt can run.
                reconnectInProgress = false
            }
        }
    }

    private fun doReconnectAfterCancel(
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
    ) {
        observeJob = null
        decoderWatchJob = null
        keyframeWatchJob = null
        callStateJob = null

        // 2. Stop old AA session + location forwarding
        aasdkSession?.stop()
        aasdkSession = null
        stopDirectLocationForwarding()

        // 3. Flush video decoder (codec/scaling may have changed)
        _videoDecoder?.release()
        _videoDecoder = MediaCodecDecoder(codecPreference, scalingMode)
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

        // 8. Start new AA session with new SDR config
        observeJob = scope.launch {
            decoderWatchJob = launch { watchDecoderState() }
            keyframeWatchJob = launch { watchKeyframeNeeds() }
            callStateJob = launch { watchCallState() }
            videoStallWatchJob = launch { watchVideoStall() }

            startSession(directTransport, hotspotSsid, hotspotPassword,
                videoAutoNegotiate, codecPreference, aaResolution, aaDpi, aaAutoDpi,
                aaWidthMargin, aaHeightMargin, aaPixelAspect, aaTargetLayoutWidthDp,
                aaViewingDistanceMm, aaDecoderAdditionalDepth, aaAutoMargins,
                videoFps,
                driveSide, hideClock, hideSignal, hideBattery, scalingMode,
                manualIpAddress,
                safeAreaTop, safeAreaBottom, safeAreaLeft, safeAreaRight)
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
        session.sendNightMode(night)
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
        isGoingIdle = false
        val gap = now - lastActiveTimestamp
        lastWakeHandledAtMs = now
        lastActiveTimestamp = now

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

        // Long gap means TCP socket is almost certainly dead. Force a clean
        // reconnect rather than wait for the keepalive watchdog.
        if (gap > LONG_WAKE_FORCE_RECONNECT_MS &&
            _sessionState.value != SessionState.IDLE) {
            OalLog.w(TAG, "Long wake gap ($gapStr) — forcing clean reconnect")
            scope.launch(kotlinx.coroutines.Dispatchers.IO) {
                aasdkSession?.forceReconnect("wake gap $gapStr")
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
        isGoingIdle = true
        lastActiveTimestamp = SystemClock.elapsedRealtime()
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
        aasdkSession?.stop()
        aasdkSession = null
        scope.launch {
            kotlinx.coroutines.delay(durationMs)
            OalLog.i(TAG, "DEBUG: simulating car wake (after ${formatGap(durationMs)})")
            markWake("debug_wake_sim")
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
    // Exposed for integration tests. Action is namespaced under our package
    // and the receiver is package-internal (RECEIVER_NOT_EXPORTED on T+); on
    // older platforms it's still package-scoped at the dispatch layer because
    // we pass the package in the broadcast intent.
    // ------------------------------------------------------------------
    private var debugSleepReceiver: android.content.BroadcastReceiver? = null
    private fun registerDebugReceiver() {
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

    suspend fun requestKeyframeForceFocus() {
        aasdkSession?.requestKeyframeForceFocus()
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
        val session = aasdkSession ?: return
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
                    val x = message.x ?: return
                    val y = message.y ?: return
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
                    if (lastSentDriving != it) { lastSentDriving = it; session.sendDrivingStatus(it) }
                }
            }
            is ControlMessage.Gnss -> {
                // GPS forwarded via LocationListener, not control messages
            }
            else -> {}
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
                    // Issue #35: a plain keyframe re-request re-asserts PROJECTED
                    // focus, which the phone ignores when it already thinks we hold
                    // it (post phone-call / GM-UI takeover) — so it emits no IDR and
                    // video stays frozen until the next natural GOP (~2 min). Use the
                    // focus-bounce variant (NATIVE_TRANSIENT -> PROJECTED, a real
                    // transition) on the first attempt and periodically thereafter to
                    // actually pull a fresh IDR forward; plain re-requests in between.
                    val useForceFocus = attempt == 1 || attempt % 4 == 0
                    if (useForceFocus) {
                        requestKeyframeForceFocus()
                    } else {
                        requestKeyframe()
                    }
                    if (attempt == 1) {
                        OalLog.i(TAG, "Keyframe re-request #$attempt (focus bounce)")
                    } else {
                        val mode = if (useForceFocus) " (focus bounce)" else ""
                        OalLog.w(TAG, "Keyframe re-request #$attempt (still waiting)$mode")
                        _remoteDiagnostics?.log(DiagnosticLevel.WARN, "video",
                            "Keyframe re-request #$attempt$mode")
                    }
                    delay(2000)
                }
            }
        }
    }

    /**
     * Video-stall watchdog (issue #34). The AA video channel can wedge mid-drive
     * — frames stop arriving while the TCP socket stays alive and other channels
     * (nav) keep flowing. The native aasdk layer stays blocked on the dead flow
     * and never calls onSessionStopped, so the existing auto-reconnect (which is
     * wired only to onSessionStopped) never triggers and the screen stays frozen
     * indefinitely while the app UI remains responsive.
     *
     * This polls the last-video-frame timestamp and, if it goes stale while we're
     * actively STREAMING and foreground, forces a reconnect via
     * AasdkSession.forceReconnect() — which routes through the normal teardown +
     * restart. The phone is left advertising on a stalled link, so the restart
     * reconnects within ~1s.
     *
     * Guards against false trips:
     *  - only while sessionState == STREAMING (not during connect/handshake)
     *  - only when not intentionally idle (backgrounded / screen-off / paused),
     *    where video legitimately stops
     *  - only after at least one frame has been seen (lastVideoFrameMs != 0)
     *  - re-arms the baseline after each forced reconnect so we don't loop
     */
    private suspend fun watchVideoStall() {
        // Wait for a session to exist.
        while (aasdkSession == null) { delay(VIDEO_STALL_POLL_MS) }
        val session = aasdkSession ?: return
        while (true) {
            delay(VIDEO_STALL_POLL_MS)
            if (_sessionState.value != SessionState.STREAMING) continue
            if (isGoingIdle) continue
            val last = session.lastVideoFrameMs
            if (last == 0L) continue // no frame yet this session
            val staleFor = SystemClock.elapsedRealtime() - last
            if (staleFor >= VIDEO_STALL_THRESHOLD_MS) {
                OalLog.w(TAG, "Video stall detected: no frame for ${staleFor}ms while STREAMING — forcing reconnect")
                DiagnosticLog.w("video", "Video stall ${staleFor}ms — forcing reconnect")
                _remoteDiagnostics?.log(DiagnosticLevel.WARN, "video",
                    "Video stall ${staleFor}ms — forcing reconnect")
                aasdkSession?.forceReconnect("video stall (${staleFor}ms no frame)")
                // Back off a full threshold so the freshly-restarted session has
                // time to deliver its first frame before we evaluate again.
                delay(VIDEO_STALL_THRESHOLD_MS)
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

    private fun handleControlMessage(message: ControlMessage) {
        when (message) {
            is ControlMessage.PhoneConnected -> {
                _remoteDiagnostics?.log(DiagnosticLevel.INFO, "session", "Phone connected: ${message.phoneName}")
                resetLatchedVehicleSensorState("phone_connected")
                seedCurrentUiNightMode("phone_connected")
                _sessionState.value = SessionState.STREAMING
                _statusMessage.value = "Streaming"
                aasdkSession?.let { startLocationForwarding(it) }
                _vehicleDataForwarder?.start()
                _imuForwarder?.start()
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

    fun forceSendEnergyModel(): Boolean {
        val session = aasdkSession ?: return false
        val vd = _vehicleDataForwarder?.latestVehicleData?.value ?: return false
        if (vd.evBatteryLevelWh == null && vd.evBatteryCapacityWh == null) return false
        val batteryWh = vd.evBatteryLevelWh?.toInt() ?: 0
        val capacityWh = vd.evBatteryCapacityWh?.toInt() ?: 0
        val rangeM = ((vd.rangeKm ?: 0f) * 1000).toInt()
        val chargeW = vd.evChargeRateW?.toInt() ?: 0
        DiagnosticLog.i(
            "vem",
            "forceSendEnergyModel: level=${batteryWh}Wh cap=${capacityWh}Wh range=${rangeM}m charge=${chargeW}W${evTuningSummary(batteryWh, rangeM)}",
        )
        sendEnergyModelWithTuning(session, batteryWh, capacityWh, rangeM, chargeW)
        return true
    }

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

