package com.openautolink.app.ui.settings

import java.io.File
import com.openautolink.app.data.CarPlaySettings
import com.openautolink.carplaylab.media.CarPlayDisplayPreferences
import com.openautolink.carplaylab.media.CarPlayResolution
import org.junit.Assert.*
import org.junit.Test

/** Source wiring guards complement pure behavior tests; not rendered Compose/device proof. */
class CarPlaySettingsUiPolicyTest {
    @Test fun resolutionTapUsesOneQualifiedAtomicDraftUpdate() {
        assertResolutionWiring(source())
    }

    @Test fun resolutionChangePreservesAnExactlyQualifiedCurrentRate() {
        val current = CarPlaySettings(fps = 60, fitToWindow = false, phoneAddress = "saved", autoScale = false)
        val profiles = listOf(30, 60).map { CarPlayDisplayPreferences(CarPlayResolution.FULL_HD1080, it) }
        assertEquals(current.copy(resolution = "1080p"),
            CarPlaySettingsUiPolicy.selectResolution(current, "1080p", profiles))
    }

    @Test fun unsupportedRateUsesQualifiedThirtyThenNearestWithoutInventingProfiles() {
        val current = CarPlaySettings(fps = 60, fitToWindow = false)
        fun profiles(vararg rates: Int) = rates.map { CarPlayDisplayPreferences(CarPlayResolution.QHD1440, it) }
        assertEquals(current.copy(resolution = "1440p", fps = 30),
            CarPlaySettingsUiPolicy.selectResolution(current, "1440p", profiles(45, 30)))
        assertEquals(current.copy(resolution = "1440p", fps = 55),
            CarPlaySettingsUiPolicy.selectResolution(current, "1440p", profiles(35, 55, 45)))
        assertEquals(current, CarPlaySettingsUiPolicy.selectResolution(current, "4k", profiles(30)))
        assertEquals(current, CarPlaySettingsUiPolicy.selectResolution(current, "1440p", emptyList()))
    }

    @Test fun everyCanonicalRateAndResolutionPreservesAllNonVideoFields() {
        val setup = CarPlaySettings(enabled = true, phoneAddress = "saved-phone", peerAddress = "192.0.2.2",
            interfaceName = "custom0", sharedWifiOverride = true, ssid = "saved-ssid", receiverAddress = "saved-receiver",
            widthPhysicalMm = 350, autoScale = false, networkFamily = "ipv4", localAddress = "192.0.2.1",
            apFrequencyMhz = 5745, apSecurity = "wpa3")
        for (resolution in CarPlayResolution.entries) {
            for (rate in CarPlayDisplayPreferences.FRAME_RATES) {
                val current = setup.copy(fps = rate)
                val result = CarPlaySettingsUiPolicy.selectResolution(current, resolution.id,
                    listOf(CarPlayDisplayPreferences(resolution, rate, widthPhysicalMm = 350)))
                assertEquals(current.copy(resolution = resolution.id), result)
            }
        }
    }

    @Test fun nearestRateTiePrefersLowerAndIgnoresOtherResolutionReceipts() {
        val current = CarPlaySettings(fps = 45, fitToWindow = false)
        val profiles = listOf(CarPlayDisplayPreferences(CarPlayResolution.QHD1440, 50),
            CarPlayDisplayPreferences(CarPlayResolution.HD720, 45),
            CarPlayDisplayPreferences(CarPlayResolution.HD720, 30),
            CarPlayDisplayPreferences(CarPlayResolution.QHD1440, 40))
        assertEquals(current.copy(resolution = "1440p", fps = 40),
            CarPlaySettingsUiPolicy.selectResolution(current, "1440p", profiles))
    }

    @Test fun sourceContractRejectsResolutionOnlySplitAndUnqualifiedTaps() {
        val ui = source()
        val call = "CarPlaySettingsUiPolicy.selectResolution(current, resolution.id, availableDisplays)"
        assertRejected { assertResolutionWiring(ui.replace(call, "current.copy(resolution = resolution.id)")) }
        // Retaining the policy token elsewhere must not permit an unsafe actual callback.
        assertRejected { assertResolutionWiring(ui.replace(call, "current.copy(resolution = resolution.id)") + "\n// $call") }
        assertRejected { assertResolutionWiring(ui.replace(call,
            "$call\nviewModel.updateCarPlayDraft { it.copy(fps = 30) }")) }
        assertRejected { assertResolutionWiring(ui.replace(
            "availableDisplays.any { profile -> profile.resolution == resolution }", "true")) }
    }

