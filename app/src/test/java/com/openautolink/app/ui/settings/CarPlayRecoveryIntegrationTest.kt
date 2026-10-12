package com.openautolink.app.ui.settings

import com.openautolink.app.data.CarPlaySettings
import java.io.File
import org.junit.Assert.*
import org.junit.Test

class CarPlayRecoveryIntegrationTest {
    private fun source(path: String): String {
        val root = generateSequence(File(requireNotNull(System.getProperty("user.dir")))) { it.parentFile }
            .first { File(it, "app/src/main").isDirectory }
        return File(root, "app/src/main/java/com/openautolink/app/$path").readText()
    }
    @Test fun codecPreferenceSurvivesStorageWithoutChangingAndroidAutoCodec() {
        val restored = CarPlaySettings.decode("""{"videoCodec":"hevc","resolution":"custom","widthPixels":2400,"heightPixels":960,"fps":31}""")
        assertTrue(CarPlaySettings.encode(restored).contains("\"videoCodec\":\"hevc\""))
        assertTrue(CarPlaySettings.encode(CarPlaySettings()).contains("\"videoCodec\":\"h264\""))
        assertTrue(restored.displayRequest().preferHevc)
        assertTrue(com.openautolink.app.data.CarPlaySettingsPolicy.runtimeConfig(
            restored.copy(enabled = true), restored.displayRequest()).display.preferHevc)
        assertFalse(CarPlaySettings().displayRequest().preferHevc)
        assertNull(carPlayEditError(restored))
        assertNotNull(carPlayEditError(restored.copy(videoCodec = "vp9")))
        assertEquals("h264", CarPlaySettings.decode("""{"videoCodec":"vp9"}""").videoCodec)
    }
    @Test fun projectionFitsActualQualifiedDimensionsInsteadOfHardcodedSixteenNine() {
        val layer = source("carplay/CarPlayProjectionLayer.kt")
        assertFalse(layer.contains("aspectRatio(16f / 9f"))
        assertTrue(layer.contains("viewModel.carPlayDisplay"))
        assertTrue(layer.contains("aspectRatio(display.width.toFloat() / display.height"))
        assertTrue(layer.contains("onSizeChanged"))
        assertTrue(layer.contains("update ="))
    }
    @Test fun recoveryPanelNeverObscuresRenderedCarPlay() {
        val screen = source("ui/projection/ProjectionScreen.kt")
        val branch = screen.substringAfter("if (carPlayState.selected) {").substringBefore("return\n    }")
        val guard = branch.substringBefore("CarPlayRecoveryPanel(").substringAfterLast("if (")
        assertTrue(guard.contains("!carPlayState.evidence.videoRendered"))
    }
}
