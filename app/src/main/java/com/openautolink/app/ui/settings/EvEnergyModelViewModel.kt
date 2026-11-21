package com.openautolink.app.ui.settings

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.openautolink.app.data.AppPreferences
import com.openautolink.app.data.EvLearnedRateEstimator
import com.openautolink.app.data.EvProfilesRepository
import com.openautolink.app.session.SessionManager
import com.openautolink.app.transport.ControlMessage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

/**
 * Backs [EvEnergyModelScreen]. State is driven entirely by [AppPreferences];
 * the live readout observes the existing process-owned vehicle stream without requiring a phone.
 *
 * See docs/ev-energy-model-tuning-plan.md.
 */
class EvEnergyModelViewModel(app: Application) : AndroidViewModel(app) {

    data class UiState(
        val tuningEnabled: Boolean = AppPreferences.DEFAULT_EV_TUNING_ENABLED,
        val drivingMode: String = AppPreferences.DEFAULT_EV_DRIVING_MODE,
        val drivingWhPerKm: Int = AppPreferences.DEFAULT_EV_DRIVING_WH_PER_KM,
        val drivingMultiplierPct: Int = AppPreferences.DEFAULT_EV_DRIVING_MULTIPLIER_PCT,
        val auxWhPerKmX10: Int = AppPreferences.DEFAULT_EV_AUX_WH_PER_KM_X10,
        val aeroCoefX100: Int = AppPreferences.DEFAULT_EV_AERO_COEF_X100,
        val reservePct: Int = AppPreferences.DEFAULT_EV_RESERVE_PCT,
        val maxChargeKw: Int = AppPreferences.DEFAULT_EV_MAX_CHARGE_KW,
        val maxDischargeKw: Int = AppPreferences.DEFAULT_EV_MAX_DISCHARGE_KW,
        // Phase 2 / 2b
        val useEpaBaseline: Boolean = AppPreferences.DEFAULT_EV_USE_EPA_BASELINE,
        val profilesLastUpdateMs: Long = 0L,
        val profilesUpdateUrl: String = AppPreferences.DEFAULT_EV_PROFILES_UPDATE_URL,
        val refreshing: Boolean = false,
        val distanceUnits: String = "auto",
        val learnEvaluationEnabled: Boolean = false,
        val learnEvaluationLoaded: Boolean = false,
    )

    /** Result of the bundled-profile lookup for the currently connected car. */
    data class ProfileMatch(
        val matched: Boolean = false,
        val displayName: String? = null,
        val drivingWhPerKm: Int? = null,
        val maxChargeKw: Int? = null,
        val carMake: String? = null,
        val carModel: String? = null,
        val carYear: String? = null,
    )

    /**
     * Snapshot of the most recent VHAL data the live-readout card displays.
     * Rates are local previews. A selected override is not proof it reached the phone.
     */
    data class LiveReadout(
        val hasData: Boolean = false,
        val batteryWh: Int = 0,
        val capacityWh: Int = 0,
        val rangeM: Int = 0,
        val chargeW: Int = 0,
        val derivedWhPerKm: Float = 0f,
        val effectiveWhPerKm: Float = 0f,
        val socPct: Float = 0f,
        val batteryAvailable: Boolean = hasData,
        val rangeAvailable: Boolean = hasData,
    )

    private val prefs = AppPreferences.getInstance(app.applicationContext)
    private val learnedEstimator = EvLearnedRateEstimator.getInstance(prefs)

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private val _readout = MutableStateFlow(LiveReadout())
    val readout: StateFlow<LiveReadout> = _readout.asStateFlow()

    private val _profileMatch = MutableStateFlow(ProfileMatch())
    val profileMatch: StateFlow<ProfileMatch> = _profileMatch.asStateFlow()

    private val _learned = MutableStateFlow(EvLearnedRateEstimator.Snapshot())
    val learned: StateFlow<EvLearnedRateEstimator.Snapshot> = _learned.asStateFlow()

