package com.openautolink.app.ui.settings

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import com.openautolink.app.cluster.*
import com.openautolink.app.data.ClusterOutputPreferences
import com.openautolink.app.displays.DiscoveredSecondaryDisplay
import com.openautolink.app.secondary.*
import com.openautolink.app.ui.testing.createOalComposeRule
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32, 34], application = Application::class, qualifiers = "w1200dp-h800dp-land-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@ConscryptMode(ConscryptMode.Mode.OFF)
class SecondaryDensitySettingsTest {
    @get:Rule val compose = createOalComposeRule()
    private val first = SecondaryOutputId("density-a")
    private val second = SecondaryOutputId("density-b")
    private fun descriptor(id: SecondaryOutputId, number: Int) = SecondaryDisplayDescriptor(
        id, number, 1, "Output $number", 2000, 810, 220,
        SecondaryRoute.PUBLIC_ACTIVITY, SecondaryReason.NONE, "Unknown")
    private fun source() = SecondaryDisplaysSource(
        MutableStateFlow(SecondaryInventorySnapshot(1, listOf(descriptor(first, 7), descriptor(second, 8)))),
        MutableStateFlow(listOf(first to 7, second to 8).map { (id, number) ->
            DiscoveredSecondaryDisplay(id.value, number, "Output $number", 2000, 810, true, true, false, null)
        }), null)
    private fun saved(mode: ClusterDisplayMode = ClusterDisplayMode.MAP_VIDEO) = SecondaryPreferencesSnapshot(7,
        mapOf(first to SecondaryOutputPreference(true, AaSecondaryRole.AUXILIARY),
            second to SecondaryOutputPreference(false, AaSecondaryRole.CLUSTER)),
        ClusterOutputPreferences(ClusterOutputSelection(mode), null))
    // Reflection lets the exact same regression compile against the pre-feature model for RED.
    private fun density(preference: SecondaryOutputPreference): Int? =
        preference.javaClass.getMethod("getAaDensityDpi").invoke(preference) as Int?

    @Test fun customDensityStagesOnlySelectedOutputAndAutomaticRemovesOverride() {
        val original = saved()
        var snapshot by mutableStateOf(original)
        val edits = mutableListOf<SecondaryOutputId>()
        val source = source()
        compose.setContent { MaterialTheme { SecondaryDisplaysSettings(source, snapshot, true,
            { id, edit -> edits += id; snapshot = snapshot.copy(outputs = snapshot.outputs +
                (id to edit(snapshot.outputs.getValue(id)))) }) } }
        compose.onNodeWithTag("secondaryDensityCustom").performScrollTo().assertIsOff().performClick()
        assertEquals(220, density(snapshot.outputs.getValue(first)))
        compose.onNodeWithTag("secondaryDensitySlider").performScrollTo()
            .performSemanticsAction(SemanticsActions.SetProgress) { it(140f) }
        compose.runOnIdle {
            assertEquals(140, density(snapshot.outputs.getValue(first)))
            assertNull(density(original.outputs.getValue(first)))
            assertEquals(original.outputs.getValue(second), snapshot.outputs.getValue(second))
            assertTrue(edits.all { it == first })
            assertTrue(snapshot.outputs.getValue(first).enabled)
            assertEquals(AaSecondaryRole.AUXILIARY, snapshot.outputs.getValue(first).aaRole)
        }
        compose.onNodeWithTag("secondaryDensityCustom").performScrollTo().performClick()
        compose.runOnIdle { assertNull(density(snapshot.outputs.getValue(first))) }
        compose.onNodeWithTag("secondaryDensitySlider").assertDoesNotExist()
    }

    @Test fun switchingSelectedOutputNeverCopiesAnotherOutputsDensity() {
        var snapshot by mutableStateOf(saved())
        val source = source()
        compose.setContent { MaterialTheme { SecondaryDisplaysSettings(source, snapshot, true,
            { id, edit -> snapshot = snapshot.copy(outputs = snapshot.outputs +
                (id to edit(snapshot.outputs.getValue(id)))) }) } }
        compose.onNodeWithTag("secondaryDensityCustom").performScrollTo().performClick()
        compose.onNodeWithTag("secondaryDensitySlider").performScrollTo()
            .performSemanticsAction(SemanticsActions.SetProgress) { it(140f) }
        compose.onNodeWithTag("secondaryEditorTarget_${second.value}").performScrollTo().performClick()
        compose.onNodeWithTag("secondaryDensityCustom").performScrollTo().assertIsOff()
        compose.onNodeWithTag("secondaryDensityCustom").performClick()
        compose.runOnIdle {
            assertEquals(140, density(snapshot.outputs.getValue(first)))
            assertEquals(220, density(snapshot.outputs.getValue(second)))
            assertFalse(snapshot.outputs.getValue(second).enabled)
        }
    }

    @Test fun disabledSettingsCannotStageDensity() {
        val source = source()
        compose.setContent { MaterialTheme { SecondaryDisplaysSettings(source, saved(), false,
            { _, _ -> fail("Disabled settings mutated") }) } }
        compose.onNodeWithTag("secondaryDensityCustom").performScrollTo().assertIsNotEnabled()
    }

    @Test fun iconsOnlyDoesNotExposeDensityControls() {
        val source = source()
        compose.setContent { MaterialTheme { SecondaryDisplaysSettings(source, saved(ClusterDisplayMode.NATIVE_ICONS), true,
            { _, _ -> fail("Viewing icons-only mutated intent") }) } }
        compose.onNodeWithTag("secondaryDensityCustom").assertDoesNotExist()
    }
}
