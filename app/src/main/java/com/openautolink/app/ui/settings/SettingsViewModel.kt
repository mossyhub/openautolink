package com.openautolink.app.ui.settings

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.openautolink.app.data.AppPreferences
import com.openautolink.app.transport.NetworkInterfaceInfo
import com.openautolink.app.transport.NetworkInterfaceScanner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class SettingsUiState(
    val directTransport: String = AppPreferences.DEFAULT_DIRECT_TRANSPORT,
    val hotspotSsid: String = AppPreferences.DEFAULT_HOTSPOT_SSID,
    val hotspotPassword: String = AppPreferences.DEFAULT_HOTSPOT_PASSWORD,
    val videoAutoNegotiate: Boolean = AppPreferences.DEFAULT_VIDEO_AUTO_NEGOTIATE,
    val videoCodec: String = AppPreferences.DEFAULT_VIDEO_CODEC,
    val videoFps: Int = AppPreferences.DEFAULT_VIDEO_FPS,
    val displayMode: String = AppPreferences.DEFAULT_DISPLAY_MODE,
    val micSource: String = AppPreferences.DEFAULT_MIC_SOURCE,
    val callAudioViaCar: Boolean = AppPreferences.DEFAULT_CALL_AUDIO_VIA_CAR,
    val videoScalingMode: String = AppPreferences.DEFAULT_VIDEO_SCALING_MODE,
    val aaResolution: String = AppPreferences.DEFAULT_AA_RESOLUTION,
    val aaDpi: Int = AppPreferences.DEFAULT_AA_DPI,
    val aaAutoDpi: Boolean = AppPreferences.DEFAULT_AA_AUTO_DPI,
    val aaWidthMargin: Int = AppPreferences.DEFAULT_AA_WIDTH_MARGIN,
    val aaHeightMargin: Int = AppPreferences.DEFAULT_AA_HEIGHT_MARGIN,
    val aaAutoMargins: Boolean = AppPreferences.DEFAULT_AA_AUTO_MARGINS,
    val aaPixelAspect: Int = AppPreferences.DEFAULT_AA_PIXEL_ASPECT,
    val aaTargetLayoutWidthDp: Int = AppPreferences.DEFAULT_AA_TARGET_LAYOUT_WIDTH_DP,
    val aaViewingDistanceMm: Int = AppPreferences.DEFAULT_AA_VIEWING_DISTANCE_MM,
    val aaDecoderAdditionalDepth: Int = AppPreferences.DEFAULT_AA_DECODER_ADDITIONAL_DEPTH,
    // App-side
    val driveSide: String = AppPreferences.DEFAULT_DRIVE_SIDE,
    val gpsForwarding: Boolean = AppPreferences.DEFAULT_GPS_FORWARDING,
    val clusterNavigation: Boolean = AppPreferences.DEFAULT_CLUSTER_NAVIGATION,
    val overlayStatsButton: Boolean = AppPreferences.DEFAULT_OVERLAY_STATS_BUTTON,
    val fileLoggingEnabled: Boolean = AppPreferences.DEFAULT_FILE_LOGGING_ENABLED,
    val fileLoggingAutoStartUsb: Boolean = AppPreferences.DEFAULT_FILE_LOGGING_AUTOSTART_USB,
    val logcatCaptureEnabled: Boolean = AppPreferences.DEFAULT_LOGCAT_CAPTURE_ENABLED,
    // UI customization
    val syncAaTheme: Boolean = AppPreferences.DEFAULT_SYNC_AA_THEME,
    val hideAaClock: Boolean = AppPreferences.DEFAULT_HIDE_AA_CLOCK,
    val hidePhoneSignal: Boolean = AppPreferences.DEFAULT_HIDE_PHONE_SIGNAL,
    val hideBatteryLevel: Boolean = AppPreferences.DEFAULT_HIDE_BATTERY_LEVEL,
    val sendImuSensors: Boolean = AppPreferences.DEFAULT_SEND_IMU_SENSORS,
    val distanceUnits: String = AppPreferences.DEFAULT_DISTANCE_UNITS,
    // AA safe area insets
    val safeAreaTop: Int = AppPreferences.DEFAULT_SAFE_AREA_TOP,
    val safeAreaBottom: Int = AppPreferences.DEFAULT_SAFE_AREA_BOTTOM,
    val safeAreaLeft: Int = AppPreferences.DEFAULT_SAFE_AREA_LEFT,
    val safeAreaRight: Int = AppPreferences.DEFAULT_SAFE_AREA_RIGHT,
    // Key remap
    val keyRemap: String = AppPreferences.DEFAULT_KEY_REMAP,
    // Volume offsets
    val volumeOffsetMedia: Int = AppPreferences.DEFAULT_VOLUME_OFFSET_MEDIA,
    val volumeOffsetNavigation: Int = AppPreferences.DEFAULT_VOLUME_OFFSET_NAVIGATION,
    val volumeOffsetAssistant: Int = AppPreferences.DEFAULT_VOLUME_OFFSET_ASSISTANT,
    // Multi-phone
    val defaultPhoneName: String = AppPreferences.DEFAULT_DEFAULT_PHONE_NAME,
    // Manual IP (emulator testing)
    val manualIpEnabled: Boolean = AppPreferences.DEFAULT_MANUAL_IP_ENABLED,
    val manualIpAddress: String = AppPreferences.DEFAULT_MANUAL_IP_ADDRESS,
)

