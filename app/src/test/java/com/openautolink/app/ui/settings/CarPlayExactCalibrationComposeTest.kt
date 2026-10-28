package com.openautolink.app.ui.settings

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewModelScope
import com.openautolink.app.data.AppPreferences
import com.openautolink.app.data.CarPlaySettings
import com.openautolink.app.ui.testing.createOalComposeRule
import java.io.File
import java.io.IOException
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@org.robolectric.annotation.ConscryptMode(org.robolectric.annotation.ConscryptMode.Mode.OFF)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32, 34], application = Application::class)
class CarPlayExactCalibrationComposeTest {
    @get:Rule val compose = createOalComposeRule()
    private val job = SupervisorJob()
    private val owners = ViewModelStore()
    private lateinit var directory: File
    private lateinit var preferences: AppPreferences
    private lateinit var boundary: StoreBoundary
    private lateinit var vm: SettingsViewModel
    private val instance = AppPreferences::class.java.getDeclaredField("instance").apply { isAccessible = true }
    private var previous: Any? = null
    private var reconnects = 0
    private var closes = 0

    /** Fault/hold only the storage boundary; real AppPreferences, draft and DataStore do the transaction. */
    private class StoreBoundary(private val actual: DataStore<Preferences>) : DataStore<Preferences> {
        val allowRead = CompletableDeferred<Unit>()
        @Volatile var writes = 0
        @Volatile var failNextWrite = false
        @Volatile var holdWrite: CompletableDeferred<Unit>? = null
        val enteredWrite = CompletableDeferred<Unit>()
        override val data = flow { allowRead.await(); emitAll(actual.data) }
        override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences =
            actual.updateData { before ->
                writes++
                val next = transform(before)
                if (failNextWrite) { failNextWrite = false; throw IOException("fixture storage failure") }
                holdWrite?.let { gate -> holdWrite = null; enteredWrite.complete(Unit); gate.await() }
                next
            }
    }

    @Before fun setup() {
        directory = File(RuntimeEnvironment.getApplication().cacheDir,
            "exact-calibration-${java.util.UUID.randomUUID()}").apply { mkdirs() }
        val store = PreferenceDataStoreFactory.create(scope = CoroutineScope(Dispatchers.IO + job),
            produceFile = { File(directory, "settings.preferences_pb") })
        boundary = StoreBoundary(store)
        preferences = AppPreferences::class.java.getDeclaredConstructor(DataStore::class.java)
            .apply { isAccessible = true }.newInstance(boundary)
        runBlocking { preferences.setCarPlaySettings(CarPlaySettings(autoScale = false)) }
        previous = instance.get(null)
        instance.set(null, preferences)
        vm = SettingsViewModel(RuntimeEnvironment.getApplication())
        owners.put("settings", vm)
    }

    private fun mount(loaded: Boolean = true) {
        if (loaded) boundary.allowRead.complete(Unit)
        compose.setContent { MaterialTheme {
            SettingsScreen(viewModel = vm, onSaveAndConnect = {
                vm.viewModelScope.launch {
                    applySettingsAndRestart(vm::commitCarPlaySettings, { reconnects++ }, { closes++ })
                }
            })
        } }
        if (loaded) awaitCondition { vm.carPlaySettingsLoaded.value }
        compose.onNodeWithTag("settingsTab_DISPLAY").performScrollTo().performClick()
    }

    @After fun cleanup() {
        boundary.allowRead.complete(Unit)
        boundary.holdWrite?.complete(Unit)
        compose.runOnIdle { owners.clear() }
        instance.set(null, previous)
        runBlocking { job.cancelAndJoin() }
        directory.deleteRecursively()
    }

    private fun stored() = runBlocking { preferences.carPlaySettings.first() }
    private fun expandExactCalibration() = compose.onNodeWithText("Show exact calibration")
        .performScrollTo().performClick()

    @Test fun exactCalibrationInitiallyHidesTheFieldAndHelpUntilExpanded() {
        mount()
        compose.onNodeWithTag("carPlayPhysicalWidth").assertDoesNotExist()
        compose.onNodeWithText("Calibration: physical width", substring = true).assertDoesNotExist()
        expandExactCalibration()
        compose.onNodeWithTag("carPlayPhysicalWidth").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Calibration: physical width", substring = true)
            .performScrollTo().assertIsDisplayed()
        assertEquals("Disclosure must not write", 1, boundary.writes)
    }

