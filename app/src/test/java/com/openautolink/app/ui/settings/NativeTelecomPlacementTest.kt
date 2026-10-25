package com.openautolink.app.ui.settings

import java.io.File
import org.junit.Test
import org.junit.Assert.*

class NativeTelecomPlacementTest {
    private fun source(path: String): String {
        val root = if (File("src/main").exists()) File(".") else File("app")
        return File(root, "src/main/java/com/openautolink/app/$path").readText()
    }
    @Test fun nativeTestControlsLiveOnlyInReachableDiagnosticsRoute() {
        assertFalse(source("ui/settings/CarPlaySettings.kt").contains("ManagedTelecomPanel()"))
        val diagnostics = source("ui/diagnostics/DiagnosticsScreen.kt")
        assertTrue(diagnostics.contains("Native CarPlay calls"))
        assertTrue(diagnostics.contains("ManagedTelecomPanel()"))
        assertTrue(diagnostics.contains("MicrophoneDiagnosticPanel(microphoneController)"))
    }
}
