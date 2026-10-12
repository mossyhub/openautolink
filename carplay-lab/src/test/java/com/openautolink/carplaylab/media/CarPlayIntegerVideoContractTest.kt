package com.openautolink.carplaylab.media

import com.shilapi.xcertplay.airplay.AirPlayInfoPlist
import com.shilapi.xcertplay.airplay.AirPlayDisplaySettings
import org.junit.Assert.*
import org.junit.Test

class CarPlayIntegerVideoContractTest {
    @Test fun integerMaximumRatesSurviveModelRestorationAndActualInfoWithoutSnapping() {
        for (fps in 1..60) {
            val restored = CarPlayDisplayPreferences.fromStored("720p", fps, 200)
            assertEquals("Restoration must retain exact integer cap", fps, restored.fps)
            assertEquals(fps, AirPlayDisplaySettings.sanitizeFps(fps))
            val info = AirPlayInfoPlist.build(benchReceiverConfig("00:11:22:33:44:55", 41234, display = restored), emptySet())
            assertEquals(fps, ((info["displays"] as List<*>).first() as Map<*, *>)["maxFPS"])
        }
    }
}
