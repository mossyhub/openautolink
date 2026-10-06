package com.openautolink.app.input

import android.content.Context
import android.content.pm.PackageManager
import android.os.SystemClock
import android.util.Log
import com.openautolink.app.diagnostics.DiagnosticLog
import com.openautolink.app.data.EvLearnedRateEstimator
import com.openautolink.app.transport.ControlMessage
import com.openautolink.app.transport.VehiclePropertyObservation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.lang.reflect.InvocationTargetException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Monitors VHAL properties via the AAOS Car API using reflection.
 * Gracefully degrades when android.car is unavailable (e.g., on non-automotive devices).
 *
 * Monitored properties include speed, gear, parking brake, night mode, turn signals,
 * battery level, fuel level, odometer, ambient temperature, and more.
 *
 * Sends batched VehicleData control messages to the bridge at a throttled rate
 * to avoid flooding the control channel.
 */
class VehicleDataForwarderImpl(
    private val context: Context,
    private val sendMessage: (ControlMessage.VehicleData) -> Unit,
    private val onIgnitionOn: ((Int) -> Unit)? = null
) : VehicleDataForwarder {

    companion object {
        private const val TAG = "VehicleDataForwarder"

        // Minimum interval between vehicle data sends (ms)
        private const val SEND_INTERVAL_MS = 500L

        /** Hardcoded property IDs for GM AAOS runtimes that strip VehiclePropertyIds field names.
         *  Integer IDs are stable across all AAOS implementations (defined in HAL spec). */
        private val VEHICLE_PROPERTY_ID_FALLBACK = mapOf(
            "PERF_VEHICLE_SPEED" to 0x11600207,
            "PERF_VEHICLE_SPEED_DISPLAY" to 0x11600208,
            "GEAR_SELECTION" to 0x11400400,
            "CURRENT_GEAR" to 0x11400401,
            "PARKING_BRAKE_ON" to 0x11200402,
            "NIGHT_MODE" to 0x11200407,
            "IGNITION_STATE" to 0x11400409,
            "EV_BATTERY_LEVEL" to 0x11600309,
            "INFO_EV_BATTERY_CAPACITY" to 0x11600106,
            "EV_BATTERY_INSTANTANEOUS_CHARGE_RATE" to 0x1160030C,
            "EV_CURRENT_BATTERY_CAPACITY" to 0x1160030D,
            "EV_BATTERY_AVERAGE_TEMPERATURE" to 0x1160030E,
            "EV_CHARGE_PORT_OPEN" to 0x1120030A,
            "EV_CHARGE_PORT_CONNECTED" to 0x1120030B,
            "EV_CHARGE_STATE" to 0x11400F41,
            "EV_CHARGE_TIME_REMAINING" to 0x11400F43,
            "EV_CHARGE_PERCENT_LIMIT" to 0x11600F40,
            "EV_CHARGE_CURRENT_DRAW_LIMIT" to 0x11600F3F,
            "EV_BRAKE_REGENERATION_LEVEL" to 0x1140040C,
            "EV_STOPPING_MODE" to 0x1140040D,
            "RANGE_REMAINING" to 0x11600308,
            "ENV_OUTSIDE_TEMPERATURE" to 0x11600703,
            "PERF_ENGINE_RPM" to 0x11600305,
            "PERF_ODOMETER" to 0x11600204,
            "PERF_STEERING_ANGLE" to 0x11600209,
            "DISTANCE_DISPLAY_UNITS" to 0x11400600,
            "INFO_FUEL_TYPE" to 0x11410105,
            "INFO_EV_CONNECTOR_TYPE" to 0x11410107,
            "INFO_MAKE" to 0x11100101,
            "INFO_MODEL" to 0x11100102,
            "INFO_MODEL_YEAR" to 0x11400103,
            // Round-6 additions — read-only props gated by CAR_MILEAGE/CAR_TIRES/CAR_DYNAMICS_STATE
            "TIRE_PRESSURE" to 0x07410F07,
            "ABS_ACTIVE" to 0x1120040A,
            "TRACTION_CONTROL_ACTIVE" to 0x1120040B,
        )
    }

    @Volatile override var isActive: Boolean = false
        private set

    // Background scope for Car API calls (matches app_v1 pattern)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // Car API objects obtained via reflection
    private var carObject: Any? = null
    private var propertyManager: Any? = null
    private var callbackProxy: Any? = null  // ONE shared callback for all properties (app_v1 pattern)
    private val trackedPropertyIds = ConcurrentHashMap.newKeySet<Int>()  // registered property IDs for dispatch

    // Latest values — updated by property callbacks, sent as batch
    private val currentValues = ConcurrentHashMap<Int, Any>()
    // Guarded with the forwarder monitor, alongside value updates and batch snapshots.
    private val evObservationMetadata = mutableMapOf<String, VehiclePropertyObservation>()
    private val observationSequence = AtomicLong()
    private data class BufferedSafetyObservation(
        val value: Any?,
        val observation: VehiclePropertyObservation,
    )
    private val pendingSafetySubscriptions = mutableMapOf<Int, Long>()
    private val bufferedSafetyObservations = mutableMapOf<Int, BufferedSafetyObservation>()
    private var lastSendTime = 0L

    // HistoryProvider polling cache (Finding F.2). Refreshed every 5s on a
    // dedicated coroutine; nullable when the provider is patched or returns
    // no data. The build path falls back to SOC-derived math transparently.
    private data class MotorPowerSnapshot(
        val value: Float,
        val observation: VehiclePropertyObservation,
    )

    @Volatile private var latestMotorPowerSnapshot: MotorPowerSnapshot? = null
    @Volatile private var latestMotorTorqueNm: Float? = null
    private var historyPollerJob: kotlinx.coroutines.Job? = null

    // Previous ignition state — used to detect ON transitions for wake signaling
    @Volatile
    private var previousIgnitionState: Int? = null

    // Edge-log state for the small set of properties whose transitions matter
    // for triaging connection / lifecycle / day-night issues. We always log
    // the very first value we see for each (so a log capture mid-drive is
    // still useful) and every subsequent transition. Speed gets its own
    // boundary-crossing edge (0 ↔ moving) rather than per-tick noise.
    @Volatile private var lastLoggedIgnition: Int? = null
    @Volatile private var lastLoggedGear: Int? = null
    @Volatile private var lastLoggedNightMode: Boolean? = null
    @Volatile private var lastLoggedParkingBrake: Boolean? = null
    @Volatile private var lastSpeedMoving: Boolean? = null

    // Static vehicle identity — read once from VHAL INFO_* properties
    private var carMake: String? = null
    private var carModel: String? = null
    private var carYear: String? = null
    private var fuelTypes: List<Int>? = null
    private var evConnectorTypes: List<Int>? = null

    private val _latestVehicleData = MutableStateFlow(ControlMessage.VehicleData())
    override val latestVehicleData: StateFlow<ControlMessage.VehicleData> = _latestVehicleData.asStateFlow()

    private val _propertyStatus = ConcurrentHashMap<String, String>()
    override val propertyStatus: Map<String, String> get() =
        java.util.Collections.unmodifiableMap(HashMap(_propertyStatus))

    private var startInFlight = false
    private var desiredActive = false
    private var lifecycleGeneration = 0L
    private var startFailureGeneration: Long? = null
    private var reconnectAttempt = 0
    private var reconnectJob: kotlinx.coroutines.Job? = null
    private var carLifecycleProxy: Any? = null
    private val retryState = VhalRetryState()
    // startInFlight owns the entire mutation/retirement lane, including refresh.
    private var refreshRequested = 0L
    private var refreshCompleted = 0L
    private var requestedGrants: Map<String, Boolean>? = null
    private val attemptedGrants = mutableMapOf<Int, Boolean>()
    private val subscribedNames = mutableSetOf<String>()

    private fun permissionGrants(): Map<String, Boolean> = properties.mapNotNull { it.permission }
        .distinct().associateWith { context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }

    private fun staticInfoMissing() = carMake == null || carModel == null || carYear == null ||
        fuelTypes == null || evConnectorTypes == null

    override fun requestSubscriptionRefresh(reason: String) {
        val grants = permissionGrants()
        var refreshGeneration: Long? = null
        var restartGeneration: Long? = null
        synchronized(this) {
            if (!desiredActive) return
            if (requestedGrants != grants || (!startInFlight && staticInfoMissing())) {
                // A grant epoch retries missing members of that group, including HALs
                // that flattened an earlier permission failure to absent/rejected.
                properties.filter { it.permission != null && requestedGrants?.get(it.permission) != grants[it.permission] }
                    .forEach { prop -> VEHICLE_PROPERTY_ID_FALLBACK[prop.fieldName]?.let { id ->
                        if (id !in trackedPropertyIds) attemptedGrants.remove(id)
                    } }
                requestedGrants = grants
                refreshRequested++
            }
            DiagnosticLog.i("vhal", "VHAL refresh requested reason=$reason generation=$registrationGeneration grants=$grants pending=${refreshRequested > refreshCompleted}")
            if (startInFlight) return
            if (isActive) {
                if (refreshRequested <= refreshCompleted) return
                startInFlight = true
                refreshGeneration = lifecycleGeneration
            } else {
                reconnectJob?.cancel()
                reconnectJob = null
                restartGeneration = retryState.serviceReady()
                restartGeneration?.let { startInFlight = true; lifecycleGeneration = it }
            }
        }
        refreshGeneration?.let(::launchRefresh)
        restartGeneration?.let(::launchStartAttempt)
    }

    private fun launchRefresh(generation: Long) {
        scope.launch {
            try {
                do {
                    val request = synchronized(this@VehicleDataForwarderImpl) { refreshRequested }
                    if (!isStartCurrent(generation)) break
                    readStaticVehicleInfo(generation)
                    registerProperties(generation)
                    synchronized(this@VehicleDataForwarderImpl) {
                        if (isStartCurrent(generation)) refreshCompleted = request
                    }
                } while (synchronized(this@VehicleDataForwarderImpl) {
                    isStartCurrent(generation) && refreshRequested > refreshCompleted
                })
            } catch (e: Exception) {
                DiagnosticLog.w("vhal", "VHAL refresh failed: ${e.rootCause().message}")
            } finally {
                cleanupAfterStartAttempt(generation, false)
            }
        }
    }

    override fun start() {
        val generation = synchronized(this) {
            if (!desiredActive) reconnectAttempt = 0
            desiredActive = true
            if (isActive || startInFlight) {
                retryState.request()
                return
            }
            val retryGeneration = retryState.start() ?: return
            startInFlight = true
            lifecycleGeneration = retryGeneration
            retryGeneration
        }
        launchStartAttempt(generation)
    }

    private fun launchStartAttempt(generation: Long) {
        // Run on background thread — Car API calls can block (connect, waitForConnected).
        // Every stage is fenced because stop() may run while this coroutine is blocked.
        scope.launch {
            val request = synchronized(this@VehicleDataForwarderImpl) {
                requestedGrants = permissionGrants()
                refreshRequested
            }
            var failed = false
            try {
                if (!isStartCurrent(generation)) return@launch
                connectToCar()
                if (!isStartCurrent(generation)) return@launch
                readStaticVehicleInfo(generation)
                if (!isStartCurrent(generation)) return@launch
                val subscribed = registerProperties(generation)
                check(VhalSubscriptionReadiness.mayActivate(subscribed)) {
                    "No meaningful safety or energy VHAL property subscribed"
                }
                if (!isStartCurrent(generation)) return@launch

                val activated = synchronized(this@VehicleDataForwarderImpl) {
                    if (lifecycleGeneration == generation && desiredActive) {
                        isActive = retryState.started(generation)
                        isActive
                    } else {
                        false
                    }
                }
                if (!activated) return@launch

                synchronized(this@VehicleDataForwarderImpl) { reconnectAttempt = 0 }

                Log.i(TAG, "Vehicle data forwarding started")
                DiagnosticLog.i("vhal", "Vehicle data forwarding started")
                // Re-emit current data so collectors see isActive=true
                _latestVehicleData.value = buildVehicleData()
                startHistoryPoller()
            } catch (e: Exception) {
                failed = true
                val root = e.rootCause()
                Log.w(TAG, "Failed to start vehicle data forwarding: ${root.message}")
                DiagnosticLog.w("vhal", "Failed to start: ${root.javaClass.simpleName}: ${root.message}")
            } finally {
                synchronized(this@VehicleDataForwarderImpl) { refreshCompleted = maxOf(refreshCompleted, request) }
                cleanupAfterStartAttempt(generation, failed)
            }
        }
    }

    private fun isStartCurrent(generation: Long): Boolean = synchronized(this) {
        lifecycleGeneration == generation && desiredActive && startFailureGeneration != generation
    }

    private fun cleanupAfterStartAttempt(generation: Long, failed: Boolean) {
        var cleaned = false
        var nextGeneration: Long? = null
        var refreshGeneration: Long? = null
        var retryDelayMs: Long? = null
        while (true) {
            val mustClean = synchronized(this) {
                val attemptFailed = failed || startFailureGeneration == generation
                val stale = lifecycleGeneration != generation || !desiredActive
                if (!cleaned && (attemptFailed || stale)) true else {
                    // Retirement decision and lane handoff share ONE critical section.
                    // stop/loss cannot slip between a healthy decision and handoff.
                    if (startFailureGeneration == generation) startFailureGeneration = null
                    startInFlight = false
                    if (attemptFailed && !stale) retryDelayMs = retryState.failedAttemptCleaned(generation)
                    val pendingGrant = refreshRequested > refreshCompleted
                    if (desiredActive && !isActive && (stale || !attemptFailed || pendingGrant)) {
                        nextGeneration = retryState.serviceReady()
                        nextGeneration?.let {
                            startInFlight = true; lifecycleGeneration = it; retryDelayMs = null
                        }
                    } else if (desiredActive && isActive && pendingGrant) {
                        startInFlight = true
                        refreshGeneration = lifecycleGeneration
                    }
                    false
                }
            }
            if (!mustClean) break
            // Keep the mutation lane reserved across captured-manager OS retirement,
            // but never hold the state monitor across a blocking Binder operation.
            cleanup()
            cleaned = true
        }
        nextGeneration?.let(::launchStartAttempt)
        refreshGeneration?.let(::launchRefresh)
        retryDelayMs?.let { scheduleReconnectAfterCleanup(generation, it) }
    }

    private fun scheduleReconnectAfterCleanup(failedGeneration: Long, delayMs: Long) {
        val job = synchronized(this) {
            if (!desiredActive || isActive || startInFlight || lifecycleGeneration != failedGeneration) return
            reconnectAttempt++
            reconnectJob?.cancel()
            scope.launch(start = kotlinx.coroutines.CoroutineStart.LAZY) {
                delay(delayMs)
                val next = synchronized(this@VehicleDataForwarderImpl) {
                    if (!desiredActive || isActive || startInFlight || lifecycleGeneration != failedGeneration) return@launch
                    val retryGeneration = retryState.retryTimerFired(failedGeneration) ?: return@launch
                    startInFlight = true
                    lifecycleGeneration = retryGeneration
                    reconnectJob = null
                    retryGeneration
                }
                launchStartAttempt(next)
            }.also { reconnectJob = it }
        }
        job.start()
        DiagnosticLog.i("vhal", "VHAL reconnect scheduled attempt=$reconnectAttempt delayMs=$delayMs")
    }

    override fun stop() {
        val retirement = synchronized(this) {
            val old = lifecycleGeneration
            desiredActive = false
            isActive = false
            retryState.stop()
            reconnectJob?.cancel()
            reconnectJob = null
            reconnectAttempt = 0
            lifecycleGeneration++
            registrationGeneration++ // Fence the live proxy immediately, even if subscribe is blocked.
            publishImmediate() // Publish the retired epoch before a blocked OS call can return.
            if (startInFlight) null else {
                startInFlight = true
                old
            }
        }
        retirement?.let { generation -> scope.launch { cleanupAfterStartAttempt(generation, false) } }
        DiagnosticLog.i("vhal", "VHAL stop requested; serialized retirement pending")
    }

    private fun connectToCar() {
        // Check FEATURE_AUTOMOTIVE first — matches app_v1 pattern
        if (!context.packageManager.hasSystemFeature(PackageManager.FEATURE_AUTOMOTIVE)) {
            Log.w(TAG, "FEATURE_AUTOMOTIVE not available on this device")
            DiagnosticLog.w("vhal", "FEATURE_AUTOMOTIVE not available — vehicle data unavailable")
            throw IllegalStateException("Not an automotive device")
        }

        val carClass = try {
            Class.forName("android.car.Car")
        } catch (_: ClassNotFoundException) {
            DiagnosticLog.w("vhal", "android.car APIs not present on this runtime")
            throw IllegalStateException("android.car APIs not available")
        }

        // Prefer the lifecycle-listener API so service death owns one bounded reconnect.
        val car = createCarWithLifecycle(carClass) ?: try {
            carClass.getMethod("createCar", Context::class.java).invoke(null, context)
        } catch (_: NoSuchMethodException) {
            carClass.getMethod("createCar", Context::class.java, android.os.Handler::class.java)
                .invoke(null, context, null)
        } ?: throw IllegalStateException("Car.createCar returned null")

        // Ensure connected (match app_v1 pattern — check state, connect if needed, wait)
        val wasConnected = invokeBoolean(car, "isConnected") ?: false
        val wasConnecting = invokeBoolean(car, "isConnecting") ?: false
        if (!wasConnected && !wasConnecting) {
            try {
                carClass.getMethod("connect").invoke(car)
            } catch (e: Exception) {
                // May throw if already connected/connecting — that's fine
                val root = e.rootCause()
                if (root is IllegalStateException &&
                    (root.message?.contains("already connected", ignoreCase = true) == true ||
                     root.message?.contains("already connecting", ignoreCase = true) == true)) {
                    Log.d(TAG, "Car already connected/connecting")
                } else {
                    throw e
                }
            }
        }

        // Wait for connection (app_v1 waits up to 2s)
        if (!waitForConnected(car)) {
            throw IllegalStateException("Car service did not report connected within timeout")
        }
        synchronized(this) { carObject = car }

        // Get CarPropertyManager
        val propertyServiceName = carClass.getField("PROPERTY_SERVICE").get(null) as String
        val manager = carClass.getMethod("getCarManager", String::class.java)
            .invoke(car, propertyServiceName)
            ?: throw IllegalStateException("CarPropertyManager is null")
        synchronized(this) { propertyManager = manager }

        Log.i(TAG, "Connected to Car API via reflection")
        DiagnosticLog.i("vhal", "Connected to Car API")
    }

    private fun createCarWithLifecycle(carClass: Class<*>): Any? = runCatching {
        val listenerClass = Class.forName("android.car.Car\$CarServiceLifecycleListener")
        val generation = synchronized(this) { lifecycleGeneration }
        val listener = java.lang.reflect.Proxy.newProxyInstance(
            listenerClass.classLoader,
            arrayOf(listenerClass),
        ) { proxy, method, args ->
            when (method.name) {
                "onLifecycleChanged" -> {
                    synchronized(this) {
                        if (lifecycleGeneration == generation && carLifecycleProxy === proxy) {
                            val ready = args?.getOrNull(1) as? Boolean ?: false
                            if (ready) onCarServiceReady(args?.firstOrNull()) else onCarServiceLost(args?.firstOrNull())
                        }
                    }
                    null
                }
                "toString" -> "OalCarServiceLifecycleListener"
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === args?.firstOrNull()
                else -> null
            }
        }
        val method = carClass.methods.first { candidate ->
            candidate.name == "createCar" && candidate.parameterTypes.size == 4 &&
                candidate.parameterTypes.last() == listenerClass
        }
        synchronized(this) { carLifecycleProxy = listener }
        method.invoke(null, context, null, 2_000L, listener)
    }.getOrNull()

    private fun onCarServiceLost(lostCar: Any?) {
        val retirement = synchronized(this) {
            if (!desiredActive || (carObject != null && carObject !== lostCar)) return
            val generation = lifecycleGeneration
            if (isActive) retryState.serviceLost(generation)
            isActive = false
            startFailureGeneration = generation
            registrationGeneration++
            publishImmediate()
            reconnectAttempt = 0
            if (startInFlight) null else { startInFlight = true; generation }
        }
        DiagnosticLog.w("vhal", "Car service lost; serialized process-owned retirement pending")
        retirement?.let { generation -> scope.launch { cleanupAfterStartAttempt(generation, true) } }
    }

    private fun onCarServiceReady(readyCar: Any?) {
        val generation = synchronized(this) {
            if (!desiredActive || isActive || startInFlight || (carObject != null && carObject !== readyCar)) return
            reconnectJob?.cancel()
            reconnectJob = null
            reconnectAttempt = 0
            val next = retryState.serviceReady() ?: return
            startInFlight = true
            lifecycleGeneration = next
            next
        }
        launchStartAttempt(generation)
    }

    /** Poll isConnected() up to timeoutMs, matching app_v1's waitForConnected pattern. */
    private fun waitForConnected(car: Any, timeoutMs: Long = 2000L): Boolean {
        val start = SystemClock.elapsedRealtime()
        while (SystemClock.elapsedRealtime() - start < timeoutMs) {
            if (invokeBoolean(car, "isConnected") == true) return true
            Thread.sleep(50)
        }
        return invokeBoolean(car, "isConnected") == true
    }

    /** Read static vehicle info (make/model/year) — one-time, these don't change. */
    private fun readStaticVehicleInfo(lifecycle: Long) {
        val pm = synchronized(this) {
            if (!isStartCurrent(lifecycle)) return
            propertyManager
        } ?: return
        val pmClass = pm::class.java
        if (context.checkSelfPermission("android.car.permission.CAR_INFO") != PackageManager.PERMISSION_GRANTED) return

        fun readStringProp(fieldName: String): String? {
            val propId = resolveIntConstant("android.car.VehiclePropertyIds", fieldName) ?: return null
            return try {
                val pv = pmClass.getMethod("getProperty", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
                    .invoke(pm, propId, 0)
                pv?.javaClass?.getMethod("getValue")?.invoke(pv)?.toString()
            } catch (t: Throwable) {
                DiagnosticLog.d("vhal", "$fieldName: read failed: ${t.rootCause().message}")
                null
            }
        }

        fun readIntProp(fieldName: String): Int? {
            val propId = resolveIntConstant("android.car.VehiclePropertyIds", fieldName) ?: return null
            return try {
                val pv = pmClass.getMethod("getProperty", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
                    .invoke(pm, propId, 0)
                pv?.javaClass?.getMethod("getValue")?.invoke(pv) as? Int
            } catch (t: Throwable) {
                DiagnosticLog.d("vhal", "$fieldName: read failed: ${t.rootCause().message}")
                null
            }
        }

        val make = if (synchronized(this) { carMake == null }) readStringProp("INFO_MAKE") else null
        val model = if (synchronized(this) { carModel == null }) readStringProp("INFO_MODEL") else null
        val year = if (synchronized(this) { carYear == null }) readIntProp("INFO_MODEL_YEAR")?.toString() else null

        // Read fuel type and EV connector arrays (Integer[] properties)
        fun readIntArrayProp(fieldName: String): List<Int>? {
            val propId = resolveIntConstant("android.car.VehiclePropertyIds", fieldName) ?: return null
            return try {
                val pv = pmClass.getMethod("getProperty", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
                    .invoke(pm, propId, 0)
                val value = pv?.javaClass?.getMethod("getValue")?.invoke(pv)
                when (value) {
                    is IntArray -> value.toList()
                    is Array<*> -> value.filterIsInstance<Int>()
                    else -> null
                }
            } catch (t: Throwable) {
                DiagnosticLog.d("vhal", "$fieldName: read failed: ${t.rootCause().message}")
                null
            }
        }

        val fuel = if (synchronized(this) { fuelTypes == null }) readIntArrayProp("INFO_FUEL_TYPE") else null
        val connectors = if (synchronized(this) { evConnectorTypes == null }) readIntArrayProp("INFO_EV_CONNECTOR_TYPE") else null
        synchronized(this) {
            if (propertyManager !== pm || lifecycleGeneration != lifecycle || !isStartCurrent(lifecycle) ||
                context.checkSelfPermission("android.car.permission.CAR_INFO") != PackageManager.PERMISSION_GRANTED) return
            if (carMake == null) carMake = make
            if (carModel == null) carModel = model
            if (carYear == null) carYear = year
            if (fuelTypes == null) fuelTypes = fuel
            if (evConnectorTypes == null) evConnectorTypes = connectors
        }
    }

    private fun invokeBoolean(target: Any, methodName: String): Boolean? {
        return try {
            target.javaClass.getMethod(methodName).invoke(target) as? Boolean
        } catch (_: Exception) { null }
    }

    private fun Throwable.rootCause(): Throwable {
        val cause = if (this is InvocationTargetException) targetException else this.cause
        return cause?.rootCause() ?: this
    }

    // Property definitions: fieldName → (permission, rateField)
    private data class PropDef(val fieldName: String, val permission: String?, val rateField: String = "SENSOR_RATE_ONCHANGE")
    private val properties = listOf(
        PropDef("PERF_VEHICLE_SPEED", "android.car.permission.CAR_SPEED", "SENSOR_RATE_FAST"),
        PropDef("GEAR_SELECTION", "android.car.permission.CAR_POWERTRAIN"),
        PropDef("PARKING_BRAKE_ON", "android.car.permission.CAR_POWERTRAIN"),
        PropDef("NIGHT_MODE", null),
        PropDef("EV_BATTERY_LEVEL", "android.car.permission.CAR_ENERGY"),
        PropDef("INFO_EV_BATTERY_CAPACITY", "android.car.permission.CAR_INFO"),
        PropDef("ENV_OUTSIDE_TEMPERATURE", "android.car.permission.CAR_EXTERIOR_ENVIRONMENT"),
        PropDef("EV_BATTERY_INSTANTANEOUS_CHARGE_RATE", "android.car.permission.CAR_ENERGY"),
        PropDef("RANGE_REMAINING", "android.car.permission.CAR_ENERGY"),
        PropDef("PERF_ENGINE_RPM", "android.car.permission.CAR_SPEED"),
        PropDef("EV_CHARGE_PORT_OPEN", "android.car.permission.CAR_ENERGY_PORTS"),
        PropDef("EV_CHARGE_PORT_CONNECTED", "android.car.permission.CAR_ENERGY_PORTS"),
        PropDef("IGNITION_STATE", "android.car.permission.CAR_POWERTRAIN"),
        // Extended EV / vehicle properties — may or may not be exposed by HAL
        PropDef("DISTANCE_DISPLAY_UNITS", "android.car.permission.READ_CAR_DISPLAY_UNITS"),
        PropDef("EV_CHARGE_STATE", "android.car.permission.CAR_ENERGY"),
        PropDef("EV_CHARGE_TIME_REMAINING", "android.car.permission.CAR_ENERGY"),
        PropDef("EV_CURRENT_BATTERY_CAPACITY", "android.car.permission.CAR_ENERGY"),
        PropDef("EV_BATTERY_AVERAGE_TEMPERATURE", "android.car.permission.CAR_ENERGY"),
        PropDef("EV_CHARGE_PERCENT_LIMIT", "android.car.permission.CAR_ENERGY"),
        PropDef("EV_CHARGE_CURRENT_DRAW_LIMIT", "android.car.permission.CAR_ENERGY"),
        PropDef("EV_BRAKE_REGENERATION_LEVEL", "android.car.permission.CAR_POWERTRAIN"),
        PropDef("EV_STOPPING_MODE", "android.car.permission.CAR_POWERTRAIN"),
        // Round-6 additions — see recon_dump/gm-aaos-recon.md §14
        PropDef("PERF_ODOMETER", "android.car.permission.CAR_MILEAGE"),
        PropDef("TIRE_PRESSURE", "android.car.permission.CAR_TIRES"),
        PropDef("ABS_ACTIVE", "android.car.permission.CAR_DYNAMICS_STATE"),
        PropDef("TRACTION_CONTROL_ACTIVE", "android.car.permission.CAR_DYNAMICS_STATE"),
    )

    private fun registerProperties(lifecycle: Long): Set<String> {
        val pm = synchronized(this) {
            if (!isStartCurrent(lifecycle)) return emptySet()
            propertyManager
        } ?: return emptySet()
        val pmClass = pm::class.java

        // Resolve callback interface ONCE and create ONE shared proxy (app_v1 pattern)
        val callbackInterface = callbackProxy?.javaClass?.interfaces?.firstOrNull() ?: try {
            Class.forName("android.car.hardware.property.CarPropertyManager\$CarPropertyEventCallback")
        } catch (e: ClassNotFoundException) {
            DiagnosticLog.w("vhal", "CarPropertyEventCallback class not found")
            return emptySet()
        }
        val (callback, generation) = synchronized(this) {
            if (!isStartCurrent(lifecycle) || propertyManager !== pm) return emptySet()
            if (callbackProxy == null) callbackProxy = createCallbackProxy(callbackInterface)
            checkNotNull(callbackProxy) to registrationGeneration
        }
        fun current(): Boolean = desiredActive && propertyManager === pm && callbackProxy === callback &&
            registrationGeneration == generation && lifecycleGeneration == lifecycle && startFailureGeneration != lifecycle
        val grants = permissionGrants()
        synchronized(this) { if (requestedGrants == null) requestedGrants = grants }
        val authorizedBefore = synchronized(this) {
            subscribedNames.filterTo(mutableSetOf()) { _propertyStatus[it] == "subscribed" }
        }
        var subscribed = 0
        val registeredAdditions = mutableSetOf<String>()
        for (prop in properties) {
            val propId = resolveIntConstant("android.car.VehiclePropertyIds", prop.fieldName) ?: continue
            val granted = prop.permission == null || context.checkSelfPermission(prop.permission) == PackageManager.PERMISSION_GRANTED
            var restore = false
            val candidate = synchronized(this) {
                if (!current()) return@synchronized false
                if (!granted) {
                    _propertyStatus[prop.fieldName] = "permission_denied:${prop.permission}"
                    currentValues.remove(propId)
                    evObservationMetadata.remove(prop.fieldName)
                    attemptedGrants[propId] = false
                    false
                } else if (propId in trackedPropertyIds) {
                    restore = _propertyStatus[prop.fieldName]?.startsWith("permission_denied") == true
                    restore
                } else if (attemptedGrants[propId] == true) false
                else { attemptedGrants[propId] = true; true }
            }
            if (!candidate) continue
            if (restore) {
                val pv = runCatching {
                    pmClass.getMethod("getProperty", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
                        .invoke(pm, propId, 0)
                }.getOrNull()
                synchronized(this) {
                    if (current() && propertyPermitted(propId)) {
                        // A concurrently delivered callback outranks this recovery read.
                        if (pv != null && evObservationMetadata[prop.fieldName] == null) handleChangeEvent(pv)
                        _propertyStatus[prop.fieldName] = "subscribed"
                    }
                }
                continue
            }
            val config = runCatching {
                pmClass.getMethod("getCarPropertyConfig", Int::class.javaPrimitiveType).invoke(pm, propId)
            }.getOrNull()
            if (config == null) {
                synchronized(this) { if (current()) _propertyStatus[prop.fieldName] = "not_exposed" }
                continue
            }
            // Initial values remain private until this exact subscription succeeds.
            val initial = runCatching {
                pmClass.getMethod("getProperty", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
                    .invoke(pm, propId, 0)
            }.getOrNull()
            val reserved = synchronized(this) {
                if (!current()) false else {
                    beginSafetySubscription(propId, generation)
                    initial?.let {
                        val value = runCatching { it.javaClass.getMethod("getValue").invoke(it) }.getOrNull()
                        bufferedSafetyObservations[propId] = BufferedSafetyObservation(value,
                            observationFrom(it, propId, authoritative = false))
                    }
                    true
                }
            }
            if (!reserved) break
            val ok = subscribe(pm, callbackInterface, callback, propId, prop.rateField)
            synchronized(this) {
                if (!current()) return@synchronized
                val stillGranted = prop.permission == null || context.checkSelfPermission(prop.permission) == PackageManager.PERMISSION_GRANTED
                if (ok) {
                    trackedPropertyIds.add(propId)
                    subscribedNames += prop.fieldName
                    registeredAdditions += prop.fieldName
                    if (stillGranted) {
                        commitSafetySubscription(propId, generation)
                        _propertyStatus[prop.fieldName] = "subscribed"
                        subscribed++
                    } else {
                        pendingSafetySubscriptions.remove(propId)
                        bufferedSafetyObservations.remove(propId)
                        _propertyStatus[prop.fieldName] = "permission_denied:${prop.permission}"
                        attemptedGrants[propId] = false
                    }
                } else {
                    rejectSafetySubscription(propId)
                    _propertyStatus[prop.fieldName] = "rejected"
                }
            }
        }
        Log.i(TAG, "Subscribed to $subscribed/${properties.size} vehicle properties")
        DiagnosticLog.i("vhal", "Subscribed to $subscribed/${properties.size} vehicle properties")
        DiagnosticLog.i("vhal", "currentValues after subscription: ${currentValues.size} entries")

        synchronized(this) {
            if (current()) {
                // Authorization can change after an earlier property's successful
                // commit while a later Binder call blocks. Recheck the full pass.
                properties.forEach { prop ->
                    val id = VEHICLE_PROPERTY_ID_FALLBACK[prop.fieldName] ?: return@forEach
                    if (!propertyPermitted(id)) {
                        currentValues.remove(id)
                        evObservationMetadata.remove(prop.fieldName)
                        _propertyStatus[prop.fieldName] = "permission_denied:${prop.permission}"
                        attemptedGrants[id] = false
                    }
                }
                val authorized = subscribedNames.filterTo(mutableSetOf()) { _propertyStatus[it] == "subscribed" }
                // Missing static INFO stays retryable, but an unchanged recovery pass
                // is not a new raw observation for the process learner/recorder.
                // Keep startup and authorization transitions even without a value;
                // real observations (including equal values) differ by metadata.
                if (!isActive || authorized != authorizedBefore || buildVehicleData() != _latestVehicleData.value) {
                    publishImmediate()
                }
                DiagnosticLog.i("vhal", "VHAL refresh completed generation=$generation registeredAdded=$registeredAdditions total=${trackedPropertyIds.size} authorized=${subscribedNames.filter { _propertyStatus[it] == "subscribed" }} staticMissing=${staticInfoMissing()}")
            }
            return subscribedNames.filterTo(mutableSetOf()) { _propertyStatus[it] == "subscribed" }
        }
    }

    /** Create ONE shared callback proxy for all properties (app_v1 pattern). */
    private var registrationGeneration = 0L

    @Synchronized
    private fun createCallbackProxy(callbackInterface: Class<*>): Any {
        val generation = ++registrationGeneration
        return java.lang.reflect.Proxy.newProxyInstance(
            callbackInterface.classLoader,
            arrayOf(callbackInterface),
        ) { proxy, method, args ->
            when (method.name) {
                "onChangeEvent" -> {
                    synchronized(this) {
                        if (generation == registrationGeneration) {
                            args?.firstOrNull()?.let { handleCallbackChange(generation, it) }
                        }
                    }
                    null
                }
                "onErrorEvent" -> {
                    val propertyId = (args?.getOrNull(0) as? Int) ?: -1
                    Log.w(TAG, "Property error callback for $propertyId")
                    synchronized(this) {
                        if (generation == registrationGeneration) handleCallbackError(generation, propertyId)
                    }
                    null
                }
                "toString" -> "VehiclePropertyCallback"
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === args?.firstOrNull()
                else -> null
            }
        }
    }

    /** Subscribe to a property — tries API 34+ subscribePropertyEvents first,
     *  falls back to registerCallback. Exactly mirrors app_v1's subscribe(). */
    private fun subscribe(
        manager: Any,
        callbackInterface: Class<*>,
        callback: Any,
        propertyId: Int,
        rateField: String,
    ): Boolean {
        val managerClass = manager.javaClass

        // API 34+ uses subscribePropertyEvents(int, float, callback)
        val subscribeMethod = managerClass.methods.firstOrNull { m ->
            m.name == "subscribePropertyEvents" &&
                m.parameterTypes.size == 3 &&
                m.parameterTypes[0] == Int::class.javaPrimitiveType &&
                m.parameterTypes[1] == Float::class.javaPrimitiveType &&
                m.parameterTypes[2] == callbackInterface
        }
        // Older API uses registerCallback(callback, int, float)
        val registerMethod = managerClass.methods.firstOrNull { m ->
            m.name == "registerCallback" &&
                m.parameterTypes.size == 3 &&
                m.parameterTypes[0] == callbackInterface &&
                m.parameterTypes[1] == Int::class.javaPrimitiveType &&
                m.parameterTypes[2] == Float::class.javaPrimitiveType
        }

        // Resolve sample rate from CarPropertyManager constants (app_v1 pattern)
        val sampleRate = resolveFloatConstant(
            "android.car.hardware.property.CarPropertyManager", rateField
        ) ?: 0.0f

        return try {
            when {
                subscribeMethod != null -> subscribeMethod.invoke(manager, propertyId, sampleRate, callback) as? Boolean ?: false
                registerMethod != null -> registerMethod.invoke(manager, callback, propertyId, sampleRate) as? Boolean ?: false
                else -> {
                    DiagnosticLog.w("vhal", "No subscribe/registerCallback method found on ${managerClass.simpleName}")
                    false
                }
            }
        } catch (t: Throwable) {
            DiagnosticLog.w("vhal", "subscribe($propertyId): ${t.rootCause().javaClass.simpleName}: ${t.rootCause().message}")
            false
        }
    }

    /** Resolve a static int constant from a class by field name, with hardcoded fallback.
     *  GM AAOS strips some field names from VehiclePropertyIds — integer IDs are stable. */
    private fun resolveIntConstant(className: String, fieldName: String): Int? {
        return try {
            Class.forName(className).getField(fieldName).getInt(null)
        } catch (_: Throwable) {
            VEHICLE_PROPERTY_ID_FALLBACK[fieldName]
        }
    }

    /** Resolve a static float constant from a class by field name (app_v1 pattern). */
    private fun resolveFloatConstant(className: String, fieldName: String): Float? {
        return try {
            Class.forName(className).getField(fieldName).getFloat(null)
        } catch (_: Throwable) { null }
    }

    /** Handle property change event — extracts propertyId from event (app_v1 pattern). */
    @Synchronized
    private fun handleInitialRead(propertyValue: Any) {
        val propertyId = runCatching {
            propertyValue.javaClass.getMethod("getPropertyId").invoke(propertyValue) as? Int
        }.getOrNull() ?: return
        val value = runCatching { propertyValue.javaClass.getMethod("getValue").invoke(propertyValue) }.getOrNull()
        if (value != null) currentValues[propertyId] = value
        VEHICLE_PROPERTY_ID_FALLBACK.entries.firstOrNull { it.value == propertyId }?.key?.let { name ->
            evObservationMetadata[name] = observationFrom(propertyValue, propertyId, authoritative = false)
        }
    }

    @Synchronized
    private fun promoteSubscribedInitialRead(propertyId: Int, propertyValue: Any) {
        trackedPropertyIds.add(propertyId)
        handleChangeEvent(propertyValue)
    }

    private fun isSafetyProperty(propertyId: Int): Boolean =
        propertyId == VEHICLE_PROPERTY_ID_FALLBACK["GEAR_SELECTION"] ||
            propertyId == VEHICLE_PROPERTY_ID_FALLBACK["IGNITION_STATE"]

    @Synchronized
    private fun beginSafetySubscription(propertyId: Int, generation: Long) {
        if (generation != registrationGeneration) return
        pendingSafetySubscriptions[propertyId] = generation
        bufferedSafetyObservations.remove(propertyId)
    }

    @Synchronized
    private fun commitSafetySubscription(propertyId: Int, generation: Long) {
        if (generation != registrationGeneration ||
            pendingSafetySubscriptions[propertyId] != generation
        ) return
        pendingSafetySubscriptions.remove(propertyId)
        trackedPropertyIds.add(propertyId)
        val name = VEHICLE_PROPERTY_ID_FALLBACK.entries.firstOrNull { it.value == propertyId }?.key ?: return
        val initial = evObservationMetadata[name]
        val buffered = bufferedSafetyObservations.remove(propertyId)
        val newest = when {
            buffered == null -> initial?.let { BufferedSafetyObservation(currentValues[propertyId], it) }
            initial == null || buffered.observation.sequence > initial.sequence -> buffered
            else -> BufferedSafetyObservation(currentValues[propertyId], initial)
        } ?: return
        applySafetyObservation(propertyId, newest.value, newest.observation.copy(
            registrationGeneration = generation, subscriptionActive = true))
        if (isSafetyProperty(propertyId)) publishImmediate()
    }

    @Synchronized
    private fun handleCallbackChange(generation: Long, propertyValue: Any) {
        val propertyId = runCatching {
            propertyValue.javaClass.getMethod("getPropertyId").invoke(propertyValue) as? Int
        }.getOrNull() ?: return
        if (pendingSafetySubscriptions[propertyId] == generation) {
            val value = runCatching { propertyValue.javaClass.getMethod("getValue").invoke(propertyValue) }.getOrNull()
            val observation = observationFrom(propertyValue, propertyId, authoritative = true)
                .copy(registrationGeneration = generation)
            val previous = bufferedSafetyObservations[propertyId]
            if (previous == null || observation.sequence > previous.observation.sequence) {
                bufferedSafetyObservations[propertyId] = BufferedSafetyObservation(value, observation)
            }
            return
        }
        if (propertyPermitted(propertyId)) handleChangeEvent(propertyValue)
    }

    private fun propertyPermitted(propertyId: Int): Boolean {
        val permission = properties.firstOrNull {
            VEHICLE_PROPERTY_ID_FALLBACK[it.fieldName] == propertyId
        }?.permission ?: return true
        return context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
    }

    @Synchronized
    private fun handleCallbackError(generation: Long, propertyId: Int) {
        if (pendingSafetySubscriptions[propertyId] == generation) {
            val observation = VehiclePropertyObservation(
                timestampElapsedNanos = SystemClock.elapsedRealtimeNanos(),
                receivedElapsedMs = SystemClock.elapsedRealtime(),
                status = 1,
                registrationGeneration = generation,
                propertyId = propertyId,
                subscriptionActive = true,
                sequence = observationSequence.incrementAndGet(),
            )
            val previous = bufferedSafetyObservations[propertyId]
            if (previous == null || observation.sequence > previous.observation.sequence) {
                bufferedSafetyObservations[propertyId] = BufferedSafetyObservation(null, observation)
            }
            return
        }
        if (propertyPermitted(propertyId)) handleErrorEvent(propertyId)
    }

    @Synchronized
    private fun rejectSafetySubscription(propertyId: Int) {
        pendingSafetySubscriptions.remove(propertyId)
        bufferedSafetyObservations.remove(propertyId)
        trackedPropertyIds.remove(propertyId)
        currentValues.remove(propertyId)
        VEHICLE_PROPERTY_ID_FALLBACK.entries.firstOrNull { it.value == propertyId }?.key?.let(evObservationMetadata::remove)
    }

    private fun observationFrom(propertyValue: Any, propertyId: Int, authoritative: Boolean): VehiclePropertyObservation {
        val value = runCatching { propertyValue.javaClass.getMethod("getValue").invoke(propertyValue) }.getOrNull()
        return VehiclePropertyObservation(
            timestampElapsedNanos = if (value == null) null else runCatching {
                propertyValue.javaClass.getMethod("getTimestamp").invoke(propertyValue) as? Long
            }.getOrNull(),
            receivedElapsedMs = SystemClock.elapsedRealtime(),
            status = runCatching { propertyValue.javaClass.getMethod("getStatus").invoke(propertyValue) as? Int }.getOrNull(),
            registrationGeneration = registrationGeneration.takeIf { authoritative },
            propertyId = propertyId,
            subscriptionActive = authoritative,
            sequence = observationSequence.incrementAndGet(),
        )
    }

    @Synchronized
    private fun handleChangeEvent(propertyValue: Any) {
        try {
            val propertyId = propertyValue.javaClass.getMethod("getPropertyId").invoke(propertyValue) as? Int ?: return
            if (propertyId !in trackedPropertyIds) return
            // Observe raw availability without filtering ordinary telemetry values.
            // Safety values are fail-closed and published immediately.
            val value = runCatching { propertyValue.javaClass.getMethod("getValue").invoke(propertyValue) }.getOrNull()
            val observation = observationFrom(propertyValue, propertyId, authoritative = true)
            if (isSafetyProperty(propertyId)) {
                applySafetyObservation(propertyId, value, observation)
                if (value != null && observation.status == 0) {
                    DiagnosticLog.d("vhal", "prop 0x${propertyId.toString(16)}: $value")
                    processSafetyEdge(propertyId, value)
                }
                publishImmediate()
                return
            }
            VEHICLE_PROPERTY_ID_FALLBACK.entries.firstOrNull { it.value == propertyId }?.key?.let { name ->
                evObservationMetadata[name] = observation
            }
            if (value == null) return
            DiagnosticLog.d("vhal", "prop 0x${propertyId.toString(16)}: $value")
            edgeLog(propertyId, value)
            currentValues[propertyId] = value
            throttledSend()
        } catch (e: Throwable) {
            Log.w(TAG, "handleChangeEvent: ${e.rootCause().message}")
        }
    }

    @Synchronized
    private fun applySafetyObservation(
        propertyId: Int,
        value: Any?,
        observation: VehiclePropertyObservation,
    ) {
        val name = VEHICLE_PROPERTY_ID_FALLBACK.entries.firstOrNull { it.value == propertyId }?.key ?: return
        evObservationMetadata[name] = observation
        if (value != null && observation.status == 0) currentValues[propertyId] = value
        else currentValues.remove(propertyId)
    }

    private fun processSafetyEdge(propertyId: Int, value: Any) {
        val ignitionId = VEHICLE_PROPERTY_ID_FALLBACK["IGNITION_STATE"]
        if (propertyId == ignitionId && value is Int) {
            val prev = previousIgnitionState
            previousIgnitionState = value
            if (prev != null && prev < 4 && value >= 4) {
                Log.i(TAG, "Ignition ON detected (was $prev, now $value)")
                DiagnosticLog.i("vhal", "Ignition ON detected ($prev → $value)")
                onIgnitionOn?.invoke(value)
            }
        }
        edgeLog(propertyId, value)
    }

    @Synchronized
    private fun publishImmediate() {
        lastSendTime = System.currentTimeMillis()
        val data = buildVehicleData()
        _latestVehicleData.value = data
        sendMessage(data)
    }

    @Synchronized
    private fun handleErrorEvent(propertyId: Int) {
        if (propertyId !in trackedPropertyIds) return
        val name = VEHICLE_PROPERTY_ID_FALLBACK.entries.firstOrNull { it.value == propertyId }?.key ?: return
        currentValues.remove(propertyId)
        evObservationMetadata[name] = VehiclePropertyObservation(
            timestampElapsedNanos = SystemClock.elapsedRealtimeNanos(),
            receivedElapsedMs = SystemClock.elapsedRealtime(),
            status = 1,
            registrationGeneration = registrationGeneration,
            propertyId = propertyId,
            subscriptionActive = true,
            sequence = observationSequence.incrementAndGet(),
        )
        if (isSafetyProperty(propertyId)) publishImmediate() else {
            lastSendTime = 0L
            throttledSend()
        }
    }

    /**
     * Emit one-shot INFO log lines on transitions of the small set of VHAL
     * properties that matter for triaging connection / lifecycle / day-night
     * issues. Each value is logged once on first observation and again on
     * each subsequent change. Speed gets a moving / stopped boundary edge
     * rather than per-tick numbers.
     */
    private fun edgeLog(propertyId: Int, value: Any) {
        when (propertyId) {
            VEHICLE_PROPERTY_ID_FALLBACK["IGNITION_STATE"] -> {
                val v = value as? Int ?: return
                if (lastLoggedIgnition != v) {
                    val name = when (v) {
                        0 -> "UNDEFINED"; 1 -> "LOCK"; 2 -> "OFF"
                        3 -> "ACC"; 4 -> "ON"; 5 -> "START"
                        else -> "?"
                    }
                    DiagnosticLog.i("vhal", "IGNITION_STATE → $v ($name) [was ${lastLoggedIgnition ?: "?"}]")
                    lastLoggedIgnition = v
                }
            }
            VEHICLE_PROPERTY_ID_FALLBACK["GEAR_SELECTION"] -> {
                val v = value as? Int ?: return
                if (lastLoggedGear != v) {
                    DiagnosticLog.i("vhal", "GEAR_SELECTION → ${gearToString(v)} ($v) [was ${lastLoggedGear ?: "?"}]")
                    // Explicit PARKED marker (gear 4 == P per VehicleGear)
                    // so shutdown-side log analysis has a clear anchor.
                    if (v == 4 && lastLoggedGear != null) {
                        DiagnosticLog.i("vhal", "PARKED")
                    }
                    lastLoggedGear = v
                }
            }
            VEHICLE_PROPERTY_ID_FALLBACK["NIGHT_MODE"] -> {
                val v = value as? Boolean ?: return
                if (lastLoggedNightMode != v) {
                    DiagnosticLog.i("vhal", "NIGHT_MODE → $v [was ${lastLoggedNightMode ?: "?"}]")
                    lastLoggedNightMode = v
                }
            }
            VEHICLE_PROPERTY_ID_FALLBACK["PARKING_BRAKE_ON"] -> {
                val v = value as? Boolean ?: return
                if (lastLoggedParkingBrake != v) {
                    DiagnosticLog.i("vhal", "PARKING_BRAKE_ON → $v [was ${lastLoggedParkingBrake ?: "?"}]")
                    lastLoggedParkingBrake = v
                }
            }
            VEHICLE_PROPERTY_ID_FALLBACK["PERF_VEHICLE_SPEED"] -> {
                // Edge on the moving / stopped boundary only — full speed
                // numbers are already in throttledSend at DEBUG.
                val mps = value as? Float ?: return
                val moving = mps > 0.5f
                if (lastSpeedMoving != moving) {
                    DiagnosticLog.i("vhal", "SPEED edge: ${if (moving) "started moving" else "stopped"} (${"%.1f".format(mps * 3.6f)} km/h)")
                    lastSpeedMoving = moving
                }
            }
        }
    }

    @Synchronized
    private fun throttledSend() {
        val now = System.currentTimeMillis()
        if (now - lastSendTime < SEND_INTERVAL_MS) return
        lastSendTime = now

        val data = buildVehicleData()
        _latestVehicleData.value = data
        DiagnosticLog.d("vhal", "throttledSend: speed=${data.speedKmh} gear=${data.gearRaw} evBatt=${data.evBatteryLevelWh} evCap=${data.evBatteryCapacityWh} range=${data.rangeKm}")
        sendMessage(data)
    }

    @Synchronized
    private fun buildVehicleData(): ControlMessage.VehicleData {
        // Resolve property IDs from VehiclePropertyIds (same runtime resolution as registration)
        fun propId(name: String): Int? = resolveIntConstant("android.car.VehiclePropertyIds", name)

        val speedId = propId("PERF_VEHICLE_SPEED")
        val gearId = propId("GEAR_SELECTION")
        val parkBrakeId = propId("PARKING_BRAKE_ON")
        val nightId = propId("NIGHT_MODE")
        val evBatteryId = propId("EV_BATTERY_LEVEL")
        val evCapId = propId("INFO_EV_BATTERY_CAPACITY")
        val tempId = propId("ENV_OUTSIDE_TEMPERATURE")
        val chargeRateId = propId("EV_BATTERY_INSTANTANEOUS_CHARGE_RATE")
        val rangeId = propId("RANGE_REMAINING")
        val rpmId = propId("PERF_ENGINE_RPM")
        val portOpenId = propId("EV_CHARGE_PORT_OPEN")
        val portConnId = propId("EV_CHARGE_PORT_CONNECTED")
        val ignitionId = propId("IGNITION_STATE")
        // Extended properties
        val distUnitsId = propId("DISTANCE_DISPLAY_UNITS")
        val chargeStateId = propId("EV_CHARGE_STATE")
        val chargeTimeId = propId("EV_CHARGE_TIME_REMAINING")
        val curCapId = propId("EV_CURRENT_BATTERY_CAPACITY")
        val battTempId = propId("EV_BATTERY_AVERAGE_TEMPERATURE")
        val chargeLimitId = propId("EV_CHARGE_PERCENT_LIMIT")
        val chargeDrawId = propId("EV_CHARGE_CURRENT_DRAW_LIMIT")
        val regenId = propId("EV_BRAKE_REGENERATION_LEVEL")
        val stopModeId = propId("EV_STOPPING_MODE")

        val speed = speedId?.let { (currentValues[it] as? Float)?.let { v -> v * 3.6f } } // m/s → km/h
        val gearInt = gearId?.let { currentValues[it] as? Int }
        val gear = gearInt?.let { gearToString(it) }
        val parkingBrake = parkBrakeId?.let { currentValues[it] as? Boolean }
        val nightMode = nightId?.let { currentValues[it] as? Boolean }

        // EV battery: compute real % from level/capacity (both in Wh)
        val batteryLevelWh = evBatteryId?.let { currentValues[it] as? Float }
        val batteryCapacityWh = evCapId?.let { currentValues[it] as? Float }
        val batteryPct = if (batteryLevelWh != null && batteryCapacityWh != null && batteryCapacityWh > 0) {
            (batteryLevelWh / batteryCapacityWh * 100).toInt().coerceIn(0, 100)
        } else null

        val ambientTemp = tempId?.let { currentValues[it] as? Float }
        val chargeRate = EvEnergyValuePolicy.instantaneousMilliwattsToWatts(
            chargeRateId?.let { currentValues[it] as? Float },
        )
        val rangeRemaining = rangeId?.let { (currentValues[it] as? Float)?.let { v -> v / 1000f } } // m → km
        val rpmRaw = rpmId?.let { currentValues[it] as? Float }
        val rpmE3 = rpmRaw?.let { (it * 1000).toInt() }

        val chargePortOpen = portOpenId?.let { currentValues[it] as? Boolean }
        val chargePortConnected = portConnId?.let { currentValues[it] as? Boolean }
        val ignitionState = ignitionId?.let { currentValues[it] as? Int }

        // Extended EV properties
        val distanceDisplayUnits = distUnitsId?.let { currentValues[it] as? Int }
        val evChargeState = chargeStateId?.let { currentValues[it] as? Int }
        val evChargeTimeRemaining = chargeTimeId?.let { currentValues[it] as? Int }
        val evCurrentBatteryCapacity = curCapId?.let { currentValues[it] as? Float }
        val evBatteryTemp = battTempId?.let { currentValues[it] as? Float }
        val evChargePercentLimit = chargeLimitId?.let { currentValues[it] as? Float }
        val evChargeDrawLimit = chargeDrawId?.let { currentValues[it] as? Float }
        val evRegenLevel = regenId?.let { currentValues[it] as? Int }
        val evStoppingMode = stopModeId?.let { currentValues[it] as? Int }

        // Round-6 additions
        val odometerKm = propId("PERF_ODOMETER")?.let { (currentValues[it] as? Float)?.let { v -> v / 1000f } }
        val tirePressures = propId("TIRE_PRESSURE")?.let { id ->
            // TIRE_PRESSURE is per-area (one float per wheel). Per-area cache
            // entries arrive keyed by combined propId|areaId — until that
            // routing lands, surface whichever single scalar is present so
            // the diag screen can at least show one value.
            (currentValues[id] as? Float)?.let { listOf(it) }
        }
        val absActive = propId("ABS_ACTIVE")?.let { currentValues[it] as? Boolean }
        val tcActive = propId("TRACTION_CONTROL_ACTIVE")?.let { currentValues[it] as? Boolean }

        // HistoryProvider — latest sample (or null when patched / unavailable).
        // Cached snapshot is refreshed by historyPoller every 5s; we just read.
        val motorPowerSnapshot = latestMotorPowerSnapshot
        val motorPowerW = motorPowerSnapshot?.value
        val motorTorqueNm = latestMotorTorqueNm

        // Derive driving status: in a drive gear (not P/N/Unknown)
        val driving = gearInt != null && gearInt !in listOf(0, 1, 4)

        val observations = HashMap(evObservationMetadata)
        motorPowerSnapshot?.observation?.let {
            observations.putAll(mapOf(EvLearnedRateEstimator.MOTOR_POWER_OBSERVATION to it))
        }

        return ControlMessage.VehicleData(
            speedKmh = speed,
            gear = gear,
            gearRaw = gearInt,
            batteryPct = batteryPct,
            turnSignal = null,
            parkingBrake = parkingBrake,
            nightMode = nightMode,
            fuelLevelPct = null,
            rangeKm = rangeRemaining,
            lowFuel = null,
            odometerKm = odometerKm,
            ambientTempC = ambientTemp,
            steeringAngleDeg = null,
            headlight = null,
            hazardLights = null,
            rpmE3 = rpmE3,
            chargePortOpen = chargePortOpen,
            chargePortConnected = chargePortConnected,
            ignitionState = ignitionState,
            evChargeRateW = chargeRate,
            evBatteryLevelWh = batteryLevelWh,
            evBatteryCapacityWh = batteryCapacityWh,
            driving = driving,
            evChargeState = evChargeState,
            evChargeTimeRemainingSec = evChargeTimeRemaining,
            evCurrentBatteryCapacityWh = evCurrentBatteryCapacity,
            evBatteryTempC = evBatteryTemp,
            evChargePercentLimit = evChargePercentLimit,
            evChargeCurrentDrawLimitA = evChargeDrawLimit,
            evRegenBrakingLevel = evRegenLevel,
            evStoppingMode = evStoppingMode,
            distanceDisplayUnits = distanceDisplayUnits,
            carMake = carMake,
            carModel = carModel,
            carYear = carYear,
            fuelTypes = fuelTypes,
            evConnectorTypes = evConnectorTypes,
            tirePressuresKpa = tirePressures,
            absActive = absActive,
            tractionControlActive = tcActive,
            evMotorPowerW = motorPowerW,
            evMotorTorqueNm = motorTorqueNm,
            evObservationMetadata = java.util.Collections.unmodifiableMap(observations),
            vhalRegistrationGeneration = registrationGeneration,
        )
    }

    /**
     * Polls com.gm.vehicleinfo.HistoryProvider every 5s for the latest motor
     * power / torque samples. Silently no-ops if the provider is patched.
     * See recon_dump/gm-aaos-recon.md §15 (Finding F.2).
     */
    private fun startHistoryPoller() {
        historyPollerJob?.cancel()
        historyPollerJob = scope.launch {
            val available = try {
                com.openautolink.app.data.GmHistoryProviderRepository.isAvailable(context)
            } catch (_: Throwable) { false }
            if (!available) return@launch
            while (true) {
                try {
                    val sample = com.openautolink.app.data.GmHistoryProviderRepository
                        .latestMotorPowerSample(context)
                    latestMotorPowerSnapshot = sample?.let {
                        MotorPowerSnapshot(
                            value = sample.value,
                            observation = VehiclePropertyObservation(
                                timestampElapsedNanos = sample.sourceElapsedNanos,
                                receivedElapsedMs = SystemClock.elapsedRealtime(),
                                status = 0,
                                source = "history-provider-coherent",
                            ),
                        )
                    }
                    latestMotorTorqueNm = com.openautolink.app.data.GmHistoryProviderRepository
                        .latestMotorTorqueNm(context)
                } catch (_: Throwable) {
                    latestMotorPowerSnapshot = null
                    latestMotorTorqueNm = null
                }
                kotlinx.coroutines.delay(5_000L)
            }
        }
    }

    private fun gearToString(gear: Int): String = when (gear) {
        0 -> "Unknown"
        1 -> "N"     // GEAR_NEUTRAL
        2 -> "R"     // GEAR_REVERSE
        4 -> "P"     // GEAR_PARK
        8 -> "D"     // GEAR_DRIVE
        16 -> "1"    // GEAR_1
        32 -> "2"    // GEAR_2
        64 -> "3"    // GEAR_3
        128 -> "4"   // GEAR_4
        else -> "D"
    }

    private fun turnSignalToString(signal: Int): String = when (signal) {
        0 -> "none"
        1 -> "right"
        2 -> "left"
        else -> "none"
    }

    private fun cleanup() {
        val retiredObjects = synchronized(this) {
            registrationGeneration++
            isActive = false
            historyPollerJob?.cancel()
            historyPollerJob = null
            latestMotorPowerSnapshot = null
            latestMotorTorqueNm = null
            val objects = Triple(propertyManager, callbackProxy, carObject)
            trackedPropertyIds.clear()
            subscribedNames.clear()
            attemptedGrants.clear()
            pendingSafetySubscriptions.clear()
            bufferedSafetyObservations.clear()
            currentValues.clear()
            _propertyStatus.clear()
            evObservationMetadata.clear()
            callbackProxy = null
            carLifecycleProxy = null
            carObject = null
            propertyManager = null
            publishImmediate()
            objects
        }
        val (pm, callback, car) = retiredObjects
        if (pm != null && callback != null) {
            callback.javaClass.interfaces.firstOrNull()?.let { iface ->
                runCatching { pm.javaClass.getMethod("unsubscribePropertyEvents", iface).invoke(pm, callback) }
                runCatching { pm.javaClass.getMethod("unregisterCallback", iface).invoke(pm, callback) }
            }
        }
        runCatching { car?.javaClass?.getMethod("disconnect")?.invoke(car) }
    }
}
