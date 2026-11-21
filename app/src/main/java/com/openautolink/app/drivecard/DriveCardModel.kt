package com.openautolink.app.drivecard

import com.openautolink.app.navigation.VehicleEnergyForecast
import com.openautolink.app.navigation.VehicleEnergyForecastPolicy
import com.openautolink.app.input.VehicleTelemetryFreshness
import com.openautolink.app.transport.ControlMessage
import kotlin.math.abs
import kotlin.math.roundToInt

/** Session-local, bounded observations, never a VHAL subscriber or an EV calibration. */
data class DriveCardArrival(val percent: Int, val approximate: Boolean)
enum class DriveCardAvailability { UNAVAILABLE, LEARNING, READY, CHARGING }
enum class DriveCardPowerBasis { BATTERY, MOTOR }
data class DriveCardState(
    val nowKmPerKwh: Double? = null,
    val recentKmPerKwh: Double? = null,
    val powerKw: Double? = null,
    /** NaN is a regenerative/nonpositive-energy gap, not invented positive efficiency. */
    val trace: List<Double> = emptyList(),
    val arrival: DriveCardArrival? = null,
    val availability: DriveCardAvailability = DriveCardAvailability.UNAVAILABLE,
    /** Net pack flow and motor output are different measurements; null means unspecified. */
    val powerBasis: DriveCardPowerBasis? = null,
    /** Current source-qualified pack level, never the destination forecast. */
    val batteryPercent: Int? = null,
    /** INFO capacity is an approximate local denominator, not measured usable capacity. */
    val batteryApproximate: Boolean = false,
)
data class DriveCardRoute(val key: String, val distanceMeters: Int?, val remainingSeconds: Long?, val receivedAtMs: Long)

internal class DriveCardModel {
    private data class Tick(val at: Long, val wh: Double, val speed: Double)
    private data class Segment(val at: Long, val km: Double, val wh: Double)
    private val segments = ArrayDeque<Segment>()
    private var previous: Tick? = null
    private var anchorWh = 0.0
    private var pendingKm = 0.0
    private var generation: Long? = null
    private var generationBeganAt = Long.MAX_VALUE
    private var vehicle: ControlMessage.VehicleData? = null
    private var route: DriveCardRoute? = null
    private var forecast: VehicleEnergyForecast? = null
    private var carPlay = false

    fun clear() {
        resetHistory(); vehicle = null; generation = null; generationBeganAt = Long.MAX_VALUE; route = null; forecast = null
    }
    private fun resetHistory() { segments.clear(); previous = null; pendingKm = 0.0 }

    fun route(next: DriveCardRoute?) {
        val old = route
        // Route-specific forecasts retire; vehicle-only distance/energy history does not.
        val replaced = old != null && next != null && (next.key != old.key ||
            (next.distanceMeters != null && old.distanceMeters != null &&
                next.distanceMeters - old.distanceMeters > maxOf(1000, old.distanceMeters / 5)))
        if (next == null || replaced) forecast = null
        route = next
    }
    fun forecast(value: VehicleEnergyForecast?, carPlay: Boolean) {
        this.carPlay = carPlay
        forecast = if (carPlay) null else value
    }

    private fun valid(data: ControlMessage.VehicleData, key: String, now: Long, maxAge: Long = VEHICLE_AGE): Boolean {
        val observation = data.evObservationMetadata[key] ?: return false
        if (observation.status != 0) return false
        if (observation.source == "vhal" && (!observation.subscriptionActive ||
            (data.vhalRegistrationGeneration ?: 0) <= 0 || observation.registrationGeneration != data.vhalRegistrationGeneration)) return false
        if (observation.source != "vhal" && !(key == "EV_MOTOR_POWER" && observation.source == "history-provider-coherent")) return false
        val sourceAt = observation.timestampElapsedNanos?.div(1_000_000) ?: return false
        if (observation.source == "history-provider-coherent" &&
            (data.vhalRegistrationGeneration == null || sourceAt < generationBeganAt)) return false
        val sourceFresh = if (observation.source == "vhal")
            VehicleTelemetryFreshness.sourceTimeConfirmed(observation, key, now, maxAge)
            else now - sourceAt in 0..maxAge
        return sourceFresh && now - observation.receivedElapsedMs in 0..maxAge
    }
    private fun number(value: Float?): Double? = value?.toDouble()?.takeIf { it.isFinite() }
    private fun charging(data: ControlMessage.VehicleData, now: Long): Boolean {
        // AOSP EvChargeState: CHARGING=1, FULLY_CHARGED=2, NOT_CHARGING=3, ERROR=4.
        // Positive pack charge rate can be regenerative or contradictory OEM telemetry;
        // it cannot overrule an explicit current state or disconnected port.
        if (valid(data, "EV_CHARGE_STATE", now)) when (data.evChargeState) {
            1 -> return true
            2, 3, 4 -> return false
        }
        return valid(data, "EV_CHARGE_PORT_CONNECTED", now) && data.chargePortConnected == true &&
            valid(data, "EV_BATTERY_INSTANTANEOUS_CHARGE_RATE", now) &&
            data.evChargeRateW?.let { it.isFinite() && it > 0 } == true
    }

