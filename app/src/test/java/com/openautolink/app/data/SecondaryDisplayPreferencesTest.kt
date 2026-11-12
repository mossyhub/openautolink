package com.openautolink.app.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import com.openautolink.app.secondary.*
import com.openautolink.app.cluster.*
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files

class SecondaryDisplayPreferencesTest {
    private fun preferences(store: DataStore<Preferences>): AppPreferences = AppPreferences::class.java
        .getDeclaredConstructor(DataStore::class.java).apply { isAccessible = true }.newInstance(store)

    private suspend fun realStore(test: suspend (DataStore<Preferences>) -> Unit) {
        val root = Files.createTempDirectory(File("build/tmp").apply { mkdirs() }.toPath(), "secondary-atomic-").toFile()
        val job = SupervisorJob()
        val store = PreferenceDataStoreFactory.create(scope = CoroutineScope(Dispatchers.IO + job),
            produceFile = { File(root, "settings.preferences_pb") })
        try { test(store) } finally { job.cancelAndJoin(); root.deleteRecursively() }
    }
    private suspend inline fun <reified T: Throwable> fails(crossinline block: suspend () -> Unit) {
        try { block(); fail("Expected ${T::class.java.name}") }
        catch (error: Throwable) { if (error !is T) throw error }
    }

    @Test fun concurrentExpectedRevisionAllowsExactlyOneCommitAndLaterMergePreservesBoth() = runBlocking {
        realStore { store ->
            val prefs = preferences(store)
            val a = SecondaryOutputId("a"); val b = SecondaryOutputId("b")
            val attempts = coroutineScope { listOf(a, b).map { id -> async(Dispatchers.Default) {
                try { prefs.updateSecondaryDisplayPreferences(0, mapOf(id to SecondaryOutputPreference(enabled = true))); true }
                catch (_: ConcurrentModificationException) { false }
            } }.awaitAll() }
            assertEquals(1, attempts.count { it })
            val current = prefs.getSecondaryDisplayPreferences()
            assertEquals(1L, current.revision); assertEquals(1, current.outputs.size)
            val missing = if (a in current.outputs) b else a
            val merged = prefs.updateSecondaryDisplayPreferences(1, mapOf(missing to SecondaryOutputPreference()))
            assertEquals(setOf(a, b), merged.outputs.keys); assertEquals(2L, merged.revision)
            assertEquals(merged, prefs.updateSecondaryDisplayPreferences(2, emptyMap()))
            fails<ConcurrentModificationException> { prefs.updateSecondaryDisplayPreferences(1, emptyMap()) }
            assertEquals(merged, prefs.getSecondaryDisplayPreferences())
        }
    }

    @Test fun migrationIsExplicitAtomicIdempotentAndDisabledEntryWinsWithoutChangingLegacyKeys() = runBlocking {
        realStore { store ->
            val prefs = preferences(store)
            prefs.setClusterOutputSelection(ClusterOutputSelection(ClusterDisplayMode.MAP_VIDEO, false, true, true))
            prefs.setClusterOutputEnabled(false)
            store.edit {
                it[AppPreferences.DISTANCE_UNITS] = "imperial"
                it[stringPreferencesKey("cluster_layout_v1_vehicle")] = "{unchanged bytes}"
                it[AppPreferences.CUSTOM_VIEWPORT_WIDTH] = 1234
            }
            val before = store.data.first().asMap()
            val gm = SecondaryOutputId("qualified-gm")
            assertTrue(prefs.getSecondaryDisplayPreferences().outputs.isEmpty())
            val migrated = prefs.migrateSecondaryClusterPreference(gm)
            assertEquals(SecondaryOutputPreference(false, AaSecondaryRole.AUXILIARY, false, true), migrated.outputs[gm])
            assertEquals(ClusterDisplayMode.OFF, migrated.legacyCluster.selection.mode)
            assertEquals(ClusterDisplayMode.MAP_VIDEO, migrated.legacyCluster.lastEnabledClusterMode)
            assertEquals(migrated, prefs.migrateSecondaryClusterPreference(gm))
            assertEquals(before, store.data.first().asMap().filterKeys { it != AppPreferences.SECONDARY_DISPLAY_OUTPUTS && it.name != "secondary_aa_calibrated_defaults_v1" })
            prefs.setClusterOutputEnabled(true)
            assertEquals(ClusterDisplayMode.MAP_VIDEO, prefs.getClusterOutputSelection().mode)
            val explicit = prefs.updateSecondaryDisplayPreferences(1, mapOf(gm to SecondaryOutputPreference()))
            assertEquals(explicit, prefs.migrateSecondaryClusterPreference(gm))
            assertFalse(prefs.getSecondaryDisplayPreferences().outputs.getValue(gm).enabled)
            // Legacy writers remain independent and never replace the secondary record.
            val raw = store.data.first()[AppPreferences.SECONDARY_DISPLAY_OUTPUTS]
            prefs.setClusterNavigation(false)
            prefs.updateClusterOutputSelection { it.copy(carPlay = false) }
            assertEquals(raw, store.data.first()[AppPreferences.SECONDARY_DISPLAY_OUTPUTS])
        }
    }

