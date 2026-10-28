package com.openautolink.app.ui.settings

import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** Source contracts only: these do not render Compose or prove tablet behavior. */
class CarPlaySettingsPlacementTest {
    @Test fun videoEditorBelongsOnlyToExistingVideoTabAndIsGated() {
        val screen = source("SettingsScreen.kt")
        val editor = source("CarPlaySettings.kt")
        assertPlacement(screen, editor)
    }

    @Test fun placementContractRejectsConnectionAndDisplayVideoEditors() {
        val screen = source("SettingsScreen.kt")
        val editor = source("CarPlaySettings.kt")
        for (tab in listOf("ConnectionTab", "DisplayTab")) {
            val start = screen.indexOf("private fun $tab(")
            val body = screen.indexOf('{', start)
            val mutated = screen.substring(0, body + 1) + "\nCarPlayVideoSettingsSection(viewModel)\n" + screen.substring(body + 1)
            assertRejected { assertPlacement(mutated, editor) }
        }
        assertRejected { assertPlacement(screen, editor.replace("if (!settings.enabled) return", "")) }
    }

    @Test fun sharedDisplayKeepsAppModeAndLabelsAndroidAutoOnlyLayout() {
        val display = function(source("SettingsScreen.kt"), "DisplayTab")
        assertTrue(display.contains("SectionHeader(\"Display Mode\")"))
        assertTrue(display.contains("viewModel.updateDisplayMode(mode.key)"))
        assertTrue("Drive side is not a CarPlay layout control", display.contains("SectionHeader(\"Android Auto Drive Side\")"))
        assertTrue("Protocol-only features must be labelled", display.contains("SectionHeader(\"Android Auto Features\")"))
    }

    @Test fun videoDraftCopiesOnlyVideoFieldsAndNeverWritesPassword() {
        val editor = function(source("CarPlaySettings.kt"), "CarPlayVideoSettingsSection")
        assertVideoSave(editor)
        assertRejected { assertVideoSave(editor + "\nviewModel.updateCarPlayPassword(\"\")") }
        assertRejected { assertVideoSave(editor + "\ncopy(phoneAddress = \"\")") }
    }

    @Test fun existingTabsUseProtocolGroupsAndCollapsedHelp() {
        val screen = source("SettingsScreen.kt")
        val editor = source("CarPlaySettings.kt")
        val connection = function(screen, "ConnectionTab")
        val display = function(screen, "DisplayTab")
        val video = function(screen, "VideoTab")
        assertTrue(connection.contains("SectionHeader(\"Android Auto\")"))
        assertTrue(video.contains("SectionHeader(\"Android Auto\")"))
        assertTrue(display.indexOf("SectionHeader(\"Shared\")") in 0 until display.indexOf("SectionHeader(\"Display Mode\")"))
        assertTrue(display.indexOf("SectionHeader(\"Android Auto\")") in 0 until display.indexOf("SectionHeader(\"AA Display Insets\")"))
        assertTrue(display.contains("SectionHeader(\"AA Display Density (DPI)\")"))
        assertFalse(video.contains("SectionHeader(\"AA Display Density (DPI)\")"))
        assertTrue(display.contains("SectionHeader(\"Video Scaling\")"))
        assertFalse(video.contains("SectionHeader(\"Video Scaling\")"))
        assertTrue(editor.contains("Text(\"CarPlay\""))
        assertTrue(editor.contains("SectionHeader(\"CarPlay\")"))
        assertTrue(connection.contains("SettingsHelp("))
        assertTrue(video.contains("SettingsHelp("))
        assertTrue(editor.contains("SettingsHelp("))
        val help = function(screen, "SettingsHelp")
        assertTrue(help.contains("mutableStateOf(false)"))
        assertTrue(help.contains("if (expanded)"))
        // Network inputs remain in the explicitly collapsed advanced block.
        val setup = function(editor, "CarPlaySettingsSection")
        for (field in listOf("peerAddress", "interfaceName", "networkFamily", "localAddress", "apFrequencyMhz", "apSecurity")) {
            assertTrue("$field must remain Advanced-only",
                setup.indexOf("if (advanced && draft.connectionMode == \"wireless\")") in 0 until setup.indexOf("draft.$field"))
        }
        assertTrue(setup.contains("viewModel.updateCarPlayDraft"))
        assertTrue(setup.contains("viewModel.updateCarPlayPassword"))
        assertFalse(setup.contains("rememberSaveable"))
    }

