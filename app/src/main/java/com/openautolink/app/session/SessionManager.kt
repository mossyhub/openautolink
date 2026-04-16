package com.openautolink.app.session

import android.content.Context
import android.media.AudioManager
import android.net.Network
import android.os.SystemClock
import android.util.Log
import com.openautolink.app.audio.AudioPlayer
import com.openautolink.app.audio.AudioPlayerImpl
import com.openautolink.app.audio.AudioStats
import com.openautolink.app.audio.CallState
import com.openautolink.app.audio.MicCaptureManager
import com.openautolink.app.cluster.ClusterNavigationState
import com.openautolink.app.data.AppPreferences
import com.openautolink.app.diagnostics.DiagnosticLevel
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
import com.openautolink.app.transport.NetworkResolver
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
import kotlinx.coroutines.launch

/**
 * Session orchestrator â€” connects component islands, manages lifecycle.
 * Manages transport, video decoder, audio player, GNSS, vehicle data, and navigation.
 *
 * In direct AA mode, aasdk runs in-process via JNI. The transport is a
 * DirectAaTransport that handles relay connection + aasdk lifecycle.
 * This class will be wired up to DirectAaTransport in Phase 4.
 */
class SessionManager(
    externalScope: CoroutineScope,
    private val context: Context? = null,
    private val audioManager: AudioManager? = null
) {

    companion object {
        private const val TAG = "SessionManager"

        @Volatile
        private var instance: SessionManager? = null

        /**
         * Get or create the shared SessionManager instance.
         * Both ProjectionViewModel and DiagnosticsViewModel must use the same instance
         * so diagnostics can observe live vehicle data, video stats, etc.
         *
         * Note: The SessionManager creates its own CoroutineScope (SupervisorJob + Main)
         * so it survives ViewModel lifecycle changes. The externalScope parameter is ignored
         * for the singleton â€” it uses its own scope to avoid cancellation when ViewModels clear.
         */
        fun getInstance(scope: CoroutineScope, context: Context, audioManager: AudioManager): SessionManager {
            return instance ?: synchronized(this) {
                instance ?: SessionManager(scope, context, audioManager).also { instance = it }
            }
        }

        /** Get existing instance without creating â€” for lifecycle callbacks (e.g. onResume). */
        fun instanceOrNull(): SessionManager? = instance
    }

    // Use our own scope that survives ViewModel lifecycle â€” SupervisorJob so child
    // failures don't cancel the whole session, Dispatchers.Main for UI state updates.
    private val scope = CoroutineScope(SupervisorJob() + kotlinx.coroutines.Dispatchers.Main)

    // TODO: Phase 3/4 â€” replace with DirectAaTransport
    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    private val _controlMessages = MutableSharedFlow<ControlMessage>(extraBufferCapacity = 64)

    /** Whether a reconnect loop is active (used by onSystemWake to avoid skipping). */
    var isReconnecting: Boolean = false
        private set

    // Dedicated single-threaded dispatcher for video decode â€” keeps frame ordering
    // and prevents blocking the main thread with MediaCodec input queueing.
    private val videoDispatcher = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
        Thread(r, "VideoDecodeInput").apply { isDaemon = true }
    }.asCoroutineDispatcher()

    // Dedicated dispatcher for audio â€” ring buffer writes must not be blocked
    // by main thread UI work (Compose recomposition, surface callbacks, etc.)
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

    private val _bridgeInfo = MutableStateFlow<BridgeInfo?>(null)
    val bridgeInfo: StateFlow<BridgeInfo?> = _bridgeInfo.asStateFlow()

    val controlMessages: SharedFlow<ControlMessage> get() = _controlMessages.asSharedFlow()

    // Video decoder â€” created per session, accessible for UI binding
    private var _videoDecoder: VideoDecoder? = null
    val videoDecoder: VideoDecoder? get() = _videoDecoder

    val videoStats: StateFlow<VideoStats>?
        get() = _videoDecoder?.stats

    val decoderState: StateFlow<DecoderState>?
        get() = _videoDecoder?.decoderState

    // Audio player â€” created per session
    private var _audioPlayer: AudioPlayer? = null
    val audioPlayer: AudioPlayer? get() = _audioPlayer

    val audioStats: StateFlow<AudioStats>?
        get() = _audioPlayer?.stats

    // Mic capture â€” created per session, only active when mic source is "car"
    private var _micCaptureManager: MicCaptureManager? = null
    private var micSource: String = "car"

    // Call state from audio player â€” exposed for UI/diagnostics
    val callState: StateFlow<CallState>?
        get() = _audioPlayer?.callState

    // GNSS forwarder â€” sends car GPS to phone via aasdk JNI
    private var _gnssForwarder: GnssForwarder? = null

    // Vehicle data forwarder â€” sends VHAL properties to phone via aasdk JNI
    private var _vehicleDataForwarder: VehicleDataForwarder? = null

    /** Latest vehicle sensor data snapshot â€” for diagnostics UI. */
    val vehicleData: StateFlow<ControlMessage.VehicleData>?
        get() = _vehicleDataForwarder?.latestVehicleData

    // IMU forwarder â€” sends accelerometer/gyro/compass to phone via aasdk JNI
    private var _imuForwarder: ImuForwarder? = null

    // Navigation display â€” processes nav_state from aasdk JNI
    private val _navigationDisplay: NavigationDisplay = NavigationDisplayImpl()
    val navigationDisplay: NavigationDisplay get() = _navigationDisplay

    // Remote diagnostics â€” sends structured logs + telemetry via relay control channel
    private var _remoteDiagnostics: RemoteDiagnosticsImpl? = null
    val remoteDiagnostics: RemoteDiagnostics? get() = _remoteDiagnostics
    private var _telemetryCollector: TelemetryCollector? = null

    val currentManeuver: StateFlow<ManeuverState?>
        get() = _navigationDisplay.currentManeuver

    // Phone battery state from aasdk
    private val _phoneBatteryLevel = MutableStateFlow<Int?>(null)
    val phoneBatteryLevel: StateFlow<Int?> = _phoneBatteryLevel.asStateFlow()
    private val _phoneBatteryCritical = MutableStateFlow(false)
    val phoneBatteryCritical: StateFlow<Boolean> = _phoneBatteryCritical.asStateFlow()

    // Voice session active state
    private val _voiceSessionActive = MutableStateFlow(false)
    val voiceSessionActive: StateFlow<Boolean> = _voiceSessionActive.asStateFlow()

    // Phone signal strength (0-4, null if unknown)
    private val _phoneSignalStrength = MutableStateFlow<Int?>(null)
    val phoneSignalStrength: StateFlow<Int?> = _phoneSignalStrength.asStateFlow()

    // Paired phones callback â€” set by ViewModels to receive paired_phones responses
    private var _pairedPhonesCallback: ((List<ControlMessage.PairedPhone>) -> Unit)? = null
    fun setPairedPhonesCallback(callback: ((List<ControlMessage.PairedPhone>) -> Unit)?) {
        _pairedPhonesCallback = callback
    }

    // Paired phones list â€” populated when bridge responds to list_paired_phones
    private val _pairedPhones = MutableStateFlow<List<ControlMessage.PairedPhone>>(emptyList())
    val pairedPhones: StateFlow<List<ControlMessage.PairedPhone>> = _pairedPhones.asStateFlow()

    // Media session â€” publishes now-playing to AAOS system UI + cluster
    private var _mediaSessionManager: OalMediaSessionManager? = null

    // Cluster manager â€” lifecycle for cluster CarAppService binding
    private var _clusterManager: com.openautolink.app.cluster.ClusterManager? = null

    private var observeJob: Job? = null
    private var decoderWatchJob: Job? = null
    private var keyframeWatchJob: Job? = null
    private var callStateJob: Job? = null
    private var targetHost: String? = null

    // Track last known active time â€” used to detect system sleep/wake gaps.
    // When the car sleeps, the process freezes; on wake, elapsedRealtime jumps.
    private var lastActiveTimestamp = SystemClock.elapsedRealtime()

    // Previous ignition state â€” used to detect ignition ON transitions
    private var previousIgnitionState: Int? = null

    fun start(host: String, port: Int = 5288, codecPreference: String = "h264", micSourcePreference: String = "car",
               diagnosticsEnabled: Boolean = false, diagnosticsMinLevel: String = "INFO",
               network: Network? = null, networkResolver: NetworkResolver? = null,
               scalingMode: String = "letterbox") {
        targetHost = host
        micSource = micSourcePreference
        observeJob?.cancel()

        // Create video decoder for this session
        _videoDecoder?.release()
        _videoDecoder = MediaCodecDecoder(codecPreference, scalingMode)

        // Create audio player for this session
        _audioPlayer?.release()
        _audioPlayer = audioManager?.let { AudioPlayerImpl(it) }
        _audioPlayer?.initialize()

        // Create mic capture manager for car mic mode
        // TODO: Phase 4 â€” MicCaptureManager needs aasdk JNI interface instead of BridgeConnection
        _micCaptureManager?.release()
        _micCaptureManager = null

        // Create GNSS forwarder
        // TODO: Phase 4 â€” send via aasdk JNI sendSensorData instead of control message
        _gnssForwarder?.stop()
        _gnssForwarder = context?.let { ctx ->
            GnssForwarderImpl(ctx) { gnssMessage ->
                scope.launch { sendControlMessage(gnssMessage) }
            }
        }

        // Create vehicle data forwarder
        // TODO: Phase 4 â€” send via aasdk JNI instead of control message
        _vehicleDataForwarder?.stop()
        _vehicleDataForwarder = context?.let { ctx ->
            VehicleDataForwarderImpl(
                ctx,
                sendMessage = { vehicleData ->
                    scope.launch { sendControlMessage(vehicleData) }
                },
                onIgnitionOn = { ignitionState -> onIgnitionOn(ignitionState) }
            )
        }

        // Create IMU forwarder
        // TODO: Phase 4 â€” send via aasdk JNI instead of control message
        _imuForwarder?.stop()
        _imuForwarder = context?.let { ctx ->
            ImuForwarder(ctx) { imuData ->
                scope.launch { sendControlMessage(imuData) }
            }
        }

        // Create media session for AAOS media source integration
        _mediaSessionManager?.release()
        _mediaSessionManager = context?.let { OalMediaSessionManager(it) }
        _mediaSessionManager?.initialize()
        // Push session token to MediaBrowserService so AAOS system UI + cluster discover it
        _mediaSessionManager?.getSessionToken()?.let { token ->
            OalMediaBrowserService.updateSessionToken(token)
        }

        // Enable and launch cluster service binding
        _clusterManager?.release()
        _clusterManager = context?.let { com.openautolink.app.cluster.ClusterManager(it) }
        _clusterManager?.setClusterEnabled(true)
        _clusterManager?.launchClusterBinding()

        // Create remote diagnostics
        // TODO: Phase 4 â€” diagnostics send via relay control channel, not aasdk
        _telemetryCollector?.stop()
        _remoteDiagnostics = RemoteDiagnosticsImpl { message ->
            scope.launch { sendControlMessage(message) }
        }
        _remoteDiagnostics?.setEnabled(diagnosticsEnabled)
        _remoteDiagnostics?.setMinLevel(DiagnosticLevel.fromWire(diagnosticsMinLevel))
        com.openautolink.app.diagnostics.DiagnosticLog.instance = _remoteDiagnostics
        _telemetryCollector = TelemetryCollector(scope, _remoteDiagnostics!!, _sessionState)
        _telemetryCollector?.videoDecoder = _videoDecoder
        _telemetryCollector?.audioPlayer = _audioPlayer
        _telemetryCollector?.start()

        observeJob = scope.launch {
            // Observe connection state changes
            launch {
                var previousState = SessionState.IDLE
                _connectionState.collect { connState ->
                    val newState = connState.toSessionState()
                    _sessionState.value = newState
                    _statusMessage.value = when (newState) {
                        SessionState.IDLE -> "Disconnected"
                        SessionState.CONNECTING -> "Connecting to bridge..."
                        SessionState.LISTENING -> "Waiting for phone..."
                        SessionState.PHONE_CONNECTED -> "Phone connected"
                        SessionState.STREAMING -> "Streaming"
                        SessionState.ERROR -> "Error"
                    }
                    Log.i(TAG, "Session state: $newState")
                    _remoteDiagnostics?.log(DiagnosticLevel.INFO, "transport", "Session state: $newState")
                    previousState = newState
                }
            }

            // Observe control messages for session-level events
            launch {
                _controlMessages.collect { message ->
                    lastActiveTimestamp = SystemClock.elapsedRealtime()
                    handleControlMessage(message)
                }
            }

            // Watch for decoder errors â€” auto-reset codec and request keyframe
            decoderWatchJob?.cancel()
            decoderWatchJob = launch {
                watchDecoderState()
            }

            // Watch for IDR starvation â€” periodically re-request keyframes
            keyframeWatchJob?.cancel()
            keyframeWatchJob = launch {
                watchKeyframeNeeds()
            }

            // Watch call state to route mic purpose (assistant vs call)
            callStateJob?.cancel()
            callStateJob = launch {
                watchCallState()
            }

            // TODO: Phase 3/4 â€” start DirectAaTransport connection here
            // For now, session starts but has no transport to connect through
            syncLocalPreferences()
            ensureClusterAlive()
        }
    }

    fun stop() {
        observeJob?.cancel()
        observeJob = null
        decoderWatchJob?.cancel()
        decoderWatchJob = null
        keyframeWatchJob?.cancel()
        keyframeWatchJob = null
        callStateJob?.cancel()
        callStateJob = null
        _videoDecoder?.release()
        _videoDecoder = null
        _audioPlayer?.release()
        _audioPlayer = null
        _micCaptureManager?.release()
        _micCaptureManager = null
        _gnssForwarder?.stop()
        _gnssForwarder = null
        _vehicleDataForwarder?.stop()
        _vehicleDataForwarder = null
        _imuForwarder?.stop()
        _imuForwarder = null
        _navigationDisplay.clear()
        ClusterNavigationState.clear()
        _mediaSessionManager?.release()
        _mediaSessionManager = null
        _clusterManager?.release()
        _clusterManager = null
        _telemetryCollector?.stop()
        _telemetryCollector = null
        com.openautolink.app.diagnostics.DiagnosticLog.instance = null
        _remoteDiagnostics = null
        _sessionState.value = SessionState.IDLE
        _statusMessage.value = "Disconnected"
        _bridgeInfo.value = null
        _phoneBatteryLevel.value = null
        _phoneBatteryCritical.value = false
        _voiceSessionActive.value = false
        _phoneSignalStrength.value = null
    }

    /**
     * Called from Activity.onResume() to detect system sleep/wake.
     * When the car sleeps, the AAOS process freezes and TCP sockets go dead.
     * On wake, this method detects the time gap and forces a reconnect
     * so the app doesn't stay stuck waiting for the next retry interval.
     *
     * Short gaps (< 10s) are ignored â€” those are normal navigation (Settings â†’ back).
     */
    fun onSystemWake() {
        val now = SystemClock.elapsedRealtime()
        val elapsed = now - lastActiveTimestamp
        lastActiveTimestamp = now

        if (elapsed < 10_000) return // Normal UI navigation, not a wake event

        val state = _sessionState.value
        if (state == SessionState.IDLE && !isReconnecting) return

        Log.i(TAG, "System wake detected (${elapsed / 1000}s gap, state=$state) â€” forcing reconnect")
        com.openautolink.app.diagnostics.DiagnosticLog.i("transport", "System wake detected (${elapsed / 1000}s gap) â€” forcing reconnect")
        forceReconnect()
    }

    /**
     * Called when the AAOS USB/Ethernet transport appears, disappears, or rebinds.
     * This is common around car sleep/wake when the USB NIC is power-cycled.
     */
    fun onTransportNetworkChanged(reason: String) {
        val state = _sessionState.value
        if (state == SessionState.IDLE && !isReconnecting) return
        if (state == SessionState.STREAMING) return

        lastActiveTimestamp = SystemClock.elapsedRealtime()
        Log.i(TAG, "Transport network changed ($reason) â€” forcing reconnect")
        com.openautolink.app.diagnostics.DiagnosticLog.i(
            "transport",
            "Transport network changed ($reason) â€” forcing reconnect"
        )
        forceReconnect()
    }

    /** Force an immediate reconnect attempt. */
    fun forceReconnect() {
        // TODO: Phase 3/4 â€” delegate to DirectAaTransport.forceReconnect()
        Log.i(TAG, "forceReconnect() â€” stub, no transport connected yet")
    }

    /**
     * Called when VHAL reports ignition state change to ON/START.
     * This is a secondary wake signal â€” the car is starting and the bridge
     * should be booting. Force-reconnect to get a fresh connection ASAP.
     */
    private fun onIgnitionOn(ignitionState: Int) {
        val state = _sessionState.value
        if (state == SessionState.IDLE) return
        if (state == SessionState.STREAMING) return

        Log.i(TAG, "Ignition ON detected (state=$ignitionState) â€” forcing reconnect")
        com.openautolink.app.diagnostics.DiagnosticLog.i("transport", "Ignition ON detected â€” forcing reconnect")
        lastActiveTimestamp = SystemClock.elapsedRealtime()
        forceReconnect()
    }

    /** Request a keyframe from the phone via aasdk. */
    suspend fun requestKeyframe() {
        // TODO: Phase 4 â€” AasdkJni.requestKeyframe()
        Log.d(TAG, "requestKeyframe() â€” stub")
    }

    /** Sync local-only preferences (e.g., cluster units). */
    private suspend fun syncLocalPreferences() {
        val ctx = context ?: return
        try {
            val prefs = AppPreferences.getInstance(ctx)
            ClusterNavigationState.distanceUnits = prefs.distanceUnits.first()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to sync local preferences: ${e.message}")
        }
    }

    /** Send a control message (used by touch forwarding, etc.). */
    suspend fun sendControlMessage(message: ControlMessage) {
        // TODO: Phase 4 â€” route through aasdk JNI or relay control channel
        Log.d(TAG, "sendControlMessage(${message::class.simpleName}) â€” stub")
    }

    /**
     * Check if the cluster session is alive; re-launch binding if it died.
     * Called after returning from Settings and on bridge reconnect.
     */
    fun ensureClusterAlive() {
        _clusterManager?.ensureAlive()
    }

    /** Update remote diagnostics enabled state at runtime. */
    fun setDiagnosticsEnabled(enabled: Boolean) {
        _remoteDiagnostics?.setEnabled(enabled)
    }

    /** Update remote diagnostics minimum log level at runtime. */
    fun setDiagnosticsMinLevel(level: String) {
        _remoteDiagnostics?.setMinLevel(DiagnosticLevel.fromWire(level))
    }

    /**
     * Watches decoder state for ERROR â€” automatically resets codec and requests a keyframe.
     * Waits 500ms before recovery to avoid tight loops on persistent errors.
     */
    private suspend fun watchDecoderState() {
        while (_videoDecoder == null) {
            delay(500)
        }
        _videoDecoder?.decoderState?.collect { state ->
            if (state == DecoderState.ERROR) {
                Log.w(TAG, "Decoder error detected â€” initiating recovery")
                _remoteDiagnostics?.log(DiagnosticLevel.ERROR, "video", "Decoder error detected â€” initiating recovery")
                _statusMessage.value = "Video error â€” recovering..."
                recoverDecoder()
            }
        }
    }

    private suspend fun recoverDecoder() {
        delay(500)
        _videoDecoder?.let { decoder ->
            decoder.resume()
            requestKeyframe()
            Log.i(TAG, "Decoder recovery: resumed codec, requested keyframe")
        }
    }

    /**
     * Watches the decoder's needsKeyframe flow and periodically re-requests keyframes
     * until the decoder receives an IDR.
     */
    private suspend fun watchKeyframeNeeds() {
        while (_videoDecoder == null) {
            delay(500)
        }
        val decoder = _videoDecoder ?: return
        decoder.needsKeyframe.collect { needed ->
            if (needed) {
                var attempt = 0
                while (decoder.needsKeyframe.value) {
                    attempt++
                    requestKeyframe()
                    if (attempt == 1) {
                        Log.i(TAG, "Keyframe re-request #$attempt (IDR starvation recovery)")
                    } else {
                        Log.w(TAG, "Keyframe re-request #$attempt (still waiting for IDR)")
                        _remoteDiagnostics?.log(DiagnosticLevel.WARN, "video",
                            "Keyframe re-request #$attempt (still waiting for IDR)")
                    }
                    delay(2000)
                }
            }
        }
    }

    /**
     * Watches the audio player's call state to route mic purpose correctly.
     */
    private suspend fun watchCallState() {
        val player = _audioPlayer ?: return
        player.callState.collect { state ->
            val purpose = when (state) {
                CallState.IN_CALL -> AudioPurpose.PHONE_CALL
                else -> AudioPurpose.ASSISTANT
            }
            _micCaptureManager?.setMicPurpose(purpose)
            Log.d(TAG, "Call state changed to $state â€” mic purpose set to $purpose")
            _remoteDiagnostics?.log(DiagnosticLevel.DEBUG, "audio", "Call state changed to $state â€” mic purpose set to $purpose")
        }
    }

    private fun handleControlMessage(message: ControlMessage) {
        when (message) {
            is ControlMessage.PhoneConnected -> {
                _remoteDiagnostics?.log(DiagnosticLevel.INFO, "session", "Phone connected: ${message.phoneName}")
                _gnssForwarder?.start()
                _vehicleDataForwarder?.start()
                _imuForwarder?.start()
            }
            is ControlMessage.PhoneDisconnected -> {
                _remoteDiagnostics?.log(DiagnosticLevel.INFO, "session", "Phone disconnected: ${message.reason}")
                _gnssForwarder?.stop()
                _vehicleDataForwarder?.stop()
                _imuForwarder?.stop()
                _navigationDisplay.clear()
                ClusterNavigationState.clear()
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
                _mediaSessionManager?.updateMetadata(
                    title = message.title,
                    artist = message.artist,
                    album = message.album,
                    durationMs = message.durationMs,
                    albumArtBase64 = message.albumArtBase64
                )
                if (message.playing != null) {
                    _mediaSessionManager?.updatePlaybackState(
                        playing = message.playing,
                        positionMs = message.positionMs ?: 0
                    )
                }
            }
            is ControlMessage.AudioStart -> {
                _remoteDiagnostics?.log(DiagnosticLevel.INFO, "audio", "Audio start: purpose=${message.purpose}, rate=${message.sampleRate}, ch=${message.channels}")
                _audioPlayer?.startPurpose(message.purpose, message.sampleRate, message.channels)
            }
            is ControlMessage.AudioStop -> {
                _remoteDiagnostics?.log(DiagnosticLevel.INFO, "audio", "Audio stop: purpose=${message.purpose}")
                _audioPlayer?.stopPurpose(message.purpose)
            }
            is ControlMessage.MicStart -> {
                if (micSource == "car") {
                    _micCaptureManager?.start(message.sampleRate)
                } else {
                    Log.i(TAG, "Mic source is phone â€” skipping car mic capture")
                }
            }
            is ControlMessage.MicStop -> {
                _micCaptureManager?.stop()
            }
            is ControlMessage.Error -> {
                Log.e(TAG, "Error ${message.code}: ${message.message}")
                _remoteDiagnostics?.log(DiagnosticLevel.ERROR, "transport", "Error ${message.code}: ${message.message}")
                _statusMessage.value = "Error: ${message.message}"
            }
            is ControlMessage.PhoneBattery -> {
                _phoneBatteryLevel.value = message.level
                _phoneBatteryCritical.value = message.critical
                Log.d(TAG, "Phone battery: ${message.level}% critical=${message.critical}")
            }
            is ControlMessage.VoiceSession -> {
                _voiceSessionActive.value = message.started
                Log.d(TAG, "Voice session: ${if (message.started) "start" else "end"}")
            }
            is ControlMessage.PhoneStatus -> {
                _phoneSignalStrength.value = message.signalStrength
                if (message.calls.isNotEmpty()) {
                    Log.d(TAG, "Phone status: signal=${message.signalStrength}, calls=${message.calls.size}")
                }
            }
            is ControlMessage.PairedPhones -> {
                Log.d(TAG, "Received paired phones: ${message.phones.size}")
                _pairedPhones.value = message.phones
                _pairedPhonesCallback?.invoke(message.phones)
            }
            else -> {}
        }
    }
}

data class BridgeInfo(
    val name: String,
    val version: Int,
    val capabilities: List<String>,
)
