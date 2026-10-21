package com.openautolink.app.ui.settings

import java.io.File
import org.junit.Assert.*
import org.junit.Test

/** Static wiring guards; runtime intent and rendered click tests are separate. */
class AndroidBluetoothSettingsWiringTest {
    private fun root() = generateSequence(File(requireNotNull(System.getProperty("user.dir")))) { it.parentFile }
        .first { File(it, "app/src/main").isDirectory }

    @Test fun alternateIsSeparateFromUnchangedGenericBluetoothButton() {
        val ui = File(root(), "app/src/main/java/com/openautolink/app/ui/settings/CarPlaySettings.kt").readText()
        assertTrue(ui.contains("OutlinedButton(onClick = { context.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) }) { Text(\"Bluetooth settings\") }"))
        assertTrue("Alternate must be available for USB as well as wireless", ui.substringAfter("if (!settings.enabled) return").substringBefore("val draft = settings").contains("AndroidBluetoothSettingsButton()"))
    }

    @Test fun packageVisibilityIsNarrowAndDoesNotRequestAllPackages() {
        val manifest = File(root(), "app/src/main/AndroidManifest.xml").readText()
        assertTrue("Exact package visibility is required", manifest.contains("<package android:name=\"com.android.car.settings\" />"))
        assertFalse(manifest.contains("QUERY_ALL_PACKAGES"))
    }
}