    @Test fun carPlayUiScaleBelongsOnlyToDisplayAndKeepsVideoAndPassword() {
        val screen = source("SettingsScreen.kt")
        val editor = source("CarPlaySettings.kt")
        assertTrue(function(screen, "DisplayTab").contains("CarPlayDisplaySettingsSection(viewModel, onNavigateToCarPlaySafeAreaEditor)"))
        assertFalse(function(screen, "ConnectionTab").contains("CarPlayDisplaySettingsSection("))
        assertFalse(function(screen, "VideoTab").contains("CarPlayDisplaySettingsSection("))
        val display = function(editor, "CarPlayDisplaySettingsSection")
        assertTrue(display.indexOf("if (!settings.enabled) return") in 0 until display.indexOf("SectionHeader("))
        assertTrue(display.contains("val calibrationWidths = CarPlayDisplayPreferences.PHYSICAL_WIDTHS_MM"))
        assertTrue(display.contains("valueRange = calibrationWidths.first().toFloat()..calibrationWidths.last().toFloat()"))
        assertTrue(display.contains("steps = 0"))
        assertTrue(display.contains("viewModel.updateCarPlayDraft { it.copy(widthPhysicalMm = sliderWidthMm) }"))
        assertFalse(display.contains("copy(resolution"))
        assertFalse(display.contains("copy(fps"))
        assertTrue(display.contains("CarPlay UI scale"))
        assertTrue(display.contains("SettingsHelp("))
    }

    @Test fun autoScaleUsesSharedDraftAndDisablesOnlyManualWidth() {
        val display = function(source("CarPlaySettings.kt"), "CarPlayDisplaySettingsSection")
        assertTrue(display.contains("it.copy(autoScale = automatic)"))
        assertTrue(display.contains("enabled = !saving && !settings.autoScale"))
        assertTrue(display.contains("Auto sizing"))
        assertFalse(display.contains("saveCarPlaySettings"))
    }

    @Test fun statusOverrideIsOptionalAndReplacesOnlyLegacyStatus() {
        val screen = source("SettingsScreen.kt")
        val settings = function(screen, "SettingsScreen")
        val status = function(screen, "ConnectionStatusBar")
        assertTrue(settings.contains("activeProtocolStatus: String? = null"))
        val content = function(screen, "SettingsScreenContent")
        assertTrue(settings.contains("SettingsScreenContent(viewModel, sessionState, onSaveAndConnect, onBack,"))
        assertTrue(settings.contains("onNavigateToCarPlaySafeAreaEditor, activeProtocolStatus, audioBindings)"))
        assertTrue(content.contains("activeProtocolStatus = activeProtocolStatus"))
        assertTrue(status.contains("val statusText = activeProtocolStatus ?: when (sessionState)"))
        assertTrue(status.contains("if (activeProtocolStatus != null)"))
        assertTrue(status.contains("onClick = onSaveAndConnect"))
        assertTrue(status.contains("SessionState.IDLE -> \"Disconnected\""))
    }

    @Test fun everyPresetAndCustomVideoCandidateRequiresExactLocalDecoderReceipt() {
        val video = function(source("CarPlaySettings.kt"), "CarPlayVideoSettingsSection")
        assertTrue(video.contains("CarPlayDisplayPolicy.availableDisplays("))
        assertTrue(video.contains("CarPlayResolution.PRESETS.filter"))
        assertTrue(video.contains("availableDisplays.any { profile -> profile.resolution == resolution }"))
        assertTrue(video.contains("draft.displayRequest()"))
        assertTrue(video.contains("CarPlayDisplayPolicy.isQualified(request, probe)"))
        assertTrue(video.contains("if (!draft.usesWindowGeometry && !supportedProfile)"))
        assertTrue(video.contains("Local decoder support does not verify iPhone acceptance."))
        assertTrue(video.contains("CarPlayDisplayPolicy.isQualified(request.copy(fps = fps), probe)"))
        assertFalse(video.contains("experimental"))
    }

