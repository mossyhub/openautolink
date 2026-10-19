package com.openautolink.app.carplay

import org.junit.Assert.*
import org.junit.Test

class CarPlayManeuverCacheDiagnosticsTest {
    @Test fun boundedOpaqueWindowSchemaSurvivesButPrivateExtensionsFailClosed() {
        val line = "carplay-nav-cache epoch=1 size=8 selected=true inserts=9 replacements=1 evictions=1 eviction=oldest"
        assertNotNull("cache receipts must survive production export", CarPlayAudioDiagnostics.sanitize(1, line))
        assertNotNull(CarPlayAudioDiagnostics.sanitize(1, line.replace("size=8", "size=0").replace("eviction=oldest", "eviction=none")))
        for (bad in listOf(line + " id=42", line + " road=Private", line + "\n", line.replace("size=8", "size=9"), line.replace("eviction=oldest", "eviction=selected42"))) {
            assertNull(bad, CarPlayAudioDiagnostics.sanitize(1, bad))
        }
    }
}
