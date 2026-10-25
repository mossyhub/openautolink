package com.openautolink.app.ui.settings

import android.app.Application
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import com.openautolink.app.data.CarPlaySettings
import com.openautolink.app.ui.testing.createOalComposeRule
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import io.mockk.*
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@org.robolectric.annotation.ConscryptMode(org.robolectric.annotation.ConscryptMode.Mode.OFF)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [32], qualifiers = "w1000dp-h1000dp-mdpi")
class ManualBluetoothSettingsUiTest {
    @get:Rule val compose = createOalComposeRule()
    @Test fun singleSharedFieldRendersForUsbAndWirelessWithoutOpeningAdvanced() {
        val vm = mockk<SettingsViewModel>(relaxed = true)
        val settings = MutableStateFlow(CarPlaySettings(receiverAddress = "AA:BB:CC:DD:EE:10"))
        every { vm.carPlaySettings } returns settings
        every { vm.carPlayPhones } returns MutableStateFlow(emptyList())
        every { vm.carPlayIdentityReady } returns MutableStateFlow(false)
        every { vm.carPlayPasswordSaved } returns MutableStateFlow(false)
        every { vm.carPlaySettingsStatus } returns MutableStateFlow("")
        every { vm.carPlaySettingsSaving } returns MutableStateFlow(false)
        every { vm.carPlaySettingsLoaded } returns MutableStateFlow(true)
        every { vm.carPlayCredentialImportBusy } returns MutableStateFlow(false)
        every { vm.carPlayCredentialImportStatus } returns MutableStateFlow("")
        every { vm.pendingCarPlayPassword() } returns null
        compose.setContent { MaterialTheme { Column(Modifier.verticalScroll(rememberScrollState())) { CarPlaySettingsSection(vm) } } }
        for (mode in listOf("usb", "wireless", "usb")) {
            compose.runOnIdle { settings.value = settings.value.copy(connectionMode = mode) }
            compose.onAllNodesWithText("Head unit Bluetooth MAC (USB and wireless)").assertCountEquals(1)
            compose.onNode(hasSetTextAction() and hasText("AA:BB:CC:DD:EE:10")).performScrollTo().assertIsDisplayed()
        }
        verify(exactly = 0) { vm.updateBtMacOverride(any()) }
    }
    @Test fun existingFieldLivesOutsideEveryWirelessConditionalAndIsNotDuplicated() {
        val root = generateSequence(File(System.getProperty("user.dir")!!)) { it.parentFile }
            .first { File(it, "app/src/main").isDirectory }
        val source = File(root, "app/src/main/java/com/openautolink/app/ui/settings/CarPlaySettings.kt").readText()
        assertEquals(1, Regex("Head unit Bluetooth MAC").findAll(source).count())
        val field = source.indexOf("CarPlayField(\"Head unit Bluetooth MAC")
        assertTrue(field >= 0)
        for (condition in listOf("if (draft.connectionMode == \"wireless\") {", "if (advanced && draft.connectionMode == \"wireless\") {")) {
            var from = 0
            while (true) {
                val start = source.indexOf(condition, from)
                if (start < 0) break
                var end = source.indexOf('{', start) + 1
                var depth = 1
                while (depth > 0) { when (source[end++]) { '{' -> depth++; '}' -> depth-- } }
                assertFalse("Shared field is still inside wireless-only conditional", field in start until end)
                from = end
            }
        }
        assertTrue(source.contains("Settings app → System → About"))
        assertTrue(source.contains("not the phone address or Wi-Fi BSSID"))
    }
}