    private fun assertVideoSave(editor: String) {
        assertTrue("Video edits belong to the shared draft", editor.contains("viewModel.updateCarPlayDraft"))
        assertTrue(editor.contains("CarPlaySettingsUiPolicy.selectResolution(current, resolution.id, availableDisplays)"))
        assertTrue(editor.contains("it.copy(fps = fps, fpsDraft = null)"))
        assertFalse(editor.contains("phoneAddress ="))
        assertFalse(editor.contains("updateCarPlayPassword"))
        assertFalse(editor.contains("saveCarPlaySettings"))
        assertFalse(editor.contains("updateCarPlayEnabled"))
        assertFalse(editor.contains("PasswordVisualTransformation"))
        assertFalse(editor.contains("rememberSaveable"))
    }

    @Test fun oneGlobalCommitNoPerSectionButtonsAndNoSecretStateRestoration() {
        val editor = source("CarPlaySettings.kt")
        assertFalse(editor.contains("carPlayApply"))
        assertFalse(editor.contains("carPlayVideoApply"))
        assertFalse(editor.contains("carPlayDisplayApply"))
        assertFalse(editor.contains("viewModel.saveCarPlaySettings"))
        assertFalse(editor.contains("rememberSaveable"))
        assertFalse(editor.contains("Experimental CarPlay"))
        val vm = source("SettingsViewModel.kt")
        assertFalse(vm.contains("SavedStateHandle"))
        assertTrue(vm.contains("suspend fun commitCarPlaySettings(): Boolean"))
        val root = generateSequence(File(requireNotNull(System.getProperty("user.dir")))) { it.parentFile }
            .first { File(it, "app/src/main").isDirectory }
        val nav = File(root, "app/src/main/java/com/openautolink/app/ui/navigation/AppNavHost.kt").readText()
        assertTrue(nav.contains("applySettingsAndRestart("))
        assertTrue(nav.contains("settingsViewModel::commitCarPlaySettings"))
        assertTrue(nav.contains("CarPlay · video active"))
        val screen = function(source("SettingsScreen.kt"), "SettingsScreenContent")
        assertTrue(screen.contains("carPlaySettingsSaving"))
        assertTrue(screen.contains("carPlayApplyError"))
    }

    private fun assertPlacement(screen: String, editor: String) {
        val connection = function(screen, "ConnectionTab")
        val display = function(screen, "DisplayTab")
        val video = function(screen, "VideoTab")
        assertTrue("Connection retains setup", connection.contains("CarPlaySettingsSection(viewModel)"))
        assertTrue("Video tab must own CarPlay video editor", video.contains("CarPlayVideoSettingsSection(viewModel)"))
        assertFalse("Connection must not own video editor", connection.contains("CarPlayVideoSettingsSection("))
        assertFalse("Display must not own video editor", display.contains("CarPlayVideoSettingsSection("))
        val setup = function(editor, "CarPlaySettingsSection")
        assertFalse("Setup must not edit resolution", setup.contains("copy(resolution ="))
        assertFalse("Setup must not edit FPS", setup.contains("copy(fps ="))
        val carPlayVideo = function(editor, "CarPlayVideoSettingsSection")
        val gate = carPlayVideo.indexOf("if (!settings.enabled) return")
        assertTrue("Disabled CarPlay hides all video controls", gate >= 0 && gate < carPlayVideo.indexOf("SectionHeader("))
    }

    private fun assertRejected(block: () -> Unit) {
        try { block() } catch (_: AssertionError) { return }
        fail("Unsafe source mutation passed placement contract")
    }

    private fun function(source: String, name: String): String {
        val start = source.indexOf("fun $name(")
        assertTrue("Missing function $name", start >= 0)
        // Settings functions are top-level and end at the next top-level declaration.
        val end = Regex("(?m)^(?:@Composable|private fun |internal fun |fun )").find(source, start + 4)?.range?.first ?: source.length
        return source.substring(start, end)
    }

    private fun source(name: String): String {
        val root = generateSequence(File(requireNotNull(System.getProperty("user.dir")))) { it.parentFile }
            .first { File(it, "app/src/main").isDirectory }
        return File(root, "app/src/main/java/com/openautolink/app/ui/settings/$name").readText()
    }
}