    @Test fun collapsingAndReopeningPreservesTheExactPendingTextWithoutWriting() {
        mount(); expandExactCalibration(); enter("225")
        compose.onNodeWithText("Hide exact calibration").performScrollTo().performClick()
        compose.onNodeWithTag("carPlayPhysicalWidth").assertDoesNotExist()
        compose.onNodeWithText("Calibration: physical width", substring = true).assertDoesNotExist()
        compose.runOnIdle { assertEquals("225", vm.carPlaySettings.value.physicalWidthDraft) }
        expandExactCalibration()
        compose.onNodeWithTag("carPlayPhysicalWidth").performScrollTo().assertTextContains("225")
        assertEquals(200, stored().widthPhysicalMm)
        assertEquals("Disclosure and text edits must not write", 1, boundary.writes)
        save(); awaitSaved(225)
    }

    @Test fun invalidDraftCannotBeCollapsedAndStillBlocksGlobalSave() {
        mount(); expandExactCalibration(); enter("2.")
        compose.onNodeWithText("Hide exact calibration").performScrollTo().assertIsNotEnabled().performClick()
        compose.onNodeWithTag("carPlayPhysicalWidth").performScrollTo().assertIsDisplayed().assertTextContains("2.")
        compose.onNodeWithText("Enter a whole number from 100 to 400.").assertIsDisplayed()
        assertEquals("Invalid draft and disclosure must not write", 1, boundary.writes)
        save(); awaitError()
        compose.onNodeWithTag("carPlayPhysicalWidth").performScrollTo().assertIsDisplayed().assertTextContains("2.")
        assertEquals(200, stored().widthPhysicalMm)
        compose.runOnIdle { assertEquals(0, reconnects); assertEquals(0, closes) }
        enter("225")
        compose.onNodeWithText("Hide exact calibration").performScrollTo().assertIsEnabled().performClick()
        expandExactCalibration()
        compose.onNodeWithTag("carPlayPhysicalWidth").performScrollTo().assertTextContains("225")
        save(); awaitSaved(225)
    }
    private fun enter(raw: String) = compose.onNodeWithTag("carPlayPhysicalWidth")
        .performScrollTo().performTextReplacement(raw)
    private fun save() = compose.onNodeWithTag("saveAndConnectButton").performClick()
    private fun awaitCondition(condition: () -> Boolean) {
        compose.waitUntil(5000) {
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            condition()
        }
    }
    private fun awaitSaved(width: Int) {
        awaitCondition { stored().widthPhysicalMm == width && !vm.carPlaySettingsSaving.value }
    }
    private fun awaitError() {
        awaitCondition { vm.carPlayApplyError.value.isNotEmpty() && !vm.carPlaySettingsSaving.value }
    }

    @Test fun exactEntryUpdatesOnlyPendingUntilTheGlobalSaveTransaction() {
        mount()
        expandExactCalibration()
        assertEquals(200, stored().widthPhysicalMm)
        compose.onNodeWithText("UI calibration (mm)").performScrollTo().assertExists()
        enter("225")
        compose.runOnIdle { assertEquals(225, vm.carPlaySettings.value.widthPhysicalMm) }
        compose.onNodeWithTag("carPlayPhysicalWidth").assertTextContains("225")
        assertEquals(200, stored().widthPhysicalMm)
        assertEquals("Composition and text edits must not write", 1, boundary.writes)
        save()
        awaitSaved(225)
        compose.runOnIdle { assertEquals(1, reconnects); assertEquals(1, closes) }
        assertEquals(225, CarPlaySettings.decode(CarPlaySettings.encode(stored())).widthPhysicalMm)
        assertNull(stored().physicalWidthDraft)
    }

