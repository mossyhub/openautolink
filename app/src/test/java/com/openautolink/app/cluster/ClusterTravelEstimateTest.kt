package com.openautolink.app.cluster

import androidx.car.app.navigation.model.TravelEstimate
import org.junit.Assert.*
import org.junit.Test
import java.time.ZonedDateTime

class ClusterTravelEstimateTest {
    private fun estimate(distance: Int?, seconds: Long?): TravelEstimate {
        val type = runCatching { Class.forName("com.openautolink.app.cluster.ClusterTravelEstimate") }.getOrNull()
        assertNotNull("Cluster must support estimates with absent arrival/distance", type)
        return type!!.getMethod("build", Integer::class.java, java.lang.Long::class.java, ZonedDateTime::class.java)
            .invoke(type.getField("INSTANCE").get(null), distance, seconds, ZonedDateTime.parse("2026-01-01T12:00:00Z")) as TravelEstimate
    }
    @Test fun unknownArrivalDoesNotBecomeArrivalNow() {
        val value = estimate(200, null)
        assertNull(value.arrivalTimeAtDestination)
        assertEquals(TravelEstimate.REMAINING_TIME_UNKNOWN, value.remainingTimeSeconds)
        assertEquals(200.0, value.remainingDistance!!.displayDistance, 0.0)
    }
    @Test fun unknownDistanceDoesNotBecomeZero() {
        val value = estimate(null, null)
        assertNull(value.remainingDistance)
        assertNull(value.arrivalTimeAtDestination)
    }
    @Test fun knownZeroIsDistinctFromUnknown() {
        val value = estimate(0, 0L)
        assertEquals(0.0, value.remainingDistance!!.displayDistance, 0.0)
        assertEquals(0L, value.remainingTimeSeconds)
        assertEquals(ZonedDateTime.parse("2026-01-01T12:00:00Z").toInstant().toEpochMilli(), value.arrivalTimeAtDestination!!.timeSinceEpochMillis)
    }
}
