package com.openautolink.app.ui.settings

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.test.*
import com.openautolink.app.displays.DiscoveredSecondaryDisplay
import com.openautolink.app.secondary.*
import com.openautolink.app.cluster.ClusterOutputSelection
import com.openautolink.app.cluster.ClusterDisplayMode
import com.openautolink.app.data.ClusterOutputPreferences
import com.openautolink.app.ui.testing.createOalComposeRule
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32, 34], application = Application::class, qualifiers = "w1000dp-h360dp-land-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@ConscryptMode(ConscryptMode.Mode.OFF)
class SecondaryDisplayAvailabilityTest {
    @get:Rule val compose = createOalComposeRule()
    private val builtin = SecondaryOutputId("physical")
    private val virtual = SecondaryOutputId("virtual")
    private fun mount() {
        val physical = SecondaryDisplayDescriptor(builtin, 7, 1, "Built-in driver display", 2000, 810, 160,
            SecondaryRoute.INVENTORY_ONLY, SecondaryReason.UNKNOWN_BUILTIN, "Unknown")
        val host = SecondaryDisplayDescriptor(virtual, 8, 1, "AppHost CarAppService virtual canvas", 1000, 400, 160,
            SecondaryRoute.INVENTORY_ONLY, SecondaryReason.NO_PUBLIC_ROUTE, "Unknown")
        val source = SecondaryDisplaysSource(MutableStateFlow(SecondaryInventorySnapshot(1, listOf(physical, host))),
            MutableStateFlow(listOf(physical, host).map { DiscoveredSecondaryDisplay(it.id.value, it.displayId, it.name,
                it.width, it.height, true, false, false, null) }), null)
        compose.setContent { MaterialTheme { Surface { SecondaryDisplaysSettings(source, SecondaryPreferencesSnapshot(0,
            emptyMap(), ClusterOutputPreferences(ClusterOutputSelection(ClusterDisplayMode.MAP_VIDEO), null)), true,
            { _, _ -> fail("Unqualified outputs must not be editable") }) } } }
    }
    @Test fun builtinAndAppHostDoNotBecomeOutputSwitchesOrTechnicalDashboard() {
        mount()
        for (id in listOf(builtin, virtual)) {
            compose.onNodeWithTag("secondaryEnabled_${id.value}").assertDoesNotExist()
            compose.onNodeWithTag("secondaryReason_${id.value}").assertDoesNotExist()
            compose.onNodeWithTag("secondaryEditorTarget_${id.value}").assertDoesNotExist()
        }
        compose.onNodeWithText("AppHost CarAppService virtual canvas").assertDoesNotExist()
        compose.onNodeWithTag("secondaryImportantContentEditor").assertIsNotEnabled()
        compose.onNodeWithTag("secondaryEditorUnavailable").performScrollTo().assertIsDisplayed()
    }
    @Test fun noLocalOrLegacyEditorSubstitutesForMissingPhysicalOutput() {
        mount()
        compose.onNodeWithTag("secondaryLocalGridEditor").assertDoesNotExist()
        compose.onNodeWithTag("adjustClusterLayout").assertDoesNotExist()
        compose.onNodeWithTag("secondaryImportantContentEditor").assertIsNotEnabled()
    }
    @Test @Config(qualifiers = "w360dp-h720dp-port-mdpi")
    fun narrowActionableAvailabilityRemainsReachable() {
        mount()
        compose.onNodeWithTag("secondaryEditorUnavailable").performScrollTo()
            .assertTextEquals("Connect or choose a display to configure video.").assertIsDisplayed()
    }
}
