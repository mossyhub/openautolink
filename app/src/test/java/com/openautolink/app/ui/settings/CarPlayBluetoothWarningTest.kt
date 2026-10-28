package com.openautolink.app.ui.settings

import android.app.Application
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.text.TextLayoutResult
import com.openautolink.app.ui.testing.createOalComposeRule
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import org.robolectric.annotation.GraphicsMode

@ConscryptMode(ConscryptMode.Mode.OFF)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [32, 34], qualifiers = "w1000dp-h1000dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CarPlayBluetoothWarningTest {
    @get:Rule val compose = createOalComposeRule()

    @Test fun warningIsAdjacentAndErrorColoredBeforeAndAfterChoosingBluetooth() {
        val state = mutableStateOf(CarPlayCallUiState(preferencesLoaded = true,
            setup = CarPlayCallSetup.READY))
        val events = mutableListOf<String>()
        var errorColor = Color.Unspecified
        compose.setContent { MaterialTheme {
            errorColor = MaterialTheme.colorScheme.error
            Column(Modifier.verticalScroll(rememberScrollState())) {
                CarPlayCallSettings(bindings(state.value, events))
            }
        } }
        val warning = compose.onNodeWithText(WARNING)
        warning.assertIsDisplayed()
        compose.onNodeWithText("Recommended").assertIsDisplayed()
        compose.onNodeWithTag("callHandler_CARPLAY").assertIsSelected()
        compose.onNodeWithTag("carPlayBluetoothPrerequisite").assertIsDisplayed()
        compose.onNodeWithText("Ready").assertIsDisplayed()
        compose.onNodeWithText(REASSURANCE).assertIsDisplayed()
        assertTextColor("Car Bluetooth", errorColor)
        assertTextColor(WARNING, errorColor)
        val optionBounds = compose.onNodeWithTag("callHandler_CAR_BLUETOOTH").fetchSemanticsNode().boundsInRoot
        val warningBounds = warning.fetchSemanticsNode().boundsInRoot
        assertTrue("Warning immediately follows Bluetooth option", warningBounds.top >= optionBounds.bottom &&
            warningBounds.top <= optionBounds.bottom + 16f)
        compose.runOnIdle { assertTrue("Reading advice must not alter any backend state", events.isEmpty()) }

        compose.onNodeWithTag("callHandler_CAR_BLUETOOTH").assertIsEnabled().performClick()
        compose.onNodeWithTag("callHandler_CARPLAY").assertIsSelected() // waits for controller readback
        compose.runOnIdle {
            assertEquals(listOf("handler:CAR_BLUETOOTH"), events)
            state.value = state.value.copy(handler = CarPlayCallHandler.CAR_BLUETOOTH, appliesToNextCall = true)
        }
        compose.onNodeWithTag("callHandler_CAR_BLUETOOTH").assertIsSelected()
        warning.assertIsDisplayed()
        assertTextColor("Car Bluetooth", errorColor)
        assertTextColor(WARNING, errorColor)
        compose.onNodeWithText("Recommended").assertIsDisplayed()
        compose.onNodeWithText("Pair the iPhone with the car and enable Phone calls.").assertIsDisplayed()
        compose.onNodeWithTag("callNextCall").assertIsDisplayed()
        compose.onNodeWithTag("carPlayBluetoothPrerequisite").assertDoesNotExist()
        compose.onNodeWithTag("callSetup").assertDoesNotExist()
        compose.onNodeWithText(REASSURANCE).assertDoesNotExist()
        compose.onNodeWithTag("extraCarRingtone").assertDoesNotExist()
        compose.runOnIdle { assertEquals(listOf("handler:CAR_BLUETOOTH"), events) }
    }

    @Test fun recommendationKeepsAndroidApprovalExplicitAndNeverStartsSetupAutomatically() {
        val state = mutableStateOf(CarPlayCallUiState(preferencesLoaded = true,
            setup = CarPlayCallSetup.SETUP_REQUIRED))
        val events = mutableListOf<String>()
        compose.setContent { MaterialTheme {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                CarPlayCallSettings(bindings(state.value, events))
            }
        } }
        compose.onNodeWithText(REASSURANCE).assertIsDisplayed()
        compose.onNodeWithText("Recommended").assertIsDisplayed()
        compose.onNodeWithTag("callSetup").assertIsDisplayed().assertTextEquals("Set up calls")
        compose.onNodeWithText("Ready").assertDoesNotExist()
        val reassurance = compose.onNodeWithText(REASSURANCE).fetchSemanticsNode().boundsInRoot
        val setup = compose.onNodeWithTag("callSetup").fetchSemanticsNode().boundsInRoot
        assertTrue("Reassurance precedes the required setup action", reassurance.bottom <= setup.top)
        compose.runOnIdle { assertTrue(events.isEmpty()) }
        compose.onNodeWithTag("callSetup").performClick()
        compose.runOnIdle {
            assertEquals(listOf("setup"), events)
            state.value = state.value.copy(setup = CarPlayCallSetup.ENABLE_ACCOUNT)
        }
        compose.onNodeWithText("Turn on OpenAutoLink, then return.").assertIsDisplayed()
        compose.onNodeWithTag("callSetup").assertTextEquals("Continue to calling accounts")
        compose.onNodeWithText("Ready").assertDoesNotExist()
        compose.onNodeWithText(WARNING).assertIsDisplayed()
        compose.onNodeWithText(REASSURANCE).assertIsDisplayed()
    }

    @Test fun androidAutoMicrophoneChoicesDoNotAcquireCarPlayBluetoothAdvice() {
        val source = mutableStateOf("car")
        val events = mutableListOf<String>()
        compose.setContent { MaterialTheme {
            Column {
                AudioMicrophoneSettings(source.value, { events += it; source.value = it })
            }
        } }
        compose.onNodeWithText(WARNING).assertDoesNotExist()
        compose.onNodeWithText(REASSURANCE).assertDoesNotExist()
        compose.onNodeWithText("Recommended").assertDoesNotExist()
        compose.onNodeWithText("Car Bluetooth").assertIsDisplayed()
        compose.onNodeWithTag("micSource_phone").performClick().assertIsSelected()
        compose.runOnIdle { assertEquals(listOf("phone"), events) }
    }

    private fun assertTextColor(text: String, expected: Color) {
        val layouts = mutableListOf<TextLayoutResult>()
        compose.onNodeWithText(text, useUnmergedTree = true)
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { assertTrue(it(layouts)) }
        assertEquals("Actual Compose text layout color for $text", expected, layouts.single().layoutInput.style.color)
    }

    private fun bindings(state: CarPlayCallUiState, events: MutableList<String>) = AudioSettingsBindings(
        state, { events += "handler:$it" }, { events += "ring:$it" },
        { events += "setup" }, { events += "calls" }, { events += "sound" }, { events += "audio" })

    private companion object {
        const val REASSURANCE = "CarPlay calls still support the car microphone, steering-wheel controls and driver display/cluster. Complete call setup below."
        const val WARNING = "Warning: Bluetooth can cause repeated media pauses. Recommended: CarPlay. Unpair the iPhone from the car and leave Bluetooth disconnected."
    }
}
