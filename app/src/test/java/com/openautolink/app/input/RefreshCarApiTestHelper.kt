package android.car

import android.content.Context

/** Executable reflection API for host tests; no Android car library is available to JVM tests. */
class Car(val manager: Any) {
    @Volatile var disconnects = 0
    fun isConnected() = true
    fun isConnecting() = false
    fun connect() {}
    fun getCarManager(name: String): Any = manager
    fun disconnect() { disconnects++ }
    companion object {
        @JvmField val PROPERTY_SERVICE = "property"
        @Volatile var factory: (() -> Car)? = null
        @JvmStatic fun createCar(context: Context): Car = checkNotNull(factory) { "test car factory not installed" }.invoke()
    }
}
