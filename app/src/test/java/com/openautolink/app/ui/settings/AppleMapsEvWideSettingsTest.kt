package com.openautolink.app.ui.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.openautolink.app.data.AppPreferences
import com.openautolink.app.data.CarPlaySettings
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32, 34], application = android.app.Application::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
class AppleMapsEvWideSettingsTest {
    @Test fun unsetDefaultsOnButExplicitOffAndMalformedValuesRemainOff() {
        assertTrue(CarPlaySettings.decode(null).evDataEnabled)
        assertTrue(CarPlaySettings.decode("""{"ssid":"preserved"}""").evDataEnabled)
        for (value in listOf("false", "null", "1", "{}", "[]", "\"true\"", "\"false\"")) {
            val saved = CarPlaySettings.decode("""{"ssid":"preserved","evDataEnabled":$value}""")
            assertFalse(value, saved.evDataEnabled)
            assertEquals("preserved", saved.ssid)
            assertFalse("Fail-closed survives encode/reopen", CarPlaySettings.decode(CarPlaySettings.encode(saved)).evDataEnabled)
        }
    }

    @Test fun legacyCalibrationCannotSilentlySetTheWideEstimate() {
        val decoded = CarPlaySettings.decode("""{"evDataEnabled":true,"evRoutingPilot":{"enabled":true,"profileId":"gm-source-estimate","averageChargeKw":"91"}}""")
        assertEquals("91", decoded.evRoutingPilot.averageChargeKw)
        assertEquals(JsonPrimitive(""), savedAverage(decoded))
    }

    @Test fun optionalAverageCommitsWithExistingDraftAndResetSurvivesColdReopen() = runBlocking<Unit> {
        val dir = File("build/tmp/ev-wide-${UUID.randomUUID()}").apply { mkdirs() }
        try {
            open(dir) { prefs ->
                prefs.updateCarPlaySettings({ it.copy(ssid = "initial", fps = 47) })
                val pending = CarPlaySettings.decode("""{"evDataEnabled":true,"evAverageDcChargeKw":"73.125"}""")
                val draft = CarPlaySettingsDraft()
                // Use the decoded optional field through the existing saved record, not a future constructor.
                draft.update { latest -> mergeEstimate(latest, pending) }
                assertEquals(JsonPrimitive(""), savedAverage(prefs.carPlaySettings.first()))
                prefs.updateCarPlaySettings({ it.copy(ssid = "concurrent") })
                assertTrue(draft.commit { merge, _ -> prefs.updateCarPlaySettings(merge) { require(carPlayEditError(it) == null) } })
            }
            open(dir) { prefs ->
                val saved = prefs.carPlaySettings.first()
                assertTrue(saved.evDataEnabled)
                assertEquals(JsonPrimitive("73.125"), savedAverage(saved))
                assertEquals("concurrent", saved.ssid)
                assertEquals(47, saved.fps)
                val draft = CarPlaySettingsDraft()
                draft.update { mergeEstimate(it, CarPlaySettings.decode("""{"evAverageDcChargeKw":""}""")) }
                assertTrue(draft.commit { merge, _ -> prefs.updateCarPlaySettings(merge) { require(carPlayEditError(it) == null) } })
            }
            open(dir) { prefs ->
                val saved = prefs.carPlaySettings.first()
                assertTrue(saved.evDataEnabled)
                assertEquals("concurrent", saved.ssid)
                assertEquals(JsonPrimitive(""), savedAverage(saved))
            }
        } finally { dir.deleteRecursively() }
    }

    private fun savedAverage(settings: CarPlaySettings) =
        Json.parseToJsonElement(CarPlaySettings.encode(settings)).jsonObject["evAverageDcChargeKw"]

    private fun mergeEstimate(latest: CarPlaySettings, edited: CarPlaySettings): CarPlaySettings {
        val kept = Json.parseToJsonElement(CarPlaySettings.encode(latest)).jsonObject
        return CarPlaySettings.decode(kotlinx.serialization.json.JsonObject(
            kept + ("evAverageDcChargeKw" to (savedAverage(edited) ?: JsonPrimitive(""))),
        ).toString())
    }

    private suspend fun open(dir: File, block: suspend (AppPreferences) -> Unit) {
        val job = SupervisorJob()
        val store = PreferenceDataStoreFactory.create(scope = CoroutineScope(Dispatchers.IO + job),
            produceFile = { File(dir, "settings.preferences_pb") })
        val constructor = AppPreferences::class.java.getDeclaredConstructor(DataStore::class.java).apply { isAccessible = true }
        try { block(constructor.newInstance(store)) } finally { job.cancelAndJoin() }
    }
}
