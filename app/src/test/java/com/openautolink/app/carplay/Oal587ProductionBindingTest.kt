package com.openautolink.app.carplay

import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** Narrow parent-owned integration guards; actual model/reporting behavior has its own tests. */
class Oal587ProductionBindingTest {
    private fun source(path: String): String {
        val relative = "src/main/java/com/openautolink/app/$path"
        return listOf(File(relative), File("app/$relative"))
            .single { it.isFile }.readText()
    }

    @Test fun bothCarPlayTransportsReadSavedPilotAtTheRealVehicleAdapter() {
        for (path in listOf("carplay/OalCarPlayRuntime.kt", "carplay/OalUsbCarPlayRuntime.kt")) {
            val text = source(path)
            assertTrue(path, text.contains("OalCarPlayVehicleSnapshotAdapter.forContext(context)"))
            assertFalse(path, text.contains("OalCarPlayVehicleSnapshotAdapter()"))
        }
    }

    @Test fun actualProjectionFactoryPassesExplicitSavedClusterHevcOptIn() {
        val text = source("ui/projection/ProjectionViewModel.kt")
        assertTrue(text.contains("experimentalHevc = saved.clusterHevcEnabled"))
    }

    @Test fun modelPresenceBitsAreExportedWithoutModelOrRouteContents() {
        for (fields in listOf(0, 63, 64, 128, 192, 255, 256, 999, 4095)) {
            val line = "carplay-car-data id=41217 reason=transmitted count=1 fields=$fields suppressed=0"
            assertEquals(line, "CARPLAY_AUDIO session=1 $line", CarPlayAudioDiagnostics.sanitize(1, line))
            for (suffix in listOf(" model_id=12582922", " charging_json={}", "\nprivate", " destination=private"))
                assertNull(CarPlayAudioDiagnostics.sanitize(1, line + suffix))
        }
    }

    @Test fun modelPresenceMaskRejectsOutOfBoundsAndNoncanonicalValues() {
        for (fields in listOf("-1", "4096", "99999", "0255", "1.5", "NaN", "12582922")) {
            val line = "carplay-car-data id=41217 reason=transmitted count=1 fields=$fields suppressed=0"
            assertNull(fields, CarPlayAudioDiagnostics.sanitize(1, line))
        }
    }
}
