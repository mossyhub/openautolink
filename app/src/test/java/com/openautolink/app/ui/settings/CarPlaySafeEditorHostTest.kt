package com.openautolink.app.ui.settings

import java.io.File
import org.junit.Assert.*
import org.junit.Test

/** Wiring assertions are not rendered Compose or OEM-window proof. */
class CarPlaySafeEditorHostTest {
    private fun source(path: String) = File("src/main/java/com/openautolink/app/$path").readText()

    @Test fun calibrationIsRequestedFromSettingsAndNeverCreatesAnOemDialogWindow() {
        val settings = source("ui/settings/CarPlaySettings.kt")
        val display = settings.substringAfter("fun CarPlayDisplaySettingsSection")
            .substringBefore("/** Next-session video requests")
        assertFalse("513 calibration was confined to settings/OEM Dialog, not projection parent",
            display.contains("androidx.compose.ui.window.Dialog"))
        assertTrue("Settings must request the parent's overlay", display.contains("onNavigateToCarPlaySafeAreaEditor"))
        assertFalse("Settings content must not own editor geometry", display.contains("LocalView"))
        assertFalse("Settings content must not compose calibration", display.contains("SafeAreaEditorScreen("))
    }

    @Test fun bothProjectionOwnersAndStandaloneSettingsUseTheOwningViewportNotSettingsBounds() {
        val projection = source("ui/projection/ProjectionScreen.kt")
        val nav = source("ui/navigation/AppNavHost.kt")
        val editor = source("ui/settings/CarPlaySafeEditor.kt")
        val carPlayBranch = projection.substringAfter("if (carPlayState.selected) {")
            .substringBefore("// Effective system inset")
        assertTrue(carPlayBranch.contains("ProjectionViewport(uiState.displayMode)"))
        assertTrue(carPlayBranch.indexOf("CarPlayProjectionLayer(") < carPlayBranch.indexOf("if (showSettings)"))
        assertTrue(carPlayBranch.indexOf("if (showSettings)") < carPlayBranch.indexOf("if (showCarPlaySafeEditor)"))
        assertFalse(carPlayBranch.contains("navController.navigate"))
        assertTrue(projection.contains("ProjectionViewport(uiState.displayMode, Modifier.testTag(\"projectionScreen\"))"))
        assertEquals(2, Regex("if \\(showCarPlaySafeEditor\\) carPlaySafeEditorOverlay").findAll(projection).count())
        val standalone = nav.substringAfter("composable(AppDestinations.SETTINGS)").substringBefore("composable(AppDestinations.DIAGNOSTICS)")
        assertTrue(standalone.contains("ProjectionViewport(uiState.displayMode)"))
        assertTrue(standalone.contains("onNavigateToCarPlaySafeAreaEditor = { showCarPlaySafeEditor = true }"))
        assertTrue(editor.contains("displayMode = \"fullscreen_immersive\""))
        assertFalse(editor.contains("windowInsetsPadding"))
        assertFalse(editor.contains("LocalView"))
    }

    @Test fun calibrationIsModalInTheSameWindowSoTouchesCannotReachGlobalSaveBehindIt() {
        val editor = source("ui/settings/CarPlaySafeEditor.kt")
        assertTrue("A same-window editor must intercept its entire rectangle, not only edge handles",
            editor.contains("pointerInput(Unit)"))
        assertTrue(editor.contains("detectTapGestures"))
        assertTrue(editor.contains("Modifier.fillMaxSize()"))
    }

    @Test fun calibrationOnlyChangesDraftOnDoneAndNeverReconnectsOrDestroysSurface() {
        val editor = source("ui/settings/CarPlaySafeEditor.kt")
        assertTrue(editor.contains("viewModel.updateCarPlayDraft { it.copy(safeTop = top"))
        assertTrue(editor.contains("onBack = onBack"))
        assertTrue(editor.contains("BackHandler(onBack = onBack)"))
        listOf("commitCarPlaySettings", "saveAndReconnect", "reconnectCarPlay", "onCarPlaySurfaceDestroyed",
            "navController", "WindowCompat", "Dialog(").forEach { assertFalse(it, editor.contains(it)) }
        val nav = source("ui/navigation/AppNavHost.kt")
        assertTrue(nav.contains("applySettingsAndRestart("))
        assertTrue(nav.contains("settingsViewModel::commitCarPlaySettings"))
        assertTrue(nav.contains("projectionViewModel.reconnectAfterSettingsSave"))
        val aaEditor = source("ui/settings/SafeAreaEditorScreen.kt")
        assertTrue(aaEditor.contains("coordinateWidth: Int = VIDEO_W"))
        assertTrue(aaEditor.contains("coordinateHeight: Int = VIDEO_H"))
        assertTrue(aaEditor.contains("maximumInset: Int = MAX_INSET"))
    }

    @Test fun saveReconnectWaitsForFreshModeQualifiedParentRatherThanOldSurfaceTuple() {
        val vm = source("ui/projection/ProjectionViewModel.kt")
        val layer = source("carplay/CarPlayProjectionLayer.kt")
        assertTrue(vm.contains("val savedDisplayMode = preferences.displayMode.first()"))
        assertTrue(vm.contains("carPlayViewport.first { it?.displayMode == savedDisplayMode }?.input"))
        assertTrue(layer.contains("parentReceipt = coordinates.size to currentDisplayMode.value"))
        assertTrue(layer.contains("parentSize.width, parentSize.height, view.display, receipt.second"))
        assertFalse(layer.contains("this@apply.width, this@apply.height"))
    }
}
