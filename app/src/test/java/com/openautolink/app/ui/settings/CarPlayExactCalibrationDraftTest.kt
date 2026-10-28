package com.openautolink.app.ui.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.openautolink.app.data.AppPreferences
import com.openautolink.app.data.CarPlaySettings
import java.io.File
import java.io.IOException
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test

class CarPlayExactCalibrationDraftTest {
    @Test fun onlyMatchingCanonicalIntegerTextCanPassGlobalValidation() {
        for (width in 100..400) {
            val next = CarPlaySettings(widthPhysicalMm = width, physicalWidthDraft = width.toString())
            assertNull(carPlayEditError(next))
            val json = CarPlaySettings.encode(next)
            assertFalse(json.contains("physicalWidthDraft"))
            val restored = CarPlaySettings.decode(json)
            assertEquals(width, restored.widthPhysicalMm)
            assertNull(restored.physicalWidthDraft)
        }
        for (raw in listOf("", "2", "2.", ".", "0", "401", "-1", "225.0", "+225", "0225", " 225", "225 ", "2147483648")) {
            val next = CarPlaySettings(widthPhysicalMm = 225, physicalWidthDraft = raw)
            assertNotNull("Accepted [$raw]", carPlayEditError(next))
            assertNull("Calibration must not disable unrelated Video controls", next.videoError())
        }
        assertNotNull(carPlayEditError(CarPlaySettings(widthPhysicalMm = 225, physicalWidthDraft = "226")))
    }

    @Test fun historicalRestoreGuardKeepsValidWidthsAndRepairsOnlyInvalidCalibration() {
        for (width in listOf(100, 225, 400, 422, 1178, 0, -1, 401)) {
            val restored = CarPlaySettings.decode("""{"widthPhysicalMm":$width,"ssid":"keep","fps":31}""")
            assertEquals(if (width in 100..400) width else 200, restored.widthPhysicalMm)
            assertEquals("keep", restored.ssid)
            assertEquals(31, restored.fps)
            assertNull(restored.physicalWidthDraft)
        }
    }

    @Test fun failedRealStorePublicationRetainsExactTextAndRetriesWithoutOverwritingAudio() = runBlocking {
        val directory = File("build/tmp/calibration-failure-${java.util.UUID.randomUUID()}").apply { mkdirs() }
        try {
            open(directory) { prefs ->
                prefs.setCarPlaySettings(CarPlaySettings(ssid = "original"))
                val draft = CarPlaySettingsDraft()
                draft.update { it.copy(widthPhysicalMm = 225, physicalWidthDraft = "225", autoScale = false) }
                draft.update { it.copy(ssid = "edited") }
                assertFalse(draft.commit { merge, _ -> prefs.updateCarPlaySettings(merge) {
                    check(carPlayEditError(it) == null)
                    // A later partial edit arrives while this valid snapshot is failing storage.
                    draft.update { current -> current.copy(physicalWidthDraft = "2.") }
                    throw IOException("fixture storage failure")
                } })
                assertEquals(200, prefs.carPlaySettings.first().widthPhysicalMm)
                assertEquals("2.", draft.preview(prefs.carPlaySettings.first()).physicalWidthDraft)
                assertEquals(225, draft.preview(prefs.carPlaySettings.first()).widthPhysicalMm)
                draft.update { it.copy(physicalWidthDraft = "225") }
                prefs.setVolumeOffsetMedia(7)
                assertTrue(draft.commit { merge, _ -> prefs.updateCarPlaySettings(merge) {
                    check(carPlayEditError(it) == null)
                } })
                assertEquals(7, prefs.volumeOffsetMedia.first())
            }
            open(directory) { prefs ->
                val restored = prefs.carPlaySettings.first()
                assertEquals(225, restored.widthPhysicalMm)
                assertEquals("edited", restored.ssid)
                assertFalse(restored.autoScale)
                assertNull(restored.physicalWidthDraft)
                assertEquals(7, prefs.volumeOffsetMedia.first())
            }
        } finally { directory.deleteRecursively() }
    }

    @Test fun inFlightGlobalSaveMergesLatestSsidAudioAndKeepsLaterPartialDraft() = runBlocking {
        val directory = File("build/tmp/calibration-race-${java.util.UUID.randomUUID()}").apply { mkdirs() }
        try {
            open(directory) { prefs ->
                prefs.setCarPlaySettings(CarPlaySettings())
                val draft = CarPlaySettingsDraft()
                draft.update { it.copy(widthPhysicalMm = 225, physicalWidthDraft = "225") }
                val entered = CompletableDeferred<Unit>()
                val release = CompletableDeferred<Unit>()
                val save = async(Dispatchers.IO) { draft.commit { merge, _ ->
                    entered.complete(Unit); release.await()
                    prefs.updateCarPlaySettings(merge) { check(carPlayEditError(it) == null) }
                } }
                try {
                    withTimeout(5000) { entered.await() }
                    prefs.updateCarPlaySettings({ it.copy(ssid = "racing-network") })
                    prefs.setVolumeOffsetNavigation(9)
                    draft.update { it.copy(physicalWidthDraft = "2.") }
                } finally { release.complete(Unit) }
                assertTrue(withTimeout(5000) { save.await() })
                val saved = prefs.carPlaySettings.first()
                assertEquals(225, saved.widthPhysicalMm)
                assertEquals("racing-network", saved.ssid)
                assertEquals(9, prefs.volumeOffsetNavigation.first())
                assertEquals("2.", draft.preview(saved).physicalWidthDraft)
                assertFalse(draft.commit { merge, _ -> prefs.updateCarPlaySettings(merge) {
                    check(carPlayEditError(it) == null)
                } })
                assertEquals("2.", draft.preview(saved).physicalWidthDraft)
                assertEquals(saved, prefs.carPlaySettings.first())
            }
        } finally { directory.deleteRecursively() }
    }

    private suspend fun open(directory: File, block: suspend (AppPreferences) -> Unit) {
        val job = SupervisorJob()
        val store = PreferenceDataStoreFactory.create(scope = CoroutineScope(Dispatchers.IO + job),
            produceFile = { File(directory, "settings.preferences_pb") })
        val prefs = AppPreferences::class.java.getDeclaredConstructor(DataStore::class.java)
            .apply { isAccessible = true }.newInstance(store)
        try { block(prefs) } finally { job.cancelAndJoin() }
    }
}
