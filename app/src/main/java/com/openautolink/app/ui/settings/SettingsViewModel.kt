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
    // Custom viewport
    val customViewportWidth: Int = AppPreferences.DEFAULT_CUSTOM_VIEWPORT_WIDTH,
    val customViewportHeight: Int = AppPreferences.DEFAULT_CUSTOM_VIEWPORT_HEIGHT,
    val viewportAspectRatioLocked: Boolean = AppPreferences.DEFAULT_VIEWPORT_ASPECT_RATIO_LOCKED,
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
        preferences.customViewportWidth,
        preferences.customViewportHeight,
        preferences.viewportAspectRatioLocked,
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
            phoneMode = values[11] as String,
            wifiBand = values[12] as String,
            wifiCountry = values[13] as String,
            wifiSsid = values[14] as String,
            wifiPassword = values[15] as String,
            headUnitName = values[16] as String,
            btMac = values[17] as String,
            driveSide = values[18] as String,
            gpsForwarding = values[19] as Boolean,
            clusterNavigation = values[20] as Boolean,
            audioSource = values[21] as String,
            callQuality = values[22] as String,
            overlaySettingsButton = values[23] as Boolean,
            overlayStatsButton = values[24] as Boolean,
            overlayPhoneSwitchButton = values[25] as Boolean,
            defaultPhoneMac = values[26] as String,
            syncAaTheme = values[27] as Boolean,
            hideAaClock = values[28] as Boolean,
            sendImuSensors = values[29] as Boolean,
            customViewportWidth = values[30] as Int,
            customViewportHeight = values[31] as Int,
            viewportAspectRatioLocked = values[32] as Boolean,
            safeAreaTop = values[33] as Int,
            safeAreaBottom = values[34] as Int,
            safeAreaLeft = values[35] as Int,
            safeAreaRight = values[36] as Int,
            contentInsetTop = values[37] as Int,
            contentInsetBottom = values[38] as Int,
            contentInsetLeft = values[39] as Int,
            contentInsetRight = values[40] as Int,
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
        viewModelScope.launch {
            preferences.setVideoCodec(codec)
            sendBridgeConfig("video_codec" to codec)
        }
    }

    fun updateVideoFps(fps: Int) {
        viewModelScope.launch {
            preferences.setVideoFps(fps)
            sendBridgeConfig("video_fps" to fps.toString())
        }
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
        viewModelScope.launch {
            preferences.setAaResolution(resolution)
            sendBridgeConfig("aa_resolution" to resolution)
        }
    }

    fun updateAaDpi(dpi: Int) {
        viewModelScope.launch {
            preferences.setAaDpi(dpi)
            sendBridgeConfig("aa_dpi" to dpi.toString())
        }
    }

    fun updatePhoneMode(mode: String) {
        viewModelScope.launch {
            preferences.setPhoneMode(mode)
            sendBridgeConfig("phone_mode" to mode)
        }
    }

    fun updateWifiBand(band: String) {
        viewModelScope.launch {
            preferences.setWifiBand(band)
            sendBridgeConfig("wifi_band" to band)
        }
    }

    fun updateWifiCountry(country: String) {
        viewModelScope.launch {
            preferences.setWifiCountry(country)
            sendBridgeConfig("wifi_country" to country)
        }
    }

    fun updateWifiSsid(ssid: String) {
        viewModelScope.launch {
            preferences.setWifiSsid(ssid)
            sendBridgeConfig("wifi_ssid" to ssid)
        }
    }

    fun updateWifiPassword(password: String) {
        viewModelScope.launch {
            preferences.setWifiPassword(password)
            sendBridgeConfig("wifi_password" to password)
        }
    }

    fun updateHeadUnitName(name: String) {
        viewModelScope.launch {
            preferences.setHeadUnitName(name)
            sendBridgeConfig("head_unit_name" to name)
        }
    }

    fun updateBtMac(mac: String) {
        viewModelScope.launch {
            preferences.setBtMac(mac)
            sendBridgeConfig("bt_mac" to mac)
        }
    }

    fun updateDriveSide(side: String) {
        viewModelScope.launch {
            preferences.setDriveSide(side)
            sendBridgeConfig("drive_side" to side)
        }
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

    fun updateCustomViewportWidth(width: Int) {
        viewModelScope.launch { preferences.setCustomViewportWidth(width) }
    }

    fun updateCustomViewportHeight(height: Int) {
        viewModelScope.launch { preferences.setCustomViewportHeight(height) }
    }

    fun updateViewportAspectRatioLocked(locked: Boolean) {
        viewModelScope.launch { preferences.setViewportAspectRatioLocked(locked) }
    }

    fun updateCustomViewport(width: Int, height: Int) {
        viewModelScope.launch {
            preferences.setCustomViewportWidth(width)
            preferences.setCustomViewportHeight(height)
        }
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
            sendBridgeConfig(
                "aa_stable_insets" to "$top,$bottom,$left,$right"
            )
        }
    }

    fun updateContentInsets(top: Int, bottom: Int, left: Int, right: Int) {
        viewModelScope.launch {
            preferences.setContentInsetTop(top)
            preferences.setContentInsetBottom(bottom)
            preferences.setContentInsetLeft(left)
            preferences.setContentInsetRight(right)
            sendBridgeConfig(
                "aa_content_insets" to "$top,$bottom,$left,$right"
            )
        }
    }

    override fun onCleared() {
        super.onCleared()
        bridgeDiscovery.stopDiscovery()
    }

    private suspend fun sendBridgeConfig(vararg pairs: Pair<String, String>) {
        ConfigUpdateSender.sendConfigUpdate(pairs.toMap())
    }

    /**
     * Send all pending config changes to the bridge, then restart bridge services.
     * The bridge saves config to env, then restarts itself (and optionally WiFi/BT).
     * The phone will reconnect and renegotiate (e.g., new codec, resolution).
     */
    fun saveAndRestart(restartWireless: Boolean = true, restartBluetooth: Boolean = false) {
        viewModelScope.launch {
            ConfigUpdateSender.sendRestart(
                restartWireless = restartWireless,
                restartBluetooth = restartBluetooth,
            )
        }
    }
}
