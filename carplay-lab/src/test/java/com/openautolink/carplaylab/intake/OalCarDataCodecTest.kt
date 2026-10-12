package com.openautolink.carplaylab.intake

import com.shilapi.xcertplay.iap2.body.Iap2BodyReader
import org.junit.Assert.*
import org.junit.Test

class OalCarDataCodecTest {
    @Test fun invalidAndUnknownFieldsNeverProduceFabricatedUpdates() {
        assertNull(OalCarDataCodec.vehicle(OalEvSnapshot()))
        assertNull(OalCarDataCodec.vehicle(OalEvSnapshot(-1, -1, 0x1_0000_0000)))
        val body = Iap2BodyReader.of(OalCarDataCodec.vehicle(OalEvSnapshot(65536, null, 0xffff_ffff))!!)
        assertEquals(listOf(23), body.list().map { it.id })
        assertEquals(0xffff_ffffL, body.u32(23))
    }

    @Test fun unknownEnergyIsOmittedButObservedZeroHasExactUnsignedWireBytes() {
        val frame = OalCarDataCodec.vehicle(OalEvSnapshot(rangeKm = 0, currentChargeWh = 0))!!
        assertEquals(0xA101, frame.messageId)
        assertArrayEquals(byteArrayOf(0,6,0,3,0,0, 0,6,0,11,0,0, 0,8,0,22,0,0,0,0), frame.payload)
        val body = Iap2BodyReader.of(frame)
        assertEquals(listOf(3,11,22), body.list().map { it.id })
        assertFalse(body.has(21))
        assertFalse(body.has(24))
        assertFalse(body.has(25))
    }
}
