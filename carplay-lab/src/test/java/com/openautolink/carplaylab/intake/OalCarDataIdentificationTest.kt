package com.openautolink.carplaylab.intake

import com.shilapi.xcertplay.iap2.body.Iap2BodyReader
import com.shilapi.xcertplay.iap2.message.Iap2Messages
import org.junit.Assert.*
import org.junit.Test

class OalCarDataIdentificationTest {
    @Test fun exactSupportedFieldsReplaceReferenceDefaultsAndPreserveUnrelatedIdentity() {
        val base = Iap2Messages.buildRaw(0x1D01) {
            string(0,"OAL"); u16List(6,listOf(0x4301,0xFFFB,0xA101)); u16List(7,listOf(0x4300,0xFFFA,0xFFFC,0xA100,0xA102))
            group(21) {void(6); void(30)}; group(22) {void(20)}
        }
        val frame = OalCarDataIdentification.apply(base,OalCarDataCapabilities(location=true,range=true,currentEnergy=true))
        val body = Iap2BodyReader.of(frame)
        assertEquals("OAL",body.string(0))
        assertEquals(listOf(0,1,17,18),body.group(22).list().map {it.id})
        assertEquals(listOf(0,1,3,11,21),body.group(21).list().map {it.id})
        assertEquals(2,body.group(20).u8(2))
        assertFalse(body.group(20).has(11)) // No guessed connector inventory.
        assertEquals(listOf(0x4301,0xFFFB,0xA101),body.u16List(6))
        val disabled = Iap2BodyReader.of(OalCarDataIdentification.apply(frame,OalCarDataCapabilities()))
        assertFalse(disabled.has(20)); assertFalse(disabled.has(21)); assertFalse(disabled.has(22))
        assertEquals(listOf(0x4301),disabled.u16List(6))
        assertEquals(listOf(0x4300),disabled.u16List(7))
    }
}
