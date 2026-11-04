package com.openautolink.app.ui.settings

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
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
@Config(sdk = [32], application = Application::class, qualifiers = "w1200dp-h800dp-land-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@ConscryptMode(ConscryptMode.Mode.OFF)
class SecondaryDisplaysSettingsTest {
    @get:Rule val compose = createOalComposeRule()
    private val first = SecondaryOutputId("public-a")
    private val second = SecondaryOutputId("public-b")
    private fun descriptor(id: SecondaryOutputId, display: Int) = SecondaryDisplayDescriptor(
        id, display, 1, "Output $display", 1280, 720, 160, SecondaryRoute.PUBLIC_ACTIVITY, SecondaryReason.NONE, "Unknown")
    private fun raw(id: SecondaryOutputId?, display: Int, available: Boolean = true) = DiscoveredSecondaryDisplay(
        id?.value, display, "Output $display", 1280, 720, available, available, id == null,
        if (available) null else "PRIVATE_DISPLAY")
    private fun source() = SecondaryDisplaysSource(
        MutableStateFlow(SecondaryInventorySnapshot(1, listOf(descriptor(first, 7), descriptor(second, 8)))),
        MutableStateFlow(listOf(raw(first, 7), raw(second, 8))), null)
    private fun snapshot(mode: ClusterDisplayMode = ClusterDisplayMode.MAP_VIDEO) = SecondaryPreferencesSnapshot(0,
        emptyMap(), ClusterOutputPreferences(ClusterOutputSelection(mode), null))

    @Test fun liveRowsKeepAmbiguousUnavailableAndDisconnectedSeparate() {
        val disconnected = SecondaryOutputId("disconnected")
        val rows = secondaryDisplayRows(listOf(raw(first, 7), raw(null, 8), raw(null, 9), raw(second, 10, false)),
            SecondaryInventorySnapshot(1, listOf(descriptor(first, 7))), mapOf(disconnected to SecondaryOutputPreference(true)))
        assertEquals(5, rows.size); assertEquals(5, rows.map { it.rowKey }.toSet().size)
        assertEquals(2, rows.count { it.id == null }); assertEquals(1, rows.count { it.usable })
        assertNull(rows.last().raw); assertEquals(disconnected, rows.last().id)
    }

    @Test fun roleEditsUseOnlyTheSelectedExactOutputWithoutAnEnableSwitch() {
        var snapshot by mutableStateOf(snapshot())
        val edited = mutableListOf<SecondaryOutputId>()
        compose.setContent { MaterialTheme { SecondaryDisplaysSettings(source(), snapshot, true,
            { id, edit -> edited += id; snapshot = snapshot.copy(outputs = snapshot.outputs +
                (id to edit(snapshot.outputs[id] ?: SecondaryOutputPreference()))) }) } }
        compose.onNodeWithTag("secondaryEditorTarget_${second.value}").performScrollTo().performClick()
        compose.onNodeWithTag("secondaryRole_${second.value}_AUXILIARY").performScrollTo().performClick()
        assertEquals(listOf(second), edited)
        assertEquals(AaSecondaryRole.AUXILIARY, snapshot.outputs[second]?.aaRole)
        assertFalse(requireNotNull(snapshot.outputs[second]).enabled) // only Save owns enable intent
        for (id in listOf(first, second)) compose.onNodeWithTag("secondaryEnabled_${id.value}").assertDoesNotExist()
    }

    @Test fun observedMilestonesNeverAppearAsConfigurationOrMutateIntent() {
        val source = source().copy(statuses = MutableStateFlow(mapOf(first to SecondaryOutputStatus(first,
            advertised = true, phoneSelected = true, hostAttached = true, decoded = 2, presented = 1))))
        compose.setContent { MaterialTheme { SecondaryDisplaysSettings(source, snapshot(), true,
            { _, _ -> fail("Observation changed intent") }) } }
        for (text in listOf("Advertised:", "Phone selected:", "Decoded:", "Host attached:", "Runtime status:", "Display details"))
            compose.onNodeWithText(text, substring = true).assertDoesNotExist()
        compose.onNodeWithTag("secondaryGmSetup").assertDoesNotExist()
        compose.onAllNodesWithContentDescription("Driver-display video").assertCountEquals(1)
    }

    @Test fun unavailableAndAmbiguousRowsOfferNoFakeTargetOrLegacyEditor() {
        val source = SecondaryDisplaysSource(MutableStateFlow(SecondaryInventorySnapshot(1, emptyList())),
            MutableStateFlow(listOf(raw(null, 7), raw(null, 8), raw(second, 9, false))), null)
        compose.setContent { MaterialTheme { SecondaryDisplaysSettings(source, snapshot(), true,
            { _, _ -> fail("Unavailable row edited") }) } }
        compose.onNodeWithTag("secondaryImportantContentEditor").assertIsNotEnabled()
        compose.onNodeWithTag("secondaryEditorUnavailable").assertExists()
        compose.onNodeWithTag("secondaryLocalGridEditor").assertDoesNotExist()
        compose.onNodeWithTag("secondaryEditorTarget_${second.value}").assertDoesNotExist()
    }

    @Test fun pendingPreferencesDisableTheSingleControlWithoutInventingDefaults() {
        compose.setContent { MaterialTheme { SecondaryDisplaysSettings(source(), null, true,
            { _, _ -> fail("Pending preferences edited") }) } }
        compose.onNodeWithTag("clusterDisplay_MAP_VIDEO").assertIsNotEnabled()
        compose.onNodeWithTag("secondaryPreferencesUnavailable").assertExists()
        compose.onNodeWithTag("secondaryImportantContentEditor").assertDoesNotExist()
    }
}
