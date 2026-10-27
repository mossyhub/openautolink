package com.shilapi.xcertplay.media

import org.junit.Assert.*
import org.junit.Test

class CarPlayProductionCapturePolicyTest {
    @Test fun productionUsesAcceptedClassicVrSixteenMonoForEveryInputKindWithoutArming() {
        CarPlayMicrophoneExperiments.disarm()
        for (kind in listOf("default", "telephony", "speechrecognition")) {
            val profile = CarPlayMicrophoneCapturePolicy.profile(kind, null)
            assertNotNull("Production input must use the accepted immutable policy: $kind", profile)
            assertEquals(MicrophoneSource.VOICE_RECOGNITION, profile!!.source)
            assertEquals(CarPlayMicrophoneCaptureRate.HZ_16000, profile.rate)
            assertEquals(CarPlayMicrophoneChannels.MONO, profile.channels)
            assertTrue(profile.aaStyle)
        }
        assertFalse(CarPlayMicrophoneExperiments.status().armed)
    }
    @Test fun explicitOverrideWinsAndClearingRestoresProduction() {
        val override = CarPlayMicrophoneExperimentProfile(source=MicrophoneSource.MIC)
        assertSame(override, CarPlayMicrophoneCapturePolicy.profile("telephony", override))
        CarPlayMicrophoneExperiments.arm(override)
        CarPlayMicrophoneExperiments.disarm()
        assertTrue(CarPlayMicrophoneCapturePolicy.profile("speechrecognition", null)!!.aaStyle)
    }
}
