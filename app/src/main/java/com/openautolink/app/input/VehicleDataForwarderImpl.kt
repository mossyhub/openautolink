package com.openautolink.app.input

import android.content.Context
import android.content.pm.PackageManager
import android.os.SystemClock
import android.util.Log
import com.openautolink.app.diagnostics.DiagnosticLog
import com.openautolink.app.transport.ControlMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.lang.reflect.InvocationTargetException
import java.util.concurrent.ConcurrentHashMap

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

    override var isActive: Boolean = false
        private set

    // Background scope for Car API calls (matches app_v1 pattern)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // Car API objects obtained via reflection
    private var carObject: Any? = null
    private var propertyManager: Any? = null
    private var callbackProxy: Any? = null  // ONE shared callback for all properties (app_v1 pattern)
    private val trackedPropertyIds = mutableSetOf<Int>()  // registered property IDs for dispatch

    // Latest values — updated by property callbacks, sent as batch
    private val currentValues = ConcurrentHashMap<Int, Any>()
    private var lastSendTime = 0L

    // HistoryProvider polling cache (Finding F.2). Refreshed every 5s on a
    // dedicated coroutine; nullable when the provider is patched or returns
    // no data. The build path falls back to SOC-derived math transparently.
    @Volatile private var latestMotorPowerW: Float? = null
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

    private val _propertyStatus = mutableMapOf<String, String>()
    override val propertyStatus: Map<String, String> get() = _propertyStatus.toMap()

    @Volatile private var startInFlight = false

    override fun start() {
        if (isActive) return
        // Idempotency guard: if start() is already running on a coroutine,
        // a second concurrent call must NOT launch another connectToCar +
        // registerProperties — they race against each other and the
        // emulator silently fails property subscription.
        synchronized(this) {
            if (isActive || startInFlight) return
            startInFlight = true
        }
        // Run on background thread — Car API calls can block (connect, waitForConnected)
        scope.launch {
            try {
                connectToCar()
                readStaticVehicleInfo()
                registerProperties()
                isActive = true
                Log.i(TAG, "Vehicle data forwarding started")
                DiagnosticLog.i("vhal", "Vehicle data forwarding started")
                // Re-emit current data so collectors see isActive=true
                _latestVehicleData.value = buildVehicleData()
                startHistoryPoller()
            } catch (e: Exception) {
                val root = e.rootCause()
                Log.w(TAG, "Failed to start vehicle data forwarding: ${root.message}")
                DiagnosticLog.w("vhal", "Failed to start: ${root.javaClass.simpleName}: ${root.message}")
                cleanup()
            } finally {
                startInFlight = false
            }
        }
    }

    override fun stop() {
        if (!isActive) return
        cleanup()
        isActive = false
        Log.i(TAG, "Vehicle data forwarding stopped")
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

        // Try single-arg createCar(Context) first, fall back to createCar(Context, Handler)
        val car = try {
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
            DiagnosticLog.i("vhal", "Car service did not report connected within timeout; continuing best-effort")
        }
        carObject = car

        // Get CarPropertyManager
        val propertyServiceName = carClass.getField("PROPERTY_SERVICE").get(null) as String
        propertyManager = carClass.getMethod("getCarManager", String::class.java)
            .invoke(car, propertyServiceName)
            ?: throw IllegalStateException("CarPropertyManager is null")

        Log.i(TAG, "Connected to Car API via reflection")
        DiagnosticLog.i("vhal", "Connected to Car API")
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
    private fun readStaticVehicleInfo() {
        val pm = propertyManager ?: return
        val pmClass = pm::class.java

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

        carMake = readStringProp("INFO_MAKE")
        carModel = readStringProp("INFO_MODEL")
        carYear = readIntProp("INFO_MODEL_YEAR")?.toString()

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

        fuelTypes = readIntArrayProp("INFO_FUEL_TYPE")
        evConnectorTypes = readIntArrayProp("INFO_EV_CONNECTOR_TYPE")

        if (carMake != null || carModel != null) {
            DiagnosticLog.i("vhal", "Vehicle identity: $carMake $carModel $carYear fuel=$fuelTypes ev_conn=$evConnectorTypes")
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

    private fun registerProperties() {
        val pm = propertyManager ?: return
        val pmClass = pm::class.java

        // Resolve callback interface ONCE and create ONE shared proxy (app_v1 pattern)
        val callbackInterface = try {
            Class.forName("android.car.hardware.property.CarPropertyManager\$CarPropertyEventCallback")
        } catch (e: ClassNotFoundException) {
            DiagnosticLog.w("vhal", "CarPropertyEventCallback class not found")
            return
        }
        callbackProxy = createCallbackProxy(callbackInterface)

        // Property definitions: fieldName → (permission, rateField)
        data class PropDef(val fieldName: String, val permission: String?, val rateField: String = "SENSOR_RATE_ONCHANGE")
        val properties = listOf(
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

        var subscribed = 0
        for (prop in properties) {
            // Resolve property ID from VehiclePropertyIds at runtime (app_v1 pattern)
            val propId = resolveIntConstant("android.car.VehiclePropertyIds", prop.fieldName)
            if (propId == null) {
                DiagnosticLog.d("vhal", "${prop.fieldName}: not in this SDK")
                _propertyStatus[prop.fieldName] = "not_in_sdk"
                continue
            }

            // Check permission before subscribing
            if (prop.permission != null && context.checkSelfPermission(prop.permission) != PackageManager.PERMISSION_GRANTED) {
                DiagnosticLog.i("vhal", "${prop.fieldName}: permission not granted (${prop.permission})")
                _propertyStatus[prop.fieldName] = "permission_denied:${prop.permission}"
                continue
            }

            // Check if property is exposed by this vehicle's HAL
            val config = try {
                pmClass.getMethod("getCarPropertyConfig", Int::class.javaPrimitiveType)
                    .invoke(pm, propId)
            } catch (t: Throwable) {
                DiagnosticLog.d("vhal", "${prop.fieldName}: config lookup failed: ${t.rootCause().message}")
                null
            }
            if (config == null) {
                DiagnosticLog.i("vhal", "${prop.fieldName}: not exposed by this vehicle/HAL")
                _propertyStatus[prop.fieldName] = "not_exposed"
                continue
            }

            // Add to tracked set BEFORE initial read so handleChangeEvent doesn't discard it.
            // Static properties (e.g., INFO_EV_BATTERY_CAPACITY) never get callback updates,
            // so the initial read is their only chance to populate currentValues.
            trackedPropertyIds.add(propId)

            // Read initial value
            try {
                val pv = pmClass.getMethod("getProperty", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
                    .invoke(pm, propId, 0)
                if (pv != null) {
                    val initVal = try { pv.javaClass.getMethod("getValue").invoke(pv) } catch (_: Throwable) { null }
                    DiagnosticLog.d("vhal", "${prop.fieldName}: initial read = $initVal")
                    handleChangeEvent(pv)
                } else {
                    DiagnosticLog.d("vhal", "${prop.fieldName}: initial read returned null")
                }
            } catch (t: Throwable) {
                DiagnosticLog.d("vhal", "${prop.fieldName}: initial read: ${t.rootCause().javaClass.simpleName}: ${t.rootCause().message}")
            }

            // Subscribe using shared callback (app_v1's subscribe pattern)
            val ok = subscribe(pm, callbackInterface, callbackProxy!!, propId, prop.rateField)
            if (ok) {
                subscribed++
                _propertyStatus[prop.fieldName] = "subscribed"
                DiagnosticLog.d("vhal", "${prop.fieldName}: subscribed")
            } else {
                _propertyStatus[prop.fieldName] = "rejected"
                DiagnosticLog.w("vhal", "${prop.fieldName}: subscription rejected")
            }
        }

        Log.i(TAG, "Subscribed to $subscribed/${properties.size} vehicle properties")
        DiagnosticLog.i("vhal", "Subscribed to $subscribed/${properties.size} vehicle properties")
        DiagnosticLog.i("vhal", "currentValues after subscription: ${currentValues.size} entries")

        // Fire initial data with all values populated from initial reads.
        // Reset lastSendTime to bypass throttle — earlier throttled sends during
        // subscription may have sent incomplete data (missing EV battery values).
        if (currentValues.isNotEmpty()) {
            lastSendTime = 0L  // force send regardless of throttle
            val data = buildVehicleData()
            _latestVehicleData.value = data
            sendMessage(data)
        }
    }

    /** Create ONE shared callback proxy for all properties (app_v1 pattern). */
    private fun createCallbackProxy(callbackInterface: Class<*>): Any {
        return java.lang.reflect.Proxy.newProxyInstance(
            callbackInterface.classLoader,
            arrayOf(callbackInterface),
        ) { proxy, method, args ->
            when (method.name) {
                "onChangeEvent" -> {
                    args?.firstOrNull()?.let(::handleChangeEvent)
                    null
                }
                "onErrorEvent" -> {
                    val propertyId = (args?.getOrNull(0) as? Int) ?: -1
                    Log.w(TAG, "Property error callback for $propertyId")
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
    private fun handleChangeEvent(propertyValue: Any) {
        try {
            val propertyId = propertyValue.javaClass.getMethod("getPropertyId").invoke(propertyValue) as? Int ?: return
            if (propertyId !in trackedPropertyIds) return
            val value = propertyValue.javaClass.getMethod("getValue").invoke(propertyValue) ?: return
            DiagnosticLog.d("vhal", "prop 0x${propertyId.toString(16)}: $value")

            // Detect ignition state transitions to ON(4)/START(5) for wake signaling
            val ignitionId = VEHICLE_PROPERTY_ID_FALLBACK["IGNITION_STATE"]
            if (propertyId == ignitionId && value is Int) {
                val prev = previousIgnitionState
                previousIgnitionState = value
                // IGNITION_STATE: 0=UNDEFINED, 1=LOCK, 2=OFF, 3=ACC, 4=ON, 5=START
                if (prev != null && prev < 4 && value >= 4) {
                    Log.i(TAG, "Ignition ON detected (was $prev, now $value)")
                    DiagnosticLog.i("vhal", "Ignition ON detected ($prev → $value)")
                    onIgnitionOn?.invoke(value)
                }
            }

            // Edge-log a small set of life-cycle-relevant transitions at INFO
            // so postmortem captures show when the car's state actually changed
            // (vs the high-cadence DEBUG line above which is one-per-tick).
            edgeLog(propertyId, value)

            currentValues[propertyId] = value
            throttledSend()
        } catch (e: Throwable) {
            Log.w(TAG, "handleChangeEvent: ${e.rootCause().message}")
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
        val chargeRate = chargeRateId?.let { currentValues[it] as? Float }
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
        val motorPowerW = latestMotorPowerW
        val motorTorqueNm = latestMotorTorqueNm

        // Derive driving status: in a drive gear (not P/N/Unknown)
        val driving = gearInt != null && gearInt !in listOf(0, 1, 4)

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
                    latestMotorPowerW = com.openautolink.app.data.GmHistoryProviderRepository
                        .latestMotorPowerW(context)
                    latestMotorTorqueNm = com.openautolink.app.data.GmHistoryProviderRepository
                        .latestMotorTorqueNm(context)
                } catch (_: Throwable) {
                    latestMotorPowerW = null
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
        historyPollerJob?.cancel()
        historyPollerJob = null
        latestMotorPowerW = null
        latestMotorTorqueNm = null

        val pm = propertyManager
        val callback = callbackProxy

        // Unregister the shared callback (app_v1 pattern)
        if (pm != null && callback != null) {
            val iface = callback.javaClass.interfaces.firstOrNull()
            if (iface != null) {
                runCatching {
                    pm.javaClass.getMethod("unsubscribePropertyEvents", iface).invoke(pm, callback)
                }
                runCatching {
                    pm.javaClass.getMethod("unregisterCallback", iface).invoke(pm, callback)
                }
            }
        }

        // Disconnect car
        runCatching {
            carObject?.javaClass?.getMethod("disconnect")?.invoke(carObject)
        }

        trackedPropertyIds.clear()
        currentValues.clear()
        callbackProxy = null
        carObject = null
        propertyManager = null
    }
}
