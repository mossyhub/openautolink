package com.openautolink.app.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class WppConfigPreferencesTest {
    @Test fun networkIdentityIsWrittenAsOneSnapshotWithoutTrimmingSsid() = runBlocking {
        val store = MemoryStore()
        val preferences = preferences(store)
        assertTrue(preferences.setWppConfig(" Car ", "00:11:22:33:44:55") { mutation -> mutation(); true })
        assertEquals(1, store.snapshots.size)
        assertEquals(" Car ", store.snapshots.single()[stringPreferencesKey("hotspot_ssid")])
        assertEquals("00:11:22:33:44:55", store.snapshots.single()[stringPreferencesKey("wpp_bssid")])
    }

    @Test fun rejectedCommitDoesNotChangeEitherField() = runBlocking {
        val store = MemoryStore()
        val preferences = preferences(store)
        preferences.setWppConfig("Original", "00:11:22:33:44:55") { mutation -> mutation(); true }
        assertFalse(preferences.setWppConfig("Stale", "00:11:22:33:44:66") { false })
        assertEquals("Original", store.value[stringPreferencesKey("hotspot_ssid")])
        assertEquals("00:11:22:33:44:55", store.value[stringPreferencesKey("wpp_bssid")])
    }

    @Test fun commitGuardIsEvaluatedInsideDelayedStoreTransaction() = runBlocking {
        val store = MemoryStore()
        val preferences = preferences(store)
        store.gate = CompletableDeferred()
        var current = true
        val pending = async { preferences.setWppConfig("Stale", "00:11:22:33:44:55") { mutation ->
            if (current) { mutation(); true } else false
        } }
        store.entered.await()
        current = false
        store.gate!!.complete(Unit)
        assertFalse(pending.await())
        assertNull(store.value[stringPreferencesKey("hotspot_ssid")])
        assertNull(store.value[stringPreferencesKey("wpp_bssid")])
    }

    private fun preferences(store: DataStore<Preferences>): AppPreferences =
        AppPreferences::class.java.getDeclaredConstructor(DataStore::class.java).apply {
            isAccessible = true
        }.newInstance(store)

    private class MemoryStore : DataStore<Preferences> {
        private val state = MutableStateFlow(emptyPreferences())
        override val data: Flow<Preferences> = state
        val value get() = state.value
        val snapshots = mutableListOf<Preferences>()
        val entered = CompletableDeferred<Unit>()
        var gate: CompletableDeferred<Unit>? = null
        override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences {
            entered.complete(Unit)
            gate?.await()
            return transform(state.value).also { state.value = it; snapshots.add(it) }
        }
    }
}
