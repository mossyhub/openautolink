package com.openautolink.app.ui.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.openautolink.app.data.AppPreferences
import com.openautolink.app.data.CarPlaySettings
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class CarPlayAtomicCommitTest {
    @Test fun carPlayDisplayAndVideoApplyIgnoresIncompleteUnusedAndroidAutoNetworkDefaults() = runTest {
        withPreferences { preferences ->
            val original = CarPlaySettings(enabled = true, resolution = "1440p", fps = 40,
                sharedWifiOverride = true, peerAddress = "192.0.2.2", interfaceName = "wlan0",
                ssid = "test-network", phoneAddress = "10:20:30:40:50:60", receiverAddress = "20:30:40:50:60:70")
            preferences.setCarPlaySettings(original)
            // Deliberately incomplete unused WPP setup must not gate a CarPlay-only edit.
            preferences.setDirectTransport(AppPreferences.DEFAULT_DIRECT_TRANSPORT)
            preferences.setHotspotSsid("")
            preferences.setHotspotPassword("")
            preferences.setWppBssid("")
            val draft = CarPlaySettingsDraft()
            draft.update { it.copy(widthPhysicalMm = 350, autoScale = false) }
            draft.update { it.copy(resolution = "1080p", fps = 45) }
            val events = mutableListOf<String>()
            applySettingsAndRestart({ draft.commit { merge, password ->
                assertNull(password)
                preferences.updateCarPlaySettings(merge) { check(carPlayEditError(it) == null) }
                events += "persisted"
            } }, { events += "restart" }, { events += "close" })
            assertEquals(listOf("persisted", "restart", "close"), events)
            assertEquals(original.copy(widthPhysicalMm = 350, autoScale = false, resolution = "1080p", fps = 45),
                preferences.carPlaySettings.first())
            assertEquals(AppPreferences.DEFAULT_DIRECT_TRANSPORT, preferences.directTransport.first())
            assertEquals("", preferences.hotspotSsid.first())
            assertEquals("", preferences.hotspotPassword.first())
            assertEquals("", preferences.wppBssid.first())
        }
    }

    @Test fun everyNetworkFieldCommitsAtomicallyWithoutClobberingLatestCarPlayOrAa() = runTest {
        withPreferences { preferences ->
            val draft = CarPlaySettingsDraft()
            draft.update { it.copy(networkFamily = "ipv6") }
            draft.update { it.copy(localAddress = "fe80::1%ap_br_swlan0") }
            draft.update { it.copy(peerAddress = "fe80::2") }
            draft.update { it.copy(apFrequencyMhz = 5745) }
            draft.update { it.copy(apSecurity = "wpa3") }
            val latest = CarPlaySettings(ssid = "latest-car-ap", receiverAddress = "20:30:40:50:60:70",
                resolution = "1080p", fps = 60, widthPhysicalMm = 350, autoScale = false)
            preferences.setCarPlaySettings(latest)
            preferences.setWppChannelMhz(2412)
            preferences.setDirectTransport("wpp")
            preferences.setAaResolution("1440p")
            val events = mutableListOf<String>()
            applySettingsAndRestart({ draft.commit { merge, password ->
                assertNull(password)
                preferences.updateCarPlaySettings(merge) { check(carPlayEditError(it) == null) }
                events += "persisted"
            } }, { events += "restart" }, { events += "close" })
            val stored = preferences.carPlaySettings.first()
            assertEquals(latest.copy(networkFamily = "ipv6", localAddress = "fe80::1%ap_br_swlan0",
                peerAddress = "fe80::2", apFrequencyMhz = 5745, apSecurity = "wpa3"), stored)
            assertEquals(stored, CarPlaySettings.decode(CarPlaySettings.encode(stored)))
            assertEquals(2412, preferences.wppChannelMhz.first())
            assertEquals("wpp", preferences.directTransport.first())
            assertEquals("1440p", preferences.aaResolution.first())
            assertEquals(listOf("persisted", "restart", "close"), events)
        }
    }

    @Test fun malformedNetworkDraftKeepsAtomicRecordAndDoesNotRestart() = runTest {
        withPreferences { preferences ->
            val original = CarPlaySettings()
            preferences.setCarPlaySettings(original)
            val draft = CarPlaySettingsDraft()
            draft.update { it.copy(localAddress = "fe80::1%0", apSecurity = "wpa3", apFrequencyMhz = 5745) }
            var restarted = false
            var closed = false
            applySettingsAndRestart({ draft.commit { merge, _ ->
                preferences.updateCarPlaySettings(merge) { check(carPlayEditError(it) == null) }
            } }, { restarted = true }, { closed = true })
            assertEquals(original, preferences.carPlaySettings.first())
            assertEquals("fe80::1%0", draft.preview(original).localAddress)
            assertEquals(5745, draft.preview(original).apFrequencyMhz)
            assertEquals("wpa3", draft.preview(original).apSecurity)
            assertFalse(restarted)
            assertFalse(closed)
        }
    }

    @Test fun globalCommitMergesInsideAtomicStoreAndKeepsUnrelatedPreferences() = runTest {
        withPreferences { preferences ->
            val draft = CarPlaySettingsDraft()
            draft.update { it.copy(resolution = "1080p", fps = 60) }
            preferences.setCarPlaySettings(CarPlaySettings(ssid = "latest-network", enabled = false))
            preferences.setVideoCodec("h265")
            assertTrue(draft.commit { merge, _ -> preferences.updateCarPlaySettings(merge) })
            val stored = preferences.carPlaySettings.first()
            assertEquals("latest-network", stored.ssid)
            assertFalse(stored.enabled)
            assertEquals("1080p", stored.resolution)
            assertEquals("h265", preferences.videoCodec.first())
        }
    }

    @Test fun validationOrEncryptedWriteFailureCannotPublishConfiguration() = runTest {
        withPreferences { preferences ->
            preferences.setCarPlaySettings(CarPlaySettings(ssid = "unchanged"))
            val draft = CarPlaySettingsDraft()
            draft.update { it.copy(ssid = "pending") }
            draft.setPassword("pending-secret")
            assertFalse(draft.commit { merge, _ ->
                preferences.updateCarPlaySettings(merge) { throw java.io.IOException("secret details") }
            })
            assertEquals("unchanged", preferences.carPlaySettings.first().ssid)
            assertEquals("pending", draft.preview(preferences.carPlaySettings.first()).ssid)
            assertEquals("pending-secret", draft.pendingPassword())
        }
    }

    @Test fun invalidDraftCannotWritePasswordRestartOrDismissOverlay() = runTest {
        withPreferences { preferences ->
            val original = CarPlaySettings(ssid = "unchanged")
            preferences.setCarPlaySettings(original)
            val draft = CarPlaySettingsDraft()
            draft.update { it.copy(peerAddress = "invalid-ip") }
            draft.setPassword("pending-secret")
            var secretWritten = false
            var restarted = false
            var closed = false
            applySettingsAndRestart({
                draft.commit { merge, _ ->
                    preferences.updateCarPlaySettings(merge) { next ->
                        check(carPlayEditError(next) == null)
                        secretWritten = true
                    }
                }
            }, { restarted = true }, { closed = true })
            assertFalse(secretWritten)
            assertFalse(restarted)
            assertFalse(closed)
            assertEquals(original, preferences.carPlaySettings.first())
            assertEquals("pending-secret", draft.pendingPassword())
        }
    }

    private suspend fun withPreferences(block: suspend (AppPreferences) -> Unit) {
        val directory = File("build/tmp/carplay-atomic-${java.util.UUID.randomUUID()}").apply { mkdirs() }
        val job = SupervisorJob()
        val store = PreferenceDataStoreFactory.create(scope = CoroutineScope(Dispatchers.IO + job),
            produceFile = { File(directory, "settings.preferences_pb") })
        val constructor = AppPreferences::class.java.getDeclaredConstructor(DataStore::class.java).apply { isAccessible = true }
        try { block(constructor.newInstance(store)) }
        finally { job.cancelAndJoin(); directory.deleteRecursively() }
    }
}