    internal val learnEvaluationStatus = com.openautolink.app.input.ProcessVehicleDataRuntime.learnQualificationStatus

    /** Snackbar / toast events. Cleared by the UI after read. */
    private val _events = MutableStateFlow<String?>(null)
    val events: StateFlow<String?> = _events.asStateFlow()
    private var lastVehicle: ControlMessage.VehicleData? = null

    init {
        viewModelScope.launch {
            combine(
                prefs.evTuningEnabled,
                prefs.evDrivingMode,
                prefs.evDrivingWhPerKm,
                prefs.evDrivingMultiplierPct,
                prefs.evAuxWhPerKmX10,
                prefs.evAeroCoefX100,
                prefs.evReservePct,
                prefs.evMaxChargeKw,
                prefs.evMaxDischargeKw,
                prefs.evUseEpaBaseline,
                prefs.evProfilesLastUpdateMs,
                prefs.evProfilesUpdateUrl,
                prefs.distanceUnits,
                prefs.evLearnEvaluationEnabled,
            ) { values: Array<Any> ->
                UiState(
                    tuningEnabled = values[0] as Boolean,
                    drivingMode = values[1] as String,
                    drivingWhPerKm = values[2] as Int,
                    drivingMultiplierPct = values[3] as Int,
                    auxWhPerKmX10 = values[4] as Int,
                    aeroCoefX100 = values[5] as Int,
                    reservePct = values[6] as Int,
                    maxChargeKw = values[7] as Int,
                    maxDischargeKw = values[8] as Int,
                    useEpaBaseline = values[9] as Boolean,
                    profilesLastUpdateMs = values[10] as Long,
                    profilesUpdateUrl = values[11] as String,
                    refreshing = _state.value.refreshing,
                    distanceUnits = values[12] as String,
                    learnEvaluationEnabled = values[13] as Boolean,
                    learnEvaluationLoaded = true,
                )
            }.collect { _state.value = it }
        }

        // Read-only observation of the existing producer: no extra VHAL subscription/consumer.
        viewModelScope.launch {
            val source = com.openautolink.app.input.ProcessVehicleDataRuntime.latestVehicleData()
                ?: SessionManager.instanceOrNull()?.vehicleData
            source?.collect { vd ->
                lastVehicle = vd
                _readout.value = computeReadout(vd, _state.value)
                _profileMatch.value = computeProfileMatch(vd)
            }
        }
        // Mirror the active learned-rate snapshot so the UI can render it.
        // Bind directly to the estimator singleton (not through SessionManager)
        // so the screen works even when opened before a session exists.
        viewModelScope.launch {
            learnedEstimator.activeSnapshot.collect { _learned.value = it }
        }
        // Recompute readout when sliders change (so effectiveWhPerKm tracks UI).
        viewModelScope.launch {
            _state.collect { ui ->
                val vd = lastVehicle
                if (vd != null) _readout.value = computeReadout(vd, ui)
            }
        }
        // Expire the display even if a disconnected producer emits nothing further. This only
        // revalidates the retained observation; it never re-ages or rereads vehicle properties.
        viewModelScope.launch {
            while (isActive) {
                delay(5_000)
                lastVehicle?.let { _readout.value = computeReadout(it, _state.value) }
            }
        }
    }

