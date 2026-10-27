package com.openautolink.app.ui.settings

import android.app.Application
import android.graphics.Bitmap
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.openautolink.app.ui.testing.createOalComposeRule
import com.openautolink.app.ui.theme.OpenAutoLinkTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@org.robolectric.annotation.ConscryptMode(org.robolectric.annotation.ConscryptMode.Mode.OFF)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [32, 34], qualifiers = "w1000dp-h700dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CarPlayCallSettingsTest {
    @get:Rule val compose = createOalComposeRule()
    private lateinit var rootView: android.view.View

    @Composable private fun captureView() {
        val view = androidx.compose.ui.platform.LocalView.current
        SideEffect { rootView = view.rootView }
    }

    @Test fun leavingCallPageCancelsOnlyPendingUiSetupAndUsesLatestBinding() {
        var shown by mutableStateOf(true)
        var generation by mutableIntStateOf(1)
        val disposed = mutableListOf<Int>()
        compose.setContent { MaterialTheme {
            if (shown) CarPlayCallSettings(bindings(CarPlayCallUiState(preferencesLoaded = true), mutableListOf())
                .copy(onCallSetupHidden = { disposed.add(generation) }))
        } }
        compose.runOnIdle { generation = 2 }
        compose.waitForIdle()
        compose.runOnIdle { shown = false }
        compose.waitForIdle()
        assertEquals(listOf(2), disposed)
    }

    @Test fun actionsReachSuppliedBackendAndDisplayedValuesWaitForReadback() {
        var state by mutableStateOf(CarPlayCallUiState(preferencesLoaded = true, setup = CarPlayCallSetup.SETUP_REQUIRED))
        val events = mutableListOf<String>()
        compose.setContent { MaterialTheme {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                CarPlayCallSettings(bindings(state, events))
            }
        } }
        assertTrue("Composition never changes backend intent or opens setup", events.isEmpty())
        compose.onNodeWithTag("callHandler_CARPLAY").assertIsSelected()
        compose.onNodeWithTag("extraCarRingtone").assertIsOff().performClick()
        compose.onNodeWithTag("extraCarRingtone").assertIsOff() // no invented optimistic save
        compose.onNodeWithTag("callSetup").performClick()
        compose.onNodeWithTag("androidCallSettings").performScrollTo().performClick()
        compose.onNodeWithTag("androidSoundSettings").performScrollTo().performClick()
        compose.onNodeWithTag("callHandler_CAR_BLUETOOTH").performScrollTo().performClick()
        compose.runOnIdle {
            assertEquals(listOf("ring:true", "setup", "calls", "sound", "handler:CAR_BLUETOOTH"), events)
            state = state.copy(handler = CarPlayCallHandler.CAR_BLUETOOTH, appliesToNextCall = true)
        }
        compose.onNodeWithTag("callHandler_CAR_BLUETOOTH").assertIsSelected()
        compose.onNodeWithTag("callNextCall").assertExists()
        compose.onNodeWithTag("callSetup").assertDoesNotExist()
        compose.onNodeWithTag("extraCarRingtone").assertDoesNotExist()
    }

    @Test fun extraRingtoneExplainsOptionalVehicleRingOnlyForCarPlay() {
        var state by mutableStateOf(CarPlayCallUiState(preferencesLoaded = true,
            setup = CarPlayCallSetup.SETUP_REQUIRED))
        val events = mutableListOf<String>()
        compose.setContent { MaterialTheme { Column(Modifier.verticalScroll(rememberScrollState())) {
            CarPlayCallSettings(bindings(state, events))
        } } }
        compose.onNodeWithTag("extraCarRingtoneHelper").performScrollTo()
            .assertIsDisplayed().assertTextEquals("Adds a vehicle ringtone alongside CarPlay.")
        compose.onNodeWithTag("extraCarRingtone").assertIsOff()
        compose.runOnIdle {
            assertTrue("Reading guidance must not change intent", events.isEmpty())
            state = state.copy(handler = CarPlayCallHandler.CAR_BLUETOOTH)
        }
        compose.onNodeWithTag("extraCarRingtoneHelper").assertDoesNotExist()
        compose.onNodeWithTag("extraCarRingtone").assertDoesNotExist()
        compose.onNodeWithTag("carPlayBluetoothPrerequisite").assertDoesNotExist()
    }

    @Test fun missingMicrophoneAndFrozenInputUseOneContextualActionWithoutArming() {
        var invoked = 0
        val bindings = bindings(CarPlayCallUiState(preferencesLoaded = true), mutableListOf()).copy(
            microphoneMessage = "Reconnect CarPlay when not in a call to enable microphone input.",
            microphoneAction = "Reconnect CarPlay", onMicrophoneAction = { invoked++ })
        compose.setContent { MaterialTheme { Column(Modifier.verticalScroll(rememberScrollState())) {
            AudioMicrophoneSettings("car", {}, bindings)
        } } }
        compose.onNodeWithTag("carPlayMicrophoneStatus").assertExists()
        compose.onNodeWithTag("carPlayMicrophoneAction").performScrollTo().performClick()
        assertEquals(1, invoked)
        compose.onNodeWithText("Arm").assertDoesNotExist()
        compose.onNodeWithText("Save changes").assertDoesNotExist()
    }

    @Test fun bluetoothPrerequisiteRemainsVisibleWhenSetupOrSaveFails() {
        val events = mutableListOf<String>()
        compose.setContent { MaterialTheme { Column {
            CarPlayCallSettings(bindings(CarPlayCallUiState(preferencesLoaded = true,
                setup = CarPlayCallSetup.SETUP_REQUIRED, message = "Setting was not saved."), events))
        } } }
        compose.onNodeWithTag("carPlayBluetoothPrerequisite").assertExists()
        compose.onNodeWithText("Setting was not saved.").assertExists()
        compose.onNodeWithTag("callSetup").assertExists()
    }

    @Test fun accountApprovalIsOneContextActionNotInspectionOrIndependentControlToggles() {
        val events = mutableListOf<String>()
        compose.setContent { MaterialTheme { Column {
            CarPlayCallSettings(bindings(CarPlayCallUiState(preferencesLoaded = true,
                setup = CarPlayCallSetup.ENABLE_ACCOUNT), events))
        } } }
        compose.onNodeWithText("Turn on OpenAutoLink, then return.").assertExists()
        compose.onNodeWithTag("callSetup").performClick()
        assertEquals(listOf("setup"), events)
        compose.onNodeWithText("Native call integration").assertDoesNotExist()
        compose.onNodeWithText("Native Answer/Reject/End").assertDoesNotExist()
        compose.onNodeWithText("Inspect").assertDoesNotExist()
        compose.onNodeWithText("Save changes").assertDoesNotExist()
    }

    @Test fun landscapeRendersActualSettingsAndSingleSharedNativeAudioAction() {
        val vm = SettingsViewModel(RuntimeEnvironment.getApplication())
        val events = mutableListOf<String>()
        compose.setContent { OpenAutoLinkTheme(oalUiScalePercent = 100) {
            captureView()
            SettingsScreen(viewModel = vm, audioBindings = bindings(CarPlayCallUiState(
                preferencesLoaded = true, setup = CarPlayCallSetup.SETUP_REQUIRED), events))
        } }
        compose.onNodeWithTag("settingsTab_AUDIO").performClick()
        screenshot("audio-landscape-top")
        compose.onNodeWithTag("extraCarRingtoneHelper").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("carPlayBluetoothPrerequisite").assertIsDisplayed()
        compose.onNodeWithTag("callSetup").assertIsDisplayed().assertTextEquals("Set up calls")
        compose.onNodeWithTag("extraCarRingtone").assertIsOff()
        screenshot("audio-landscape-calls")
        compose.onNodeWithTag("openVehicleAudio").performScrollTo().performClick()
        assertEquals(listOf("audio"), events)
        compose.onAllNodesWithTag("openVehicleAudio").assertCountEquals(1)
        compose.onNodeWithTag("saveAndConnectButton").assertDoesNotExist()
    }

    @Test @Config(qualifiers = "w320dp-h640dp-mdpi")
    fun narrowLargeTextRemainsScrollableAndBackIsReachable() {
        val vm = SettingsViewModel(RuntimeEnvironment.getApplication())
        val events = mutableListOf<String>()
        compose.setContent { OpenAutoLinkTheme(oalUiScalePercent = 100) {
            CompositionLocalProvider(LocalDensity provides Density(1f, 1.5f)) {
                captureView()
                SettingsScreen(viewModel = vm, onBack = { events += "back" },
                    audioBindings = bindings(CarPlayCallUiState(preferencesLoaded = true,
                        setup = CarPlayCallSetup.SETUP_REQUIRED), events))
            }
        } }
        compose.onNodeWithTag("settingsTab_AUDIO").performScrollTo().performClick()
        screenshot("audio-narrow-large-text-top")
        compose.onNodeWithTag("extraCarRingtoneHelper").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("carPlayBluetoothPrerequisite").assertIsDisplayed()
        compose.onNodeWithTag("callSetup").assertIsDisplayed().assertTextEquals("Set up calls")
        compose.onNodeWithTag("extraCarRingtone").assertIsOff()
        screenshot("audio-narrow-large-text-setup")
        compose.onNodeWithTag("callSetup").assertHeightIsAtLeast(48.dp)
        compose.onNodeWithTag("volumeOffset_Assistant / Speech").performScrollTo()
        compose.onAllNodesWithText("+0%")[2].assertWidthIsAtLeast(20.dp)
        screenshot("audio-narrow-large-text-volume")
        compose.onNodeWithContentDescription("Back").assertIsDisplayed().performClick()
        assertEquals(listOf("back"), events)
    }

    private fun screenshot(name: String) {
        val file = File("build/reports/audio-settings/$name-api${android.os.Build.VERSION.SDK_INT}.png")
        file.parentFile!!.mkdirs()
        // Robolectric has no window compositor for PixelCopy/forceRedraw; NativeGraphics
        // draws the actual composed Android View tree, not a synthetic screenshot.
        compose.runOnIdle {
            val bitmap = Bitmap.createBitmap(rootView.width, rootView.height, Bitmap.Config.ARGB_8888)
            rootView.draw(android.graphics.Canvas(bitmap))
            file.outputStream().use { out -> assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)) }
        }
        assertTrue(file.length() > 0)
    }

    private fun bindings(state: CarPlayCallUiState, events: MutableList<String>) = AudioSettingsBindings(
        state, { events += "handler:$it" }, { events += "ring:$it" },
        { events += "setup" }, { events += "calls" }, { events += "sound" }, { events += "audio" })
}
