package com.openautolink.app.ui.settings

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.openautolink.app.data.AppPreferences
import com.openautolink.app.transport.BridgeDiscovery
import com.openautolink.app.transport.ConfigUpdateSender
import com.openautolink.app.transport.ControlMessage
import com.openautolink.app.transport.DiscoveredBridge
import com.openautolink.app.transport.NetworkInterfaceInfo
import com.openautolink.app.transport.NetworkInterfaceScanner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class SettingsUiState(
    val bridgeHost: String = AppPreferences.DEFAULT_BRIDGE_HOST,
    val bridgePort: Int = AppPreferences.DEFAULT_BRIDGE_PORT,
    val videoCodec: String = AppPreferences.DEFAULT_VIDEO_CODEC,
    val videoFps: Int = AppPreferences.DEFAULT_VIDEO_FPS,
    val displayMode: String = AppPreferences.DEFAULT_DISPLAY_MODE,
    val micSource: String = AppPreferences.DEFAULT_MIC_SOURCE,
    val networkInterface: String = AppPreferences.DEFAULT_NETWORK_INTERFACE,
    val remoteDiagnosticsEnabled: Boolean = AppPreferences.DEFAULT_REMOTE_DIAGNOSTICS_ENABLED,
    val remoteDiagnosticsMinLevel: String = AppPreferences.DEFAULT_REMOTE_DIAGNOSTICS_MIN_LEVEL,
    // Bridge config — AA stream
    val aaResolution: String = AppPreferences.DEFAULT_AA_RESOLUTION,
    val aaDpi: Int = AppPreferences.DEFAULT_AA_DPI,
    val aaWidthMargin: Int = AppPreferences.DEFAULT_AA_WIDTH_MARGIN,
    val aaHeightMargin: Int = AppPreferences.DEFAULT_AA_HEIGHT_MARGIN,
    val aaPixelAspect: Int = AppPreferences.DEFAULT_AA_PIXEL_ASPECT,
    val videoScalingMode: String = AppPreferences.DEFAULT_VIDEO_SCALING_MODE,
    val phoneMode: String = AppPreferences.DEFAULT_PHONE_MODE,
    val wifiBand: String = AppPreferences.DEFAULT_WIFI_BAND,
    val wifiCountry: String = AppPreferences.DEFAULT_WIFI_COUNTRY,
    val wifiSsid: String = AppPreferences.DEFAULT_WIFI_SSID,
    val wifiPassword: String = AppPreferences.DEFAULT_WIFI_PASSWORD,
    val headUnitName: String = AppPreferences.DEFAULT_HEAD_UNIT_NAME,
    val btMac: String = AppPreferences.DEFAULT_BT_MAC,
    // App-side
    val driveSide: String = AppPreferences.DEFAULT_DRIVE_SIDE,
    val gpsForwarding: Boolean = AppPreferences.DEFAULT_GPS_FORWARDING,
    val clusterNavigation: Boolean = AppPreferences.DEFAULT_CLUSTER_NAVIGATION,
    val audioSource: String = AppPreferences.DEFAULT_AUDIO_SOURCE,
    val callQuality: String = AppPreferences.DEFAULT_CALL_QUALITY,
    val overlaySettingsButton: Boolean = AppPreferences.DEFAULT_OVERLAY_SETTINGS_BUTTON,
    val overlayStatsButton: Boolean = AppPreferences.DEFAULT_OVERLAY_STATS_BUTTON,
    val overlayPhoneSwitchButton: Boolean = AppPreferences.DEFAULT_OVERLAY_PHONE_SWITCH_BUTTON,
    val defaultPhoneMac: String = AppPreferences.DEFAULT_DEFAULT_PHONE_MAC,
    // UI customization
    val syncAaTheme: Boolean = AppPreferences.DEFAULT_SYNC_AA_THEME,
    val hideAaClock: Boolean = AppPreferences.DEFAULT_HIDE_AA_CLOCK,
    val sendImuSensors: Boolean = AppPreferences.DEFAULT_SEND_IMU_SENSORS,
    // AA safe area insets
    val safeAreaTop: Int = AppPreferences.DEFAULT_SAFE_AREA_TOP,
    val safeAreaBottom: Int = AppPreferences.DEFAULT_SAFE_AREA_BOTTOM,
    val safeAreaLeft: Int = AppPreferences.DEFAULT_SAFE_AREA_LEFT,
    val safeAreaRight: Int = AppPreferences.DEFAULT_SAFE_AREA_RIGHT,
    // AA content insets
    val contentInsetTop: Int = AppPreferences.DEFAULT_CONTENT_INSET_TOP,
    val contentInsetBottom: Int = AppPreferences.DEFAULT_CONTENT_INSET_BOTTOM,
    val contentInsetLeft: Int = AppPreferences.DEFAULT_CONTENT_INSET_LEFT,
    val contentInsetRight: Int = AppPreferences.DEFAULT_CONTENT_INSET_RIGHT,
)

