package com.openautolink.carplaylab.intake

import com.shilapi.xcertplay.iap2.wire.Iap2Frame
import com.shilapi.xcertplay.iap2.message.Iap2Messages

data class OalEvSnapshot(val rangeKm: Int? = null, val currentChargeWh: Long? = null,
    val maxChargeWh: Long? = null, val outsideTemperatureC: Int? = null,
    /** Exact frozen owner calibration; never used as a replacement for sourced maxChargeWh. */
    val routingModel: OalEvRoutingModel? = null,
    /** Explicit reference convention; never an inferred OEM reserve. */
    val minimumChargeWh: Long? = null,
    val chargePercentMilli: Long? = null,
    val charging: Boolean? = null,
    val fullRangeKm: Int? = null,
    val electricRangeWarning: Boolean? = null)

object OalCarDataCodec {
    fun vehicle(snapshot: OalEvSnapshot): Iap2Frame? {
        val range = snapshot.rangeKm?.takeIf { it in 0..0xffff }
        val max = snapshot.maxChargeWh?.takeIf { it in 0..0xffff_ffffL }
        val current = snapshot.currentChargeWh?.takeIf { it in 0..0xffff_ffffL && (max == null || it <= max) }
        val minimum = snapshot.minimumChargeWh?.takeIf { max != null && it in 0..max }
        val percent = snapshot.chargePercentMilli?.takeIf { current != null && max != null && max > 0 && it in 0..100_000L }
        val fullRange = snapshot.fullRangeKm?.takeIf { range != null && percent != null && it in 1..0xffff }
        val temperature = snapshot.outsideTemperatureC?.takeIf { it in Short.MIN_VALUE..Short.MAX_VALUE }
        // A retained calibration alone is never permission to send a model-only update.
        val model = snapshot.routingModel?.takeIf { current != null && it.acceptsCurrentCharge(current,max) }
        if (range == null && current == null && max == null && temperature == null) return null
        return Iap2Messages.buildRaw(0xA101) {
            range?.let {
                u16(3, it); u16(11, it)
                snapshot.electricRangeWarning?.let { warning -> u8(15, if (warning) 1 else 0) }
            }
            temperature?.let { i16(4, it) }
            minimum?.let { u32(21, it) }
            current?.let { u32(22, it) }
            max?.let { u32(23, it) }
            percent?.let { u32(24, it) }
            snapshot.charging?.let { u8(25, if (it) 1 else 0) }
            model?.let { string(26,it.chargingJson); string(27,it.consumptionJson) }
            fullRange?.let { u16(30, it) }
        }
    }
}
