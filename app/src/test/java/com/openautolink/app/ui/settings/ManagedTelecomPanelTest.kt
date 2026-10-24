package com.openautolink.app.ui.settings

import android.telecom.TelecomManager
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import com.openautolink.app.ui.testing.createOalComposeRule
import com.openautolink.carplaylab.telecom.ManagedTelecomExperiment
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@org.robolectric.annotation.ConscryptMode(org.robolectric.annotation.ConscryptMode.Mode.OFF)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32, 34], application = android.app.Application::class)
class ManagedTelecomPanelTest {
    @get:Rule val compose = createOalComposeRule()
    private val app get() = RuntimeEnvironment.getApplication()
    private val experiment get() = ManagedTelecomExperiment.get(app)
    @Before fun reset() { experiment.disable() }
    @After fun close() { experiment.disable() }
    private fun click(tag: String) {
        compose.onNodeWithTag(tag).performScrollTo().performClick()
        compose.waitUntil(timeoutMillis = 10000) {
            compose.onNodeWithTag("telecomStatus").fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsProperties.Text].none { it.text == "WORKING" }
        }
    }
    @Test fun simpleTogglesRegisterOnlyOnEnableAndKeepSystemAccountEnableSeparate() {
        compose.setContent { MaterialTheme { Column(Modifier.verticalScroll(rememberScrollState())) { ManagedTelecomPanel() } } }
        compose.waitForIdle()
        val manager = app.getSystemService(TelecomManager::class.java)
        assertNull(manager.getPhoneAccount(experiment.handle))
        compose.onNodeWithTag("telecomDisclosureConsent").assertDoesNotExist()
        compose.onNodeWithTag("telecomControlsConsent").assertDoesNotExist()
        compose.onNodeWithTag("telecomEnabled").assertIsOff()
        compose.onNodeWithTag("telecomControlsEnabled").assertIsNotEnabled()
        click("telecomEnabled")
        assertNotNull(manager.getPhoneAccount(experiment.handle))
        assertFalse(experiment.armed)
        compose.onNodeWithTag("telecomEnabled").assertIsOn()
        shadowOf(manager).enablePhoneAccount(experiment.handle, true) // simulated OS user action
        click("telecomReadback")
        assertTrue(experiment.armed)
        assertFalse(experiment.nativeControlsEnabled)
        click("telecomControlsEnabled")
        assertTrue(experiment.nativeControlsEnabled)
        compose.onNodeWithTag("telecomControlsEnabled").assertIsOn()
        click("telecomControlsEnabled")
        assertFalse(experiment.nativeControlsEnabled)
        click("telecomEnabled")
        assertFalse(experiment.armed)
        assertNull(manager.getPhoneAccount(experiment.handle))
        compose.onNodeWithTag("telecomEnabled").assertIsOff()
    }
    @Test fun settingsNeverLaunchFromCompositionOrWithoutProcessConsent() {
        compose.setContent { MaterialTheme { Column(Modifier.verticalScroll(rememberScrollState())) { ManagedTelecomPanel() } } }
        compose.waitForIdle()
        // Compose itself launches its test Activity; drain that known host before this assertion.
        while (shadowOf(app).nextStartedActivity != null) { }
        click("telecomEnableSettings")
        assertNull(shadowOf(app).nextStartedActivity)
        assertNull(app.getSystemService(TelecomManager::class.java).getPhoneAccount(experiment.handle))
    }
}
