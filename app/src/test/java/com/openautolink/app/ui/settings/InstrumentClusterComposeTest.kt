package com.openautolink.app.ui.settings

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Density
import androidx.compose.ui.test.*
import com.openautolink.app.cluster.*
import com.openautolink.app.ui.testing.createOalComposeRule
import kotlinx.coroutines.launch
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** Explicit disconnected test fixture, never a production adapter. Unexpected runtime work fails. */
fun clusterSettingsFixture() = ClusterSettingsBindings(
    status = ClusterSettingsStatus(null, null, null, null, null), layout = null, automaticLayout = null,
    actualSurface = null, isCustomLayout = false, previewActive = false, previewOwner = Any(),
    onStageLayout = { error("No qualified fixture geometry") }, onResetLayout = { error("No automatic fixture geometry") },
    onStartPreview = { _, _ -> error("Opening settings must not start preview") },
    onStopPreview = {}, commitLayoutDraft = { true },
)

@org.robolectric.annotation.ConscryptMode(org.robolectric.annotation.ConscryptMode.Mode.OFF)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class InstrumentClusterComposeTest {
    @get:Rule val compose = createOalComposeRule()

    @Test fun defaultsAndMapOnlyControlsReflectIntentWithoutPretendingReady() {
        var selection by mutableStateOf(ClusterOutputSelection())
        var root: android.view.View? = null
        compose.setContent { MaterialTheme {
            root = LocalView.current.rootView
            InstrumentClusterSettings(selection, "auto", clusterSettingsFixture(), true,
                { selection = selection.copy(mode = if (it) ClusterDisplayMode.NATIVE_ICONS else ClusterDisplayMode.OFF) },
                { selection = selection.copy(mode = it) }, { selection = it(selection) }, {}, {})
        } }
        compose.onNodeWithTag("clusterDisplay_MAP_VIDEO").assertIsOff()
        compose.onNodeWithTag("clusterNativeWithMap").assertDoesNotExist()
        capture(root, "cluster-default.png")
        compose.onNodeWithTag("clusterDisplay_MAP_VIDEO").performClick()
        compose.onNodeWithTag("clusterNativeWithMap").assertDoesNotExist()
        compose.onNodeWithTag("clusterAndroidAuto").assertDoesNotExist()
        compose.onNodeWithTag("clusterCarPlay").assertDoesNotExist()
        compose.onNodeWithTag("clusterDirectWindow").assertDoesNotExist()
        compose.onNodeWithTag("clusterNativeWarning").assertDoesNotExist()
        compose.onNodeWithText("Host: Unavailable — not observed").assertDoesNotExist()
        capture(root, "cluster-map-status.png")
        compose.onNodeWithTag("clusterDisplay_MAP_VIDEO").performScrollTo().performClick().assertIsOff()
        compose.onNodeWithTag("clusterNativeWarning").assertDoesNotExist()
        compose.onNodeWithTag("clusterNativeWithMap").assertDoesNotExist()
    }

    private fun editableFixture(): ClusterSettingsBindings {
        val p = ClusterDisplayProfiles.AEGEAN_ORANGE_239
        val model = ClusterLayoutModel(ClusterLayoutKey(p.vehicle, p.role, p.panel), p.panel.bounds, p.output, p.safe)
        return clusterSettingsFixture().copy(layout = model, automaticLayout = model)
    }

    @Test fun primarySettingsWithoutDisplayInventoryNeverOffersLegacyEditorOrLaunchesPreview() {
        val vm = SettingsViewModel(RuntimeEnvironment.getApplication())
        compose.setContent { MaterialTheme { SettingsScreen(clusterBindings = editableFixture(), viewModel = vm) } }
        compose.waitUntil(5000) { vm.clusterLoaded.value }
        compose.onNodeWithTag("settingsTab_INSTRUMENT_CLUSTER").performScrollTo().performClick()
        compose.onNodeWithTag("clusterDisplay_MAP_VIDEO").assertIsOff()
        compose.onNodeWithTag("adjustClusterLayout").assertDoesNotExist()
        compose.onNodeWithTag("secondaryInventoryUnavailable").assertExists()
        compose.onNodeWithTag("instrumentClusterSettings").assertExists()
        compose.onNodeWithTag("settingsTab_DISPLAY").performScrollTo().performClick()
        compose.onNodeWithTag("clusterDisplay_NATIVE_ICONS").assertDoesNotExist()
        compose.onNodeWithTag("distanceUnits_auto").assertDoesNotExist()
        compose.onNodeWithTag("settingsTab_INPUT").performScrollTo().performClick()
        compose.onNodeWithTag("clusterDisplay_NATIVE_ICONS").assertDoesNotExist()
    }

    @Test fun narrowLargeFontShowsNoticeAndControlsWithoutFixedHeightClipping() {
        RuntimeEnvironment.setQualifiers("w360dp-h800dp")
        var root: android.view.View? = null
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 1.5f)) { MaterialTheme {
                root = LocalView.current.rootView
                InstrumentClusterSettings(ClusterOutputSelection(mode = ClusterDisplayMode.MAP_VIDEO), "auto",
                    clusterSettingsFixture(), true, {}, {}, {}, {}, {})
            } }
        }
        compose.onNodeWithContentDescription("Driver-display video").performScrollTo().assertIsDisplayed()
        capture(root, "cluster-narrow-large-font.png")
        compose.onNodeWithText(CLUSTER_LAYOUT_NOTICE).assertDoesNotExist()
        compose.onNodeWithTag("adjustClusterLayout").assertDoesNotExist()
    }

    @Test fun landscapeDarkPrimaryDestinationRendersAndSurvivesSavedStateRestore() {
        RuntimeEnvironment.setQualifiers("w1024dp-h600dp-land")
        val vm = SettingsViewModel(RuntimeEnvironment.getApplication())
        var root: android.view.View? = null
        val restoration = androidx.compose.ui.test.junit4.StateRestorationTester(compose)
        restoration.setContent { MaterialTheme(colorScheme = androidx.compose.material3.darkColorScheme()) {
            root = LocalView.current.rootView
            SettingsScreen(clusterBindings = editableFixture(), viewModel = vm)
        } }
        compose.waitUntil(5000) { vm.clusterLoaded.value && vm.carPlaySettingsLoaded.value }
        compose.onNodeWithTag("settingsTab_INSTRUMENT_CLUSTER").performScrollTo().performClick()
        compose.onNodeWithTag("clusterDisplay_OFF").assertDoesNotExist()
        compose.onNodeWithTag("clusterDisplay_MAP_VIDEO").assertIsEnabled().assertIsOff().performClick().assertIsOn()
        capture(root, "cluster-primary-landscape-dark.png")
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithTag("instrumentClusterSettings").assertExists()
        compose.onNodeWithTag("clusterDisplay_MAP_VIDEO").assertIsOn()
        compose.onNodeWithTag("adjustClusterLayout").assertDoesNotExist()
        compose.onNodeWithTag("secondaryInventoryUnavailable").assertExists()
        compose.onNodeWithTag("instrumentClusterSettings").assertExists()
    }

    @Test fun editorPreviewRequiresExplicitBoundedStartAndStopsOnExit() {
        val profile = ClusterDisplayProfiles.AEGEAN_ORANGE_239
        val model = ClusterLayoutModel(ClusterLayoutKey(profile.vehicle, profile.role, profile.panel),
            profile.panel.bounds, profile.output, profile.safe)
        var staged: ClusterLayoutModel? = null
        val starts = mutableListOf<Int>()
        var stops = 0
        var open by mutableStateOf(true)
        val bindings = clusterSettingsFixture().copy(layout = model, automaticLayout = model,
            onStageLayout = { staged = it }, onStartPreview = { _, seconds -> starts += seconds }, onStopPreview = { stops++ })
        compose.setContent { MaterialTheme { if (open) ClusterLayoutEditor(bindings, { open = false }) } }
        compose.runOnIdle { assertTrue(starts.isEmpty()) }
        compose.onNodeWithTag("clusterNudge_SAFE_X_plus").revealClusterEditorControl().performClick()
        compose.runOnIdle { assertNotNull(staged); assertTrue(staged!!.output.contains(staged!!.safe)) }
        compose.onNodeWithTag("clusterPreviewStart").revealClusterEditorControl().performClick()
        compose.runOnIdle { assertEquals(listOf(15), starts) }
        compose.onNodeWithTag("clusterPreviewStop").revealClusterEditorControl().performClick()
        compose.runOnIdle { assertEquals(1, stops) }
        compose.onNodeWithTag("clusterPreviewStart").revealClusterEditorControl().performClick()
        compose.runOnIdle { open = false }
        compose.runOnIdle { assertFalse(open); assertEquals(2, stops) }
    }

    @Test fun dragResizeStagesValidatedClusterOnlyGeometryAndResetUsesAutomaticCallback() {
        val profile = ClusterDisplayProfiles.AEGEAN_ORANGE_239
        val model = ClusterLayoutModel(ClusterLayoutKey(profile.vehicle, profile.role, profile.panel),
            profile.panel.bounds, profile.output, profile.safe)
        var staged: ClusterLayoutModel? = null
        var resets = 0
        var root: android.view.View? = null
        val bindings = clusterSettingsFixture().copy(layout = model, automaticLayout = model,
            onStageLayout = { staged = it }, onResetLayout = { resets++ })
        compose.setContent { MaterialTheme {
            root = LocalView.current.rootView
            ClusterLayoutEditor(bindings, {})
        } }
        compose.onNodeWithTag("clusterDragResize").revealClusterEditorControl().performClick()
        compose.onNodeWithTag("clusterLayoutCanvas").revealClusterEditorControl().performTouchInput {
            swipe(center, center + androidx.compose.ui.geometry.Offset(20f, 5f))
        }
        compose.runOnIdle {
            assertNotNull(staged)
            assertTrue(staged!!.safe.width > model.safe.width)
            assertTrue(staged!!.output.contains(staged!!.safe))
            assertEquals(model.key, staged!!.key)
        }
        capture(root, "cluster-layout-editor.png")
        compose.onNodeWithTag("clusterResetAutomatic").revealClusterEditorControl().performClick()
        compose.runOnIdle { assertEquals(1, resets) }
    }

    @Test fun explicitPreviewStopsAtItsUiDeadlineWithoutReconnecting() {
        val profile = ClusterDisplayProfiles.AEGEAN_ORANGE_239
        val model = ClusterLayoutModel(ClusterLayoutKey(profile.vehicle, profile.role, profile.panel),
            profile.panel.bounds, profile.output, profile.safe)
        var stops = 0
        val bindings = clusterSettingsFixture().copy(layout = model, automaticLayout = model,
            onStartPreview = { _, seconds -> assertEquals(15, seconds) }, onStopPreview = { stops++ })
        compose.setContent { MaterialTheme { ClusterLayoutEditor(bindings, {}) } }
        compose.onNodeWithTag("clusterPreviewStart").revealClusterEditorControl().performClick()
        compose.mainClock.autoAdvance = false
        compose.mainClock.advanceTimeBy(15032)
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(1, stops) }
        compose.onNodeWithTag("clusterPreviewStart").assertExists()
    }

    @Test fun failedPreviewStartKeepsEditorUsableAndReportsNoActiveMap() {
        val profile = ClusterDisplayProfiles.AEGEAN_ORANGE_239
        val model = ClusterLayoutModel(ClusterLayoutKey(profile.vehicle, profile.role, profile.panel),
            profile.panel.bounds, profile.output, profile.safe)
        val bindings = clusterSettingsFixture().copy(layout = model, automaticLayout = model,
            onStartPreview = { _, _ -> throw IllegalStateException("fixture unavailable") })
        compose.setContent { MaterialTheme { ClusterLayoutEditor(bindings, {}) } }
        compose.onNodeWithTag("clusterPreviewStart").revealClusterEditorControl().performClick()
        compose.onNodeWithText("Preview unavailable — main display continues").revealClusterEditorControl().assertIsDisplayed()
        compose.onNodeWithTag("clusterPreviewStart").assertExists()
    }

    @Test fun sharedSaveBlocksCarPlayEditsUntilLayoutAcknowledges() {
        val vm = SettingsViewModel(RuntimeEnvironment.getApplication())
        var scope: kotlinx.coroutines.CoroutineScope? = null
        val entered = kotlinx.coroutines.CompletableDeferred<Unit>()
        val ack = kotlinx.coroutines.CompletableDeferred<Boolean>()
        var result: Boolean? = null
        compose.setContent { scope = rememberCoroutineScope(); MaterialTheme { CarPlaySettingsSection(vm) } }
        compose.waitUntil(5000) { vm.clusterLoaded.value && vm.carPlaySettingsLoaded.value }
        compose.runOnIdle {
            scope!!.launch { result = vm.commitSettings { entered.complete(Unit); ack.await() } }
        }
        compose.waitUntil(5000) { entered.isCompleted }
        compose.runOnIdle {
            vm.updateCarPlayDraft { it.copy(ssid = "must-not-be-accepted") }
            vm.updateCarPlayPassword("must-not-be-accepted")
        }
        compose.waitForIdle()
        compose.onNodeWithTag("carPlayEnabled").assertIsNotEnabled()
        compose.runOnIdle {
            assertNotEquals("must-not-be-accepted", vm.carPlaySettings.value.ssid)
            assertNull(vm.pendingCarPlayPassword())
            ack.complete(true)
        }
        compose.waitUntil(5000) { result != null }
        assertEquals(true, result)
    }

    @Test fun stagedMapThenOffIsSavedAsOneRecordAndRestoredByFreshViewModel() {
        val prefs = com.openautolink.app.data.AppPreferences.getInstance(RuntimeEnvironment.getApplication())
        val original = kotlinx.coroutines.runBlocking { prefs.getClusterOutputPreferences() }
        try {
        val first = SettingsViewModel(RuntimeEnvironment.getApplication())
        var scope: kotlinx.coroutines.CoroutineScope? = null
        var result: Boolean? = null
        compose.setContent { scope = rememberCoroutineScope() }
        compose.waitUntil(5000) { first.clusterLoaded.value && first.carPlaySettingsLoaded.value }
        compose.runOnIdle {
            first.chooseClusterDisplay(ClusterDisplayMode.MAP_VIDEO)
            first.setClusterEnabled(false)
            scope!!.launch { result = first.commitSettings { true } }
        }
        compose.waitUntil(5000) { result != null }
        assertEquals(true, result)
        val record = kotlinx.coroutines.runBlocking { prefs.getClusterOutputPreferences() }
        assertEquals(ClusterDisplayMode.OFF, record.selection.mode)
        assertEquals(ClusterDisplayMode.MAP_VIDEO, record.lastEnabledClusterMode)
        lateinit var fresh: SettingsViewModel
        compose.runOnIdle { fresh = SettingsViewModel(RuntimeEnvironment.getApplication()) }
        compose.waitUntil(5000) {
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            fresh.clusterLoaded.value
        }
        compose.runOnIdle { fresh.setClusterEnabled(true) }
        compose.waitUntil(5000) { fresh.clusterSelection.value.mode != ClusterDisplayMode.OFF }
        assertEquals(ClusterDisplayMode.MAP_VIDEO, fresh.clusterSelection.value.mode)
        } finally { kotlinx.coroutines.runBlocking { prefs.updateClusterOutputState { original } } }
    }

    @Test fun failedSharedSavePreservesInvalidDraftAndPasswordForRetry() {
        val vm = SettingsViewModel(RuntimeEnvironment.getApplication())
        var scope: kotlinx.coroutines.CoroutineScope? = null
        var result: Boolean? = null
        compose.setContent { scope = rememberCoroutineScope() }
        compose.waitUntil(5000) { vm.clusterLoaded.value && vm.carPlaySettingsLoaded.value }
        compose.runOnIdle {
            vm.updateCarPlayDraft { it.copy(widthPhysicalMm = 99, ssid = "retry-draft") }
            vm.updateCarPlayPassword("retry-secret")
            scope!!.launch { result = vm.commitSettings { fail("Invalid CarPlay draft must not reach layout persistence"); true } }
        }
        compose.waitUntil(5000) { result != null }
        compose.runOnIdle {
            assertEquals(false, result)
            assertEquals(99, vm.carPlaySettings.value.widthPhysicalMm)
            assertEquals("retry-draft", vm.carPlaySettings.value.ssid)
            assertEquals("retry-secret", vm.pendingCarPlayPassword())
            assertFalse(vm.clusterSaving.value)
            assertFalse(vm.carPlaySettingsSaving.value)
        }
    }

    @Test fun previewBindingReplacementStopsOnlyOriginalOwner() {
        val p = ClusterDisplayProfiles.AEGEAN_ORANGE_239
        val model = ClusterLayoutModel(ClusterLayoutKey(p.vehicle, p.role, p.panel), p.panel.bounds, p.output, p.safe)
        var aStops = 0
        var bStops = 0
        var open by mutableStateOf(true)
        var bindings by mutableStateOf(clusterSettingsFixture().copy(layout = model,
            onStartPreview = { _, _ -> }, onStopPreview = { aStops++ }))
        compose.setContent { if (open) MaterialTheme { ClusterLayoutEditor(bindings, {}) } }
        compose.onNodeWithTag("clusterPreviewStart").revealClusterEditorControl().performClick()
        compose.runOnIdle { bindings = clusterSettingsFixture().copy(layout = model, onStopPreview = { bStops++ }) }
        compose.runOnIdle { assertEquals(1, aStops); assertEquals(0, bStops); open = false }
        compose.runOnIdle { assertEquals(1, aStops); assertEquals(0, bStops) }
    }

    @Test fun dragMoveAfterOwnerReplacementStopsCurrentPreviewAndStagesWithCurrentCallback() {
        dragAfterOwnerReplacementStopsCurrentPreview(resize = false)
    }

    @Test fun dragResizeAfterOwnerReplacementStopsCurrentPreviewAndStagesWithCurrentCallback() {
        dragAfterOwnerReplacementStopsCurrentPreview(resize = true)
    }

    private fun dragAfterOwnerReplacementStopsCurrentPreview(resize: Boolean) {
        val p = ClusterDisplayProfiles.AEGEAN_ORANGE_239
        val model = ClusterLayoutModel(ClusterLayoutKey(p.vehicle, p.role, p.panel), p.panel.bounds, p.output, p.safe)
        val aStages = mutableListOf<ClusterLayoutModel>()
        val bStages = mutableListOf<ClusterLayoutModel>()
        var aStops = 0
        var bStops = 0
        var bActive = false
        var open by mutableStateOf(true)
        var bindings by mutableStateOf(clusterSettingsFixture().copy(layout = model,
            onStageLayout = { aStages += it }, onStartPreview = { _, _ -> }, onStopPreview = { aStops++ }))
        compose.setContent { if (open) MaterialTheme { ClusterLayoutEditor(bindings, {}) } }
        if (resize) compose.onNodeWithTag("clusterDragResize").revealClusterEditorControl().performClick()
        fun drag() {
            compose.onNodeWithTag("clusterLayoutCanvas").revealClusterEditorControl().performTouchInput {
                swipe(center, center + androidx.compose.ui.geometry.Offset(20f, 5f))
            }
        }
        // Execute the lazy pointer coroutine under A, keeping its key/target/mode unchanged for B.
        drag()
        compose.runOnIdle { assertTrue(aStages.isNotEmpty()) }
        compose.onNodeWithTag("clusterPreviewStart").revealClusterEditorControl().performClick()
        compose.runOnIdle {
            bindings = clusterSettingsFixture().copy(layout = model,
                onStageLayout = { assertFalse("Preview B must stop before staging", bActive); bStages += it },
                onStartPreview = { _, _ -> bActive = true }, onStopPreview = { bStops++; bActive = false })
        }
        compose.runOnIdle { assertEquals(1, aStops); assertEquals(0, bStops) }
        compose.onNodeWithTag("clusterPreviewStart").revealClusterEditorControl().performClick()
        var before: ClusterLayoutModel? = null
        var aStageCount = 0
        compose.runOnIdle {
            assertTrue(bActive)
            before = aStages.last()
            aStageCount = aStages.size
        }
        drag()
        compose.runOnIdle {
            assertEquals("Drag must stop the exact current preview owner", 1, bStops)
            assertFalse(bActive)
            assertEquals("A must not receive a stale extra stop", 1, aStops)
            assertEquals("Old stage callback must not run", aStageCount, aStages.size)
            assertTrue(bStages.isNotEmpty())
            val next = bStages.last()
            assertEquals(model.key, next.key)
            assertTrue(next.key.panel.bounds.contains(next.canvas))
            assertTrue(next.canvas.contains(next.output))
            assertTrue(next.output.contains(next.safe))
            if (resize) assertTrue(next.safe.width > before!!.safe.width)
            else assertTrue(next.safe.left > before!!.safe.left)
            open = false
        }
        compose.runOnIdle { assertEquals(1, aStops); assertEquals(1, bStops) }
    }

    @Test fun previewGeometryReplacementStopsOriginalLease() {
        val p = ClusterDisplayProfiles.AEGEAN_ORANGE_239
        val model = ClusterLayoutModel(ClusterLayoutKey(p.vehicle, p.role, p.panel), p.panel.bounds, p.output, p.safe)
        var stops = 0
        var bindings by mutableStateOf(clusterSettingsFixture().copy(layout = model,
            onStartPreview = { _, _ -> }, onStopPreview = { stops++ }))
        compose.setContent { MaterialTheme { ClusterLayoutEditor(bindings, {}) } }
        compose.onNodeWithTag("clusterPreviewStart").revealClusterEditorControl().performClick()
        compose.runOnIdle { bindings = bindings.copy(layout = model.adjust(ClusterLayoutTarget.SAFE, x = model.safe.left + 1)) }
        compose.runOnIdle { assertEquals(1, stops) }
    }

    @Test fun externallyActivePreviewStopsItsBindingOwnerOnDisposal() {
        var stops = 0
        var open by mutableStateOf(true)
        val p = ClusterDisplayProfiles.AEGEAN_ORANGE_239
        val model = ClusterLayoutModel(ClusterLayoutKey(p.vehicle, p.role, p.panel), p.panel.bounds, p.output, p.safe)
        val bindings = clusterSettingsFixture().copy(layout = model, previewActive = true, onStopPreview = { stops++ })
        compose.setContent { if (open) MaterialTheme { ClusterLayoutEditor(bindings, {}) } }
        compose.runOnIdle { open = false }
        compose.runOnIdle { assertEquals(1, stops) }
    }

    @Test fun switchesExposeActionableNamesAndNudgesIdentifyTargetAxis() {
        compose.setContent { MaterialTheme {
            InstrumentClusterSettings(ClusterOutputSelection(mode = ClusterDisplayMode.MAP_VIDEO), "auto",
                clusterSettingsFixture(), true, {}, {}, {}, {}, {})
        } }
        for (name in listOf("Driver-display video")) {
            compose.onNode(hasContentDescription(name) and hasClickAction()).performScrollTo().assertExists()
        }
    }

    @Test fun nudgeExposesTargetAxisAndAction() {
        val p = ClusterDisplayProfiles.AEGEAN_ORANGE_239
        val model = ClusterLayoutModel(ClusterLayoutKey(p.vehicle, p.role, p.panel), p.panel.bounds, p.output, p.safe)
        compose.setContent { MaterialTheme { ClusterLayoutEditor(clusterSettingsFixture().copy(layout = model), {}) } }
        compose.onNode(hasContentDescription("Important content X +1") and hasClickAction()).revealClusterEditorControl().assertExists()
        compose.onNodeWithText("Map output").revealClusterEditorControl().performClick()
        compose.onNode(hasContentDescription("Map output X +1") and hasClickAction()).revealClusterEditorControl().assertExists()
    }

    private fun capture(root: android.view.View?, name: String) {
        compose.runOnIdle {
            val view = requireNotNull(root)
            val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(bitmap))
            val directory = File(System.getProperty("cluster.ui.render.dir", "build/cluster-ui-renders")).apply { mkdirs() }
            File(directory, name).outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            assertTrue(bitmap.width > 0 && bitmap.height > 0)
        }
    }
}

/** Pinned editor actions need no scroll ancestor; narrow body controls still scroll normally. */
internal fun SemanticsNodeInteraction.revealClusterEditorControl(): SemanticsNodeInteraction {
    val scrolls = generateSequence(fetchSemanticsNode().parent) { it.parent }
        .any { it.config.contains(androidx.compose.ui.semantics.SemanticsActions.ScrollBy) }
    return if (scrolls) performScrollTo() else assertIsDisplayed()
}
