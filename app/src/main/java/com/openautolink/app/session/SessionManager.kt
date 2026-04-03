package com.openautolink.app.session

import android.content.Context
import android.media.AudioManager
import android.util.Log
import com.openautolink.app.audio.AudioFrame
import com.openautolink.app.audio.AudioPlayer
import com.openautolink.app.audio.AudioPlayerImpl
import com.openautolink.app.audio.AudioStats
import com.openautolink.app.audio.CallState
import com.openautolink.app.audio.MicCaptureManager
import com.openautolink.app.cluster.ClusterNavigationState
import com.openautolink.app.diagnostics.DiagnosticLevel
import com.openautolink.app.diagnostics.RemoteDiagnostics
import com.openautolink.app.diagnostics.RemoteDiagnosticsImpl
import com.openautolink.app.diagnostics.TelemetryCollector
import com.openautolink.app.input.GnssForwarder
import com.openautolink.app.input.GnssForwarderImpl
import com.openautolink.app.input.ImuForwarder
import com.openautolink.app.input.VehicleDataForwarder
import com.openautolink.app.input.VehicleDataForwarderImpl
import com.openautolink.app.media.OalMediaSessionManager
import com.openautolink.app.navigation.ManeuverState
import com.openautolink.app.navigation.NavigationDisplay
import com.openautolink.app.navigation.NavigationDisplayImpl
import com.openautolink.app.transport.AudioPurpose
import com.openautolink.app.transport.BridgeConnection
import com.openautolink.app.transport.ConnectionManager
import com.openautolink.app.transport.ConnectionState
import com.openautolink.app.transport.ControlMessage
import com.openautolink.app.transport.ControlMessageSerializer
import com.openautolink.app.video.DecoderState
import com.openautolink.app.video.MediaCodecDecoder
import com.openautolink.app.video.VideoDecoder
import com.openautolink.app.video.VideoStats
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Session orchestrator — connects component islands, manages lifecycle.
 * Manages transport, video decoder, audio player, GNSS, vehicle data, and navigation.
 */
