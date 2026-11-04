package com.openautolink.app.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import com.openautolink.app.cluster.ClusterDisplayMode
import com.openautolink.app.cluster.ClusterOutputSelection
import kotlinx.coroutines.*
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.junit.Assert.*
import org.junit.Test

class ClusterOutputPreferencesTest {
    private class Store(initial: Preferences = emptyPreferences()) : DataStore<Preferences> {
        val state = MutableStateFlow(initial)
        var commits = 0
            private set
        private val mutex = Mutex()
        override val data: Flow<Preferences> = state
        override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences = mutex.withLock {
            transform(state.value).also { state.value = it; commits++ }
        }
    }

    private fun preferences(store: DataStore<Preferences>): AppPreferences = AppPreferences::class.java
        .getDeclaredConstructor(DataStore::class.java).apply { isAccessible = true }.newInstance(store)

    @Test fun legacyDisableMustChangeAuthoritativeMode() = runBlocking {
        val store = Store(preferencesOf(stringPreferencesKey("cluster_display_mode_v1") to "NATIVE_ICONS"))
        val prefs = preferences(store)
        prefs.setClusterNavigation(false)
        assertFalse(prefs.clusterNavigation.first())
    }

    @Test fun missingAndLegacyModesNeverOptIntoMapVideo() = runBlocking {
        for (legacy in listOf(null, false, true)) {
            val initial = mutablePreferencesOf(AppPreferences.DISTANCE_UNITS to "imperial")
            legacy?.let { initial[AppPreferences.CLUSTER_NAVIGATION] = it }
            val store = Store(initial.toPreferences())
            val before = store.state.value.asMap()
            val actual = preferences(store).getClusterOutputSelection()
            assertEquals(ClusterOutputSelection(
                if (legacy == false) ClusterDisplayMode.OFF else ClusterDisplayMode.NATIVE_ICONS,
            ), actual)
            assertEquals("reads must not reset saved choices", before, store.state.value.asMap())
        }
    }

    @Test fun invalidExplicitModesFailClosedWithoutResettingSavedOptions() = runBlocking {
        for (raw in listOf("", "map_video", " MAP_VIDEO", "MAP_VIDEO ", "FUTURE_MODE")) {
            val store = Store(preferencesOf(
                AppPreferences.CLUSTER_DISPLAY_MODE to raw,
                AppPreferences.CLUSTER_NAVIGATION to true,
                AppPreferences.CLUSTER_ANDROID_AUTO to false,
                AppPreferences.CLUSTER_CARPLAY to true,
                AppPreferences.CLUSTER_NATIVE_ICONS_WITH_MAP to true,
                AppPreferences.DISTANCE_UNITS to "metric",
            ))
            val prefs = preferences(store)
            assertEquals(ClusterOutputSelection(ClusterDisplayMode.OFF, false, true, true), prefs.getClusterOutputSelection())
            assertFalse(prefs.clusterNavigation.first())
            assertEquals(raw, store.state.value[AppPreferences.CLUSTER_DISPLAY_MODE])
            assertEquals("metric", prefs.distanceUnits.first())
        }
    }

    @Test fun fullSelectionMatrixRoundTripsAndWritesOneCoherentRecord() = runBlocking {
        for (mode in ClusterDisplayMode.entries) for (aa in listOf(false, true))
            for (cp in listOf(false, true)) for (nativeWithMap in listOf(false, true)) {
                val store = Store(preferencesOf(
                    AppPreferences.DISTANCE_UNITS to "imperial",
                    AppPreferences.SEND_IMU_SENSORS to false,
                    AppPreferences.CARPLAY_SETTINGS to "unmodified unrelated record",
                ))
                val prefs = preferences(store)
                val selection = ClusterOutputSelection(mode, aa, cp, nativeWithMap)
                assertEquals(selection, prefs.setClusterOutputSelection(selection))
                assertEquals("all fields and compatibility mirror must be one transaction", 1, store.commits)
                assertEquals(selection, preferences(store).getClusterOutputSelection())
                val values = store.state.value
                assertEquals(mode.name, values[AppPreferences.CLUSTER_DISPLAY_MODE])
                assertEquals(aa, values[AppPreferences.CLUSTER_ANDROID_AUTO])
                assertEquals(cp, values[AppPreferences.CLUSTER_CARPLAY])
                assertEquals(nativeWithMap, values[AppPreferences.CLUSTER_NATIVE_ICONS_WITH_MAP])
                val native = mode == ClusterDisplayMode.NATIVE_ICONS || (mode == ClusterDisplayMode.MAP_VIDEO && nativeWithMap)
                assertEquals(native, values[AppPreferences.CLUSTER_NAVIGATION])
                assertEquals(native, prefs.clusterNavigation.first())
                assertEquals("imperial", prefs.distanceUnits.first())
                assertFalse(prefs.sendImuSensors.first())
                assertEquals("unmodified unrelated record", values[AppPreferences.CARPLAY_SETTINGS])
            }
    }

    @Test fun legacyToggleAtomicallyChangesModeButPreservesProtocolAndCoexistChoices() = runBlocking {
        val store = Store()
        val prefs = preferences(store)
        prefs.setClusterOutputSelection(ClusterOutputSelection(ClusterDisplayMode.MAP_VIDEO, false, false, true))
        for (enabled in listOf(false, true, false)) {
            prefs.setClusterNavigation(enabled)
            assertEquals(ClusterOutputSelection(
                if (enabled) ClusterDisplayMode.NATIVE_ICONS else ClusterDisplayMode.OFF,
                false, false, true,
            ), prefs.getClusterOutputSelection())
            assertEquals(enabled, store.state.value[AppPreferences.CLUSTER_NAVIGATION])
        }
    }