    @Test fun gmDefaultNeverRewritesExplicitClusterRoleLayoutOrNativeIntent() = runBlocking {
        realStore { store ->
            val prefs = preferences(store)
            val selection = ClusterOutputSelection(ClusterDisplayMode.MAP_VIDEO, nativeIconsWithMap = true, directVideoWindow = true)
            prefs.setClusterOutputSelection(selection)
            val id = SecondaryOutputId("qualified-gm-explicit")
            val layout = SecondaryLayoutPreference(SecondaryLayoutScope(id, "vehicle", "exact-build", 2000, 810, 160),
                SecondaryLayoutBox(0.0, 0.0, 1.0, 1.0), SecondaryLayoutBox(.2, .2, .8, .8),
                directSafe = SecondaryLayoutBox(.3, .25, .7, .75))
            val explicit = SecondaryOutputPreference(true, AaSecondaryRole.CLUSTER, true, true, layout)
            val saved = prefs.updateSecondaryDisplayPreferences(0, mapOf(id to explicit))
            assertEquals(saved, prefs.migrateSecondaryClusterPreference(id))
            assertEquals(explicit, prefs.getSecondaryDisplayPreferences().outputs[id])
            assertEquals(selection, prefs.getClusterOutputSelection())
        }
    }

    @Test fun migrationCopiesEachLegacyModeAndProtocolOptOutButNeverInventsLayout() = runBlocking {
        realStore { store ->
            val prefs = preferences(store)
            for (mode in ClusterDisplayMode.entries) {
                val selection = ClusterOutputSelection(mode, androidAuto = false, carPlay = false, nativeIconsWithMap = true)
                prefs.setClusterOutputSelection(selection)
                val id = SecondaryOutputId("exact-gm-${mode.name}")
                val migrated = prefs.migrateSecondaryClusterPreference(id)
                assertEquals(SecondaryOutputPreference(mode != ClusterDisplayMode.OFF, AaSecondaryRole.AUXILIARY, false, false), migrated.outputs[id])
                assertEquals(selection, migrated.legacyCluster.selection)
            }
        }
    }

    @Test fun strictSchemaInvalidEnumsBoundsAndUnsupportedVersionsAreWithheldAndNeverOverwritten() = runBlocking {
        realStore { store ->
            val prefs = preferences(store)
            prefs.setClusterOutputSelection(ClusterOutputSelection(ClusterDisplayMode.OFF, false, false, true))
            val valid = """{"version":1,"revision":1,"outputs":{"raw":{"enabled":false,"aaRole":null,"androidAuto":false,"carPlay":false}}}"""
            val bad = listOf("", "[]", "{}", "null", valid.replace("\"version\":1", "\"version\":2"),
                valid.replace("\"revision\":1", "\"revision\":-1"), valid.replace("\"revision\":1", "\"revision\":1.0"),
                valid.replace("\"revision\":1", "\"revision\":9223372036854775808"),
                valid.replace("\"aaRole\":null", "\"aaRole\":\"AUX\""),
                valid.replace("\"enabled\":false", "\"enabled\":\"false\""),
                valid.replace("\"raw\"", "\" \""), valid.replace("\"raw\"", "\"${"x".repeat(513)}\""),
                valid.replace("\"version\":1", "\"version\":1,\"version\":1"),
                valid.replace("\"version\":1", "\"version\":1,\"\\u0076ersion\":1"),
                valid.replace("\"version\":1", "\"version\":1,\"future\":true"),
                valid.replace("\"aaRole\":null,", ""),
                " ".repeat(65537), "[".repeat(1000) + "]".repeat(1000))
            for (raw in bad) {
                store.edit { it[AppPreferences.SECONDARY_DISPLAY_OUTPUTS] = raw }
                val snapshot = prefs.getSecondaryDisplayPreferences()
                assertNotNull("Must withhold: $raw", snapshot.error)
                assertTrue(snapshot.outputs.isEmpty()); assertEquals(ClusterDisplayMode.OFF, snapshot.legacyCluster.selection.mode)
                fails<IllegalStateException> { prefs.updateSecondaryDisplayPreferences(0, mapOf(SecondaryOutputId("a") to SecondaryOutputPreference())) }
                fails<IllegalStateException> { prefs.migrateSecondaryClusterPreference(SecondaryOutputId("gm")) }
                assertEquals(raw, store.data.first()[AppPreferences.SECONDARY_DISPLAY_OUTPUTS])
            }
            store.edit { it[AppPreferences.SECONDARY_DISPLAY_OUTPUTS] = valid }
            assertNull(prefs.getSecondaryDisplayPreferences().error)
        }
    }

