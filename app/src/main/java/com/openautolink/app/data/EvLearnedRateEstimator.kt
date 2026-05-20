package com.openautolink.app.data

import com.openautolink.app.diagnostics.DiagnosticLog
import com.openautolink.app.diagnostics.OalLog
import com.openautolink.app.transport.ControlMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/**
 * Phase 3' — learned driving Wh/km estimator.
 *
 * Computes a rolling exponential moving average of energy consumption from
 * `Δ(EV_BATTERY_LEVEL × capacity) ÷ Δdistance`, where Δdistance is integrated
 * from VHAL `speedKmh × Δt`. (GM AAOS blocks `PERF_ODOMETER`, so VHAL speed is
 * the only viable distance source — it's also what the dashboard's own trip
 * computer uses.)
 *
 * Per-vehicle state (keyed by `Make|Model|Year`) is persisted to DataStore
 * as a small JSON blob so the value survives car-off / sleep / app restart.
 *
 * See docs/ev-energy-model-tuning-plan.md.
 */
class EvLearnedRateEstimator private constructor(
    private val prefs: AppPreferences,
) {

    /** Public snapshot for the UI. */
    data class Snapshot(
        val key: String? = null,
        val whPerKm: Float = 0f,
        val sampleKm: Float = 0f,
        val lastUpdateMs: Long = 0L,
        /** Reason the most recent tick was rejected (informational). */
        val lastTickStatus: String = "",
    ) {
        /** True when [whPerKm] is trustworthy enough to send to Maps. */
        val usable: Boolean get() = whPerKm in 50f..800f && sampleKm >= MIN_USABLE_KM
    }

    companion object {
        private const val TAG = "EvLearnedRate"
        private const val MIN_USABLE_KM = 1.0f
        private const val MIN_INST_WH_PER_KM = 50f   // < this is implausible
        private const val MAX_INST_WH_PER_KM = 800f  // > this too
        private const val MIN_SAMPLE_KM_PER_TICK = 0.05f
        // Restart the sample window if more than this elapses (e.g. car was
        // off, app was backgrounded, etc.). Prevents stale lastBatteryWh from
        // creating a giant Δ when the car turns back on.
        private const val MAX_TICK_GAP_MS = 15 * 60 * 1000L
        // Throttle DataStore writes so we don't thrash on every sub-second tick.
        private const val PERSIST_DEBOUNCE_MS = 5_000L

        @Volatile private var instance: EvLearnedRateEstimator? = null

        fun getInstance(prefs: AppPreferences): EvLearnedRateEstimator =
            instance ?: synchronized(this) {
                instance ?: EvLearnedRateEstimator(prefs).also { instance = it }
            }

        /**
         * Pure tick math. Mutates `s` in place, returns a short status string.
         * Exposed `internal` so [EvLearnedRateEstimatorTest] can exercise the
         * regen / outlier / gap-reset / EMA branches without DataStore or
         * coroutines. Returned status starts with `"ok:"` only for accepted
         * samples.
         */
        internal fun applyTick(
            s: VehicleState,
            vd: ControlMessage.VehicleData,
            nowElapsedMs: Long,
        ): String {
            val batteryWh = vd.evBatteryLevelWh?.toInt() ?: return "skip:noBattery"
            val speedKmh = vd.speedKmh ?: 0f
            val charging = (vd.evChargeRateW ?: 0f) > 0f

            val prevElapsed = s.lastTickElapsedMs
            val prevBattery = s.lastBatteryWh

            // Always track the latest reading so the next tick can compute Δ.
            s.lastTickElapsedMs = nowElapsedMs
            s.lastBatteryWh = batteryWh

            if (charging) return "skip:charging"
            if (prevElapsed == 0L || prevBattery <= 0) return "init"

            val dtMs = nowElapsedMs - prevElapsed
            if (dtMs <= 0 || dtMs > MAX_TICK_GAP_MS) return "skip:gap=${dtMs}ms"

            val dtH = dtMs / 3_600_000f
            val dKm = speedKmh * dtH
            if (dKm < MIN_SAMPLE_KM_PER_TICK) return "skip:dKm<$MIN_SAMPLE_KM_PER_TICK"

            // Energy delta — prefer motor-power × dt when HistoryProvider is
            // serving samples (Finding F.2: more responsive, less SOC-quantization
            // noise than batteryWh delta). Falls back to batteryWh delta when
            // the provider is patched / unavailable / returns null.
            val motorW = vd.evMotorPowerW
            val (dWh, source) = if (motorW != null && motorW > 0f) {
                (motorW * dtH) to "gt"   // ground-truth integration
            } else {
                ((prevBattery - batteryWh).toFloat()) to "bd"   // battery delta
            }
            if (dWh <= 0f) return "skip:regen(dWh=${dWh.toInt()})"

            val instWhPerKm = dWh / dKm
            if (instWhPerKm < MIN_INST_WH_PER_KM || instWhPerKm > MAX_INST_WH_PER_KM) {
                return "skip:outlier(${instWhPerKm.toInt()},src=$source)"
            }

            // EMA — alpha scales with sample distance so a long stretch counts more
            // than a 50 m blip.
            val alpha = (dKm / 5f).coerceIn(0.01f, 0.3f)
            s.whPerKm = if (s.whPerKm <= 0f) instWhPerKm
                        else (1f - alpha) * s.whPerKm + alpha * instWhPerKm
            s.sampleKm += dKm
            s.lastUpdateMs = System.currentTimeMillis()

            return "ok:$source inst=${instWhPerKm.toInt()} ema=${s.whPerKm.toInt()}"
        }
    }

    /** Mutable per-vehicle state. Protected by the [stateLock] for write
     *  ordering; individual fields are also `@Volatile` so the UI snapshot
     *  reader sees consistent-enough values without taking the lock. */
    internal class VehicleState {
        @Volatile var whPerKm: Float = 0f          // 0 = no data yet
        @Volatile var sampleKm: Float = 0f
        @Volatile var lastUpdateMs: Long = 0L
        @Volatile var lastBatteryWh: Int = 0       // last absolute battery (Wh)
        @Volatile var lastTickElapsedMs: Long = 0L // SystemClock.elapsedRealtime
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val states = ConcurrentHashMap<String, VehicleState>()
    private val loadMutex = Mutex()
    private val stateLock = Any()
    @Volatile private var loaded = false
    @Volatile private var pendingPersist: Job? = null

    private val _activeSnapshot = MutableStateFlow(Snapshot())
    val activeSnapshot: StateFlow<Snapshot> = _activeSnapshot.asStateFlow()

    private suspend fun ensureLoaded() {
        if (loaded) return
        loadMutex.withLock {
            if (loaded) return
            try {
                val raw = prefs.evLearnedRatesJson.first()
                if (raw.isNotBlank() && raw != "{}") {
                    val obj = JSONObject(raw)
                    val keys = obj.keys()
                    while (keys.hasNext()) {
                        val k = keys.next()
                        val v = obj.optJSONObject(k) ?: continue
                        states[k] = VehicleState().apply {
                            whPerKm = v.optDouble("whPerKm", 0.0).toFloat()
                            sampleKm = v.optDouble("sampleKm", 0.0).toFloat()
                            lastUpdateMs = v.optLong("lastUpdateMs", 0L)
                        }
                    }
                    OalLog.i(TAG, "loaded ${states.size} per-vehicle learned rates")
                }
            } catch (e: Exception) {
                OalLog.w(TAG, "load failed: ${e.message}")
            }
            loaded = true
        }
    }

    private fun keyOf(vd: ControlMessage.VehicleData): String? {
        val mk = vd.carMake?.takeIf { it.isNotBlank() } ?: return null
        val md = vd.carModel?.takeIf { it.isNotBlank() } ?: return null
        val yr = vd.carYear?.takeIf { it.isNotBlank() } ?: "?"
        return "$mk|$md|$yr"
    }

    /**
     * Feed one VHAL tick. No-op when the data is too thin to learn from.
     * `nowElapsedMs` should be `SystemClock.elapsedRealtime()` (monotonic).
     */
    fun onVehicleTick(vd: ControlMessage.VehicleData, nowElapsedMs: Long) {
        if (!loaded) {
            scope.launch { ensureLoaded(); onVehicleTickLoaded(vd, nowElapsedMs) }
            return
        }
        onVehicleTickLoaded(vd, nowElapsedMs)
    }

    private fun onVehicleTickLoaded(vd: ControlMessage.VehicleData, nowElapsedMs: Long) {
        val key = keyOf(vd) ?: return
        val s = states.getOrPut(key) { VehicleState() }
        val status = applyTick(s, vd, nowElapsedMs)
        publishActive(key, s, status)
        if (status.startsWith("ok:")) schedulePersist()
    }

    private fun publishActive(key: String, s: VehicleState, status: String) {
        _activeSnapshot.value = Snapshot(
            key = key,
            whPerKm = s.whPerKm,
            sampleKm = s.sampleKm,
            lastUpdateMs = s.lastUpdateMs,
            lastTickStatus = status,
        )
    }

    /** Returns the snapshot for [key], or empty when never seen. */
    fun snapshotFor(key: String?): Snapshot {
        if (key.isNullOrBlank()) return Snapshot()
        val s = synchronized(states) { states[key] } ?: return Snapshot()
        return Snapshot(
            key = key,
            whPerKm = s.whPerKm,
            sampleKm = s.sampleKm,
            lastUpdateMs = s.lastUpdateMs,
            lastTickStatus = "",
        )
    }

    /** Clear learned state for the currently active vehicle (or all). */
    fun reset(key: String? = null) {
        synchronized(states) {
            if (key == null) states.clear()
            else states.remove(key)
        }
        _activeSnapshot.value = Snapshot(key = key, lastTickStatus = "reset")
        schedulePersist(immediate = true)
        DiagnosticLog.i("ev_learned", "reset key=${key ?: "ALL"}")
    }

    private fun schedulePersist(immediate: Boolean = false) {
        pendingPersist?.cancel()
        pendingPersist = scope.launch {
            if (!immediate) kotlinx.coroutines.delay(PERSIST_DEBOUNCE_MS)
            persistNow()
        }
    }

    private suspend fun persistNow() {
        val obj = JSONObject()
        synchronized(states) {
            for ((k, s) in states) {
                if (s.whPerKm <= 0f && s.sampleKm <= 0f) continue
                obj.put(k, JSONObject().apply {
                    put("whPerKm", s.whPerKm.toDouble())
                    put("sampleKm", s.sampleKm.toDouble())
                    put("lastUpdateMs", s.lastUpdateMs)
                })
            }
        }
        try {
            prefs.setEvLearnedRatesJson(obj.toString())
        } catch (e: Exception) {
            OalLog.w(TAG, "persist failed: ${e.message}")
        }
    }
}
