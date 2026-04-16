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
import com.openautolink.app.transport.AasdkJni
import com.openautolink.app.transport.AudioPurpose
import com.openautolink.app.transport.ConnectionState
import com.openautolink.app.transport.ControlMessage
import com.openautolink.app.transport.DirectAaTransport
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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Session orchestrator -- connects component islands, manages lifecycle.
 * Manages transport, video decoder, audio player, GNSS, vehicle data, and navigation.
 *
 * In direct AA mode, aasdk runs in-process via JNI. The transport is a
 * DirectAaTransport that handles relay connection + aasdk lifecycle.
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

        fun getInstance(scope: CoroutineScope, context: Context, audioManager: AudioManager): SessionManager {
            return instance ?: synchronized(this) {
                instance ?: SessionManager(scope, context, audioManager).also { instance = it }
            }
        }

        fun instanceOrNull(): SessionManager? = instance
    }

    private val scope = CoroutineScope(SupervisorJob() + kotlinx.coroutines.Dispatchers.Main)

    // Direct AA transport -- relay connection + aasdk JNI lifecycle
    private var transport: DirectAaTransport? = null

    val isReconnecting: Boolean get() = transport?.isReconnecting ?: false

    private val videoDispatcher = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
        Thread(r, "VideoDecodeInput").apply { isDaemon = true }
    }.asCoroutineDispatcher()

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

    val controlMessages: Flow<ControlMessage> get() = transport?.controlMessages
        ?: kotlinx.coroutines.flow.emptyFlow()

    private var _videoDecoder: VideoDecoder? = null
    val videoDecoder: VideoDecoder? get() = _videoDecoder

    val videoStats: StateFlow<VideoStats>?
        get() = _videoDecoder?.stats

    val decoderState: StateFlow<DecoderState>?
        get() = _videoDecoder?.decoderState

    private var _audioPlayer: AudioPlayer? = null
    val audioPlayer: AudioPlayer? get() = _audioPlayer

    val audioStats: StateFlow<AudioStats>?
        get() = _audioPlayer?.stats

    private var _micCaptureManager: MicCaptureManager? = null
    private var micSource: String = "car"

    val callState: StateFlow<CallState>?
        get() = _audioPlayer?.callState

    private var _gnssForwarder: GnssForwarder? = null
    private var _vehicleDataForwarder: VehicleDataForwarder? = null

    val vehicleData: StateFlow<ControlMessage.VehicleData>?
        get() = _vehicleDataForwarder?.latestVehicleData

    private var _imuForwarder: ImuForwarder? = null

    private val _navigationDisplay: NavigationDisplay = NavigationDisplayImpl()
    val navigationDisplay: NavigationDisplay get() = _navigationDisplay

    private var _remoteDiagnostics: RemoteDiagnosticsImpl? = null
    val remoteDiagnostics: RemoteDiagnostics? get() = _remoteDiagnostics
    private var _telemetryCollector: TelemetryCollector? = null

    val currentManeuver: StateFlow<ManeuverState?>
        get() = _navigationDisplay.currentManeuver

    private val _phoneBatteryLevel = MutableStateFlow<Int?>(null)
    val phoneBatteryLevel: StateFlow<Int?> = _phoneBatteryLevel.asStateFlow()
    private val _phoneBatteryCritical = MutableStateFlow(false)
    val phoneBatteryCritical: StateFlow<Boolean> = _phoneBatteryCritical.asStateFlow()

    private val _voiceSessionActive = MutableStateFlow(false)
    val voiceSessionActive: StateFlow<Boolean> = _voiceSessionActive.asStateFlow()

    private val _phoneSignalStrength = MutableStateFlow<Int?>(null)
    val phoneSignalStrength: StateFlow<Int?> = _phoneSignalStrength.asStateFlow()

    private var _pairedPhonesCallback: ((List<ControlMessage.PairedPhone>) -> Unit)? = null
    fun setPairedPhonesCallback(callback: ((List<ControlMessage.PairedPhone>) -> Unit)?) {
        _pairedPhonesCallback = callback
    }

    private val _pairedPhones = MutableStateFlow<List<ControlMessage.PairedPhone>>(emptyList())
    val pairedPhones: StateFlow<List<ControlMessage.PairedPhone>> = _pairedPhones.asStateFlow()

    private var _mediaSessionManager: OalMediaSessionManager? = null
    private var _clusterManager: com.openautolink.app.cluster.ClusterManager? = null

    private var observeJob: Job? = null
    private var videoCollectJob: Job? = null
    private var audioCollectJob: Job? = null
    private var decoderWatchJob: Job? = null
    private var keyframeWatchJob: Job? = null
    private var callStateJob: Job? = null
    private var targetHost: String? = null

    private var lastActiveTimestamp = SystemClock.elapsedRealtime()

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

        // Create DirectAaTransport
        transport?.disconnect()
        val t = DirectAaTransport(scope)
        transport = t

        // Configure session parameters from DataStore prefs
        val ctx = context
        if (ctx != null) {
            val prefs = AppPreferences.getInstance(ctx)
            val resolution = kotlinx.coroutines.runBlocking { prefs.aaResolution.first() }
            val dpi = kotlinx.coroutines.runBlocking { prefs.aaDpi.first() }
            val fps = kotlinx.coroutines.runBlocking { prefs.videoFps.first() }
            val (w, h) = resolveResolution(resolution)
            t.sessionWidth = w
            t.sessionHeight = h
            t.sessionFps = fps
            t.sessionDpi = dpi
            t.sessionMarginW = kotlinx.coroutines.runBlocking { prefs.aaWidthMargin.first() }
            t.sessionMarginH = kotlinx.coroutines.runBlocking { prefs.aaHeightMargin.first() }
            t.sessionPixelAspect = kotlinx.coroutines.runBlocking { prefs.aaPixelAspect.first() }
            t.sessionDriverPos = if (kotlinx.coroutines.runBlocking { prefs.driveSide.first() } == "right") 1 else 0
            t.sessionSafeTop = kotlinx.coroutines.runBlocking { prefs.safeAreaTop.first() }
            t.sessionSafeBottom = kotlinx.coroutines.runBlocking { prefs.safeAreaBottom.first() }
            t.sessionSafeLeft = kotlinx.coroutines.runBlocking { prefs.safeAreaLeft.first() }
            t.sessionSafeRight = kotlinx.coroutines.runBlocking { prefs.safeAreaRight.first() }
            t.sessionContentTop = kotlinx.coroutines.runBlocking { prefs.contentInsetTop.first() }
            t.sessionContentBottom = kotlinx.coroutines.runBlocking { prefs.contentInsetBottom.first() }
            t.sessionContentLeft = kotlinx.coroutines.runBlocking { prefs.contentInsetLeft.first() }
            t.sessionContentRight = kotlinx.coroutines.runBlocking { prefs.contentInsetRight.first() }
            t.sessionHeadUnitName = kotlinx.coroutines.runBlocking { prefs.headUnitName.first() }
        }

        // Create mic capture manager -- sends PCM via aasdk JNI
        _micCaptureManager?.release()
        _micCaptureManager = MicCaptureManager { frame ->
            t.sendMicAudio(frame.data)
        }

        // Create GNSS forwarder -- sends via aasdk JNI sendSensorData
        _gnssForwarder?.stop()
        _gnssForwarder = context?.let { ctx ->
            GnssForwarderImpl(ctx) { gnssMessage ->
                t.sendControlMessage(gnssMessage)
            }
        }

        // Create vehicle data forwarder -- sends via aasdk JNI
        _vehicleDataForwarder?.stop()
        _vehicleDataForwarder = context?.let { ctx ->
            VehicleDataForwarderImpl(
                ctx,
                sendMessage = { vehicleData ->
                    t.sendControlMessage(vehicleData)
                },
                onIgnitionOn = { ignitionState -> onIgnitionOn(ignitionState) }
            )
        }

        // Create IMU forwarder -- sends via aasdk JNI
        _imuForwarder?.stop()
        _imuForwarder = context?.let { ctx ->
            ImuForwarder(ctx) { imuData ->
                t.sendControlMessage(imuData)
            }
        }

        // Create media session for AAOS media source integration
        _mediaSessionManager?.release()
        _mediaSessionManager = context?.let { OalMediaSessionManager(it) }
        _mediaSessionManager?.initialize()
        _mediaSessionManager?.getSessionToken()?.let { token ->
            OalMediaBrowserService.updateSessionToken(token)
        }

        // Enable and launch cluster service binding
        _clusterManager?.release()
        _clusterManager = context?.let { com.openautolink.app.cluster.ClusterManager(it) }
        _clusterManager?.setClusterEnabled(true)
        _clusterManager?.launchClusterBinding()

        // Create remote diagnostics -- sends via relay control channel
        _telemetryCollector?.stop()
        _remoteDiagnostics = RemoteDiagnosticsImpl { message ->
            t.sendControlMessage(message)
        }
        _remoteDiagnostics?.setEnabled(diagnosticsEnabled)
        _remoteDiagnostics?.setMinLevel(DiagnosticLevel.fromWire(diagnosticsMinLevel))
        com.openautolink.app.diagnostics.DiagnosticLog.instance = _remoteDiagnostics
        _telemetryCollector = TelemetryCollector(scope, _remoteDiagnostics!!, _sessionState)
        _telemetryCollector?.videoDecoder = _videoDecoder
        _telemetryCollector?.audioPlayer = _audioPlayer
        _telemetryCollector?.start()

        observeJob = scope.launch {
            // Observe transport connection state changes
            launch {
                t.connectionState.collect { connState ->
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
                }
            }

            // Observe control messages for session-level events
            launch {
                t.controlMessages.collect { message ->
                    lastActiveTimestamp = SystemClock.elapsedRealtime()
                    handleControlMessage(message)
                }
            }

            // Collect video frames from aasdk JNI -> decoder
            videoCollectJob = launch(videoDispatcher) {
                t.videoFrames.collect { frame ->
                    _videoDecoder?.onFrame(frame)
                }
            }

            // Collect audio frames from aasdk JNI -> player
            audioCollectJob = launch(audioDispatcher) {
                t.audioFrames.collect { frame ->
                    _audioPlayer?.onAudioFrame(frame)
                }
            }

            // Watch for decoder errors
            decoderWatchJob = launch { watchDecoderState() }
            keyframeWatchJob = launch { watchKeyframeNeeds() }
            callStateJob = launch { watchCallState() }

            // Start transport connection
            syncLocalPreferences()
            ensureClusterAlive()
            t.connect(host, network = network, networkResolver = networkResolver)
        }
    }

    fun stop() {
        observeJob?.cancel()
        observeJob = null
        videoCollectJob?.cancel()
        videoCollectJob = null
        audioCollectJob?.cancel()
        audioCollectJob = null
        decoderWatchJob?.cancel()
        decoderWatchJob = null
        keyframeWatchJob?.cancel()
        keyframeWatchJob = null
        callStateJob?.cancel()
        callStateJob = null
        transport?.disconnect()
        transport = null
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

    fun onSystemWake() {
        val now = SystemClock.elapsedRealtime()
        val elapsed = now - lastActiveTimestamp
        lastActiveTimestamp = now

        if (elapsed < 10_000) return

        val state = _sessionState.value
        if (state == SessionState.IDLE && !isReconnecting) return

        Log.i(TAG, "System wake detected (${elapsed / 1000}s gap, state=$state) -- forcing reconnect")
        com.openautolink.app.diagnostics.DiagnosticLog.i("transport", "System wake detected (${elapsed / 1000}s gap) -- forcing reconnect")
        forceReconnect()
    }

    fun onTransportNetworkChanged(reason: String) {
        val state = _sessionState.value
        if (state == SessionState.IDLE && !isReconnecting) return
        if (state == SessionState.STREAMING) return

        lastActiveTimestamp = SystemClock.elapsedRealtime()
        Log.i(TAG, "Transport network changed ($reason) -- forcing reconnect")
        com.openautolink.app.diagnostics.DiagnosticLog.i("transport", "Transport network changed ($reason) -- forcing reconnect")
        forceReconnect()
    }

    fun forceReconnect() {
        transport?.forceReconnect()
    }

    private fun onIgnitionOn(ignitionState: Int) {
        val state = _sessionState.value
        if (state == SessionState.IDLE) return
        if (state == SessionState.STREAMING) return

        Log.i(TAG, "Ignition ON detected (state=$ignitionState) -- forcing reconnect")
        com.openautolink.app.diagnostics.DiagnosticLog.i("transport", "Ignition ON detected -- forcing reconnect")
        lastActiveTimestamp = SystemClock.elapsedRealtime()
        forceReconnect()
    }

    suspend fun requestKeyframe() {
        // aasdk handles keyframes internally -- no explicit request needed in direct mode
    }

    private suspend fun syncLocalPreferences() {
        val ctx = context ?: return
        try {
            val prefs = AppPreferences.getInstance(ctx)
            ClusterNavigationState.distanceUnits = prefs.distanceUnits.first()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to sync local preferences: ${e.message}")
        }
    }

    suspend fun sendControlMessage(message: ControlMessage) {
        transport?.sendControlMessage(message)
    }

    fun ensureClusterAlive() {
        _clusterManager?.ensureAlive()
    }

    fun setDiagnosticsEnabled(enabled: Boolean) {
        _remoteDiagnostics?.setEnabled(enabled)
    }

    fun setDiagnosticsMinLevel(level: String) {
        _remoteDiagnostics?.setMinLevel(DiagnosticLevel.fromWire(level))
    }

    private suspend fun watchDecoderState() {
        while (_videoDecoder == null) { delay(500) }
        _videoDecoder?.decoderState?.collect { state ->
            if (state == DecoderState.ERROR) {
                Log.w(TAG, "Decoder error detected -- initiating recovery")
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
            Log.i(TAG, "Decoder recovery: resumed codec")
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
                    if (attempt > 1) {
                        _remoteDiagnostics?.log(DiagnosticLevel.WARN, "video",
                            "Keyframe re-request #$attempt")
                    }
                    delay(2000)
                }
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
                _audioPlayer?.stopPurpose(message.purpose)
            }
            is ControlMessage.MicStart -> {
                if (micSource == "car") {
                    _micCaptureManager?.start(message.sampleRate)
                }
            }
            is ControlMessage.MicStop -> {
                _micCaptureManager?.stop()
            }
            is ControlMessage.Error -> {
                Log.e(TAG, "Error ${message.code}: ${message.message}")
                _statusMessage.value = "Error: ${message.message}"
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
            is ControlMessage.PairedPhones -> {
                _pairedPhones.value = message.phones
                _pairedPhonesCallback?.invoke(message.phones)
            }
            else -> {}
        }
    }

    /** Map resolution preference string to width/height for aasdk. */
    private fun resolveResolution(resolution: String): Pair<Int, Int> = when (resolution) {
        "480p" -> 800 to 480
        "720p" -> 1280 to 720
        "1080p" -> 1920 to 1080
        "1440p" -> 2560 to 1440
        "4k" -> 3840 to 2160
        else -> 1920 to 1080
    }
}

data class BridgeInfo(
    val name: String,
    val version: Int,
    val capabilities: List<String>,
)
