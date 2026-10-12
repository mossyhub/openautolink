package com.openautolink.app.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class CarPlaySettingsTest {
    @Test fun sharedModeRetainsUsbAndMigratesRetiredWirelessModes() = runBlocking {
        val store = MemoryStore()
        val preferences = preferences(store)
        assertEquals("wpp", preferences.directTransport.first())
        preferences.setDirectTransport("hotspot")
        assertEquals("wpp", preferences.directTransport.first())
        preferences.setDirectTransport("nearby")
        assertEquals("wpp", preferences.directTransport.first())
        preferences.setDirectTransport("usb")
        assertEquals("usb", preferences.directTransport.first())
    }

    @Test fun newInstallSupportsUsbAndCarPlayWritesNeverChangeAndroidAuto() = runBlocking {
        val store = MemoryStore()
        val preferences = preferences(store)
        val initial = preferences.carPlaySettings.first()
        assertTrue(initial.enabled)
        assertEquals("720p", initial.resolution)
        assertEquals(30, initial.fps)
        assertFalse(initial.sharedWifiOverride)
        assertEquals("ap_br_swlan0", initial.interfaceName)
        assertEquals("", initial.phoneAddress)
        assertEquals("", initial.peerAddress)
        preferences.setDirectTransport("wpp")
        preferences.setAaResolution("1440p")
        val saved = initial.copy(enabled = true, resolution = "1080p", fps = 60,
            phoneAddress = "10:20:30:40:50:60", peerAddress = "192.0.2.20", ssid = " Car ")
        val before = store.writes
        preferences.setCarPlaySettings(saved)
        assertEquals(before + 1, store.writes)
        assertEquals(saved, preferences.carPlaySettings.first())
        assertEquals("wpp", preferences.directTransport.first())
        assertEquals("1440p", preferences.aaResolution.first())
        assertEquals(setOf("direct_transport", "aa_resolution", "carplay_settings_v1"), store.value.asMap().keys.map { it.name }.toSet())
        assertFalse(store.value[stringPreferencesKey("carplay_settings_v1")]!!.contains("passphrase"))
    }

    @Test fun customDimensionsContinuousWidthAndIntegerRatePersistAtomicallyAndMapToRuntime() = runBlocking {
        val store = MemoryStore()
        val preferences = preferences(store)
        preferences.setAaResolution("1440p")
        val saved = CarPlaySettings(enabled = true, resolution = "custom", widthPixels = 2400,
            heightPixels = 960, fps = 59, widthPhysicalMm = 225, autoScale = false, ssid = "keep")
        val before = store.writes
        preferences.updateCarPlaySettings({ saved }) { check(it.videoError() == null) }
        assertEquals(before + 1, store.writes)
        val restored = preferences.carPlaySettings.first()
        assertEquals(saved, restored)
        assertEquals("1440p", preferences.aaResolution.first())
        val runtime = CarPlaySettingsPolicy.runtimeConfig(restored, restored.displayRequest())
        assertEquals(2400, runtime.display.width)
        assertEquals(960, runtime.display.height)
        assertEquals(59, runtime.display.fps)
        assertEquals(225, runtime.display.physicalSize.widthMm)
        assertEquals(90, runtime.display.physicalSize.heightMm)
        assertEquals("keep", runtime.ssid)
    }

    @Test fun intermediateSliderWidthsSurviveEntirePreferenceToActualOfferPipeline() = runBlocking {
        val store = MemoryStore()
        val preferences = preferences(store)
        for (width in listOf(125, 225, 399)) {
            val saved = CarPlaySettings(enabled = true, resolution = "custom", widthPixels = 2400,
                heightPixels = 960, fps = 31, widthPhysicalMm = width, autoScale = false)
            preferences.setCarPlaySettings(saved)
            val restored = preferences.carPlaySettings.first()
            assertEquals(width, restored.widthPhysicalMm)
            val canonical = com.openautolink.carplaylab.media.CarPlayDisplayPreferences.fromStored(
                restored.resolution, restored.fps, restored.widthPhysicalMm,
                customWidth = restored.widthPixels, customHeight = restored.heightPixels)
            assertEquals(restored.displayRequest(), canonical)
            assertEquals(width, com.shilapi.xcertplay.airplay.AirPlayDisplaySettings.sanitizeWidthPhysicalMm(width))
            val runtime = CarPlaySettingsPolicy.runtimeConfig(restored, canonical)
            val config = com.shilapi.xcertplay.airplay.AirPlayConfig(
                deviceName = "settings-test", deviceId = "00:11:22:33:44:55", btMac = "00:11:22:33:44:55",
                sourceVersion = "950.7.1", main = runtime.display.toAirPlayDisplay(), port = 41234)
            val info = com.shilapi.xcertplay.airplay.AirPlayInfoPlist.build(config, emptySet())
            val main = (info["displays"] as List<*>).first() as Map<*, *>
            assertEquals(width, main["widthPhysical"])
            assertEquals(Math.round(width * 960.0 / 2400).toInt(), main["heightPhysical"])
            assertEquals(2400, main["widthPixels"]); assertEquals(960, main["heightPixels"])
            assertEquals(31, main["maxFPS"])
            val auto = canonical.copy(autoScaleInput = com.openautolink.carplaylab.media.CarPlayAutoScalePolicy.Input(
                960, 540, 1920, 1080, 240.0, 240.0, 160))
            assertEquals(com.openautolink.carplaylab.media.CarPlayAutoScalePolicy.Basis.HARDWARE_ESTIMATE, auto.physicalSize.basis)
            assertEquals(Math.round(960 * 25.4 / 240).toInt(), auto.physicalSize.widthMm)
        }
    }

    @Test fun invalidStoredProfilesFailSafeWithoutGuessingPhoneIdentity() = runBlocking {
        val store = MemoryStore()
        store.updateData { androidx.datastore.preferences.core.preferencesOf(
            stringPreferencesKey("carplay_settings_v1") to """{"enabled":true,"resolution":"8k","fps":120,"phoneAddress":""}"""
        ) }
        val preferences = preferences(store)
        assertEquals("720p", preferences.carPlayResolution.first())
        assertEquals(30, preferences.carPlayFps.first())
        assertEquals("", preferences.carPlayPhoneAddress.first())
        assertFalse(CarPlaySettings.decode("not-json").enabled)
        preferences.setCarPlaySettings(CarPlaySettings(enabled = true, peerAddress = "192.0.2.21",
            phoneAddress = "10:20:30:40:50:60", sharedWifiOverride = true, interfaceName = "wlan0",
            ssid = " LAN ", receiverAddress = "20:30:40:50:60:70"))
        assertTrue(preferences.carPlayEnabled.first())
        assertEquals("192.0.2.21", preferences.carPlayPeerAddress.first())
        assertEquals("wlan0", preferences.carPlayInterface.first())
        assertTrue(preferences.carPlaySharedWifiOverride.first())
        assertEquals(" LAN ", preferences.carPlaySsid.first())
        assertEquals("20:30:40:50:60:70", preferences.carPlayReceiverAddress.first())
        preferences.setCarPlayEnabled(false)
        assertFalse(preferences.carPlayEnabled.first())
        assertEquals("192.0.2.21", preferences.carPlayPeerAddress.first())
    }

    @Test fun explicitPhoneAndReadinessNeverInferTrustFromFriendlyName() {
        val phones = listOf(CarPlayPairedPhone("10:20:30:40:50:60", "iPhone"),
            CarPlayPairedPhone("10:20:30:40:50:70", "iPhone"))
        assertNull(CarPlaySettingsPolicy.selectedPhone("", phones))
        assertNull(CarPlaySettingsPolicy.selectedPhone("10:20:30:40:50:80", phones))
        assertEquals(phones[1], CarPlaySettingsPolicy.selectedPhone(phones[1].address, phones))
        assertEquals("CarPlay is off", CarPlaySettingsPolicy.requiredAction(CarPlaySettings(enabled = false), phones, false, false))
        var settings = CarPlaySettings(enabled = true, sharedWifiOverride = true,
            receiverAddress = "20:30:40:50:60:70")
        assertEquals("Select a paired phone", CarPlaySettingsPolicy.requiredAction(settings, phones, false, false))
        settings = settings.copy(phoneAddress = phones[1].address)
        assertEquals("Provision authentication identity on this head unit", CarPlaySettingsPolicy.requiredAction(settings, phones, false, false))
        assertEquals("Enter the selected phone's numeric IP address", CarPlaySettingsPolicy.requiredAction(settings, phones, true, false))
        settings = settings.copy(peerAddress = "192.0.2.21", ssid = " Car ")
        assertEquals("Save a Wi-Fi passphrase", CarPlaySettingsPolicy.requiredAction(settings, phones, true, false))
        assertEquals("Ready for an explicit connection; phone acceptance is not verified", CarPlaySettingsPolicy.requiredAction(settings, phones, true, true))
        assertFalse(CarPlaySettingsPolicy.numericPeer("iphone.local"))
        assertFalse(CarPlaySettingsPolicy.numericPeer("192.0.2.999"))
        assertFalse(CarPlaySettingsPolicy.numericPeer("0.0.0.0"))
        assertFalse(CarPlaySettingsPolicy.numericPeer("224.0.0.1"))
        assertTrue(CarPlaySettingsPolicy.numericPeer("fe80::1234%ap_br_swlan0"))
        assertTrue(CarPlaySettingsPolicy.numericPeer("192.0.2.21"))
    }

    @Test fun passwordFailureCannotPublishConfigurationAndUnchangedPasswordIsNotRewritten() = runBlocking {
        val written = mutableListOf<CarPlaySettings>()
        val secrets = mutableListOf<String>()
        val settings = CarPlaySettings(enabled = true, ssid = " New LAN ")
        val editor = CarPlaySettingsWriter({ written.add(it) }, { secrets.add(it) })
        assertTrue(editor.save(settings, null))
        assertEquals(listOf(settings), written)
        assertTrue(secrets.isEmpty())
        assertTrue(editor.save(settings, "new-passphrase"))
        assertEquals(listOf("new-passphrase"), secrets)
        assertTrue(editor.save(settings, "")) // Deliberately clear the encrypted saved secret.
        assertEquals(listOf("new-passphrase", ""), secrets)
        written.clear()
        val failed = CarPlaySettingsWriter({ written.add(it) }, { error("secret failure") })
        assertFalse(failed.save(settings, "not-logged"))
        assertTrue(written.isEmpty())
    }

    /** Lexical wiring guard only; JVM cannot execute Android Compose without a device. */
    @Test fun realOverlayExposesGatedCarPlayEditorAndNeverPersistsSecretInUiState() {
        fun source(path: String): String {
            val root = generateSequence(java.io.File(requireNotNull(System.getProperty("user.dir")))) { it.parentFile }
                .first { java.io.File(it, "app/src/main").isDirectory }
            return java.io.File(root, "app/src/main/java/com/openautolink/app/$path").readText()
        }
        val screen = source("ui/settings/SettingsScreen.kt")
        val vm = source("ui/settings/SettingsViewModel.kt")
        val editor = source("ui/settings/CarPlaySettings.kt")
        assertTrue(screen.contains("CarPlaySettingsSection(viewModel)"))
        assertTrue(editor.contains("if (!settings.enabled) return"))
        assertTrue(editor.contains("viewModel.updateCarPlayDraft"))
        assertTrue(editor.contains("viewModel.updateCarPlayPassword(it)"))
        assertFalse(editor.contains("viewModel.saveCarPlaySettings"))
        assertTrue(editor.contains("PasswordVisualTransformation()"))
        assertFalse(Regex("""\brememberSaveable\s*(\(|\{)""").containsMatchIn(editor))
        assertTrue(vm.contains("SavedWifiPassword as EncryptedSavedWifiPassword"))
        assertTrue(vm.contains("suspend fun commitCarPlaySettings(): Boolean"))
        assertTrue(vm.contains("preferences.updateCarPlaySettings(merge)"))
        assertTrue(vm.contains("if (password != null) EncryptedSavedWifiPassword(getApplication()).save(password)"))
        assertFalse(vm.contains("SavedStateHandle"))
        assertFalse(Regex("password.*(?:StateFlow|MutableStateFlow)\\s*<\\s*(?:String|CharArray)", RegexOption.IGNORE_CASE).containsMatchIn(vm))
        assertFalse(vm.contains("setCarPlayPassword"))
    }

    @Test fun ipv6LinkLocalRequiresScopeWithoutInferringItFromTheNetworkMode() {
        assertFalse(CarPlaySettingsPolicy.numericPeer("fe80::1234"))
        assertTrue(CarPlaySettingsPolicy.numericPeer("fe80::1234%ap_br_swlan0"))
        assertFalse(CarPlaySettingsPolicy.numericPeer("fe80::1234%"))
        assertFalse(CarPlaySettingsPolicy.numericPeer("fe80::1234%ap/br"))
        assertTrue(CarPlaySettingsPolicy.numericPeer("2001:db8::1234"))
    }

    private fun preferences(store: DataStore<Preferences>): AppPreferences =
        AppPreferences::class.java.getDeclaredConstructor(DataStore::class.java).apply {
            isAccessible = true
        }.newInstance(store)

    private class MemoryStore : DataStore<Preferences> {
        private val state = MutableStateFlow(emptyPreferences())
        override val data: Flow<Preferences> = state
        val value get() = state.value
        var writes = 0
        override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences =
            transform(state.value).also { state.value = it; writes++ }
    }
}
