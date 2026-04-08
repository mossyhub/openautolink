package com.openautolink.app.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

/**
 * App preferences backed by DataStore.
 */
class AppPreferences private constructor(private val dataStore: DataStore<Preferences>) {

    companion object {
        @Volatile
        private var instance: AppPreferences? = null

        fun getInstance(context: Context): AppPreferences {
            return instance ?: synchronized(this) {
                instance ?: AppPreferences(context.applicationContext.dataStore).also {
                    instance = it
                }
            }
        }

        val BRIDGE_HOST = stringPreferencesKey("bridge_host")
        val BRIDGE_PORT = intPreferencesKey("bridge_port")
        val VIDEO_CODEC = stringPreferencesKey("video_codec")
        val VIDEO_FPS = intPreferencesKey("video_fps")
        val DISPLAY_MODE = stringPreferencesKey("display_mode")
        val MIC_SOURCE = stringPreferencesKey("mic_source")
        val NETWORK_INTERFACE = stringPreferencesKey("network_interface")
        val REMOTE_DIAGNOSTICS_ENABLED = booleanPreferencesKey("remote_diagnostics_enabled")
        val REMOTE_DIAGNOSTICS_MIN_LEVEL = stringPreferencesKey("remote_diagnostics_min_level")
        val SYNC_AA_THEME = booleanPreferencesKey("sync_aa_theme")
        val HIDE_AA_CLOCK = booleanPreferencesKey("hide_aa_clock")
        val HIDE_PHONE_SIGNAL = booleanPreferencesKey("hide_phone_signal")
        val HIDE_BATTERY_LEVEL = booleanPreferencesKey("hide_battery_level")
        val SEND_IMU_SENSORS = booleanPreferencesKey("send_imu_sensors")
        val DISTANCE_UNITS = stringPreferencesKey("distance_units") // "auto", "metric", or "imperial"

        // Bridge config — AA stream settings (sent to bridge via config_update)
        val AA_RESOLUTION = stringPreferencesKey("aa_resolution")
        val AA_DPI = intPreferencesKey("aa_dpi")
        val AA_WIDTH_MARGIN = intPreferencesKey("aa_width_margin")
        val AA_HEIGHT_MARGIN = intPreferencesKey("aa_height_margin")
        val AA_PIXEL_ASPECT = intPreferencesKey("aa_pixel_aspect")
        val VIDEO_SCALING_MODE = stringPreferencesKey("video_scaling_mode")
        val PHONE_MODE = stringPreferencesKey("phone_mode")
        val WIFI_BAND = stringPreferencesKey("wifi_band")
        val WIFI_COUNTRY = stringPreferencesKey("wifi_country")
        val WIFI_SSID = stringPreferencesKey("wifi_ssid")
        val WIFI_PASSWORD = stringPreferencesKey("wifi_password")
        val HEAD_UNIT_NAME = stringPreferencesKey("head_unit_name")
        val BT_MAC = stringPreferencesKey("bt_mac")

        // App-side settings
        val DRIVE_SIDE = stringPreferencesKey("drive_side")
        val GPS_FORWARDING = booleanPreferencesKey("gps_forwarding")
        val CLUSTER_NAVIGATION = booleanPreferencesKey("cluster_navigation")
        val AUDIO_SOURCE = stringPreferencesKey("audio_source")
        val CALL_QUALITY = stringPreferencesKey("call_quality")
        val OVERLAY_SETTINGS_BUTTON = booleanPreferencesKey("overlay_settings_button")
        val OVERLAY_STATS_BUTTON = booleanPreferencesKey("overlay_stats_button")
        val OVERLAY_PHONE_SWITCH_BUTTON = booleanPreferencesKey("overlay_phone_switch_button")
        val DEFAULT_PHONE_MAC = stringPreferencesKey("default_phone_mac")
        val BRIDGE_AUTO_UPDATE = booleanPreferencesKey("bridge_auto_update")
        val BRIDGE_AUTO_APPLY = booleanPreferencesKey("bridge_auto_apply")
        val GITHUB_REPO_OWNER = stringPreferencesKey("github_repo_owner")
        val GITHUB_REPO_NAME = stringPreferencesKey("github_repo_name")

        // AA safe area (stable) insets — maps render, UI stays inside
        val SAFE_AREA_TOP = intPreferencesKey("safe_area_top")
        val SAFE_AREA_BOTTOM = intPreferencesKey("safe_area_bottom")
        val SAFE_AREA_LEFT = intPreferencesKey("safe_area_left")
        val SAFE_AREA_RIGHT = intPreferencesKey("safe_area_right")

        // AA content insets — hard cutoff, nothing renders outside
        val CONTENT_INSET_TOP = intPreferencesKey("content_inset_top")
        val CONTENT_INSET_BOTTOM = intPreferencesKey("content_inset_bottom")
        val CONTENT_INSET_LEFT = intPreferencesKey("content_inset_left")
        val CONTENT_INSET_RIGHT = intPreferencesKey("content_inset_right")

        // Custom viewport
        val CUSTOM_VIEWPORT_WIDTH = intPreferencesKey("custom_viewport_width")
        val CUSTOM_VIEWPORT_HEIGHT = intPreferencesKey("custom_viewport_height")
        val VIEWPORT_ASPECT_RATIO_LOCKED = booleanPreferencesKey("viewport_aspect_ratio_locked")

        const val DEFAULT_BRIDGE_HOST = "192.168.222.222"
        const val DEFAULT_BRIDGE_PORT = 5288
        const val DEFAULT_VIDEO_CODEC = "h264"
        const val DEFAULT_VIDEO_FPS = 60
        const val DEFAULT_DISPLAY_MODE = "system_ui_visible"
        const val DEFAULT_MIC_SOURCE = "car"
        const val DEFAULT_NETWORK_INTERFACE = "" // empty = auto-select first available
        const val DEFAULT_REMOTE_DIAGNOSTICS_ENABLED = false
        const val DEFAULT_REMOTE_DIAGNOSTICS_MIN_LEVEL = "INFO"
        const val DEFAULT_SYNC_AA_THEME = true
        const val DEFAULT_HIDE_AA_CLOCK = false
        const val DEFAULT_HIDE_PHONE_SIGNAL = false
        const val DEFAULT_HIDE_BATTERY_LEVEL = false
        const val DEFAULT_SEND_IMU_SENSORS = true
        const val DEFAULT_DISTANCE_UNITS = "auto" // "auto" = locale-based, "metric", "imperial"
        const val DEFAULT_AA_RESOLUTION = "1080p"
        const val DEFAULT_AA_DPI = 160
        const val DEFAULT_AA_WIDTH_MARGIN = 0 // 0 = auto from display AR
        const val DEFAULT_AA_HEIGHT_MARGIN = 0 // 0 = auto from display AR
        const val DEFAULT_AA_PIXEL_ASPECT = 0 // 0 = default (square pixels, 10000)
        const val DEFAULT_VIDEO_SCALING_MODE = "letterbox" // "letterbox" or "crop"
        const val DEFAULT_PHONE_MODE = "wireless"
        const val DEFAULT_WIFI_BAND = "5ghz"
        const val DEFAULT_WIFI_COUNTRY = "US"
        const val DEFAULT_WIFI_SSID = ""
        const val DEFAULT_WIFI_PASSWORD = ""
        const val DEFAULT_HEAD_UNIT_NAME = "OpenAutoLink"
        const val DEFAULT_BT_MAC = ""
        const val DEFAULT_DRIVE_SIDE = "left"
        const val DEFAULT_GPS_FORWARDING = true
        const val DEFAULT_CLUSTER_NAVIGATION = true
        const val DEFAULT_AUDIO_SOURCE = "bridge"
        const val DEFAULT_CALL_QUALITY = "hd"
        const val DEFAULT_OVERLAY_SETTINGS_BUTTON = true
        const val DEFAULT_OVERLAY_STATS_BUTTON = true
        const val DEFAULT_OVERLAY_PHONE_SWITCH_BUTTON = true
        const val DEFAULT_DEFAULT_PHONE_MAC = ""
        const val DEFAULT_BRIDGE_AUTO_UPDATE = true
        const val DEFAULT_BRIDGE_AUTO_APPLY = true
        const val DEFAULT_GITHUB_REPO_OWNER = "mossyhub"
        const val DEFAULT_GITHUB_REPO_NAME = "openautolink"
        const val DEFAULT_SAFE_AREA_TOP = 0
        const val DEFAULT_SAFE_AREA_BOTTOM = 0
        const val DEFAULT_SAFE_AREA_LEFT = 0
        const val DEFAULT_SAFE_AREA_RIGHT = 0
        const val DEFAULT_CONTENT_INSET_TOP = 0
        const val DEFAULT_CONTENT_INSET_BOTTOM = 0
        const val DEFAULT_CONTENT_INSET_LEFT = 0
        const val DEFAULT_CONTENT_INSET_RIGHT = 0
        const val DEFAULT_CUSTOM_VIEWPORT_WIDTH = 0 // 0 = use full usable width
        const val DEFAULT_CUSTOM_VIEWPORT_HEIGHT = 0 // 0 = use full usable height
        const val DEFAULT_VIEWPORT_ASPECT_RATIO_LOCKED = true
    }