    private fun computeReadout(vd: ControlMessage.VehicleData, ui: UiState): LiveReadout {
        // Reuse the production source/status/generation/freshness boundary. Nonnull cached
        // scalars alone must never turn this screen Ready after the car source goes stale.
        val qualified = com.openautolink.app.carplay.OalCarPlayVehicleSnapshotAdapter(
            source = { MutableStateFlow(vd) },
        ).snapshot()
        val battery = qualified?.currentChargeWh?.takeIf { it <= Int.MAX_VALUE }?.toInt()
        val capacityWh = qualified?.maxChargeWh?.takeIf { it <= Int.MAX_VALUE }?.toInt() ?: 0
        val range = vd.rangeKm?.takeIf { qualified?.rangeKm != null }?.let { (it * 1000).toInt() }
        val batteryWh = battery ?: 0
        val rangeM = range ?: 0
        val chargeW = vd.evChargeRateW?.toInt() ?: 0
        val derived = if (rangeM > 0) (batteryWh.toFloat() / rangeM.toFloat()) * 1000f else 0f
        val effective = if (!ui.tuningEnabled) derived else when (ui.drivingMode) {
            AppPreferences.EV_DRIVING_MODE_MANUAL -> ui.drivingWhPerKm.toFloat()
            AppPreferences.EV_DRIVING_MODE_MULTIPLIER -> derived * (ui.drivingMultiplierPct / 100f)
            AppPreferences.EV_DRIVING_MODE_LEARNED -> {
                val s = _learned.value
                if (s.usable) s.whPerKm else derived
            }
            else -> derived
        }
        return LiveReadout(
            hasData = battery != null && range != null,
            batteryWh = batteryWh,
            capacityWh = capacityWh,
            rangeM = rangeM,
            chargeW = chargeW,
            derivedWhPerKm = derived,
            effectiveWhPerKm = effective,
            socPct = if (capacityWh > 0) (batteryWh.toFloat() / capacityWh.toFloat()) * 100f else 0f,
            batteryAvailable = battery != null,
            rangeAvailable = range != null,
        )
    }

    fun setTuningEnabled(v: Boolean) = viewModelScope.launch { prefs.setEvTuningEnabled(v) }
    fun setDrivingMode(v: String) = viewModelScope.launch { prefs.setEvDrivingMode(v) }
    fun setDrivingWhPerKm(v: Int) = viewModelScope.launch { prefs.setEvDrivingWhPerKm(v) }
    fun setDrivingMultiplierPct(v: Int) = viewModelScope.launch { prefs.setEvDrivingMultiplierPct(v) }
    fun setAuxWhPerKmX10(v: Int) = viewModelScope.launch { prefs.setEvAuxWhPerKmX10(v) }
    fun setAeroCoefX100(v: Int) = viewModelScope.launch { prefs.setEvAeroCoefX100(v) }
    fun setReservePct(v: Int) = viewModelScope.launch { prefs.setEvReservePct(v) }
    fun setMaxChargeKw(v: Int) = viewModelScope.launch { prefs.setEvMaxChargeKw(v) }
    fun setMaxDischargeKw(v: Int) = viewModelScope.launch { prefs.setEvMaxDischargeKw(v) }

    fun resetToDefaults() {
        viewModelScope.launch {
            prefs.setEvDrivingMode(AppPreferences.DEFAULT_EV_DRIVING_MODE)
            prefs.setEvDrivingWhPerKm(AppPreferences.DEFAULT_EV_DRIVING_WH_PER_KM)
            prefs.setEvDrivingMultiplierPct(AppPreferences.DEFAULT_EV_DRIVING_MULTIPLIER_PCT)
            prefs.setEvAuxWhPerKmX10(AppPreferences.DEFAULT_EV_AUX_WH_PER_KM_X10)
            prefs.setEvAeroCoefX100(AppPreferences.DEFAULT_EV_AERO_COEF_X100)
            prefs.setEvReservePct(AppPreferences.DEFAULT_EV_RESERVE_PCT)
            prefs.setEvMaxChargeKw(AppPreferences.DEFAULT_EV_MAX_CHARGE_KW)
            prefs.setEvMaxDischargeKw(AppPreferences.DEFAULT_EV_MAX_DISCHARGE_KW)
            _events.value = "Reset to defaults"
        }
    }

    fun sendNow() {
        val sm = SessionManager.instanceOrNull()
        if (sm == null) {
            _events.value = "Connect to phone first."
            return
        }
        val ok = sm.forceSendEnergyModel()
        _events.value = if (ok) "Vehicle-data resend requested" else "No EV battery data yet."
    }