    @Test fun invalidPartialAndNoncanonicalInputStaysVisibleAndBlocksGlobalSave() {
        mount()
        expandExactCalibration()
        for (raw in listOf("", "2", "2.", ".", "401", "0", "-1", "225.0", "+225", "0225", " 225", "2147483648")) {
            enter(raw)
            compose.onNodeWithTag("carPlayPhysicalWidth").assert(
                SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.EditableText,
                    androidx.compose.ui.text.AnnotatedString(raw)))
            save(); awaitError()
            compose.runOnIdle {
                assertEquals(raw, vm.carPlaySettings.value.physicalWidthDraft)
                assertEquals(200, vm.carPlaySettings.value.widthPhysicalMm)
                assertNull(vm.carPlaySettings.value.videoError())
                assertEquals(0, reconnects); assertEquals(0, closes)
            }
            assertEquals(200, stored().widthPhysicalMm)
        }
        enter("225"); save(); awaitSaved(225)
    }

    @Test fun autoPreservesTypedCalibrationAndDisablesBothManualControls() {
        mount(); expandExactCalibration(); enter("225")
        compose.onNodeWithTag("carPlayAutoScale").performScrollTo().performClick()
        compose.onNodeWithTag("carPlayUiScale").assertIsNotEnabled()
        compose.onNodeWithTag("carPlayPhysicalWidth").performScrollTo().assertIsNotEnabled().assertTextContains("225")
        compose.runOnIdle { assertEquals(225, vm.carPlaySettings.value.widthPhysicalMm) }
        assertEquals(200, stored().widthPhysicalMm)
        save(); awaitSaved(225)
        assertTrue(stored().autoScale)
        compose.onNodeWithTag("carPlayAutoScale").performScrollTo().performClick()
        compose.onNodeWithTag("carPlayPhysicalWidth").performScrollTo().assertIsEnabled().assertTextContains("225")
    }

    @Test fun sliderCompletionClearsPartialTextAndExactEntrySynchronizesSameSlider() {
        mount(); expandExactCalibration(); enter("225")
        compose.onNodeWithTag("carPlayUiScale").assertRangeInfoEquals(
            androidx.compose.ui.semantics.ProgressBarRangeInfo(275f, 100f..400f, 0))
        enter("2.")
        compose.onNodeWithTag("carPlayUiScale").performScrollTo()
            .performSemanticsAction(SemanticsActions.SetProgress) { it(250f) }
        compose.runOnIdle {
            assertEquals(250, vm.carPlaySettings.value.widthPhysicalMm)
            assertNull(vm.carPlaySettings.value.physicalWidthDraft)
        }
        compose.onNodeWithTag("carPlayPhysicalWidth").performScrollTo().assertTextContains("250")
        assertEquals(200, stored().widthPhysicalMm)
        save(); awaitSaved(250)
    }

    @Test fun sliderDragChangesOnlyLocalEchoUntilFingerRelease() {
        mount()
        val slider = compose.onNodeWithTag("carPlayUiScale").performScrollTo()
        slider.performTouchInput {
            down(center)
            moveTo(Offset(width * 0.8f, centerY))
        }
        compose.runOnIdle { assertEquals(200, vm.carPlaySettings.value.widthPhysicalMm) }
        assertEquals(200, stored().widthPhysicalMm)
        assertEquals("Drag must not write", 1, boundary.writes)
        slider.performTouchInput { up() }
        compose.runOnIdle {
            assertNotEquals(200, vm.carPlaySettings.value.widthPhysicalMm)
            assertNull(vm.carPlaySettings.value.physicalWidthDraft)
        }
        assertEquals(200, stored().widthPhysicalMm)
    }

    @Test fun persistenceFailureKeepsTextAndPartialCorrectionIsNotSilentlySaved() {
        mount(); expandExactCalibration(); enter("225")
        boundary.failNextWrite = true
        save(); awaitError()
        compose.onNodeWithTag("carPlayPhysicalWidth").performScrollTo().assertTextContains("225")
        compose.runOnIdle {
            assertEquals("225", vm.carPlaySettings.value.physicalWidthDraft)
            assertEquals(0, reconnects); assertEquals(0, closes)
        }
        assertEquals(200, stored().widthPhysicalMm)
        enter("2."); save(); awaitError()
        compose.onNodeWithTag("carPlayPhysicalWidth").performScrollTo().assertTextContains("2.")
        assertEquals(200, stored().widthPhysicalMm)
        enter("225"); save(); awaitSaved(225)
    }

    @Test fun loadingAndSavingDisableControlsWithoutHidingTheCalibration() {
        mount(loaded = false)
        expandExactCalibration()
        compose.onNodeWithTag("carPlayAutoScale").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag("carPlayUiScale").assertIsNotEnabled()
        compose.onNodeWithTag("carPlayPhysicalWidth").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag("saveAndConnectButton").assertIsNotEnabled()
        boundary.allowRead.complete(Unit)
        awaitCondition { vm.carPlaySettingsLoaded.value && !vm.carPlaySettings.value.autoScale }
        enter("225")
        val release = CompletableDeferred<Unit>()
        boundary.holdWrite = release
        try {
            save()
            awaitCondition { boundary.enteredWrite.isCompleted }
            compose.onNodeWithTag("carPlayPhysicalWidth").performScrollTo().assertIsNotEnabled()
            compose.onNodeWithTag("carPlayUiScale").assertIsNotEnabled()
            compose.onNodeWithTag("carPlayAutoScale").assertIsNotEnabled()
            compose.onNodeWithTag("saveAndConnectButton").assertIsNotEnabled()
            compose.runOnIdle { assertEquals(0, reconnects); assertEquals(0, closes) }
        } finally { release.complete(Unit) }
        awaitSaved(225)
    }
}
