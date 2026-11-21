package android.car.hardware.property

class CarPropertyManager {
    // Test-only platform constants, absent from the existing minimal reflection fixture.
    // Values are the standard rates named by this packet's contract; not new product APIs.
    companion object {
        const val SENSOR_RATE_ONCHANGE = 0f
        const val SENSOR_RATE_UI = 5f
        const val SENSOR_RATE_FAST = 10f
    }
    interface CarPropertyEventCallback {
        fun onChangeEvent(value: Any)
        fun onErrorEvent(propertyId: Int, areaId: Int)
    }
}