class SettingsViewModel(application: Application) : AndroidViewModel(application) {

    private val preferences = AppPreferences.getInstance(application)
    private val interfaceScanner = NetworkInterfaceScanner(application)

    val networkInterfaces: StateFlow<List<NetworkInterfaceInfo>> = interfaceScanner.interfaces

    val uiState: StateFlow<SettingsUiState> = combine(
        preferences.videoAutoNegotiate,
        preferences.videoCodec,
        preferences.videoFps,
        preferences.displayMode,
        preferences.micSource,
        preferences.videoScalingMode,
        preferences.aaResolution,
        preferences.aaDpi,
        preferences.aaAutoDpi,
        preferences.aaWidthMargin,
        preferences.aaHeightMargin,
        preferences.aaAutoMargins,
        preferences.aaPixelAspect,
        preferences.aaTargetLayoutWidthDp,
        preferences.aaViewingDistanceMm,
        preferences.aaDecoderAdditionalDepth,
        preferences.driveSide,
        preferences.gpsForwarding,
        preferences.clusterNavigation,
        preferences.overlayStatsButton,
        preferences.fileLoggingEnabled,
        preferences.fileLoggingAutoStartUsb,
        preferences.logcatCaptureEnabled,
        preferences.syncAaTheme,
        preferences.hideAaClock,
        preferences.hidePhoneSignal,
        preferences.hideBatteryLevel,
        preferences.sendImuSensors,
        preferences.distanceUnits,
        preferences.safeAreaTop,
        preferences.safeAreaBottom,
        preferences.safeAreaLeft,
        preferences.safeAreaRight,
        preferences.keyRemap,
        preferences.volumeOffsetMedia,
        preferences.volumeOffsetNavigation,
        preferences.volumeOffsetAssistant,
        preferences.defaultPhoneName,
        preferences.manualIpEnabled,
        preferences.manualIpAddress,
        preferences.callAudioViaCar,
    ) { values: Array<Any> ->
        SettingsUiState(
            videoAutoNegotiate = values[0] as Boolean,
            videoCodec = values[1] as String,
            videoFps = values[2] as Int,
            displayMode = values[3] as String,
            micSource = values[4] as String,
            videoScalingMode = values[5] as String,
            aaResolution = values[6] as String,
            aaDpi = values[7] as Int,
            aaAutoDpi = values[8] as Boolean,
            aaWidthMargin = values[9] as Int,
            aaHeightMargin = values[10] as Int,
            aaAutoMargins = values[11] as Boolean,
            aaPixelAspect = values[12] as Int,
            aaTargetLayoutWidthDp = values[13] as Int,
            aaViewingDistanceMm = values[14] as Int,
            aaDecoderAdditionalDepth = values[15] as Int,
            driveSide = values[16] as String,
            gpsForwarding = values[17] as Boolean,
            clusterNavigation = values[18] as Boolean,
            overlayStatsButton = values[19] as Boolean,
            fileLoggingEnabled = values[20] as Boolean,
            fileLoggingAutoStartUsb = values[21] as Boolean,
            logcatCaptureEnabled = values[22] as Boolean,
            syncAaTheme = values[23] as Boolean,
            hideAaClock = values[24] as Boolean,
            hidePhoneSignal = values[25] as Boolean,
            hideBatteryLevel = values[26] as Boolean,
            sendImuSensors = values[27] as Boolean,
            distanceUnits = values[28] as String,
            safeAreaTop = values[29] as Int,
            safeAreaBottom = values[30] as Int,
            safeAreaLeft = values[31] as Int,
            safeAreaRight = values[32] as Int,
            keyRemap = values[33] as String,
            volumeOffsetMedia = values[34] as Int,
            volumeOffsetNavigation = values[35] as Int,
            volumeOffsetAssistant = values[36] as Int,
            defaultPhoneName = values[37] as String,
            manualIpEnabled = values[38] as Boolean,
            manualIpAddress = values[39] as String,
            callAudioViaCar = values[40] as Boolean,
        )
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5000),
        SettingsUiState()
    )

    private val _hotspotSsidOverride = MutableStateFlow(AppPreferences.DEFAULT_HOTSPOT_SSID)
    private val _hotspotPasswordOverride = MutableStateFlow(AppPreferences.DEFAULT_HOTSPOT_PASSWORD)
    private val _directTransportOverride = MutableStateFlow(AppPreferences.DEFAULT_DIRECT_TRANSPORT)

    init {
        viewModelScope.launch {
            preferences.hotspotSsid.collect { _hotspotSsidOverride.value = it }
        }
        viewModelScope.launch {
            preferences.hotspotPassword.collect { _hotspotPasswordOverride.value = it }
        }
        viewModelScope.launch {
            preferences.directTransport.collect { _directTransportOverride.value = it }
        }
    }

    val settingsState: StateFlow<SettingsUiState> = combine(
        uiState,
        _hotspotSsidOverride,
        _hotspotPasswordOverride,
        _directTransportOverride,
    ) { state, ssid, psk, transport ->
        state.copy(hotspotSsid = ssid, hotspotPassword = psk, directTransport = transport)
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5000),
        SettingsUiState()
    )

    // ── Multi-phone (Car Hotspot mode) ─────────────────────────────────
    // Exposed as separate flows rather than baked into [SettingsUiState] —
    // [combine] is already at its max-arg limit and the multi-phone settings
    // change independently of the rest.

    private val knownPhonesStore = com.openautolink.app.data.KnownPhonesStore(preferences)

    val connectionMode: StateFlow<String> = preferences.connectionMode.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5000),
        AppPreferences.DEFAULT_CONNECTION_MODE,
    )

    val defaultPhoneId: StateFlow<String> = preferences.defaultPhoneId.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5000),
        AppPreferences.DEFAULT_DEFAULT_PHONE_ID,
    )

    val knownPhones: StateFlow<List<com.openautolink.app.data.KnownPhone>> =
        knownPhonesStore.phones.stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5000),
            emptyList(),
        )

    val alwaysAskPhone: StateFlow<Boolean> = preferences.alwaysAskPhone.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5000),
        AppPreferences.DEFAULT_ALWAYS_ASK_PHONE,
    )

    fun updateConnectionMode(mode: String) {
        viewModelScope.launch { preferences.setConnectionMode(mode) }
    }

    fun setDefaultPhoneId(id: String) {
        viewModelScope.launch { preferences.setDefaultPhoneId(id) }
    }

    fun forgetKnownPhone(id: String) {
        viewModelScope.launch { knownPhonesStore.remove(id) }
    }

    fun setAlwaysAskPhone(enabled: Boolean) {
        viewModelScope.launch { preferences.setAlwaysAskPhone(enabled) }
    }

    val carHotspotAutoInterface: StateFlow<Boolean> =
        preferences.carHotspotAutoInterface.stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5000),
            AppPreferences.DEFAULT_CAR_HOTSPOT_AUTO_INTERFACE,
        )

    val carHotspotInterfaceName: StateFlow<String> =
        preferences.carHotspotInterfaceName.stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5000),
            AppPreferences.DEFAULT_CAR_HOTSPOT_INTERFACE_NAME,
        )

    fun setCarHotspotAutoInterface(enabled: Boolean) {
        viewModelScope.launch { preferences.setCarHotspotAutoInterface(enabled) }
    }

    fun setCarHotspotInterfaceName(name: String) {
        viewModelScope.launch { preferences.setCarHotspotInterfaceName(name) }
    }

    /**
     * Snapshot of currently-up, non-virtual interfaces from
     * [com.openautolink.app.transport.PhoneDiscovery]. Used by the dropdown
     * when the user disables auto-detection.
     */
    fun listCarHotspotInterfaces(): List<Pair<String, String>> {
        return com.openautolink.app.transport.PhoneDiscovery
            .getInstance(getApplication())
            .listRealInterfaces()
    }

    fun updateDirectTransport(transport: String) {
        viewModelScope.launch { preferences.setDirectTransport(transport) }
    }

    fun updateHotspotSsid(ssid: String) {
        viewModelScope.launch { preferences.setHotspotSsid(ssid) }
    }

    fun updateHotspotPassword(password: String) {
        viewModelScope.launch { preferences.setHotspotPassword(password) }
    }
    fun updateVideoCodec(codec: String) {
        viewModelScope.launch { preferences.setVideoCodec(codec) }
    }

    fun updateVideoAutoNegotiate(enabled: Boolean) {
        viewModelScope.launch { preferences.setVideoAutoNegotiate(enabled) }
    }

    fun updateVideoFps(fps: Int) {
        viewModelScope.launch { preferences.setVideoFps(fps) }
    }

    fun updateDisplayMode(mode: String) {
        viewModelScope.launch { preferences.setDisplayMode(mode) }
    }

    fun updateMicSource(source: String) {
        viewModelScope.launch { preferences.setMicSource(source) }
    }

    fun updateCallAudioViaCar(enabled: Boolean) {
        viewModelScope.launch { preferences.setCallAudioViaCar(enabled) }
    }

    fun updateVideoScalingMode(mode: String) {
        viewModelScope.launch { preferences.setVideoScalingMode(mode) }
    }

    fun updateAaResolution(resolution: String) {
        viewModelScope.launch { preferences.setAaResolution(resolution) }
    }

    fun updateAaDpi(dpi: Int) {
        viewModelScope.launch { preferences.setAaDpi(dpi) }
    }

    fun updateAaAutoDpi(value: Boolean) {
        viewModelScope.launch { preferences.setAaAutoDpi(value) }
    }

    fun updateAaWidthMargin(margin: Int) {
        viewModelScope.launch { preferences.setAaWidthMargin(margin) }
    }

    fun updateAaHeightMargin(margin: Int) {
        viewModelScope.launch { preferences.setAaHeightMargin(margin) }
    }

    fun updateAaAutoMargins(value: Boolean) {
        viewModelScope.launch { preferences.setAaAutoMargins(value) }
    }

    fun updateAaPixelAspect(value: Int) {
        viewModelScope.launch { preferences.setAaPixelAspect(value) }
    }

    fun updateAaTargetLayoutWidthDp(value: Int) {
        viewModelScope.launch { preferences.setAaTargetLayoutWidthDp(value) }
    }

    fun updateAaViewingDistanceMm(value: Int) {
        viewModelScope.launch { preferences.setAaViewingDistanceMm(value) }
    }

    fun updateAaDecoderAdditionalDepth(value: Int) {
        viewModelScope.launch { preferences.setAaDecoderAdditionalDepth(value) }
    }

    fun updateDriveSide(side: String) {
        viewModelScope.launch { preferences.setDriveSide(side) }
    }

    fun updateGpsForwarding(enabled: Boolean) {
        viewModelScope.launch { preferences.setGpsForwarding(enabled) }
    }

    fun updateClusterNavigation(enabled: Boolean) {
        viewModelScope.launch { preferences.setClusterNavigation(enabled) }
    }

    fun updateOverlayStatsButton(visible: Boolean) {
        viewModelScope.launch { preferences.setOverlayStatsButton(visible) }
    }

    fun updateFileLoggingEnabled(enabled: Boolean) {
        viewModelScope.launch { preferences.setFileLoggingEnabled(enabled) }
    }

    fun updateFileLoggingAutoStartUsb(enabled: Boolean) {
        viewModelScope.launch { preferences.setFileLoggingAutoStartUsb(enabled) }
    }

    fun updateLogcatCaptureEnabled(enabled: Boolean) {
        viewModelScope.launch { preferences.setLogcatCaptureEnabled(enabled) }
    }

    fun updateSyncAaTheme(enabled: Boolean) {
        viewModelScope.launch { preferences.setSyncAaTheme(enabled) }
    }

    fun updateHideAaClock(enabled: Boolean) {
        viewModelScope.launch { preferences.setHideAaClock(enabled) }
    }

    fun updateHidePhoneSignal(enabled: Boolean) {
        viewModelScope.launch { preferences.setHidePhoneSignal(enabled) }
    }

    fun updateHideBatteryLevel(enabled: Boolean) {
        viewModelScope.launch { preferences.setHideBatteryLevel(enabled) }
    }

    fun updateSendImuSensors(enabled: Boolean) {
        viewModelScope.launch { preferences.setSendImuSensors(enabled) }
    }

    fun updateDistanceUnits(units: String) {
        viewModelScope.launch {
            preferences.setDistanceUnits(units)
            com.openautolink.app.cluster.ClusterNavigationState.distanceUnits = units
        }
    }

    fun scanNetworkInterfaces() {
        viewModelScope.launch(Dispatchers.IO) {
            interfaceScanner.scan()
        }
    }

    fun updateSafeAreaInsets(top: Int, bottom: Int, left: Int, right: Int) {
        viewModelScope.launch {
            preferences.setSafeAreaTop(top)
            preferences.setSafeAreaBottom(bottom)
            preferences.setSafeAreaLeft(left)
            preferences.setSafeAreaRight(right)
        }
    }

    fun updateKeyRemap(json: String) {
        viewModelScope.launch { preferences.setKeyRemap(json) }
    }

    fun updateVolumeOffsetMedia(offset: Int) {
        viewModelScope.launch { preferences.setVolumeOffsetMedia(offset) }
    }

    fun updateVolumeOffsetNavigation(offset: Int) {
        viewModelScope.launch { preferences.setVolumeOffsetNavigation(offset) }
    }

    fun updateVolumeOffsetAssistant(offset: Int) {
        viewModelScope.launch { preferences.setVolumeOffsetAssistant(offset) }
    }

    fun clearDefaultPhone() {
        viewModelScope.launch {
            preferences.setDefaultPhoneName("")
            com.openautolink.app.session.SessionManager.instanceOrNull()?.clearDefaultPhone()
        }
    }

    fun updateManualIpEnabled(enabled: Boolean) {
        viewModelScope.launch { preferences.setManualIpEnabled(enabled) }
    }

    fun updateManualIpAddress(address: String) {
        viewModelScope.launch { preferences.setManualIpAddress(address) }
    }

    override fun onCleared() {
        super.onCleared()
    }

    /** Reconnect the AA session with current settings. Called from Settings "Save & Reconnect". */
    fun saveAndReconnect() {
        viewModelScope.launch {
            val sm = com.openautolink.app.session.SessionManager.instanceOrNull() ?: return@launch
            val codec = preferences.videoCodec.first()
            val micSrc = preferences.micSource.first()
            val callAudioViaCar = preferences.callAudioViaCar.first()
            val scalingMode = preferences.videoScalingMode.first()
            val hotspotSsid = preferences.hotspotSsid.first()
            val hotspotPassword = preferences.hotspotPassword.first()
            val directTransport = preferences.directTransport.first()
            val videoAutoNeg = preferences.videoAutoNegotiate.first()
            val aaRes = preferences.aaResolution.first()
            val aaDpi = preferences.aaDpi.first()
            val aaAutoDpi = preferences.aaAutoDpi.first()
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
            val volMedia = preferences.volumeOffsetMedia.first()
            val volNav = preferences.volumeOffsetNavigation.first()
            val volAssistant = preferences.volumeOffsetAssistant.first()
            val manualIpEnabled = preferences.manualIpEnabled.first()
            val manualIp = if (manualIpEnabled) preferences.manualIpAddress.first().takeIf { it.isNotBlank() } else null
            val saTop = preferences.safeAreaTop.first()
            val saBottom = preferences.safeAreaBottom.first()
            val saLeft = preferences.safeAreaLeft.first()
            val saRight = preferences.safeAreaRight.first()
            sm.reconnect(
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
                callAudioViaCar = callAudioViaCar,
            )
        }
    }
}
