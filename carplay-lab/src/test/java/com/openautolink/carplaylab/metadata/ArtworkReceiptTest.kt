package com.openautolink.carplaylab.metadata

import com.shilapi.xcertplay.iap2.message.Iap2Messages
import org.junit.Assert.*
import org.junit.Test

class ArtworkReceiptTest {
    @Test fun transferRejectionsAndAcceptanceAreFiniteBoundedAndDoNotChangeReferencePolicy() {
        val intake=CarPlayMetadataIntake { 0L }
        val lines=mutableListOf<String>();intake.onDiagnostic=lines::add
        intake.offerArtwork(7,byteArrayOf(1))
        intake.authenticate()
        intake.offerArtwork(7,byteArrayOf(1))
        intake.handle(Iap2Messages.buildRaw(0x5001) { group(0) { string(1,"private.canary");u8(26,7) } })
        intake.offerArtwork(7,byteArrayOf())
        intake.offerArtwork(7,byteArrayOf(1))
        assertArrayEquals(byteArrayOf(1),intake.artwork)
        assertEquals(listOf("carplay-artwork result=unauthenticated size=small","carplay-artwork result=reference size=small",
            "carplay-artwork result=size size=empty","carplay-artwork result=accepted size=small"),lines)
        repeat(1000) { intake.offerArtwork(8,byteArrayOf(1)) }
        assertTrue("finite per-link diagnostic budget",lines.size<=33)
        assertEquals("carplay-artwork result=limit size=empty",lines.last())
        assertTrue(lines.none { it.contains("private") || it.contains("id=") })
        intake.onDiagnostic={throw IllegalStateException("private error")}
        intake.offerArtwork(7,byteArrayOf(2));assertArrayEquals(byteArrayOf(2),intake.artwork)
    }
}
