package com.openautolink.app.drivecard

import com.openautolink.app.transport.ControlMessage
import java.util.Locale

internal object DriveCardUnits {
    // AOSP android.car.VehicleUnit, DISTANCE_DISPLAY_UNITS. Not speed-unit enums.
    // https://android.googlesource.com/platform/packages/services/Car/+/refs/heads/main/car-lib/src/android/car/VehicleUnit.java
    fun imperial(preference: String, vehicleUnits: Int?, locale: Locale=Locale.getDefault()): Boolean = when(preference) {
        "imperial" -> true
        "metric" -> false
        else -> when(vehicleUnits) {
            0x24 -> true
            0x20,0x21,0x23 -> false
            else -> locale.country in setOf("US","LR","MM") // existing DistanceFormatter policy
        }
    }
    /** ON_CHANGE unit configuration may have an old event timestamp. A recent real receipt or
     * exact serialized status read qualifies it; neither a timer nor a bare cached int can. */
    fun currentVehicleUnits(data: ControlMessage.VehicleData?, now: Long): Int? {
        data ?: return null
        val source=data.evObservationMetadata["DISTANCE_DISPLAY_UNITS"] ?: return null
        val at=source.timestampElapsedNanos?.div(1_000_000) ?: return null
        if(source.source!="vhal" || source.status!=0 || !source.subscriptionActive ||
            data.vhalRegistrationGeneration==null || data.vhalRegistrationGeneration<=0 ||
            source.registrationGeneration!=data.vhalRegistrationGeneration ||
            source.propertyId != 0x11400600 || at !in 0..now || now-source.receivedElapsedMs !in 0..30_000L) return null
        if(!com.openautolink.app.input.VehicleTelemetryFreshness.sourceTimeConfirmed(
                source,"DISTANCE_DISPLAY_UNITS",now,30_000L))return null
        return data.distanceDisplayUnits?.takeIf {it in setOf(0x20,0x21,0x23,0x24)}
    }
    fun efficiency(kmPerKwh: Double, imperial: Boolean)=if(imperial)kmPerKwh/1.609344 else kmPerKwh
}
