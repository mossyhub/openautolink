package com.openautolink.app.ui.settings

import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** Source wiring guards only, not a rendered Compose/device receipt. */
class CarPlayNetworkControlsTest {
    @Test fun advancedNetworkControlsEditSharedDraftWithNoSectionApplyOrAaWrites() {
        val editor = source("ui/settings/CarPlaySettings.kt")
        for (field in listOf("networkFamily", "apFrequencyMhz", "apSecurity", "localAddress")) {
            assertTrue("Missing $field draft control", editor.contains("copy($field ="))
        }
        assertTrue(editor.contains("\"auto\" to \"Auto\""))
        assertTrue(editor.contains("\"ipv4\" to \"IPv4\""))
        assertTrue(editor.contains("\"ipv6\" to \"IPv6\""))
        assertTrue(editor.contains("\"wpa2\" to \"WPA2\""))
        assertTrue(editor.contains("\"wpa3\" to \"WPA3\""))
        assertTrue(editor.contains("if (!draft.sharedWifiOverride)"))
        assertTrue(editor.contains("previously trusted iPhone"))
        assertTrue(editor.contains("accept CarPlay on the iPhone"))
        assertTrue(editor.contains("Wi-Fi BSSID"))
        assertTrue(editor.contains("not the phone address or Wi-Fi BSSID"))
        assertFalse(editor.contains("Leave the Bluetooth override empty to use the real system address"))
        assertFalse(editor.contains("commitCarPlaySettings"))
        assertFalse(editor.contains("applySettingsAndRestart"))
        assertFalse(editor.contains("updateWpp"))
        val navigation = source("ui/navigation/AppNavHost.kt")
        assertTrue(navigation.contains("applySettingsAndRestart("))
        assertTrue(navigation.contains("settingsViewModel::commitCarPlaySettings"))
    }

    private fun source(path: String): String {
        val root = generateSequence(File(requireNotNull(System.getProperty("user.dir")))) { it.parentFile }
            .first { File(it, "app/src/main").isDirectory }
        return File(root, "app/src/main/java/com/openautolink/app/$path").readText()
    }
}