class SettingsViewModel(application: Application) : AndroidViewModel(application) {

    private val preferences = AppPreferences.getInstance(application)
    private val bridgeDiscovery = BridgeDiscovery(application)
    private val interfaceScanner = NetworkInterfaceScanner(application)

    val discoveredBridges: StateFlow<List<DiscoveredBridge>> = bridgeDiscovery.discoveredBridges
    val isDiscovering: StateFlow<Boolean> = bridgeDiscovery.isDiscovering
    val networkInterfaces: StateFlow<List<NetworkInterfaceInfo>> = interfaceScanner.interfaces

    private val _pairedPhones = MutableStateFlow<List<ControlMessage.PairedPhone>>(emptyList())
    val pairedPhones: StateFlow<List<ControlMessage.PairedPhone>> = _pairedPhones

    private val _phonesLoading = MutableStateFlow(false)
    val phonesLoading: StateFlow<Boolean> = _phonesLoading

    val uiState: StateFlow<SettingsUiState> = combine(
        preferences.bridgeHost,
        preferences.bridgePort,
        preferences.videoCodec,
        preferences.videoFps,
        preferences.displayMode,
        preferences.micSource,
        preferences.networkInterface,
        preferences.remoteDiagnosticsEnabled,
        preferences.remoteDiagnosticsMinLevel,
        preferences.aaResolution,
        preferences.aaDpi,
        preferences.aaWidthMargin,
        preferences.aaHeightMargin,
        preferences.aaPixelAspect,
        preferences.videoScalingMode,
        preferences.phoneMode,
        preferences.wifiBand,
        preferences.wifiCountry,
        preferences.wifiSsid,
        preferences.wifiPassword,
        preferences.headUnitName,
        preferences.btMac,
        preferences.driveSide,
        preferences.gpsForwarding,
        preferences.clusterNavigation,
        preferences.audioSource,
        preferences.callQuality,
        preferences.overlaySettingsButton,
        preferences.overlayStatsButton,
        preferences.overlayPhoneSwitchButton,
        preferences.defaultPhoneMac,
        preferences.syncAaTheme,
        preferences.hideAaClock,
        preferences.sendImuSensors,
        preferences.safeAreaTop,
        preferences.safeAreaBottom,
        preferences.safeAreaLeft,
        preferences.safeAreaRight,
        preferences.contentInsetTop,
        preferences.contentInsetBottom,
        preferences.contentInsetLeft,
        preferences.contentInsetRight,
    ) { values: Array<Any> ->
        SettingsUiState(
            bridgeHost = values[0] as String,
            bridgePort = values[1] as Int,
            videoCodec = values[2] as String,
            videoFps = values[3] as Int,
            displayMode = values[4] as String,
            micSource = values[5] as String,
            networkInterface = values[6] as String,
            remoteDiagnosticsEnabled = values[7] as Boolean,
            remoteDiagnosticsMinLevel = values[8] as String,
            aaResolution = values[9] as String,
            aaDpi = values[10] as Int,
            aaWidthMargin = values[11] as Int,
            aaHeightMargin = values[12] as Int,
            aaPixelAspect = values[13] as Int,
            videoScalingMode = values[14] as String,
            phoneMode = values[15] as String,
            wifiBand = values[16] as String,
            wifiCountry = values[17] as String,
            wifiSsid = values[18] as String,
            wifiPassword = values[19] as String,
            headUnitName = values[20] as String,
            btMac = values[21] as String,
            driveSide = values[22] as String,
            gpsForwarding = values[23] as Boolean,
            clusterNavigation = values[24] as Boolean,
            audioSource = values[25] as String,
            callQuality = values[26] as String,
            overlaySettingsButton = values[27] as Boolean,
            overlayStatsButton = values[28] as Boolean,
            overlayPhoneSwitchButton = values[29] as Boolean,
            defaultPhoneMac = values[30] as String,
            syncAaTheme = values[31] as Boolean,
            hideAaClock = values[32] as Boolean,
            sendImuSensors = values[33] as Boolean,
            safeAreaTop = values[34] as Int,
            safeAreaBottom = values[35] as Int,
            safeAreaLeft = values[36] as Int,
            safeAreaRight = values[37] as Int,
            contentInsetTop = values[38] as Int,
            contentInsetBottom = values[39] as Int,
            contentInsetLeft = values[40] as Int,
            contentInsetRight = values[41] as Int,
        )
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5000),
        SettingsUiState()
    )

    fun updateBridgeHost(host: String) {
        viewModelScope.launch { preferences.setBridgeHost(host) }
    }

    fun updateBridgePort(port: Int) {
        viewModelScope.launch { preferences.setBridgePort(port) }
    }

    fun updateVideoCodec(codec: String) {
        viewModelScope.launch { preferences.setVideoCodec(codec) }
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

    fun updateRemoteDiagnosticsEnabled(enabled: Boolean) {
        viewModelScope.launch { preferences.setRemoteDiagnosticsEnabled(enabled) }
    }

    fun updateRemoteDiagnosticsMinLevel(level: String) {
        viewModelScope.launch { preferences.setRemoteDiagnosticsMinLevel(level) }
    }

    fun updateAaResolution(resolution: String) {
        viewModelScope.launch { preferences.setAaResolution(resolution) }
    }

    fun updateAaDpi(dpi: Int) {
        viewModelScope.launch { preferences.setAaDpi(dpi) }
    }

    fun updateAaWidthMargin(margin: Int) {
        viewModelScope.launch { preferences.setAaWidthMargin(margin) }
    }

    fun updateAaHeightMargin(margin: Int) {
        viewModelScope.launch { preferences.setAaHeightMargin(margin) }
    }

    fun updateAaPixelAspect(value: Int) {
        viewModelScope.launch { preferences.setAaPixelAspect(value) }
    }

    fun updateVideoScalingMode(mode: String) {
        viewModelScope.launch { preferences.setVideoScalingMode(mode) }
    }

    fun updatePhoneMode(mode: String) {
        viewModelScope.launch { preferences.setPhoneMode(mode) }
    }

    fun updateWifiBand(band: String) {
        viewModelScope.launch { preferences.setWifiBand(band) }
    }

    fun updateWifiCountry(country: String) {
        viewModelScope.launch { preferences.setWifiCountry(country) }
    }

    fun updateWifiSsid(ssid: String) {
        viewModelScope.launch { preferences.setWifiSsid(ssid) }
    }

    fun updateWifiPassword(password: String) {
        viewModelScope.launch { preferences.setWifiPassword(password) }
    }

    fun updateHeadUnitName(name: String) {
        viewModelScope.launch { preferences.setHeadUnitName(name) }
    }

    fun updateBtMac(mac: String) {
        viewModelScope.launch { preferences.setBtMac(mac) }
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

    fun updateAudioSource(source: String) {
        viewModelScope.launch { preferences.setAudioSource(source) }
    }

    fun updateCallQuality(quality: String) {
        viewModelScope.launch { preferences.setCallQuality(quality) }
    }

    fun updateOverlaySettingsButton(visible: Boolean) {
        viewModelScope.launch { preferences.setOverlaySettingsButton(visible) }
    }

    fun updateOverlayStatsButton(visible: Boolean) {
        viewModelScope.launch { preferences.setOverlayStatsButton(visible) }
    }

    fun updateOverlayPhoneSwitchButton(visible: Boolean) {
        viewModelScope.launch { preferences.setOverlayPhoneSwitchButton(visible) }
    }

    fun updateDefaultPhoneMac(mac: String) {
        viewModelScope.launch { preferences.setDefaultPhoneMac(mac) }
    }

    fun requestPairedPhones() {
        _phonesLoading.value = true
        viewModelScope.launch {
            ConfigUpdateSender.sendControlMessage(ControlMessage.ListPairedPhones)
        }
    }

    fun onPairedPhonesReceived(phones: List<ControlMessage.PairedPhone>) {
        _pairedPhones.value = phones
        _phonesLoading.value = false
    }

    fun switchPhone(mac: String) {
        viewModelScope.launch {
            ConfigUpdateSender.sendControlMessage(ControlMessage.SwitchPhone(mac))
        }
    }

    fun forgetPhone(mac: String) {
        viewModelScope.launch {
            ConfigUpdateSender.sendControlMessage(ControlMessage.ForgetPhone(mac))
            // Remove from local list immediately for responsive UI
            _pairedPhones.value = _pairedPhones.value.filter { it.mac != mac }
        }
    }

    fun updateSyncAaTheme(enabled: Boolean) {
        viewModelScope.launch { preferences.setSyncAaTheme(enabled) }
    }

    fun updateHideAaClock(enabled: Boolean) {
        viewModelScope.launch { preferences.setHideAaClock(enabled) }
    }

    fun updateSendImuSensors(enabled: Boolean) {
        viewModelScope.launch { preferences.setSendImuSensors(enabled) }
    }

    fun startDiscovery() {
        bridgeDiscovery.startDiscovery()
    }

    fun stopDiscovery() {
        bridgeDiscovery.stopDiscovery()
    }

    fun selectBridge(bridge: DiscoveredBridge) {
        viewModelScope.launch {
            preferences.setBridgeHost(bridge.host)
            preferences.setBridgePort(bridge.port)
        }
        bridgeDiscovery.stopDiscovery()
    }

    fun scanNetworkInterfaces() {
        viewModelScope.launch(Dispatchers.IO) {
            interfaceScanner.scan()
        }
    }

    fun selectNetworkInterface(interfaceName: String) {
        viewModelScope.launch { preferences.setNetworkInterface(interfaceName) }
    }

    fun updateSafeAreaInsets(top: Int, bottom: Int, left: Int, right: Int) {
        viewModelScope.launch {
            preferences.setSafeAreaTop(top)
            preferences.setSafeAreaBottom(bottom)
            preferences.setSafeAreaLeft(left)
            preferences.setSafeAreaRight(right)
        }
    }

    fun updateContentInsets(top: Int, bottom: Int, left: Int, right: Int) {
        viewModelScope.launch {
            preferences.setContentInsetTop(top)
            preferences.setContentInsetBottom(bottom)
            preferences.setContentInsetLeft(left)
            preferences.setContentInsetRight(right)
        }
    }

    override fun onCleared() {
        super.onCleared()
        bridgeDiscovery.stopDiscovery()
    }

    /**
     * Send the full config snapshot to the bridge, then restart bridge services.
     * All settings changes are cached locally in DataStore until this is called.
     * The bridge saves config to env, then restarts itself (and optionally WiFi/BT).
     * The phone will reconnect and renegotiate (e.g., new codec, resolution).
     */
    fun saveAndRestart(restartWireless: Boolean = true, restartBluetooth: Boolean = false) {
        viewModelScope.launch {
            // Send full config snapshot — bridge compares and applies diffs
            val config = preferences.getBridgeConfigSnapshot()
            if (config.isNotEmpty()) {
                ConfigUpdateSender.sendConfigUpdate(config)
            }
            ConfigUpdateSender.sendRestart(
                restartWireless = restartWireless,
                restartBluetooth = restartBluetooth,
            )
        }
    }
}
