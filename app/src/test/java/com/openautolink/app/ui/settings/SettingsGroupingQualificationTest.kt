package com.openautolink.app.ui.settings

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Build
import android.os.Looper
import android.view.View
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.*
import androidx.lifecycle.viewModelScope
import com.openautolink.app.cluster.*
import com.openautolink.app.data.*
import com.openautolink.app.displays.DiscoveredSecondaryDisplay
import com.openautolink.app.secondary.*
import com.openautolink.app.ui.testing.createOalComposeRule
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.*
import org.robolectric.util.ReflectionHelpers
import java.io.File

/** Actual production Settings root; no replica of its rows, navigation or actions. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32, 34], application = Application::class, qualifiers = "w1600dp-h640dp-land-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@ConscryptMode(ConscryptMode.Mode.OFF)
class SettingsGroupingQualificationTest {
    @get:Rule val compose = createOalComposeRule()
    private var root: View? = null
    private var vm: SettingsViewModel? = null
    private var oldProduct = Build.PRODUCT
    private val id = SecondaryOutputId("grouping-driver")

    private fun mount(fontScale: Float = 1f, inventory: Boolean = true) {
        RuntimeEnvironment.setFontScale(fontScale)
        oldProduct = Build.PRODUCT
        ReflectionHelpers.setStaticField(Build::class.java, "PRODUCT", ClusterDisplayProfiles.AEGEAN_ORANGE_239.vehicle.product)
        val app = RuntimeEnvironment.getApplication() as Application
        val prefs = AppPreferences.getInstance(app)
        runBlocking { prefs.setCarPlaySettings(CarPlaySettings(enabled = true, drivingRestrictionsEnabled = true)); prefs.setClusterOutputSelection(ClusterOutputSelection(ClusterDisplayMode.MAP_VIDEO)) }
        val model = SettingsViewModel(app); vm = model
        val display = SecondaryDisplayDescriptor(id, 2, 1, "Driver display", 2000, 810, 160, SecondaryRoute.PUBLIC_ACTIVITY, SecondaryReason.NONE, "Driver")
        val source = SecondaryDisplaysSource(MutableStateFlow(SecondaryInventorySnapshot(1, listOf(display))),
            MutableStateFlow(listOf(DiscoveredSecondaryDisplay(id.value, 2, "Driver display", 2000, 810, true, true, false, null))), null)
        val audio = AudioSettingsBindings(CarPlayCallUiState(setup = CarPlayCallSetup.ENABLE_ACCOUNT, preferencesLoaded = true), {}, {}, {}, {}, {}, {})
        compose.setContent { MaterialTheme(colorScheme = darkColorScheme()) { Surface {
            root = LocalView.current.rootView
            SettingsScreen(clusterBindings = clusterSettingsFixture(), viewModel = model,
                secondarySource = if (inventory) source else null, audioBindings = audio)
        } } }
        compose.waitUntil(5000) { shadowOf(Looper.getMainLooper()).idle(); model.carPlaySettingsLoaded.value && model.clusterLoaded.value && model.secondaryPreferences.value != null }
    }
    private fun tab(name: String) { compose.onNodeWithTag("settingsTab_$name").performScrollTo().performClick(); compose.waitForIdle() }
    private fun capture(name: String) {
        val dir = File(System.getProperty("settings.grouping.render.dir", "build/settings-grouping")); dir.mkdirs()
        compose.runOnIdle {
            val view = requireNotNull(root)
            val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(bitmap))
            File(dir, "$name-sdk${RuntimeEnvironment.getApiLevel()}.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
        File(dir, "$name-sdk${RuntimeEnvironment.getApiLevel()}.semantics.txt").writeText(compose.onRoot().printToString())
    }
    private fun finish() { vm?.viewModelScope?.cancel(); RuntimeEnvironment.setFontScale(1f); ReflectionHelpers.setStaticField(Build::class.java, "PRODUCT", oldProduct) }

    private fun bounds(tag: String) = compose.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot
    private fun receipt(name: String, values: String) {
        val dir = File(System.getProperty("settings.grouping.render.dir", "build/settings-grouping")); dir.mkdirs()
        File(dir, "$name-sdk${RuntimeEnvironment.getApiLevel()}.bounds.txt").writeText(values)
    }

    @Test fun screenshotAspectSecondaryHasOwnedGroupsAndFortyEightDpControls() {
        try {
            mount(); tab("INSTRUMENT_CLUSTER")
            val main = bounds("settingGroup_Driver-display video")
            val toggle = bounds("clusterDisplay_MAP_VIDEO")
            val consequence = bounds("gmVideoConsequence")
            val gm = bounds("secondaryGmSetupGroup")
            // OAL's accepted own-interface size is 125%; dimensions below are physical fixture pixels.
            assertTrue("Column width $main", main.width <= 760f * 1.25f + 1)
            assertTrue("Switch touch target $toggle", toggle.width >= 48f * 1.25f && toggle.height >= 48f * 1.25f)
            assertTrue(main.contains(consequence.topLeft) && main.contains(consequence.bottomRight))
            assertTrue("Different group separation $main $gm", gm.top >= main.bottom)
            compose.onNodeWithTag("secondaryRoleGroup").performScrollTo()
            val role = bounds("secondaryRoleGroup")
            val description = bounds("secondaryRoleDescription")
            assertTrue("Role owns the whole helper: $role $description", role.left <= description.left && description.right <= role.right && role.top <= description.top && description.bottom <= role.bottom)
            compose.onNodeWithTag("secondaryDirectionsGroup").performScrollTo()
            val editor = bounds("secondaryImportantContentEditor")
            val helper = bounds("secondaryDirectionsHelp")
            assertTrue("Directions helper is immediately beneath its action: $editor $helper", helper.top - editor.bottom in 0f..11f)
            assertTrue(editor.height >= 48f * 1.25f)
            receipt("secondary-wide", "main=$main\ntoggle=$toggle\nconsequence=$consequence\ngm=$gm\nrole=$role\nroleHelper=$description\neditor=$editor\ndirectionsHelper=$helper")
            capture("screenshot-aspect-secondary-directions")
        } finally { finish() }
    }

    @Test fun disabledControlsKeepHelpersAndOneTapChangesOnlyOneDraft() {
        try {
            mount(); tab("CONNECTION")
            compose.onNodeWithTag("carPlayAlwaysInPark").performScrollTo()
            val parked = compose.onNodeWithTag("carPlayAlwaysInPark")
            val before = requireNotNull(vm).carPlaySettings.value.alwaysInPark
            parked.performClick()
            compose.runOnIdle { assertEquals(!before, requireNotNull(vm).carPlaySettings.value.alwaysInPark) }
            tab("AUDIO")
            compose.onNodeWithTag("extraCarRingtone").performScrollTo().assertContentDescriptionEquals("Extra car ringtone")
            compose.onNodeWithTag("extraCarRingtoneHelper").assertExists()
            tab("EV")
            compose.onNodeWithTag("evUseEpaBaseline").performScrollTo().assertIsNotEnabled()
            compose.onNodeWithText("No matching profile bundled. Try refreshing below.").assertIsDisplayed()
        } finally { finish() }
    }

    @Test fun missingInventoryDoesNotHideGmSetupOrInitializeAnEditor() {
        try {
            mount(inventory = false); tab("INSTRUMENT_CLUSTER")
            compose.onNodeWithTag("secondaryGmSetup").performScrollTo().assertIsDisplayed()
            compose.onNodeWithTag("secondaryImportantContentEditor").assertDoesNotExist()
            tab("DIAGNOSTICS"); compose.onNodeWithTag("secondaryGmSetup").assertDoesNotExist()
        } finally { finish() }
    }
    @Test fun allEightDestinationsKeepTheirControlsBoundedWithoutSavingOnOpen() {
        try {
            mount()
            val prefs = AppPreferences.getInstance(RuntimeEnvironment.getApplication())
            fun snapshot() = runBlocking { Triple(prefs.carPlaySettings.first(), prefs.getClusterOutputSelection(), prefs.getSecondaryDisplayPreferences()) }
            val before = snapshot()
            val observed = StringBuilder()
            for ((destination, control) in listOf(
                "CONNECTION" to "carPlayEnabled", "DISPLAY" to "oalUiScale_125",
                "INSTRUMENT_CLUSTER" to "clusterDisplay_MAP_VIDEO", "VIDEO" to "carPlayFitToWindow",
                "AUDIO" to "micSource_car", "INPUT" to "keyMap_85", "EV" to "evMasterToggle",
                "DIAGNOSTICS" to "liveDiagnosticsServer")) {
                tab(destination)
                compose.onNodeWithTag(control).performScrollTo().assertIsDisplayed()
                val box = bounds(control)
                assertTrue("$destination lost its control: $box", box.height >= 48f * 1.25f)
                assertTrue("$destination escaped its readable column: $box", box.width <= 760f * 1.25f + 1)
                observed.append("$destination/$control=$box\n")
                if (destination == "AUDIO") compose.onNodeWithTag("saveAndConnectButton").assertDoesNotExist()
                else compose.onNodeWithTag("saveAndConnectButton").assertIsDisplayed()
            }
            assertEquals("Opening settings must not commit a draft", before, snapshot())
            receipt("all-eight-control-bounds", observed.toString())
        } finally { finish() }
    }

    @Test fun existingNegotiationRowLabelStillTogglesExactlyOnce() {
        try {
            mount(); tab("VIDEO")
            assertTrue(requireNotNull(vm).settingsState.value.videoAutoNegotiate)
            compose.onNodeWithText("Auto (Recommended)").performScrollTo().performClick()
            compose.waitUntil(5000) { shadowOf(Looper.getMainLooper()).idle(); !requireNotNull(vm).settingsState.value.videoAutoNegotiate }
            compose.onNodeWithContentDescription("Video negotiation").performClick()
            compose.waitUntil(5000) { shadowOf(Looper.getMainLooper()).idle(); requireNotNull(vm).settingsState.value.videoAutoNegotiate }
        } finally { finish() }
    }

    @Test @Config(qualifiers = "w480dp-h900dp-port-mdpi")
    fun longRailLabelWrapsInsideItsOwnRailWithReadableMargins() {
        try {
            mount(1.3f); tab("INSTRUMENT_CLUSTER")
            val rail = bounds("settingsTabRail")
            val label = compose.onNode(hasText("Secondary displays") and hasAnyAncestor(hasTestTag("settingsTabRail")), useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
            assertTrue("Rail label must not touch the clipped panel edge: $rail $label", label.left >= rail.left + 9f && label.right <= rail.right - 9f)
            assertTrue("The complete label wraps rather than shrinks or ellipsizes: $label", label.height > 30f)
            compose.onNodeWithContentDescription("Back").assertIsDisplayed()
            receipt("narrow-large-rail", "rail=$rail\nlabel=$label")
            capture("narrow-large-rail")
        } finally { finish() }
    }

}