    @Test fun connectionUsesSystemBluetoothAndCollapsesAuthenticationRecovery() {
        val ui = source()
        assertTrue(ui.contains("Text(\"Bluetooth settings\")"))
        assertFalse(ui.contains("Text(\"Pair a phone\")"))
        assertTrue(ui.contains("Pair your iPhone in GM / iPhone Bluetooth settings. No extra OAL pairing is needed."))
        assertTrue(ui.contains("var authenticationExpanded by remember { mutableStateOf(false) }"))
        val block = ui.substringAfter("if (authenticationExpanded) {").substringBefore("TextButton(onClick = { advanced")
        assertTrue("Import/replacement stays inside collapsed Authentication", block.contains("setupHelp = true"))
        assertTrue(block.contains("Replace authentication identity"))
        assertEquals("Only recovery may open the import dialog", 1, Regex("setupHelp = true").findAll(ui).count())
        assertTrue(ui.contains("context.assets.list(\"offline-mfi\")"))
        assertFalse("Settings must not bootstrap credentials", ui.contains("CarPlayMfiAssetBootstrap("))
        assertFalse(ui.contains("\"\${it.name} · \${it.address}\""))
        assertFalse(ui.contains("\"\${phone.name} · \${phone.address}\""))
        assertTrue(ui.contains("if (phoneDetails)"))
    }

    @Test fun sizingExplainsDirectionKeepsCalibrationAndMarksAndroidAutoSeparate() {
        val ui = source()
        assertTrue(ui.contains("Text(\"Auto sizing\""))
        assertTrue(ui.contains("Manual UI size"))
        assertTrue(ui.contains("Smaller physical width makes the UI larger."))
        assertTrue(ui.contains("SettingsHelp(\"Calibration: physical width"))
        assertTrue(ui.contains("steps = 0"))
        assertTrue(ui.contains("Android Auto settings below apply only to Android phones."))
        assertFalse(ui.contains("This does not guarantee phone-initiated ingress."))
        assertFalse(ui.contains("another AP client could race first enrollment."))
    }

    @Test fun phoneDetailsAllowsExactSelectionWhenFriendlyNamesCollide() {
        val details = source().substringAfter("if (phoneDetails) {").substringBefore("HorizontalDivider()")
        assertTrue(details.contains("OutlinedButton(onClick = {"))
        assertTrue(details.contains("it.copy(phoneAddress = phone.address)"))
        assertTrue(details.contains("enabled = !saving"))
        assertTrue(details.contains("phone.address}"))
    }

    private fun assertResolutionWiring(ui: String) {
        val choices = ui.substringAfter("fun CarPlayVideoSettingsSection(").substringAfter("testTag(\"carPlayResolutionMenu\")").substringBefore("DropdownMenuItem(text = { Text(\"Custom\")")
        assertTrue("Resolution changes must use the qualified atomic policy within the draft callback",
            Regex("viewModel\\.updateCarPlayDraft \\{ current ->\\s*" +
                "CarPlaySettingsUiPolicy\\.selectResolution\\(current, resolution\\.id, availableDisplays\\)\\s*\\}")
                .containsMatchIn(choices))
        assertEquals("Exactly one aggregate update per resolution tap", 1,
            Regex("viewModel\\.updateCarPlayDraft").findAll(choices).count())
        assertFalse("Do not copy resolution alone", choices.contains("copy(resolution = resolution.id)"))
        assertTrue("All resolution candidates need exact capability qualification", choices.contains(
            "availableDisplays.any { profile -> profile.resolution == resolution }"))
    }

    private fun assertRejected(block: () -> Unit) {
        try { block() } catch (_: AssertionError) { return }
        fail("Unsafe source mutation passed the UI wiring contract")
    }

    private fun source(): String {
        val root = generateSequence(File(requireNotNull(System.getProperty("user.dir")))) { it.parentFile }
            .first { File(it, "app/src/main").isDirectory }
        return File(root, "app/src/main/java/com/openautolink/app/ui/settings/CarPlaySettings.kt").readText()
    }
}