    @Test fun concurrentFieldUpdatesMergeAgainstTransactionState() = runBlocking {
        val store = Store()
        val prefs = preferences(store)
        coroutineScope {
            listOf(
                async(Dispatchers.Default) { prefs.updateClusterOutputSelection { it.copy(androidAuto = false) } },
                async(Dispatchers.Default) { prefs.updateClusterOutputSelection { it.copy(carPlay = false) } },
                async(Dispatchers.Default) { prefs.updateClusterOutputSelection { it.copy(mode = ClusterDisplayMode.MAP_VIDEO) } },
                async(Dispatchers.Default) { prefs.updateClusterOutputSelection { it.copy(nativeIconsWithMap = true) } },
            ).awaitAll()
        }
        assertEquals(ClusterOutputSelection(ClusterDisplayMode.MAP_VIDEO, false, false, true), prefs.getClusterOutputSelection())
    }

    @Test fun failedTransformDoesNotPublishPartialSettings() = runBlocking {
        val store = Store()
        val prefs = preferences(store)
        val before = store.state.value
        try {
            prefs.updateClusterOutputSelection { error("deliberate failed edit") }
            fail("must propagate failed save")
        } catch (_: IllegalStateException) {
            assertEquals(before, store.state.value)
        }
    }

    @Test fun everySavedUnitChoiceSurvivesLegacyMigrationAndModeChanges() = runBlocking {
        for (units in listOf("auto", "metric", "imperial", "future-units")) for (legacy in listOf(false, true)) {
            val store = Store(preferencesOf(AppPreferences.DISTANCE_UNITS to units, AppPreferences.CLUSTER_NAVIGATION to legacy))
            val prefs = preferences(store)
            assertEquals(units, prefs.distanceUnits.first())
            prefs.setClusterOutputSelection(ClusterOutputSelection(ClusterDisplayMode.MAP_VIDEO, false, true, true))
            prefs.setClusterNavigation(false)
            assertEquals(units, preferences(store).distanceUnits.first())
        }
        assertEquals("auto", preferences(Store()).distanceUnits.first())
    }

    @Test fun setterReturnsItsCommittedSnapshotEvenWhenAnotherWriterWinsBeforeReturn() = runBlocking {
        val backing = Store()
        val interleaved = object : DataStore<Preferences> {
            override val data: Flow<Preferences> = backing.data
            override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences {
                val committed = backing.updateData(transform)
                backing.edit { it[AppPreferences.CLUSTER_DISPLAY_MODE] = "OFF" }
                return committed
            }
        }
        val prefs = preferences(interleaved)
        val selected = ClusterOutputSelection(ClusterDisplayMode.MAP_VIDEO, true, false, true)
        assertEquals(selected, prefs.setClusterOutputSelection(selected))
        assertEquals(selected.copy(mode = ClusterDisplayMode.OFF), prefs.getClusterOutputSelection())
    }

    @Test fun failedPersistencePropagatesWithoutClaimingCommittedIntent() = runBlocking {
        val store = object : DataStore<Preferences> {
            override val data: Flow<Preferences> = flowOf(emptyPreferences())
            override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences {
                transform(emptyPreferences())
                throw java.io.IOException("deliberate storage failure")
            }
        }
        val prefs = preferences(store)
        try {
            prefs.setClusterOutputSelection(ClusterOutputSelection(ClusterDisplayMode.MAP_VIDEO))
            fail("failed persistence must not return success")
        } catch (_: java.io.IOException) {
            assertEquals(ClusterOutputSelection(), prefs.getClusterOutputSelection())
        }
    }

    @Test fun realDataStoreRetainsSelectionAndUnrelatedSavedChoicesAfterReopen() = runBlocking {
        val root = Files.createTempDirectory(File("build/tmp").apply { mkdirs() }.toPath(), "cluster-output-").toFile()
        val file = File(root, "settings.preferences_pb")
        var job = SupervisorJob()
        fun open() = PreferenceDataStoreFactory.create(scope = CoroutineScope(Dispatchers.IO + job), produceFile = { file })
        try {
            var store = open()
            var prefs = preferences(store)
            prefs.setDistanceUnits("imperial")
            prefs.setSendImuSensors(false)
            prefs.setClusterNavigation(false)
            assertEquals(ClusterOutputSelection(ClusterDisplayMode.OFF), prefs.getClusterOutputSelection())
            val selected = ClusterOutputSelection(ClusterDisplayMode.MAP_VIDEO, false, true, true)
            assertEquals(selected, prefs.setClusterOutputSelection(selected))
            job.cancelAndJoin()
            job = SupervisorJob()
            store = open()
            prefs = preferences(store)
            assertEquals(selected, prefs.getClusterOutputSelection())
            assertEquals("imperial", prefs.distanceUnits.first())
            assertFalse(prefs.sendImuSensors.first())
        } finally {
            job.cancelAndJoin()
            root.deleteRecursively()
        }
    }

    @Test fun explicitOffCannotBeBypassedByLegacyStoredTrue() = runBlocking {
        val store = Store(preferencesOf(
            AppPreferences.CLUSTER_NAVIGATION to true,
            stringPreferencesKey("cluster_display_mode_v1") to "OFF",
        ))
        assertFalse(preferences(store).clusterNavigation.first())
    }
}
