package com.openautolink.app.diagnostics.live

import android.app.Application
import androidx.compose.foundation.layout.width
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import com.openautolink.app.ui.testing.createOalComposeRule
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[32,35],application=Application::class,manifest=Config.NONE)
class LiveSettingsTest {
    @get:Rule val compose=createOalComposeRule()
    @Test fun invitationEyeIsAccessibleMaskedByDefaultAndClearedOnSubmitAtNarrowWidth() {
        LiveDiagnostics.initialize(RuntimeEnvironment.getApplication());LiveDiagnostics.stop()
        compose.waitUntil(5000){LiveDiagnostics.ready.value}
        compose.setContent{MaterialTheme{androidx.compose.foundation.layout.Column(
            androidx.compose.ui.Modifier.width(320.dp).verticalScroll(rememberScrollState())
        ){LiveDiagnosticsSettings()}}}
        val input=compose.onNodeWithTag("liveDiagnosticsPairCode")
        input.performScrollTo().performTextInput("invalid-test-invitation")
        input.assert(SemanticsMatcher.keyIsDefined(androidx.compose.ui.semantics.SemanticsProperties.Password))
        val eye=compose.onNodeWithContentDescription("Show invitation")
        eye.assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp).performClick()
        input.assert(SemanticsMatcher.keyNotDefined(androidx.compose.ui.semantics.SemanticsProperties.Password))
        input.assertTextContains("invalid-test-invitation")
        compose.onNodeWithContentDescription("Hide invitation").performClick()
        input.assert(SemanticsMatcher.keyIsDefined(androidx.compose.ui.semantics.SemanticsProperties.Password))
        compose.onNodeWithContentDescription("Show invitation").performClick()
        compose.onNodeWithTag("liveDiagnosticsEnable").performScrollTo().performClick()
        input.performScrollTo().assert(SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.EditableText,androidx.compose.ui.text.AnnotatedString("")))
        input.assert(SemanticsMatcher.keyIsDefined(androidx.compose.ui.semantics.SemanticsProperties.Password))
        compose.onNodeWithContentDescription("Show invitation").assertExists()
        LiveDiagnostics.stop()
    }
    @Test fun editingServerIsDraftOnlyAndCannotEnableOrSendInvitationToDraft() {
        LiveDiagnostics.initialize(RuntimeEnvironment.getApplication());LiveDiagnostics.stop()
        compose.waitUntil(5000){LiveDiagnostics.ready.value}
        val active=LiveDiagnostics.endpoint.value
        compose.setContent{MaterialTheme{androidx.compose.foundation.layout.Column(
            androidx.compose.ui.Modifier.verticalScroll(rememberScrollState())
        ){LiveDiagnosticsSettings()}}}
        compose.onNodeWithTag("liveDiagnosticsServer").performScrollTo().performTextReplacement("https://other.example/custom/oal")
        compose.onNodeWithTag("liveDiagnosticsEnable").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag("liveDiagnosticsPairCode").assertIsNotEnabled()
        assertEquals(active,LiveDiagnostics.endpoint.value)
        compose.onNodeWithTag("liveDiagnosticsStop").performScrollTo().performClick()
        assertEquals(active,LiveDiagnostics.endpoint.value)
        assertEquals("Off",LiveDiagnostics.status.value)
    }
    @Test fun launchChoiceIsExplicitAndExplainsStopScope() {
        LiveDiagnostics.initialize(RuntimeEnvironment.getApplication());LiveDiagnostics.stop()
        compose.setContent{MaterialTheme{androidx.compose.foundation.layout.Column(
            androidx.compose.ui.Modifier.verticalScroll(rememberScrollState())
        ){LiveDiagnosticsSettings()}}}
        compose.onNodeWithTag("liveDiagnosticsAutoConnect").performScrollTo().assertIsOff()
        compose.onNodeWithText("Auto-connect on OAL launch").assertExists()
        compose.onNodeWithText("Stop pauses this run only. Turn off auto-connect to stay Off after the next launch.").assertExists()
        compose.onNodeWithTag("liveDiagnosticsAutoConnect").performClick()
        compose.waitUntil(5000){LiveDiagnostics.autoConnect.value}
        compose.onNodeWithTag("liveDiagnosticsAutoConnect").assertIsOn()
        assertTrue(LiveConsentStore(RuntimeEnvironment.getApplication()).load())
        compose.onNodeWithTag("liveDiagnosticsAutoConnect").performClick()
        compose.waitUntil(5000){!LiveDiagnostics.autoConnect.value}
        assertFalse(LiveConsentStore(RuntimeEnvironment.getApplication()).load())
    }
    @Test fun renderedOptInMissingInvitationAndStopRemainReadOnly() {
        LiveDiagnostics.initialize(RuntimeEnvironment.getApplication());LiveDiagnostics.stop()
        compose.setContent{MaterialTheme{androidx.compose.foundation.layout.Column(
            androidx.compose.ui.Modifier.verticalScroll(rememberScrollState())
        ){LiveDiagnosticsSettings()}}}
        compose.onNodeWithTag("liveDiagnosticsStatus").assertTextEquals("Status: Off")
        compose.onNodeWithTag("liveDiagnosticsEnable").performScrollTo().performClick()
        compose.waitUntil(5000){LiveDiagnostics.status.value.contains("pairing required")}
        compose.onNodeWithTag("liveDiagnosticsStop").performScrollTo().performClick()
        compose.onNodeWithTag("liveDiagnosticsStatus").assertTextEquals("Status: Off")
        assertNull(com.openautolink.app.session.SessionManager.instanceOrNull())
    }
}
