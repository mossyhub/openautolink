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
import com.openautolink.app.update.AppInstaller
import com.openautolink.app.update.UpdateChecker
import com.openautolink.app.update.UpdateManifest
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
    val selfUpdateEnabled: String = AppPreferences.DEFAULT_SELF_UPDATE_ENABLED,
    val updateManifestUrl: String = AppPreferences.DEFAULT_UPDATE_MANIFEST_URL,
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
)

sealed class UpdateStatus {
    data object Idle : UpdateStatus()
    data object Checking : UpdateStatus()
    data class UpdateAvailable(val manifest: UpdateManifest) : UpdateStatus()
    data object UpToDate : UpdateStatus()
    data class Downloading(val progress: Float) : UpdateStatus()
    data object Installing : UpdateStatus()
    data class Error(val message: String) : UpdateStatus()
}

class SettingsViewModel(application: Application) : AndroidViewModel(application) {

    private val preferences = AppPreferences.getInstance(application)
    private val updateChecker = UpdateChecker(application)
    private val appInstaller = AppInstaller(application)
    private val bridgeDiscovery = BridgeDiscovery(application)
    private val interfaceScanner = NetworkInterfaceScanner(application)

    val updateStatus = MutableStateFlow<UpdateStatus>(UpdateStatus.Idle)
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
        preferences.selfUpdateEnabled,
        preferences.updateManifestUrl,
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
    ) { values: Array<Any> ->
        SettingsUiState(
            bridgeHost = values[0] as String,
            bridgePort = values[1] as Int,
            videoCodec = values[2] as String,
            videoFps = values[3] as Int,
            displayMode = values[4] as String,
            micSource = values[5] as String,
            selfUpdateEnabled = values[6] as String,
            updateManifestUrl = values[7] as String,
            networkInterface = values[8] as String,
            remoteDiagnosticsEnabled = values[9] as Boolean,
            remoteDiagnosticsMinLevel = values[10] as String,
            aaResolution = values[11] as String,
            aaDpi = values[12] as Int,
            phoneMode = values[13] as String,
            wifiBand = values[14] as String,
            wifiCountry = values[15] as String,
            wifiSsid = values[16] as String,
            wifiPassword = values[17] as String,
            headUnitName = values[18] as String,
            btMac = values[19] as String,
            driveSide = values[20] as String,
            gpsForwarding = values[21] as Boolean,
            clusterNavigation = values[22] as Boolean,
            audioSource = values[23] as String,
            callQuality = values[24] as String,
            overlaySettingsButton = values[25] as Boolean,
            overlayStatsButton = values[26] as Boolean,
            overlayPhoneSwitchButton = values[27] as Boolean,
            defaultPhoneMac = values[28] as String,
            syncAaTheme = values[29] as Boolean,
            hideAaClock = values[30] as Boolean,
            sendImuSensors = values[31] as Boolean,
            customViewportWidth = values[32] as Int,
            customViewportHeight = values[33] as Int,
            viewportAspectRatioLocked = values[34] as Boolean,
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

    fun updateSelfUpdateEnabled(enabled: String) {
        viewModelScope.launch { preferences.setSelfUpdateEnabled(enabled) }
    }

    fun updateManifestUrl(url: String) {
        viewModelScope.launch { preferences.setUpdateManifestUrl(url) }
    }

    fun checkForUpdate(manifestUrl: String) {
        viewModelScope.launch(Dispatchers.IO) {
            updateStatus.value = UpdateStatus.Checking
            when (val result = updateChecker.checkForUpdate(manifestUrl)) {
                is UpdateChecker.CheckResult.UpdateAvailable -> {
                    updateStatus.value = UpdateStatus.UpdateAvailable(result.manifest)
                }
                is UpdateChecker.CheckResult.UpToDate -> {
                    updateStatus.value = UpdateStatus.UpToDate
                }
                is UpdateChecker.CheckResult.Error -> {
                    updateStatus.value = UpdateStatus.Error(result.message)
                }
            }
        }
    }

    fun downloadAndInstall(apkUrl: String) {
        viewModelScope.launch(Dispatchers.IO) {
            updateStatus.value = UpdateStatus.Downloading(0f)

            when (val result = updateChecker.downloadApk(apkUrl) { downloaded, total ->
                val progress = if (total > 0) downloaded.toFloat() / total else 0f
                updateStatus.value = UpdateStatus.Downloading(progress)
            }) {
                is UpdateChecker.DownloadResult.Success -> {
                    updateStatus.value = UpdateStatus.Installing
                    when (val installResult = appInstaller.installApk(result.apkFile)) {
                        is AppInstaller.InstallResult.SessionCreated -> {
                            // System takes over with install prompt
                            updateStatus.value = UpdateStatus.Idle
                        }
                        is AppInstaller.InstallResult.Error -> {
                            updateStatus.value = UpdateStatus.Error(installResult.message)
                        }
                    }
                }
                is UpdateChecker.DownloadResult.Error -> {
                    updateStatus.value = UpdateStatus.Error(result.message)
                }
            }
        }
    }

    fun dismissUpdateStatus() {
        updateStatus.value = UpdateStatus.Idle
    }

    fun canInstallPackages(): Boolean = appInstaller.canInstallPackages()

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