    fun update(data: ControlMessage.VehicleData, now: Long) {
        if (generation != data.vhalRegistrationGeneration) { resetHistory(); forecast = null; generationBeganAt = now }
        generation = data.vhalRegistrationGeneration
        vehicle = data
        val wh = number(data.evBatteryLevelWh)
        val speed = number(data.speedKmh)
        if (charging(data, now) || wh == null || wh <= 0 || speed == null || speed !in 0.0..350.0 ||
            !valid(data, "EV_BATTERY_LEVEL", now) || !valid(data, "PERF_VEHICLE_SPEED", now)) {
            resetHistory(); return
        }
        val speedObservation = data.evObservationMetadata.getValue("PERF_VEHICLE_SPEED")
        // Repeated StateFlow/timer snapshots cannot accumulate distance. A distinct actual
        // read can sample an unchanged speed without fabricating a newer HAL event time.
        val at = if (VehicleTelemetryFreshness.exactStatusRead(speedObservation, "PERF_VEHICLE_SPEED", now, VEHICLE_AGE))
            speedObservation.receivedElapsedMs else speedObservation.timestampElapsedNanos!! / 1_000_000
        val old = previous
        if (old != null && at == old.at) return // cached StateFlow/timer is not a new sample
        val tick = Tick(at, wh, speed)
        if (old == null || at < old.at || at - old.at > VEHICLE_AGE || abs(wh - old.wh) > 1000) {
            resetHistory(); previous = tick; anchorWh = wh; return
        }
        pendingKm += (speed + old.speed) / 2 * (at - old.at) / 3_600_000.0
        previous = tick
        if (pendingKm >= 0.25) {
            val delta = anchorWh - wh
            // Net pack delta includes regen and accessory draw. Do not turn regen into consumption.
            if (abs(delta / pendingKm) > 1500) { resetHistory(); previous = tick }
            else segments.addLast(Segment(at, pendingKm, delta))
            anchorWh = wh; pendingKm = 0.0
        }
        while (segments.size > 128 || segments.firstOrNull()?.let { at - it.at > HISTORY_AGE } == true ||
            (segments.size > 1 && segments.sumOf { it.km } > 10)) segments.removeFirst()
    }

    private fun batteryLevel(data: ControlMessage.VehicleData, now: Long): Pair<Int, Boolean>? {
        val energy = number(data.evBatteryLevelWh)?.takeIf {
            it >= 0 && valid(data, "EV_BATTERY_LEVEL", now)
        } ?: return null
        val usable = number(data.evCurrentBatteryCapacityWh)?.takeIf {
            it > 0 && valid(data, "EV_CURRENT_BATTERY_CAPACITY", now, CAPACITY_AGE)
        }
        val reported = number(data.evBatteryCapacityWh)?.takeIf {
            it in 10_000.0..250_000.0 && valid(data, "INFO_EV_BATTERY_CAPACITY", now, CAPACITY_AGE)
        }
        val capacity = usable ?: reported ?: return null
        if (energy > capacity) return null
        return (energy / capacity * 100).roundToInt() to (usable == null)
    }

