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
@Config(sdk = [32, 34], application = Application::class, qualifiers = "w1400dp-h700dp-land-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@ConscryptMode(ConscryptMode.Mode.OFF)
class SettingsGroupingTest {
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
        runBlocking { prefs.setCarPlaySettings(CarPlaySettings(enabled = true)); prefs.setClusterOutputSelection(ClusterOutputSelection(ClusterDisplayMode.MAP_VIDEO)) }
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

    @Test fun renderAllEightActualDestinations() {
        try {
            mount()
            for (name in SettingsTab.entries.map { it.name }) {
                tab(name)
                if (name == "AUDIO") compose.onNodeWithTag("saveAndConnectButton").assertDoesNotExist()
                else compose.onNodeWithTag("saveAndConnectButton").assertIsDisplayed()
                capture("wide-$name-top")
            }
        } finally { finish() }
    }

    @Test @Config(qualifiers = "w480dp-h900dp-port-mdpi")
    fun renderNarrowLargeTextAssociatedControls() {
        try {
            mount(1.3f)
            for ((tab, tag) in listOf("INSTRUMENT_CLUSTER" to "clusterDisplay_MAP_VIDEO", "AUDIO" to "callSetup", "DISPLAY" to "gpsForwardingToggle", "EV" to "evUseEpaBaseline")) {
                tab(tab); compose.onNodeWithTag(tag).performScrollTo().assertIsDisplayed(); capture("narrow-large-$tab")
            }
        } finally { finish() }
    }

    @Test fun helpersStayWithTheirControlAndGmSetupLeavesDiagnostics() {
        try {
            mount(); tab("INSTRUMENT_CLUSTER")
            compose.onNodeWithTag("clusterDisplay_MAP_VIDEO").performScrollTo()
            val toggle = compose.onNodeWithTag("clusterDisplay_MAP_VIDEO").fetchSemanticsNode().boundsInRoot
            assertTrue("Readable bounded column, not panel-wide control separation: $toggle", toggle.width <= 760f)
            compose.onNodeWithTag("secondaryGmSetup").performScrollTo().assertTextContains("30 seconds", substring = true)
            tab("DIAGNOSTICS"); compose.onNodeWithTag("secondaryGmSetup").assertDoesNotExist()
        } finally { finish() }
    }
}
