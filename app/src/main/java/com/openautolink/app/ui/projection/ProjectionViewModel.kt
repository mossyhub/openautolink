package com.openautolink.app.ui.projection

import android.app.Application
import android.content.Context
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.Surface
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.openautolink.app.audio.AudioStats
import com.openautolink.app.data.AppPreferences
import com.openautolink.app.data.KnownPhone
import com.openautolink.app.data.KnownPhonesStore
import com.openautolink.app.input.SteeringWheelController
import com.openautolink.app.input.TouchForwarder
import com.openautolink.app.input.TouchForwarderImpl
import com.openautolink.app.navigation.ManeuverState
import com.openautolink.app.session.SessionManager
import com.openautolink.app.session.SessionState
import com.openautolink.app.video.VideoStats
import com.openautolink.app.diagnostics.OalLog
import com.openautolink.app.diagnostics.DiagnosticLog
import com.openautolink.app.diagnostics.FileLogWriter
import com.openautolink.app.diagnostics.LogcatCapture
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.async
import kotlinx.coroutines.launch

data class ProjectionUiState(
    val sessionState: SessionState = SessionState.IDLE,
    val statusMessage: String = "Ready",
    val phoneName: String? = null,
    val videoStats: VideoStats = VideoStats(),
    val audioStats: AudioStats = AudioStats(),
    val showStats: Boolean = false,
    val maneuver: ManeuverState? = null,
    val phoneBatteryLevel: Int? = null,
    val phoneBatteryCritical: Boolean = false,
    val voiceSessionActive: Boolean = false,
    val phoneSignalStrength: Int? = null,
    val wifiFrequencyMhz: Int = 0,
    val displayMode: String = AppPreferences.DEFAULT_DISPLAY_MODE,
    val safeAreaTop: Int = AppPreferences.DEFAULT_SAFE_AREA_TOP,
    val safeAreaBottom: Int = AppPreferences.DEFAULT_SAFE_AREA_BOTTOM,
    val safeAreaLeft: Int = AppPreferences.DEFAULT_SAFE_AREA_LEFT,
    val safeAreaRight: Int = AppPreferences.DEFAULT_SAFE_AREA_RIGHT,
    val videoScalingMode: String = AppPreferences.DEFAULT_VIDEO_SCALING_MODE,
    val aaPixelAspect: Int = -1,
    val aaDpi: Int = 160,
    /** Density that was actually sent to the phone in the SDR.
     *  0 until first session built. Surfaced in Stats overlay so the user
     *  can see what auto-DPI picked vs. their manual slider value. */
    val effectiveDpi: Int = 0,
    val aaWidthMargin: Int = 0,
    val aaHeightMargin: Int = 0,
    val aaAutoMargins: Boolean = AppPreferences.DEFAULT_AA_AUTO_MARGINS,
    val fileLoggingActive: Boolean = false,
    val fileLoggingPath: String? = null,
    val fileLoggingEnabled: Boolean = false,
)

