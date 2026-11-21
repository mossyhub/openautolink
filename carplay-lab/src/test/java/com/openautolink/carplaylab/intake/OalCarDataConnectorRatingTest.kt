package com.openautolink.carplaylab.intake

import com.shilapi.xcertplay.iap2.body.Iap2BodyReader
import com.shilapi.xcertplay.iap2.message.Iap2Messages
import org.junit.Assert.*
import org.junit.Test

class OalCarDataConnectorRatingTest {
    private val base = Iap2Messages.buildRaw(0x1D01) {
        string(0, "Accessory")
        u16List(6, listOf(0x4301))
        u16List(7, listOf(0x4300))
        // Stale template metadata must always be replaced, not merged into the new offer.
        group(20) { u8(11, 6); u32(12, 235_000); u32(14, 10_900); u32(16, 50_000) }
    }

    private val rated = OalCarDataCapabilities(
        location = true,
        range = true,
        currentEnergy = true,
        vehicleDisplayName = "Chevrolet Blazer EV",
        chargingConnectorMask = (1 shl 0) or (1 shl 2),
        maximumDcChargePowerWatts = 150_000,
        maximumAcChargePowerWatts = 11_500,
    )

    private fun offer(capabilities: OalCarDataCapabilities) =
        Iap2BodyReader.of(OalCarDataIdentification.apply(base, capabilities))

    @Test fun publishedRatingsFollowRepeatedConnectorInventoryAsSeparateBigEndianWatts() {
        val body = offer(rated)
        val group = body.group(20)
        assertEquals(3, group.u16(0))
        assertEquals(listOf(0, 1, 2, 6, 8, 11, 11, 12, 14), group.list().map { it.id })
        assertArrayEquals(byteArrayOf(0), group.all(11)[0].payload)
        assertArrayEquals(byteArrayOf(2), group.all(11)[1].payload)
        assertArrayEquals(byteArrayOf(0, 2, 0x49, 0xf0.toByte()), group.raw(12))
        assertArrayEquals(byteArrayOf(0, 0, 0x2c, 0xec.toByte()), group.raw(14))
        assertEquals(150_000L, group.u32(12))
        assertEquals(11_500L, group.u32(14))

        val unrated = offer(rated.copy(maximumDcChargePowerWatts = null, maximumAcChargePowerWatts = null))
        for (id in listOf(0, 6, 7, 21, 22)) {
            assertArrayEquals("Unchanged outer field $id", unrated.raw(id), body.raw(id))
        }
        assertEquals(listOf(0, 1, 2, 6, 8, 11, 11), unrated.group(20).list().map { it.id })
    }

    @Test fun eachRatingRequiresItsOwnNativeConnectorBit() {
        val acOnly = offer(rated.copy(chargingConnectorMask = 1 shl 2)).group(20)
        assertEquals(2, acOnly.u8(11))
        assertFalse(acOnly.has(12))
        assertEquals(11_500L, acOnly.u32(14))

        val dcOnly = offer(rated.copy(chargingConnectorMask = 1 shl 0)).group(20)
        assertEquals(0, dcOnly.u8(11))
        assertEquals(150_000L, dcOnly.u32(12))
        assertFalse(dcOnly.has(14))

        // CCS2, Type 2 and a reserved bit do not authorize CCS1 or J1772 ratings.
        val other = offer(rated.copy(chargingConnectorMask = (1 shl 1) or (1 shl 3) or (1 shl 4))).group(20)
        assertEquals(listOf(1, 4), other.all(11).map { it.payload.single().toInt() })
        assertFalse(other.has(12))
        assertFalse(other.has(14))
    }

    @Test fun absentOrNonpositiveRatingDoesNotSuppressTheOtherValidRating() {
        for (invalid in listOf(null, 0, -1)) {
            val ac = offer(rated.copy(maximumDcChargePowerWatts = invalid)).group(20)
            assertFalse(ac.has(12))
            assertEquals(11_500L, ac.u32(14))
            val dc = offer(rated.copy(maximumAcChargePowerWatts = invalid)).group(20)
            assertEquals(150_000L, dc.u32(12))
            assertFalse(dc.has(14))
        }
    }

    @Test fun ratingsAndInventoryAloneDoNotEnableTheVehicleDataPlane() {
        val disabled = offer(rated.copy(range = false, currentEnergy = false))
        assertFalse(disabled.has(20))
        assertFalse(disabled.has(21))
        assertEquals(listOf(0x4301, 0xFFFB), disabled.u16List(6))
        assertEquals(listOf(0x4300, 0xFFFA, 0xFFFC), disabled.u16List(7))
    }
}
