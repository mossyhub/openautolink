package com.openautolink.app.ui.theme

import java.io.File
import org.junit.Assert.*
import org.junit.Test

/** Pair these narrow wiring contracts with OalDialogSizingTest's real dialog window. */
class OalDialogWiringTest {
    @Test fun `every native alert uses the shared dialog without changing callback owners`() {
        val sites = mapOf(
            "settings/SettingsScreen.kt" to listOf("Assign key to:", "Share expanded EV observations?"),
            "diagnostics/DiagnosticsScreen.kt" to listOf("Enable ADB via RDMS?"),
            "settings/CarPlaySettings.kt" to listOf("Import authentication identity"),
            "projection/ProjectionScreen.kt" to listOf("USB phone connected", "USB switch paused", "CarPlay connection failed"),
        )
        for ((path, titles) in sites) {
            val source = uiFile(path).readText()
            assertFalse("$path must not create unscaled Material dialog windows", source.contains("androidx.compose.material3.AlertDialog"))
            assertTrue("$path imports the shared dialog", source.contains("import com.openautolink.app.ui.components.OalAlertDialog"))
            val calls = source.split("OalAlertDialog(").drop(1)
            assertEquals("$path call count", titles.size, calls.size)
            titles.zip(calls).forEach { (title, call) ->
                assertTrue("$title stays in its existing dialog", call.substringBefore("text = {").contains(title))
            }
        }
        val settings = uiFile("settings/SettingsScreen.kt").readText()
            .substringAfter("OalAlertDialog(").substringBefore("private fun AudioTab")
        assertTrue(settings.substringAfter("text = {").substringBefore("confirmButton = {").contains("DialogKeyCaptureWindowHook()"))
        assertTrue(settings.contains("viewModel.updateKeyRemap(json)"))
        val diagnostics = uiFile("diagnostics/DiagnosticsScreen.kt").readText().substringAfter("OalAlertDialog(")
        assertTrue(diagnostics.contains("viewModel.callRdmsAdbEnable(true)"))
        val auth = uiFile("settings/CarPlaySettings.kt").readText().substringAfter("OalAlertDialog(")
            .substringBefore("fun CarPlayDisplaySettingsSection")
        assertFalse("whole-dialog scrolling must not nest an unbounded body scroller", auth.contains("verticalScroll"))
        assertTrue(auth.contains("enabled = !saving && !importing && selectedKey != null && selectedCertificate != null"))
        assertTrue(auth.contains("viewModel.importCarPlayCredentials(requireNotNull(selectedKey), requireNotNull(selectedCertificate))"))
        assertTrue(auth.contains("if (!importing) { setupHelp = false; selectedKey = null; selectedCertificate = null }"))
        val projection = uiFile("projection/ProjectionScreen.kt").readText()
        listOf("onDismissRequest = onStay", "onClick = onSwitch", "onClick = onStay",
            "onDismissRequest = viewModel::dismissUsbSwitchFailure", "onClick = viewModel::retryUsbSwitch",
            "onDismissRequest = viewModel::dismissCarPlayFailure", "onClick = viewModel::returnToAndroidAuto",
            "onClick = retryCarPlay,", "enabled = carPlayEnabled && failure.kind != com.openautolink.app.carplay.CarPlayFailureKind.USB_STARTUP_TIMEOUT")
            .forEach { assertTrue(it, projection.contains(it)) }
    }

    @Test fun `support applies sizing inside its actual window while retaining a fixed Close footer`() {
        val support = uiFile("settings/SupportOpenAutoLink.kt").readText()
        val window = support.substringAfter("if (showQr)").substringBefore("private fun SupportTitle")
        assertTrue(Regex("Dialog\\([\\s\\S]*?\\) \\{\\s*OalUiScale \\{\\s*SupportQrDialog").containsMatchIn(window))
        val footer = support.substringAfter("TextButton(onClick = onClose")
        assertTrue(footer.contains("supportClose"))
        assertTrue(support.contains("Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())"))
    }

    private fun uiFile(path: String): File {
        var dir = File(System.getProperty("user.dir") ?: error("user.dir unavailable"))
        repeat(8) {
            val candidate = File(dir, "app/src/main/java/com/openautolink/app/ui/$path")
            if (candidate.isFile) return candidate
            dir = dir.parentFile ?: error("Project root not found")
        }
        error("Cannot locate $path")
    }
}
