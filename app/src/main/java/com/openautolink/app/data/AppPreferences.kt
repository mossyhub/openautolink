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

        val VIDEO_AUTO_NEGOTIATE = booleanPreferencesKey("video_auto_negotiate")
        val VIDEO_CODEC = stringPreferencesKey("video_codec")
        val VIDEO_FPS = intPreferencesKey("video_fps")
        val DISPLAY_MODE = stringPreferencesKey("display_mode")
        val MIC_SOURCE = stringPreferencesKey("mic_source")
        val SYNC_AA_THEME = booleanPreferencesKey("sync_aa_theme")
        val HIDE_AA_CLOCK = booleanPreferencesKey("hide_aa_clock")
        val HIDE_PHONE_SIGNAL = booleanPreferencesKey("hide_phone_signal")
        val HIDE_BATTERY_LEVEL = booleanPreferencesKey("hide_battery_level")
        val SEND_IMU_SENSORS = booleanPreferencesKey("send_imu_sensors")
        val DISTANCE_UNITS = stringPreferencesKey("distance_units") // "auto", "metric", or "imperial"
        val VIDEO_SCALING_MODE = stringPreferencesKey("video_scaling_mode")
        val HOTSPOT_SSID = stringPreferencesKey("hotspot_ssid")
        val HOTSPOT_PASSWORD = stringPreferencesKey("hotspot_password")
        val DIRECT_TRANSPORT = stringPreferencesKey("direct_transport")
        val MANUAL_IP_ENABLED = booleanPreferencesKey("manual_ip_enabled")
        val MANUAL_IP_ADDRESS = stringPreferencesKey("manual_ip_address")
        val AA_RESOLUTION = stringPreferencesKey("aa_resolution")
        val AA_DPI = intPreferencesKey("aa_dpi")
        // When true (default), ignore AA_DPI and compute density at
        // session start from the live render rect so AA UI ends up at the
        // same physical size as native AAOS apps on the same panel.
        // Formula mirrors GM's getScaledDensity:
        //   fWidth = renderRectWidthPx / (codecW - widthMargin)
        //   density = round(panelDpi / fWidth)
        // The render rect is full panel in fullscreen_immersive mode and
        // the inset-shrunk rect in system_ui_visible mode — so this
        // automatically tracks the user's display-mode choice.
        val AA_AUTO_DPI = booleanPreferencesKey("aa_auto_dpi")
        val AA_WIDTH_MARGIN = intPreferencesKey("aa_width_margin")
        val AA_HEIGHT_MARGIN = intPreferencesKey("aa_height_margin")
        val AA_AUTO_MARGINS = booleanPreferencesKey("aa_auto_margins")
        val AA_PIXEL_ASPECT = intPreferencesKey("aa_pixel_aspect")
        val AA_TARGET_LAYOUT_WIDTH_DP = intPreferencesKey("aa_target_layout_width_dp")
        val AA_VIEWING_DISTANCE_MM = intPreferencesKey("aa_viewing_distance_mm")
        val AA_DECODER_ADDITIONAL_DEPTH = intPreferencesKey("aa_decoder_additional_depth")

        // App-side settings
        val DRIVE_SIDE = stringPreferencesKey("drive_side")
        val GPS_FORWARDING = booleanPreferencesKey("gps_forwarding")
        val CLUSTER_NAVIGATION = booleanPreferencesKey("cluster_navigation")
        val OVERLAY_STATS_BUTTON = booleanPreferencesKey("overlay_stats_button")
        val FILE_LOGGING_ENABLED = booleanPreferencesKey("file_logging_enabled")
        val FILE_LOGGING_AUTOSTART_USB = booleanPreferencesKey("file_logging_autostart_usb")
        val LOGCAT_CAPTURE_ENABLED = booleanPreferencesKey("logcat_capture_enabled")

        // AA safe area (stable) insets — maps render, UI stays inside
        val SAFE_AREA_TOP = intPreferencesKey("safe_area_top")
        val SAFE_AREA_BOTTOM = intPreferencesKey("safe_area_bottom")
        val SAFE_AREA_LEFT = intPreferencesKey("safe_area_left")
        val SAFE_AREA_RIGHT = intPreferencesKey("safe_area_right")

        // Custom viewport
        val CUSTOM_VIEWPORT_WIDTH = intPreferencesKey("custom_viewport_width")
        val CUSTOM_VIEWPORT_HEIGHT = intPreferencesKey("custom_viewport_height")
        val VIEWPORT_ASPECT_RATIO_LOCKED = booleanPreferencesKey("viewport_aspect_ratio_locked")

        // Key remapping — JSON string: {"androidKeycode": aaKeycode, ...}
        // e.g. {"131":88,"137":87} means F6→MEDIA_PREVIOUS, F7→MEDIA_NEXT
        val KEY_REMAP = stringPreferencesKey("key_remap")

        // Per-purpose volume offsets (-100 to +100, applied as gain multiplier)
        // 0 = default, +50 = 1.5x, -50 = 0.5x
        val VOLUME_OFFSET_MEDIA = intPreferencesKey("volume_offset_media")
        val VOLUME_OFFSET_NAVIGATION = intPreferencesKey("volume_offset_navigation")
        val VOLUME_OFFSET_ASSISTANT = intPreferencesKey("volume_offset_assistant")

        // Multi-phone: default phone to auto-connect to
        val DEFAULT_PHONE_NAME = stringPreferencesKey("default_phone_name")

        // Multi-phone (Car Hotspot mode):
        // - CONNECTION_MODE: "phone_hotspot" (default, current single-phone flow)
        //                    or "car_hotspot" (multi-phone over car AP).
        // - DEFAULT_PHONE_ID: stable phone_id (UUID published by companion in
        //   mDNS TXT) the car prefers when multiple phones are reachable.
        // - KNOWN_PHONES_JSON: JSON array of `{phone_id, friendly_name,
        //   last_seen_ms}` — the persistent list shown in the chooser even
        //   when the phone isn't currently advertising.
        // - ALWAYS_ASK_PHONE: when true, the car never auto-connects to the
        //   default phone; the chooser is shown on every connect. (Behavior 2.)
        // - CAR_HOTSPOT_AUTO_INTERFACE: when true (default), the discovery
        //   sweep prefers known AP-bridge interfaces (e.g. ap_br_swlan0 on
        //   GM AAOS) and falls back to other real interfaces. When false,
        //   only [CAR_HOTSPOT_INTERFACE_NAME] is scanned.
        // - CAR_HOTSPOT_INTERFACE_NAME: the explicit interface name to use
        //   when auto-detection is disabled. Empty = first real iface.
        val CONNECTION_MODE = stringPreferencesKey("connection_mode")
        val DEFAULT_PHONE_ID = stringPreferencesKey("default_phone_id")
        val KNOWN_PHONES_JSON = stringPreferencesKey("known_phones_json")
        val ALWAYS_ASK_PHONE = booleanPreferencesKey("always_ask_phone")
        val CAR_HOTSPOT_AUTO_INTERFACE = booleanPreferencesKey("car_hotspot_auto_interface")
        val CAR_HOTSPOT_INTERFACE_NAME = stringPreferencesKey("car_hotspot_interface_name")

        // EV energy model tuning — see docs/ev-energy-model-tuning-plan.md.
        // EV_TUNING_ENABLED is the master toggle; when false, all overrides
        // are ignored and we send today's hardcoded VEM values.
        // EV_DRIVING_MODE: "derived" | "manual" | "multiplier"
        val EV_TUNING_ENABLED = booleanPreferencesKey("ev_tuning_enabled")
        val EV_DRIVING_MODE = stringPreferencesKey("ev_driving_mode")
        val EV_DRIVING_WH_PER_KM = intPreferencesKey("ev_driving_wh_per_km")
        val EV_DRIVING_MULTIPLIER_PCT = intPreferencesKey("ev_driving_multiplier_pct")
        val EV_AUX_WH_PER_KM_X10 = intPreferencesKey("ev_aux_wh_per_km_x10")
        val EV_AERO_COEF_X100 = intPreferencesKey("ev_aero_coef_x100")
        val EV_RESERVE_PCT = intPreferencesKey("ev_reserve_pct")
        val EV_MAX_CHARGE_KW = intPreferencesKey("ev_max_charge_kw")
        val EV_MAX_DISCHARGE_KW = intPreferencesKey("ev_max_discharge_kw")

        // Phase 2 — EPA / community-curated prefill profiles.
        // EV_USE_EPA_BASELINE: when true, baseline driving rate falls back to
        //   the bundled per-vehicle Wh/km when the auto-derived value isn't
        //   available or when the master tuning toggle is off.
        // EV_PROFILES_LAST_UPDATE_MS / EV_PROFILES_OVERLAY_PATH: track the
        //   user-fetched profile DB (Phase 2b). Empty path = use bundled JSON.
        // EV_PROFILES_UPDATE_URL: source of truth for refreshes; defaults to
        //   the OAL repo's bundled file. User-editable for forks.
        val EV_USE_EPA_BASELINE = booleanPreferencesKey("ev_use_epa_baseline")
        val EV_PROFILES_LAST_UPDATE_MS = androidx.datastore.preferences.core.longPreferencesKey("ev_profiles_last_update_ms")
        val EV_PROFILES_UPDATE_URL = stringPreferencesKey("ev_profiles_update_url")

        // Phase 3' — learned driving rate (per-vehicle JSON map). Stored as
        // a single JSON blob keyed by "Make|Model|Year". See
        // [EvLearnedRateEstimator].
        val EV_LEARNED_RATES_JSON = stringPreferencesKey("ev_learned_rates_json")

        const val DEFAULT_VIDEO_AUTO_NEGOTIATE = true
        const val DEFAULT_VIDEO_CODEC = "h264"
        const val DEFAULT_VIDEO_FPS = 60
        const val DEFAULT_DISPLAY_MODE = "fullscreen_immersive"
        const val DEFAULT_MIC_SOURCE = "car"
        const val DEFAULT_SYNC_AA_THEME = true
        const val DEFAULT_HIDE_AA_CLOCK = false
        const val DEFAULT_HIDE_PHONE_SIGNAL = false
        const val DEFAULT_HIDE_BATTERY_LEVEL = false
        const val DEFAULT_SEND_IMU_SENSORS = true
        const val DEFAULT_DISTANCE_UNITS = "auto" // "auto" = locale-based, "metric", "imperial"
        const val DEFAULT_VIDEO_SCALING_MODE = "crop" // "letterbox" or "crop"
        const val DEFAULT_HOTSPOT_SSID = ""
        const val DEFAULT_HOTSPOT_PASSWORD = ""
        const val DEFAULT_DIRECT_TRANSPORT = "hotspot" // "hotspot" (TCP over shared WiFi), "usb" (AOAv2)
        const val DEFAULT_MANUAL_IP_ENABLED = false
        const val DEFAULT_MANUAL_IP_ADDRESS = ""
        const val DEFAULT_AA_RESOLUTION = "1080p" // "480p", "720p", "1080p", "1440p", "4k"
        const val DEFAULT_AA_DPI = 160
        const val DEFAULT_AA_AUTO_DPI = true
        const val DEFAULT_AA_WIDTH_MARGIN = 0
        const val DEFAULT_AA_HEIGHT_MARGIN = 0
        // When true, ignore the WIDTH/HEIGHT_MARGIN values and compute
        // per-tier margins so the codec's inner rect matches panel AR.
        // The renderer (Crop mode) uses the same formula on the decoded
        // frame so square pixels arrive on the panel. Disable to send
        // exactly the user-set width/height margin values (or 0 for no
        // margins, letting the panel stretch the full codec frame).
        const val DEFAULT_AA_AUTO_MARGINS = true
        const val DEFAULT_AA_PIXEL_ASPECT = -1  // -1 = auto-compute from display/video AR in crop mode
        const val DEFAULT_AA_TARGET_LAYOUT_WIDTH_DP = 0  // 0 = disabled (use DPI slider directly)
        // Viewing distance from driver eye to display, in millimetres. GM
        // sends this in their VideoConfiguration so the phone can pick text
        // sizes that look correct at the actual seating distance. 700mm is
        // the typical mid-IP-screen distance GM uses; tune per vehicle from
        // a tape measure or VHAL prop if available. 0 = omit field.
        const val DEFAULT_AA_VIEWING_DISTANCE_MM = 700
        // Number of additional decoded frames the car can buffer beyond the
        // codec's reorder requirement. GM hard-codes 1; matching this is
        // safest cross-phone. 0 = omit field.
        const val DEFAULT_AA_DECODER_ADDITIONAL_DEPTH = 1
        const val DEFAULT_DRIVE_SIDE = "left"
        const val DEFAULT_GPS_FORWARDING = true
        const val DEFAULT_CLUSTER_NAVIGATION = true
        const val DEFAULT_OVERLAY_STATS_BUTTON = true
        const val DEFAULT_FILE_LOGGING_ENABLED = false
        const val DEFAULT_FILE_LOGGING_AUTOSTART_USB = false
        const val DEFAULT_LOGCAT_CAPTURE_ENABLED = false
        const val DEFAULT_SAFE_AREA_TOP = 0
        const val DEFAULT_SAFE_AREA_BOTTOM = 0
        const val DEFAULT_SAFE_AREA_LEFT = 0
        const val DEFAULT_SAFE_AREA_RIGHT = 0
        const val DEFAULT_CUSTOM_VIEWPORT_WIDTH = 0 // 0 = use full usable width
        const val DEFAULT_CUSTOM_VIEWPORT_HEIGHT = 0 // 0 = use full usable height
        const val DEFAULT_VIEWPORT_ASPECT_RATIO_LOCKED = true
        const val DEFAULT_KEY_REMAP = "" // empty = use built-in defaults
        const val DEFAULT_VOLUME_OFFSET_MEDIA = 0
        const val DEFAULT_VOLUME_OFFSET_NAVIGATION = 0
        const val DEFAULT_VOLUME_OFFSET_ASSISTANT = 0
        const val DEFAULT_DEFAULT_PHONE_NAME = "" // empty = connect to first found

        // Connection-mode constants. Kept as `const val` strings so call sites
        // can `when (mode) { CONNECTION_MODE_PHONE_HOTSPOT -> ... }` cleanly.
        const val CONNECTION_MODE_PHONE_HOTSPOT = "phone_hotspot"
        const val CONNECTION_MODE_CAR_HOTSPOT = "car_hotspot"
        const val DEFAULT_CONNECTION_MODE = CONNECTION_MODE_CAR_HOTSPOT
        const val DEFAULT_DEFAULT_PHONE_ID = ""
        const val DEFAULT_KNOWN_PHONES_JSON = "[]"
        const val DEFAULT_ALWAYS_ASK_PHONE = false
        const val DEFAULT_CAR_HOTSPOT_AUTO_INTERFACE = true
        const val DEFAULT_CAR_HOTSPOT_INTERFACE_NAME = ""

        // EV tuning defaults — must reproduce current hardcoded VEM behavior
        // when EV_TUNING_ENABLED=false (see sendEnergyModelSensor in
        // app/src/main/cpp/jni_session.cpp).
        const val EV_DRIVING_MODE_DERIVED = "derived"
        const val EV_DRIVING_MODE_MANUAL = "manual"
        const val EV_DRIVING_MODE_MULTIPLIER = "multiplier"
        const val EV_DRIVING_MODE_LEARNED = "learned"

        const val DEFAULT_EV_TUNING_ENABLED = false
        const val DEFAULT_EV_DRIVING_MODE = EV_DRIVING_MODE_DERIVED
        const val DEFAULT_EV_DRIVING_WH_PER_KM = 160
        const val DEFAULT_EV_DRIVING_MULTIPLIER_PCT = 100
        const val DEFAULT_EV_AUX_WH_PER_KM_X10 = 20      // 2.0
        const val DEFAULT_EV_AERO_COEF_X100 = 36         // 0.36
        const val DEFAULT_EV_RESERVE_PCT = 5
        const val DEFAULT_EV_MAX_CHARGE_KW = 150
        const val DEFAULT_EV_MAX_DISCHARGE_KW = 150

        const val DEFAULT_EV_USE_EPA_BASELINE = false
        const val DEFAULT_EV_PROFILES_LAST_UPDATE_MS = 0L
        const val DEFAULT_EV_PROFILES_UPDATE_URL =
            "https://raw.githubusercontent.com/mossyhub/openautolink/main/app/src/main/assets/ev_profiles.json"

        const val DEFAULT_EV_LEARNED_RATES_JSON = "{}"
    }

    val videoAutoNegotiate: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[VIDEO_AUTO_NEGOTIATE] ?: DEFAULT_VIDEO_AUTO_NEGOTIATE
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

    val videoScalingMode: Flow<String> = dataStore.data.map { prefs ->
        prefs[VIDEO_SCALING_MODE] ?: DEFAULT_VIDEO_SCALING_MODE
    }

    val hotspotSsid: Flow<String> = dataStore.data.map { prefs ->
        prefs[HOTSPOT_SSID] ?: DEFAULT_HOTSPOT_SSID
    }

    val hotspotPassword: Flow<String> = dataStore.data.map { prefs ->
        prefs[HOTSPOT_PASSWORD] ?: DEFAULT_HOTSPOT_PASSWORD
    }

    val directTransport: Flow<String> = dataStore.data.map { prefs ->
        // Migrate any saved "nearby" preference to "hotspot" — Nearby Connections
        // is no longer used; the companion app speaks TCP over the shared WiFi.
        val raw = prefs[DIRECT_TRANSPORT] ?: DEFAULT_DIRECT_TRANSPORT
        if (raw == "nearby") "hotspot" else raw
    }

    val manualIpEnabled: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[MANUAL_IP_ENABLED] ?: DEFAULT_MANUAL_IP_ENABLED
    }

    val manualIpAddress: Flow<String> = dataStore.data.map { prefs ->
        prefs[MANUAL_IP_ADDRESS] ?: DEFAULT_MANUAL_IP_ADDRESS
    }

    val aaResolution: Flow<String> = dataStore.data.map { prefs ->
        prefs[AA_RESOLUTION] ?: DEFAULT_AA_RESOLUTION
    }

    val aaDpi: Flow<Int> = dataStore.data.map { prefs ->
        prefs[AA_DPI] ?: DEFAULT_AA_DPI
    }

    val aaAutoDpi: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[AA_AUTO_DPI] ?: DEFAULT_AA_AUTO_DPI
    }

    val aaWidthMargin: Flow<Int> = dataStore.data.map { prefs ->
        prefs[AA_WIDTH_MARGIN] ?: DEFAULT_AA_WIDTH_MARGIN
    }

    val aaHeightMargin: Flow<Int> = dataStore.data.map { prefs ->
        prefs[AA_HEIGHT_MARGIN] ?: DEFAULT_AA_HEIGHT_MARGIN
    }

    val aaAutoMargins: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[AA_AUTO_MARGINS] ?: DEFAULT_AA_AUTO_MARGINS
    }

    val aaPixelAspect: Flow<Int> = dataStore.data.map { prefs ->
        prefs[AA_PIXEL_ASPECT] ?: DEFAULT_AA_PIXEL_ASPECT
    }

    val aaTargetLayoutWidthDp: Flow<Int> = dataStore.data.map { prefs ->
        prefs[AA_TARGET_LAYOUT_WIDTH_DP] ?: DEFAULT_AA_TARGET_LAYOUT_WIDTH_DP
    }

    val aaViewingDistanceMm: Flow<Int> = dataStore.data.map { prefs ->
        prefs[AA_VIEWING_DISTANCE_MM] ?: DEFAULT_AA_VIEWING_DISTANCE_MM
    }

    val aaDecoderAdditionalDepth: Flow<Int> = dataStore.data.map { prefs ->
        prefs[AA_DECODER_ADDITIONAL_DEPTH] ?: DEFAULT_AA_DECODER_ADDITIONAL_DEPTH
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

    val overlayStatsButton: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[OVERLAY_STATS_BUTTON] ?: DEFAULT_OVERLAY_STATS_BUTTON
    }

    val fileLoggingEnabled: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[FILE_LOGGING_ENABLED] ?: DEFAULT_FILE_LOGGING_ENABLED
    }

    val fileLoggingAutoStartUsb: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[FILE_LOGGING_AUTOSTART_USB] ?: DEFAULT_FILE_LOGGING_AUTOSTART_USB
    }

    val logcatCaptureEnabled: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[LOGCAT_CAPTURE_ENABLED] ?: DEFAULT_LOGCAT_CAPTURE_ENABLED
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

    val customViewportWidth: Flow<Int> = dataStore.data.map { prefs ->
        prefs[CUSTOM_VIEWPORT_WIDTH] ?: DEFAULT_CUSTOM_VIEWPORT_WIDTH
    }

    val customViewportHeight: Flow<Int> = dataStore.data.map { prefs ->
        prefs[CUSTOM_VIEWPORT_HEIGHT] ?: DEFAULT_CUSTOM_VIEWPORT_HEIGHT
    }

    val viewportAspectRatioLocked: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[VIEWPORT_ASPECT_RATIO_LOCKED] ?: DEFAULT_VIEWPORT_ASPECT_RATIO_LOCKED
    }

    suspend fun setHotspotSsid(ssid: String) {
        dataStore.edit { it[HOTSPOT_SSID] = ssid }
    }

    suspend fun setHotspotPassword(password: String) {
        dataStore.edit { it[HOTSPOT_PASSWORD] = password }
    }

    suspend fun setDirectTransport(transport: String) {
        dataStore.edit { it[DIRECT_TRANSPORT] = transport }
    }

    suspend fun setManualIpEnabled(enabled: Boolean) {
        dataStore.edit { it[MANUAL_IP_ENABLED] = enabled }
    }

    suspend fun setManualIpAddress(address: String) {
        dataStore.edit { it[MANUAL_IP_ADDRESS] = address }
    }

    suspend fun setAaResolution(resolution: String) {
        dataStore.edit { it[AA_RESOLUTION] = resolution }
    }

    suspend fun setAaDpi(dpi: Int) {
        dataStore.edit { it[AA_DPI] = dpi }
    }

    suspend fun setAaAutoDpi(value: Boolean) {
        dataStore.edit { it[AA_AUTO_DPI] = value }
    }

    suspend fun setAaWidthMargin(margin: Int) {
        dataStore.edit { it[AA_WIDTH_MARGIN] = margin }
    }

    suspend fun setAaHeightMargin(margin: Int) {
        dataStore.edit { it[AA_HEIGHT_MARGIN] = margin }
    }

    suspend fun setAaAutoMargins(value: Boolean) {
        dataStore.edit { it[AA_AUTO_MARGINS] = value }
    }

    suspend fun setAaPixelAspect(value: Int) {
        dataStore.edit { it[AA_PIXEL_ASPECT] = value }
    }

    suspend fun setAaTargetLayoutWidthDp(value: Int) {
        dataStore.edit { it[AA_TARGET_LAYOUT_WIDTH_DP] = value }
    }

    suspend fun setAaViewingDistanceMm(value: Int) {
        dataStore.edit { it[AA_VIEWING_DISTANCE_MM] = value }
    }

    suspend fun setAaDecoderAdditionalDepth(value: Int) {
        dataStore.edit { it[AA_DECODER_ADDITIONAL_DEPTH] = value }
    }

    suspend fun setVideoAutoNegotiate(enabled: Boolean) {
        dataStore.edit { it[VIDEO_AUTO_NEGOTIATE] = enabled }
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

    suspend fun setVideoScalingMode(mode: String) {
        dataStore.edit { it[VIDEO_SCALING_MODE] = mode }
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

    suspend fun setOverlayStatsButton(visible: Boolean) {
        dataStore.edit { it[OVERLAY_STATS_BUTTON] = visible }
    }

    suspend fun setFileLoggingEnabled(enabled: Boolean) {
        dataStore.edit { it[FILE_LOGGING_ENABLED] = enabled }
    }

    suspend fun setFileLoggingAutoStartUsb(enabled: Boolean) {
        dataStore.edit { it[FILE_LOGGING_AUTOSTART_USB] = enabled }
    }

    suspend fun setLogcatCaptureEnabled(enabled: Boolean) {
        dataStore.edit { it[LOGCAT_CAPTURE_ENABLED] = enabled }
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

    suspend fun setCustomViewportWidth(width: Int) {
        dataStore.edit { it[CUSTOM_VIEWPORT_WIDTH] = width }
    }

    suspend fun setCustomViewportHeight(height: Int) {
        dataStore.edit { it[CUSTOM_VIEWPORT_HEIGHT] = height }
    }

    suspend fun setViewportAspectRatioLocked(locked: Boolean) {
        dataStore.edit { it[VIEWPORT_ASPECT_RATIO_LOCKED] = locked }
    }

    // Key remapping
    val keyRemap: Flow<String> = dataStore.data.map { prefs ->
        prefs[KEY_REMAP] ?: DEFAULT_KEY_REMAP
    }

    suspend fun setKeyRemap(json: String) {
        dataStore.edit { it[KEY_REMAP] = json }
    }

    // Per-purpose volume offsets
    val volumeOffsetMedia: Flow<Int> = dataStore.data.map { prefs ->
        prefs[VOLUME_OFFSET_MEDIA] ?: DEFAULT_VOLUME_OFFSET_MEDIA
    }
    val volumeOffsetNavigation: Flow<Int> = dataStore.data.map { prefs ->
        prefs[VOLUME_OFFSET_NAVIGATION] ?: DEFAULT_VOLUME_OFFSET_NAVIGATION
    }
    val volumeOffsetAssistant: Flow<Int> = dataStore.data.map { prefs ->
        prefs[VOLUME_OFFSET_ASSISTANT] ?: DEFAULT_VOLUME_OFFSET_ASSISTANT
    }

    suspend fun setVolumeOffsetMedia(offset: Int) {
        dataStore.edit { it[VOLUME_OFFSET_MEDIA] = offset.coerceIn(-100, 100) }
    }
    suspend fun setVolumeOffsetNavigation(offset: Int) {
        dataStore.edit { it[VOLUME_OFFSET_NAVIGATION] = offset.coerceIn(-100, 100) }
    }
    suspend fun setVolumeOffsetAssistant(offset: Int) {
        dataStore.edit { it[VOLUME_OFFSET_ASSISTANT] = offset.coerceIn(-100, 100) }
    }

    // Default phone name for auto-connect
    val defaultPhoneName: Flow<String> = dataStore.data.map { prefs ->
        prefs[DEFAULT_PHONE_NAME] ?: DEFAULT_DEFAULT_PHONE_NAME
    }

    suspend fun setDefaultPhoneName(name: String) {
        dataStore.edit { it[DEFAULT_PHONE_NAME] = name }
    }

    // ── Multi-phone (Car Hotspot mode) ──────────────────────────────

    val connectionMode: Flow<String> = dataStore.data.map { prefs ->
        prefs[CONNECTION_MODE] ?: DEFAULT_CONNECTION_MODE
    }

    suspend fun setConnectionMode(mode: String) {
        // Sanitize unknown values to the default rather than persisting junk.
        val sanitized = when (mode) {
            CONNECTION_MODE_PHONE_HOTSPOT, CONNECTION_MODE_CAR_HOTSPOT -> mode
            else -> DEFAULT_CONNECTION_MODE
        }
        dataStore.edit { it[CONNECTION_MODE] = sanitized }
    }

    val defaultPhoneId: Flow<String> = dataStore.data.map { prefs ->
        prefs[DEFAULT_PHONE_ID] ?: DEFAULT_DEFAULT_PHONE_ID
    }

    suspend fun setDefaultPhoneId(id: String) {
        dataStore.edit { it[DEFAULT_PHONE_ID] = id }
    }

    val knownPhonesJson: Flow<String> = dataStore.data.map { prefs ->
        prefs[KNOWN_PHONES_JSON] ?: DEFAULT_KNOWN_PHONES_JSON
    }

    suspend fun setKnownPhonesJson(json: String) {
        dataStore.edit { it[KNOWN_PHONES_JSON] = json }
    }

    val alwaysAskPhone: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[ALWAYS_ASK_PHONE] ?: DEFAULT_ALWAYS_ASK_PHONE
    }

    suspend fun setAlwaysAskPhone(enabled: Boolean) {
        dataStore.edit { it[ALWAYS_ASK_PHONE] = enabled }
    }

    val carHotspotAutoInterface: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[CAR_HOTSPOT_AUTO_INTERFACE] ?: DEFAULT_CAR_HOTSPOT_AUTO_INTERFACE
    }

    suspend fun setCarHotspotAutoInterface(enabled: Boolean) {
        dataStore.edit { it[CAR_HOTSPOT_AUTO_INTERFACE] = enabled }
    }

    val carHotspotInterfaceName: Flow<String> = dataStore.data.map { prefs ->
        prefs[CAR_HOTSPOT_INTERFACE_NAME] ?: DEFAULT_CAR_HOTSPOT_INTERFACE_NAME
    }

    suspend fun setCarHotspotInterfaceName(name: String) {
        dataStore.edit { it[CAR_HOTSPOT_INTERFACE_NAME] = name }
    }

    // ── EV energy model tuning ──────────────────────────────────────

    val evTuningEnabled: Flow<Boolean> = dataStore.data.map { p ->
        p[EV_TUNING_ENABLED] ?: DEFAULT_EV_TUNING_ENABLED
    }

    suspend fun setEvTuningEnabled(enabled: Boolean) {
        dataStore.edit { it[EV_TUNING_ENABLED] = enabled }
    }

    val evDrivingMode: Flow<String> = dataStore.data.map { p ->
        p[EV_DRIVING_MODE] ?: DEFAULT_EV_DRIVING_MODE
    }

    suspend fun setEvDrivingMode(mode: String) {
        val sanitized = when (mode) {
            EV_DRIVING_MODE_DERIVED, EV_DRIVING_MODE_MANUAL,
            EV_DRIVING_MODE_MULTIPLIER, EV_DRIVING_MODE_LEARNED -> mode
            else -> DEFAULT_EV_DRIVING_MODE
        }
        dataStore.edit { it[EV_DRIVING_MODE] = sanitized }
    }

    val evDrivingWhPerKm: Flow<Int> = dataStore.data.map { p ->
        p[EV_DRIVING_WH_PER_KM] ?: DEFAULT_EV_DRIVING_WH_PER_KM
    }

    suspend fun setEvDrivingWhPerKm(value: Int) {
        dataStore.edit { it[EV_DRIVING_WH_PER_KM] = value.coerceIn(80, 300) }
    }

    val evDrivingMultiplierPct: Flow<Int> = dataStore.data.map { p ->
        p[EV_DRIVING_MULTIPLIER_PCT] ?: DEFAULT_EV_DRIVING_MULTIPLIER_PCT
    }

    suspend fun setEvDrivingMultiplierPct(value: Int) {
        dataStore.edit { it[EV_DRIVING_MULTIPLIER_PCT] = value.coerceIn(50, 150) }
    }

    val evAuxWhPerKmX10: Flow<Int> = dataStore.data.map { p ->
        p[EV_AUX_WH_PER_KM_X10] ?: DEFAULT_EV_AUX_WH_PER_KM_X10
    }

    suspend fun setEvAuxWhPerKmX10(value: Int) {
        dataStore.edit { it[EV_AUX_WH_PER_KM_X10] = value.coerceIn(0, 100) }
    }

    val evAeroCoefX100: Flow<Int> = dataStore.data.map { p ->
        p[EV_AERO_COEF_X100] ?: DEFAULT_EV_AERO_COEF_X100
    }

    suspend fun setEvAeroCoefX100(value: Int) {
        dataStore.edit { it[EV_AERO_COEF_X100] = value.coerceIn(20, 45) }
    }

    val evReservePct: Flow<Int> = dataStore.data.map { p ->
        p[EV_RESERVE_PCT] ?: DEFAULT_EV_RESERVE_PCT
    }

    suspend fun setEvReservePct(value: Int) {
        dataStore.edit { it[EV_RESERVE_PCT] = value.coerceIn(0, 15) }
    }

    val evMaxChargeKw: Flow<Int> = dataStore.data.map { p ->
        p[EV_MAX_CHARGE_KW] ?: DEFAULT_EV_MAX_CHARGE_KW
    }

    suspend fun setEvMaxChargeKw(value: Int) {
        dataStore.edit { it[EV_MAX_CHARGE_KW] = value.coerceIn(50, 350) }
    }

    val evMaxDischargeKw: Flow<Int> = dataStore.data.map { p ->
        p[EV_MAX_DISCHARGE_KW] ?: DEFAULT_EV_MAX_DISCHARGE_KW
    }

    suspend fun setEvMaxDischargeKw(value: Int) {
        dataStore.edit { it[EV_MAX_DISCHARGE_KW] = value.coerceIn(50, 300) }
    }

    // ── EV profile prefill (Phase 2 / 2b) ───────────────────────────

    val evUseEpaBaseline: Flow<Boolean> = dataStore.data.map { p ->
        p[EV_USE_EPA_BASELINE] ?: DEFAULT_EV_USE_EPA_BASELINE
    }

    suspend fun setEvUseEpaBaseline(enabled: Boolean) {
        dataStore.edit { it[EV_USE_EPA_BASELINE] = enabled }
    }

    val evProfilesLastUpdateMs: Flow<Long> = dataStore.data.map { p ->
        p[EV_PROFILES_LAST_UPDATE_MS] ?: DEFAULT_EV_PROFILES_LAST_UPDATE_MS
    }

    suspend fun setEvProfilesLastUpdateMs(ms: Long) {
        dataStore.edit { it[EV_PROFILES_LAST_UPDATE_MS] = ms }
    }

    val evProfilesUpdateUrl: Flow<String> = dataStore.data.map { p ->
        val raw = p[EV_PROFILES_UPDATE_URL] ?: DEFAULT_EV_PROFILES_UPDATE_URL
        if (raw.isBlank()) DEFAULT_EV_PROFILES_UPDATE_URL else raw
    }

    suspend fun setEvProfilesUpdateUrl(url: String) {
        dataStore.edit { it[EV_PROFILES_UPDATE_URL] = url }
    }

    val evLearnedRatesJson: Flow<String> = dataStore.data.map { p ->
        p[EV_LEARNED_RATES_JSON] ?: DEFAULT_EV_LEARNED_RATES_JSON
    }

    suspend fun setEvLearnedRatesJson(json: String) {
        dataStore.edit { it[EV_LEARNED_RATES_JSON] = json }
    }
}
