package com.openautolink.app.ui.settings

import android.app.Application
import android.os.Looper
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.lifecycle.viewModelScope
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import com.openautolink.app.cluster.*
import com.openautolink.app.data.*
import com.openautolink.app.displays.DiscoveredSecondaryDisplay
import com.openautolink.app.secondary.*
import com.openautolink.app.ui.testing.createOalComposeRule
import io.mockk.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.*
import java.io.File

/** Render the shipped editor via SettingsScreen; commit with its global Save and real file-backed preferences. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32, 34], application = Application::class, qualifiers = "w1200dp-h800dp-land-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@ConscryptMode(ConscryptMode.Mode.OFF)
class SecondaryCalibratedEditorJourneyTest {
    @get:Rule val compose = createOalComposeRule()
    @get:Rule val temp = org.junit.rules.TemporaryFolder()
    private val id = SecondaryOutputId("calibrated-editor")
    private val display = SecondaryDisplayDescriptor(id, 17, 1, "Driver", 2000, 810, 220,
        SecondaryRoute.PUBLIC_ACTIVITY, SecondaryReason.NONE, "Driver")
    private var job = SupervisorJob()
    private var vm: SettingsViewModel? = null
    private fun store() = PreferenceDataStoreFactory.create(scope = CoroutineScope(job + Dispatchers.IO),
        produceFile = { File(temp.root, "editor.preferences_pb") })
    private fun preferences(store: DataStore<Preferences>) = AppPreferences::class.java
        .getDeclaredConstructor(DataStore::class.java).apply { isAccessible = true }.newInstance(store)
    private fun await(condition: () -> Boolean) {
        compose.waitUntil(5000) { shadowOf(Looper.getMainLooper()).idle(); condition() }
    }
    private fun action(label: String) {
        val actions = compose.onNodeWithTag("directions_MOVE").fetchSemanticsNode().config[SemanticsActions.CustomActions]
        compose.runOnIdle { assertTrue(actions.single { it.label == label }.action()) }
    }
    private fun rendered(rect: PixelRect) {
        val canvas = compose.onNodeWithTag("liveGridCanvas").fetchSemanticsNode().boundsInRoot
        val handle = compose.onNodeWithTag("directions_MOVE").fetchSemanticsNode().boundsInRoot
        assertEquals("rendered safe center X", canvas.left + canvas.width * (rect.left + rect.right) / 4000f, handle.center.x, 1.1f)
        assertEquals("rendered safe center Y", canvas.top + canvas.height * (rect.top + rect.bottom) / 1620f, handle.center.y, 1.1f)
        val corner = compose.onNodeWithTag("directions_BOTTOM_RIGHT").fetchSemanticsNode().boundsInRoot
        assertEquals("rendered safe right", canvas.left + canvas.width * rect.right / 2000f, corner.center.x, 1.1f)
        assertEquals("rendered safe bottom", canvas.top + canvas.height * rect.bottom / 810f, corner.center.y, 1.1f)
    }
    @After fun cleanup() { vm?.viewModelScope?.cancel(); runBlocking { job.cancelAndJoin() }; unmockkAll() }

    @Test fun migratedEditorCustomAndAutomaticSurviveGlobalSaveAndColdReopen() = runBlocking<Unit> {
        var data = store()
        var prefs = preferences(data)
        prefs.setClusterOutputSelection(ClusterOutputSelection(ClusterDisplayMode.MAP_VIDEO))
        val original = SecondaryLayoutPreference(requireNotNull(secondaryLayoutScope(display)),
            SecondaryLayoutBox(.1,.1,.9,.9), SecondaryLayoutBox(.3,.2,.7,.7),
            directSafe = SecondaryLayoutBox(.32,.23,.68,.67), directSafeAutomatic = true)
        data.edit { it[AppPreferences.SECONDARY_DISPLAY_OUTPUTS] = SecondaryDisplayPreferences.encode(
            SecondaryPreferencesSnapshot(7, mapOf(id to SecondaryOutputPreference(true, AaSecondaryRole.AUXILIARY,
                layout = original, aaDensityDpi = 220)), ClusterOutputPreferences(
                ClusterOutputSelection(ClusterDisplayMode.MAP_VIDEO), ClusterDisplayMode.MAP_VIDEO))) }
        val migrated = prefs.getSecondaryDisplayPreferences()
        val migratedLayout = migrated.outputs.getValue(id).layout!!
        assertNotNull(migratedLayout.aaDirectSafe)
        assertTrue(migratedLayout.directSafeAutomatic)
        val marker = booleanPreferencesKey("secondary_aa_calibrated_defaults_v1")
        assertEquals(true, data.data.first()[marker])
        mockkObject(AppPreferences.Companion)
        every { AppPreferences.getInstance(any()) } answers { prefs }
        var model = SettingsViewModel(RuntimeEnvironment.getApplication()); vm = model
        val source = SecondaryDisplaysSource(MutableStateFlow(SecondaryInventorySnapshot(1, listOf(display))),
            MutableStateFlow(listOf(DiscoveredSecondaryDisplay(id.value,17,"Driver",2000,810,true,true,false,null))), null)
        var mounted by mutableStateOf(true)
        var epoch by mutableIntStateOf(0)
        var saves = 0
        compose.setContent { if (mounted) key(epoch) {
            val scope = rememberCoroutineScope()
            MaterialTheme { SettingsScreen(clusterBindings = clusterSettingsFixture(), viewModel = model,
                secondarySource = source, onSaveAndConnect = { scope.launch {
                    assertTrue(model.carPlayApplyError.value, model.commitSettings { true }); saves++
                } }) }
        } }
        fun loaded() = await { model.clusterLoaded.value && model.carPlaySettingsLoaded.value && model.secondaryPreferences.value != null }
        fun tab() { compose.onNodeWithTag("settingsTab_INSTRUMENT_CLUSTER").performScrollTo().performClick() }
        fun editor() { compose.onNodeWithTag("secondaryImportantContentEditor").performScrollTo().performClick() }
        fun done() { compose.onNodeWithTag("liveGridBack").performClick() }
        fun save() { val previous = saves; compose.onNodeWithTag("saveAndConnectButton").performClick(); await { saves == previous + 1 } }
        suspend fun reopen() {
            compose.runOnIdle { mounted = false }; compose.waitForIdle()
            model.viewModelScope.cancel(); job.cancelAndJoin(); job = SupervisorJob()
            data = store(); prefs = preferences(data); model = SettingsViewModel(RuntimeEnvironment.getApplication()); vm = model
            compose.runOnIdle { epoch++; mounted = true }; loaded(); tab()
        }
        loaded(); tab(); editor()
        // aaDirectSafe must beat the historical Automatic flag and both shared CP rectangles.
        rendered(PixelRect(192,71,1788,660))
        action("Move right"); action("Increase width")
        rendered(PixelRect(193,71,1790,660)); done()
        assertEquals("Done is draft-only", migrated, prefs.getSecondaryDisplayPreferences())
        save()
        val custom = prefs.getSecondaryDisplayPreferences().outputs.getValue(id).layout!!
        assertNull(custom.aaDirectSafe); assertFalse(custom.directSafeAutomatic)
        assertEquals(original.output, custom.output); assertEquals(original.safe, custom.safe)
        assertEquals(SecondaryLayoutBox(193.0/2000,71.0/810,1790.0/2000,660.0/810), custom.directSafe)
        fun runtime(layout: SecondaryLayoutPreference?) = SecondaryRuntimeLayouts.target(display, layout,
            PixelSize(2000,810), PixelRect(558,70,1694,668), android.os.Build.PRODUCT, android.os.Build.FINGERPRINT, safeOnly = true)
        assertEquals(PixelRect(0,0,2000,810), runtime(custom).output)
        reopen(); assertEquals(custom, prefs.getSecondaryDisplayPreferences().outputs.getValue(id).layout)
        editor(); rendered(PixelRect(193,71,1790,660))
        compose.onNodeWithTag("liveGridReset").performClick(); rendered(PixelRect(192,71,1788,660)); done()
        assertEquals(custom, prefs.getSecondaryDisplayPreferences().outputs.getValue(id).layout)
        // Explicit Automatic density must remain null after Save/reopen, not be re-migrated to an override.
        compose.onNodeWithTag("secondaryDensityCustom").performScrollTo().assertIsOn().performClick()
        compose.onNodeWithText("Automatic · 149 DPI").performScrollTo().assertIsDisplayed()
        save()
        val automatic = prefs.getSecondaryDisplayPreferences().outputs.getValue(id)
        assertNull(automatic.aaDensityDpi); assertNull(automatic.layout!!.aaDirectSafe)
        assertNull(automatic.layout!!.directSafe); assertTrue(automatic.layout!!.directSafeAutomatic)
        assertEquals(original.output, automatic.layout!!.output); assertEquals(original.safe, automatic.layout!!.safe)
        assertEquals(PixelRect(192,71,1788,660), runtime(automatic.layout).safe)
        reopen(); assertEquals(automatic, prefs.getSecondaryDisplayPreferences().outputs.getValue(id))
        assertEquals(true, data.data.first()[marker])
        compose.onNodeWithText("Automatic · 149 DPI").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("secondaryDensityCustom").performScrollTo().performClick()
        assertEquals(149, model.secondaryPreferences.value!!.outputs.getValue(id).aaDensityDpi)
        editor(); rendered(PixelRect(192,71,1788,660)); done()
    }
}
