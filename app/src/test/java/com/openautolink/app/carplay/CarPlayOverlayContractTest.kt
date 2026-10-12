package com.openautolink.app.carplay

import java.io.File
import org.junit.Assert.*
import org.junit.Test

/** Source integration guards, NOT rendered Compose/device proof. */
class CarPlayOverlayContractTest {
    private fun source(path: String) = File("src/main/java/com/openautolink/app/$path").readText()

    @Test fun carPlayUsesExistingFloatingControlsAndKeepsSurfaceOutsideOverlayVisibility() {
        val layer = source("carplay/CarPlayProjectionLayer.kt")
        assertTrue("Use the same controls as AA", layer.contains("ProjectionOverlayControls("))
        assertFalse(layer.contains("FilledTonalButton"))
        assertFalse(layer.contains("experimental manual connection"))
        assertFalse(layer.contains("Alignment.TopStart"))
        assertTrue(layer.indexOf("AndroidView(") < layer.indexOf("ProjectionOverlayControls("))
        assertTrue(layer.contains("CarPlayOverlayPolicy.forwardTouch(showSettings, showDiagnostics)"))
        val screen = source("ui/projection/ProjectionScreen.kt")
        assertTrue(screen.contains("ProjectionOverlayControls("))
        assertTrue(screen.contains("controls = uiState"))
        assertTrue(screen.contains("onReconnect = onReconnect"))
        val shared = source("ui/components/ProjectionOverlayControls.kt")
        assertTrue(shared.contains("DraggableOverlayButton("))
        assertTrue(shared.contains("positionKey = \"overlay_settings\""))
        assertTrue(shared.contains("positionKey = \"overlay_stats\""))
        assertTrue(shared.contains("positionKey = \"overlay_reconnect\""))
        assertTrue("Settings precedes optional controls", shared.indexOf("positionKey = \"overlay_settings\"") < shared.indexOf("if (uiState.overlayStatsButton)"))
        assertTrue("No unsupported phone chooser by default", shared.contains("onSwitchPhone: (() -> Unit)? = null"))
        assertTrue("No AA ignition simulation by default", shared.contains("onSimulateIgnition: (() -> Unit)? = null"))
    }
}
