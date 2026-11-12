package com.openautolink.app.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import com.openautolink.app.secondary.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import java.io.File
import java.nio.file.Files

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32, 34], application = android.app.Application::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
class SecondaryDensityPersistenceTest {
    private fun preferences(store: DataStore<Preferences>): AppPreferences = AppPreferences::class.java
        .getDeclaredConstructor(DataStore::class.java).apply { isAccessible = true }.newInstance(store)

    @Test fun customDensityAndAutomaticResetSurviveColdReopenWithoutChangingOtherSettings() = runBlocking {
        val root = Files.createTempDirectory(File("build/tmp").apply { mkdirs() }.toPath(), "secondary-density-").toFile()
        val file = File(root, "density.preferences_pb")
        var job = SupervisorJob()
        fun store() = PreferenceDataStoreFactory.create(scope = CoroutineScope(Dispatchers.IO + job), produceFile = { file })
        var data = store()
        val a = SecondaryOutputId("density-a")
        val b = SecondaryOutputId("density-b")
        val custom = SecondaryOutputPreference(true, AaSecondaryRole.AUXILIARY, aaDensityDpi = 140)
        val other = SecondaryOutputPreference(false, AaSecondaryRole.CLUSTER)
        try {
            data.edit { it[AppPreferences.DISTANCE_UNITS] = "imperial"; it[AppPreferences.CUSTOM_VIEWPORT_WIDTH] = 1234 }
            val unrelated = data.data.first().asMap()
            val prefs = preferences(data)
            assertTrue(prefs.getSecondaryDisplayPreferences().outputs.isEmpty())
            val saved = prefs.updateSecondaryDisplayPreferences(0, mapOf(a to custom, b to other))
            assertEquals(1L, saved.revision)
            job.cancelAndJoin()
            job = SupervisorJob(); data = store()
            val reopened = preferences(data)
            val cold = reopened.getSecondaryDisplayPreferences()
            assertEquals(saved, cold)
            assertEquals(140, cold.outputs.getValue(a).aaDensityDpi)
            assertEquals(other, cold.outputs.getValue(b))
            assertEquals(unrelated, data.data.first().asMap().filterKeys { it != AppPreferences.SECONDARY_DISPLAY_OUTPUTS && it.name != "secondary_aa_calibrated_defaults_v1" })
            val reset = reopened.updateSecondaryDisplayPreferences(cold.revision,
                mapOf(a to cold.outputs.getValue(a).copy(aaDensityDpi = null)))
            assertEquals(custom.copy(aaDensityDpi = null), reset.outputs.getValue(a))
            assertEquals(other, reset.outputs.getValue(b))
            job.cancelAndJoin()
            job = SupervisorJob(); data = store()
            val resetCold = preferences(data).getSecondaryDisplayPreferences()
            assertEquals(reset, resetCold)
            assertNull(resetCold.outputs.getValue(a).aaDensityDpi)
            assertFalse(requireNotNull(data.data.first()[AppPreferences.SECONDARY_DISPLAY_OUTPUTS]).contains("aaDensityDpi"))
            assertEquals(unrelated, data.data.first().asMap().filterKeys { it != AppPreferences.SECONDARY_DISPLAY_OUTPUTS && it.name != "secondary_aa_calibrated_defaults_v1" })
        } finally { job.cancelAndJoin(); root.deleteRecursively() }
    }
}