    @Test fun identityCountAndUtf8ByteCapsRejectWholeMergeAndRevisionOverflowCannotWrap() = runBlocking {
        realStore { store ->
            val prefs = preferences(store)
            val entries = (1..64).associate { SecondaryOutputId("id-$it") to SecondaryOutputPreference() }
            val saved = prefs.updateSecondaryDisplayPreferences(0, entries)
            fails<IllegalArgumentException> { prefs.updateSecondaryDisplayPreferences(1, mapOf(SecondaryOutputId("extra") to SecondaryOutputPreference())) }
            assertEquals(saved, prefs.getSecondaryDisplayPreferences())
            store.edit { it.remove(AppPreferences.SECONDARY_DISPLAY_OUTPUTS) }
            // 512 characters each but multibyte serialized keys exceed 64 KiB.
            val huge = (1..64).associate { SecondaryOutputId("界".repeat(500) + it) to SecondaryOutputPreference() }
            fails<IllegalArgumentException> { prefs.updateSecondaryDisplayPreferences(0, huge) }
            assertTrue(prefs.getSecondaryDisplayPreferences().outputs.isEmpty())
            store.edit { it[AppPreferences.SECONDARY_DISPLAY_OUTPUTS] = """{"version":1,"revision":9223372036854775807,"outputs":{}}""" }
            fails<IllegalStateException> { prefs.updateSecondaryDisplayPreferences(Long.MAX_VALUE, entries) }
            fails<IllegalStateException> { prefs.migrateSecondaryClusterPreference(SecondaryOutputId("gm")) }
            assertEquals(Long.MAX_VALUE, prefs.getSecondaryDisplayPreferences().revision)
        }
    }

    @Test fun layoutRoundTripsAndAutomaticResetAreAtomicAndWrongIdentityIsRejected() = runBlocking {
        realStore { store ->
            val prefs = preferences(store); val id = SecondaryOutputId("public")
            val box = SecondaryLayoutBox(0.0, 0.0, 1.0, 1.0)
            val layout = SecondaryLayoutPreference(SecondaryLayoutScope(id, "vehicle", "exact-build", 1920, 1080, 240),
                box, SecondaryLayoutBox(.1, .2, .8, .9))
            val intent = SecondaryOutputPreference(true, AaSecondaryRole.AUXILIARY, true, true, layout)
            val saved = prefs.updateSecondaryDisplayPreferences(0, mapOf(id to intent))
            assertEquals(layout, prefs.getSecondaryDisplayPreferences().outputs.getValue(id).layout)
            fails<IllegalArgumentException> { prefs.updateSecondaryDisplayPreferences(1,
                mapOf(SecondaryOutputId("other") to intent)) }
            assertEquals(saved, prefs.getSecondaryDisplayPreferences())
            val automatic = prefs.updateSecondaryDisplayPreferences(1, mapOf(id to intent.copy(layout = null)))
            assertNull(automatic.outputs.getValue(id).layout)
            assertEquals(intent.copy(layout = null), automatic.outputs.getValue(id))
            // Stored invalid layout values are withheld, not clamped or stretched.
            val raw = SecondaryDisplayPreferences.encode(saved)
            for (invalid in listOf(raw.replace("\"left\":0.0", "\"left\":-0.1"),
                raw.replace("\"panelWidth\":1920", "\"panelWidth\":0"),
                raw.replace("\"outputId\":\"public\"", "\"outputId\":\"other\""),
                raw.replace("\"left\":0.1", "\"left\":\"0.1\""),
                raw.replace("\"version\":1,\"scope\"", "\"version\":2,\"scope\""))) {
                store.edit { it[AppPreferences.SECONDARY_DISPLAY_OUTPUTS] = invalid }
                assertNotNull(prefs.getSecondaryDisplayPreferences().error)
            }
        }
    }

