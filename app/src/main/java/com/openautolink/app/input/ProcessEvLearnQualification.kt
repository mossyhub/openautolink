package com.openautolink.app.input

import com.openautolink.app.data.EvLearnQualification as Qualification
import com.openautolink.app.data.EvLearnedRateEstimator
import com.openautolink.app.transport.ControlMessage.VehicleData
import com.openautolink.app.transport.VehiclePropertyObservation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Serialized by the process coordinator. Nothing here is an input to projection or export. */
internal class ProcessEvLearnQualification(
    private val estimator: EvLearnedRateEstimator,
    private val enabled: StateFlow<Boolean>,
    private val _status: MutableStateFlow<Qualification.Status?>,
) {
    private data class Source(
        val generation: Long, val make: String, val model: String, val year: String,
        val capacityWh: Int, val basis: Qualification.Basis, val reportedWh: Int,
    )

    private data class Owner(val source: Source, val scope: Qualification.Scope, val policy: Qualification)
    private data class Motion(val timeNs: Long, val speed: Float)
    private data class Drive(
        val ticket: Qualification.Ticket, val openedNs: Long, var motion: Motion,
        var distanceKm: Double = 0.0, var largestGapMs: Long = 1, var coveredNs: Long = 0,
    )

    val status = _status.asStateFlow()
    private var nextOwner = 0L
    private var owner: Owner? = null
    private var drive: Drive? = null
    private var parked = false
    private var lastBatch = -1L
    private var chargeStateRevision = 0L
    private var resetRevision = estimator.currentQualificationResetRevision()

    fun reconcile() {
        val revision = estimator.currentQualificationResetRevision()
        if (!enabled.value || resetRevision != revision) retire()
        resetRevision = revision
    }

    fun observeChargeStateRevision(revision: Long) {
        if (chargeStateRevision == revision) return
        chargeStateRevision = revision
        // A healthy recovered DTO cannot prove uninterrupted charge authority.
        retire()
    }

    fun onRawBatch(data: VehicleData, now: Long) {
        reconcile()
        val source = if (enabled.value) source(data, now) else null
        if (source != owner?.source || (owner != null && now < lastBatch)) retire()
        val current = source?.let {
            owner ?: run {
                // This token identifies this exact local process source lease, not an OEM trim
                // classification. Never carry it across a manager, identity or basis change.
                val scope = Qualification.Scope(++nextOwner, it.capacityWh, it.basis, it.generation)
                Owner(it, scope, Qualification(scope, enabled = true)).also { owner = it }
            }
        }
        val learnerInput = current?.let { observe(it, data, now) }
        // Observe/open/score before admitting this batch to the asynchronous estimator.
        estimator.onProcessVehicleTick(data, now, learnerInput, resetRevision)
        reconcile()
        _status.value = owner?.policy?.status
    }

    private fun retire() {
        owner?.policy?.retire()
        owner = null
        drive = null
        parked = false
        lastBatch = -1
        _status.value = null
    }

    private fun observe(current: Owner, data: VehicleData, now: Long): EvLearnedRateEstimator.QualificationInput? {
        val batchGap = if (lastBatch < 0) 0 else now - lastBatch
        lastBatch = now
        val policy = current.policy
        fun interrupt() {
            drive?.let { policy.abort(it.ticket) }
            drive = null
            parked = false
        }
        if (batchGap > MAX_GAP) interrupt()
        val gear = state(data, "GEAR_SELECTION", 0x11400400, now)
        val ignition = state(data, "IGNITION_STATE", 0x11400409, now)
        val charge = state(data, "EV_CHARGE_STATE", 0x11400F41, now)
        val speed = data.speedKmh?.takeIf { it in 0f..250f }
        val speedObs = fresh(data, "PERF_VEHICLE_SPEED", 0x11600207, now, allowRead = false)
        val pack = data.evBatteryLevelWh?.takeIf { it in 0f..current.scope.capacityWh.toFloat() }
        val packObs = fresh(data, "EV_BATTERY_LEVEL", 0x11600309, now)
        if (gear == null || ignition == null || data.ignitionState != 4 || charge == null ||
            (data.evChargeState != 2 && data.evChargeState != 3) || data.chargePortConnected == true ||
            data.evChargeRateW?.let { !it.isFinite() || it > 0f } == true || speed == null || speedObs == null ||
            pack == null || packObs == null || (data.gearRaw != PARK && data.gearRaw != DRIVE)
        ) {
            interrupt()
            return null
        }
        val speedNs = speedObs.timestampElapsedNanos!!
        val currentMotion = Motion(speedNs, speed)
        val gearNs = gear.timestampElapsedNanos!!
        val packNs = packObs.timestampElapsedNanos!!
        val packTime = packNs / NS_PER_MS
        // The exact-read marker proves availability, not measurement time. Only use a
        // pack event inside a short, stopped speed/gear bracket; anchor the window to
        // the event itself. Never credit distance before it or extrapolate after speed.
        val atBoundary = speed <= 0.5f && now - gearNs / NS_PER_MS <= MAX_AGE &&
            kotlin.math.abs(gearNs - speedNs) <= MAX_BOUNDARY_SKEW_NS &&
            packNs in speedNs..maxOf(gearNs, speedNs)
        val packAge = maxOf(now - packObs.receivedElapsedMs, now - packTime)
        val existing = drive
        if (existing != null) {
            val delta = speedNs - existing.motion.timeNs
            if (delta < 0 || delta > MAX_GAP * NS_PER_MS ||
                now - existing.openedNs / NS_PER_MS > MAX_DURATION || (delta == 0L && speed != existing.motion.speed)
            ) interrupt()
            else {
                if (delta > 0) {
                    // Discard the interval crossing the pack anchor, rather than estimating
                    // missing distance. The full pack-to-pack duration still counts below.
                    if (existing.motion.timeNs >= existing.openedNs) {
                        existing.distanceKm += (speed + existing.motion.speed) / 2.0 * delta / NS_PER_HOUR
                        existing.coveredNs += delta
                    }
                    existing.motion = currentMotion
                }
                existing.largestGapMs = maxOf(existing.largestGapMs, batchGap, ceilMs(delta))
                if (data.gearRaw == PARK) {
                    if (atBoundary && gearNs > existing.openedNs && packNs > existing.openedNs) {
                        val gap = maxOf(existing.largestGapMs, ceilMs(packNs - speedNs))
                        val durationNs = packNs - existing.openedNs
                        val coverage = (existing.coveredNs.toDouble() / durationNs).coerceAtMost(1.0)
                        policy.finish(existing.ticket, Qualification.End(current.scope, packTime,
                            pack.toInt(), packAge, existing.distanceKm, coverage, gap, Qualification.Closure.BOUNDARY))
                        drive = null
                    } else interrupt()
                }
            }
        } else if (parked && data.gearRaw == DRIVE && atBoundary) {
            val range = data.rangeKm?.takeIf { it.isFinite() && it >= 1f }
            val rangeNs = fresh(data, "RANGE_REMAINING", 0x11600308, now, allowRead = false)?.timestampElapsedNanos
            // Freeze the unlearned source estimate in the same stopped bracket, no
            // later than the pack anchor. This is not a departure route forecast.
            if (range != null && rangeNs != null && rangeNs in speedNs..packNs) {
                val rate = pack.toDouble() / range
                val prior = estimator.qualificationPrior(data, current.scope, packTime, resetRevision)
                policy.begin(Qualification.Start(current.scope, packTime, pack.toInt(), rate, packAge, prior))?.let {
                    drive = Drive(it, packNs, currentMotion, largestGapMs = maxOf(1, ceilMs(packNs - speedNs)))
                }
            }
        }
        parked = data.gearRaw == PARK && speed <= 0.5f
        // The legacy EMA uses only timestamps it can qualify independently for this receipt.
        // Never retroactively qualify a stale HAL event merely because its status was reread.
        if (now - packTime > MAX_AGE) return null
        return EvLearnedRateEstimator.QualificationInput(current.scope, speedNs, packNs)
    }

    private fun source(data: VehicleData, now: Long): Source? {
        val generation = data.vhalRegistrationGeneration?.takeIf { it > 0 } ?: return null
        // A retained scalar is not authority after an unavailable/error callback or a
        // registration change. Freshness failures interrupt below; explicit source loss retires.
        if (observation(data, "GEAR_SELECTION", 0x11400400, now) == null ||
            observation(data, "IGNITION_STATE", 0x11400409, now) == null ||
            observation(data, "EV_CHARGE_STATE", 0x11400F41, now) == null ||
            observation(data, "PERF_VEHICLE_SPEED", 0x11600207, now) == null ||
            observation(data, "EV_BATTERY_LEVEL", 0x11600309, now) == null ||
            data.gearRaw == null || data.ignitionState == null || data.evChargeState == null ||
            data.speedKmh == null || data.evBatteryLevelWh == null
        ) return null
        val make = data.carMake?.takeIf { it.isNotBlank() } ?: return null
        val model = data.carModel?.takeIf { it.isNotBlank() } ?: return null
        val year = data.carYear?.takeIf { it.toIntOrNull() in 1900..2200 } ?: return null
        if (data.fuelTypes != listOf(10)) return null // FuelType.ELECTRIC, not a PHEV.
        val nominalWh = capacity(data.evBatteryCapacityWh) ?: return null
        if (state(data, "INFO_EV_BATTERY_CAPACITY", 0x11600106, now, allowStatic = true) == null) return null
        val actual = data.evCurrentBatteryCapacityWh
        return if (actual == null) Source(generation, make, model, year, nominalWh, Qualification.Basis.REPORTED, nominalWh)
        else {
            val usableWh = capacity(actual) ?: return null
            if (state(data, "EV_CURRENT_BATTERY_CAPACITY", 0x1160030D, now, allowStatic = true) == null) return null
            Source(generation, make, model, year, usableWh, Qualification.Basis.USABLE, nominalWh)
        }
    }

    private fun capacity(value: Float?): Int? = value?.takeIf { it in 1_000f..300_000f && it == it.toInt().toFloat() }?.toInt()

    private fun observation(data: VehicleData, name: String, id: Int, now: Long): VehiclePropertyObservation? =
        data.evObservationMetadata[name]?.takeIf {
            it.source == "vhal" && it.propertyId == id && it.status == 0 && it.subscriptionActive &&
                it.registrationGeneration == data.vhalRegistrationGeneration && it.receivedElapsedMs in 1..now &&
                it.timestampElapsedNanos?.let { ns -> ns > 0 && ns / NS_PER_MS in 1..it.receivedElapsedMs } == true
        }

    private fun state(data: VehicleData, name: String, id: Int, now: Long, allowStatic: Boolean = false) =
        observation(data, name, id, now)?.takeIf {
            it.changeMode == 1 || (allowStatic && it.changeMode == 0) ||
                (now - it.receivedElapsedMs <= MAX_AGE &&
                    (now - it.timestampElapsedNanos!! / NS_PER_MS <= MAX_AGE ||
                        VehicleTelemetryFreshness.exactStatusRead(it, name, now, MAX_AGE)))
        }

    private fun fresh(data: VehicleData, name: String, id: Int, now: Long, allowRead: Boolean = true) =
        observation(data, name, id, now)?.takeIf {
            now - it.receivedElapsedMs <= MAX_AGE &&
                (now - it.timestampElapsedNanos!! / NS_PER_MS <= MAX_AGE ||
                    (allowRead && VehicleTelemetryFreshness.exactStatusRead(it, name, now, MAX_AGE)))
        }

    private fun ceilMs(ns: Long): Long = (ns + NS_PER_MS - 1) / NS_PER_MS

    private companion object {
        const val PARK = 4
        const val DRIVE = 8
        const val MAX_AGE = 10_000L
        const val MAX_GAP = 15_000L
        const val MAX_DURATION = 7_200_000L
        const val NS_PER_MS = 1_000_000L
        const val MAX_BOUNDARY_SKEW_NS = 500 * NS_PER_MS
        const val NS_PER_HOUR = 3_600_000_000_000.0
    }
}
