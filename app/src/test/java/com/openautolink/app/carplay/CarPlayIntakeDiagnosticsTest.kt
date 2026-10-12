package com.openautolink.app.carplay

import org.junit.Assert.*
import org.junit.Test

class CarPlayIntakeDiagnosticsTest {
    @Test fun boundedFullStringCarDataReceiptsReachExistingExportWithoutValues() {
        val line = "carplay-car-data id=65531 reason=transmitted count=1 fields=1 suppressed=0"
        assertNotNull("real intake must reach diagnostic export", CarPlayAudioDiagnostics.sanitize(1,line))
        assertNotNull(CarPlayAudioDiagnostics.sanitize(1,"carplay-appearance reason=transmitted count=1"))
        assertNotNull(CarPlayAudioDiagnostics.sanitize(1,"carplay-enrollment stage=commit accepted=true"))
        assertNotNull(CarPlayAudioDiagnostics.sanitize(1,"carplay-enrollment stage=authorized windowMs=120000 boundary=selected-bootstrap-request; first-client race remains possible"))
        assertNull(CarPlayAudioDiagnostics.sanitize(1,"carplay-enrollment stage=commit accepted=true controller=private"))
        for (bad in listOf(line + " lat=123", line + "\nprivate", line.replace("transmitted","raw"),
            line.replace("65531","99999"), line.replace("fields=1","fields=999"), "x".repeat(500)))
            assertNull(CarPlayAudioDiagnostics.sanitize(1,bad))
    }
}