    @Test fun ioFailureAndCancelledTransactionNeverPublishPartialEditOrRevision() = runBlocking {
        realStore { store ->
            val initial = preferences(store).updateSecondaryDisplayPreferences(0,
                mapOf(SecondaryOutputId("a") to SecondaryOutputPreference()))
            val failure = object: DataStore<Preferences> {
                override val data = store.data
                override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences =
                    store.updateData { transform(it); throw IOException("interrupted before commit") }
            }
            fails<IOException> { preferences(failure).updateSecondaryDisplayPreferences(1,
                mapOf(SecondaryOutputId("b") to SecondaryOutputPreference(enabled = true))) }
            assertEquals(initial, preferences(store).getSecondaryDisplayPreferences())
            val entered = CompletableDeferred<Unit>()
            val blocking = object: DataStore<Preferences> {
                override val data = store.data
                override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences =
                    store.updateData { transform(it).also { entered.complete(Unit); awaitCancellation() } }
            }
            val writing = launch { preferences(blocking).updateSecondaryDisplayPreferences(1,
                mapOf(SecondaryOutputId("b") to SecondaryOutputPreference(enabled = true))) }
            withTimeout(5000) { entered.await() }; writing.cancelAndJoin()
            assertEquals(initial, preferences(store).getSecondaryDisplayPreferences())
        }
    }

    @Test fun failedRealDiskWritePropagatesWithoutChangingDurableRecord() = runBlocking {
        val root = Files.createTempDirectory(File("build/tmp").apply { mkdirs() }.toPath(), "secondary-disk-failure-").toFile()
        val file = File(root, "settings.preferences_pb")
        var job = SupervisorJob()
        fun open() = PreferenceDataStoreFactory.create(scope = CoroutineScope(Dispatchers.IO + job), produceFile = { file })
        try {
            var prefs = preferences(open())
            val saved = prefs.updateSecondaryDisplayPreferences(0,
                mapOf(SecondaryOutputId("a") to SecondaryOutputPreference()))
            assertTrue(root.setWritable(false, false))
            try {
                fails<IOException> { prefs.updateSecondaryDisplayPreferences(1,
                    mapOf(SecondaryOutputId("b") to SecondaryOutputPreference(enabled = true))) }
            } finally { assertTrue(root.setWritable(true, true)) }
            job.cancelAndJoin(); job = SupervisorJob()
            prefs = preferences(open())
            assertEquals(saved, prefs.getSecondaryDisplayPreferences())
        } finally { root.setWritable(true, true); job.cancelAndJoin(); root.deleteRecursively() }
    }

    @Test fun snapshotUsesSameLegacyStateAndCommitReturnIsNotARacySecondRead() = runBlocking {
        realStore { store ->
            val wrapper = object: DataStore<Preferences> {
                override val data: Flow<Preferences> = store.data
                override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences {
                    val own = store.updateData(transform)
                    store.edit { it[AppPreferences.CLUSTER_DISPLAY_MODE] = "OFF" }
                    return own
                }
            }
            val own = preferences(wrapper).updateSecondaryDisplayPreferences(0,
                mapOf(SecondaryOutputId("a") to SecondaryOutputPreference()))
            assertEquals(ClusterDisplayMode.NATIVE_ICONS, own.legacyCluster.selection.mode)
            assertEquals(ClusterDisplayMode.OFF, preferences(store).getSecondaryDisplayPreferences().legacyCluster.selection.mode)
        }
    }

    @Test fun canonicalKeysWithJsonPunctuationAndUnicodeRoundTripVerbatim() = runBlocking {
        realStore { store ->
            val prefs = preferences(store)
            val ids = listOf(" raw:\"{[\\\\ key Ω ", "line\n\tkey", "界".repeat(512)).map(::SecondaryOutputId)
            val edits = ids.associateWith { SecondaryOutputPreference() }
            prefs.updateSecondaryDisplayPreferences(0, edits)
            assertEquals(edits, prefs.getSecondaryDisplayPreferences().outputs)
        }
    }

