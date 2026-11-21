package com.openautolink.app.ui.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.openautolink.app.carplay.OalCarPlayEvPilotTest
import com.openautolink.app.data.AppPreferences
import com.openautolink.app.data.CarPlaySettings
import java.io.File
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[32,34],application=android.app.Application::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
class OalCarPlayEvPilotSettingsTest {
    @Test fun carPlayEvDataDefaultsOnButRetainsExplicitOffAndRejectsMalformedRecords() {
        assertTrue(CarPlaySettings.decode(null).evDataEnabled)
        assertFalse(CarPlaySettings.decode("not a settings record").evDataEnabled)
        val legacy = CarPlaySettings(evRoutingPilot = OalCarPlayEvPilotTest.configuration(), evDataEnabled = false)
        assertFalse(CarPlaySettings.decode(CarPlaySettings.encode(legacy)).evDataEnabled)
        for (invalid in listOf("null", "1", "{}", "[]", "\"true\"", "false")) {
            val decoded = CarPlaySettings.decode("""{"ssid":"keep","evDataEnabled":$invalid}""")
            assertFalse(invalid, decoded.evDataEnabled)
            assertEquals("keep", decoded.ssid)
            assertTrue(decoded.enabled)
        }
        assertTrue(CarPlaySettings.decode("""{"evDataEnabled":true}""").evDataEnabled)
    }

    @Test fun malformedStoredAverageCannotBecomeManualPowerOrEraseOrdinarySettings() {
        for (invalid in listOf("null", "60", "{}", "[]", "\"1.\"", "\"1000.001\"", "\"1e2\"")) {
            val decoded = CarPlaySettings.decode("""{"ssid":"keep","evAverageDcChargeKw":$invalid}""")
            assertTrue(decoded.evDataEnabled)
            assertEquals("keep", decoded.ssid)
            assertEquals("", decoded.evAverageDcChargeKw)
        }
    }

    @Test fun optInIsDraftOnlyUntilSaveAndColdReopenPreservesExistingSettings() = runBlocking<Unit> {
        val dir = File("build/tmp/ev-data-${java.util.UUID.randomUUID()}").apply { mkdirs() }
        // A bad old calibration has no editor on the new page. It must not block Save.
        val legacy = OalCarPlayEvPilotTest.configuration().copy(pilotCapacityWh = "")
        try {
            open(dir) { prefs ->
                prefs.updateCarPlaySettings({ it.copy(evRoutingPilot = legacy, fps = 47, evDataEnabled = false) })
                val draft = CarPlaySettingsDraft()
                draft.update { it.copy(evDataEnabled = true) }
                assertFalse(prefs.carPlaySettings.first().evDataEnabled)
                prefs.updateCarPlaySettings({ it.copy(ssid = "concurrent-network") })
                assertTrue(draft.commit { merge, _ ->
                    prefs.updateCarPlaySettings(merge) { require(carPlayEditError(it) == null) }
                })
            }
            open(dir) { prefs ->
                val saved = prefs.carPlaySettings.first()
                assertTrue(saved.evDataEnabled)
                assertEquals(legacy, saved.evRoutingPilot)
                assertEquals(47, saved.fps)
                assertEquals("concurrent-network", saved.ssid)
                val draft = CarPlaySettingsDraft()
                draft.update { it.copy(evDataEnabled = false) }
                assertTrue(draft.commit { merge, _ ->
                    prefs.updateCarPlaySettings(merge) { require(carPlayEditError(it) == null) }
                })
            }
            open(dir) { prefs -> assertFalse(prefs.carPlaySettings.first().evDataEnabled) }
        } finally { dir.deleteRecursively() }
    }

    @Test fun invalidEnabledDraftBlocksExistingAtomicSaveAndColdReopenRetainsOnlyCommittedCalibration() = runBlocking<Unit> {
        val dir=File("build/tmp/ev-pilot-${java.util.UUID.randomUUID()}").apply {mkdirs()}
        try {
            open(dir) { prefs ->
                val draft=CarPlaySettingsDraft()
                draft.update {it.copy(evRoutingPilot=OalCarPlayEvPilotTest.configuration(),fps=59)}
                assertFalse(prefs.carPlaySettings.first().evRoutingPilot.enabled)
                prefs.updateCarPlaySettings({it.copy(ssid="concurrent-network")})
                assertTrue(draft.commit {merge,_ -> prefs.updateCarPlaySettings(merge) {require(carPlayEditError(it)==null)}})
                val previous=prefs.carPlaySettings.first()
                assertTrue(previous.evRoutingPilot.enabled);assertEquals(59,previous.fps);assertEquals("concurrent-network",previous.ssid)
                draft.update {it.copy(evRoutingPilot=it.evRoutingPilot.copy(pilotCapacityWh=""))}
                assertFalse(draft.commit {merge,_ -> prefs.updateCarPlaySettings(merge) {require(carPlayEditError(it)==null)}})
                assertEquals(previous,prefs.carPlaySettings.first())
                assertEquals("",draft.preview(previous).evRoutingPilot.pilotCapacityWh)
            }
            open(dir) {prefs -> assertEquals(OalCarPlayEvPilotTest.configuration(),prefs.carPlaySettings.first().evRoutingPilot)}
        } finally {dir.deleteRecursively()}
    }
    private suspend fun open(dir: File, block: suspend (AppPreferences)->Unit) {
        val job=SupervisorJob()
        val store=PreferenceDataStoreFactory.create(scope=CoroutineScope(Dispatchers.IO+job),produceFile={File(dir,"settings.preferences_pb")})
        val constructor=AppPreferences::class.java.getDeclaredConstructor(DataStore::class.java).apply {isAccessible=true}
        try {block(constructor.newInstance(store))} finally {job.cancelAndJoin()}
    }
}
