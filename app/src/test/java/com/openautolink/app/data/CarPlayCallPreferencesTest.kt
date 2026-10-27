package com.openautolink.app.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.junit.Assert.*
import org.junit.Test

class CarPlayCallPreferencesTest {
    @Test fun applicationBootstrapOwnsPolicyBeforeAnyActivityOrServiceAdmission() {
        val source = java.io.File("src/main/java/com/openautolink/app/OalApplication.kt").readText()
        assertTrue("production policy must initialize without opening Compose settings", source.contains("CarPlayCallController.get(this)"))
    }

    private class Store : DataStore<Preferences> {
        val state = MutableStateFlow<Preferences>(emptyPreferences())
        val mutex = Mutex()
        override val data: Flow<Preferences> = state
        override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences = mutex.withLock {
            transform(state.value).also { state.value = it }
        }
    }
    private fun preferences(store: Store): AppPreferences = AppPreferences::class.java
        .getDeclaredConstructor(DataStore::class.java).apply { isAccessible = true }.newInstance(store)
    @Test fun freshDefaultsAndSeparateAtomicEditsSurviveRestartWithoutChangingDisplay() = runBlocking {
        val store = Store(); val prefs = preferences(store)
        assertEquals(CarPlayCallPreferences(), prefs.carPlayCallPreferences.first())
        prefs.updateCarPlayCallPreferences { it.copy(handler = CarPlayCallPreferences.Handler.CAR_BLUETOOTH) }
        prefs.updateCarPlayCallPreferences { it.copy(extraRingtone = true) }
        val restored = preferences(store).carPlayCallPreferences.first()
        assertEquals(CarPlayCallPreferences.Handler.CAR_BLUETOOTH, restored.handler)
        assertTrue(restored.extraRingtone)
        assertNull(store.state.value[AppPreferences.CARPLAY_SETTINGS])
    }
}