    fun consumeEvent() { _events.value = null }

    fun setLearnEvaluationEnabled(enabled: Boolean) {
        viewModelScope.launch {
            try {
                prefs.setEvLearnEvaluationEnabled(enabled)
                if (_events.value == "Could not save Learn Mode.") _events.value = null
            } catch (_: java.io.IOException) {
                _events.value = "Could not save Learn Mode."
            }
        }
    }

    /** Phase 3' — reset learned EMA for the active vehicle. */
    fun resetLearnedRate() {
        // Reset the active key (whatever the estimator last saw); falls back
        // to "all" if nothing has been observed yet.
        val activeKey = learnedEstimator.activeSnapshot.value.key
        viewModelScope.launch {
            _events.value = when (learnedEstimator.reset(activeKey)) {
                EvLearnedRateEstimator.ResetResult.COMPLETED -> "Learned rate reset"
                EvLearnedRateEstimator.ResetResult.PERSIST_PENDING -> "Learned rate cleared; save will retry"
                EvLearnedRateEstimator.ResetResult.REJECTED_BUSY -> "Reset busy; try again"
                EvLearnedRateEstimator.ResetResult.REJECTED_STOPPING -> "Reset unavailable while stopping"
            }
        }
    }

    // ── Phase 2 / 2b ────────────────────────────────────────────────

    fun setUseEpaBaseline(v: Boolean) = viewModelScope.launch { prefs.setEvUseEpaBaseline(v) }

    fun setProfilesUpdateUrl(url: String) =
        viewModelScope.launch { prefs.setEvProfilesUpdateUrl(url.trim()) }

    /** Apply matched EPA values to the manual-mode sliders and turn tuning ON. */
    fun applyEpaValues() {
        val match = _profileMatch.value
        if (!match.matched) {
            _events.value = "No EPA profile for this vehicle yet."
            return
        }
        viewModelScope.launch {
            prefs.setEvTuningEnabled(true)
            prefs.setEvDrivingMode(AppPreferences.EV_DRIVING_MODE_MANUAL)
            match.drivingWhPerKm?.let { prefs.setEvDrivingWhPerKm(it) }
            match.maxChargeKw?.let { prefs.setEvMaxChargeKw(it) }
            _events.value = "Applied EPA values for ${match.displayName ?: "this vehicle"}"
        }
    }

    /** Phase 2b: opt-in network refresh of the profile DB. */
    fun refreshProfilesFromNetwork() {
        if (_state.value.refreshing) return
        viewModelScope.launch {
            _state.value = _state.value.copy(refreshing = true)
            val ctx = getApplication<Application>().applicationContext
            val url = _state.value.profilesUpdateUrl
            val n = EvProfilesRepository.getInstance(ctx).refreshFromNetwork(url)
            _state.value = _state.value.copy(refreshing = false)
            if (n != null) {
                prefs.setEvProfilesLastUpdateMs(System.currentTimeMillis())
                _events.value = "Updated EV profile database ($n profiles)"
                // Re-run match against new data.
                lastVehicle?.let { _profileMatch.value = computeProfileMatch(it) }
            } else {
                _events.value = "Update failed — check your internet connection."
            }
        }
    }

    private fun computeProfileMatch(vd: ControlMessage.VehicleData): ProfileMatch {
        val ctx = getApplication<Application>().applicationContext
        val profile = EvProfilesRepository.getInstance(ctx)
            .lookup(vd.carMake, vd.carModel, vd.carYear)
        return ProfileMatch(
            matched = profile != null,
            displayName = profile?.displayName ?: profile?.key,
            drivingWhPerKm = profile?.drivingWhPerKm,
            maxChargeKw = profile?.maxChargeKw,
            carMake = vd.carMake,
            carModel = vd.carModel,
            carYear = vd.carYear,
        )
    }
}
