package com.openautolink.app.ui.settings

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import com.openautolink.app.cluster.*
import com.openautolink.app.data.ClusterOutputPreferences
import com.openautolink.app.displays.DiscoveredSecondaryDisplay
import com.openautolink.app.secondary.*
import com.openautolink.app.ui.testing.createOalComposeRule
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32, 34], application = Application::class, qualifiers = "w1200dp-h800dp-land-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@ConscryptMode(ConscryptMode.Mode.OFF)
class SecondaryDensityProtocolHelpTest {
    @get:Rule val compose = createOalComposeRule()
    @Test fun densityHelpExplicitlyExcludesCarPlayWithoutEditingPreferences() {
        val id = SecondaryOutputId("protocol-help")
        val descriptor = SecondaryDisplayDescriptor(id, 7, 1, "Driver display", 2000, 810, 220,
            SecondaryRoute.PUBLIC_ACTIVITY, SecondaryReason.NONE, "Unknown")
        val source = SecondaryDisplaysSource(
            MutableStateFlow(SecondaryInventorySnapshot(1, listOf(descriptor))),
            MutableStateFlow(listOf(DiscoveredSecondaryDisplay(id.value, 7, "Driver display", 2000, 810, true, true, false, null))), null)
        val saved = SecondaryPreferencesSnapshot(7,
            mapOf(id to SecondaryOutputPreference(true, AaSecondaryRole.AUXILIARY, aaDensityDpi = 149)),
            ClusterOutputPreferences(ClusterOutputSelection(ClusterDisplayMode.MAP_VIDEO), null))
        compose.setContent { MaterialTheme { SecondaryDisplaysSettings(source, saved, true,
            { _, _ -> error("Viewing protocol help must not change any setting") }) } }
        compose.onNodeWithTag("secondaryDensityProtocolHelp").performScrollTo()
            .assertTextEquals("Android Auto only. Changes text and card size on this secondary display after Save and reconnect. This control does not affect CarPlay or the main display.")
    }
}