    @Test fun nonStringStoredRecordIsWithheldWithoutLosingLegacyStateOrRepairingIt() = runBlocking {
        realStore { store ->
            store.edit {
                it[intPreferencesKey("secondary_display_outputs_v1")] = 17
                it[AppPreferences.CLUSTER_DISPLAY_MODE] = "OFF"
            }
            val prefs = preferences(store)
            val snapshot = prefs.getSecondaryDisplayPreferences()
            assertNotNull(snapshot.error)
            assertTrue(snapshot.outputs.isEmpty())
            assertEquals(ClusterDisplayMode.OFF, snapshot.legacyCluster.selection.mode)
            fails<IllegalStateException> { prefs.updateSecondaryDisplayPreferences(0,
                mapOf(SecondaryOutputId("a") to SecondaryOutputPreference())) }
            fails<IllegalStateException> { prefs.migrateSecondaryClusterPreference(SecondaryOutputId("gm")) }
            assertEquals(17, store.data.first()[intPreferencesKey("secondary_display_outputs_v1")])
        }
    }

    @Test fun malformedTypedAndPartialRecordsSurviveReopenWithoutChangingOrdinaryPreferences() = runBlocking {
        val root = Files.createTempDirectory(File("build/tmp").apply { mkdirs() }.toPath(), "secondary-malformed-").toFile()
        val file = File(root, "settings.preferences_pb")
        var job = SupervisorJob()
        fun open() = PreferenceDataStoreFactory.create(scope = CoroutineScope(Dispatchers.IO + job), produceFile = { file })
        try {
            var store = open()
            var prefs = preferences(store)
            prefs.setClusterOutputSelection(ClusterOutputSelection(ClusterDisplayMode.MAP_VIDEO, false, false, true))
            prefs.setClusterOutputEnabled(false)
            prefs.setDistanceUnits("imperial")
            val profile = ClusterDisplayProfiles.AEGEAN_ORANGE_239
            val layoutScope = ClusterLayoutScope(profile.vehicle, profile.id, profile.role, profile.panel)
            val customLayout = SavedClusterLayout(layoutScope, profile.panel.bounds, profile.output, profile.safe)
            prefs.saveClusterLayout(customLayout)
            store.edit { it[AppPreferences.CUSTOM_VIEWPORT_WIDTH] = 1234 }
            val legacy = prefs.getClusterOutputPreferences()
            val baseline = store.data.first().asMap()
            val name = AppPreferences.SECONDARY_DISPLAY_OUTPUTS.name
            for (kind in 0..8) {
                store.edit {
                    when (kind) {
                        0 -> it[intPreferencesKey(name)] = 17
                        1 -> it[booleanPreferencesKey(name)] = true
                        2 -> it[longPreferencesKey(name)] = 17L
                        3 -> it[floatPreferencesKey(name)] = 17f
                        4 -> it[doublePreferencesKey(name)] = 17.0
                        5 -> it[stringSetPreferencesKey(name)] = setOf("enabled")
                        6 -> it[AppPreferences.SECONDARY_DISPLAY_OUTPUTS] = """{"version":1,"revision":1,"outputs":{"a":{"enabled":true}}}"""
                        7 -> it[AppPreferences.SECONDARY_DISPLAY_OUTPUTS] = """{"version":1,"revision":1,"outputs":{"a":{"enabled":true,"aaRole":"AUXILIARY","androidAuto":true,"carPlay":false}},"""
                        8 -> it[AppPreferences.SECONDARY_DISPLAY_OUTPUTS] = "null"
                    }
                }
                val before = store.data.first().asMap()
                job.cancelAndJoin(); job = SupervisorJob(); store = open(); prefs = preferences(store)
                val snapshot = prefs.secondaryDisplayPreferences.first()
                assertNotNull("kind=$kind", snapshot.error)
                assertTrue("kind=$kind", snapshot.outputs.isEmpty())
                assertEquals(0L, snapshot.revision)
                assertEquals(legacy, snapshot.legacyCluster)
                assertEquals(ClusterDisplayMode.OFF, snapshot.legacyCluster.selection.mode)
                assertEquals(ClusterDisplayMode.MAP_VIDEO, snapshot.legacyCluster.lastEnabledClusterMode)
                assertFalse(snapshot.legacyCluster.selection.androidAuto)
                assertFalse(snapshot.legacyCluster.selection.carPlay)
                assertEquals("imperial", prefs.distanceUnits.first())
                assertEquals(customLayout, prefs.getClusterLayout(layoutScope, customLayout.canvas).custom)
                assertEquals(baseline, before.filterKeys { it.name != name })
                fails<IllegalStateException> { prefs.updateSecondaryDisplayPreferences(0,
                    mapOf(SecondaryOutputId("a") to SecondaryOutputPreference(enabled = true))) }
                fails<IllegalStateException> { prefs.migrateSecondaryClusterPreference(SecondaryOutputId("gm")) }
                assertEquals(before, store.data.first().asMap())
                // Ordinary settings still read/write independently; no automatic record repair.
                prefs.setDistanceUnits("metric")
                assertEquals("metric", prefs.distanceUnits.first())
                assertEquals(before.filterKeys { it != AppPreferences.DISTANCE_UNITS },
                    store.data.first().asMap().filterKeys { it != AppPreferences.DISTANCE_UNITS })
                prefs.setDistanceUnits("imperial")
            }
        } finally { job.cancelAndJoin(); root.deleteRecursively() }
    }

