package com.openautolink.app.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import com.openautolink.app.carplay.CarPlayRestrictionPolicy
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class SharedAlwaysInParkTest {
    private class Store(initial: Preferences) : DataStore<Preferences> {
        override val data = MutableStateFlow(initial)
        override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences =
            transform(data.value).also { data.value = it }
    }
    private fun prefs(global: Boolean? = null, legacy: String? = null): AppPreferences {
        val values = mutablePreferencesOf()
        global?.let { values[AppPreferences.ALWAYS_IN_PARK] = it }
        legacy?.let { values[AppPreferences.CARPLAY_SETTINGS] = it }
        return AppPreferences::class.java.getDeclaredConstructor(DataStore::class.java)
            .apply { isAccessible = true }.newInstance(Store(values))
    }
    private suspend fun shared(prefs: AppPreferences, expected: Boolean) {
        assertEquals(expected, prefs.alwaysInPark.first())
        assertEquals(expected, prefs.carPlaySettings.first().alwaysInPark)
    }

    @Test fun explicitGlobalChoiceWinsOverEitherLegacyValue() = runBlocking {
        for (global in listOf(false, true)) for (legacy in listOf(false, true)) {
            shared(prefs(global, CarPlaySettings.encode(CarPlaySettings(alwaysInPark = legacy))), global)
        }
    }

    @Test fun onlyValidatedLegacyTrueIsFallbackWhenSharedKeyIsAbsent() = runBlocking {
        shared(prefs(legacy = "{\"alwaysInPark\":true}"), true)
        for (legacy in listOf(null, "{}", "{", "{\"alwaysInPark\":\"true\"}",
            "{\"alwaysInPark\":1}", "{\"alwaysInPark\":null}")) shared(prefs(legacy = legacy), false)
    }

    @Test fun sharedToggleChangesNextSessionPolicyButNotFrozenSnapshotOrSavedCapability() = runBlocking {
        for (capability in listOf(false, true)) {
            val prefs = prefs(false, CarPlaySettings.encode(CarPlaySettings(drivingRestrictionsEnabled = capability)))
            val frozen = CarPlayRestrictionPolicy.from(prefs.carPlaySettings.first(), false)
            prefs.setAlwaysInPark(true)
            shared(prefs, true)
            val overridden = CarPlayRestrictionPolicy.from(prefs.carPlaySettings.first(), false)
            assertTrue(overridden.enabled)
            assertTrue(overridden.alwaysInPark)
            assertFalse(frozen.alwaysInPark)
            assertEquals(capability, frozen.enabled)
            assertEquals(capability, prefs.carPlaySettings.first().drivingRestrictionsEnabled)
            prefs.setAlwaysInPark(false)
            shared(prefs, false)
            val restored = CarPlayRestrictionPolicy.from(prefs.carPlaySettings.first(), false)
            assertEquals(capability, restored.enabled)
            assertFalse(restored.alwaysInPark)
        }
    }

    @Test fun staleCarPlayDraftCannotChangeSharedChoiceIncludingLegacyFallback() = runBlocking {
        for (global in listOf(null, false, true)) {
            val prefs = prefs(global, "{\"alwaysInPark\":true}")
            val expected = global ?: true
            prefs.updateCarPlaySettings({ it.copy(alwaysInPark = !expected, fps = 30) })
            shared(prefs, expected)
            assertEquals(30, prefs.carPlaySettings.first().fps)
        }
    }
}
