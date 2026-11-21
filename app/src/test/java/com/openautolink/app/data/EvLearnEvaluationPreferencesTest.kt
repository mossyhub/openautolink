package com.openautolink.app.data

import android.app.Application
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32, 34], application = Application::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
class EvLearnEvaluationPreferencesTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun explicitOptInPersistsWithoutMigratingOrChangingWireSettings() = runBlocking {
        val file = temp.root.resolve("learn.preferences_pb")
        var job = SupervisorJob()
        fun open(): AppPreferences = AppPreferences::class.java
            .getDeclaredConstructor(DataStore::class.java)
            .apply { isAccessible = true }
            .newInstance(PreferenceDataStoreFactory.create(scope = CoroutineScope(Dispatchers.IO + job)) { file })
        try {
            var prefs = open()
            assertFalse(prefs.evLearnEvaluationEnabled.first())
            prefs.setEvTuningEnabled(true)
            prefs.setEvDrivingMode(AppPreferences.EV_DRIVING_MODE_LEARNED)
            prefs.setCarPlaySettings(CarPlaySettings(evDataEnabled = true))
            assertFalse(prefs.evLearnEvaluationEnabled.first())
            prefs.setEvLearnEvaluationEnabled(true)
            job.cancelAndJoin()
            job = SupervisorJob()
            prefs = open()
            assertTrue(prefs.evLearnEvaluationEnabled.first())
            prefs.setEvLearnEvaluationEnabled(false)
            assertFalse(prefs.evLearnEvaluationEnabled.first())
            assertTrue(prefs.evTuningEnabled.first())
            assertEquals(AppPreferences.EV_DRIVING_MODE_LEARNED, prefs.evDrivingMode.first())
            assertTrue(prefs.carPlaySettings.first().evDataEnabled)
        } finally {
            job.cancelAndJoin()
        }
    }
}