    @Test fun secondaryReadPropagatesStoreIoAndCancellationWithoutManufacturingSnapshot() = runBlocking {
        for (error in listOf(IOException("read failed"), CancellationException("read cancelled"))) {
            val store = object : DataStore<Preferences> {
                override val data: Flow<Preferences> = kotlinx.coroutines.flow.flow { throw error }
                override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences = error("No writes expected")
            }
            try {
                preferences(store).getSecondaryDisplayPreferences()
                fail("Expected original store failure")
            } catch (actual: Exception) { assertSame(error, actual) }
        }
    }

    @Test fun savesIndependentIntentAcrossRealDataStoreReopen() = runBlocking {
        val root = Files.createTempDirectory(File("build/tmp").apply { mkdirs() }.toPath(), "secondary-").toFile()
        val file = File(root, "settings.preferences_pb")
        var job = SupervisorJob()
        fun open() = PreferenceDataStoreFactory.create(scope = CoroutineScope(Dispatchers.IO + job), produceFile = { file })
        try {
            var prefs = preferences(open())
            val id = SecondaryOutputId(" canonical raw KEY ")
            val output = SecondaryOutputPreference(true, AaSecondaryRole.AUXILIARY, true, false,
                SecondaryLayoutPreference(SecondaryLayoutScope(id, "vehicle", "exact-build", 1280, 720, 160),
                    SecondaryLayoutBox(0.0, 0.0, 1.0, 1.0), SecondaryLayoutBox(.1, .1, .9, .9)))
            prefs.setClusterOutputSelection(ClusterOutputSelection(ClusterDisplayMode.MAP_VIDEO, false, true, true))
            prefs.setClusterOutputEnabled(false)
            prefs.setDistanceUnits("imperial")
            val initial = prefs.getSecondaryDisplayPreferences()
            assertEquals(0L, initial.revision)
            assertTrue(initial.outputs.isEmpty())
            assertNull(initial.error)
            val saved = prefs.updateSecondaryDisplayPreferences(0, mapOf(id to output))
            assertEquals(1L, saved.revision)
            assertEquals(mapOf(id to output), saved.outputs)
            assertEquals(initial.legacyCluster, saved.legacyCluster)
            val gm = SecondaryOutputId("exact-gm-only")
            val migrated = prefs.migrateSecondaryClusterPreference(gm)
            job.cancelAndJoin()
            job = SupervisorJob()
            prefs = preferences(open())
            assertEquals(migrated, prefs.secondaryDisplayPreferences.first())
            assertEquals(" canonical raw KEY ", saved.outputs.keys.single().value)
            assertEquals(output.layout, prefs.getSecondaryDisplayPreferences().outputs.getValue(id).layout)
            assertEquals(migrated, prefs.migrateSecondaryClusterPreference(gm))
            assertEquals(SecondaryOutputPreference(false, AaSecondaryRole.AUXILIARY, false, true), migrated.outputs[gm])
            assertEquals("imperial", prefs.distanceUnits.first())
            assertEquals(ClusterDisplayMode.OFF, migrated.legacyCluster.selection.mode)
            assertEquals(ClusterDisplayMode.MAP_VIDEO, prefs.setClusterOutputEnabled(true).selection.mode)
        } finally { job.cancelAndJoin(); root.deleteRecursively() }
    }
}
