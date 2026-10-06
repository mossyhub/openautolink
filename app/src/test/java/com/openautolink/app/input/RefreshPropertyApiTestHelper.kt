package android.car.hardware.property

class CarPropertyManager {
    interface CarPropertyEventCallback {
        fun onChangeEvent(value: Any)
        fun onErrorEvent(propertyId: Int, areaId: Int)
    }
}
