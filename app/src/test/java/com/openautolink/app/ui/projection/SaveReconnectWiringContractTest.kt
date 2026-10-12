package com.openautolink.app.ui.projection

import java.io.File
import org.junit.Assert.*
import org.junit.Test

class SaveReconnectWiringContractTest {
    private fun source(path: String): String {
        val root = generateSequence(File(requireNotNull(System.getProperty("user.dir")))) { it.parentFile }
            .first { File(it, "app/src/main").isDirectory }
        return File(root, "app/src/main/java/com/openautolink/app/$path").readText()
    }

    @Test fun carPlayViewportOfferCannotBorrowThePreviousDisplayModeReceipt() {
        val vm = source("ui/projection/ProjectionViewModel.kt")
        val runtime = vm.substringAfter("private fun createCarPlayRuntime(").substringBefore("fun reconnectCarPlay()")
        assertTrue(runtime.contains("val savedDisplayMode = preferences.displayMode.first()"))
        assertTrue(runtime.contains("carPlayViewport.first { it?.displayMode == savedDisplayMode }"))
        val layer = source("carplay/CarPlayProjectionLayer.kt")
        assertTrue(layer.contains("rememberUpdatedState(controls.displayMode)"))
        val callback = layer.substringAfter("override fun surfaceChanged(").substringBefore("override fun surfaceDestroyed(")
        assertTrue(layer.contains("parentReceipt = coordinates.size to currentDisplayMode.value"))
        assertTrue(callback.contains("publishParent(this@apply)"))
        assertTrue(callback.indexOf("publishParent(") < callback.indexOf("onCarPlaySurfaceAvailable("))
    }

    @Test fun carPlayUsesTheSharedChromeExclusionBeforeItsEarlyReturn() {
        val screen = source("ui/projection/ProjectionScreen.kt")
        val branch = screen.substringAfter("if (carPlayState.selected) {").substringBefore("return\n    }")
        val viewport = source("ui/projection/ProjectionViewport.kt")
        assertTrue("CarPlay must constrain its actual Surface parent, not merely overlay bar padding",
            branch.contains("ProjectionViewport(uiState.displayMode)"))
        assertTrue(viewport.contains("Box(modifier.fillMaxSize().background(Color.Black).padding(padding)"))
        assertTrue(viewport.contains("maxOf(bars.bottom, cutout.bottom)"))
        assertTrue(viewport.contains("if (mode == \"system_ui_visible\")"))
        assertTrue(viewport.contains("else ProjectionChrome()"))
    }

    @Test fun launchUsesOneShotSavedPreferencePolicyNotUnconditionalAndroidAuto() {
        val screen = source("ui/projection/ProjectionScreen.kt")
        assertTrue(screen.contains("viewModel.startProjectionOnce()"))
        assertFalse(screen.substringAfter("DisposableEffect(Unit)").substringBefore("if (carPlayState.selected)").contains("viewModel.connect()"))
        assertFalse("Normal launch must not retain the large top-left manual start button",
            screen.contains("testTag(\"connectCarPlay\")"))
        assertTrue("Unavailable setup must retain an explicit recovery action", screen.contains("testTag(\"carPlayRetry\")"))
    }

    @Test fun committedSaveReadsAuthoritativePreferencesBeforeDispatch() {
        val vm = source("ui/projection/ProjectionViewModel.kt")
        val dispatch = vm.substringAfter("fun reconnectAfterSettingsSave(").substringBefore("fun stopCarPlay()")
        assertTrue(dispatch.indexOf("val saved = preferences.carPlaySettings.first()") < dispatch.indexOf("usbSwitchPolicy.wirelessCarPlay("))
        assertTrue(dispatch.contains("usbSwitchPolicy.wirelessCarPlay(saved.enabled, saved.connectionMode)"))
        assertFalse(dispatch.contains("carPlayEnabled.value"))
        assertTrue(dispatch.contains("carPlayCoordinator.connect(true, ::createCarPlayRuntime)"))
        assertTrue(dispatch.indexOf("releaseCarPlayUsbOwner()") < dispatch.indexOf("carPlayCoordinator.connect("))
        assertTrue(dispatch.contains("preferences.directTransport.first() != AppPreferences.DIRECT_TRANSPORT_USB"))
        assertFalse("Global Save keeps the passive USB watcher alive", dispatch.contains("stopUnifiedUsb()"))
    }

    @Test fun globalSaveDoesNotReuseSelectedOnlyOverlayReconnect() {
        val nav = source("ui/navigation/AppNavHost.kt")
        val save = nav.substringAfter("onSaveAndConnect = {").substringBefore("onBack = onBack")
        assertTrue("Successful committed save must dispatch from saved preferences, including enabled but unselected CarPlay",
            save.contains("projectionViewModel.reconnectAfterSettingsSave(settingsViewModel::saveAndReconnect)"))
        assertFalse("Save cannot depend on the captured UI selection", save.contains("carPlayState"))
        assertFalse("Save must not reuse the stale selected-only reconnect closure", save.contains("\n                                    reconnectProjection,"))
    }
}
