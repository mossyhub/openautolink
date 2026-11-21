package com.openautolink.app.ui.settings

import android.app.Application
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import com.openautolink.app.data.CarPlaySettings
import com.openautolink.app.ui.testing.createOalComposeRule
import com.openautolink.app.ui.theme.OalUiScale
import com.openautolink.app.ui.theme.OpenAutoLinkTheme
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32, 34], application = Application::class, qualifiers = "w480dp-h640dp-port-mdpi")
@ConscryptMode(ConscryptMode.Mode.OFF)
class AppleMapsEvWideEditorTest {
    @get:Rule val compose = createOalComposeRule()

    @Test fun advancedEntryStagesOnlyAverageRejectsMalformedTextThenSavesAndResets() {
        val before = CarPlaySettings.decode("""{"evDataEnabled":true,"ssid":"kept","fps":47}""")
        var saved = before
        var writes = 0
        val draft = CarPlaySettingsDraft()
        val pending = MutableStateFlow(before)
        val saving = MutableStateFlow(false)
        val settings = mockk<SettingsViewModel>(relaxed = true) {
            every { carPlaySettings } returns pending
            every { carPlaySettingsLoaded } returns MutableStateFlow(true)
            every { carPlaySettingsSaving } returns saving
            every { updateCarPlayDraft(any()) } answers {
                draft.update(firstArg())
                pending.value = draft.preview(saved)
            }
        }
        val vehicleModel = mockk<EvEnergyModelViewModel>(relaxed = true) {
            every { state } returns MutableStateFlow(EvEnergyModelViewModel.UiState())
            every { readout } returns MutableStateFlow(EvEnergyModelViewModel.LiveReadout())
            every { events } returns MutableStateFlow(null)
            every { learnEvaluationStatus } returns MutableStateFlow(null)
        }
        fun commit(): Boolean = runBlocking {
            draft.commit { merge, _ ->
                val next = merge(saved)
                require(carPlayEditError(next) == null)
                saved = next
                writes++
            }
        }
        fun encoded(value: CarPlaySettings) = Json.parseToJsonElement(CarPlaySettings.encode(value)).jsonObject
        val oldFont = org.robolectric.RuntimeEnvironment.getApplication().resources.configuration.fontScale
        try {
            org.robolectric.RuntimeEnvironment.setFontScale(1.3f)
            compose.setContent {
                OpenAutoLinkTheme(oalUiScalePercent = 100) {
                    OalUiScale { Surface(Modifier.fillMaxSize()) { EvEnergyModelTab(vehicleModel, carPlaySettingsViewModel = settings) } }
                }
            }
            compose.onNodeWithTag("carPlayEvData").performScrollTo().assertIsOn()
            val field = compose.onNodeWithTag("carPlayEvChargeKw")
            field.assertDoesNotExist()
            verify(exactly = 0) { settings.updateCarPlayDraft(any()) }
            compose.onNodeWithTag("carPlayEvAdvanced").performScrollTo().performClick()
            compose.onNodeWithText("not peak", substring = true, ignoreCase = true).performScrollTo().assertIsDisplayed()
            compose.onNodeWithText("may break EV setup or routing", substring = true).performScrollTo().assertIsDisplayed()
            field.performScrollTo().assertIsEnabled()
            verify(exactly = 0) { settings.updateCarPlayDraft(any()) }

            for (invalid in listOf("0", "1.", "1e2", "1000.001")) {
                field.performTextReplacement(invalid)
                field.assertTextContains(invalid)
                compose.runOnIdle {
                    assertFalse("Invalid $invalid must block the existing Save", commit())
                    assertEquals(before, saved)
                    assertEquals(0, writes)
                }
            }
            field.performTextReplacement("73.125")
            compose.runOnIdle {
                assertEquals(before, saved)
                assertEquals(0, writes)
                assertEquals(encoded(before) + ("evAverageDcChargeKw" to JsonPrimitive("73.125")), encoded(pending.value).toMap())
                assertTrue(commit())
                assertEquals(1, writes)
                assertEquals(pending.value, saved)
                saving.value = true
            }
            field.assertIsNotEnabled()
            compose.runOnIdle { saving.value = false }
            compose.onNodeWithTag("carPlayEvChargeReset").performScrollTo().performClick()
            compose.runOnIdle {
                assertEquals(JsonPrimitive("73.125"), encoded(saved)["evAverageDcChargeKw"])
                assertTrue(commit())
                assertEquals(2, writes)
                assertEquals(encoded(before), encoded(saved))
            }
            verify(exactly = 0) { vehicleModel.setTuningEnabled(any()); vehicleModel.sendNow() }
        } finally {
            org.robolectric.RuntimeEnvironment.setFontScale(oldFont)
        }
    }
}
