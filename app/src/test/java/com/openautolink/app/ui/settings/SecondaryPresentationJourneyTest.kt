package com.openautolink.app.ui.settings

import android.app.Application
import android.os.Looper
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.lifecycle.viewModelScope
import com.openautolink.app.cluster.*
import com.openautolink.app.data.*
import com.openautolink.app.secondary.*
import com.openautolink.app.ui.testing.createOalComposeRule
import io.mockk.*
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import java.io.File

/** Real rendered callback -> shared Save -> real file DataStore -> cold reopen.
 * Protocol/vehicle execution is qualified separately; no UI state is called Ready. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32, 34], application = Application::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
class SecondaryPresentationJourneyTest {
    @get:Rule val compose = createOalComposeRule()
    @get:Rule val temp = org.junit.rules.TemporaryFolder()
    private var storeJob = SupervisorJob()
    private var vm: SettingsViewModel? = null
    private fun preferences(): AppPreferences {
        val store = PreferenceDataStoreFactory.create(scope = CoroutineScope(Dispatchers.IO + storeJob),
            produceFile = { File(temp.root, "settings.preferences_pb") })
        return AppPreferences::class.java.getDeclaredConstructor(DataStore::class.java)
            .apply { isAccessible = true }.newInstance(store)
    }
    private fun await(condition: () -> Boolean) {
        val deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5)
        while (!condition() && System.nanoTime() < deadline) { shadowOf(Looper.getMainLooper()).idle(); Thread.sleep(2) }
        assertTrue("actual completion before original deadline", condition())
    }
    @After fun cleanup() {
        vm?.viewModelScope?.cancel()
        runBlocking { storeJob.cancelAndJoin() }
        unmockkAll()
    }
    @Test fun renderedVideoOffSaveColdRestoresIconsWithoutErasingRoleOrLayout() {
        var prefs = preferences()
        val id = SecondaryOutputId("saved-physical")
        val layout = SecondaryLayoutPreference(SecondaryLayoutScope(id, "vehicle", "build", 2000, 810, 160),
            SecondaryLayoutBox(0.0, 0.1, 1.0, 1.0), SecondaryLayoutBox(0.2, 0.2, 0.8, 0.9))
        val output = SecondaryOutputPreference(true, AaSecondaryRole.AUXILIARY, false, true, layout)
        runBlocking {
            prefs.setClusterOutputSelection(ClusterOutputSelection(ClusterDisplayMode.MAP_VIDEO, false, true, true))
            prefs.updateSecondaryDisplayPreferences(0, mapOf(id to output))
        }
        mockkObject(AppPreferences.Companion)
        every { AppPreferences.getInstance(any()) } answers { prefs }
        val model = SettingsViewModel(RuntimeEnvironment.getApplication()); vm = model
        await { model.clusterLoaded.value && model.carPlaySettingsLoaded.value && model.secondaryPreferences.value != null }
        model.observeSecondaryPresentationTarget(null) // observation only; OFF requires no physical video target
        var save: (() -> Unit)? = null
        var result: Boolean? = null
        compose.setContent {
            val selection by model.clusterSelection.collectAsState()
            val scope = rememberCoroutineScope()
            save = { scope.launch { result = model.commitSettings { true } } }
            MaterialTheme { InstrumentClusterSettings(selection, "auto", clusterSettingsFixture(), true,
                model::setClusterEnabled, model::chooseClusterDisplay, model::updateClusterSelection, {}, {}) }
        }
        compose.onNodeWithTag("clusterDisplay_MAP_VIDEO").assertIsOn().performClick().assertIsOff()
        compose.onNodeWithTag("clusterDisplay_NATIVE_ICONS").assertDoesNotExist()
        compose.onNodeWithTag("clusterDisplay_OFF").assertDoesNotExist()
        compose.runOnIdle { save!!() }
        await { result != null }; assertEquals(true, result)
        assertEquals(ClusterDisplayMode.NATIVE_ICONS, runBlocking { prefs.getClusterOutputSelection() }.mode)
        assertEquals(output.copy(enabled = false), runBlocking { prefs.getSecondaryDisplayPreferences() }.outputs[id])
        model.viewModelScope.cancel(); vm = null
        runBlocking { storeJob.cancelAndJoin() }; storeJob = SupervisorJob(); prefs = preferences()
        val cold = runBlocking { prefs.getSecondaryDisplayPreferences() }
        assertEquals(ClusterDisplayMode.NATIVE_ICONS, cold.legacyCluster.selection.mode)
        assertEquals(ClusterDisplayMode.NATIVE_ICONS, cold.legacyCluster.lastEnabledClusterMode)
        assertEquals(output.copy(enabled = false), cold.outputs[id])
        assertEquals(false, cold.legacyCluster.selection.androidAuto)
        assertEquals(true, cold.legacyCluster.selection.carPlay)
        assertEquals(true, cold.legacyCluster.selection.nativeIconsWithMap)
        runBlocking { prefs.migrateSecondaryClusterPreference(id) }
        assertEquals(cold, runBlocking { prefs.getSecondaryDisplayPreferences() })
    }
}