    fun snapshot(now: Long): DriveCardState {
        val data = vehicle ?: return DriveCardState()
        val level = batteryLevel(data, now)
        if (charging(data, now)) return DriveCardState(availability = DriveCardAvailability.CHARGING,
            batteryPercent = level?.first, batteryApproximate = level?.second ?: false)
        // AOSP charge rate has positive inflow, negative discharge, and is already W here.
        // Prefer useful qualified net BATTERY flow; never relabel it as shaft/motor power.
        val batteryPower = number(data.evChargeRateW)?.takeIf { abs(it) <= 1_000_000 &&
            data.evObservationMetadata["EV_BATTERY_INSTANTANEOUS_CHARGE_RATE"]?.propertyId == 0x1160030c &&
            valid(data, "EV_BATTERY_INSTANTANEOUS_CHARGE_RATE", now) }?.div(-1000)
        val motorPower = number(data.evMotorPowerW)?.takeIf { abs(it) <= 1_000_000 && valid(data, "EV_MOTOR_POWER", now) }?.div(1000)
        val power = batteryPower ?: motorPower
        val powerBasis = when {
            batteryPower != null -> DriveCardPowerBasis.BATTERY
            motorPower != null -> DriveCardPowerBasis.MOTOR
            else -> null
        }
        val speed = number(data.speedKmh)?.takeIf { it > 5 && it <= 350 && valid(data, "PERF_VEHICLE_SPEED", now) }
        val nowRate = if (power != null && power > 0 && speed != null) speed / power else null
        val packFresh = number(data.evBatteryLevelWh)?.let { it > 0 } == true &&
            number(data.speedKmh)?.let { it in 0.0..350.0 } == true &&
            valid(data, "EV_BATTERY_LEVEL", now) && valid(data, "PERF_VEHICLE_SPEED", now)
        val history = if (packFresh) segments.filter { now - it.at in 0..HISTORY_AGE } else emptyList()
        val km = history.sumOf { it.km }
        val wh = history.sumOf { it.wh }
        val recent = if (km >= 0.5 && wh > 0) km * 1000 / wh else null
        val capacity = number(data.evCurrentBatteryCapacityWh)?.takeIf { it > 0 && valid(data, "EV_CURRENT_BATTERY_CAPACITY", now, CAPACITY_AGE) }
        // Nominal/new usable scale is only a LOCAL approximate estimate, never sourced
        // usable capacity, wire maximum, instantaneous power or an AA forecast denominator.
        val reportedCapacity = number(data.evBatteryCapacityWh)?.takeIf {
            it in 10_000.0..250_000.0 && valid(data, "INFO_EV_BATTERY_CAPACITY", now, CAPACITY_AGE)
        }
        val activeRoute = route?.takeIf { now - it.receivedAtMs in 0..ROUTE_AGE &&
            it.distanceMeters?.let { d -> d > 0 } == true && it.remainingSeconds?.let { s -> s > 0 } == true }
        val aa = forecast?.takeIf { !carPlay && activeRoute != null && VehicleEnergyForecastPolicy.isFresh(it, now) &&
            it.receivedAtElapsedMs >= activeRoute.receivedAtMs - ROUTE_AGE &&
            it.energyAtNextStop?.distanceMeters?.let { meters ->
                kotlin.math.abs(meters.toLong() - activeRoute.distanceMeters!!.toLong()) <=
                    maxOf(500L, activeRoute.distanceMeters.toLong() / 10)
            } == true }
        val sourcedWh = aa?.energyAtNextStop?.arrivalBatteryEnergyWh?.toDouble()?.takeIf { it >= 0 && capacity != null && it <= capacity }
        val localCapacity = capacity ?: reportedCapacity
        val localBattery = number(data.evBatteryLevelWh)?.takeIf {
            packFresh && localCapacity != null && it <= localCapacity
        }
        val dashboardRange = number(data.rangeKm)?.takeIf {
            it > 0 && valid(data, "RANGE_REMAINING", now)
        }
        // Prefer completed driving history. Before it is ready, use the car's own current
        // energy/range relationship, not guessed road load, instant power or a phone forecast.
        val localWhPerKm = if (recent != null && km >= 1.0) 1000.0 / recent
            else if (localBattery != null && dashboardRange != null) localBattery / dashboardRange else null
        val localWh = if (localBattery != null && activeRoute != null && localWhPerKm != null)
            localBattery - activeRoute.distanceMeters!!.toDouble() / 1000.0 * localWhPerKm else null
        val arrivalWh = sourcedWh ?: localWh
        val arrivalCapacity = if (sourcedWh != null) capacity else capacity ?: reportedCapacity
        val arrival = if (arrivalCapacity != null && activeRoute != null && arrivalWh != null && arrivalWh >= 0 && arrivalWh <= arrivalCapacity)
            DriveCardArrival((arrivalWh / arrivalCapacity * 100).roundToInt(), sourcedWh == null || aa?.forecastQuality != VehicleEnergyForecast.QUALITY_HIGH)
            else null
        val availability = when {
            nowRate != null || recent != null || power != null || arrival != null -> DriveCardAvailability.READY
            packFresh || level != null -> DriveCardAvailability.LEARNING
            else -> DriveCardAvailability.UNAVAILABLE
        }
        return DriveCardState(nowRate, recent, power,
            history.map { if (it.wh > 0) it.km * 1000 / it.wh else Double.NaN }, arrival, availability, powerBasis,
            batteryPercent = level?.first, batteryApproximate = level?.second ?: false)
    }
    companion object {
        private const val VEHICLE_AGE = 15_000L
        private const val CAPACITY_AGE = 120_000L
        private const val ROUTE_AGE = 120_000L
        private const val HISTORY_AGE = 1_800_000L
    }
}
