package com.openautolink.app.ui.settings

import android.app.Application
import android.os.Build
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
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
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.*
import org.robolectric.util.ReflectionHelpers

/** Root Save/cold-restoration and NativeGraphics renders live in SecondaryPresentationRootTest. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32], application = Application::class, qualifiers = "w1024dp-h360dp-land-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@ConscryptMode(ConscryptMode.Mode.OFF)
class SettingsClarityTest {
    @get:Rule val compose = createOalComposeRule()

    @Test fun nonGmDisplayTwoKeepsClusterDefaultWithoutGmConsequences() {
        val old = Build.PRODUCT
        ReflectionHelpers.setStaticField(Build::class.java, "PRODUCT", "not-a-qualified-gm")
        try {
            val id = SecondaryOutputId("non-gm")
            val display = SecondaryDisplayDescriptor(id, 2, 1, "Output", 2000, 810, 160,
                SecondaryRoute.PUBLIC_ACTIVITY, SecondaryReason.NONE, "Unknown")
            val source = SecondaryDisplaysSource(MutableStateFlow(SecondaryInventorySnapshot(1, listOf(display))),
                MutableStateFlow(listOf(DiscoveredSecondaryDisplay(id.value, 2, "Output", 2000, 810, true, true, false, null))), null)
            val snapshot = SecondaryPreferencesSnapshot(0, emptyMap(), ClusterOutputPreferences(
                ClusterOutputSelection(ClusterDisplayMode.MAP_VIDEO), null))
            compose.setContent { MaterialTheme { SecondaryDisplaysSettings(source, snapshot, true, { _, _ -> fail("Composition changed intent") }) } }
            compose.onNodeWithTag("secondaryRole_${id.value}_CLUSTER").performScrollTo().assertIsSelected()
            compose.onNodeWithTag("gmVideoConsequence").assertDoesNotExist()
            compose.onNodeWithTag("clusterNativeWithMap").assertDoesNotExist()
            compose.onAllNodesWithText("Directions area").assertCountEquals(1)
            compose.onNodeWithTag("adjustClusterLayout").assertDoesNotExist()
        } finally { ReflectionHelpers.setStaticField(Build::class.java, "PRODUCT", old) }
    }

    @Test fun pairingCopyIsRemovedWhileBriefPrivacyAndRevocationRemainVisible() {
        com.openautolink.app.diagnostics.live.LiveDiagnostics.initialize(RuntimeEnvironment.getApplication())
        com.openautolink.app.diagnostics.live.LiveDiagnostics.stop()
        compose.setContent { MaterialTheme {
            Column(Modifier.verticalScroll(rememberScrollState())) { com.openautolink.app.diagnostics.live.LiveDiagnosticsSettings() }
        } }
        compose.onNodeWithText("Pairing is separate from Mossy and upload credentials.", substring = true).assertDoesNotExist()
        compose.onNodeWithText("Pairing is stored encrypted on this device.", substring = true).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Revoke also invalidates server access.", substring = true).assertExists()
    }
}