    val bridgeHost: Flow<String> = dataStore.data.map { prefs ->
        prefs[BRIDGE_HOST] ?: DEFAULT_BRIDGE_HOST
    }

    val bridgePort: Flow<Int> = dataStore.data.map { prefs ->
        prefs[BRIDGE_PORT] ?: DEFAULT_BRIDGE_PORT
    }

    val videoCodec: Flow<String> = dataStore.data.map { prefs ->
        prefs[VIDEO_CODEC] ?: DEFAULT_VIDEO_CODEC
    }

    val videoFps: Flow<Int> = dataStore.data.map { prefs ->
        prefs[VIDEO_FPS] ?: DEFAULT_VIDEO_FPS
    }

    val displayMode: Flow<String> = dataStore.data.map { prefs ->
        prefs[DISPLAY_MODE] ?: DEFAULT_DISPLAY_MODE
    }

    val micSource: Flow<String> = dataStore.data.map { prefs ->
        prefs[MIC_SOURCE] ?: DEFAULT_MIC_SOURCE
    }

    val networkInterface: Flow<String> = dataStore.data.map { prefs ->
        prefs[NETWORK_INTERFACE] ?: DEFAULT_NETWORK_INTERFACE
    }

    val remoteDiagnosticsEnabled: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[REMOTE_DIAGNOSTICS_ENABLED] ?: DEFAULT_REMOTE_DIAGNOSTICS_ENABLED
    }

    val remoteDiagnosticsMinLevel: Flow<String> = dataStore.data.map { prefs ->
        prefs[REMOTE_DIAGNOSTICS_MIN_LEVEL] ?: DEFAULT_REMOTE_DIAGNOSTICS_MIN_LEVEL
    }

    val syncAaTheme: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[SYNC_AA_THEME] ?: DEFAULT_SYNC_AA_THEME
    }

    val hideAaClock: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[HIDE_AA_CLOCK] ?: DEFAULT_HIDE_AA_CLOCK
    }

    val hidePhoneSignal: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[HIDE_PHONE_SIGNAL] ?: DEFAULT_HIDE_PHONE_SIGNAL
    }

    val hideBatteryLevel: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[HIDE_BATTERY_LEVEL] ?: DEFAULT_HIDE_BATTERY_LEVEL
    }

    val sendImuSensors: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[SEND_IMU_SENSORS] ?: DEFAULT_SEND_IMU_SENSORS
    }

    val distanceUnits: Flow<String> = dataStore.data.map { prefs ->
        prefs[DISTANCE_UNITS] ?: DEFAULT_DISTANCE_UNITS
    }

    val aaResolution: Flow<String> = dataStore.data.map { prefs ->
        prefs[AA_RESOLUTION] ?: DEFAULT_AA_RESOLUTION
    }

    val aaDpi: Flow<Int> = dataStore.data.map { prefs ->
        prefs[AA_DPI] ?: DEFAULT_AA_DPI
    }

    val aaWidthMargin: Flow<Int> = dataStore.data.map { prefs ->
        prefs[AA_WIDTH_MARGIN] ?: DEFAULT_AA_WIDTH_MARGIN
    }

    val aaHeightMargin: Flow<Int> = dataStore.data.map { prefs ->
        prefs[AA_HEIGHT_MARGIN] ?: DEFAULT_AA_HEIGHT_MARGIN
    }

    val aaPixelAspect: Flow<Int> = dataStore.data.map { prefs ->
        prefs[AA_PIXEL_ASPECT] ?: DEFAULT_AA_PIXEL_ASPECT
    }

    val videoScalingMode: Flow<String> = dataStore.data.map { prefs ->
        prefs[VIDEO_SCALING_MODE] ?: DEFAULT_VIDEO_SCALING_MODE
    }

    val phoneMode: Flow<String> = dataStore.data.map { prefs ->
        prefs[PHONE_MODE] ?: DEFAULT_PHONE_MODE
    }

    val wifiBand: Flow<String> = dataStore.data.map { prefs ->
        prefs[WIFI_BAND] ?: DEFAULT_WIFI_BAND
    }

    val wifiCountry: Flow<String> = dataStore.data.map { prefs ->
        prefs[WIFI_COUNTRY] ?: DEFAULT_WIFI_COUNTRY
    }

    val wifiSsid: Flow<String> = dataStore.data.map { prefs ->
        prefs[WIFI_SSID] ?: DEFAULT_WIFI_SSID
    }

    val wifiPassword: Flow<String> = dataStore.data.map { prefs ->
        prefs[WIFI_PASSWORD] ?: DEFAULT_WIFI_PASSWORD
    }

    val headUnitName: Flow<String> = dataStore.data.map { prefs ->
        prefs[HEAD_UNIT_NAME] ?: DEFAULT_HEAD_UNIT_NAME
    }

    val btMac: Flow<String> = dataStore.data.map { prefs ->
        prefs[BT_MAC] ?: DEFAULT_BT_MAC
    }

    val driveSide: Flow<String> = dataStore.data.map { prefs ->
        prefs[DRIVE_SIDE] ?: DEFAULT_DRIVE_SIDE
    }

    val gpsForwarding: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[GPS_FORWARDING] ?: DEFAULT_GPS_FORWARDING
    }

    val clusterNavigation: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[CLUSTER_NAVIGATION] ?: DEFAULT_CLUSTER_NAVIGATION
    }

    val audioSource: Flow<String> = dataStore.data.map { prefs ->
        prefs[AUDIO_SOURCE] ?: DEFAULT_AUDIO_SOURCE
    }

    val callQuality: Flow<String> = dataStore.data.map { prefs ->
        prefs[CALL_QUALITY] ?: DEFAULT_CALL_QUALITY
    }

    val overlaySettingsButton: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[OVERLAY_SETTINGS_BUTTON] ?: DEFAULT_OVERLAY_SETTINGS_BUTTON
    }

    val overlayStatsButton: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[OVERLAY_STATS_BUTTON] ?: DEFAULT_OVERLAY_STATS_BUTTON
    }

    val overlayPhoneSwitchButton: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[OVERLAY_PHONE_SWITCH_BUTTON] ?: DEFAULT_OVERLAY_PHONE_SWITCH_BUTTON
    }

    val defaultPhoneMac: Flow<String> = dataStore.data.map { prefs ->
        prefs[DEFAULT_PHONE_MAC] ?: DEFAULT_DEFAULT_PHONE_MAC
    }

    val bridgeAutoUpdate: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[BRIDGE_AUTO_UPDATE] ?: DEFAULT_BRIDGE_AUTO_UPDATE
    }

    val bridgeAutoApply: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[BRIDGE_AUTO_APPLY] ?: DEFAULT_BRIDGE_AUTO_APPLY
    }

    val githubRepoOwner: Flow<String> = dataStore.data.map { prefs ->
        prefs[GITHUB_REPO_OWNER] ?: DEFAULT_GITHUB_REPO_OWNER
    }

    val githubRepoName: Flow<String> = dataStore.data.map { prefs ->
        prefs[GITHUB_REPO_NAME] ?: DEFAULT_GITHUB_REPO_NAME
    }

    val safeAreaTop: Flow<Int> = dataStore.data.map { prefs ->
        prefs[SAFE_AREA_TOP] ?: DEFAULT_SAFE_AREA_TOP
    }

    val safeAreaBottom: Flow<Int> = dataStore.data.map { prefs ->
        prefs[SAFE_AREA_BOTTOM] ?: DEFAULT_SAFE_AREA_BOTTOM
    }

    val safeAreaLeft: Flow<Int> = dataStore.data.map { prefs ->
        prefs[SAFE_AREA_LEFT] ?: DEFAULT_SAFE_AREA_LEFT
    }

    val safeAreaRight: Flow<Int> = dataStore.data.map { prefs ->
        prefs[SAFE_AREA_RIGHT] ?: DEFAULT_SAFE_AREA_RIGHT
    }

    val contentInsetTop: Flow<Int> = dataStore.data.map { prefs ->
        prefs[CONTENT_INSET_TOP] ?: DEFAULT_CONTENT_INSET_TOP
    }

    val contentInsetBottom: Flow<Int> = dataStore.data.map { prefs ->
        prefs[CONTENT_INSET_BOTTOM] ?: DEFAULT_CONTENT_INSET_BOTTOM
    }

    val contentInsetLeft: Flow<Int> = dataStore.data.map { prefs ->
        prefs[CONTENT_INSET_LEFT] ?: DEFAULT_CONTENT_INSET_LEFT
    }

    val contentInsetRight: Flow<Int> = dataStore.data.map { prefs ->
        prefs[CONTENT_INSET_RIGHT] ?: DEFAULT_CONTENT_INSET_RIGHT
    }

    val customViewportWidth: Flow<Int> = dataStore.data.map { prefs ->
        prefs[CUSTOM_VIEWPORT_WIDTH] ?: DEFAULT_CUSTOM_VIEWPORT_WIDTH
    }

    val customViewportHeight: Flow<Int> = dataStore.data.map { prefs ->
        prefs[CUSTOM_VIEWPORT_HEIGHT] ?: DEFAULT_CUSTOM_VIEWPORT_HEIGHT
    }

    val viewportAspectRatioLocked: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[VIEWPORT_ASPECT_RATIO_LOCKED] ?: DEFAULT_VIEWPORT_ASPECT_RATIO_LOCKED
    }

    suspend fun setBridgeHost(host: String) {
        dataStore.edit { it[BRIDGE_HOST] = host }
    }

    suspend fun setBridgePort(port: Int) {
        dataStore.edit { it[BRIDGE_PORT] = port }
    }

    suspend fun setVideoCodec(codec: String) {
        dataStore.edit { it[VIDEO_CODEC] = codec }
    }

    suspend fun setVideoFps(fps: Int) {
        dataStore.edit { it[VIDEO_FPS] = fps }
    }

    suspend fun setDisplayMode(mode: String) {
        dataStore.edit { it[DISPLAY_MODE] = mode }
    }

    suspend fun setMicSource(source: String) {
        dataStore.edit { it[MIC_SOURCE] = source }
    }

    suspend fun setNetworkInterface(interfaceName: String) {
        dataStore.edit { it[NETWORK_INTERFACE] = interfaceName }
    }

    suspend fun setRemoteDiagnosticsEnabled(enabled: Boolean) {
        dataStore.edit { it[REMOTE_DIAGNOSTICS_ENABLED] = enabled }
    }

    suspend fun setRemoteDiagnosticsMinLevel(level: String) {
        dataStore.edit { it[REMOTE_DIAGNOSTICS_MIN_LEVEL] = level }
    }

    suspend fun setSyncAaTheme(enabled: Boolean) {
        dataStore.edit { it[SYNC_AA_THEME] = enabled }
    }

    suspend fun setHideAaClock(enabled: Boolean) {
        dataStore.edit { it[HIDE_AA_CLOCK] = enabled }
    }

    suspend fun setHidePhoneSignal(enabled: Boolean) {
        dataStore.edit { it[HIDE_PHONE_SIGNAL] = enabled }
    }

    suspend fun setHideBatteryLevel(enabled: Boolean) {
        dataStore.edit { it[HIDE_BATTERY_LEVEL] = enabled }
    }

    suspend fun setSendImuSensors(enabled: Boolean) {
        dataStore.edit { it[SEND_IMU_SENSORS] = enabled }
    }

    suspend fun setDistanceUnits(units: String) {
        dataStore.edit { it[DISTANCE_UNITS] = units }
    }

    suspend fun setAaResolution(resolution: String) {
        dataStore.edit { it[AA_RESOLUTION] = resolution }
    }

    suspend fun setAaDpi(dpi: Int) {
        dataStore.edit { it[AA_DPI] = dpi }
    }

    suspend fun setAaWidthMargin(margin: Int) {
        dataStore.edit { it[AA_WIDTH_MARGIN] = margin }
    }

    suspend fun setAaHeightMargin(margin: Int) {
        dataStore.edit { it[AA_HEIGHT_MARGIN] = margin }
    }

    suspend fun setAaPixelAspect(value: Int) {
        dataStore.edit { it[AA_PIXEL_ASPECT] = value }
    }

    suspend fun setVideoScalingMode(mode: String) {
        dataStore.edit { it[VIDEO_SCALING_MODE] = mode }
    }

    suspend fun setPhoneMode(mode: String) {
        dataStore.edit { it[PHONE_MODE] = mode }
    }

    suspend fun setWifiBand(band: String) {
        dataStore.edit { it[WIFI_BAND] = band }
    }

    suspend fun setWifiCountry(country: String) {
        dataStore.edit { it[WIFI_COUNTRY] = country }
    }

    suspend fun setWifiSsid(ssid: String) {
        dataStore.edit { it[WIFI_SSID] = ssid }
    }

    suspend fun setWifiPassword(password: String) {
        dataStore.edit { it[WIFI_PASSWORD] = password }
    }

    suspend fun setHeadUnitName(name: String) {
        dataStore.edit { it[HEAD_UNIT_NAME] = name }
    }

    suspend fun setBtMac(mac: String) {
        dataStore.edit { it[BT_MAC] = mac }
    }

    suspend fun setDriveSide(side: String) {
        dataStore.edit { it[DRIVE_SIDE] = side }
    }

    suspend fun setGpsForwarding(enabled: Boolean) {
        dataStore.edit { it[GPS_FORWARDING] = enabled }
    }

    suspend fun setClusterNavigation(enabled: Boolean) {
        dataStore.edit { it[CLUSTER_NAVIGATION] = enabled }
    }

    suspend fun setAudioSource(source: String) {
        dataStore.edit { it[AUDIO_SOURCE] = source }
    }

    suspend fun setCallQuality(quality: String) {
        dataStore.edit { it[CALL_QUALITY] = quality }
    }

    suspend fun setOverlaySettingsButton(visible: Boolean) {
        dataStore.edit { it[OVERLAY_SETTINGS_BUTTON] = visible }
    }

    suspend fun setOverlayStatsButton(visible: Boolean) {
        dataStore.edit { it[OVERLAY_STATS_BUTTON] = visible }
    }

    suspend fun setOverlayPhoneSwitchButton(visible: Boolean) {
        dataStore.edit { it[OVERLAY_PHONE_SWITCH_BUTTON] = visible }
    }

    suspend fun setDefaultPhoneMac(mac: String) {
        dataStore.edit { it[DEFAULT_PHONE_MAC] = mac }
    }

    suspend fun setBridgeAutoUpdate(enabled: Boolean) {
        dataStore.edit { it[BRIDGE_AUTO_UPDATE] = enabled }
    }

    suspend fun setBridgeAutoApply(enabled: Boolean) {
        dataStore.edit { it[BRIDGE_AUTO_APPLY] = enabled }
    }

    suspend fun setSafeAreaTop(value: Int) {
        dataStore.edit { it[SAFE_AREA_TOP] = value }
    }

    suspend fun setSafeAreaBottom(value: Int) {
        dataStore.edit { it[SAFE_AREA_BOTTOM] = value }
    }

    suspend fun setSafeAreaLeft(value: Int) {
        dataStore.edit { it[SAFE_AREA_LEFT] = value }
    }

    suspend fun setSafeAreaRight(value: Int) {
        dataStore.edit { it[SAFE_AREA_RIGHT] = value }
    }

    suspend fun setContentInsetTop(value: Int) {
        dataStore.edit { it[CONTENT_INSET_TOP] = value }
    }

    suspend fun setContentInsetBottom(value: Int) {
        dataStore.edit { it[CONTENT_INSET_BOTTOM] = value }
    }

    suspend fun setContentInsetLeft(value: Int) {
        dataStore.edit { it[CONTENT_INSET_LEFT] = value }
    }

    suspend fun setContentInsetRight(value: Int) {
        dataStore.edit { it[CONTENT_INSET_RIGHT] = value }
    }

    suspend fun setCustomViewportWidth(width: Int) {
        dataStore.edit { it[CUSTOM_VIEWPORT_WIDTH] = width }
    }

    suspend fun setCustomViewportHeight(height: Int) {
        dataStore.edit { it[CUSTOM_VIEWPORT_HEIGHT] = height }
    }

    suspend fun setViewportAspectRatioLocked(locked: Boolean) {
        dataStore.edit { it[VIEWPORT_ASPECT_RATIO_LOCKED] = locked }
    }

    /**
     * Read all bridge-relevant preferences and return as a config map
     * suitable for sending as a config_update message on initial connection.
     */
    suspend fun getBridgeConfigSnapshot(): Map<String, String> {
        val prefs = dataStore.data.first()
        val config = mutableMapOf<String, String>()
        // Always include defaults so bridge uses app defaults even when prefs are unset
        // (e.g. after clearing app data). Without this, the bridge would keep using
        // whatever value was sent in the hello message (device DPI, not user's AA DPI).
        config["video_codec"] = prefs[VIDEO_CODEC] ?: DEFAULT_VIDEO_CODEC
        config["video_fps"] = (prefs[VIDEO_FPS] ?: DEFAULT_VIDEO_FPS).toString()
        config["aa_resolution"] = prefs[AA_RESOLUTION] ?: DEFAULT_AA_RESOLUTION
        config["aa_dpi"] = (prefs[AA_DPI] ?: DEFAULT_AA_DPI).toString()
        config["aa_width_margin"] = (prefs[AA_WIDTH_MARGIN] ?: DEFAULT_AA_WIDTH_MARGIN).toString()
        config["aa_height_margin"] = (prefs[AA_HEIGHT_MARGIN] ?: DEFAULT_AA_HEIGHT_MARGIN).toString()
        config["aa_pixel_aspect"] = (prefs[AA_PIXEL_ASPECT] ?: DEFAULT_AA_PIXEL_ASPECT).toString()
        config["drive_side"] = prefs[DRIVE_SIDE] ?: DEFAULT_DRIVE_SIDE
        config["head_unit_name"] = prefs[HEAD_UNIT_NAME] ?: DEFAULT_HEAD_UNIT_NAME
        val btMac = prefs[BT_MAC] ?: DEFAULT_BT_MAC
        if (btMac.isNotBlank()) config["bt_mac"] = btMac
        config["phone_mode"] = prefs[PHONE_MODE] ?: DEFAULT_PHONE_MODE
        config["wifi_band"] = prefs[WIFI_BAND] ?: DEFAULT_WIFI_BAND
        config["wifi_country"] = prefs[WIFI_COUNTRY] ?: DEFAULT_WIFI_COUNTRY
        val wifiSsid = prefs[WIFI_SSID] ?: DEFAULT_WIFI_SSID
        if (wifiSsid.isNotBlank()) config["wifi_ssid"] = wifiSsid
        val wifiPassword = prefs[WIFI_PASSWORD] ?: DEFAULT_WIFI_PASSWORD
        if (wifiPassword.isNotBlank()) config["wifi_password"] = wifiPassword
        // AA UI flags
        val hideClock = prefs[HIDE_AA_CLOCK] ?: DEFAULT_HIDE_AA_CLOCK
        config["hide_clock"] = hideClock.toString()
        val hidePhoneSignal = prefs[HIDE_PHONE_SIGNAL] ?: DEFAULT_HIDE_PHONE_SIGNAL
        config["hide_phone_signal"] = hidePhoneSignal.toString()
        val hideBatteryLevel = prefs[HIDE_BATTERY_LEVEL] ?: DEFAULT_HIDE_BATTERY_LEVEL
        config["hide_battery_level"] = hideBatteryLevel.toString()
        // AA insets
        val safeTop = prefs[SAFE_AREA_TOP] ?: DEFAULT_SAFE_AREA_TOP
        val safeBottom = prefs[SAFE_AREA_BOTTOM] ?: DEFAULT_SAFE_AREA_BOTTOM
        val safeLeft = prefs[SAFE_AREA_LEFT] ?: DEFAULT_SAFE_AREA_LEFT
        val safeRight = prefs[SAFE_AREA_RIGHT] ?: DEFAULT_SAFE_AREA_RIGHT
        config["aa_stable_insets"] = "$safeTop,$safeBottom,$safeLeft,$safeRight"
        val contentTop = prefs[CONTENT_INSET_TOP] ?: DEFAULT_CONTENT_INSET_TOP
        val contentBottom = prefs[CONTENT_INSET_BOTTOM] ?: DEFAULT_CONTENT_INSET_BOTTOM
        val contentLeft = prefs[CONTENT_INSET_LEFT] ?: DEFAULT_CONTENT_INSET_LEFT
        val contentRight = prefs[CONTENT_INSET_RIGHT] ?: DEFAULT_CONTENT_INSET_RIGHT
        config["aa_content_insets"] = "$contentTop,$contentBottom,$contentLeft,$contentRight"
        return config
    }

    /**
     * Update DataStore from bridge's config_echo so Settings UI shows the
     * bridge's actual running values. Called on each connection.
     */
    suspend fun applyConfigEcho(config: Map<String, String>) {
        dataStore.edit { prefs ->
            config["video_codec"]?.let { prefs[VIDEO_CODEC] = it }
            config["video_fps"]?.let { it.toIntOrNull()?.let { v -> prefs[VIDEO_FPS] = v } }
            config["aa_resolution"]?.let { prefs[AA_RESOLUTION] = it }
            config["video_dpi"]?.let { it.toIntOrNull()?.let { v -> prefs[AA_DPI] = v } }
            config["aa_width_margin"]?.let { it.toIntOrNull()?.let { v -> prefs[AA_WIDTH_MARGIN] = v } }
            config["aa_height_margin"]?.let { it.toIntOrNull()?.let { v -> prefs[AA_HEIGHT_MARGIN] = v } }
            config["aa_pixel_aspect"]?.let { it.toIntOrNull()?.let { v -> prefs[AA_PIXEL_ASPECT] = v } }
            config["drive_side"]?.let { prefs[DRIVE_SIDE] = it }
            config["head_unit_name"]?.let { prefs[HEAD_UNIT_NAME] = it }
            config["hide_clock"]?.let { prefs[HIDE_AA_CLOCK] = it.toBooleanStrictOrNull() ?: false }
            config["hide_phone_signal"]?.let { prefs[HIDE_PHONE_SIGNAL] = it.toBooleanStrictOrNull() ?: false }
            config["hide_battery_level"]?.let { prefs[HIDE_BATTERY_LEVEL] = it.toBooleanStrictOrNull() ?: false }
            // Parse insets: "top,bottom,left,right"
            config["aa_stable_insets"]?.let { str ->
                val parts = str.split(",").mapNotNull { it.trim().toIntOrNull() }
                if (parts.size == 4) {
                    prefs[SAFE_AREA_TOP] = parts[0]
                    prefs[SAFE_AREA_BOTTOM] = parts[1]
                    prefs[SAFE_AREA_LEFT] = parts[2]
                    prefs[SAFE_AREA_RIGHT] = parts[3]
                }
            }
            config["aa_content_insets"]?.let { str ->
                val parts = str.split(",").mapNotNull { it.trim().toIntOrNull() }
                if (parts.size == 4) {
                    prefs[CONTENT_INSET_TOP] = parts[0]
                    prefs[CONTENT_INSET_BOTTOM] = parts[1]
                    prefs[CONTENT_INSET_LEFT] = parts[2]
                    prefs[CONTENT_INSET_RIGHT] = parts[3]
                }
            }
        }
    }
}