@OptIn(kotlinx.coroutines.FlowPreview::class)
class ProjectionViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        private const val TAG = "ProjectionViewModel"
    }

    private val preferences = AppPreferences.getInstance(application)
    private val knownPhonesStore = KnownPhonesStore(preferences)
    private val audioManager = application.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val connectivityManager = application.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private val sessionManager = SessionManager.getInstance(viewModelScope, application, audioManager)
    @Volatile private var selectedNetworkInterfaceName: String = ""
    @Volatile private var lastTransportNetworkEventAt: Long = 0L
    private val trackedTransportNetworks = mutableSetOf<Long>()

    /** Suppress config_echo DataStore writes while Settings is open. */
    fun setSettingsOpen(open: Boolean) {
    }

    /**
     * Push the OS-reported safe-area insets (system bars ∪ display cutouts)
     * down to SessionManager so the next start()/reconnect() can use them
     * as fallback for AA `content_insets` when the user hasn't manually
     * overridden them in settings.
     */
    fun setSystemInsets(top: Int, bottom: Int, left: Int, right: Int) {
        sessionManager.setSystemInsets(top, bottom, left, right)
    }

    /**
     * Push the live render-rect dims (Compose Box content area after
     * displayMode padding) and the panel's reported DPI down to
     * SessionManager so auto-DPI math can pick a density that produces
     * native-AAOS-equivalent UI sizes regardless of displayMode.
     */
    fun setRenderRect(widthPx: Int, heightPx: Int, panelDpi: Int, displayMode: String? = null) {
        sessionManager.setRenderRect(widthPx, heightPx, panelDpi, displayMode)
    }

    private val touchForwarder: TouchForwarder = TouchForwarderImpl { touchMessage ->
        viewModelScope.launch {
            sessionManager.sendControlMessage(touchMessage)
        }
    }

    private val steeringWheelController = SteeringWheelController(
        sendMessage = { buttonMessage ->
            viewModelScope.launch {
                sessionManager.sendControlMessage(buttonMessage)
            }
        },
        audioManager = audioManager
    )

    private val _phoneName = MutableStateFlow<String?>(null)
    private val _videoStats = MutableStateFlow(VideoStats())
    private val _audioStats = MutableStateFlow(AudioStats())
    private val _showStats = MutableStateFlow(false)
    private val _showPhoneChooser = MutableStateFlow(false)
    /**
     * Transient status surfaced inside the Car Hotspot chooser. Set when the
     * directed warm-cache loop has been trying without success long enough
     * that the user should verify their setup. Cleared on next select / dismiss.
     */
    private val _carHotspotChooserMessage = MutableStateFlow<String?>(null)
    val carHotspotChooserMessage: StateFlow<String?> = _carHotspotChooserMessage.asStateFlow()
    private val _fileLoggingActive = MutableStateFlow(false)
    private val _fileLoggingPath = MutableStateFlow<String?>(null)
    private var fileLogWriter: FileLogWriter? = null
    private var logcatCapture: LogcatCapture? = null

    // Pending surface — stored when surfaceCreated fires before decoder exists.
    // Attached to decoder on session start or when decoder becomes available.
    private var pendingSurface: Surface? = null
    private var pendingSurfaceWidth: Int = 0
    private var pendingSurfaceHeight: Int = 0
    private var surfaceDebounceJob: kotlinx.coroutines.Job? = null

    private val transportNetworkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            handleTransportNetworkUpdate(network, "available")
        }

        override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
            handleTransportNetworkUpdate(network, "capabilities")
        }

        override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) {
            handleTransportNetworkUpdate(network, "link")
        }

        override fun onLost(network: Network) {
            val handle = network.networkHandle
            val wasTracked = synchronized(trackedTransportNetworks) {
                trackedTransportNetworks.remove(handle)
            }
            if (!wasTracked) return
            requestTransportReconnect("lost")
        }
    }

    val uiState: StateFlow<ProjectionUiState> = combine(
        sessionManager.sessionState,
        sessionManager.statusMessage,
        _phoneName,
        _videoStats,
        _audioStats,
        _showStats,
        sessionManager.currentManeuver,
        sessionManager.phoneBatteryLevel,
        sessionManager.phoneBatteryCritical,
        sessionManager.voiceSessionActive,
        preferences.displayMode,
        preferences.safeAreaTop,
        preferences.safeAreaBottom,
        preferences.safeAreaLeft,
        preferences.safeAreaRight,
        sessionManager.phoneSignalStrength,
        preferences.videoScalingMode,
        sessionManager.wifiFrequencyMhz,
        preferences.aaDpi,
        sessionManager.effectiveDpi,
        preferences.aaPixelAspect,
        preferences.aaWidthMargin,
        preferences.aaHeightMargin,
        preferences.aaAutoMargins,
        _fileLoggingActive,
        _fileLoggingPath,
        preferences.fileLoggingEnabled,
    ) { values ->
        ProjectionUiState(
            sessionState = values[0] as SessionState,
            statusMessage = values[1] as String,
            phoneName = values[2] as? String,
            videoStats = values[3] as VideoStats,
            audioStats = values[4] as AudioStats,
            showStats = values[5] as Boolean,
            maneuver = values[6] as? ManeuverState,
            phoneBatteryLevel = values[7] as? Int,
            phoneBatteryCritical = values[8] as Boolean,
            voiceSessionActive = values[9] as Boolean,
            displayMode = values[10] as String,
            safeAreaTop = values[11] as Int,
            safeAreaBottom = values[12] as Int,
            safeAreaLeft = values[13] as Int,
            safeAreaRight = values[14] as Int,
            phoneSignalStrength = values[15] as? Int,
            videoScalingMode = values[16] as String,
            wifiFrequencyMhz = values[17] as Int,
            aaDpi = values[18] as Int,
            effectiveDpi = values[19] as Int,
            aaPixelAspect = values[20] as Int,
            aaWidthMargin = values[21] as Int,
            aaHeightMargin = values[22] as Int,
            aaAutoMargins = values[23] as Boolean,
            fileLoggingActive = values[24] as Boolean,
            fileLoggingPath = values[25] as? String,
            fileLoggingEnabled = values[26] as Boolean,
        )
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5000),
        ProjectionUiState()
    )

    init {
        registerTransportNetworkCallback()

        // Collect video and audio stats when streaming
        viewModelScope.launch {
            sessionManager.sessionState.collect { state ->
                // Attach pending surface when decoder becomes available
                if (state == SessionState.CONNECTED ||
                    state == SessionState.STREAMING) {
                    attachPendingSurface()
                }
                if (state == SessionState.STREAMING) {
                    sessionManager.videoStats?.let { statsFlow ->
                        launch {
                            statsFlow.collect { stats ->
                                _videoStats.value = stats
                            }
                        }
                    }
                    sessionManager.audioStats?.let { statsFlow ->
                        launch {
                            statsFlow.collect { stats ->
                                _audioStats.value = stats
                            }
                        }
                    }
                }
            }
        }
    }

    @Volatile private var hasConnected = false
    /**
     * Set while a [connect] coroutine is between claiming the slot and
     * handing off to [SessionManager.start]. Prevents the parallel-connect
     * storm we saw in early logs (21 simultaneous connect() invocations
     * each running the full resolve pipeline).
     */
    @Volatile private var connectInFlight = false
    /** Throttle for the auto-reconnect collector (Car Hotspot mode). */
    @Volatile private var lastAutoReconnectAttemptMs: Long = 0L
    private val AUTO_RECONNECT_MIN_GAP_MS = 10_000L
    /**
     * mDNS-only grace window inside [resolveCarHotspotPhone]. mDNS is
     * passive and zero-cost, so we always give it a brief head-start
     * before kicking the active /24 sweep.
     */
    private val MDNS_GRACE_MS = 3_000L
    /**
     * Background sweep cadence while idle in Car Hotspot mode with a
     * default phone set. Covers two scenarios where mDNS alone wouldn't
     * surface the phone: (1) mid-drive session drops where mDNS is
     * filtered (e.g. AAOS 13 IPv6-only NSD), (2) car wake-from-sleep
     * before the WiFi callback fires. The sweep is fast (~400ms wall
     * time, 128-way parallel) and only runs while idle, so the cost is
     * negligible.
     */
    private val IDLE_SWEEP_INTERVAL_MS = 15_000L
    /**
     * Wake-gap threshold above which the in-memory active phone pick is
     * cleared so the persisted default phone re-wins. Anything past this is
     * presumed to be a "new visit to the car": mid-drive WiFi blips and brief
     * pauses are typically under 30s, while AAOS actually suspending the SoC
     * long enough to produce a >1 min gap means the user almost certainly
     * left the car (or the car genuinely powered down). Tunable from
     * always-USB-log captures once we have real wake-gap data.
     */
    private val LONG_WAKE_CLEAR_ACTIVE_PHONE_MS = 60_000L  // 1 minute
    /**
     * Consecutive auto-reconnect attempts after which we escalate to the
     * phone picker. Auto-reconnect with backoff is fine for short blips but
     * if we're this far in, the user needs to intervene (wrong phone,
     * companion not running, network change).
     *
     * Tuning: each TCP attempt is `CONNECT_TIMEOUT_MS` (5s) + `RETRY_DELAY_MS`
     * (3s) = 8s, so threshold=2 surfaces the picker ~16s after the user
     * gets in the car, vs ~24s at threshold=3. WiFi blips that come back
     * within 5-10s still ride out cleanly because the auto-reconnect
     * counter resets the moment a connection succeeds — even if the picker
     * has opened, the chooser-auto-close fix slams it shut on STREAMING.
     */
    private val PICKER_ESCALATION_THRESHOLD = 2
    /**
     * Minimum wake gap that should force an auto-reconnect re-arm. Short
     * pause/resume blips (sub-second) shouldn't trigger; anything that looks
     * like the head unit actually suspended should.
     */
    private val WAKE_AUTO_RECONNECT_MIN_GAP_MS = 3_000L
    private val connectLock = Any()

    fun connect() {
        connect(overrideIp = null)
    }

    /**
     * Connect with an optional one-shot IP override (e.g. user picked a
     * specific phone in the Car Hotspot chooser). The override is captured
     * by-value here so a concurrent caller can't race on a shared field.
     */
    fun connect(overrideIp: String?) {
        // Open the chooser instead of auto-connecting when:
        //   - "Always ask" is on (Behavior 2), OR
        //   - No default phone is set yet (first-run or after Forget — the
        //     user hasn't told us which phone to prefer, so don't guess).
        // Explicit picks pass overrideIp and bypass this gate entirely.
        //
        // IMPORTANT: read these prefs via `.first()` instead of `.value` on
        // the stateIn StateFlows — the StateFlows seed an INITIAL VALUE
        // (empty / DEFAULT_*) before DataStore has actually emitted, so a
        // connect() called immediately after Activity creation (e.g. from
        // the projection screen's DisposableEffect) saw stale "no default
        // phone" and popped the chooser even when the user had a saved
        // default. DataStore's Flow guarantees the first emission carries
        // the persisted value, so awaiting it here is correct and cheap.
        if (overrideIp == null) {
            // Acquire the in-flight slot synchronously before suspending so
            // concurrent connect() callers don't all race past the gate.
            synchronized(connectLock) {
                if (hasConnected && sessionManager.sessionState.value != SessionState.IDLE) {
                    sessionManager.ensureClusterAlive()
                    return
                }
                if (connectInFlight) {
                    OalLog.d(TAG, "connect() ignored — another connect coroutine is already in flight")
                    return
                }
                connectInFlight = true
                hasConnected = true
            }
            viewModelScope.launch {
                try {
                    val mode = preferences.connectionMode.first()
                    if (mode == AppPreferences.CONNECTION_MODE_CAR_HOTSPOT) {
                        val defaultId = preferences.defaultPhoneId.first()
                        val askMode = preferences.alwaysAskPhone.first()
                        val noDefault = defaultId.isBlank()
                        if (noDefault || askMode) {
                            val reason = when {
                                askMode && noDefault -> "always-ask + no default"
                                askMode -> "always-ask is on"
                                else -> "no default phone set"
                            }
                            OalLog.i(TAG, "Opening chooser instead of auto-connecting: $reason")
                            _showPhoneChooser.value = true
                            phoneDiscovery.start()
                            return@launch
                        }
                    }
                    doConnect(overrideIp = null)
                } catch (e: Exception) {
                    OalLog.e(TAG, "connect() failed: ${e.message}")
                } finally {
                    connectInFlight = false
                }
            }
            return
        }

        // Explicit override (chooser tap) — bypass the chooser-open gate and
        // run the connect pipeline directly.
        synchronized(connectLock) {
            if (hasConnected && sessionManager.sessionState.value != SessionState.IDLE) {
                sessionManager.ensureClusterAlive()
                return
            }
            if (connectInFlight) {
                OalLog.d(TAG, "connect() ignored — another connect coroutine is already in flight")
                return
            }
            connectInFlight = true
            hasConnected = true
        }
        viewModelScope.launch {
            try {
                doConnect(overrideIp)
            } catch (e: Exception) {
                OalLog.e(TAG, "connect() failed: ${e.message}")
            } finally {
                connectInFlight = false
            }
        }
    }

    /**
     * Inner connect — assumes the in-flight slot is already claimed and the
     * chooser-open gate has been cleared. Reads all remaining settings and
     * hands off to SessionManager.start.
     */
    private suspend fun doConnect(overrideIp: String?) {
            val codec = preferences.videoCodec.first()
            val micSrc = preferences.micSource.first()
            val scalingMode = preferences.videoScalingMode.first()
            val hotspotSsid = preferences.hotspotSsid.first()
            val hotspotPassword = preferences.hotspotPassword.first()
            val directTransport = preferences.directTransport.first()
            val videoAutoNeg = preferences.videoAutoNegotiate.first()
            val aaRes = preferences.aaResolution.first()
            val aaDpi = preferences.aaDpi.first()
            val aaAutoDpi = preferences.aaAutoDpi.first()
            OalLog.i(TAG, "Connect with aaDpi=$aaDpi (auto=$aaAutoDpi) aaRes=$aaRes codec=$codec autoNeg=$videoAutoNeg")
            val aaWM = preferences.aaWidthMargin.first()
            val aaHM = preferences.aaHeightMargin.first()
            val aaPA = preferences.aaPixelAspect.first()
            val aaTargetLayoutDp = preferences.aaTargetLayoutWidthDp.first()
            val aaViewDistMm = preferences.aaViewingDistanceMm.first()
            val aaDecAddDepth = preferences.aaDecoderAdditionalDepth.first()
            val aaAutoM = preferences.aaAutoMargins.first()
            val videoFps = preferences.videoFps.first()
            val driveSide = preferences.driveSide.first()
            val hideClock = preferences.hideAaClock.first()
            val hideSignal = preferences.hidePhoneSignal.first()
            val hideBattery = preferences.hideBatteryLevel.first()

            // Safe area insets
            val saTop = preferences.safeAreaTop.first()
            val saBottom = preferences.safeAreaBottom.first()
            val saLeft = preferences.safeAreaLeft.first()
            val saRight = preferences.safeAreaRight.first()

            // Load key remap from preferences
            val keyRemapJson = preferences.keyRemap.first()
            if (keyRemapJson.isNotBlank()) {
                try {
                    val map = mutableMapOf<Int, Int>()
                    val json = org.json.JSONObject(keyRemapJson)
                    for (key in json.keys()) {
                        map[key.toInt()] = json.getInt(key)
                    }
                    steeringWheelController.customKeyMap = map
                } catch (_: Exception) {
                    steeringWheelController.customKeyMap = emptyMap()
                }
            }

            // Load volume offsets
            val volMedia = preferences.volumeOffsetMedia.first()
            val volNav = preferences.volumeOffsetNavigation.first()
            val volAssistant = preferences.volumeOffsetAssistant.first()

            // Load manual IP for emulator testing
            val manualIpEnabled = preferences.manualIpEnabled.first()
            val manualIpFromPrefs = if (manualIpEnabled) preferences.manualIpAddress.first().takeIf { it.isNotBlank() } else null

            // Resolve the effective IP for this connect attempt:
            //   1. Explicit [overrideIp] from the Car Hotspot chooser wins.
            //   2. In Car Hotspot mode, look up the default (or first
            //      currently-discovered) phone via [phoneDiscovery] and use
            //      its IP. If discovery hasn't surfaced anything yet, wait
            //      briefly before giving up.
            //   3. Fall back to the persistent manual-IP setting.
            val mode = preferences.connectionMode.first()
            val carHotspotPhone = if (overrideIp == null && mode == AppPreferences.CONNECTION_MODE_CAR_HOTSPOT) {
                // Long budget: with directed probing (no /24 sweep) this is
                // cheap — just one TCP probe per known IP every
                // [WARM_CACHE_RETRY_GAP_MS]. The user's guidance is to set a
                // static IP on the phone for the car's WiFi; once that's done
                // we want to keep retrying the known IP rather than nag the
                // user with a chooser. If the AP genuinely re-leased a new
                // IP, the user can press Scan in the chooser.
                resolveCarHotspotPhone(timeoutMs = 45_000)
            } else null
            val carHotspotIp: String? = carHotspotPhone?.let { phone ->
                // Append the discovered port if it differs from the canonical
                // companion port. Production phones all use 5277 so this is
                // usually a no-op; the debug discovery-injection broadcast
                // can specify other ports for emulator-via-USB testing.
                val host = phone.host ?: return@let null
                if (phone.port != 0 && phone.port != com.openautolink.app.transport.hotspot.TcpConnector.COMPANION_PORT) {
                    "$host:${phone.port}"
                } else {
                    host
                }
            }
            val manualIp = overrideIp ?: carHotspotIp ?: manualIpFromPrefs
            if (mode == AppPreferences.CONNECTION_MODE_CAR_HOTSPOT) {
                OalLog.i(
                    TAG,
                    "Car Hotspot connect: overrideIp=$overrideIp resolved=$carHotspotIp final=$manualIp",
                )
                // Track which phone we're dialing so the chooser can show
                // the ACTIVE badge correctly. Explicit picks set this in
                // selectCarHotspotPhone; auto-connect sets it here.
                if (overrideIp == null && carHotspotPhone != null) {
                    val pickedId = carHotspotPhone.phoneId
                    if (!pickedId.isNullOrBlank()) {
                        _activePhoneId.value = pickedId
                    }
                }
                // Resolve failed and we have nothing to fall back to → guide
                // the user. Re-open the chooser with a clear message; the
                // user can re-tap a phone or press Scan.
                if (carHotspotPhone == null && manualIp == null) {
                    val defaultName = try {
                        knownPhonesStore.phones.first()
                            .firstOrNull { it.phoneId == defaultPhoneId.value }
                            ?.friendlyName
                    } catch (_: Exception) { null }
                    val who = defaultName ?: "your phone"
                    _carHotspotChooserMessage.value =
                        "Couldn't reach $who. Verify it's connected to this car's WiFi and the companion app is started. " +
                            "If the connection looks good, tap your phone again. If its IP changed, press Scan."
                    _showPhoneChooser.value = true
                    OalLog.w(TAG, "Car Hotspot resolve gave up after 45s — re-opening chooser with guidance")
                    return
                }
            }

            // Load default phone name for auto-connect
            val defaultPhone = preferences.defaultPhoneName.first()
            sessionManager.setDefaultPhoneName(defaultPhone)

            sessionManager.start(
                codecPreference = codec,
                micSourcePreference = micSrc,
                scalingMode = scalingMode,
                directTransport = directTransport,
                hotspotSsid = hotspotSsid,
                hotspotPassword = hotspotPassword,
                videoAutoNegotiate = videoAutoNeg,
                aaResolution = aaRes,
                aaDpi = aaDpi,
                aaAutoDpi = aaAutoDpi,
                aaWidthMargin = aaWM,
                aaHeightMargin = aaHM,
                aaPixelAspect = aaPA,
                aaTargetLayoutWidthDp = aaTargetLayoutDp,
                aaViewingDistanceMm = aaViewDistMm,
                aaDecoderAdditionalDepth = aaDecAddDepth,
                aaAutoMargins = aaAutoM,
                videoFps = videoFps,
                driveSide = driveSide,
                hideClock = hideClock,
                hideSignal = hideSignal,
                hideBattery = hideBattery,
                volumeOffsetMedia = volMedia,
                volumeOffsetNavigation = volNav,
                volumeOffsetAssistant = volAssistant,
                manualIpAddress = manualIp,
                safeAreaTop = saTop,
                safeAreaBottom = saBottom,
                safeAreaLeft = saLeft,
                safeAreaRight = saRight,
            )
    }

    /**
     * Force reconnect — used by "Save & Connect" button in Settings and
     * other manual reconnect triggers.
     *
     * Stops cleanly and re-runs the full connect pipeline. In Car Hotspot
     * mode that pipeline already starts with a warm-cache probe (Phase 0
     * of [resolveCarHotspotPhone]) so reconnects are sub-second when the
     * AP re-leased the same IP, and fall through gracefully when it
     * didn't. **Don't** pass a cached IP as overrideIp here: if the IP
     * is stale, [TcpConnector] will retry forever on it because manualIp
     * mode has no fallback.
     */
    fun reconnect() {
        viewModelScope.launch {
            OalLog.i(TAG, "reconnect(): tearing down current session")
            sessionManager.stop()
            hasConnected = false
            connect(overrideIp = null)
        }
    }

    fun disconnect() {
        sessionManager.stop()
    }

    // --- Multi-phone: Phone Chooser ---

    /**
     * Live phone discovery for Car Hotspot mode. Runs mDNS passively while
     * projection is visible; sweep is on-demand from the chooser UI. Results
     * carry source tags ([PhoneDiscovery.Source.MDNS] / SWEEP / BOTH) so the
     * UX can show which mechanism worked.
     */
    private val phoneDiscovery = com.openautolink.app.transport.PhoneDiscovery.getInstance(application)
    val carHotspotPhones: StateFlow<List<com.openautolink.app.transport.PhoneDiscovery.DiscoveredPhone>> =
        phoneDiscovery.phones
    val carHotspotSweepActive: StateFlow<Boolean> = phoneDiscovery.isSweeping
    val carHotspotSweepProgress: StateFlow<String> = phoneDiscovery.sweepProgress

    /**
     * Current connection mode: phone-hotspot (default) or car-hotspot.
     * Drives whether the multi-phone UX is exposed in the projection screen.
     */
    val connectionMode: StateFlow<String> = preferences.connectionMode.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5000),
        AppPreferences.DEFAULT_CONNECTION_MODE,
    )

    /** Persistent known-phones list, surfaced for the chooser + settings. */
    val knownPhones: StateFlow<List<KnownPhone>> = knownPhonesStore.phones.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5000),
        emptyList(),
    )

    /** Currently-preferred phone_id (empty string = no default set). */
    val defaultPhoneId: StateFlow<String> = preferences.defaultPhoneId.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5000),
        AppPreferences.DEFAULT_DEFAULT_PHONE_ID,
    )

    /**
     * Whether the user has opted out of auto-connecting to the saved default
     * phone (Behavior 2). When true, the chooser is shown on connect even if
     * a default exists.
     */
    val alwaysAskPhone: StateFlow<Boolean> = preferences.alwaysAskPhone.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5000),
        AppPreferences.DEFAULT_ALWAYS_ASK_PHONE,
    )

    /** True while the car app is tearing down + redialing for a phone switch. */
    private val _carHotspotSwitching = MutableStateFlow(false)
    val carHotspotSwitching: StateFlow<Boolean> = _carHotspotSwitching.asStateFlow()

    /** Stashed WiFi callback so we can unregister in onCleared. */
    private var wifiAvailableCallback: ConnectivityManager.NetworkCallback? = null

    /**
     * High-level Car Hotspot connection status surfaced to the projection
     * screen so the user always knows what's happening when streaming
     * isn't yet active. Distinct from [SessionState] because it captures
     * pre-session phases (scanning, picking, no default set).
     */
    enum class CarHotspotStatus {
        /** Car Hotspot mode isn't selected, no banner needed. */
        INACTIVE,
        /** Need user to pick a phone (no default set, or "always ask"). */
        AWAITING_USER_PICK,
        /** Searching the network — mDNS + sweep in flight. */
        SEARCHING,
        /** Tearing down + dialing a different phone. */
        SWITCHING,
        /** Found the phone, AA handshake in progress. */
        CONNECTING,
        /** AA streaming. */
        STREAMING,
        /** Discovery cycle finished without finding the default phone. */
        PHONE_NOT_FOUND,
    }
    private val _carHotspotStatus = MutableStateFlow(CarHotspotStatus.INACTIVE)
    val carHotspotStatus: StateFlow<CarHotspotStatus> = _carHotspotStatus.asStateFlow()
    /** Optional human-readable detail line under the headline status. */
    private val _carHotspotStatusDetail = MutableStateFlow<String?>(null)
    val carHotspotStatusDetail: StateFlow<String?> = _carHotspotStatusDetail.asStateFlow()

    /**
     * Last phone_id we deliberately dialed via the Car Hotspot flow. Used by
     * the chooser UI to mark the ACTIVE phone reliably (vs. comparing by
     * friendly_name, which is user-editable and not unique).
     */
    private val _activePhoneId = MutableStateFlow<String?>(null)
    val activePhoneId: StateFlow<String?> = _activePhoneId.asStateFlow()

    init {
        // Resolve the projection overlay's "connected phone" label from
        // whatever we currently know: prefer the live mDNS friendly_name for
        // [_activePhoneId], fall back to the known-phones store entry, and
        // null out when no phone is active. Recomputes when any of the three
        // inputs change. Must live in this init block (not the earlier one)
        // because phoneDiscovery and _activePhoneId are declared between the
        // two init blocks and Kotlin initializes properties in declaration
        // order.
        viewModelScope.launch {
            kotlinx.coroutines.flow.combine(
                _activePhoneId,
                phoneDiscovery.phones,
                knownPhonesStore.phones,
            ) { activeId, discovered, known ->
                if (activeId.isNullOrBlank()) return@combine null
                discovered.firstOrNull { it.phoneId == activeId }?.friendlyName
                    ?: known.firstOrNull { it.phoneId == activeId }?.friendlyName
            }.collect { name -> _phoneName.value = name }
        }
        // Continuously run mDNS discovery while in Car Hotspot mode. This
        // keeps `knownPhones` "online" status fresh and lets the floating
        // switcher button surface phones the moment they appear on the AP.
        viewModelScope.launch {
            connectionMode.collect { mode ->
                if (mode == AppPreferences.CONNECTION_MODE_CAR_HOTSPOT) {
                    phoneDiscovery.start()
                } else {
                    phoneDiscovery.stop()
                }
            }
        }
        // Debug aid for emulator testing: when manualIpEnabled is on, inject
        // a synthetic resolved phone into PhoneDiscovery so the picker /
        // auto-reconnect / IP-cache flows exercise the same code paths as
        // the car. The AVD's 10.0.2.0/24 NAT prevents real discovery from
        // ever surfacing a phone on the host's WiFi, but outbound TCP to a
        // home-WiFi IP still works (NAT'd through the host). On real
        // hardware the user shouldn't have manualIpEnabled on, so this is
        // a no-op there.
        viewModelScope.launch {
            kotlinx.coroutines.flow.combine(
                preferences.manualIpEnabled,
                preferences.manualIpAddress,
            ) { enabled, ip -> if (enabled && ip.isNotBlank()) ip else "" }
                .distinctUntilChanged()
                .collect { ip ->
                    if (ip.isNotBlank()) {
                        phoneDiscovery.injectDebugPhone(host = ip)
                    }
                }
        }
        // Auto-touch known phones as their identity becomes visible. We
        // distinct on (id, name, host) tuple so identical successive
        // emissions don't drive any DataStore writes (KnownPhonesStore.touch
        // also throttles by lastSeen, but cutting off here saves the
        // suspend round-trip entirely).
        viewModelScope.launch {
            phoneDiscovery.phones
                .map { list ->
                    list.mapNotNull { p ->
                        val id = p.phoneId
                        if (id.isNullOrBlank()) null
                        else id to (p.friendlyName ?: "")
                    }.toSet()
                }
                .distinctUntilChanged()
                .collect { tuples ->
                    val mode = connectionMode.value
                    if (mode != AppPreferences.CONNECTION_MODE_CAR_HOTSPOT) return@collect
                    tuples.forEach { (id, name) ->
                        knownPhonesStore.touch(
                            phoneId = id,
                            friendlyName = name.takeIf { it.isNotBlank() },
                        )
                    }
                }
        }
        // Clear the in-memory active-phone pick on a long wake gap. The pick
        // sticks across mid-drive WiFi blips and short pit-stops (intentional:
        // "I picked phone B for this drive"), but a long sleep gap proxies for
        // "user left the car / fresh visit," after which the default phone
        // should win again. Threshold tuned conservatively — see
        // [LONG_WAKE_CLEAR_ACTIVE_PHONE_MS].
        viewModelScope.launch {
            sessionManager.wakeEvents.collect { event ->
                if (event.gapMs >= LONG_WAKE_CLEAR_ACTIVE_PHONE_MS &&
                    _activePhoneId.value != null) {
                    OalLog.i(
                        TAG,
                        "Wake gap ${event.gapMs}ms ≥ ${LONG_WAKE_CLEAR_ACTIVE_PHONE_MS}ms — " +
                            "clearing active phone pick (reason=${event.reason})",
                    )
                    _activePhoneId.value = null
                }
                // Force-kick auto-reconnect on wake. `phoneDiscovery.phones`
                // edge-triggered collector only fires when isResolved flips
                // false→true; after a short sleep the phone often stays
                // resolved across the gap, so the edge never happens and we
                // sit idle. The wake event is a level signal — use it to
                // resync. Gate on the same conditions as the edge collector.
                if (event.gapMs >= WAKE_AUTO_RECONNECT_MIN_GAP_MS &&
                    connectionMode.value == AppPreferences.CONNECTION_MODE_CAR_HOTSPOT &&
                    !alwaysAskPhone.value &&
                    defaultPhoneId.value.isNotBlank() &&
                    !connectInFlight &&
                    sessionManager.sessionState.value == SessionState.IDLE &&
                    phoneDiscovery.phones.value.any { it.isResolved }) {
                    val now = SystemClock.elapsedRealtime()
                    if (now - lastAutoReconnectAttemptMs >= AUTO_RECONNECT_MIN_GAP_MS) {
                        lastAutoReconnectAttemptMs = now
                        OalLog.i(
                            TAG,
                            "Wake event (gap=${event.gapMs}ms) — kicking auto-reconnect",
                        )
                        hasConnected = false
                        connect()
                    }
                }
            }
        }
        // Auto-close the phone chooser once we successfully reach STREAMING.
        // Without this the picker stays up after a successful tap-reconnect
        // and the user has to dismiss it manually.
        viewModelScope.launch {
            sessionManager.sessionState
                .collect { state ->
                    if (state == SessionState.STREAMING && _showPhoneChooser.value) {
                        OalLog.i(TAG, "Session STREAMING — auto-closing phone chooser")
                        _showPhoneChooser.value = false
                        _carHotspotChooserMessage.value = null
                    }
                }
        }
        // Escalate to the picker after [PICKER_ESCALATION_THRESHOLD]
        // consecutive auto-reconnect failures. The reconnect itself is fine
        // (keeps retrying with backoff) but if we're failing this much, the
        // user almost certainly needs to intervene — wrong phone selected,
        // companion not running, network changed, etc. Open the chooser with
        // a contextual message so they have one tap to resolve it.
        viewModelScope.launch {
            sessionManager.reconnectAttempt
                .collect { attempt ->
                    if (attempt < PICKER_ESCALATION_THRESHOLD) return@collect
                    // Don't fight the user: if the chooser is already open
                    // (they might be mid-selection) or always-ask is on
                    // (chooser-driven mode), don't re-open or re-set the
                    // message.
                    if (_showPhoneChooser.value) return@collect
                    if (alwaysAskPhone.value) return@collect
                    val mode = connectionMode.value
                    if (mode != AppPreferences.CONNECTION_MODE_CAR_HOTSPOT) return@collect

                    val activeId = _activePhoneId.value
                    val defaultId = defaultPhoneId.value
                    val targetId = activeId ?: defaultId.takeIf { it.isNotBlank() }
                    val targetName = targetId?.let { id ->
                        knownPhonesStore.phones.first()
                            .firstOrNull { it.phoneId == id }?.friendlyName
                    } ?: "your phone"
                    OalLog.w(
                        TAG,
                        "Reconnect attempt $attempt reached escalation threshold — opening chooser",
                    )
                    _carHotspotChooserMessage.value =
                        "Couldn't reach $targetName after $attempt attempts. " +
                            "Pick a phone or press Scan."
                    _showPhoneChooser.value = true
                }
        }
        // Drive the [carHotspotStatus] flow from connection mode +
        // sessionState + chooser visibility + switching flag + default-set
        // state. The projection UI uses this to render a clear "what's
        // happening" banner whenever streaming isn't yet active.
        viewModelScope.launch {
            kotlinx.coroutines.flow.combine(
                connectionMode,
                sessionManager.sessionState,
                _carHotspotSwitching,
                defaultPhoneId,
                alwaysAskPhone,
            ) { mode, state, switching, defId, askMode ->
                computeCarHotspotStatus(mode, state, switching, defId, askMode)
            }.collect { status ->
                if (_carHotspotStatus.value != status) {
                    OalLog.d(TAG, "carHotspotStatus: ${_carHotspotStatus.value} -> $status")
                }
                _carHotspotStatus.value = status
            }
        }
        // Periodic background sweep while idle in Car Hotspot mode + has
        // a default phone. Covers two cases the auto-reconnect collector
        // alone can't handle:
        //   1. Mid-drive session drop where mDNS is filtered (AAOS 13
        //      IPv6-only NSD). phoneDiscovery.phones never changes →
        //      collector never fires → stuck IDLE forever. Periodic
        //      sweep produces a fresh mDNS-equivalent hit.
        //   2. Car wake-from-sleep where the phone is on a brand new
        //      subnet and nothing has triggered a re-resolve yet.
        viewModelScope.launch {
            while (true) {
                kotlinx.coroutines.delay(IDLE_SWEEP_INTERVAL_MS)
                try {
                    val mode = connectionMode.value
                    val state = sessionManager.sessionState.value
                    val askMode = alwaysAskPhone.value
                    val haveDefault = defaultPhoneId.value.isNotBlank()
                    val idleAndCarHotspot = mode == AppPreferences.CONNECTION_MODE_CAR_HOTSPOT &&
                        state == SessionState.IDLE
                    if (!idleAndCarHotspot) continue
                    if (askMode) continue          // user wants to pick manually
                    if (!haveDefault) continue     // no default → chooser path handles it
                    if (connectInFlight) continue
                    if (phoneDiscovery.isSweeping.value) continue
                    OalLog.d(TAG, "Periodic idle probe (Car Hotspot, default set, no session)")
                    // Cheap-first: a single UDP broadcast costs ~1 packet
                    // and ~600ms wall time. Only escalate to the full /24
                    // sweep if broadcast comes back empty.
                    val hits = try { phoneDiscovery.udpBroadcastAllInterfaces(listenWindowMs = 600L) } catch (_: Exception) { 0 }
                    if (hits == 0) {
                        kickSweep()
                    }
                } catch (_: Exception) { /* keep loop alive */ }
            }
        }
        // WiFi-up trigger: when the head unit's WiFi (re)connects to the
        // car AP — typically the car waking from sleep — kick a discovery
        // immediately rather than waiting for the next [IDLE_SWEEP_INTERVAL_MS]
        // tick. Best-effort; if the callback fails we still have the
        // periodic timer above.
        try {
            val cm = application.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            val req = NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .build()
            val cb = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    val mode = connectionMode.value
                    if (mode != AppPreferences.CONNECTION_MODE_CAR_HOTSPOT) return
                    if (sessionManager.sessionState.value != SessionState.IDLE) return
                    if (!defaultPhoneId.value.isNotBlank()) return
                    if (alwaysAskPhone.value) return
                    if (connectInFlight) return
                    OalLog.i(TAG, "WiFi onAvailable — kicking sweep to find phone")
                    kickSweep()
                }
            }
            cm?.registerNetworkCallback(req, cb)
            // Stash for cleanup
            wifiAvailableCallback = cb
        } catch (e: Exception) {
            OalLog.w(TAG, "Couldn't register WiFi NetworkCallback: ${e.message}")
        }
        // Car Hotspot mode auto-reconnect: when we're idle and a phone
        // appears in discovery, kick off a connect. Throttled to one attempt
        // every [AUTO_RECONNECT_MIN_GAP_MS] so a flapping discovery flow
        // doesn't hammer connect() — sessionState transitions can briefly
        // dip back to IDLE during reconnect retries, which would otherwise
        // re-trigger this collector immediately.
        viewModelScope.launch {
            phoneDiscovery.phones
                .map { list -> list.any { it.isResolved } }
                .distinctUntilChanged()
                .collect { anyResolved ->
                    val mode = connectionMode.value
                    if (mode != AppPreferences.CONNECTION_MODE_CAR_HOTSPOT) return@collect
                    if (alwaysAskPhone.value) return@collect
                    // No default phone set → don't auto-connect; the user
                    // will pick from the chooser when they're ready.
                    if (defaultPhoneId.value.isBlank()) return@collect
                    if (!anyResolved) return@collect
                    // Bail before logging if a connect is already running or
                    // the session is already past IDLE — saves logging spam
                    // when discovery emits multiple times during sweep.
                    if (connectInFlight) return@collect
                    if (sessionManager.sessionState.value != SessionState.IDLE) return@collect
                    val now = SystemClock.elapsedRealtime()
                    if (now - lastAutoReconnectAttemptMs < AUTO_RECONNECT_MIN_GAP_MS) {
                        // Note: keep this at debug — under sweep flapping it
                        // can fire dozens of times in a few ms.
                        return@collect
                    }
                    lastAutoReconnectAttemptMs = now
                    OalLog.i(TAG, "Car Hotspot auto-reconnect: phone discovered while idle")
                    hasConnected = false
                    connect()
                }
        }

        // Auto-start file logging on USB whenever the pref is on AND a USB
        // drive is mounted. Watch both the pref and storage-volume mount
        // events so attaching a stick mid-session also starts logging.
        registerUsbStorageReceiver()
        viewModelScope.launch {
            preferences.fileLoggingAutoStartUsb.collect { enabled ->
                evaluateAutoUsbLogging(enabled, "pref-change")
            }
        }
    }

    /**
     * Pure mapping from inputs → [CarHotspotStatus]. Keeps the side-effect-
     * free logic out of the collector.
     */
    private fun computeCarHotspotStatus(
        mode: String,
        state: SessionState,
        switching: Boolean,
        defaultId: String,
        askMode: Boolean,
    ): CarHotspotStatus {
        if (mode != AppPreferences.CONNECTION_MODE_CAR_HOTSPOT) {
            _carHotspotStatusDetail.value = null
            return CarHotspotStatus.INACTIVE
        }
        if (switching) {
            _carHotspotStatusDetail.value = null
            return CarHotspotStatus.SWITCHING
        }
        return when (state) {
            SessionState.STREAMING -> {
                _carHotspotStatusDetail.value = null
                CarHotspotStatus.STREAMING
            }
            SessionState.CONNECTED, SessionState.CONNECTING -> {
                _carHotspotStatusDetail.value = null
                CarHotspotStatus.CONNECTING
            }
            SessionState.IDLE, SessionState.ERROR -> when {
                askMode -> {
                    _carHotspotStatusDetail.value = "Pick a phone in the chooser"
                    CarHotspotStatus.AWAITING_USER_PICK
                }
                defaultId.isBlank() -> {
                    _carHotspotStatusDetail.value = "Tap the phone icon to choose"
                    CarHotspotStatus.AWAITING_USER_PICK
                }
                phoneDiscovery.isSweeping.value || connectInFlight -> {
                    _carHotspotStatusDetail.value = phoneDiscovery.sweepProgress.value.takeIf { it.isNotBlank() }
                    CarHotspotStatus.SEARCHING
                }
                else -> {
                    // Idle, default set, not actively scanning — between
                    // sweep cycles. Treat as "searching" rather than the
                    // alarming "not found"; the periodic sweep will fire
                    // within IDLE_SWEEP_INTERVAL_MS.
                    _carHotspotStatusDetail.value = "Looking for your phone…"
                    CarHotspotStatus.SEARCHING
                }
            }
        }
    }

    /** Whether the phone chooser overlay is showing. */
    val showPhoneChooser: StateFlow<Boolean> = _showPhoneChooser.asStateFlow()

    /** Show the phone chooser: disconnect, restart discovery showing all phones. */
    fun showPhoneChooser() {
        _showPhoneChooser.value = true
        sessionManager.stop()
        hasConnected = false
        // Temporarily clear the default filter so all phones appear in discovery,
        // but don't persist — the saved default stays unchanged.
        viewModelScope.launch {
            val savedDefault = sessionManager.getDefaultPhoneName()
            sessionManager.setDefaultPhoneName("")
            connect()
            // Restore after discovery starts (the chooser UI handles selection)
        }
    }

    /**
     * Show the Car Hotspot phone chooser. Does NOT disconnect the active
     * session — the user can browse and dismiss without interruption.
     * Switching is only triggered by an explicit pick of a different phone.
     */
    fun showCarHotspotChooser() {
        _showPhoneChooser.value = true
        // mDNS keeps running passively while in Car Hotspot mode; don't fire
        // a sweep here. The user can press the explicit "Scan" button in
        // the chooser if mDNS hasn't found their phone.
    }

    /** User explicitly requested a re-scan from inside the chooser. */
    fun rescanCarHotspotPhones() {
        kickSweep()
    }

    /**
     * Start a sweep, honoring the user's auto-vs-manual interface preference.
     * When manual is selected, only the configured interface is scanned;
     * when auto is on, the full preferred → fallback two-phase sweep runs.
     */
    private fun kickSweep() {
        viewModelScope.launch {
            val auto = try { preferences.carHotspotAutoInterface.first() } catch (_: Exception) { true }
            if (auto) {
                phoneDiscovery.startSweep()
            } else {
                val name = try { preferences.carHotspotInterfaceName.first() } catch (_: Exception) { "" }
                phoneDiscovery.startSweep(forcedInterfaceName = name.takeIf { it.isNotBlank() })
            }
        }
    }

    /**
     * User picked a phone from the Car Hotspot chooser. Persists the phone,
     * sets it as the default if no default exists yet, and triggers a
     * session reconnect to that phone's IP.
     *
     * Identity match is by `phone_id` (stable UUID), not `friendly_name`
     * (user-editable, not unique).
     *
     * The [carHotspotSwitching] flag tracks the actual session-state
     * transition: it stays true until the new session reaches STREAMING,
     * times out at 30s, or the user dismisses the projection.
     */
    /** Reentrancy guard for [selectCarHotspotPhone]. The chooser row's
     *  click handler can fire many times per physical tap on AAOS touch
     *  surfaces (observed: 21× in 36ms in production logs). Without this
     *  guard each fire launches a coroutine that does sessionManager.stop()
     *  + connect(), racing 20 stops against one in-flight startup. */
    @Volatile private var selectPhoneInFlight = false

    fun selectCarHotspotPhone(phone: com.openautolink.app.transport.PhoneDiscovery.DiscoveredPhone) {
        if (selectPhoneInFlight) {
            OalLog.d(TAG, "selectCarHotspotPhone ignored — another selection is in flight")
            return
        }
        _showPhoneChooser.value = false
        _carHotspotChooserMessage.value = null
        val phoneId = phone.phoneId
        val host = phone.host
        if (phoneId.isNullOrBlank() || host.isNullOrBlank()) {
            OalLog.w(TAG, "Cannot select phone — missing phone_id or host: $phone")
            return
        }
        selectPhoneInFlight = true
        viewModelScope.launch {
            try {
                runSelectCarHotspotPhone(phone, phoneId, host)
            } finally {
                selectPhoneInFlight = false
            }
        }
    }

    private suspend fun runSelectCarHotspotPhone(
        phone: com.openautolink.app.transport.PhoneDiscovery.DiscoveredPhone,
        phoneId: String,
        host: String,
    ) {
            // Persist into the known-phones list. Auto-promote to default
            // only if there's no default set yet.
            knownPhonesStore.upsert(
                KnownPhone(
                    phoneId = phoneId,
                    friendlyName = phone.friendlyName ?: "Phone-${phoneId.take(4)}",
                    lastSeenMs = System.currentTimeMillis(),
                )
            )
            val currentDefault = preferences.defaultPhoneId.first()
            if (currentDefault.isBlank()) {
                preferences.setDefaultPhoneId(phoneId)
                OalLog.i(TAG, "Auto-promoted ${phone.friendlyName} to default phone")
            }

            // If this phone is already the active session, do nothing.
            if (_activePhoneId.value == phoneId) {
                OalLog.i(TAG, "Already connected to id=${phoneId.take(8)}; no switch needed")
                return
            }

            OalLog.i(
                TAG,
                "Switching to ${phone.friendlyName} ($host:${phone.port}) src=${phone.source}",
            )
            _carHotspotSwitching.value = true
            _activePhoneId.value = phoneId
            sessionManager.stop()
            hasConnected = false
            // Encode the port into the override string when it isn't the
            // canonical 5277 — TcpConnector parses "host:port" form so the
            // debug discovery-injection path can target USB-forwarded ports.
            val overrideStr = if (
                phone.port != 0 &&
                phone.port != com.openautolink.app.transport.hotspot.TcpConnector.COMPANION_PORT
            ) "$host:${phone.port}" else host
            connect(overrideIp = overrideStr)

            // Wait for the new session to settle. STREAMING means success;
            // any IDLE *after* we've seen at least one CONNECTING means the
            // attempt finished and bounced back without streaming (network
            // unreachable, handshake failed, etc.). 30s is the absolute
            // ceiling.
            val timeoutMs = 30_000L
            var sawConnecting = false
            val outcome = kotlinx.coroutines.withTimeoutOrNull(timeoutMs) {
                sessionManager.sessionState.first { state ->
                    if (state == SessionState.CONNECTING ||
                        state == SessionState.CONNECTED) sawConnecting = true
                    when {
                        state == SessionState.STREAMING -> true
                        state == SessionState.ERROR -> true
                        sawConnecting && state == SessionState.IDLE -> true
                        else -> false
                    }
                }
            }
            when (outcome) {
                null -> {
                    OalLog.w(TAG, "Switch to id=${phoneId.take(8)} timed out after ${timeoutMs}ms")
                    _activePhoneId.value = null
                }
                SessionState.STREAMING -> {
                    OalLog.i(TAG, "Switch to id=${phoneId.take(8)} succeeded")
                }
                else -> {
                    OalLog.w(
                        TAG,
                        "Switch to id=${phoneId.take(8)} failed: state settled to $outcome",
                    )
                    _activePhoneId.value = null
                }
            }
            _carHotspotSwitching.value = false
    }

    /** Mark a phone as the auto-connect default and persist. */
    fun setDefaultPhoneId(phoneId: String) {
        viewModelScope.launch {
            preferences.setDefaultPhoneId(phoneId)
            OalLog.i(TAG, "Default phone set to id=${phoneId.take(8)}")
        }
    }

    /** Forget a known phone (also clears it as default if it was set). */
    fun forgetKnownPhone(phoneId: String) {
        viewModelScope.launch {
            knownPhonesStore.remove(phoneId)
        }
    }

    /** Toggle Behavior 2: always show chooser instead of auto-connecting. */
    fun setAlwaysAskPhone(enabled: Boolean) {
        viewModelScope.launch { preferences.setAlwaysAskPhone(enabled) }
    }

    /**
     * Resolve a usable phone for the Car Hotspot connect flow.
     *
     * Strategy: **mDNS first, /24 sweep on fallback.** No IP cache —
     * automotive APs re-randomize their entire DHCP scope every boot
     * (`10.220.23.0/24` one drive, `10.59.121.0/24` the next), so any
     * cached "last known IP" is wrong as often as it's right. We always
     * re-discover fresh.
     *
     *   - mDNS passive grace window of [MDNS_GRACE_MS]. Returns
     *     immediately on AAOS 14+ if NSD's IPv4 path is healthy.
     *   - On expiry, kick a /24 TCP sweep on the AP-bridge interface(s)
     *     (`ap_br_swlan0` etc.). With high parallelism this completes in
     *     well under a second.
     *
     * Identity is keyed on `phone_id` — the chooser still tracks "your
     * phones" across drives even when their IPs change.
     */
    private suspend fun resolveCarHotspotPhone(
        timeoutMs: Long,
    ): com.openautolink.app.transport.PhoneDiscovery.DiscoveredPhone? {
        // Make sure discovery is actually running. The init-block flow starts
        // it on connectionMode change, but the user might call connect()
        // before that emit lands.
        phoneDiscovery.start()

        val defaultId = try {
            preferences.defaultPhoneId.first().takeIf { it.isNotBlank() }
        } catch (_: Exception) { null }

        // Phase 1: mDNS-only grace. Cheapest, fastest, no socket pressure.
        OalLog.i(TAG, "Resolving phone — mDNS grace ${MDNS_GRACE_MS}ms")
        val mdnsHit = kotlinx.coroutines.withTimeoutOrNull(MDNS_GRACE_MS) {
            phoneDiscovery.phones
                .map { list -> pickBestPhone(list, defaultId) }
                .first { it != null }
        }
        if (mdnsHit != null) {
            OalLog.i(TAG, "Resolved via mDNS within ${MDNS_GRACE_MS}ms")
            return mdnsHit
        }

        // Phase 2: UDP broadcast. ~50ms when the AP allows it. Sits
        // between mDNS (often broken on AAOS 12/13) and the full /24
        // sweep (always works, ~400ms wall time).
        OalLog.i(TAG, "mDNS grace expired — trying UDP broadcast")
        val broadcastHits = phoneDiscovery.udpBroadcastAllInterfaces(listenWindowMs = 600L)
        if (broadcastHits > 0) {
            val picked = pickBestPhone(phoneDiscovery.phones.value, defaultId)
            if (picked != null) {
                OalLog.i(TAG, "Resolved via UDP broadcast ($broadcastHits hit(s))")
                return picked
            }
        }

        // Phase 3: full /24 TCP sweep. Always works on cooperative APs.
        OalLog.i(TAG, "UDP broadcast empty — kicking /24 sweep")
        kickSweep()
        val remaining = (timeoutMs - MDNS_GRACE_MS - 600L).coerceAtLeast(2_000L)
        return kotlinx.coroutines.withTimeoutOrNull(remaining) {
            phoneDiscovery.phones
                .map { list -> pickBestPhone(list, defaultId) }
                .first { it != null }
        }
    }

    /**
     * Pick the most appropriate phone from the current discovery snapshot.
     * Prefers the default phone if it's currently resolved; otherwise the
     * first resolved phone in the list. Returns null if nothing is resolved.
     */
    private fun pickBestPhone(
        list: List<com.openautolink.app.transport.PhoneDiscovery.DiscoveredPhone>,
        defaultId: String?,
    ): com.openautolink.app.transport.PhoneDiscovery.DiscoveredPhone? {
        // Defensive: drop IPv6 link-local hosts that slipped through. They
        // aren't usable for TCP without a scope ID (e.g. %wlan0).
        val resolved = list.filter {
            it.isResolved && !it.host.isNullOrBlank() && !isUnusableHost(it.host)
        }
        if (resolved.isEmpty()) return null
        if (defaultId != null) {
            resolved.firstOrNull { it.phoneId == defaultId }?.let { return it }
        }
        return resolved.first()
    }

    private fun isUnusableHost(host: String): Boolean {
        // Cheap textual check — `fe80:` prefix covers IPv6 link-local. Avoids
        // creating an InetAddress just for filtering.
        return host.startsWith("fe80:", ignoreCase = true)
    }

    /**
     * Close the phone chooser without picking a new phone.
     *
     * In **Car Hotspot mode** this is purely a UI op — the chooser overlays
     * a live session and should never tear it down on dismiss. The user's
     * intent is "I changed my mind / closed the picker."
     *
     * In **Phone Hotspot mode** the chooser was historically opened by
     * actively disconnecting (so the user could pick from the discovery
     * list), so dismissing has to restore the default phone and reconnect.
     */
    fun dismissPhoneChooser() {
        _showPhoneChooser.value = false
        _carHotspotChooserMessage.value = null
        if (connectionMode.value == AppPreferences.CONNECTION_MODE_CAR_HOTSPOT) {
            // Car Hotspot: chooser was opened over a live session. Do nothing.
            return
        }
        // Phone Hotspot path: chooser was opened with the session torn down — restore.
        viewModelScope.launch {
            val savedDefault = preferences.defaultPhoneName.first()
            sessionManager.setDefaultPhoneName(savedDefault)
            hasConnected = false
            connect()
        }
    }

    /**
     * Resolve a saved network interface name to an [android.net.Network] for socket binding.
     * Uses two-tier lookup: first by interface name via ConnectivityManager, then falls back
     * to default routing if not found. Skips binding for loopback addresses.
     *
     * When no interface is configured (empty string), auto-selects eth0 if available.
     * On GM AAOS head units, a USB NIC always appears as eth0.
     */
    private fun resolveNetwork(interfaceName: String): Network? {
        val targetName = interfaceName.ifBlank {
            // Auto-select: prefer eth0 (USB NIC on GM AAOS), then any Ethernet interface
            val autoName = findDefaultEthernetInterface()
            if (autoName != null) {
                Log.i(TAG, "Auto-selected network interface: $autoName")
                com.openautolink.app.diagnostics.DiagnosticLog.i("transport",
                    "Auto-selected network interface: $autoName")
            }
            autoName ?: return null // no ethernet found — default routing
        }
        try {
            for (network in connectivityManager.allNetworks) {
                val linkProps = connectivityManager.getLinkProperties(network) ?: continue
                if (linkProps.interfaceName == targetName) {
                    val caps = connectivityManager.getNetworkCapabilities(network)
                    val transport = when {
                        caps?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) == true -> "Ethernet"
                        caps?.hasTransport(NetworkCapabilities.TRANSPORT_USB) == true -> "USB"
                        caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true -> "WiFi"
                        else -> "other"
                    }
                    Log.i(TAG, "Bound to interface '$targetName' ($transport) handle=${network.networkHandle}")
                    com.openautolink.app.diagnostics.DiagnosticLog.i("transport",
                        "Bound to interface '$targetName' ($transport)")
                    return network
                }
            }
            Log.w(TAG, "Interface '$targetName' not found in ConnectivityManager — default routing")
            com.openautolink.app.diagnostics.DiagnosticLog.w("transport",
                "Interface '$targetName' not found — default routing")
        } catch (e: Exception) {
            Log.w(TAG, "Network resolution failed: ${e.message}")
        }
        return null
    }

    /**
     * Find the best default Ethernet interface for bridge communication.
     * Prefers eth0 (USB NIC on GM AAOS), then any other Ethernet/USB transport interface.
     */
    private fun findDefaultEthernetInterface(): String? {
        var fallback: String? = null
        try {
            for (network in connectivityManager.allNetworks) {
                val caps = connectivityManager.getNetworkCapabilities(network) ?: continue
                val linkProps = connectivityManager.getLinkProperties(network) ?: continue
                val name = linkProps.interfaceName ?: continue
                val isEthernet = caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) ||
                        caps.hasTransport(NetworkCapabilities.TRANSPORT_USB)
                if (!isEthernet) continue
                if (name == "eth0") return name // preferred — GM AAOS USB NIC
                if (fallback == null) fallback = name
            }
        } catch (e: Exception) {
            Log.w(TAG, "Ethernet interface scan failed: ${e.message}")
        }
        return fallback
    }

    fun toggleStats() {
        _showStats.value = !_showStats.value
    }

    private var fileLogToggleLock = Any()

    /** True while the auto-start-on-USB pref owns the current file-logger session.
     *  When the user manually stops via the overlay record button we clear this
     *  so the auto-start observer won't immediately restart it on the next USB
     *  mount event \u2014 they'll have to toggle the pref off/on or restart the app. */
    @Volatile private var autoUsbLoggingActive = false

    fun toggleFileLogging() {
        synchronized(fileLogToggleLock) {
            if (_fileLoggingActive.value) {
                stopFileLoggingLocked()
                autoUsbLoggingActive = false
            } else {
                startFileLoggingLocked(requireRemovable = false)
            }
        }
    }

    /**
     * Internal start helper \u2014 must be called under [fileLogToggleLock].
     * Returns true if logging actually started.
     */
    private fun startFileLoggingLocked(requireRemovable: Boolean): Boolean {
        if (_fileLoggingActive.value) return true
        val writer = FileLogWriter(getApplication())
        val path = writer.start(requireRemovable) ?: return false
        fileLogWriter = writer
        DiagnosticLog.fileLogWriter = writer
        _fileLoggingActive.value = true
        _fileLoggingPath.value = path
        // Write existing ring buffer entries so we have context
        writer.writeExistingLogs(DiagnosticLog.localLogs.value)

        // Optionally start logcat capture if enabled in settings
        viewModelScope.launch {
            val captureEnabled = preferences.logcatCaptureEnabled.first()
            if (captureEnabled) {
                val logDir = java.io.File(path).parentFile
                if (logDir != null) {
                    val capture = LogcatCapture()
                    capture.start(logDir)
                    logcatCapture = capture
                }
            }
        }
        return true
    }

    /** Internal stop helper — must be called under [fileLogToggleLock]. */
    private fun stopFileLoggingLocked() {
        logcatCapture?.stop()
        logcatCapture = null
        fileLogWriter?.stop()
        DiagnosticLog.fileLogWriter = null
        fileLogWriter = null
        _fileLoggingActive.value = false
        _fileLoggingPath.value = null
    }

    /** Receiver for USB storage mount/unmount so auto-USB logging can react
     *  to a stick being plugged in or yanked mid-session. */
    private var usbStorageReceiver: android.content.BroadcastReceiver? = null

    private fun registerUsbStorageReceiver() {
        if (usbStorageReceiver != null) return
        val ctx = getApplication<Application>()
        val r = object : android.content.BroadcastReceiver() {
            override fun onReceive(c: android.content.Context?, intent: android.content.Intent?) {
                val action = intent?.action ?: return
                viewModelScope.launch {
                    val enabled = preferences.fileLoggingAutoStartUsb.first()
                    evaluateAutoUsbLogging(enabled, action)
                }
            }
        }
        val filter = android.content.IntentFilter().apply {
            addAction(android.content.Intent.ACTION_MEDIA_MOUNTED)
            addAction(android.content.Intent.ACTION_MEDIA_UNMOUNTED)
            addAction(android.content.Intent.ACTION_MEDIA_EJECT)
            addAction(android.content.Intent.ACTION_MEDIA_REMOVED)
            addAction(android.content.Intent.ACTION_MEDIA_BAD_REMOVAL)
            addDataScheme("file")
        }
        try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                ctx.registerReceiver(r, filter, android.content.Context.RECEIVER_NOT_EXPORTED)
            } else {
                @Suppress("UnspecifiedRegisterReceiverFlag")
                ctx.registerReceiver(r, filter)
            }
            usbStorageReceiver = r
        } catch (e: Exception) {
            OalLog.w(TAG, "USB storage receiver registration failed: ${e.message}")
        }
    }

    private fun unregisterUsbStorageReceiver() {
        val ctx = getApplication<Application>()
        usbStorageReceiver?.let {
            try { ctx.unregisterReceiver(it) } catch (_: Exception) {}
        }
        usbStorageReceiver = null
    }

    /**
     * Apply the auto-USB-logging pref against current storage state.
     *
     * - Pref on + USB present + not currently logging → start (USB-only).
     * - Pref on + USB present + manually-stopped session → don't restart
     *   (user explicitly stopped via overlay button this app-run).
     * - Pref off + auto-owned session active → stop.
     * - USB removed while auto-owned session active → stop.
     */
    private fun evaluateAutoUsbLogging(prefEnabled: Boolean, reason: String) {
        synchronized(fileLogToggleLock) {
            if (!prefEnabled) {
                if (autoUsbLoggingActive && _fileLoggingActive.value) {
                    OalLog.i(TAG, "USB auto-logging: stopping (pref off, reason=$reason)")
                    stopFileLoggingLocked()
                }
                autoUsbLoggingActive = false
                return
            }
            val probe = FileLogWriter(getApplication())
            val hasUsb = probe.hasRemovableStorage()
            if (!hasUsb) {
                if (autoUsbLoggingActive && _fileLoggingActive.value) {
                    OalLog.i(TAG, "USB auto-logging: stopping (USB removed, reason=$reason)")
                    stopFileLoggingLocked()
                }
                // Keep autoUsbLoggingActive flag so we restart on next mount.
                autoUsbLoggingActive = true
                return
            }
            if (_fileLoggingActive.value) {
                autoUsbLoggingActive = true
                return
            }
            val started = startFileLoggingLocked(requireRemovable = true)
            if (started) {
                autoUsbLoggingActive = true
                OalLog.i(TAG, "USB auto-logging: started (reason=$reason)")
            }
        }
    }

    /**
     * Forward a touch event from the projection surface to the bridge.
     *
     * In Crop margin-zoom rendering, the SurfaceView is INFLATED beyond the
     * panel and anchored top-left, so codec [0..innerW]x[0..innerH] maps
     * directly to the visible panel rect. Touch coords from the SurfaceView's
     * MotionEvent come in inflated-view space, but only the visible portion
     * (the panel rect) is reachable.
     *
     * Map by: codec_xy = event_xy / scale, where scale was the inflate factor
     * the renderer chose. Equivalently: codec_xy = event_xy * innerW /
     * panelW. We pass [innerW]/[innerH] explicitly so the touch math doesn't
     * have to re-derive margins from prefs.
     */
    fun onTouchEvent(
        event: MotionEvent,
        surfaceWidth: Int,
        surfaceHeight: Int,
        innerW: Int = 0,
        innerH: Int = 0,
        panelW: Int = 0,
        panelH: Int = 0,
    ) {
        val stats = _videoStats.value
        val codecW = if (stats.width > 0) stats.width else sessionManager.touchWidth.value
        val codecH = if (stats.height > 0) stats.height else sessionManager.touchHeight.value
        if (codecW <= 0 || codecH <= 0) return
        // When inner+panel are provided, pretend the view is the panel rect
        // and the codec is the inner rect — this maps edge-of-panel to
        // edge-of-AA-UI (codec col innerW, row innerH) regardless of how the
        // SurfaceView is sized/clipped.
        val effSurfW: Int
        val effSurfH: Int
        val effCodecW: Int
        val effCodecH: Int
        if (innerW > 0 && innerH > 0 && panelW > 0 && panelH > 0) {
            effSurfW = panelW
            effSurfH = panelH
            effCodecW = innerW
            effCodecH = innerH
        } else {
            effSurfW = surfaceWidth
            effSurfH = surfaceHeight
            effCodecW = codecW
            effCodecH = codecH
        }
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            val sx = event.x * effCodecW / effSurfW
            val sy = event.y * effCodecH / effSurfH
            Log.d("TouchDebug", "viewSurf=${surfaceWidth}x${surfaceHeight} " +
                "effSurf=${effSurfW}x${effSurfH} effCodec=${effCodecW}x${effCodecH} " +
                "raw=(${event.x.toInt()},${event.y.toInt()}) scaled=(${sx.toInt()},${sy.toInt()})")
        }
        touchForwarder.onTouch(event, effSurfW, effSurfH, effCodecW, effCodecH)
    }

    /** Handle a steering wheel key event. Returns true if consumed. */
    fun onKeyEvent(event: KeyEvent): Boolean {
        return steeringWheelController.onKeyEvent(event)
    }

    /** Called when the SurfaceView surface is created or changed. */
    fun onSurfaceAvailable(surface: Surface, width: Int, height: Int) {
        pendingSurface = surface
        pendingSurfaceWidth = width
        pendingSurfaceHeight = height

        // Debounce surface changes — AAOS animates surface size on launch (788→864 in ~30 steps).
        // Without debounce, each step resets the codec, losing the codec config frame.
        surfaceDebounceJob?.cancel()
        surfaceDebounceJob = viewModelScope.launch {
            kotlinx.coroutines.delay(150)
            Log.d(TAG, "Surface stabilized at ${width}x${height}")
            com.openautolink.app.diagnostics.DiagnosticLog.i("video",
                "Surface stabilized: ${width}x${height}")
            sessionManager.videoDecoder?.attach(surface, width, height)
            // Surface may have attached after the bridge's SPS/PPS+IDR replay arrived,
            // meaning the IDR was dropped (codec wasn't configured yet). Request a
            // fresh keyframe so the bridge sends a new IDR now that the codec is ready.
            sessionManager.requestKeyframe()
        }
    }

    /** Called when the SurfaceView surface is destroyed. */
    fun onSurfaceDestroyed() {
        pendingSurface = null
        pendingSurfaceWidth = 0
        pendingSurfaceHeight = 0
        sessionManager.videoDecoder?.detach()
    }

    /** Attach pending surface to a newly created decoder. Called by session observer. */
    internal fun attachPendingSurface() {
        val s = pendingSurface ?: return
        sessionManager.videoDecoder?.attach(s, pendingSurfaceWidth, pendingSurfaceHeight)
    }

    private fun registerTransportNetworkCallback() {
        try {
            connectivityManager.registerNetworkCallback(
                NetworkRequest.Builder().build(),
                transportNetworkCallback,
            )
        } catch (e: Exception) {
            Log.w(TAG, "Failed to register transport network callback: ${e.message}")
        }
    }

    private fun handleTransportNetworkUpdate(network: Network, reason: String) {
        if (!isTransportNetwork(network)) return
        synchronized(trackedTransportNetworks) {
            trackedTransportNetworks.add(network.networkHandle)
        }
        requestTransportReconnect(reason)
    }

    private fun isTransportNetwork(network: Network): Boolean {
        val linkProps = connectivityManager.getLinkProperties(network)
        val interfaceName = linkProps?.interfaceName
        if (selectedNetworkInterfaceName.isNotBlank() && interfaceName == selectedNetworkInterfaceName) {
            return true
        }

        val caps = connectivityManager.getNetworkCapabilities(network) ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) ||
            caps.hasTransport(NetworkCapabilities.TRANSPORT_USB)
    }

    private fun requestTransportReconnect(reason: String) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastTransportNetworkEventAt < 750L) return
        lastTransportNetworkEventAt = now
        Log.i(TAG, "Transport network event: $reason")
    }

    override fun onCleared() {
        try {
            connectivityManager.unregisterNetworkCallback(transportNetworkCallback)
        } catch (_: Exception) {}
        wifiAvailableCallback?.let {
            try { connectivityManager.unregisterNetworkCallback(it) } catch (_: Exception) {}
        }
        wifiAvailableCallback = null
        // Stop file logging if active
        logcatCapture?.stop()
        fileLogWriter?.stop()
        DiagnosticLog.fileLogWriter = null
        unregisterUsbStorageReceiver()
        sessionManager.stop()
        super.onCleared()
    }
}
