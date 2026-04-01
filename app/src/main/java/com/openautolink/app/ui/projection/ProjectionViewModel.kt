package com.openautolink.app.ui.projection

import android.app.Application
import android.content.Context
import android.media.AudioManager
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
    val phoneName: String? = null,
    val bridgeHost: String = AppPreferences.DEFAULT_BRIDGE_HOST,
    val videoStats: VideoStats = VideoStats(),
    val audioStats: AudioStats = AudioStats(),
    val showStats: Boolean = false,
    val maneuver: ManeuverState? = null,
)

class ProjectionViewModel(application: Application) : AndroidViewModel(application) {

    private val preferences = AppPreferences.getInstance(application)
    private val audioManager = application.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val sessionManager = SessionManager(viewModelScope, application, audioManager)

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
    ) { values ->
        @Suppress("UNCHECKED_CAST")
        ProjectionUiState(
            sessionState = values[0] as SessionState,
            statusMessage = values[1] as String,
            bridgeName = (values[2] as? com.openautolink.app.session.BridgeInfo)?.name,
            phoneName = values[3] as? String,
            bridgeHost = values[4] as String,
            videoStats = values[5] as VideoStats,
            audioStats = values[6] as AudioStats,
            showStats = values[7] as Boolean,
            maneuver = values[8] as? ManeuverState,
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
                    else -> {}
                }
            }
        }

        // Collect video and audio stats when streaming
        viewModelScope.launch {
            sessionManager.sessionState.collect { state ->
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
            sessionManager.start(host, port, codec, micSrc)
        }
    }

    fun disconnect() {
        sessionManager.stop()
    }

    fun toggleStats() {
        _showStats.value = !_showStats.value
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
        sessionManager.videoDecoder?.attach(surface, width, height)
    }

    /** Called when the SurfaceView surface is destroyed. */
    fun onSurfaceDestroyed() {
        sessionManager.videoDecoder?.detach()
    }

    override fun onCleared() {
        sessionManager.stop()
        super.onCleared()
    }
}
