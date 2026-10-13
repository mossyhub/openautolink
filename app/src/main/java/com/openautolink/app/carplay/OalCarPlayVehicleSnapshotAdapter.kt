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
    private val lastDiagnostics = mutableMapOf<Pair<String,String>,Long>()
    fun snapshot(): OalEvSnapshot? = snapshot {}

    /** Stable HAL modes describe value changes, not heartbeat cadence. Receipt liveness stays bounded. */
    @Synchronized fun snapshot(receipt: (String) -> Unit): OalEvSnapshot? {
        val data = source()?.value
        val now = elapsedMs()
        val generation = data?.vhalRegistrationGeneration
        fun age(timestampMs: Long?): Long = if (timestampMs == null || timestampMs < 0 || now < timestampMs) -1
            else (now-timestampMs).coerceAtMost(99_999_999)
        fun reason(raw: Float?, property: String, limit: Double, positive: Boolean=false): String {
            if (raw == null) return "absent"
            if (data?.fuelTypes?.toSet() != setOf(10)) return "fuel"
            val observation = data.evObservationMetadata[property] ?: return "metadata"
            if (generation == null || generation <= 0 || observation.registrationGeneration != generation) return "generation"
            if (observation.source != "vhal") return "source"
            if (!observation.subscriptionActive) return "inactive"
            if (observation.status != 0) return "status"
            val timestamp = observation.timestampElapsedNanos ?: return "timestamp"
            if (timestamp <= 0 || now < timestamp/1_000_000) return "timestamp"
            // An old event time is legitimate for a confirmed unchanged value. Only an actual
            // discovered STATIC/ONCHANGE config grants this exception; requested sample rate does not.
            if (observation.changeMode !in setOf(0, 1) && now-timestamp/1_000_000 > 30_000) return "source_age"
            if (now < observation.receivedElapsedMs || observation.receivedElapsedMs < 0 || now-observation.receivedElapsedMs > 30_000) return "receipt_age"
            if (!raw.isFinite() || raw < 0 || raw.toDouble() > limit || (positive && raw <= 0)) return "bounds"
            return "available"
        }
        val rangeReason = reason(data?.rangeKm,"RANGE_REMAINING",65535.0)
        val maxReason = reason(data?.evCurrentBatteryCapacityWh,"EV_CURRENT_BATTERY_CAPACITY",4294967295.0,true)
        var currentReason = reason(data?.evBatteryLevelWh,"EV_BATTERY_LEVEL",4294967295.0)
        val range = data?.rangeKm?.takeIf {rangeReason=="available"}?.toInt()
        val max = data?.evCurrentBatteryCapacityWh?.takeIf {maxReason=="available"}?.toLong()
        var current = data?.evBatteryLevelWh?.takeIf {currentReason=="available"}?.toLong()
        if (current != null && max != null && current > max) { current=null; currentReason="inconsistent" }
        fun report(label: String,property: String,result: String) {
            val key=label to result
            val last=lastDiagnostics[key]
            if (last != null && now >= last && now-last < 1000) return
            lastDiagnostics[key]=now
            val observation=data?.evObservationMetadata?.get(property)
            val status=observation?.status?.takeIf {it in 0..9} ?: -1
            val sameGeneration=generation != null && generation>0 && observation?.registrationGeneration==generation
            val sourceAge=age(observation?.timestampElapsedNanos?.takeIf {it>0}?.div(1_000_000))
            val receiptAge=age(observation?.receivedElapsedMs)
            runCatching {receipt("carplay-car-data-source property=$label reason=$result status=$status active=${observation?.subscriptionActive==true} generation=$sameGeneration sourceAgeMs=$sourceAge receiptAgeMs=$receiptAge")}
        }
        report("range","RANGE_REMAINING",rangeReason)
        report("current","EV_BATTERY_LEVEL",currentReason)
        report("maximum","EV_CURRENT_BATTERY_CAPACITY",maxReason)
        if (range == null && current == null && max == null) return null
        // Actual usable capacity only. Nominal/new capacity, derived displayed SOC and inferred
        // charging state are not substitutes. No vehicle profile or Maps eligibility is implied.
        return OalEvSnapshot(range,current,max)
    }
}
