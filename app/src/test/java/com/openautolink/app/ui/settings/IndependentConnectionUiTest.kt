package com.openautolink.app.ui.settings

import java.io.File
import org.junit.Assert.*
import org.junit.Test

/** Source wiring contracts only, not rendered Compose or phone admission proof. */
class IndependentConnectionUiTest {
    @Test fun normalConnectionsHaveSeparateSelectorsAndNeverImplicitlyEnableCarPlay() {
        val connection = function(source("settings/SettingsScreen.kt"), "ConnectionTab")
        val carPlay = function(source("settings/CarPlaySettings.kt"), "CarPlaySettingsSection")
        assertFalse(connection.contains("SectionHeader(\"Connection mode\")"))
        assertTrue(connection.contains("Text(\"Android Auto connection\""))
        assertTrue(connection.contains("AppPreferences.DIRECT_TRANSPORT_USB to \"USB\""))
        assertTrue(connection.contains("AppPreferences.DIRECT_TRANSPORT_WPP to \"Wireless\""))
        assertTrue(connection.contains("viewModel.updateDirectTransport(mode)"))
        assertFalse(connection.contains("DIRECT_TRANSPORT_HOTSPOT"))
        assertFalse(connection.contains("\"Phone Hotspot\""))
        assertTrue(carPlay.contains("listOf(\"usb\" to \"USB\", \"wireless\" to \"Wireless\")"))
        assertTrue(carPlay.contains("selected = settings.connectionMode == mode"))
        assertTrue(carPlay.contains("viewModel.updateCarPlayDraft { it.copy(connectionMode = mode) }"))
        assertTrue(carPlay.indexOf("Text(\"CarPlay connection\"") in 0 until carPlay.indexOf("if (!settings.enabled) return"))
        assertFalse(carPlay.contains("copy(enabled ="))
        assertFalse(carPlay.contains("updateDirectTransport"))
        assertFalse(carPlay.contains("saveCarPlaySettings"))
    }

    @Test fun helpExplainsTemporaryTakeoverAndRetainsUnverifiedWirelessWithoutWiredRequirements() {
        val connection = function(source("settings/SettingsScreen.kt"), "ConnectionTab")
        val carPlay = function(source("settings/CarPlaySettings.kt"), "CarPlaySettingsSection")
        assertTrue(connection.contains("temporarily take over after you confirm; saved modes stay unchanged"))
        assertTrue(carPlay.contains("wireless CarPlay is unverified in the car"))
        assertTrue(carPlay.contains("Selecting a connection does not enable CarPlay"))
        assertTrue(carPlay.indexOf("if (draft.connectionMode == \"wireless\")") in 0 until carPlay.indexOf("Text(\"Paired iPhone\""))
        assertTrue(carPlay.contains("if (advanced && draft.connectionMode == \"wireless\")"))
        assertTrue(carPlay.contains("SettingsHelp(\"CarPlay's normal connection"))
    }

    @Test fun commonPromptPassesExactGenerationBeforeEitherProtocolBranchAndKeepsParentEditor() {
        val projection = function(source("projection/ProjectionScreen.kt"), "ProjectionScreen")
        assertTrue(projection.contains("viewModel.usbHotPlugPrompt.collectAsStateWithLifecycle()"))
        assertTrue(projection.contains("if (!showSettings && !showDiagnostics && !showCarPlaySafeEditor)"))
        assertEquals(1, Regex("UsbHotPlugSwitchDialog\\(").findAll(projection).count())
        assertTrue(projection.indexOf("UsbHotPlugSwitchDialog(") in 0 until projection.indexOf("if (carPlayState.selected)"))
        assertTrue(projection.contains("viewModel.acceptUsbHotPlug(prompt.generation)"))
        assertTrue(projection.contains("viewModel.stayOnCurrentProjection(prompt.generation)"))
        assertTrue(projection.contains("key(prompt.generation)"))
        assertEquals(2, Regex("if \\(showCarPlaySafeEditor\\) carPlaySafeEditorOverlay").findAll(projection).count())
        assertTrue(projection.contains("ProjectionViewport(uiState.displayMode)"))
        assertTrue(projection.contains("ProjectionViewport(uiState.displayMode, Modifier.testTag(\"projectionScreen\"))"))
    }

    @Test fun switchDialogOnlyConfirmsAndDismissesAsStayWithoutSettingsOrPermissionMutation() {
        val screen = source("projection/ProjectionScreen.kt")
        val dialog = function(screen, "UsbHotPlugSwitchDialog")
        assertTrue(dialog.contains("prompt: com.openautolink.app.carplay.UsbHotPlugPrompt"))
        assertTrue(dialog.contains("onDismissRequest = onStay"))
        assertTrue(dialog.contains("onClick = onSwitch"))
        assertTrue(dialog.contains("onClick = onStay"))
        assertTrue(dialog.contains("Text(\"Switch\")"))
        assertTrue(dialog.contains("Text(\"Stay here\")"))
        assertTrue(dialog.contains("\${prompt.protocolLabel} for this connection?"))
        listOf("updateDirectTransport", "updateCarPlay", "setDirectTransport", ".launch(", "UsbConnectionManager", "returnToAndroidAuto")
            .forEach { assertFalse(it, dialog.contains(it)) }
        assertTrue(screen.contains("if (!showSettings && !showDiagnostics && usbHotPlugPrompt == null)"))
        assertTrue(screen.contains("if (usbHotPlugPrompt == null && UsbPickerVisibility.shouldShow("))
    }

    private fun function(text: String, name: String): String {
        val start = text.indexOf("fun $name(")
        assertTrue("Missing $name", start >= 0)
        val end = Regex("(?m)^(?:@Composable|private fun |internal fun |fun )").find(text, start + 4)?.range?.first ?: text.length
        return text.substring(start, end)
    }

    private fun source(path: String): String {
        val root = generateSequence(File(requireNotNull(System.getProperty("user.dir")))) { it.parentFile }
            .first { File(it, "app/src/main").isDirectory }
        return File(root, "app/src/main/java/com/openautolink/app/ui/$path").readText()
    }
}