class SessionManager(
    private val scope: CoroutineScope,
    private val context: Context? = null,
    private val audioManager: AudioManager? = null
) {

    companion object {
        private const val TAG = "SessionManager"
    }

    private val connectionManager = ConnectionManager(scope)

    // Dedicated single-threaded dispatcher for video decode — keeps frame ordering
    // and prevents blocking the main thread with MediaCodec input queueing.
    private val videoDispatcher = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
        Thread(r, "VideoDecodeInput").apply { isDaemon = true }
    }.asCoroutineDispatcher()

    private val _sessionState = MutableStateFlow(SessionState.IDLE)
    val sessionState: StateFlow<SessionState> = _sessionState.asStateFlow()

    private val _statusMessage = MutableStateFlow("Ready")
    val statusMessage: StateFlow<String> = _statusMessage.asStateFlow()

    private val _bridgeInfo = MutableStateFlow<BridgeInfo?>(null)
    val bridgeInfo: StateFlow<BridgeInfo?> = _bridgeInfo.asStateFlow()

    val controlMessages get() = connectionManager.controlMessages

    // Video decoder — created per session, accessible for UI binding
    private var _videoDecoder: VideoDecoder? = null
    val videoDecoder: VideoDecoder? get() = _videoDecoder

    val videoStats: StateFlow<VideoStats>?
        get() = _videoDecoder?.stats

    val decoderState: StateFlow<DecoderState>?
        get() = _videoDecoder?.decoderState

    // Audio player — created per session
    private var _audioPlayer: AudioPlayer? = null
    val audioPlayer: AudioPlayer? get() = _audioPlayer

    val audioStats: StateFlow<AudioStats>?
        get() = _audioPlayer?.stats

    // Mic capture — created per session, only active when mic source is "car"
    private var _micCaptureManager: MicCaptureManager? = null
    private var micSource: String = "car"

    // Call state from audio player — exposed for UI/diagnostics
    val callState: StateFlow<CallState>?
        get() = _audioPlayer?.callState

    // GNSS forwarder — sends car GPS to bridge → phone
    private var _gnssForwarder: GnssForwarder? = null

    // Vehicle data forwarder — sends VHAL properties to bridge → phone
    private var _vehicleDataForwarder: VehicleDataForwarder? = null

    // IMU forwarder — sends accelerometer/gyro/compass to bridge → phone
    private var _imuForwarder: ImuForwarder? = null

    // Navigation display — processes nav_state from bridge
    private val _navigationDisplay: NavigationDisplay = NavigationDisplayImpl()
    val navigationDisplay: NavigationDisplay get() = _navigationDisplay

    // Remote diagnostics — sends structured logs + telemetry to bridge
    private var _remoteDiagnostics: RemoteDiagnosticsImpl? = null
    val remoteDiagnostics: RemoteDiagnostics? get() = _remoteDiagnostics
    private var _telemetryCollector: TelemetryCollector? = null

    val currentManeuver: StateFlow<ManeuverState?>
        get() = _navigationDisplay.currentManeuver

    // Phone battery state from bridge
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

    // Media session — publishes now-playing to AAOS system UI + cluster
    private var _mediaSessionManager: OalMediaSessionManager? = null

    // Cluster manager — lifecycle for cluster CarAppService binding
    private var _clusterManager: com.openautolink.app.cluster.ClusterManager? = null

    private var observeJob: Job? = null
    private var videoCollectJob: Job? = null
    private var audioCollectJob: Job? = null
    private var decoderWatchJob: Job? = null
    private var callStateJob: Job? = null
    private var targetHost: String? = null

    fun start(host: String, port: Int = 5288, codecPreference: String = "h264", micSourcePreference: String = "car",
               diagnosticsEnabled: Boolean = false, diagnosticsMinLevel: String = "INFO") {
        targetHost = host
        micSource = micSourcePreference
        observeJob?.cancel()

        // Create video decoder for this session
        _videoDecoder?.release()
        _videoDecoder = MediaCodecDecoder(codecPreference)

        // Create audio player for this session
        _audioPlayer?.release()
        _audioPlayer = audioManager?.let { AudioPlayerImpl(it) }
        _audioPlayer?.initialize()

        // Create mic capture manager for car mic mode
        _micCaptureManager?.release()
        _micCaptureManager = MicCaptureManager(connectionManager)

        // Create GNSS forwarder
        _gnssForwarder?.stop()
        _gnssForwarder = context?.let { ctx ->
            GnssForwarderImpl(ctx) { gnssMessage ->
                scope.launch { connectionManager.sendControlMessage(gnssMessage) }
            }
        }

        // Create vehicle data forwarder
        _vehicleDataForwarder?.stop()
        _vehicleDataForwarder = context?.let { ctx ->
            VehicleDataForwarderImpl(ctx) { vehicleData ->
                scope.launch { connectionManager.sendControlMessage(vehicleData) }
            }
        }

        // Create IMU forwarder
        _imuForwarder?.stop()
        _imuForwarder = context?.let { ctx ->
            ImuForwarder(ctx) { imuData ->
                scope.launch { connectionManager.sendControlMessage(imuData) }
            }
        }

        // Create media session for AAOS media source integration
        _mediaSessionManager?.release()
        _mediaSessionManager = context?.let { OalMediaSessionManager(it) }
        _mediaSessionManager?.initialize()

        // Enable and launch cluster service binding
        _clusterManager?.release()
        _clusterManager = context?.let { com.openautolink.app.cluster.ClusterManager(it) }
        _clusterManager?.setClusterEnabled(true)
        _clusterManager?.launchClusterBinding()

        // Create remote diagnostics
        _telemetryCollector?.stop()
        _remoteDiagnostics = RemoteDiagnosticsImpl { message ->
            scope.launch { connectionManager.sendControlMessage(message) }
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
                connectionManager.connectionState.collect { connState ->
                    val newState = connState.toSessionState()
                    _sessionState.value = newState
                    _statusMessage.value = when (newState) {
                        SessionState.IDLE -> "Disconnected"
                        SessionState.CONNECTING -> "Connecting to bridge..."
                        SessionState.BRIDGE_CONNECTED -> "Waiting for phone..."
                        SessionState.PHONE_CONNECTED -> "Phone connected"
                        SessionState.STREAMING -> "Streaming"
                        SessionState.ERROR -> "Error"
                    }
                    Log.i(TAG, "Session state: $newState")
                    _remoteDiagnostics?.log(DiagnosticLevel.INFO, "transport", "Session state: $newState")

                    // Auto-reconnect video/audio if they dropped but phone is still connected
                    if (newState == SessionState.PHONE_CONNECTED && previousState == SessionState.STREAMING) {
                        Log.w(TAG, "Video/audio channel lost — reconnecting")
                        _remoteDiagnostics?.log(DiagnosticLevel.WARN, "transport", "Video/audio channel lost — reconnecting")
                        _statusMessage.value = "Reconnecting video/audio..."
                        val info = _bridgeInfo.value
                        val host = targetHost
                        if (info != null && host != null) {
                            _videoDecoder?.resume()
                            startVideoChannel(host, info.videoPort)
                            startAudioChannel(host, info.audioPort)
                        }
                    }

                    previousState = newState
                }
            }

            // Observe control messages for session-level events
            launch {
                connectionManager.controlMessages.collect { message ->
                    handleControlMessage(message)
                }
            }

            // Forward config updates from settings to bridge
            launch {
                com.openautolink.app.transport.ConfigUpdateSender.configUpdates.collect { config ->
                    if (connectionManager.connectionState.value != ConnectionState.DISCONNECTED) {
                        connectionManager.sendControlMessage(
                            ControlMessage.ConfigUpdate(config)
                        )
                        Log.i(TAG, "Sent config_update to bridge: $config")
                    }
                }
            }

            // Forward restart requests from settings to bridge
            launch {
                com.openautolink.app.transport.ConfigUpdateSender.restartRequests.collect { restart ->
                    if (connectionManager.connectionState.value != ConnectionState.DISCONNECTED) {
                        connectionManager.sendControlMessage(restart)
                        Log.i(TAG, "Sent restart_services to bridge: wireless=${restart.wireless} bt=${restart.bluetooth}")
                    }
                }
            }

            // Watch for decoder errors — auto-reset codec and request keyframe
            decoderWatchJob?.cancel()
            decoderWatchJob = launch {
                watchDecoderState()
            }

            // Watch call state to route mic purpose (assistant vs call)
            callStateJob?.cancel()
            callStateJob = launch {
                watchCallState()
            }

            // Start connection
            connectionManager.connect(host, port)
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
        callStateJob?.cancel()
        callStateJob = null
        scope.launch {
            connectionManager.disconnectAudio()
            connectionManager.disconnectVideo()
            connectionManager.disconnect()
        }
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

    suspend fun sendAppHello(displayWidth: Int, displayHeight: Int, displayDpi: Int) {
        connectionManager.sendControlMessage(
            ControlMessage.AppHello(
                version = 1,
                name = "OpenAutoLink App",
                displayWidth = displayWidth,
                displayHeight = displayHeight,
                displayDpi = displayDpi
            )
        )
    }

    /** Send a keyframe request to the bridge. */
    suspend fun requestKeyframe() {
        connectionManager.sendControlMessage(ControlMessage.KeyframeRequest)
    }

    /** Send a control message to the bridge (used by touch forwarding, etc.). */
    suspend fun sendControlMessage(message: ControlMessage) {
        connectionManager.sendControlMessage(message)
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
     * Watches decoder state for ERROR — automatically resets codec and requests a keyframe.
     * Waits 500ms before recovery to avoid tight loops on persistent errors.
     */
    private suspend fun watchDecoderState() {
        // Wait until decoder is created
        while (_videoDecoder == null) {
            delay(500)
        }
        _videoDecoder?.decoderState?.collect { state ->
            if (state == DecoderState.ERROR) {
                Log.w(TAG, "Decoder error detected — initiating recovery")
                _remoteDiagnostics?.log(DiagnosticLevel.ERROR, "video", "Decoder error detected — initiating recovery")
                _statusMessage.value = "Video error — recovering..."
                recoverDecoder()
            }
        }
    }

    private suspend fun recoverDecoder() {
        delay(500) // avoid tight error loops
        _videoDecoder?.let { decoder ->
            decoder.resume() // releases codec, clears IDR flag, reconfigures if SPS/PPS available
            requestKeyframe()
            Log.i(TAG, "Decoder recovery: resumed codec, requested keyframe")
        }
    }

    /**
     * Watches the audio player's call state to route mic purpose correctly.
     * IN_CALL → mic frames tagged with PHONE_CALL purpose (bridge routes to BT SCO)
     * Otherwise → mic frames tagged with ASSISTANT purpose (bridge routes to aasdk)
     */
    private suspend fun watchCallState() {
        val player = _audioPlayer ?: return
        player.callState.collect { state ->
            val purpose = when (state) {
                CallState.IN_CALL -> AudioPurpose.PHONE_CALL
                else -> AudioPurpose.ASSISTANT
            }
            _micCaptureManager?.setMicPurpose(purpose)
            Log.d(TAG, "Call state changed to $state — mic purpose set to $purpose")
            _remoteDiagnostics?.log(DiagnosticLevel.DEBUG, "audio", "Call state changed to $state — mic purpose set to $purpose")
        }
    }

    private fun handleControlMessage(message: ControlMessage) {
        when (message) {
            is ControlMessage.Hello -> {
                val info = BridgeInfo(
                    name = message.name,
                    version = message.version,
                    capabilities = message.capabilities,
                    videoPort = message.videoPort,
                    audioPort = message.audioPort
                )
                _bridgeInfo.value = info
                // Send system info once on connect
                _remoteDiagnostics?.log(DiagnosticLevel.INFO, "system",
                    "Android ${android.os.Build.VERSION.RELEASE} (SDK ${android.os.Build.VERSION.SDK_INT}), " +
                    "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}, " +
                    "SoC: ${android.os.Build.SOC_MANUFACTURER} ${android.os.Build.SOC_MODEL}")
                // Send our hello back
                scope.launch {
                    sendAppHello(displayWidth = 0, displayHeight = 0, displayDpi = 0)
                }
            }
            is ControlMessage.PhoneConnected -> {
                _remoteDiagnostics?.log(DiagnosticLevel.INFO, "session", "Phone connected: ${message.phoneName}")
                // Phone connected — open video and audio channels
                val info = _bridgeInfo.value ?: return
                val host = targetHost ?: return
                startVideoChannel(host, info.videoPort)
                startAudioChannel(host, info.audioPort)
                // Start GNSS and vehicle data forwarding
                _gnssForwarder?.start()
                _vehicleDataForwarder?.start()
                _imuForwarder?.start()
            }
            is ControlMessage.PhoneDisconnected -> {
                _remoteDiagnostics?.log(DiagnosticLevel.INFO, "session", "Phone disconnected: ${message.reason}")
                // Phone left — stop video and audio
                stopVideoChannel()
                stopAudioChannel()
                _gnssForwarder?.stop()
                _vehicleDataForwarder?.stop()
                _imuForwarder?.stop()
                _navigationDisplay.clear()
                ClusterNavigationState.clear()
            }
            is ControlMessage.NavState -> {
                _navigationDisplay.onNavState(message)
                // Also push to cluster singleton for CarAppService consumption
                _navigationDisplay.currentManeuver.value?.let { maneuver ->
                    ClusterNavigationState.update(maneuver)
                }
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
                    Log.i(TAG, "Mic source is phone — skipping car mic capture")
                }
            }
            is ControlMessage.MicStop -> {
                _micCaptureManager?.stop()
            }
            is ControlMessage.Error -> {
                Log.e(TAG, "Bridge error ${message.code}: ${message.message}")
                _remoteDiagnostics?.log(DiagnosticLevel.ERROR, "transport", "Bridge error ${message.code}: ${message.message}")
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
            else -> {} // Other messages handled by island-specific collectors
        }
    }

    private fun startVideoChannel(host: String, videoPort: Int) {
        videoCollectJob?.cancel()
        scope.launch {
            connectionManager.connectVideo(host, videoPort)
        }
        // Collect video frames on a dedicated thread — NEVER on Main.
        // Video decode (MediaCodec input queueing) must not block the UI thread.
        videoCollectJob = scope.launch(videoDispatcher) {
            connectionManager.videoFrames.collect { frame ->
                _videoDecoder?.onFrame(frame)
            }
        }
    }

    private fun stopVideoChannel() {
        videoCollectJob?.cancel()
        videoCollectJob = null
        _videoDecoder?.detach()
        scope.launch {
            connectionManager.disconnectVideo()
        }
    }

    private fun startAudioChannel(host: String, audioPort: Int) {
        audioCollectJob?.cancel()
        scope.launch {
            connectionManager.connectAudio(host, audioPort)
        }
        // Collect audio frames and feed to audio player
        audioCollectJob = scope.launch {
            connectionManager.audioFrames.collect { frame ->
                _audioPlayer?.onAudioFrame(frame)
            }
        }
    }

    private fun stopAudioChannel() {
        audioCollectJob?.cancel()
        audioCollectJob = null
        // Stop all active audio purposes
        AudioPurpose.entries.forEach { purpose ->
            _audioPlayer?.stopPurpose(purpose)
        }
        scope.launch {
            connectionManager.disconnectAudio()
        }
    }
}

data class BridgeInfo(
    val name: String,
    val version: Int,
    val capabilities: List<String>,
    val videoPort: Int,
    val audioPort: Int
)
