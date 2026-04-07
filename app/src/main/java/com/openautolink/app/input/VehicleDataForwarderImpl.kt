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
    private val sendMessage: (ControlMessage.VehicleData) -> Unit
) : VehicleDataForwarder {

    companion object {
        private const val TAG = "VehicleDataForwarder"

        // Minimum interval between vehicle data sends (ms)
        private const val SEND_INTERVAL_MS = 500L
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

    private val _latestVehicleData = MutableStateFlow(ControlMessage.VehicleData())
    override val latestVehicleData: StateFlow<ControlMessage.VehicleData> = _latestVehicleData.asStateFlow()

    override fun start() {
        if (isActive) return
        // Run on background thread — Car API calls can block (connect, waitForConnected)
        scope.launch {
            try {
                connectToCar()
                registerProperties()
                isActive = true
                Log.i(TAG, "Vehicle data forwarding started")
                DiagnosticLog.i("vhal", "Vehicle data forwarding started")
                // Re-emit current data so collectors see isActive=true
                _latestVehicleData.value = buildVehicleData()
            } catch (e: Exception) {
                val root = e.rootCause()
                Log.w(TAG, "Failed to start vehicle data forwarding: ${root.message}")
                DiagnosticLog.w("vhal", "Failed to start: ${root.javaClass.simpleName}: ${root.message}")
                cleanup()
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
        )

        var subscribed = 0
        for (prop in properties) {
            // Resolve property ID from VehiclePropertyIds at runtime (app_v1 pattern)
            val propId = resolveIntConstant("android.car.VehiclePropertyIds", prop.fieldName)
            if (propId == null) {
                DiagnosticLog.d("vhal", "${prop.fieldName}: not in this SDK")
                continue
            }

            // Check permission before subscribing
            if (prop.permission != null && context.checkSelfPermission(prop.permission) != PackageManager.PERMISSION_GRANTED) {
                DiagnosticLog.i("vhal", "${prop.fieldName}: permission not granted (${prop.permission})")
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
                continue
            }

            // Read initial value
            try {
                val pv = pmClass.getMethod("getProperty", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
                    .invoke(pm, propId, 0)
                if (pv != null) {
                    handleChangeEvent(pv)
                }
            } catch (t: Throwable) {
                DiagnosticLog.d("vhal", "${prop.fieldName}: initial read: ${t.rootCause().javaClass.simpleName}: ${t.rootCause().message}")
            }

            // Subscribe using shared callback (app_v1's subscribe pattern)
            trackedPropertyIds.add(propId)
            val ok = subscribe(pm, callbackInterface, callbackProxy!!, propId, prop.rateField)
            if (ok) {
                subscribed++
                DiagnosticLog.d("vhal", "${prop.fieldName}: subscribed")
            } else {
                DiagnosticLog.w("vhal", "${prop.fieldName}: subscription rejected")
            }
        }

        Log.i(TAG, "Subscribed to $subscribed/${properties.size} vehicle properties")
        DiagnosticLog.i("vhal", "Subscribed to $subscribed/${properties.size} vehicle properties")

        // Fire initial data if any values were read
        if (currentValues.isNotEmpty()) {
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

    /** Resolve a static int constant from a class by field name (app_v1 pattern). */
    private fun resolveIntConstant(className: String, fieldName: String): Int? {
        return try {
            Class.forName(className).getField(fieldName).getInt(null)
        } catch (_: Throwable) { null }
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
            currentValues[propertyId] = value
            throttledSend()
        } catch (e: Throwable) {
            Log.w(TAG, "handleChangeEvent: ${e.rootCause().message}")
        }
    }

    @Synchronized
    private fun throttledSend() {
        val now = System.currentTimeMillis()
        if (now - lastSendTime < SEND_INTERVAL_MS) return
        lastSendTime = now

        val data = buildVehicleData()
        _latestVehicleData.value = data
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
            odometerKm = null,
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
            driving = driving
        )
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
