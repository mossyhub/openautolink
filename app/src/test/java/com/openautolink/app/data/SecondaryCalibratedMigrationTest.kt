package com.openautolink.app.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import com.openautolink.app.secondary.*
import com.openautolink.app.cluster.*
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
class SecondaryCalibratedMigrationTest {
    private val id = SecondaryOutputId("synthetic-output")
    private val descriptor = SecondaryDisplayDescriptor(id, 17, 1, "Synthetic", 2000, 810, 220,
        SecondaryRoute.GM_HOSTED, SecondaryReason.NONE, "Synthetic")
    private val scope = SecondaryLayoutScope(id, "synthetic-product", "synthetic-build", 2000, 810, 220)
    private val layout = SecondaryLayoutPreference(scope, SecondaryLayoutBox(0.0,0.0,1.0,1.0),
        SecondaryLayoutBox(.3,.2,.7,.7), directSafe = SecondaryLayoutBox(.32,.23,.68,.67))
    private val legacy = ClusterOutputPreferences(ClusterOutputSelection(directVideoWindow = true), ClusterDisplayMode.MAP_VIDEO)
    private fun prefs(store: DataStore<Preferences>) = AppPreferences::class.java.getDeclaredConstructor(DataStore::class.java)
        .apply { isAccessible = true }.newInstance(store)
    private fun target(value: SecondaryLayoutPreference?, cp: Boolean = false) = SecondaryRuntimeLayouts.target(descriptor,
        value, PixelSize(2000,810), PixelRect(558,70,1694,668), scope.vehicle, scope.buildFingerprint, carPlay = cp, safeOnly = true)
    private suspend fun fixture(block: suspend (DataStore<Preferences>, suspend () -> DataStore<Preferences>) -> Unit) {
        val root = Files.createTempDirectory(File("build/tmp").apply { mkdirs() }.toPath(), "calibration-").toFile()
        val file = File(root,"settings.preferences_pb")
        var job = SupervisorJob()
        fun open() = PreferenceDataStoreFactory.create(scope = CoroutineScope(Dispatchers.IO + job), produceFile = { file })
        var store = open()
        try { block(store) { job.cancelAndJoin(); job = SupervisorJob(); store = open(); store } }
        finally { job.cancelAndJoin(); root.deleteRecursively() }
    }
    @Test fun firstReadAtomicallyMigratesOnceAndLateExplicitEditsSurviveRealReopen() = runBlocking {
        fixture { data, reopen ->
            val original = SecondaryOutputPreference(false, AaSecondaryRole.CLUSTER, true, false, layout, 201)
            data.edit { it[AppPreferences.SECONDARY_DISPLAY_OUTPUTS] = SecondaryDisplayPreferences.encode(
                SecondaryPreferencesSnapshot(7, mapOf(id to original), legacy)); it[AppPreferences.AA_DPI] = 177 }
            val p = prefs(data)
            val concurrent = coroutineScope { (1..12).map { async(Dispatchers.IO) { p.getSecondaryDisplayPreferences() } }.awaitAll() }
            val migrated = concurrent.first()
            assertTrue(concurrent.all { it == migrated })
            assertEquals(8L, migrated.revision)
            val output = migrated.outputs.getValue(id)
            assertEquals(149, output.aaDensityDpi)
            assertEquals(original.enabled, output.enabled); assertEquals(original.aaRole, output.aaRole)
            assertEquals(original.androidAuto, output.androidAuto); assertEquals(original.carPlay, output.carPlay)
            assertEquals(layout.output, output.layout!!.output); assertEquals(layout.safe, output.layout!!.safe)
            assertEquals(target(layout,true), target(output.layout,true))
            assertEquals(PixelRect(192,71,1788,660), target(output.layout).safe)
            assertEquals(PixelRect(0,0,2000,810), target(output.layout).output)
            try { p.updateSecondaryDisplayPreferences(7, mapOf(id to original)); fail("stale pre-migration Save accepted") }
            catch (_: ConcurrentModificationException) { }
            val edited = output.copy(aaDensityDpi = 180, layout = null)
            val saved = p.updateSecondaryDisplayPreferences(migrated.revision, mapOf(id to edited))
            val cold = prefs(reopen()).getSecondaryDisplayPreferences()
            assertEquals(saved,cold); assertEquals(180,cold.outputs.getValue(id).aaDensityDpi)
            assertEquals(177, prefsMainDpi(reopen()))
            assertEquals(cold,prefs(reopen()).getSecondaryDisplayPreferences())
        }
    }
    private suspend fun prefsMainDpi(store: DataStore<Preferences>) = store.data.first()[AppPreferences.AA_DPI]
    @Test fun malformedBytesAreRetainedAndDoNotConsumeMigration() = runBlocking {
        fixture { data, _ ->
            val raw = "{invalid retained"
            data.edit { it[AppPreferences.SECONDARY_DISPLAY_OUTPUTS] = raw }
            assertNotNull(prefs(data).getSecondaryDisplayPreferences().error)
            assertEquals(raw,data.data.first()[AppPreferences.SECONDARY_DISPLAY_OUTPUTS])
            data.edit { it[AppPreferences.SECONDARY_DISPLAY_OUTPUTS] = SecondaryDisplayPreferences.encode(
                SecondaryPreferencesSnapshot(3,mapOf(id to SecondaryOutputPreference(aaDensityDpi=200)),legacy)) }
            val repaired = prefs(data).getSecondaryDisplayPreferences()
            assertEquals(4L,repaired.revision); assertEquals(149,repaired.outputs.getValue(id).aaDensityDpi)
        }
    }
    @Test fun freshStoreConsumesMigrationBeforeFirstExplicitSave() = runBlocking {
        fixture { data, reopen ->
            val p = prefs(data); val empty = p.getSecondaryDisplayPreferences()
            assertTrue(empty.outputs.isEmpty()); assertEquals(0L,empty.revision)
            val saved = p.updateSecondaryDisplayPreferences(empty.revision,mapOf(id to SecondaryOutputPreference(aaDensityDpi=190)))
            assertEquals(saved,prefs(reopen()).getSecondaryDisplayPreferences())
        }
    }
    @Test fun revisionExhaustionRetainsRecordAndDoesNotConsumeMigration() = runBlocking {
        fixture { data, _ ->
            val original = SecondaryPreferencesSnapshot(Long.MAX_VALUE,mapOf(id to SecondaryOutputPreference(aaDensityDpi=200)),legacy)
            val raw = SecondaryDisplayPreferences.encode(original)
            data.edit { it[AppPreferences.SECONDARY_DISPLAY_OUTPUTS] = raw }
            try { prefs(data).getSecondaryDisplayPreferences(); fail("exhaustion accepted") } catch (_: IllegalStateException) { }
            assertEquals(raw,data.data.first()[AppPreferences.SECONDARY_DISPLAY_OUTPUTS])
            data.edit { it[AppPreferences.SECONDARY_DISPLAY_OUTPUTS] = SecondaryDisplayPreferences.encode(original.copy(revision=1)) }
            assertEquals(149,prefs(data).getSecondaryDisplayPreferences().outputs.getValue(id).aaDensityDpi)
        }
    }
}
