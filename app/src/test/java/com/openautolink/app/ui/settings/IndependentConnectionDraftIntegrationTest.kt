package com.openautolink.app.ui.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.openautolink.app.data.AppPreferences
import com.openautolink.app.data.CarPlaySettings
import java.io.File
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

/** Disk-backed settings transaction coverage; no device, protocol startup or USB permission. */
class IndependentConnectionDraftIntegrationTest {
    @Test fun disabledCarPlayConnectionEditWaitsForGlobalSaveAndNeverChangesAndroidAuto() = runTest {
        withPreferences { preferences ->
            preferences.setDirectTransport(AppPreferences.DIRECT_TRANSPORT_WPP)
            preferences.setWppBssid("10:20:30:40:50:60")
            val original = CarPlaySettings(enabled = false, connectionMode = "usb")
            preferences.setCarPlaySettings(original)
            val draft = CarPlaySettingsDraft()
            draft.update { it.copy(connectionMode = "wireless") }
            assertEquals(original, preferences.carPlaySettings.first())
            assertEquals("wireless", draft.preview(original).connectionMode)
            val events = mutableListOf<String>()
            applySettingsAndRestart({ draft.commit { merge, password ->
                assertNull(password)
                preferences.updateCarPlaySettings(merge) { check(carPlayEditError(it) == null) }
                events += "persisted"
            } }, { events += "restart" }, { events += "close" })
            assertEquals(listOf("persisted", "restart", "close"), events)
            assertEquals("wireless", preferences.carPlaySettings.first().connectionMode)
            assertFalse(preferences.carPlaySettings.first().enabled)
            assertEquals(AppPreferences.DIRECT_TRANSPORT_WPP, preferences.directTransport.first())
            assertEquals("10:20:30:40:50:60", preferences.wppBssid.first())
        }
    }

    @Test fun connectionDraftSurvivesCrossTabEditsWithoutClobberingLatestRecordOrAaNormalMode() = runTest {
        withPreferences { preferences ->
            preferences.setDirectTransport(AppPreferences.DIRECT_TRANSPORT_WPP)
            val original = CarPlaySettings(enabled = true, connectionMode = "usb")
            preferences.setCarPlaySettings(original)
            val draft = CarPlaySettingsDraft()
            draft.update { it.copy(connectionMode = "wireless") }
            draft.update { it.copy(resolution = "1080p", fps = 45) }
            draft.update { it.copy(widthPhysicalMm = 350, autoScale = false) }
            val latest = original.copy(ssid = "latest-network", safeLeft = 123)
            preferences.setCarPlaySettings(latest)
            assertTrue(draft.commit { merge, password ->
                assertNull(password)
                preferences.updateCarPlaySettings(merge) { check(carPlayEditError(it) == null) }
            })
            assertEquals(latest.copy(connectionMode = "wireless", resolution = "1080p", fps = 45,
                widthPhysicalMm = 350, autoScale = false), preferences.carPlaySettings.first())
            assertEquals(AppPreferences.DIRECT_TRANSPORT_WPP, preferences.directTransport.first())
            assertEquals(preferences.carPlaySettings.first(), draft.preview(preferences.carPlaySettings.first()))
        }
    }

    @Test fun normalConnectionsPersistIndependentlyAcrossDataStoreReopen() = runTest {
        val directory = temporaryDirectory()
        try {
            openPreferences(directory) { preferences ->
                preferences.setDirectTransport(AppPreferences.DIRECT_TRANSPORT_WPP)
                preferences.setCarPlaySettings(CarPlaySettings(enabled = true, connectionMode = "usb"))
            }
            openPreferences(directory) { preferences ->
                assertEquals(AppPreferences.DIRECT_TRANSPORT_WPP, preferences.directTransport.first())
                assertTrue(preferences.carPlaySettings.first().enabled)
                assertEquals("usb", preferences.carPlaySettings.first().connectionMode)
                preferences.setCarPlaySettings(preferences.carPlaySettings.first().copy(connectionMode = "wireless"))
                preferences.setDirectTransport(AppPreferences.DIRECT_TRANSPORT_USB)
            }
            openPreferences(directory) { preferences ->
                assertEquals(AppPreferences.DIRECT_TRANSPORT_USB, preferences.directTransport.first())
                assertTrue(preferences.carPlaySettings.first().enabled)
                assertEquals("wireless", preferences.carPlaySettings.first().connectionMode)
            }
        } finally { directory.deleteRecursively() }
    }

    @Test fun failedConnectionCommitRetainsDraftAndDoesNotRestartOrClose() = runTest {
        withPreferences { preferences ->
            val original = CarPlaySettings(connectionMode = "usb")
            preferences.setCarPlaySettings(original)
            val draft = CarPlaySettingsDraft()
            draft.update { it.copy(connectionMode = "wireless") }
            var restarted = false
            var closed = false
            applySettingsAndRestart({ draft.commit { _, _ -> throw java.io.IOException() } },
                { restarted = true }, { closed = true })
            assertFalse(restarted)
            assertFalse(closed)
            assertEquals(original, preferences.carPlaySettings.first())
            assertEquals("wireless", draft.preview(original).connectionMode)
        }
    }

    private fun temporaryDirectory() = File("build/tmp/independent-connection-${java.util.UUID.randomUUID()}").apply { mkdirs() }

    private suspend fun withPreferences(block: suspend (AppPreferences) -> Unit) {
        val directory = temporaryDirectory()
        try { openPreferences(directory, block) } finally { directory.deleteRecursively() }
    }

    private suspend fun openPreferences(directory: File, block: suspend (AppPreferences) -> Unit) {
        val job = SupervisorJob()
        val store = PreferenceDataStoreFactory.create(scope = CoroutineScope(Dispatchers.IO + job),
            produceFile = { File(directory, "settings.preferences_pb") })
        val constructor = AppPreferences::class.java.getDeclaredConstructor(DataStore::class.java).apply { isAccessible = true }
        try { block(constructor.newInstance(store)) } finally { job.cancelAndJoin() }
    }
}
