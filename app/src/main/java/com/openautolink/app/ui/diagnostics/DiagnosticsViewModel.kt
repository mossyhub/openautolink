package com.openautolink.app.ui.diagnostics

import android.app.Application
import android.content.Context
import android.media.AudioManager
import android.os.Build
import android.util.DisplayMetrics
import android.view.WindowManager
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.openautolink.app.data.AppPreferences
import com.openautolink.app.session.BridgeInfo
import com.openautolink.app.session.SessionManager
import com.openautolink.app.session.SessionState
import com.openautolink.app.transport.ConnectionState
import com.openautolink.app.transport.ControlMessage
import com.openautolink.app.video.CodecSelector
import com.openautolink.app.video.VideoStats
import com.openautolink.app.audio.AudioStats
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class CodecInfo(
    val name: String,
    val hwAccelerated: Boolean,
)

data class SystemInfo(
    val androidVersion: String,
    val sdkLevel: Int,
    val device: String,
    val manufacturer: String,
    val model: String,
    val soc: String,
    val displayWidth: Int,
    val displayHeight: Int,
    val displayDpi: Int,
    val h264Decoders: List<CodecInfo>,
    val h265Decoders: List<CodecInfo>,
    val vp9Decoders: List<CodecInfo>,
)

data class NetworkInfo(
    val bridgeHost: String,
    val bridgePort: Int,
    val controlState: String,
    val videoState: String,
    val audioState: String,
    val sessionState: SessionState,
)

data class BridgeStats(
    val bridgeName: String?,
    val bridgeVersion: Int?,
    val capabilities: List<String>,
    val videoFramesSent: Long,
    val audioFramesSent: Long,
    val uptimeSeconds: Long,
    val videoStats: VideoStats,
    val audioStats: AudioStats,
)

data class LogEntry(
    val timestamp: Long,
    val severity: LogSeverity,
    val tag: String,
    val message: String,
)

enum class LogSeverity { DEBUG, INFO, WARN, ERROR }

data class CarInfo(
    val isActive: Boolean = false,
    val speedKmh: Float? = null,
    val gear: String? = null,
    val parkingBrake: Boolean? = null,
    val nightMode: Boolean? = null,
    val batteryPct: Int? = null,
    val fuelLevelPct: Int? = null,
    val rangeKm: Float? = null,
    val ambientTempC: Float? = null,
    val rpmE3: Int? = null,
    val turnSignal: String? = null,
    val headlight: Int? = null,
    val hazardLights: Boolean? = null,
    val steeringAngleDeg: Float? = null,
    val odometerKm: Float? = null,
    val lowFuel: Boolean? = null,
    val chargePortOpen: Boolean? = null,
    val chargePortConnected: Boolean? = null,
    val ignitionState: Int? = null,
    val evChargeRateW: Float? = null,
    val evBatteryLevelWh: Float? = null,
    val evBatteryCapacityWh: Float? = null,
    // Extended EV properties
    val evChargeState: Int? = null,
    val evChargeTimeRemainingSec: Int? = null,
    val evCurrentBatteryCapacityWh: Float? = null,
    val evBatteryTempC: Float? = null,
    val evChargePercentLimit: Float? = null,
    val evChargeCurrentDrawLimitA: Float? = null,
    val evRegenBrakingLevel: Int? = null,
    val evStoppingMode: Int? = null,
    val distanceDisplayUnits: Int? = null,
    // Property access status — key = field name, value = "subscribed"|"not_exposed"|etc
    val propertyStatus: Map<String, String> = emptyMap(),
)

data class DiagnosticsUiState(
    val system: SystemInfo = SystemInfo(
        androidVersion = "", sdkLevel = 0, device = "", manufacturer = "", model = "", soc = "",
        displayWidth = 0, displayHeight = 0, displayDpi = 0,
        h264Decoders = emptyList(), h265Decoders = emptyList(), vp9Decoders = emptyList(),
    ),
    val network: NetworkInfo = NetworkInfo(
        bridgeHost = "", bridgePort = 0,
        controlState = "DISCONNECTED", videoState = "DISCONNECTED",
        audioState = "DISCONNECTED", sessionState = SessionState.IDLE,
    ),
    val bridge: BridgeStats = BridgeStats(
        bridgeName = null, bridgeVersion = null, capabilities = emptyList(),
        videoFramesSent = 0, audioFramesSent = 0, uptimeSeconds = 0,
        videoStats = VideoStats(), audioStats = AudioStats(),
    ),
    val car: CarInfo = CarInfo(),
    val logs: List<LogEntry> = emptyList(),
    val logFilter: LogSeverity = LogSeverity.DEBUG,
)

class DiagnosticsViewModel(application: Application) : AndroidViewModel(application) {

    private val preferences = AppPreferences.getInstance(application)

