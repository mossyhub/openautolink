package com.openautolink.app.input

import android.content.Context
import android.content.pm.PackageManager
import android.os.SystemClock
import android.util.Log
import com.openautolink.app.diagnostics.DiagnosticLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.lang.reflect.InvocationTargetException

/**
 * Always-on VHAL watcher for IGNITION_STATE and GEAR_SELECTION.
 *
 * Runs for the entire app process lifetime (started from [com.openautolink.app.OalApplication])
 * so that wake-handling code knows the real ignition state before deciding
 * whether to auto-connect. Without this, the "ghost wake" that AAOS dispatches
 * during shutdown (Activity.onCreate → onPause → onStop seen ~2 minutes after
 * ignition off) burns a full 45s auto-reconnect timeout into a dead WiFi.
 *
 * Kept deliberately minimal and independent of [VehicleDataForwarderImpl]:
 * - subscribes to only 2 properties (vs ~25 in the full forwarder)
 * - never sends anything; pure observer
 * - lives on the Application scope, not a Session scope
 */
object IgnitionMonitor {

    private const val TAG = "IgnitionMonitor"
    private const val IGNITION_STATE_ID = 0x11400409
    private const val GEAR_SELECTION_ID = 0x11400400
    /** Gear position 4 == PARKING per android.car.VehicleGear. */
    private const val GEAR_PARK = 4

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _ignitionState = MutableStateFlow<Int?>(null)
    /** IGNITION_STATE: 0=UNDEFINED, 1=LOCK, 2=OFF, 3=ACC, 4=ON, 5=START. Null until first read. */
    val ignitionState: StateFlow<Int?> = _ignitionState.asStateFlow()

    private val _gearSelection = MutableStateFlow<Int?>(null)
    /** Raw VehicleGear enum value. Null until first read. */
    val gearSelection: StateFlow<Int?> = _gearSelection.asStateFlow()

    /** elapsedRealtime when ignitionState last transitioned to a non-ON value. */
    @Volatile
    private var ignitionOffStampMs: Long = 0L

    @Volatile
    private var started = false

    private var carObject: Any? = null
    private var propertyManager: Any? = null
    private var callbackProxy: Any? = null

    @Volatile private var lastLoggedIgnition: Int? = null
    @Volatile private var lastLoggedGear: Int? = null

    /**
     * Returns true when ignition is known to be off (or about to be — LOCK is
     * also a non-driving state). Returns false when ignition is ON/START/ACC
     * OR when state is null/unknown (treat unknown as "might be on" so we
     * don't block the very first auto-connect on a fresh boot before the
     * Car API has reported a value).
     */
    fun isOffOrLocked(): Boolean = _ignitionState.value.let { it == 1 || it == 2 }

    /** Milliseconds since ignition last transitioned to OFF, or Long.MAX_VALUE if never. */
    fun msSinceIgnitionOff(): Long {
        val stamp = ignitionOffStampMs
        return if (stamp == 0L) Long.MAX_VALUE else SystemClock.elapsedRealtime() - stamp
    }

    fun start(context: Context) {
        synchronized(this) {
            if (started) return
            started = true
        }
        scope.launch {
            try {
                if (!context.packageManager.hasSystemFeature(PackageManager.FEATURE_AUTOMOTIVE)) {
                    DiagnosticLog.i(TAG, "FEATURE_AUTOMOTIVE not present — ignition monitor disabled")
                    return@launch
                }
                connectToCar(context)
                subscribe()
                DiagnosticLog.i(TAG, "Started")
            } catch (e: Throwable) {
                val root = e.rootCause()
                Log.w(TAG, "Failed to start: ${root.message}")
                DiagnosticLog.w(TAG, "Failed to start: ${root.javaClass.simpleName}: ${root.message}")
            }
        }
    }

    private fun connectToCar(context: Context) {
        val carClass = Class.forName("android.car.Car")
        val car = try {
            carClass.getMethod("createCar", Context::class.java).invoke(null, context)
        } catch (_: NoSuchMethodException) {
            carClass.getMethod("createCar", Context::class.java, android.os.Handler::class.java)
                .invoke(null, context, null)
        } ?: throw IllegalStateException("Car.createCar returned null")

        val wasConnected = invokeBoolean(car, "isConnected") ?: false
        val wasConnecting = invokeBoolean(car, "isConnecting") ?: false
        if (!wasConnected && !wasConnecting) {
            try { carClass.getMethod("connect").invoke(car) } catch (_: Throwable) {}
        }
        val start = SystemClock.elapsedRealtime()
        while (SystemClock.elapsedRealtime() - start < 2000L) {
            if (invokeBoolean(car, "isConnected") == true) break
            Thread.sleep(50)
        }
        carObject = car

        val propertyServiceName = carClass.getField("PROPERTY_SERVICE").get(null) as String
        propertyManager = carClass.getMethod("getCarManager", String::class.java)
            .invoke(car, propertyServiceName)
            ?: throw IllegalStateException("CarPropertyManager is null")
    }

