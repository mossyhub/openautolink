package com.openautolink.app.ui.settings

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Build
import android.os.Looper
import android.view.View
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.Density
import androidx.lifecycle.viewModelScope
import com.openautolink.app.cluster.*
import com.openautolink.app.data.*
import com.openautolink.app.displays.DiscoveredSecondaryDisplay
import com.openautolink.app.secondary.*
import com.openautolink.app.ui.testing.createOalComposeRule
import io.mockk.*
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

/** Actual SettingsScreen, real Save transaction/DataStore and a cold store + ViewModel reopen. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32, 34], application = Application::class, qualifiers = "w1024dp-h360dp-land-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@ConscryptMode(ConscryptMode.Mode.OFF)
class SecondaryPresentationRootTest {
    @get:Rule val compose = createOalComposeRule()
    private val first = SecondaryOutputId("driver-display")
    private val second = SecondaryOutputId("other-display")
    private fun descriptor(id: SecondaryOutputId = first, display: Int = 2) = SecondaryDisplayDescriptor(
        id, display, 1, "Driver display $display", 2000, 810, 160, SecondaryRoute.PUBLIC_ACTIVITY, SecondaryReason.NONE, "Driver")
    private fun source(displays: List<SecondaryDisplayDescriptor> = listOf(descriptor())) = SecondaryDisplaysSource(
        MutableStateFlow(SecondaryInventorySnapshot(1, displays)), MutableStateFlow(displays.map {
            DiscoveredSecondaryDisplay(it.id.value, it.displayId, it.name, it.width, it.height, true, true, false, null)
        }), MutableStateFlow(mapOf(first to SecondaryOutputStatus(first, advertised = true,
            phoneSelected = true, hostAttached = true, decoded = 23, presented = 22))))

    @Test fun gmBinarySaveColdReopenPreservesGeometryAndSuppressesNativeVideo() = rootJourney(true, 1f)
    @Test @Config(qualifiers = "w480dp-h900dp-port-mdpi")
    fun gmNarrowLargeFontUsesSameRootAndSaveJourney() = rootJourney(true, 1.5f)
    @Test fun nonGmPreservesSavedCoexistenceWithoutGmCopy() = rootJourney(false, 1f)

    @Test fun exactPhysicalTargetSurvivesEditorAndSettingsTabRoundTrips() = rootJourney(true, 1f, true)

    private fun rootJourney(gm: Boolean, fontScale: Float, multipleTargets: Boolean = false) = runBlocking<Unit> {
        val oldProduct = Build.PRODUCT
        ReflectionHelpers.setStaticField(Build::class.java, "PRODUCT",
            if (gm) ClusterDisplayProfiles.AEGEAN_ORANGE_239.vehicle.product else "unqualified-oem")
        val app = RuntimeEnvironment.getApplication() as Application
        val file = File(app.filesDir, "secondary-root-${java.util.UUID.randomUUID()}.preferences_pb")
        var storeJob = SupervisorJob()
        fun newPreferences(): AppPreferences {
            val store = androidx.datastore.preferences.core.PreferenceDataStoreFactory.create(
                scope = CoroutineScope(storeJob + Dispatchers.IO), produceFile = { file })
            return AppPreferences::class.java.getDeclaredConstructor(androidx.datastore.core.DataStore::class.java)
                .apply { isAccessible = true }.newInstance(store)
        }
        var preferences = newPreferences()
        val layout = SecondaryLayoutPreference(requireNotNull(secondaryLayoutScope(descriptor())),
            SecondaryLayoutBox(.1, .1, .9, .9), SecondaryLayoutBox(.3, .3, .7, .7),
            directSafe = SecondaryLayoutBox(.31, .12, .81, .78))
        // Historical explicit OFF + enabled output is shown truthfully, not silently migrated on composition.
        preferences.setCarPlaySettings(CarPlaySettings(enabled = false))
        preferences.setClusterOutputSelection(ClusterOutputSelection(ClusterDisplayMode.OFF,
            androidAuto = false, carPlay = false, nativeIconsWithMap = true, directVideoWindow = false))
        preferences.updateSecondaryDisplayPreferences(0, mapOf(first to SecondaryOutputPreference(
            true, AaSecondaryRole.CLUSTER, false, false, layout), second to SecondaryOutputPreference(true, AaSecondaryRole.AUXILIARY)))
        mockkObject(AppPreferences.Companion)
        every { AppPreferences.getInstance(any()) } answers { preferences }
        var vm = SettingsViewModel(app)
        var mounted by mutableStateOf(true)
        var epoch by mutableIntStateOf(0)
        var root: View? = null
        var saves = 0
        var failedSaves = 0
        val saveJob = SupervisorJob()
        val saveScope = CoroutineScope(saveJob + Dispatchers.Main.immediate)
        val source = source(if (multipleTargets) listOf(descriptor(), descriptor(second, 7)) else listOf(descriptor()))
        val renderName = "${if (gm) "gm" else "other"}-$fontScale-${if (multipleTargets) "multi" else "single"}"
        fun loaded() = compose.waitUntil(5000) {
            shadowOf(Looper.getMainLooper()).idle()
            vm.clusterLoaded.value && vm.carPlaySettingsLoaded.value && vm.secondaryPreferences.value != null
        }
        fun openSecondary() { compose.onNodeWithTag("settingsTab_INSTRUMENT_CLUSTER").performScrollTo().performClick() }
        fun save() {
            val before = saves
            compose.onNodeWithTag("saveAndConnectButton").performClick()
            compose.waitUntil(5000) { shadowOf(Looper.getMainLooper()).idle(); saves == before + 1 }
        }
        suspend fun coldReopen() {
            compose.runOnIdle { mounted = false }
            compose.waitForIdle()
            vm.viewModelScope.cancel(); storeJob.cancelAndJoin()
            storeJob = SupervisorJob(); preferences = newPreferences(); vm = SettingsViewModel(app)
            compose.runOnIdle { epoch++; mounted = true }
            loaded(); openSecondary()
        }
        try {
            compose.setContent { if (mounted) key(epoch) {
                val density = LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                    MaterialTheme(colorScheme = darkColorScheme()) {
                        root = LocalView.current.rootView
                        SettingsScreen(clusterBindings = clusterSettingsFixture(), viewModel = vm, secondarySource = source,
                            onSaveAndConnect = { saveScope.launch { if (vm.commitSettings { true }) saves++ else failedSaves++ } })
                    }
                }
            } }
            loaded(); openSecondary()
            compose.onNodeWithContentDescription("Driver-display video").assertIsOff()
            compose.onNodeWithTag("secondaryLegacyOff").assertExists()
            assertEquals(ClusterDisplayMode.OFF, preferences.getClusterOutputSelection().mode)
            assertTrue(preferences.getSecondaryDisplayPreferences().outputs.getValue(first).enabled)
            assertCleanRoot(video = false)
            if (gm) compose.onNodeWithTag("gmVideoConsequence").assertExists()
            else compose.onNodeWithTag("gmVideoConsequence").assertDoesNotExist()
            save() // Explicit conversion of old OFF, not a composition write.
            assertEquals(ClusterDisplayMode.NATIVE_ICONS, preferences.getClusterOutputSelection().mode)
            assertTrue(preferences.clusterNavigation.first())
            assertTrue(preferences.getSecondaryDisplayPreferences().outputs.values.none { it.enabled })
            coldReopen()
            compose.onNodeWithTag("clusterDisplay_MAP_VIDEO").assertIsOff()
            compose.onNodeWithTag("secondaryLegacyOff").assertDoesNotExist()
            assertCleanRoot(video = false)
            compose.onNodeWithTag("clusterDisplay_MAP_VIDEO").performClick().assertIsOn()
            assertCleanRoot(video = true)
            if (multipleTargets) {
                compose.onNodeWithTag("secondaryEditorTarget_${second.value}").performScrollTo().performClick()
                compose.onNodeWithTag("secondaryImportantContentEditor").performScrollTo().performClick()
                compose.onNodeWithText("Directions area").assertExists()
                compose.onNodeWithTag("liveGridBack").performClick()
                compose.onNodeWithTag("secondaryEditorTarget_${second.value}").performScrollTo().assertIsSelected()
                compose.onNodeWithTag("settingsTab_DISPLAY").performScrollTo().performClick()
                openSecondary()
                compose.onNodeWithTag("secondaryEditorTarget_${second.value}").performScrollTo().assertIsSelected()
                compose.onNodeWithTag("secondaryEditorTarget_${first.value}").performScrollTo().performClick()
            }
            compose.onNodeWithTag("secondaryRole_${first.value}_CLUSTER").performScrollTo().assertIsSelected()
            compose.onNodeWithTag("secondaryRole_${first.value}_AUXILIARY").performScrollTo().performClick().assertIsSelected()
            compose.onNodeWithTag("secondaryRoleDescription").performScrollTo().assertTextEquals(
                "Full-screen map with destination details, speed limit and current speed in Google Maps. Recommended for larger driver displays, including most GM EVs.")
            assertEquals(AaSecondaryRole.CLUSTER, preferences.getSecondaryDisplayPreferences().outputs.getValue(first).aaRole)
            compose.onNodeWithTag("secondaryRole_${first.value}_CLUSTER").performScrollTo().performClick().assertIsSelected()
            compose.onNodeWithTag("secondaryRoleDescription").performScrollTo().assertTextEquals(
                "Full-screen map with a compact next-turn instruction at the top.")
            compose.onNodeWithTag("secondaryRoleAppearanceHelp").performScrollTo().assertTextEquals(
                "Appearance depends on Android Auto and your navigation app. These descriptions reflect Google Maps; Waze may look the same in both modes. OAL displays the supplied video—its content may change with phone-app updates.")
            compose.onNodeWithTag("secondaryCarPlayRoleNote").performScrollTo().assertTextEquals(
                "CarPlay has no equivalent Cluster/Auxiliary selector in OAL; its driver-display view is controlled by iOS and the navigation app.")
            compose.onNodeWithTag("secondaryImportantContentEditor").performScrollTo().assertIsDisplayed()
            if (gm && !multipleTargets && RuntimeEnvironment.getApiLevel() == 32) {
                compose.onNodeWithTag("clusterDisplay_MAP_VIDEO").performScrollTo()
                capture(root, "$renderName-video")
                compose.onNodeWithTag("secondaryImportantContentEditor").performScrollTo()
            }
            compose.onNodeWithTag("secondaryImportantContentEditor").performClick()
            compose.onNodeWithTag("liveGridOutput").assertDoesNotExist()
            compose.onNodeWithContentDescription("Move directions area").assertExists()
            compose.onNodeWithText("Move and resize the green box to keep directions visible.").assertExists()
            compose.onNodeWithText("After Done, tap Save changes. USB: unplug and reconnect to apply.").assertIsDisplayed()
            compose.onNodeWithTag("liveGridMove").assertDoesNotExist()
            compose.onNodeWithTag("liveGridResize").assertDoesNotExist()
            compose.onNodeWithTag("liveGridBack").assertIsDisplayed()
            compose.onNodeWithTag("liveGridReset").assertIsDisplayed()
            compose.onNodeWithTag("liveGridBack").performClick()
            assertEquals(ClusterDisplayMode.NATIVE_ICONS, preferences.getClusterOutputSelection().mode)
            save()
            val durable = preferences.getSecondaryDisplayPreferences()
            assertTrue(durable.outputs.getValue(first).enabled)
            assertFalse(durable.outputs.getValue(second).enabled)
            assertEquals(layout, durable.outputs.getValue(first).layout)
            assertEquals(AaSecondaryRole.CLUSTER, durable.outputs.getValue(first).aaRole)
            val video = preferences.getClusterOutputSelection()
            assertEquals(ClusterDisplayMode.MAP_VIDEO, video.mode)
            assertTrue(video.nativeIconsWithMap) // preserve non-GM intent; GM policy still vetoes publication
            assertFalse(video.androidAuto); assertFalse(video.carPlay); assertFalse(video.directVideoWindow)
            for (protocol in ClusterProjectionProtocol.entries)
                assertEquals(!gm, ClusterOutputPolicy.shouldPublishNativeIcons(video, protocol, true))
            assertTrue(preferences.clusterNavigation.first()) // compatibility intent; policy above owns GM publication
            coldReopen()
            compose.onNodeWithTag("clusterDisplay_MAP_VIDEO").assertIsOn()
            compose.onNodeWithTag("secondaryRole_${first.value}_CLUSTER").performScrollTo().assertIsSelected()
            assertEquals(layout, preferences.getSecondaryDisplayPreferences().outputs.getValue(first).layout)
            compose.onNodeWithTag("clusterDisplay_MAP_VIDEO").performScrollTo().performClick().assertIsOff()
            assertCleanRoot(video = false)
            save(); coldReopen()
            compose.onNodeWithTag("clusterDisplay_MAP_VIDEO").assertIsOff()
            assertEquals(ClusterDisplayMode.NATIVE_ICONS, preferences.getClusterOutputSelection().mode)
            assertTrue(preferences.clusterNavigation.first())
            assertEquals(layout, preferences.getSecondaryDisplayPreferences().outputs.getValue(first).layout)
            // A target disappearing while another tab is open cannot enable a stale physical locator.
            compose.onNodeWithTag("clusterDisplay_MAP_VIDEO").performScrollTo().performClick()
            compose.onNodeWithTag("settingsTab_DISPLAY").performScrollTo().performClick()
            compose.runOnIdle {
                (source.inventory as MutableStateFlow<SecondaryInventorySnapshot>).value = SecondaryInventorySnapshot(2, emptyList())
                (source.rawDisplays as MutableStateFlow<List<DiscoveredSecondaryDisplay>>).value = emptyList()
            }
            compose.waitForIdle()
            compose.onNodeWithTag("saveAndConnectButton").performClick()
            compose.waitUntil(5000) { shadowOf(Looper.getMainLooper()).idle(); failedSaves == 1 }
            assertEquals(ClusterDisplayMode.NATIVE_ICONS, preferences.getClusterOutputSelection().mode)
            assertTrue(preferences.getSecondaryDisplayPreferences().outputs.values.none { it.enabled })
            compose.onNodeWithTag("settingsApplyError").assertTextEquals("Choose a connected display before saving video.")
            openSecondary()
            if (gm) compose.onNodeWithTag("secondaryGmSetup").performScrollTo()
                .assertTextContains("30 seconds", substring = true)
            else compose.onNodeWithTag("secondaryGmSetup").assertDoesNotExist()
            compose.onNodeWithTag("settingsTab_DIAGNOSTICS").performScrollTo().performClick()
            compose.onNodeWithTag("secondaryGmSetup").assertDoesNotExist()
            } finally {
            compose.runOnIdle { mounted = false }
            compose.waitForIdle(); vm.viewModelScope.cancel(); saveJob.cancel(); storeJob.cancelAndJoin()
            unmockkObject(AppPreferences.Companion); file.delete()
            ReflectionHelpers.setStaticField(Build::class.java, "PRODUCT", oldProduct)
        }
    }

    private fun assertCleanRoot(video: Boolean) {
        compose.onAllNodesWithContentDescription("Driver-display video").assertCountEquals(1)
        for (tag in listOf("clusterDisplay_OFF", "clusterDisplay_NATIVE_ICONS", "clusterNativeWithMap",
            "clusterDirectWindow", "clusterAndroidAuto", "clusterCarPlay", "adjustClusterLayout", "secondaryLocalGridEditor"))
            compose.onNodeWithTag(tag).assertDoesNotExist()
        for (id in listOf(first, second)) for (prefix in listOf("secondaryEnabled_", "secondaryAa_", "secondaryCarPlay_", "secondaryStatus_", "secondaryReason_"))
            compose.onNodeWithTag(prefix + id.value).assertDoesNotExist()
        for (text in listOf("Enable output", "Icons-only", "Status", "Display details", "Source:", "Vehicle / profile:",
            "Host:", "Phone map video:", "Rendered frames:", "Decoded:", "Advertised:", "OEM", "Video outputs (advanced)"))
            compose.onNodeWithText(text, substring = true, ignoreCase = false).assertDoesNotExist()
        compose.onNodeWithText("Off").assertDoesNotExist()
        if (video) {
            compose.onAllNodesWithText("Directions area").assertCountEquals(1)
            compose.onNodeWithText("Usually no adjustment is needed. Edit only if directions or other non-map content are hidden or cut off. The map still fills the whole display.").assertExists()
            compose.onNodeWithTag("secondaryCarPlayRoleNote").assertExists()
            compose.onNodeWithText("Android Auto display mode").assertExists()
        } else {
            compose.onNodeWithTag("secondaryImportantContentEditor").assertDoesNotExist()
            compose.onNodeWithText("Android Auto display mode").assertDoesNotExist()
            compose.onNodeWithTag("secondaryRoleDescription").assertDoesNotExist()
            compose.onNodeWithTag("secondaryRoleAppearanceHelp").assertDoesNotExist()
            compose.onNodeWithTag("secondaryCarPlayRoleNote").assertDoesNotExist()
            compose.onNodeWithText("Auxiliary").assertDoesNotExist()
            compose.onNodeWithText("Cluster").assertDoesNotExist()
        }
    }

    @Test fun exactTargetSelectionDoesNotWriteOrRetargetWhenDisconnected() {
        val source = source(listOf(descriptor(), descriptor(second, 7)))
        val targets = mutableListOf<SecondaryDisplayDescriptor>()
        var observed: SecondaryDisplayDescriptor? = null
        val snapshot = SecondaryPreferencesSnapshot(0, emptyMap(), ClusterOutputPreferences(
            ClusterOutputSelection(ClusterDisplayMode.MAP_VIDEO), null))
        compose.setContent { MaterialTheme { SecondaryDisplaysSettings(source, snapshot, true,
            { _, _ -> fail("Target selection wrote output intent") }, { targets += it },
            onTargetSelected = { observed = it }) } }
        compose.onNodeWithTag("secondaryEditorTarget_${second.value}").performScrollTo().performClick()
        compose.onNodeWithTag("secondaryImportantContentEditor").performScrollTo().performClick()
        assertEquals(listOf(descriptor(second, 7)), targets)
        compose.runOnIdle {
            (source.inventory as MutableStateFlow<SecondaryInventorySnapshot>).value = SecondaryInventorySnapshot(2, listOf(descriptor()))
            (source.rawDisplays as MutableStateFlow<List<DiscoveredSecondaryDisplay>>).value = source.rawDisplays.value.filter { it.displayId == 2 }
        }
        compose.onNodeWithTag("secondaryImportantContentEditor").assertIsNotEnabled()
        compose.onNodeWithTag("secondaryEditorUnavailable").assertExists()
        compose.runOnIdle { assertNull(observed) }
        assertEquals(1, targets.size)
    }

    @Test fun freshRoleUsesGmAuxiliaryAndDefaultSafeBoxRemainsSourced() {
        val old = Build.PRODUCT
        ReflectionHelpers.setStaticField(Build::class.java, "PRODUCT", ClusterDisplayProfiles.AEGEAN_ORANGE_239.vehicle.product)
        try {
            val snapshot = SecondaryPreferencesSnapshot(0, emptyMap(), ClusterOutputPreferences(
                ClusterOutputSelection(ClusterDisplayMode.MAP_VIDEO), null))
            compose.setContent { MaterialTheme { SecondaryDisplaysSettings(source(), snapshot, true, { _, _ -> }) } }
            compose.onNodeWithTag("secondaryRole_${first.value}_AUXILIARY").performScrollTo().assertIsSelected()
            assertEquals(PixelRect(558, 70, 1694, 668), SecondaryProjectionActivity.directDefaultSafe(2000, 810))
        } finally { ReflectionHelpers.setStaticField(Build::class.java, "PRODUCT", old) }
    }

    private fun capture(root: View?, name: String) = compose.runOnIdle {
        val view = requireNotNull(root)
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bitmap))
        val dir = File("build/secondary-presentation-renders/sdk-${RuntimeEnvironment.getApiLevel()}").apply { mkdirs() }
        val output = File(dir, "$name.png")
        output.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        File(dir, "$name.txt").writeText("Actual SettingsScreen root; controlled host fixture, not vehicle output. ${view.width}x${view.height}; density=${view.resources.displayMetrics.density}.\n")
        assertTrue(output.length() > 1000)
    }
}