    // Shared SessionManager — same instance used by ProjectionViewModel
    private val audioManager = application.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val sessionManager = SessionManager.getInstance(viewModelScope, application, audioManager)

    // Standalone vehicle data forwarder for diagnostics display — independent of session
    private var diagnosticVehicleForwarder: com.openautolink.app.input.VehicleDataForwarder? = null

    private val _system = MutableStateFlow(gatherSystemInfo(application))
    private val _network = MutableStateFlow(DiagnosticsUiState().network)
    private val _bridge = MutableStateFlow(DiagnosticsUiState().bridge)
    private val _car = MutableStateFlow(CarInfo())
    private val _logFilter = MutableStateFlow(LogSeverity.DEBUG)

    val uiState: StateFlow<DiagnosticsUiState> = combine(
        _system,
        _network,
        _bridge,
        _car,
        combine(
            com.openautolink.app.diagnostics.DiagnosticLog.localLogs,
            _logFilter,
        ) { logs, filter -> logs to filter },
    ) { system, network, bridge, car, (localEntries, filter) ->
        // Map LocalLogEntry → LogEntry for UI
        val logs = localEntries.map { entry ->
            LogEntry(
                timestamp = entry.timestamp,
                severity = when (entry.level) {
                    com.openautolink.app.diagnostics.DiagnosticLevel.DEBUG -> LogSeverity.DEBUG
                    com.openautolink.app.diagnostics.DiagnosticLevel.INFO -> LogSeverity.INFO
                    com.openautolink.app.diagnostics.DiagnosticLevel.WARN -> LogSeverity.WARN
                    com.openautolink.app.diagnostics.DiagnosticLevel.ERROR -> LogSeverity.ERROR
                },
                tag = entry.tag,
                message = entry.message,
            )
        }
        val filtered = if (filter == LogSeverity.DEBUG) logs
        else logs.filter { it.severity >= filter }
        DiagnosticsUiState(
            system = system,
            network = network,
            bridge = bridge,
            car = car,
            logs = filtered,
            logFilter = filter,
        )
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5000),
        DiagnosticsUiState(system = _system.value)
    )

    init {
        // Start local log capture while diagnostics is open
        com.openautolink.app.diagnostics.DiagnosticLog.startLocalCapture()

        // Observe session state for network tab
        viewModelScope.launch {
            combine(
                sessionManager.sessionState,
                sessionManager.bridgeInfo,
                preferences.bridgeHost,
                preferences.bridgePort,
            ) { state, info, host, port ->
                val connState = when (state) {
                    SessionState.IDLE -> "DISCONNECTED"
                    SessionState.CONNECTING -> "CONNECTING"
                    else -> "CONNECTED"
                }
                val videoState = when (state) {
                    SessionState.STREAMING -> "STREAMING"
                    SessionState.PHONE_CONNECTED -> "CONNECTED"
                    else -> "DISCONNECTED"
                }
                val audioState = when (state) {
                    SessionState.STREAMING -> "STREAMING"
                    SessionState.PHONE_CONNECTED -> "CONNECTED"
                    else -> "DISCONNECTED"
                }
                NetworkInfo(
                    bridgeHost = host,
                    bridgePort = port,
                    controlState = connState,
                    videoState = videoState,
                    audioState = audioState,
                    sessionState = state,
                )
            }.collect { _network.value = it }
        }

        // Observe bridge info + stats
        viewModelScope.launch {
            sessionManager.bridgeInfo.collect { info ->
                _bridge.value = _bridge.value.copy(
                    bridgeName = info?.name,
                    bridgeVersion = info?.version,
                    capabilities = info?.capabilities ?: emptyList(),
                )
            }
        }

        // Observe control messages for Stats messages
        viewModelScope.launch {
            sessionManager.controlMessages.collect { msg ->
                when (msg) {
                    is ControlMessage.Stats -> {
                        _bridge.value = _bridge.value.copy(
                            videoFramesSent = msg.videoFramesSent,
                            audioFramesSent = msg.audioFramesSent,
                            uptimeSeconds = msg.uptimeSeconds,
                        )
                    }
                    is ControlMessage.Error -> {
                        com.openautolink.app.diagnostics.DiagnosticLog.e("Bridge", "Error ${msg.code}: ${msg.message}")
                    }
                    is ControlMessage.PhoneConnected -> {
                        com.openautolink.app.diagnostics.DiagnosticLog.i("Session", "Phone connected: ${msg.phoneName}")
                    }
                    is ControlMessage.PhoneDisconnected -> {
                        com.openautolink.app.diagnostics.DiagnosticLog.i("Session", "Phone disconnected: ${msg.reason}")
                    }
                    else -> {}
                }
            }
        }

        // Observe video/audio stats — cancel stale collectors on state change
        viewModelScope.launch {
            sessionManager.sessionState.collectLatest { state ->
                if (state == SessionState.STREAMING) {
                    coroutineScope {
                        sessionManager.videoStats?.let { flow ->
                            launch { flow.collect { stats -> _bridge.value = _bridge.value.copy(videoStats = stats) } }
                        }
                        sessionManager.audioStats?.let { flow ->
                            launch { flow.collect { stats -> _bridge.value = _bridge.value.copy(audioStats = stats) } }
                        }
                    }
                }
            }
        }

        // Observe vehicle data for car tab — standalone instance, no session required
        // (matches app_v1 pattern where VehiclePropertyMonitor was independent)
        val forwarder = com.openautolink.app.input.VehicleDataForwarderImpl(
            application,
            sendMessage = { /* no-op sender — diagnostics only, no bridge forwarding */ }
        )
        diagnosticVehicleForwarder = forwarder
        forwarder.start()

        viewModelScope.launch {
            forwarder.latestVehicleData.collect { vd ->
                _car.value = CarInfo(
                    isActive = forwarder.isActive,
                    speedKmh = vd.speedKmh,
                    gear = vd.gear,
                    parkingBrake = vd.parkingBrake,
                    nightMode = vd.nightMode,
                    batteryPct = vd.batteryPct,
                    fuelLevelPct = vd.fuelLevelPct,
                    rangeKm = vd.rangeKm,
                    ambientTempC = vd.ambientTempC,
                    rpmE3 = vd.rpmE3,
                    turnSignal = vd.turnSignal,
                    headlight = vd.headlight,
                    hazardLights = vd.hazardLights,
                    steeringAngleDeg = vd.steeringAngleDeg,
                    odometerKm = vd.odometerKm,
                    lowFuel = vd.lowFuel,
                    chargePortOpen = vd.chargePortOpen,
                    chargePortConnected = vd.chargePortConnected,
                    ignitionState = vd.ignitionState,
                    evChargeRateW = vd.evChargeRateW,
                    evBatteryLevelWh = vd.evBatteryLevelWh,
                    evBatteryCapacityWh = vd.evBatteryCapacityWh,
                    evChargeState = vd.evChargeState,
                    evChargeTimeRemainingSec = vd.evChargeTimeRemainingSec,
                    evCurrentBatteryCapacityWh = vd.evCurrentBatteryCapacityWh,
                    evBatteryTempC = vd.evBatteryTempC,
                    evChargePercentLimit = vd.evChargePercentLimit,
                    evChargeCurrentDrawLimitA = vd.evChargeCurrentDrawLimitA,
                    evRegenBrakingLevel = vd.evRegenBrakingLevel,
                    evStoppingMode = vd.evStoppingMode,
                    distanceDisplayUnits = vd.distanceDisplayUnits,
                    propertyStatus = forwarder.propertyStatus,
                )
            }
        }

        com.openautolink.app.diagnostics.DiagnosticLog.i("Diagnostics", "Diagnostics screen opened")
    }

    fun setLogFilter(severity: LogSeverity) {
        _logFilter.value = severity
    }

    fun clearLogs() {
        com.openautolink.app.diagnostics.DiagnosticLog.clearLocal()
    }

    override fun onCleared() {
        super.onCleared()
        diagnosticVehicleForwarder?.stop()
        diagnosticVehicleForwarder = null
        com.openautolink.app.diagnostics.DiagnosticLog.stopLocalCapture()
    }

    companion object {
        private fun gatherSystemInfo(app: Application): SystemInfo {
            val wm = app.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val metrics = DisplayMetrics()
            @Suppress("DEPRECATION")
            wm.defaultDisplay.getRealMetrics(metrics)

            val h264Decoders = parseDecoderList(CodecSelector.listDecoders(CodecSelector.MIME_H264))
            val h265Decoders = parseDecoderList(CodecSelector.listDecoders(CodecSelector.MIME_H265))
            val vp9Decoders = parseDecoderList(CodecSelector.listDecoders(CodecSelector.MIME_VP9))

            return SystemInfo(
                androidVersion = Build.VERSION.RELEASE,
                sdkLevel = Build.VERSION.SDK_INT,
                device = Build.DEVICE,
                manufacturer = Build.MANUFACTURER,
                model = Build.MODEL,
                soc = Build.SOC_MODEL,
                displayWidth = metrics.widthPixels,
                displayHeight = metrics.heightPixels,
                displayDpi = metrics.densityDpi,
                h264Decoders = h264Decoders,
                h265Decoders = h265Decoders,
                vp9Decoders = vp9Decoders,
            )
        }

        private fun parseDecoderList(decoders: List<String>): List<CodecInfo> {
            return decoders.map { entry ->
                val hw = entry.endsWith("[HW]")
                val name = entry.removeSuffix(" [HW]").removeSuffix(" [SW]").trim()
                CodecInfo(name = name, hwAccelerated = hw)
            }
        }
    }
}
