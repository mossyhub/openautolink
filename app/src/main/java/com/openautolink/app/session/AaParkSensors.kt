package com.openautolink.app.session

import com.openautolink.app.transport.ControlMessage

/** Outbound AA restriction sensors only; the raw vehicle snapshot is never rewritten. */
internal data class AaParkSensors(
    val gearRaw: Int?,
    val speedKmh: Float?,
    val parkingBrake: Boolean?,
    val driving: Boolean?,
) {
    companion object {
        fun resolve(alwaysInPark: Boolean, raw: ControlMessage.VehicleData?): AaParkSensors =
            // sendGear accepts raw VHAL Park (4); native converts it to AA Park (101).
            if (alwaysInPark) AaParkSensors(4, 0f, true, false)
            else AaParkSensors(raw?.gearRaw, raw?.speedKmh, raw?.parkingBrake, raw?.driving)
    }
}
