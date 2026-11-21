package com.openautolink.app.data

import com.openautolink.app.diagnostics.DiagnosticLog
import com.openautolink.app.diagnostics.OalLog
import com.openautolink.app.transport.ControlMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Phase 3' — learned driving Wh/km estimator.
 *
 * Computes a rolling exponential moving average of energy consumption from
 * `Δ(EV_BATTERY_LEVEL × capacity) ÷ Δdistance`, where Δdistance is integrated
 * from VHAL `speedKmh × Δt`. (GM AAOS blocks `PERF_ODOMETER`, so VHAL speed is
 * the only viable distance source — it's also what the dashboard's own trip
 * computer uses.)
 *
 * Per-configuration state (model plus capacity band, estimator revision and
 * energy basis; never VIN) is persisted to DataStore
 * as a small JSON blob so the value survives car-off / sleep / app restart.
 *
 * See docs/ev-energy-model-tuning-plan.md.
 */
class EvLearnedRateEstimator private constructor(
    private val store: Store,
    private val scope: CoroutineScope,
    private val config: Config,
) {

    internal data class Config(
        val persistDebounceMs: Long = PERSIST_DEBOUNCE_MS,
        val persistMaxDelayMs: Long = 60_000L,
        val persistRetryMs: Long = 5_000L,
        val maxEntries: Int = 32,
        val maxJsonBytes: Int = 16 * 1024,
        val commandCapacity: Int = 64,
        val maxPersistAttempts: Int = 3,
    ) {
        init {
            require(persistDebounceMs >= 0L)
            require(persistMaxDelayMs >= 0L)
            require(persistRetryMs >= 0L)
            require(maxEntries >= 0)
            require(maxJsonBytes >= 2) { "maxJsonBytes must fit an empty JSON object" }
            require(commandCapacity > 0)
            require(maxPersistAttempts > 0)
        }
    }

    internal interface Store {
        suspend fun load(): String
        suspend fun save(value: String)
    }

    private class PreferencesStore(private val prefs: AppPreferences) : Store {
        override suspend fun load(): String = prefs.evLearnedRatesJson.first()
        override suspend fun save(value: String) = prefs.setEvLearnedRatesJson(value)
    }

    /** Public snapshot for the UI. */
    data class Snapshot(
        val key: String? = null,
        val whPerKm: Float = 0f,
        val sampleKm: Float = 0f,
        val lastUpdateMs: Long = 0L,
        /** Reason the most recent tick was rejected (informational). */
        val lastTickStatus: String = "",
    ) {
        /** True when the learner has enough bounded evidence to report readiness.
         *  Wire activation is a separate safety decision. */
        val usable: Boolean get() = whPerKm in 50f..800f && sampleKm >= MIN_USABLE_KM
    }

    data class RuntimeState(
        val accepting: Boolean = true,
        val droppedCommands: Long = 0,
        val stateEntryCount: Int = 0,
        val dirty: Boolean = false,
        val persistFailures: Long = 0,
        val retryExhausted: Boolean = false,
    )

    data class StopResult(val finalSaveSucceeded: Boolean)

    enum class ResetResult {
        COMPLETED,
        PERSIST_PENDING,
        REJECTED_BUSY,
        REJECTED_STOPPING,
    }

    class FinalSaveException(message: String) : IllegalStateException(message)

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
        internal const val MOTOR_POWER_OBSERVATION = "EV_MOTOR_POWER"
        private const val MAX_MOTOR_OBSERVATION_AGE_MS = 10_000L
        private const val MAX_VHAL_OBSERVATION_AGE_MS = 10_000L
        private const val SPEED_OBSERVATION = "PERF_VEHICLE_SPEED"
        private const val BATTERY_OBSERVATION = "EV_BATTERY_LEVEL"
        // Throttle DataStore writes so we don't thrash on every sub-second tick.
        private const val PERSIST_DEBOUNCE_MS = 5_000L
        private const val ESTIMATOR_REVISION = "rev2"
        private const val ENERGY_BASIS = "absolute-wh"
        private const val CAPACITY_BAND_WH = 5_000

        @Volatile private var instance: EvLearnedRateEstimator? = null

        fun getInstance(prefs: AppPreferences): EvLearnedRateEstimator =
            instance ?: synchronized(this) {
                instance ?: EvLearnedRateEstimator(
                    PreferencesStore(prefs),
                    CoroutineScope(SupervisorJob() + Dispatchers.Default),
                    Config(),
                ).also { instance = it }
            }

        internal fun createForTest(
            store: Store,
            scope: CoroutineScope,
            config: Config = Config(),
        ): EvLearnedRateEstimator = EvLearnedRateEstimator(store, scope, config)

        /** Fail closed when configuration identity is incomplete or implausible. */
        internal fun calibrationKey(vd: ControlMessage.VehicleData): String? {
            val make = vd.carMake?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            val model = vd.carModel?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            val year = vd.carYear?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            val capacity = vd.evBatteryCapacityWh?.takeIf { it.isFinite() && it > 0f } ?: return null
            val band = (capacity.toInt() / CAPACITY_BAND_WH) * CAPACITY_BAND_WH
            return listOf(make, model, year, "cap${band}-${band + CAPACITY_BAND_WH}", ESTIMATOR_REVISION, ENERGY_BASIS)
                .joinToString("|")
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
            val batteryRaw = vd.evBatteryLevelWh ?: return "skip:noBattery"
            if (!batteryRaw.isFinite() || batteryRaw < 0f) {
                s.resetTransient()
                return "skip:invalidBattery"
            }
            if (!validOptionalObservation(vd, BATTERY_OBSERVATION, nowElapsedMs)) {
                s.resetTransient()
                return "skip:invalidBatteryObservation"
            }
            val batteryWh = batteryRaw.toInt()
            val speedKmh = vd.speedKmh ?: 0f
            if (!speedKmh.isFinite() || speedKmh < 0f) {
                s.resetTransient()
                return "skip:invalidSpeed"
            }
            if (!validOptionalObservation(vd, SPEED_OBSERVATION, nowElapsedMs)) {
                s.resetTransient()
                return "skip:invalidSpeedObservation"
            }
            val charging = vd.evChargeRateW?.let { it.isFinite() && it > 0f } == true ||
                vd.evChargeState == 2 || vd.chargePortConnected == true

            val chargingBoundary = changedWhenKnown(s.lastChargeState, vd.evChargeState) ||
                changedWhenKnown(s.lastChargePortConnected, vd.chargePortConnected) ||
                changedWhenKnown(s.lastChargePortOpen, vd.chargePortOpen)
            s.lastChargeState = vd.evChargeState
            s.lastChargePortConnected = vd.chargePortConnected
            s.lastChargePortOpen = vd.chargePortOpen

            val prevElapsed = s.lastTickElapsedMs
            val prevBattery = s.lastBatteryWh
            val prevSpeedKmh = s.lastSpeedKmh

            // Advance only the interval baseline here. Window baselines are
            // retained until at least 50 m has accumulated.
            s.lastTickElapsedMs = nowElapsedMs
            s.lastBatteryWh = batteryWh
            s.lastSpeedKmh = speedKmh

            if (chargingBoundary) {
                s.resetWindow(batteryWh)
                return "skip:charging-boundary"
            }
            if (charging) {
                s.resetWindow(batteryWh)
                return "skip:charging"
            }
            if (prevElapsed == 0L || prevBattery <= 0) {
                s.resetWindow(batteryWh)
                return "init"
            }

            val dtMs = nowElapsedMs - prevElapsed
            if (dtMs <= 0 || dtMs > MAX_TICK_GAP_MS) {
                s.resetWindow(batteryWh)
                return "skip:gap=${dtMs}ms"
            }

            val dtH = dtMs / 3_600_000f
            val dKm = ((prevSpeedKmh + speedKmh) / 2f).coerceAtLeast(0f) * dtH
            s.windowDistanceKm += dKm

            val motorW = validatedMotorPower(vd, nowElapsedMs)
            if (motorW == null) {
                s.windowMotorPowerFullyCovered = false
            } else {
                s.windowMotorEnergyWh += motorW * dtH
            }
            if (s.windowDistanceKm + 1e-6f < MIN_SAMPLE_KM_PER_TICK) {
                return "accum:dKm=${s.windowDistanceKm}"
            }

            val windowDistanceKm = s.windowDistanceKm
            val batteryDeltaWh = (s.windowStartBatteryWh - batteryWh).toFloat()
            val useMotorPower = s.windowMotorPowerFullyCovered
            val dWh = if (useMotorPower) s.windowMotorEnergyWh else batteryDeltaWh
            val source = if (useMotorPower) "gt" else "bd"
            s.resetWindow(batteryWh)
            if (dWh <= 0f) return "skip:regen(dWh=${dWh.toInt()})"

            val instWhPerKm = dWh / windowDistanceKm
            if (instWhPerKm < MIN_INST_WH_PER_KM || instWhPerKm > MAX_INST_WH_PER_KM) {
                return "skip:outlier(${instWhPerKm.toInt()},src=$source)"
            }

            // EMA — alpha scales with sample distance so a long stretch counts more
            // than a 50 m blip.
            val alpha = (windowDistanceKm / 5f).coerceIn(0.01f, 0.3f)
            s.whPerKm = if (s.whPerKm <= 0f) instWhPerKm
                        else (1f - alpha) * s.whPerKm + alpha * instWhPerKm
            s.sampleKm += windowDistanceKm
            s.lastUpdateMs = System.currentTimeMillis()

            return "ok:$source inst=${instWhPerKm.toInt()} ema=${s.whPerKm.toInt()}"
        }

        private fun <T> changedWhenKnown(previous: T?, current: T?): Boolean =
            previous != null && current != null && previous != current

        private fun validatedMotorPower(
            vd: ControlMessage.VehicleData,
            nowElapsedMs: Long,
        ): Float? {
            val value = vd.evMotorPowerW?.takeIf { it.isFinite() } ?: return null
            val observation = vd.evObservationMetadata[MOTOR_POWER_OBSERVATION] ?: return null
            if (observation.status != null && observation.status != 0) return null
            val observedElapsedMs = observation.timestampElapsedNanos
                ?.div(1_000_000L)
                ?: observation.receivedElapsedMs
            val ageMs = nowElapsedMs - observedElapsedMs
            if (ageMs !in 0L..MAX_MOTOR_OBSERVATION_AGE_MS) return null
            return value
        }

        private fun validOptionalObservation(
            vd: ControlMessage.VehicleData,
            key: String,
            nowElapsedMs: Long,
        ): Boolean {
            val observation = vd.evObservationMetadata[key] ?: return true
            if (observation.status != null && observation.status != 0) return false
            val observedElapsedMs = observation.timestampElapsedNanos
                ?.div(1_000_000L)
                ?: observation.receivedElapsedMs
            if (observedElapsedMs == 0L && observation.timestampElapsedNanos == null) return true
            return nowElapsedMs - observedElapsedMs in 0L..MAX_VHAL_OBSERVATION_AGE_MS
        }
    }

    /** Mutable per-vehicle state, owned by the command actor. The volatile
     * fields also support direct pure-math tests and immutable UI snapshots. */
    internal class VehicleState {
        // In-memory proof attached to the existing EMA, never persisted or used by its math.
        var qualificationInput: QualificationInput? = null
        var qualifiedWindow = false
        var qualificationScope: EvLearnQualification.Scope? = null
        @Volatile var whPerKm: Float = 0f          // 0 = no data yet
        @Volatile var sampleKm: Float = 0f
        @Volatile var lastUpdateMs: Long = 0L
        @Volatile var lastBatteryWh: Int = 0       // last absolute battery (Wh)
        @Volatile var lastTickElapsedMs: Long = 0L // SystemClock.elapsedRealtime
        @Volatile var lastSpeedKmh: Float = 0f
        internal var lastChargeState: Int? = null
        internal var lastChargePortConnected: Boolean? = null
        internal var lastChargePortOpen: Boolean? = null
        internal var lastObservedSequence: Long = 0L
        internal var windowStartBatteryWh: Int = 0
        internal var windowDistanceKm: Float = 0f
        internal var windowMotorEnergyWh: Float = 0f
        internal var windowMotorPowerFullyCovered: Boolean = true

        internal fun resetTransient() {
            qualificationInput = null
            qualifiedWindow = false
            lastBatteryWh = 0
            lastTickElapsedMs = 0L
            lastSpeedKmh = 0f
            lastChargeState = null
            lastChargePortConnected = null
            lastChargePortOpen = null
            resetWindow(0)
        }

        internal fun resetWindow(batteryWh: Int) {
            windowStartBatteryWh = batteryWh
            windowDistanceKm = 0f
            windowMotorEnergyWh = 0f
            windowMotorPowerFullyCovered = true
        }
    }

    private val states = linkedMapOf<String, VehicleState>()
    @Volatile private var publishedStates: Map<String, Snapshot> = emptyMap()
    @Volatile private var debouncePersist: Job? = null
    @Volatile private var maxDelayPersist: Job? = null
    @Volatile private var retryPersist: Job? = null
    private val accepting = AtomicBoolean(true)
    private val admissionInFlight = AtomicInteger(0)
    private val droppedCommands = AtomicLong(0)
    private val continuityGeneration = AtomicLong(0)
    private val tickAdmissionLock = Any()
    private val resetRevision = AtomicLong(0)
    private val _qualificationResetRevision = MutableStateFlow(0L)
    internal val qualificationResetRevision = _qualificationResetRevision.asStateFlow()
    internal fun currentQualificationResetRevision(): Long = resetRevision.get()
    internal data class QualificationInput(val scope: EvLearnQualification.Scope, val speedNs: Long, val packNs: Long)
    private data class QualifiedRate(val key: String, val prior: EvLearnQualification.Prior, val admittedAt: Long, val revision: Long)
    @Volatile private var qualifiedRate: QualifiedRate? = null
    private val persistFailures = AtomicLong(0)
    private val stateEntryCount = AtomicInteger(0)
    @Volatile private var dirty = false
    private var persistAttemptsForDirty = 0
    @Volatile private var retryExhausted = false
    private var activeKey: String? = null
    private var observationSequence = 0L
    private var actorContinuityGeneration = 0L

    private sealed interface Command {
        data class Tick(
            val data: ControlMessage.VehicleData,
            val elapsedMs: Long,
            val continuityGeneration: Long,
            val qualificationInput: QualificationInput?,
            val resetRevision: Long,
        ) : Command
        data class Reset(val key: String?, val done: CompletableDeferred<ResetResult>) : Command
        data class Barrier(val done: CompletableDeferred<Unit>) : Command
        data class Stop(val done: CompletableDeferred<StopResult>) : Command
        data object Persist : Command
    }

    private val commands = Channel<Command>(config.commandCapacity)

    private val _activeSnapshot = MutableStateFlow(Snapshot())
    val activeSnapshot: StateFlow<Snapshot> = _activeSnapshot.asStateFlow()
    private val _runtimeState = MutableStateFlow(RuntimeState())
    val runtimeState: StateFlow<RuntimeState> = _runtimeState.asStateFlow()

    init {
        scope.launch { runActor() }
    }

    private suspend fun runActor() {
        loadOnce()
        for (command in commands) {
            when (command) {
                is Command.Tick -> {
                    if (command.continuityGeneration != actorContinuityGeneration) {
                        states.values.forEach(VehicleState::resetTransient)
                        actorContinuityGeneration = command.continuityGeneration
                    }
                    onVehicleTickLoaded(command)
                }
                is Command.Reset -> command.done.complete(resetLoaded(command.key))
                is Command.Persist -> persistDirty()
                is Command.Barrier -> command.done.complete(Unit)
                is Command.Stop -> {
                    debouncePersist?.cancel()
                    maxDelayPersist?.cancel()
                    retryPersist?.cancel()
                    val saved = persistNow()
                    if (saved) dirty = false
                    publishRuntimeState()
                    if (saved) {
                        command.done.complete(StopResult(finalSaveSucceeded = true))
                    } else {
                        command.done.completeExceptionally(
                            FinalSaveException("final save failed with dirty learned state retained in memory"),
                        )
                    }
                    commands.close()
                }
            }
        }
    }

    private suspend fun loadOnce() {
        try {
            val raw = store.load()
            if (raw.isNotBlank() && raw != "{}") {
                val obj = Json.parseToJsonElement(raw).jsonObject
                for ((k, element) in obj) {
                    val v = runCatching { element.jsonObject }.getOrNull() ?: continue
                    states[k] = VehicleState().apply {
                        whPerKm = v["whPerKm"]?.jsonPrimitive?.floatOrNull ?: 0f
                        sampleKm = v["sampleKm"]?.jsonPrimitive?.floatOrNull ?: 0f
                        lastUpdateMs = v["lastUpdateMs"]?.jsonPrimitive?.longOrNull ?: 0L
                    }
                }
                if (enforceEntryLimit()) markDirty()
                publishStateIndex()
                OalLog.i(TAG, "loaded ${states.size} per-vehicle learned rates")
            }
        } catch (e: Exception) {
            OalLog.w(TAG, "load failed: ${e.message}")
        }
    }

    private fun keyOf(vd: ControlMessage.VehicleData): String? = calibrationKey(vd)

    /**
     * Feed one VHAL tick. No-op when the data is too thin to learn from.
     * `nowElapsedMs` should be `SystemClock.elapsedRealtime()` (monotonic).
     */
    fun onVehicleTick(vd: ControlMessage.VehicleData, nowElapsedMs: Long): Boolean =
        onProcessVehicleTick(vd, nowElapsedMs, null, -1)

    internal fun onProcessVehicleTick(
        vd: ControlMessage.VehicleData, nowElapsedMs: Long, input: QualificationInput?, expectedResetRevision: Long,
    ): Boolean {
        if (!accepting.get()) return rejectAdmission()
        admissionInFlight.incrementAndGet()
        return try {
            if (!accepting.get()) return rejectAdmission()
            val accepted = synchronized(tickAdmissionLock) {
                val revision = resetRevision.get()
                val admitted = commands.trySend(
                    Command.Tick(vd, nowElapsedMs, continuityGeneration.get(),
                        input?.takeIf { expectedResetRevision == revision }, revision),
                ).isSuccess
                if (!admitted) continuityGeneration.incrementAndGet()
                admitted
            }
            if (!accepted) rejectAdmission() else true
        } finally {
            admissionInFlight.decrementAndGet()
        }
    }

    private fun rejectAdmission(): Boolean {
        droppedCommands.incrementAndGet()
        publishRuntimeState()
        return false
    }


    private fun onVehicleTickLoaded(tick: Command.Tick) {
        val vd = tick.data
        val key = keyOf(vd)
        if (key == null) {
            qualifiedRate = null
            activeKey?.let { states[it]?.resetTransient() }
            activeKey = null
            publishRuntimeState()
            return
        }
        if (activeKey != key) {
            activeKey?.let { states[it]?.resetTransient() }
            states[key]?.resetTransient()
            activeKey = key
        }
        if (config.maxEntries == 0) {
            activeKey = null
            publishRuntimeState()
            return
        }
        val s = states.getOrPut(key) { VehicleState() }
        s.lastObservedSequence = ++observationSequence
        val evicted = enforceEntryLimit()
        val input = tick.qualificationInput
        val previous = s.qualificationInput
        val previousRate = s.whPerKm
        val proven = input != null && previous != null && input.scope == previous.scope &&
            s.qualifiedWindow && input.speedNs >= previous.speedNs && input.packNs >= previous.packNs &&
            tick.elapsedMs - s.lastTickElapsedMs in 1..10_000
        val status = applyTick(s, vd, tick.elapsedMs)
        s.qualificationInput = input
        if (status.startsWith("ok:")) {
            // Battery delta only: HistoryProvider power has different provenance. A persisted
            // or mixed EMA can never gain exact proof just by accepting another sample.
            s.qualificationScope = input?.scope?.takeIf {
                proven && status.startsWith("ok:bd ") && (previousRate <= 0f || s.qualificationScope == it)
            }
            qualifiedRate = if (input != null && s.qualificationScope != null && s.sampleKm >= MIN_USABLE_KM) {
                QualifiedRate(key, EvLearnQualification.Prior(input.scope,
                    maxOf(input.speedNs, input.packNs) / 1_000_000L, s.whPerKm.toDouble()), tick.elapsedMs, tick.resetRevision)
            } else null
        }
        s.qualifiedWindow = when {
            input == null || s.lastTickElapsedMs != tick.elapsedMs -> false
            status == "init" || status.startsWith("ok:") || status.startsWith("skip:gap=") ||
                status.startsWith("skip:regen(") || status.startsWith("skip:outlier(") ||
                status.startsWith("skip:charging") -> true // applyTick opened a new anchor
            else -> proven
        }
        if (evicted || status.startsWith("ok:")) markDirty()
        publishActive(key, s, status)
        publishRuntimeState()
    }

    private fun publishActive(key: String, s: VehicleState, status: String) {
        _activeSnapshot.value = Snapshot(
            key = key,
            whPerKm = s.whPerKm,
            sampleKm = s.sampleKm,
            lastUpdateMs = s.lastUpdateMs,
            lastTickStatus = status,
        )
        publishStateIndex()
    }

    private fun publishStateIndex() {
        publishedStates = states.mapValues { (key, s) ->
            Snapshot(key, s.whPerKm, s.sampleKm, s.lastUpdateMs)
        }
        stateEntryCount.set(states.size)
    }

    private fun publishRuntimeState() {
        _runtimeState.value = RuntimeState(
            accepting = accepting.get(),
            droppedCommands = droppedCommands.get(),
            stateEntryCount = stateEntryCount.get(),
            dirty = dirty,
            persistFailures = persistFailures.get(),
            retryExhausted = retryExhausted,
        )
    }

    /** Returns the snapshot for [key], or empty when never seen. */
    fun snapshotFor(key: String?): Snapshot {
        if (key.isNullOrBlank()) return Snapshot()
        return publishedStates[key] ?: Snapshot()
    }

    internal fun qualificationPrior(data: ControlMessage.VehicleData, scope: EvLearnQualification.Scope,
        openingMs: Long, expectedResetRevision: Long): EvLearnQualification.Prior? = qualifiedRate?.takeIf {
        it.revision == expectedResetRevision && it.revision == resetRevision.get() && it.key == keyOf(data) &&
            it.prior.scope == scope && it.prior.trainedThroughElapsedMs < openingMs && it.admittedAt < openingMs
    }?.prior

    /** Breaks delta continuity at producer lifecycle boundaries without erasing learning.
     * Reset and tick admission share one lock, giving callers a linearized boundary:
     * every tick is wholly admitted before the reset or observes its new generation. */
    fun resetContinuity() {
        synchronized(tickAdmissionLock) {
            continuityGeneration.incrementAndGet()
        }
    }

    /** Clear learned state and report admission plus durable persistence status. */
    suspend fun reset(key: String? = null): ResetResult {
        if (!accepting.get()) return ResetResult.REJECTED_STOPPING
        admissionInFlight.incrementAndGet()
        try {
            if (!accepting.get()) return ResetResult.REJECTED_STOPPING
            val done = CompletableDeferred<ResetResult>()
            val revision = synchronized(tickAdmissionLock) {
                if (commands.trySend(Command.Reset(key, done)).isSuccess) resetRevision.incrementAndGet() else null
            }
            if (revision == null) {
                droppedCommands.incrementAndGet()
                publishRuntimeState()
                return ResetResult.REJECTED_BUSY
            }
            _qualificationResetRevision.update { maxOf(it, revision) }
            return done.await()
        } finally {
            admissionInFlight.decrementAndGet()
        }
    }

    private suspend fun resetLoaded(key: String?): ResetResult {
        qualifiedRate = null
        if (key == null) states.clear() else states.remove(key)
        if (key == null || activeKey == key) activeKey = null
        _activeSnapshot.value = Snapshot(key = key, lastTickStatus = "reset")
        publishStateIndex()
        debouncePersist?.cancel()
        maxDelayPersist?.cancel()
        dirty = true
        persistAttemptsForDirty = 0
        retryExhausted = false
        publishRuntimeState()
        persistDirty()
        DiagnosticLog.i("ev_learned", "reset key=${key ?: "ALL"}")
        return if (dirty) ResetResult.PERSIST_PENDING else ResetResult.COMPLETED
    }

    internal suspend fun awaitIdle() {
        val done = CompletableDeferred<Unit>()
        commands.send(Command.Barrier(done))
        done.await()
    }

    @Volatile private var stopCompletion: CompletableDeferred<StopResult>? = null

    internal suspend fun stop(): StopResult {
        var ownsStop = false
        val done = synchronized(this) {
            stopCompletion ?: CompletableDeferred<StopResult>().also {
                stopCompletion = it
                accepting.set(false)
                ownsStop = true
            }
        }
        publishRuntimeState()
        return withContext(NonCancellable) {
            if (ownsStop) {
                while (admissionInFlight.get() != 0) yield()
                commands.send(Command.Stop(done))
            }
            done.await()
        }
    }

    private fun markDirty() {
        if (!dirty) {
            dirty = true
            persistAttemptsForDirty = 0
            retryExhausted = false
            armMaxDelayPersist()
        } else if (retryExhausted) {
            // A later vehicle mutation may open one new bounded retry cycle.
            // There is no self-scheduling loop after exhaustion.
            persistAttemptsForDirty = 0
            retryExhausted = false
            armMaxDelayPersist()
        }
        debouncePersist?.cancel()
        debouncePersist = scope.launch {
            kotlinx.coroutines.delay(config.persistDebounceMs)
            commands.trySend(Command.Persist)
        }
        publishRuntimeState()
    }

    private fun armMaxDelayPersist() {
        maxDelayPersist?.cancel()
        maxDelayPersist = scope.launch {
            kotlinx.coroutines.delay(config.persistMaxDelayMs)
            commands.trySend(Command.Persist)
        }
    }

    private suspend fun persistDirty() {
        if (!dirty || retryExhausted) return
        persistAttemptsForDirty++
        if (persistNow()) {
            dirty = false
            persistAttemptsForDirty = 0
            retryExhausted = false
            debouncePersist?.cancel()
            maxDelayPersist?.cancel()
            retryPersist?.cancel()
        } else if (persistAttemptsForDirty < config.maxPersistAttempts) {
            retryPersist?.cancel()
            retryPersist = scope.launch {
                kotlinx.coroutines.delay(config.persistRetryMs)
                commands.trySend(Command.Persist)
            }
        } else {
            retryPersist?.cancel()
            retryPersist = null
            retryExhausted = true
            OalLog.w(TAG, "persist retries exhausted; dirty state retained for a future tick or stop")
        }
        publishRuntimeState()
    }

    private fun enforceEntryLimit(): Boolean {
        var evicted = false
        while (states.size > config.maxEntries.coerceAtLeast(0)) {
            val victim = states.entries.minWithOrNull(
                compareBy<Map.Entry<String, VehicleState>> { it.value.lastUpdateMs }
                    .thenBy { it.value.lastObservedSequence }
                    .thenBy { it.key },
            ) ?: break
            states.remove(victim.key)
            evicted = true
            if (activeKey == victim.key) activeKey = null
        }
        return evicted
    }

    private suspend fun persistNow(): Boolean {
        try {
            val values = linkedMapOf<String, kotlinx.serialization.json.JsonElement>()
            val newestFirst = states.entries.sortedWith(
                compareByDescending<Map.Entry<String, VehicleState>> { it.value.lastUpdateMs }
                    .thenByDescending { it.key },
            )
            for ((k, s) in newestFirst) {
                if (!s.whPerKm.isFinite() || !s.sampleKm.isFinite() || s.sampleKm < 0f) continue
                if (s.whPerKm <= 0f && s.sampleKm <= 0f) continue
                val value = buildJsonObject {
                    put("whPerKm", s.whPerKm.toDouble())
                    put("sampleKm", s.sampleKm.toDouble())
                    put("lastUpdateMs", s.lastUpdateMs)
                }
                values[k] = value
                if (JsonObject(values).toString().toByteArray(Charsets.UTF_8).size > config.maxJsonBytes) {
                    values.remove(k)
                }
            }
            val encoded = JsonObject(values).toString()
            store.save(encoded)
            return true
        } catch (e: Exception) {
            persistFailures.incrementAndGet()
            OalLog.w(TAG, "persist failed: ${e.message}")
            return false
        }
    }
}
