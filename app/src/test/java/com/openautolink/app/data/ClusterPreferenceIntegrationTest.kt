package com.openautolink.app.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import com.openautolink.app.cluster.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files

class ClusterPreferenceIntegrationTest {
    private fun preferences(store: DataStore<Preferences>): AppPreferences = AppPreferences::class.java
        .getDeclaredConstructor(DataStore::class.java).apply { isAccessible = true }.newInstance(store)

    private class Store(initial: Preferences = emptyPreferences()) : DataStore<Preferences> {
        val state = kotlinx.coroutines.flow.MutableStateFlow(initial)
        private val mutex = kotlinx.coroutines.sync.Mutex()
        override val data: kotlinx.coroutines.flow.Flow<Preferences> = state
        override suspend fun updateData(transform: suspend (Preferences)->Preferences): Preferences {
            mutex.lock()
            try { return transform(state.value).also { state.value=it } } finally { mutex.unlock() }
        }
    }

    @Test fun stagedMapThenOffBeforeSaveCanPersistBothWithoutOverwritingProtocolFields() = runBlocking {
        val store=Store(preferencesOf(AppPreferences.CLUSTER_ANDROID_AUTO to false,AppPreferences.CLUSTER_CARPLAY to false))
        val prefs=preferences(store)
        val committed=prefs.updateClusterOutputState { current ->
            current.copy(selection=current.selection.copy(mode=ClusterDisplayMode.OFF),lastEnabledClusterMode=ClusterDisplayMode.MAP_VIDEO)
        }
        assertEquals(ClusterDisplayMode.OFF,committed.selection.mode)
        assertEquals(ClusterDisplayMode.MAP_VIDEO,committed.lastEnabledClusterMode)
        assertEquals(ClusterOutputSelection(ClusterDisplayMode.MAP_VIDEO,false,false,false),preferences(store).setClusterOutputEnabled(true).selection)
    }

    @Test fun legacyFalseAndInvalidModesCannotInventMapConsent() = runBlocking {
        for (raw in listOf(null,"OFF","bad","map_video")) {
            val initial = mutablePreferencesOf(AppPreferences.CLUSTER_NAVIGATION to false)
            if(raw!=null) initial[AppPreferences.CLUSTER_DISPLAY_MODE]=raw
            val prefs=preferences(Store(initial))
            val saved=prefs.getClusterOutputPreferences()
            assertEquals(ClusterDisplayMode.OFF,saved.selection.mode)
            assertNotEquals(ClusterDisplayMode.MAP_VIDEO,saved.lastEnabledClusterMode)
            val enabled=prefs.setClusterOutputEnabled(true)
            assertEquals(if(raw==null || raw=="OFF") ClusterDisplayMode.NATIVE_ICONS else ClusterDisplayMode.OFF,enabled.selection.mode)
        }
        for (raw in listOf("OFF","bad","map_video"," MAP_VIDEO")) {
            val prefs=preferences(Store(preferencesOf(AppPreferences.CLUSTER_DISPLAY_MODE to "OFF",AppPreferences.CLUSTER_LAST_ENABLED_MODE to raw)))
            assertNull(prefs.getClusterOutputPreferences().lastEnabledClusterMode)
            assertEquals(ClusterDisplayMode.OFF,prefs.setClusterOutputEnabled(true).selection.mode)
        }
    }

    @Test fun nativeChoiceReplacesPreviousMapAndConcurrentEditsMergeWithRetainedMode() = runBlocking {
        val store=Store(); val prefs=preferences(store)
        prefs.setClusterOutputSelection(ClusterOutputSelection(ClusterDisplayMode.MAP_VIDEO))
        coroutineScope {
            listOf(async(Dispatchers.Default) { prefs.setClusterOutputEnabled(false) },
                async(Dispatchers.Default) { prefs.updateClusterOutputSelection { it.copy(androidAuto=false) } },
                async(Dispatchers.Default) { prefs.updateClusterOutputSelection { it.copy(carPlay=false,nativeIconsWithMap=true) } }).awaitAll()
        }
        assertEquals(ClusterOutputSelection(ClusterDisplayMode.MAP_VIDEO,false,false,true),prefs.setClusterOutputEnabled(true).selection)
        prefs.updateClusterOutputSelection { it.copy(mode=ClusterDisplayMode.NATIVE_ICONS) }
        prefs.setClusterOutputEnabled(false)
        assertEquals(ClusterDisplayMode.NATIVE_ICONS,prefs.setClusterOutputEnabled(true).selection.mode)
    }

    @Test fun aggregateReturnIdentifiesOwnCommitNotSuccessor() = runBlocking {
        val backing=Store()
        val store=object : DataStore<Preferences> {
            override val data=backing.data
            override suspend fun updateData(transform: suspend (Preferences)->Preferences): Preferences {
                val own=backing.updateData(transform)
                backing.edit { it[AppPreferences.CLUSTER_DISPLAY_MODE]="NATIVE_ICONS"; it[AppPreferences.CLUSTER_LAST_ENABLED_MODE]="NATIVE_ICONS" }
                return own
            }
        }
        val prefs=preferences(store)
        val own=prefs.updateClusterOutputPreferences { it.selection.copy(mode=ClusterDisplayMode.MAP_VIDEO) }
        assertEquals(ClusterDisplayMode.MAP_VIDEO,own.selection.mode)
        assertEquals(ClusterDisplayMode.MAP_VIDEO,own.lastEnabledClusterMode)
        assertEquals(ClusterDisplayMode.NATIVE_ICONS,prefs.getClusterOutputPreferences().selection.mode)
    }

    @Test fun mapThenOffRetainsLastModeAcrossRealDataStoreReopen() = runBlocking {
        val root = Files.createTempDirectory(File("build/tmp").apply { mkdirs() }.toPath(), "cluster-retention-").toFile()
        val file = File(root, "settings.preferences_pb")
        var job = SupervisorJob()
        fun open() = PreferenceDataStoreFactory.create(scope = CoroutineScope(Dispatchers.IO + job), produceFile = { file })
        try {
            var store = open()
            var prefs = preferences(store)
            prefs.setDistanceUnits("imperial")
            prefs.setSendImuSensors(false)
            prefs.setClusterOutputSelection(ClusterOutputSelection(ClusterDisplayMode.MAP_VIDEO, false, true, true))
            prefs.updateClusterOutputSelection { it.copy(mode = ClusterDisplayMode.OFF) }
            job.cancelAndJoin()
            job = SupervisorJob()
            store = open()
            prefs = preferences(store)
            assertEquals(ClusterDisplayMode.OFF, prefs.getClusterOutputSelection().mode)
            assertEquals("MAP_VIDEO", store.data.first()[stringPreferencesKey("cluster_last_enabled_mode_v1")])
            assertEquals(ClusterDisplayMode.MAP_VIDEO,prefs.getClusterOutputPreferences().lastEnabledClusterMode)
            assertEquals(ClusterOutputSelection(ClusterDisplayMode.MAP_VIDEO,false,true,true),prefs.setClusterOutputEnabled(true).selection)
            assertEquals(ClusterDisplayMode.MAP_VIDEO,prefs.lastEnabledClusterMode.first())
            assertEquals("imperial", prefs.distanceUnits.first())
            assertFalse(prefs.sendImuSensors.first())
        } finally { job.cancelAndJoin(); root.deleteRecursively() }
    }
}
