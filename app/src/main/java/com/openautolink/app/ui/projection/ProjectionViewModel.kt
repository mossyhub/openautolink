package com.openautolink.app.ui.projection

import android.app.Application
import android.content.Context
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.util.Log
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.Surface
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.openautolink.app.audio.AudioStats
import com.openautolink.app.data.AppPreferences
import com.openautolink.app.input.SteeringWheelController
import com.openautolink.app.input.TouchForwarder
import com.openautolink.app.input.TouchForwarderImpl
import com.openautolink.app.navigation.ManeuverState
import com.openautolink.app.session.SessionManager
import com.openautolink.app.session.SessionState
import com.openautolink.app.transport.ControlMessage
import com.openautolink.app.video.VideoStats
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class ProjectionUiState(
    val sessionState: SessionState = SessionState.IDLE,
    val statusMessage: String = "Ready",
    val bridgeName: String? = null,
    val bridgeVersion: Int? = null,
    val phoneName: String? = null,
    val bridgeHost: String = AppPreferences.DEFAULT_BRIDGE_HOST,
    val videoStats: VideoStats = VideoStats(),
    val audioStats: AudioStats = AudioStats(),
    val showStats: Boolean = false,
    val maneuver: ManeuverState? = null,
    val phoneBatteryLevel: Int? = null,
    val phoneBatteryCritical: Boolean = false,
    val voiceSessionActive: Boolean = false,
    val phoneSignalStrength: Int? = null,
    val bridgeUptimeSeconds: Long = 0,
    val displayMode: String = AppPreferences.DEFAULT_DISPLAY_MODE,
    val customViewportWidth: Int = 0,
    val customViewportHeight: Int = 0,
    val overlayPhoneSwitchButton: Boolean = AppPreferences.DEFAULT_OVERLAY_PHONE_SWITCH_BUTTON,
    val safeAreaTop: Int = AppPreferences.DEFAULT_SAFE_AREA_TOP,
    val safeAreaBottom: Int = AppPreferences.DEFAULT_SAFE_AREA_BOTTOM,
    val safeAreaLeft: Int = AppPreferences.DEFAULT_SAFE_AREA_LEFT,
    val safeAreaRight: Int = AppPreferences.DEFAULT_SAFE_AREA_RIGHT,
    val contentInsetTop: Int = AppPreferences.DEFAULT_CONTENT_INSET_TOP,
    val contentInsetBottom: Int = AppPreferences.DEFAULT_CONTENT_INSET_BOTTOM,
    val contentInsetLeft: Int = AppPreferences.DEFAULT_CONTENT_INSET_LEFT,
    val contentInsetRight: Int = AppPreferences.DEFAULT_CONTENT_INSET_RIGHT,
)

class ProjectionViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        private const val TAG = "ProjectionViewModel"
    }

    private val preferences = AppPreferences.getInstance(application)
    private val audioManager = application.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val connectivityManager = application.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private val sessionManager = SessionManager.getInstance(viewModelScope, application, audioManager)

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
    private val _bridgeUptimeSeconds = MutableStateFlow(0L)
    private val _pairedPhones = MutableStateFlow<List<com.openautolink.app.transport.ControlMessage.PairedPhone>>(emptyList())
    private val _showPhoneSwitcher = MutableStateFlow(false)

    /** Paired phones list for the phone switcher popup. */
    val pairedPhones: StateFlow<List<com.openautolink.app.transport.ControlMessage.PairedPhone>> = _pairedPhones

    /** Whether the phone switcher popup is shown. */
    val showPhoneSwitcher: StateFlow<Boolean> = _showPhoneSwitcher

    // Pending surface — stored when surfaceCreated fires before decoder exists.
    // Attached to decoder on session start or when decoder becomes available.
    private var pendingSurface: Surface? = null
    private var pendingSurfaceWidth: Int = 0
    private var pendingSurfaceHeight: Int = 0
    private var surfaceDebounceJob: kotlinx.coroutines.Job? = null

    val uiState: StateFlow<ProjectionUiState> = combine(
        sessionManager.sessionState,
        sessionManager.statusMessage,
        sessionManager.bridgeInfo,
        _phoneName,
        preferences.bridgeHost,
        _videoStats,
        _audioStats,
        _showStats,
        sessionManager.currentManeuver,
        sessionManager.phoneBatteryLevel,
        sessionManager.phoneBatteryCritical,
        sessionManager.voiceSessionActive,
        preferences.displayMode,
        preferences.customViewportWidth,
        preferences.customViewportHeight,
        preferences.overlayPhoneSwitchButton,
        preferences.safeAreaTop,
        preferences.safeAreaBottom,
        preferences.safeAreaLeft,
        preferences.safeAreaRight,
        preferences.contentInsetTop,
        preferences.contentInsetBottom,
        preferences.contentInsetLeft,
        preferences.contentInsetRight,
        sessionManager.phoneSignalStrength,
        _bridgeUptimeSeconds,
    ) { values ->
        @Suppress("UNCHECKED_CAST")
        val info = values[2] as? com.openautolink.app.session.BridgeInfo
        ProjectionUiState(
            sessionState = values[0] as SessionState,
            statusMessage = values[1] as String,
            bridgeName = info?.name,
            bridgeVersion = info?.version,
            phoneName = values[3] as? String,
            bridgeHost = values[4] as String,
            videoStats = values[5] as VideoStats,
            audioStats = values[6] as AudioStats,
            showStats = values[7] as Boolean,
            maneuver = values[8] as? ManeuverState,
            phoneBatteryLevel = values[9] as? Int,
            phoneBatteryCritical = values[10] as Boolean,
            voiceSessionActive = values[11] as Boolean,
            displayMode = values[12] as String,
            customViewportWidth = values[13] as Int,
            customViewportHeight = values[14] as Int,
            overlayPhoneSwitchButton = values[15] as Boolean,
            safeAreaTop = values[16] as Int,
            safeAreaBottom = values[17] as Int,
            safeAreaLeft = values[18] as Int,
            safeAreaRight = values[19] as Int,
            contentInsetTop = values[20] as Int,
            contentInsetBottom = values[21] as Int,
            contentInsetLeft = values[22] as Int,
            contentInsetRight = values[23] as Int,
            phoneSignalStrength = values[24] as? Int,
            bridgeUptimeSeconds = values[25] as Long,
        )
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5000),
        ProjectionUiState()
    )

    init {
        // Observe phone connected/disconnected from control messages
        viewModelScope.launch {
            sessionManager.controlMessages.collect { message ->
                when (message) {
                    is com.openautolink.app.transport.ControlMessage.PhoneConnected -> {
                        _phoneName.value = message.phoneName
                    }
                    is com.openautolink.app.transport.ControlMessage.PhoneDisconnected -> {
                        _phoneName.value = null
                    }
                    is com.openautolink.app.transport.ControlMessage.PairedPhones -> {
                        _pairedPhones.value = message.phones
                    }
                    is com.openautolink.app.transport.ControlMessage.Stats -> {
                        _bridgeUptimeSeconds.value = message.uptimeSeconds
                    }
                    else -> {}
                }
            }
        }

        // Collect video and audio stats when streaming
        viewModelScope.launch {
            sessionManager.sessionState.collect { state ->
                // Attach pending surface when decoder becomes available
                if (state == SessionState.BRIDGE_CONNECTED ||
                    state == SessionState.PHONE_CONNECTED ||
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

    fun connect() {
        viewModelScope.launch {
            val host = preferences.bridgeHost.first()
            val port = preferences.bridgePort.first()
            val codec = preferences.videoCodec.first()
            val micSrc = preferences.micSource.first()
            val ifaceName = preferences.networkInterface.first()
            val diagEnabled = preferences.remoteDiagnosticsEnabled.first()
            val diagMinLevel = preferences.remoteDiagnosticsMinLevel.first()
            val network = resolveNetwork(ifaceName)
            sessionManager.start(host, port, codec, micSrc,
                diagnosticsEnabled = diagEnabled, diagnosticsMinLevel = diagMinLevel,
                network = network)
        }
    }

    fun disconnect() {
        sessionManager.stop()
    }

    /**
     * Resolve a saved network interface name to an [android.net.Network] for socket binding.
     * Uses two-tier lookup: first by interface name via ConnectivityManager, then falls back
     * to default routing if not found. Skips binding for loopback addresses.
     */
    private fun resolveNetwork(interfaceName: String): Network? {
        if (interfaceName.isBlank()) {
            Log.d(TAG, "No preferred network interface — default routing")
            return null
        }
        try {
            for (network in connectivityManager.allNetworks) {
                val linkProps = connectivityManager.getLinkProperties(network) ?: continue
                if (linkProps.interfaceName == interfaceName) {
                    val caps = connectivityManager.getNetworkCapabilities(network)
                    val transport = when {
                        caps?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) == true -> "Ethernet"
                        caps?.hasTransport(NetworkCapabilities.TRANSPORT_USB) == true -> "USB"
                        caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true -> "WiFi"
                        else -> "other"
                    }
                    Log.i(TAG, "Bound to interface '$interfaceName' ($transport) handle=${network.networkHandle}")
                    com.openautolink.app.diagnostics.DiagnosticLog.i("transport",
                        "Bound to interface '$interfaceName' ($transport)")
                    return network
                }
            }
            Log.w(TAG, "Interface '$interfaceName' not found in ConnectivityManager — default routing")
            com.openautolink.app.diagnostics.DiagnosticLog.w("transport",
                "Interface '$interfaceName' not found — default routing")
        } catch (e: Exception) {
            Log.w(TAG, "Network resolution failed: ${e.message}")
        }
        return null
    }

    fun toggleStats() {
        _showStats.value = !_showStats.value
    }

    fun togglePhoneSwitcher() {
        if (!_showPhoneSwitcher.value) {
            // Request fresh list when opening
            viewModelScope.launch {
                com.openautolink.app.transport.ConfigUpdateSender.sendControlMessage(
                    com.openautolink.app.transport.ControlMessage.ListPairedPhones
                )
            }
        }
        _showPhoneSwitcher.value = !_showPhoneSwitcher.value
    }

    fun switchPhone(mac: String) {
        viewModelScope.launch {
            com.openautolink.app.transport.ConfigUpdateSender.sendControlMessage(
                com.openautolink.app.transport.ControlMessage.SwitchPhone(mac)
            )
        }
        _showPhoneSwitcher.value = false
    }

    /** Forward a touch event from the projection surface to the bridge. */
    fun onTouchEvent(event: MotionEvent, surfaceWidth: Int, surfaceHeight: Int) {
        val stats = _videoStats.value
        if (stats.width <= 0 || stats.height <= 0) return
        touchForwarder.onTouch(event, surfaceWidth, surfaceHeight, stats.width, stats.height)
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

    override fun onCleared() {
        sessionManager.stop()
        super.onCleared()
    }
}
