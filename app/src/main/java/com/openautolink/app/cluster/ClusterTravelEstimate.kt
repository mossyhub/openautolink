package com.openautolink.app.cluster

import androidx.car.app.model.DateTimeWithZone
import androidx.car.app.model.Distance
import androidx.car.app.navigation.model.TravelEstimate
import java.time.ZonedDateTime

/** AndroidX's serialized model allows absent distance/arrival, but its public builder does not.
 * Use its serialization constructor only for unknown estimates; never fabricate a timestamp.
 * These @KeepFields protocol names are pinned by executable tests against the packaged library.
 */
internal object ClusterTravelEstimate {
    @JvmStatic fun build(distanceMeters: Int?, seconds: Long?, now: ZonedDateTime): TravelEstimate =
        buildDistance(distanceMeters?.let { Distance.create(it.toDouble(), Distance.UNIT_METERS) }, seconds, now)

    fun buildDistance(distance: Distance?, seconds: Long?, now: ZonedDateTime): TravelEstimate {
        val arrival = seconds?.takeIf { it >= 0 }?.let { now.plusSeconds(it) }
        if (distance != null && arrival != null)
            return TravelEstimate.Builder(distance, arrival).setRemainingTimeSeconds(seconds!!).build()
        val value = TravelEstimate::class.java.getDeclaredConstructor().apply { isAccessible = true }.newInstance()
        fun set(name: String, fieldValue: Any?) {
            TravelEstimate::class.java.getDeclaredField(name).apply { isAccessible = true }.set(value, fieldValue)
        }
        set("mRemainingDistance", distance)
        set("mRemainingTimeSeconds", seconds?.takeIf { it >= 0 } ?: TravelEstimate.REMAINING_TIME_UNKNOWN)
        set("mArrivalTimeAtDestination", arrival?.let(DateTimeWithZone::create))
        return value
    }
}
