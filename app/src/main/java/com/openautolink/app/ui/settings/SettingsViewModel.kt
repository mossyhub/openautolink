package com.openautolink.app.ui.settings

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.openautolink.app.data.AppPreferences
import com.openautolink.app.data.CarPlaySettings
import com.openautolink.app.data.CarPlayPairedPhone

import com.openautolink.carplaylab.SavedWifiPassword as EncryptedSavedWifiPassword
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import com.openautolink.app.transport.bluetooth.WppConfigBtServer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class SettingsUiState(
    val directTransport: String = AppPreferences.DEFAULT_DIRECT_TRANSPORT,
    val hotspotSsid: String = AppPreferences.DEFAULT_HOTSPOT_SSID,
    val hotspotPassword: String = AppPreferences.DEFAULT_HOTSPOT_PASSWORD,
    /** AP BSSID advertised to the phone in WPP mode. */
    val wppBssid: String = "",
    /** Interface serving the car's hotspot; its IPv4 is advertised to the phone. */
    val wppApInterface: String = AppPreferences.DEFAULT_WPP_AP_INTERFACE,
    /** Exact AP frequency in MHz sent to the phone; 0 = advertise the default 5GHz set. */
    val wppChannelMhz: String = "",
    val videoAutoNegotiate: Boolean = AppPreferences.DEFAULT_VIDEO_AUTO_NEGOTIATE,
    val videoCodec: String = AppPreferences.DEFAULT_VIDEO_CODEC,
    val videoSeedThresholdH264: Int = AppPreferences.DEFAULT_VIDEO_SEED_THRESHOLD_H264,
    val videoSeedThresholdH265: Int = AppPreferences.DEFAULT_VIDEO_SEED_THRESHOLD_H265,
    val videoSeedThresholdVp9: Int = AppPreferences.DEFAULT_VIDEO_SEED_THRESHOLD_VP9,
    val videoFps: Int = AppPreferences.DEFAULT_VIDEO_FPS,
    val galVersion: String = AppPreferences.DEFAULT_GAL_VERSION,
    val displayMode: String = AppPreferences.DEFAULT_DISPLAY_MODE,
    val micSource: String = AppPreferences.DEFAULT_MIC_SOURCE,
    val btMacOverride: String = AppPreferences.DEFAULT_BT_MAC_OVERRIDE,
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
    val alwaysInPark: Boolean = AppPreferences.DEFAULT_ALWAYS_IN_PARK,
    val clusterNavigation: Boolean = AppPreferences.DEFAULT_CLUSTER_NAVIGATION,
    val overlayStatsButton: Boolean = AppPreferences.DEFAULT_OVERLAY_STATS_BUTTON,
    val overlayPhoneSwitchButton: Boolean = AppPreferences.DEFAULT_OVERLAY_PHONE_SWITCH_BUTTON,
    val overlayReconnectButton: Boolean = AppPreferences.DEFAULT_OVERLAY_RECONNECT_BUTTON,
    val fileLoggingEnabled: Boolean = AppPreferences.DEFAULT_FILE_LOGGING_ENABLED,
    val fileLoggingAutoStartUsb: Boolean = AppPreferences.DEFAULT_FILE_LOGGING_AUTOSTART_USB,
    val logcatCaptureEnabled: Boolean = AppPreferences.DEFAULT_LOGCAT_CAPTURE_ENABLED,
    val logPersistEnabled: Boolean = AppPreferences.DEFAULT_LOG_PERSIST_ENABLED,
    // Maintainer log upload (off by default)
    val logUploadEnabled: Boolean = AppPreferences.DEFAULT_LOG_UPLOAD_ENABLED,
    val simulateIgnitionButton: Boolean = AppPreferences.DEFAULT_SIMULATE_IGNITION_BUTTON,
    val logUploadUrl: String = AppPreferences.DEFAULT_LOG_UPLOAD_URL,
    val logUploadToken: String = AppPreferences.DEFAULT_LOG_UPLOAD_TOKEN,
    val logUploadDeviceLabel: String = AppPreferences.DEFAULT_LOG_UPLOAD_DEVICE_LABEL,
    val evContributionConsent: Boolean = AppPreferences.DEFAULT_EV_CONTRIBUTION_CONSENT,
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

/** Memory-only edits shared by every CarPlay tab; transformations copy only edited fields. */
internal class CarPlaySettingsDraft {
    private val edits = mutableListOf<(CarPlaySettings) -> CarPlaySettings>()
    private var password: String? = null
    private var passwordRevision = 0

    @Synchronized fun setPassword(value: String?) { password = value; passwordRevision++ }
    @Synchronized fun pendingPassword(): String? = password

    @Synchronized fun update(edit: (CarPlaySettings) -> CarPlaySettings) { edits += edit }
    @Synchronized fun preview(latest: CarPlaySettings): CarPlaySettings = edits.fold(latest) { value, edit -> edit(value) }

    suspend fun commit(persist: suspend ((CarPlaySettings) -> CarPlaySettings, String?) -> Unit): Boolean {
        val (pendingEdits, pendingPassword, revision) = synchronized(this) { Triple(edits.toList(), password, passwordRevision) }
        if (pendingEdits.isEmpty() && pendingPassword == null) return true
        return try {
            persist({ latest -> pendingEdits.fold(latest) { value, edit -> edit(value) } }, pendingPassword)
            synchronized(this) {
                edits.subList(0, pendingEdits.size).clear()
                if (passwordRevision == revision) password = null
            }
            true
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            false // Never export an exception that could contain a secret.
        }
    }
}

/** The global action never restarts or dismisses settings before persistence succeeds. */
internal suspend fun applySettingsAndRestart(
    commit: suspend () -> Boolean,
    restart: () -> Unit,
    close: () -> Unit,
) {
    if (commit()) { restart(); close() }
}

/** Incomplete setup is saveable; malformed entered values are not. Readiness is separate. */
internal fun carPlayEditError(settings: CarPlaySettings): String? = when {
    settings.networkFamily !in setOf("auto", "ipv4", "ipv6") -> "Choose Auto, IPv4 or IPv6 in Connection."
    settings.apSecurity !in setOf("wpa2", "wpa3") -> "Choose WPA2 or WPA3 in Connection."
    !(settings.apFrequencyMhz == 2484 ||
        settings.apFrequencyMhz in 2412..2472 && (settings.apFrequencyMhz - 2407) % 5 == 0 ||
        settings.apFrequencyMhz in 5000..5900 && settings.apFrequencyMhz % 5 == 0) ->
        "Enter a supported AP frequency in MHz in Connection."
    settings.sharedWifiOverride && settings.peerAddress.isEmpty() -> "Shared LAN requires an explicit numeric phone IP in Connection."
    settings.peerAddress.isNotEmpty() && !com.openautolink.app.data.CarPlaySettingsPolicy.configuredEndpoint(settings.peerAddress, settings) ->
        "Enter a numeric phone IP matching the family and interface in Connection."
    settings.localAddress.isNotEmpty() && !com.openautolink.app.data.CarPlaySettingsPolicy.configuredEndpoint(settings.localAddress, settings) ->
        "Enter an exact numeric local address matching the family and interface in Connection."
    listOf(settings.phoneAddress, settings.receiverAddress).any {
        it.isNotEmpty() && (!it.matches(Regex("([0-9a-fA-F]{2}:){5}[0-9a-fA-F]{2}")) || it == "02:00:00:00:00:00")
    } -> "Choose a paired phone or correct the Bluetooth address in Connection."
    settings.videoError() != null -> settings.videoError()
    settings.widthPhysicalMm !in com.openautolink.carplaylab.media.CarPlayDisplayPreferences.PHYSICAL_WIDTHS_MM ->
        "Choose a manual UI size from the slider in Display (100 to 400 mm calibration)."
    else -> null
}

class SettingsViewModel(application: Application) : AndroidViewModel(application) {

    private val preferences = AppPreferences.getInstance(application)
    private val wppConfigServer = WppConfigBtServer(application, viewModelScope)

    // Shared, memory-only next-session draft. Reading it never starts either protocol.
    private val carPlayDraft = CarPlaySettingsDraft()
    private val carPlayDraftRevision = MutableStateFlow(0)
    private val _carPlaySettingsLoaded = MutableStateFlow(false)
    val carPlaySettingsLoaded: StateFlow<Boolean> = _carPlaySettingsLoaded
    val carPlaySettings: StateFlow<CarPlaySettings> = combine(
        preferences.carPlaySettings.onEach { _carPlaySettingsLoaded.value = true },
        carPlayDraftRevision,
    ) { latest, _ -> carPlayDraft.preview(latest) }.stateIn(
        viewModelScope, SharingStarted.Eagerly, CarPlaySettings(),
    )
    private val carPlayMutation = Mutex()
    private val _carPlayPhones = MutableStateFlow<List<CarPlayPairedPhone>>(emptyList())
    val carPlayPhones: StateFlow<List<CarPlayPairedPhone>> = _carPlayPhones
    private val _carPlayIdentityReady = MutableStateFlow(false)
    val carPlayIdentityReady: StateFlow<Boolean> = _carPlayIdentityReady
    private val _carPlayPasswordSaved = MutableStateFlow(false)
    val carPlayPasswordSaved: StateFlow<Boolean> = _carPlayPasswordSaved
    private val _carPlaySettingsStatus = MutableStateFlow("")
    val carPlaySettingsStatus: StateFlow<String> = _carPlaySettingsStatus
    private val _carPlaySettingsSaving = MutableStateFlow(false)
    val carPlaySettingsSaving: StateFlow<Boolean> = _carPlaySettingsSaving
    private val _carPlayApplyError = MutableStateFlow("")
    val carPlayApplyError: StateFlow<String> = _carPlayApplyError

    private val _carPlayCredentialImportStatus = MutableStateFlow("")
    val carPlayCredentialImportStatus: StateFlow<String> = _carPlayCredentialImportStatus
    private val _carPlayCredentialImportBusy = MutableStateFlow(false)
    val carPlayCredentialImportBusy: StateFlow<Boolean> = _carPlayCredentialImportBusy

    /** Explicit import, independent of the shared preference Apply/Restart transaction. */
    fun importCarPlayCredentials(privateKey: android.net.Uri, certificate: android.net.Uri) {
        if (_carPlayCredentialImportBusy.value || _carPlaySettingsSaving.value) return
        _carPlayCredentialImportBusy.value = true
        _carPlayCredentialImportStatus.value = "Validating selected pair…"
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val resolver = getApplication<Application>().contentResolver
                require(privateKey.scheme == "content" && certificate.scheme == "content")
                val imported = resolver.openInputStream(privateKey)?.use { key ->
                    resolver.openInputStream(certificate)?.use { cert ->
                        CarPlayCredentialImporter(getApplication<Application>().noBackupFilesDir).importPair(key, cert)
                    }
                } == true
                _carPlayCredentialImportStatus.value = if (imported) {
                    _carPlayIdentityReady.value = true
                    "Identity imported and locally validated. Use Save & Reconnect for the next connection. Phone acceptance is not verified."
                } else "Import failed. Choose a matching P-256 PKCS#8 key and single certificate (each at most 16 KiB). Existing identity is unchanged."
            } catch (_: Exception) {
                _carPlayCredentialImportStatus.value = "Could not read or import the selected files. Existing identity is unchanged."
            } finally {
                _carPlayCredentialImportBusy.value = false
            }
        }
    }

    fun updateCarPlayEnabled(enabled: Boolean) {
        updateCarPlayDraft { it.copy(enabled = enabled) }
    }

    fun updateCarPlayDraft(edit: (CarPlaySettings) -> CarPlaySettings) {
        if (_carPlaySettingsSaving.value || !_carPlaySettingsLoaded.value) return
        carPlayDraft.update(edit)
        carPlayDraftRevision.value++
        _carPlayApplyError.value = ""
    }

    /** Secret remains only in this ViewModel's private memory, not a StateFlow or Bundle. */
    fun pendingCarPlayPassword(): String? = carPlayDraft.pendingPassword()
    fun updateCarPlayPassword(password: String?) {
        if (_carPlaySettingsSaving.value) return
        carPlayDraft.setPassword(password)
        _carPlayApplyError.value = ""
    }

    /** Reads only bonded devices. Never discovers, picks by name or starts either protocol. */
    fun refreshCarPlaySetup() {
        viewModelScope.launch(Dispatchers.IO) {
            if (!carPlaySettings.value.enabled) return@launch
            try {
                val adapter = getApplication<Application>().getSystemService(android.bluetooth.BluetoothManager::class.java)?.adapter
                _carPlayPhones.value = adapter?.bondedDevices.orEmpty().map {
                    CarPlayPairedPhone(it.address, it.name ?: "Paired phone")
                }.sortedWith(compareBy({ it.name }, { it.address }))
                _carPlaySettingsStatus.value = if (adapter?.isEnabled == true) "" else "Turn on Bluetooth in Android settings"
            } catch (_: SecurityException) {
                _carPlayPhones.value = emptyList()
                _carPlaySettingsStatus.value = "Allow Bluetooth access, then refresh paired phones"
            }
            _carPlayIdentityReady.value = runCatching {
                com.shilapi.xcertplay.mfi.LocalMfiAuthenticationClient.load(java.io.File(
                    getApplication<Application>().noBackupFilesDir,
                    com.shilapi.xcertplay.mfi.LocalMfiAuthenticationClient.DIRECTORY,
                ))
            }.isSuccess // Local key consistency only; not proof that an iPhone trusts it.
            _carPlayPasswordSaved.value = runCatching {
                EncryptedSavedWifiPassword(getApplication()).load().isNotEmpty()
            }.getOrDefault(false)
        }
    }

    /** Global Apply awaits persistence. Validation/save failure keeps the draft and overlay open. */
    suspend fun commitCarPlaySettings(): Boolean {
        if (_carPlaySettingsSaving.value) return false
        _carPlaySettingsSaving.value = true
        _carPlayApplyError.value = ""
        return try {
            kotlinx.coroutines.withContext(Dispatchers.IO) {
                carPlayMutation.withLock {
                    var validationError: String? = null
                    val saved = carPlayDraft.commit { merge, password ->
                        preferences.updateCarPlaySettings(merge) { next ->
                            validationError = carPlayEditError(next)
                            if (validationError == null && next.enabled && !next.usesWindowGeometry) {
                                val request = next.displayRequest()
                                if (!com.openautolink.carplaylab.media.CarPlayDisplayPolicy.isQualified(request)) {
                                    validationError = "This exact resolution and maximum FPS are not supported by the local decoder in Video."
                                }
                            }
                            check(validationError == null)
                            if (password != null) EncryptedSavedWifiPassword(getApplication()).save(password)
                        }
                    }
                    if (saved) {
                        carPlayDraftRevision.value++
                        _carPlayPasswordSaved.value = runCatching {
                            EncryptedSavedWifiPassword(getApplication()).load().isNotEmpty()
                        }.getOrDefault(false)
                    } else {
                        _carPlayApplyError.value = validationError ?: "CarPlay settings could not be saved. Check storage and try again."
                    }
                    saved
                }
            }
        } finally {
            _carPlaySettingsSaving.value = false
        }
    }

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
        preferences.btMacOverride,
        preferences.logUploadEnabled,
        preferences.logUploadUrl,
        preferences.logUploadToken,
        preferences.logUploadDeviceLabel,
        preferences.logPersistEnabled,
        preferences.alwaysInPark,
        preferences.simulateIgnitionButton,
        preferences.overlayPhoneSwitchButton,
        preferences.overlayReconnectButton,
        preferences.evContributionConsent,
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
            btMacOverride = values[40] as String,
            logUploadEnabled = values[41] as Boolean,
            logUploadUrl = values[42] as String,
            logUploadToken = values[43] as String,
            logUploadDeviceLabel = values[44] as String,
            logPersistEnabled = values[45] as Boolean,
            alwaysInPark = values[46] as Boolean,
            simulateIgnitionButton = values[47] as Boolean,
            overlayPhoneSwitchButton = values[48] as Boolean,
            overlayReconnectButton = values[49] as Boolean,
            evContributionConsent = values[50] as Boolean,
        )
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5000),
        SettingsUiState()
    )

    val galVersion: StateFlow<String> = preferences.galVersion.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5000),
        AppPreferences.DEFAULT_GAL_VERSION,
    )

    val wppConfigBtStatus: StateFlow<String> = wppConfigServer.status
    val wppConfigBtAppliedConfig = wppConfigServer.appliedConfig

    private val _hotspotSsidOverride = MutableStateFlow(AppPreferences.DEFAULT_HOTSPOT_SSID)
    private val _hotspotPasswordOverride = MutableStateFlow(AppPreferences.DEFAULT_HOTSPOT_PASSWORD)
    private val _wppBssidOverride = MutableStateFlow("")
    private val _wppApInterfaceOverride = MutableStateFlow(AppPreferences.DEFAULT_WPP_AP_INTERFACE)
    private val _wppChannelMhzOverride = MutableStateFlow("")
    private val _directTransportOverride = MutableStateFlow(AppPreferences.DEFAULT_DIRECT_TRANSPORT)

    private val seedIdrThresholds = preferences.seedIdrThresholds.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5000),
        com.openautolink.app.video.SeedIdrThresholds(),
    )

    init {
        viewModelScope.launch {
            preferences.hotspotSsid.collect { _hotspotSsidOverride.value = it }
        }
        viewModelScope.launch {
            preferences.hotspotPassword.collect { _hotspotPasswordOverride.value = it }
        }
        viewModelScope.launch {
            preferences.wppBssid.collect { _wppBssidOverride.value = it }
        }
        viewModelScope.launch {
            preferences.wppApInterface.collect { _wppApInterfaceOverride.value = it }
        }
        viewModelScope.launch {
            preferences.wppChannelMhz.collect {
                _wppChannelMhzOverride.value = if (it > 0) it.toString() else ""
            }
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
        _wppBssidOverride,
        _wppApInterfaceOverride,
        _wppChannelMhzOverride,
        galVersion,
        seedIdrThresholds,
    ) { arr ->
        val state = arr[0] as SettingsUiState
        state.copy(
            hotspotSsid = arr[1] as String, hotspotPassword = arr[2] as String,
            directTransport = arr[3] as String, wppBssid = arr[4] as String,
            wppApInterface = arr[5] as String,
            wppChannelMhz = arr[6] as String,
            galVersion = arr[7] as String,
            videoSeedThresholdH264 = (arr[8] as com.openautolink.app.video.SeedIdrThresholds).h264Bytes,
            videoSeedThresholdH265 = (arr[8] as com.openautolink.app.video.SeedIdrThresholds).h265Bytes,
            videoSeedThresholdVp9 = (arr[8] as com.openautolink.app.video.SeedIdrThresholds).vp9Bytes,
        )
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

    fun updateWppChannelMhz(mhz: String) {
        _wppChannelMhzOverride.value = mhz
        viewModelScope.launch { preferences.setWppChannelMhz(mhz.trim().toIntOrNull() ?: 0) }
    }

    fun updateWppApInterface(name: String) {
        _wppApInterfaceOverride.value = name
        viewModelScope.launch { preferences.setWppApInterface(name) }
    }

    fun updateWppBssid(bssid: String) {
        _wppBssidOverride.value = bssid
        viewModelScope.launch { preferences.setWppBssid(bssid) }
    }

    fun startWppConfigListener() {
        wppConfigServer.start()
    }

    fun stopWppConfigListener() {
        wppConfigServer.stop()
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

    fun updateVideoSeedThresholdH264(bytes: Int) {
        viewModelScope.launch { preferences.setVideoSeedThresholdH264(bytes) }
    }

    fun updateVideoSeedThresholdH265(bytes: Int) {
        viewModelScope.launch { preferences.setVideoSeedThresholdH265(bytes) }
    }

    fun updateVideoSeedThresholdVp9(bytes: Int) {
        viewModelScope.launch { preferences.setVideoSeedThresholdVp9(bytes) }
    }

    fun updateVideoAutoNegotiate(enabled: Boolean) {
        viewModelScope.launch { preferences.setVideoAutoNegotiate(enabled) }
    }

    fun updateVideoFps(fps: Int) {
        viewModelScope.launch { preferences.setVideoFps(fps) }
    }

    fun updateGalVersion(version: String) {
        viewModelScope.launch { preferences.setGalVersion(version) }
    }

    fun updateDisplayMode(mode: String) {
        viewModelScope.launch { preferences.setDisplayMode(mode) }
    }

    fun updateMicSource(source: String) {
        viewModelScope.launch { preferences.setMicSource(source) }
    }

    fun updateBtMacOverride(mac: String) {
        viewModelScope.launch { preferences.setBtMacOverride(mac) }
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

    fun updateAlwaysInPark(enabled: Boolean) {
        viewModelScope.launch { preferences.setAlwaysInPark(enabled) }
    }

    fun updateClusterNavigation(enabled: Boolean) {
        viewModelScope.launch { preferences.setClusterNavigation(enabled) }
    }

    fun updateOverlayStatsButton(visible: Boolean) {
        viewModelScope.launch { preferences.setOverlayStatsButton(visible) }
    }

    fun updateOverlayPhoneSwitchButton(visible: Boolean) {
        viewModelScope.launch { preferences.setOverlayPhoneSwitchButton(visible) }
    }

    fun updateOverlayReconnectButton(visible: Boolean) {
        viewModelScope.launch { preferences.setOverlayReconnectButton(visible) }
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

    fun updateLogPersistEnabled(enabled: Boolean) {
        viewModelScope.launch { preferences.setLogPersistEnabled(enabled) }
    }

    fun updateLogUploadEnabled(enabled: Boolean) {
        viewModelScope.launch { preferences.setLogUploadEnabled(enabled) }
    }

    fun updateEvContributionConsent(enabled: Boolean) {
        viewModelScope.launch {
            val binding = if (enabled) {
                com.openautolink.app.diagnostics.EvContributionConsentBinding.create(
                    uiState.value.logUploadUrl,
                    uiState.value.logUploadToken,
                )?.encode() ?: run {
                    com.openautolink.app.diagnostics.EvContributionService.reportConsentInvalid(
                        "A valid HTTPS upload URL and non-empty token are required",
                    )
                    return@launch
                }
            } else null
            preferences.setEvContributionConsent(enabled, binding)
        }
    }

    fun deletePendingEvContributions() {
        viewModelScope.launch(Dispatchers.IO) {
            com.openautolink.app.diagnostics.EvContributionService.deletePendingEvContributions()
        }
    }

    fun updateSimulateIgnitionButton(enabled: Boolean) {
        viewModelScope.launch { preferences.setSimulateIgnitionButton(enabled) }
    }

    fun updateLogUploadUrl(url: String) {
        viewModelScope.launch { preferences.setLogUploadUrl(url) }
    }

    fun updateLogUploadToken(token: String) {
        viewModelScope.launch { preferences.setLogUploadToken(token) }
    }

    fun updateLogUploadDeviceLabel(label: String) {
        viewModelScope.launch { preferences.setLogUploadDeviceLabel(label) }
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

    fun updateSafeAreaInsets(top: Int, bottom: Int, left: Int, right: Int) {
        viewModelScope.launch {
            preferences.setSafeAreaTop(top)
            preferences.setSafeAreaBottom(bottom)
            preferences.setSafeAreaLeft(left)
            preferences.setSafeAreaRight(right)
        }
    }

    fun updateKeyRemap(json: String) {
        viewModelScope.launch {
            preferences.setKeyRemap(json)
            val count = if (json.isBlank()) {
                0
            } else {
                runCatching { org.json.JSONObject(json).length() }.getOrDefault(0)
            }
            com.openautolink.app.diagnostics.OalLog.i(
                "input",
                "Key remap persisted: count=$count mappings=$json",
            )
        }
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

    fun updateManualIpEnabled(enabled: Boolean) {
        viewModelScope.launch { preferences.setManualIpEnabled(enabled) }
    }

    fun updateManualIpAddress(address: String) {
        viewModelScope.launch { preferences.setManualIpAddress(address) }
    }

    override fun onCleared() {
        wppConfigServer.stop()
        super.onCleared()
    }

    /** Reconnect the AA session with current settings. Called from Settings "Save & Reconnect". */
    fun saveAndReconnect() {
        viewModelScope.launch {
            val sm = com.openautolink.app.session.SessionManager.instanceOrNull() ?: return@launch
            val codec = preferences.videoCodec.first()
            val seedIdrThresholds = preferences.seedIdrThresholds.first()
            val micSrc = preferences.micSource.first()
            val scalingMode = preferences.videoScalingMode.first()
            val hotspotSsid = preferences.hotspotSsid.first()
            val hotspotPassword = preferences.hotspotPassword.first()
            val directTransport = preferences.directTransport.first()
            val wppInterfaceName = _wppApInterfaceOverride.value
            preferences.setWppApInterface(wppInterfaceName)
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
            val galVersion = preferences.galVersion.first()
            val driveSide = preferences.driveSide.first()
            val hideClock = preferences.hideAaClock.first()
            val hideSignal = preferences.hidePhoneSignal.first()
            val hideBattery = preferences.hideBatteryLevel.first()
            val gpsForwarding = preferences.gpsForwarding.first()
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
                wppInterfaceName = wppInterfaceName,
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
                gpsForwarding = gpsForwarding,
                galVersion = galVersion,
                seedIdrThresholds = seedIdrThresholds,
            )
        }
    }
}
