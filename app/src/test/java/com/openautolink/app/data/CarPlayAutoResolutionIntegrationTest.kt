package com.openautolink.app.data

import java.io.File
import org.junit.Assert.*
import org.junit.Test

class CarPlayAutoResolutionIntegrationTest {
    @Test fun nextSessionSelectionAndSettingsAreIndependentOfPhysicalScaling() {
        val root = File("src/main/java/com/openautolink/app")
        val bootstrap = File(root,"ui/projection/ProjectionViewModel.kt").readText()
        val ui = File(root,"ui/settings/CarPlaySettings.kt").readText()
        val validator = File(root,"ui/settings/SettingsViewModel.kt").readText()
        assertTrue("Resolution Auto needs measured viewport even when sizing is Manual",bootstrap.contains("saved.autoScale || saved.autoResolution"))
        assertTrue("Bootstrap must retain the single exact codec receipt",bootstrap.contains("saved.qualifiedDisplay(viewport)"))
        assertTrue("Independent Automatic/Manual resolution control missing",ui.contains("carPlayResolutionPolicy_"))
        assertTrue("Codec Auto label must describe both qualified offers",ui.contains("Automatic (H.264 + qualified HEVC)"))
        assertTrue("Auto must not qualify saved manual dimensions at Save",validator.contains("!next.usesWindowGeometry"))
    }
}
