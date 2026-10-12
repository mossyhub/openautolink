package com.openautolink.carplaylab.intake

import com.shilapi.xcertplay.iap2.wire.Iap2Frame
import com.shilapi.xcertplay.iap2.message.Iap2Messages

data class OalEvSnapshot(val rangeKm: Int? = null, val currentChargeWh: Long? = null, val maxChargeWh: Long? = null)

object OalCarDataCodec {
    fun vehicle(snapshot: OalEvSnapshot): Iap2Frame? {
        val range = snapshot.rangeKm?.takeIf { it in 0..0xffff }
        val current = snapshot.currentChargeWh?.takeIf { it in 0..0xffff_ffffL }
        val max = snapshot.maxChargeWh?.takeIf { it in 0..0xffff_ffffL }
        if (range == null && current == null && max == null) return null
        return Iap2Messages.buildRaw(0xA101) {
            range?.let { u16(3, it); u16(11, it) }
            current?.let { u32(22, it) }
            max?.let { u32(23, it) }
        }
    }
}
