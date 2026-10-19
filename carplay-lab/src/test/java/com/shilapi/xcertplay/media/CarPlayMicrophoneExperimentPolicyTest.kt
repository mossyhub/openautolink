package com.shilapi.xcertplay.media

import org.junit.Assert.*
import org.junit.Test

class CarPlayMicrophoneExperimentPolicyTest {
    private val baseline = MicrophoneCapability.of(mapOf("default" to MicrophoneSupport.implementedBits,
        "telephony" to MicrophoneSupport.implementedBits, "speechrecognition" to MicrophoneSupport.implementedBits))
    private val rows = AudioOutputSupport.wirelessRows(AudioOutputSupport.implementedBits)
    @Test fun autoPreservesExactCapabilityAndCatalog() {
        assertSame(baseline, CarPlayMicrophoneExperimentPolicy.resolveCapability(null, false, baseline, rows))
        assertSame(baseline, CarPlayMicrophoneExperimentPolicy.resolveCapability(CarPlayMicrophoneExperimentProfile(), false, baseline, rows))
    }
    @Test fun codecRestrictionIntersectsQualifiedRowsWithoutChangingOutputs() {
        val opus = CarPlayMicrophoneExperimentPolicy.resolveCapability(CarPlayMicrophoneExperimentProfile(codec=CarPlayMicrophoneCodec.OPUS),false,baseline,rows)
        assertEquals(MicrophoneSupport.opusBits, opus.bits("telephony"))
        assertEquals(setOf(0x20000000L), opus.bits("speechRecognition"))
        assertTrue(opus.bits("compatibility").isEmpty())
        val offered = MicrophoneSupport.addInputOffers(rows, opus)
        assertEquals(rows.map { it["audioOutputFormats"] },offered.map { it["audioOutputFormats"] })
        val pcm = CarPlayMicrophoneExperimentPolicy.resolveCapability(CarPlayMicrophoneExperimentProfile(codec=CarPlayMicrophoneCodec.LPCM),true,baseline,rows)
        assertEquals(setOf(0x40L),pcm.bits("telephony"))
    }
    @Test fun rejectsNoQualifiedInputIncludingWirelessPcmRatherThanSilentNoMic() {
        val pcm = CarPlayMicrophoneExperimentProfile(codec=CarPlayMicrophoneCodec.LPCM)
        assertThrows(IllegalArgumentException::class.java) { CarPlayMicrophoneExperimentPolicy.resolveCapability(pcm,false,baseline,rows) }
        assertThrows(IllegalArgumentException::class.java) { CarPlayMicrophoneExperimentPolicy.resolveCapability(pcm,true,MicrophoneCapability.NONE,rows) }
        assertThrows(IllegalArgumentException::class.java) { CarPlayMicrophoneExperimentPolicy.resolveCapability(pcm,true,baseline,emptyList()) }
        val opus = CarPlayMicrophoneExperimentProfile(codec=CarPlayMicrophoneCodec.OPUS)
        val onlyPcm = MicrophoneCapability.of(mapOf("telephony" to setOf(0x40L)))
        assertThrows(IllegalArgumentException::class.java) { CarPlayMicrophoneExperimentPolicy.resolveCapability(opus,true,onlyPcm,rows) }
    }
}