    private fun subscribe() {
        val pm = propertyManager ?: return
        val cbInterface = Class.forName(
            "android.car.hardware.property.CarPropertyManager\$CarPropertyEventCallback"
        )
        callbackProxy = java.lang.reflect.Proxy.newProxyInstance(
            cbInterface.classLoader, arrayOf(cbInterface)
        ) { proxy, method, args ->
            when (method.name) {
                "onChangeEvent" -> { args?.firstOrNull()?.let(::handleChange); null }
                "onErrorEvent" -> null
                "toString" -> "IgnitionMonitorCallback"
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === args?.firstOrNull()
                else -> null
            }
        }

        for (propId in intArrayOf(IGNITION_STATE_ID, GEAR_SELECTION_ID)) {
            // Initial read
            try {
                val pv = pm.javaClass
                    .getMethod("getProperty", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
                    .invoke(pm, propId, 0)
                if (pv != null) handleChange(pv)
            } catch (_: Throwable) {}
            // Subscribe (API 34+ subscribePropertyEvents, fallback registerCallback)
            val subMethod = pm.javaClass.methods.firstOrNull {
                it.name == "subscribePropertyEvents" && it.parameterTypes.size == 3 &&
                    it.parameterTypes[0] == Int::class.javaPrimitiveType &&
                    it.parameterTypes[1] == Float::class.javaPrimitiveType &&
                    it.parameterTypes[2] == cbInterface
            }
            val regMethod = pm.javaClass.methods.firstOrNull {
                it.name == "registerCallback" && it.parameterTypes.size == 3 &&
                    it.parameterTypes[0] == cbInterface &&
                    it.parameterTypes[1] == Int::class.javaPrimitiveType &&
                    it.parameterTypes[2] == Float::class.javaPrimitiveType
            }
            try {
                when {
                    subMethod != null -> subMethod.invoke(pm, propId, 0.0f, callbackProxy)
                    regMethod != null -> regMethod.invoke(pm, callbackProxy, propId, 0.0f)
                }
            } catch (t: Throwable) {
                DiagnosticLog.w(TAG, "subscribe(${propId.toString(16)}): ${t.rootCause().javaClass.simpleName}")
            }
        }
    }

    private fun handleChange(propertyValue: Any) {
        try {
            val cls = propertyValue.javaClass
            val propId = cls.getMethod("getPropertyId").invoke(propertyValue) as? Int ?: return
            val value = cls.getMethod("getValue").invoke(propertyValue) ?: return
            when (propId) {
                IGNITION_STATE_ID -> {
                    val v = value as? Int ?: return
                    val prev = _ignitionState.value
                    _ignitionState.value = v
                    if (lastLoggedIgnition != v) {
                        val name = when (v) {
                            0 -> "UNDEFINED"; 1 -> "LOCK"; 2 -> "OFF"
                            3 -> "ACC"; 4 -> "ON"; 5 -> "START"; else -> "?"
                        }
                        DiagnosticLog.i(TAG, "IGNITION_STATE → $v ($name) [was ${lastLoggedIgnition ?: "?"}]")
                        lastLoggedIgnition = v
                    }
                    // Stamp the transition into a non-ON state so callers can
                    // reason about "how long ago did the car shut down."
                    val wasOn = prev == 4 || prev == 5
                    val isOn = v == 4 || v == 5
                    if (wasOn && !isOn) ignitionOffStampMs = SystemClock.elapsedRealtime()
                }
                GEAR_SELECTION_ID -> {
                    val v = value as? Int ?: return
                    _gearSelection.value = v
                    if (lastLoggedGear != v) {
                        DiagnosticLog.i(TAG, "GEAR_SELECTION → $v [was ${lastLoggedGear ?: "?"}]")
                        if (v == GEAR_PARK && lastLoggedGear != null) {
                            DiagnosticLog.i(TAG, "PARKED")
                        }
                        lastLoggedGear = v
                    }
                }
            }
        } catch (_: Throwable) { /* best-effort observer */ }
    }

    private fun invokeBoolean(target: Any, methodName: String): Boolean? = try {
        target.javaClass.getMethod(methodName).invoke(target) as? Boolean
    } catch (_: Throwable) { null }

    private fun Throwable.rootCause(): Throwable {
        val cause = if (this is InvocationTargetException) targetException else this.cause
        return cause?.rootCause() ?: this
    }
}
