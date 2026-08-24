package com.openautolink.app.ui.settings

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class KeyRemapDialogContractTest {

    @Test
    fun `dialog installs key capture hook from inside its own composition`() {
        val source = projectFile(
            "app/src/main/java/com/openautolink/app/ui/settings/SettingsScreen.kt",
        ).readText()
        val dialog = source.substringAfter("androidx.compose.material3.AlertDialog(")
            .substringBefore("@Composable\nprivate fun AudioTab")
        val textContent = dialog.substringAfter("text = {")
            .substringBefore("confirmButton = {")
        val hook = source.substringAfter("private fun DialogKeyCaptureWindowHook()")
            .substringBefore("private fun InputTab")

        assertTrue(textContent.contains("DialogKeyCaptureWindowHook()"))
        assertTrue(hook.contains("val dialogView = androidx.compose.ui.platform.LocalView.current"))
        assertTrue(hook.contains("findDialogWindowProvider(dialogView)"))
        assertFalse(hook.contains("dialogView.parent"))
    }

    @Test
    fun `cancel discards a detected key before the next assignment`() {
        val source = projectFile(
            "app/src/main/java/com/openautolink/app/ui/settings/SettingsScreen.kt",
        ).readText()
        val dialog = source.substringAfter("androidx.compose.material3.AlertDialog(")
            .substringBefore("@Composable\nprivate fun AudioTab")

        assertTrue(
            dialog.contains(
                "Button(onClick = { captureTarget = null; lastDetectedKey = null })",
            ),
        )
    }

    @Test
    fun `saved key remap changes update the live controller without rebuilding the view model`() {
        val source = projectFile(
            "app/src/main/java/com/openautolink/app/ui/projection/ProjectionViewModel.kt",
        ).readText()
        val initBlock = source.substringAfter("init {")
            .substringBefore("/** Bind the overlay")
        val connectBlock = source.substringAfter("private fun doConnect(")
            .substringBefore("fun reconnect()")

        assertTrue(initBlock.contains("preferences.keyRemap.distinctUntilChanged()"))
        assertTrue(initBlock.contains(".collect { serialized ->"))
        assertTrue(initBlock.contains("KeyRemapParser.parse(serialized)"))
        assertTrue(initBlock.contains("steeringWheelController.customKeyMap = map"))
        assertTrue(initBlock.contains("Applied custom key map update:"))
        assertFalse(connectBlock.contains("preferences.keyRemap.first()"))
    }

    @Test
    fun `key remap help states that assignments apply immediately`() {
        val source = projectFile(
            "app/src/main/java/com/openautolink/app/ui/settings/SettingsScreen.kt",
        ).readText()
        val inputTab = source.substringAfter("private fun InputTab")
            .substringBefore("private fun AudioTab")

        assertTrue(inputTab.contains("Key assignments apply immediately"))
        assertFalse(inputTab.contains("Requires Save & Reconnect"))
    }

    @Test
    fun `saved mappings and native send outcomes are observable`() {
        val settings = projectFile(
            "app/src/main/java/com/openautolink/app/ui/settings/SettingsViewModel.kt",
        ).readText()
        val update = settings.substringAfter("fun updateKeyRemap(json: String)")
            .substringBefore("fun updateVolumeOffsetMedia")
        val native = projectFile(
            "app/src/main/cpp/jni_session.cpp",
        ).readText().substringAfter("void JniSession::sendKeyEvent")
            .substringBefore("void JniSession::sendGpsLocation")

        assertTrue(update.contains("Key remap persisted:"))
        assertTrue(native.contains("KeyEvent skipped:"))
        assertTrue(native.contains("KeyEvent send complete:"))
        assertTrue(native.contains("KeyEvent send failed:"))
    }

    private fun projectFile(path: String): File {
        var dir = File(System.getProperty("user.dir") ?: error("user.dir unavailable"))
        repeat(8) {
            val candidate = File(dir, path)
            if (candidate.exists()) return candidate
            dir = dir.parentFile ?: error("Project root not found for: $path")
        }
        error("Project file not found: $path")
    }
}
