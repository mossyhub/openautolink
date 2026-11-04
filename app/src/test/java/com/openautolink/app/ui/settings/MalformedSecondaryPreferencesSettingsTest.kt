package com.openautolink.app.ui.settings

import android.app.Application
import android.os.Looper
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.lifecycle.viewModelScope
import com.openautolink.app.cluster.ClusterDisplayMode
import com.openautolink.app.cluster.ClusterOutputSelection
import com.openautolink.app.data.AppPreferences
import com.openautolink.app.secondary.*
import com.openautolink.app.ui.testing.createOalComposeRule
import io.mockk.every
import io.mockk.mockkObject
import io.mockk.unmockkObject
import java.io.File
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32, 34], application = Application::class, qualifiers = "w1200dp-h800dp-land-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MalformedSecondaryPreferencesSettingsTest {
    @get:Rule val compose = createOalComposeRule()

    @Test fun wrongTypedRecordKeepsOrdinarySettingsUsableAndOptionalChoicesDisabled(): Unit = exercise(true)
    @Test fun partialRecordKeepsOrdinarySettingsUsableAndOptionalChoicesDisabled(): Unit = exercise(false)

    private fun exercise(wrongType: Boolean): Unit = runBlocking {
        val app = RuntimeEnvironment.getApplication()
        val file = File(app.filesDir, "secondary-malformed-ui-${java.util.UUID.randomUUID()}.preferences_pb")
        var job = SupervisorJob()
        fun open() = PreferenceDataStoreFactory.create(scope = CoroutineScope(job + Dispatchers.IO), produceFile = { file })
        var store = open()
        fun preferences(store: DataStore<Preferences>) = AppPreferences::class.java.getDeclaredConstructor(DataStore::class.java)
            .apply { isAccessible = true }.newInstance(store)
        var prefs = preferences(store)
        prefs.setCarPlaySettings(com.openautolink.app.data.CarPlaySettings(enabled = false))
        prefs.setClusterOutputSelection(ClusterOutputSelection(ClusterDisplayMode.MAP_VIDEO, false, false, true))
        prefs.setClusterOutputEnabled(false)
        prefs.setDistanceUnits("imperial")
        val legacy = prefs.getClusterOutputPreferences()
        store.edit {
            if (wrongType) it[intPreferencesKey(AppPreferences.SECONDARY_DISPLAY_OUTPUTS.name)] = 17
            else it[AppPreferences.SECONDARY_DISPLAY_OUTPUTS] = """{"version":1,"revision":1,"outputs":{"a":{"enabled":true}}}"""
        }
        val before = store.data.first().asMap()
        job.cancelAndJoin(); job = SupervisorJob(); store = open(); prefs = preferences(store)
        mockkObject(AppPreferences.Companion)
        every { AppPreferences.getInstance(any()) } returns prefs
        val vm = SettingsViewModel(app)
        try {
            val id = SecondaryOutputId("a")
            val source = SecondaryDisplaysSource(kotlinx.coroutines.flow.MutableStateFlow(SecondaryInventorySnapshot(1,
                listOf(SecondaryDisplayDescriptor(id, 7, 1, "Output 7", 1280, 720, 160, SecondaryRoute.PUBLIC_ACTIVITY,
                    SecondaryReason.NONE, "Unknown")))),
                kotlinx.coroutines.flow.MutableStateFlow(listOf(com.openautolink.app.displays.DiscoveredSecondaryDisplay(
                    id.value, 7, "Output 7", 1280, 720, true, true, false, null))), null)
            compose.setContent { MaterialTheme { SettingsScreen(clusterBindings = clusterSettingsFixture(), viewModel = vm, secondarySource = source) } }
            compose.waitUntil(5000) {
                shadowOf(Looper.getMainLooper()).idle()
                vm.carPlaySettingsLoaded.value && vm.clusterLoaded.value && vm.secondaryPreferences.value != null
            }
            assertNotNull(vm.secondaryPreferences.value?.error)
            assertTrue(requireNotNull(vm.secondaryPreferences.value).outputs.isEmpty())
            assertEquals(legacy, vm.secondaryPreferences.value?.legacyCluster)
            compose.onNodeWithTag("settingsTab_DISPLAY").performScrollTo().performClick()
            compose.onNodeWithTag("editSafeAreaButton").assertExists()
            compose.onNodeWithTag("settingsTab_INSTRUMENT_CLUSTER").performScrollTo().performClick()
            compose.onNodeWithTag("secondaryPreferencesError").assertExists()
            compose.onNodeWithTag("secondaryEnabled_a").assertDoesNotExist()
            compose.onNodeWithTag("clusterDisplay_MAP_VIDEO").assertIsNotEnabled().assertIsOff()
            compose.onNodeWithTag("settingsTab_CONNECTION").performScrollTo().performClick()
            compose.onNodeWithTag("saveAndConnectButton").assertExists()
            assertEquals("imperial", prefs.distanceUnits.first())
            prefs.setDistanceUnits("metric")
            assertEquals("metric", prefs.distanceUnits.first())
            assertEquals(before.filterKeys { it != AppPreferences.DISTANCE_UNITS },
                store.data.first().asMap().filterKeys { it != AppPreferences.DISTANCE_UNITS })
        } finally {
            compose.runOnIdle { vm.viewModelScope.cancel() }
            unmockkObject(AppPreferences.Companion)
            job.cancelAndJoin(); file.delete()
        }
    }
}
