package com.openautolink.app.carplay

import android.os.SystemClock
import com.openautolink.app.input.ProcessVehicleDataRuntime
import com.openautolink.app.transport.ControlMessage
import com.openautolink.carplaylab.intake.OalEvSnapshot
import kotlinx.coroutines.flow.StateFlow

/** Read-only process StateFlow adapter. Does not attach/replace AA's single projection consumer. */
class OalCarPlayVehicleSnapshotAdapter(
    private val source: () -> StateFlow<ControlMessage.VehicleData>? = ProcessVehicleDataRuntime::latestVehicleData,
    private val elapsedMs: () -> Long = SystemClock::elapsedRealtime,
) {
    fun snapshot(): OalEvSnapshot? {
        val data = source()?.value ?: return null
        // FuelType.ELECTRIC=10. Mixed-fuel RANGE_REMAINING is not proven electric-only range.
        if (data.fuelTypes?.toSet() != setOf(10)) return null
        val now = elapsedMs()
        val generation = data.vhalRegistrationGeneration ?: return null
        fun available(property: String): Boolean {
            val observation = data.evObservationMetadata[property] ?: return false
            val timestamp = observation.timestampElapsedNanos ?: return false
            val sampleMs = timestamp / 1_000_000
            return generation > 0 && observation.registrationGeneration == generation &&
                observation.source == "vhal" && observation.subscriptionActive && observation.status == 0 &&
                timestamp > 0 && now >= sampleMs && now - sampleMs <= 30_000 &&
                now >= observation.receivedElapsedMs && now - observation.receivedElapsedMs <= 30_000
        }
        fun value(raw: Float?, property: String, limit: Double): Float? = raw?.takeIf {
            available(property) && it.isFinite() && it >= 0 && it.toDouble() <= limit
        }
        val range = value(data.rangeKm,"RANGE_REMAINING",65535.0)?.toInt()
        val max = value(data.evCurrentBatteryCapacityWh,"EV_CURRENT_BATTERY_CAPACITY",4294967295.0)
            ?.takeIf { it > 0 }?.toLong()
        val current = value(data.evBatteryLevelWh,"EV_BATTERY_LEVEL",4294967295.0)?.toLong()
            ?.takeIf { max == null || it <= max }
        if (range == null && current == null && max == null) return null
        // INFO_EV_BATTERY_CAPACITY is nominal when new, not current maximum. batteryPct is derived,
        // not OEM displayed SOC. Charge-port connectivity and power sign do not prove isCharging.
        return OalEvSnapshot(range,current,max)
    }
}
