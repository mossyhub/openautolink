package com.openautolink.app.input

import com.openautolink.app.transport.VehiclePropertyObservation

/** A completed exact status read confirms current availability, not a new bus event timestamp. */
internal object VehicleTelemetryFreshness {
    private val propertyIds = mapOf(
        "DISTANCE_DISPLAY_UNITS" to 0x11400600,
        "PERF_VEHICLE_SPEED" to 0x11600207,
        "EV_BATTERY_LEVEL" to 0x11600309,
        "EV_CURRENT_BATTERY_CAPACITY" to 0x1160030D,
        "INFO_EV_BATTERY_CAPACITY" to 0x11600106,
        "RANGE_REMAINING" to 0x11600308,
        "FUEL_LEVEL_LOW" to 0x11200405,
        "ENV_OUTSIDE_TEMPERATURE" to 0x11600703,
        "EV_CHARGE_STATE" to 0x11400F41,
        "EV_CHARGE_PORT_CONNECTED" to 0x1120030B,
        "EV_BATTERY_INSTANTANEOUS_CHARGE_RATE" to 0x1160030C,
    )

    fun propertyMatches(observation: VehiclePropertyObservation, property: String): Boolean =
        observation.propertyId == null || propertyIds[property]?.let { it == observation.propertyId } != false

    /** The producer validates returned ID, global area, type, status and exact live owner before marking. */
    fun exactStatusRead(observation: VehiclePropertyObservation, property: String, now: Long, maxAge: Long): Boolean {
        val expected = propertyIds[property] ?: return false
        val read = observation.statusReadElapsedMs ?: return false
        return observation.source == "vhal" && observation.status == 0 && observation.subscriptionActive &&
            observation.propertyId == expected && read == observation.receivedElapsedMs && now - read in 0..maxAge
    }

    fun sourceTimeConfirmed(observation: VehiclePropertyObservation, property: String, now: Long, maxAge: Long): Boolean {
        val timestamp = observation.timestampElapsedNanos ?: return false
        val at = timestamp / 1_000_000
        if (timestamp <= 0 || at > now || !propertyMatches(observation, property)) return false
        return now - at in 0..maxAge ||
            (property in propertyIds && observation.changeMode in setOf(0, 1)) ||
            exactStatusRead(observation, property, now, maxAge)
    }
}
