package com.openautolink.app.diagnostics.gm

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import com.openautolink.app.ui.testing.createOalComposeRule
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[32,34],application=Application::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
class GmPageObserverPanelTest {
    @get:Rule val compose=createOalComposeRule()
    @Test fun monitoringDefaultsOnButNeverClaimsDeliveryAndManualOffIsDurable() {
        val context=RuntimeEnvironment.getApplication()
        compose.setContent{MaterialTheme{Column(Modifier.verticalScroll(rememberScrollState())){GmPageObserverPanel()}}}
        compose.waitUntil(5000){compose.onAllNodesWithText("Monitoring enabled; waiting for vehicle discovery").fetchSemanticsNodes().isNotEmpty()}
        compose.onNodeWithTag("gmObserverEnabled").assertIsOn().performClick()
        compose.waitUntil(5000){!GmObserverStore(context).read().enabled}
        compose.waitUntil(5000){compose.onNodeWithTag("gmObserverEnabled").fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsProperties.ToggleableState]==androidx.compose.ui.state.ToggleableState.Off}
        compose.onNodeWithTag("gmObserverEnabled").assertIsOff()
        assertFalse(GmObserverGate.qualified(context))
        assertFalse(GmObserverStore(context).read().enabled)
        assertNull(com.openautolink.app.session.SessionManager.instanceOrNull())
        compose.onNodeWithTag("gmObserverEnabled").performClick()
        compose.waitUntil(5000){GmObserverStore(context).read().enabled && GmObserverGate.qualified(context)}
        compose.waitUntil(5000){compose.onNodeWithTag("gmObserverEnabled").fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsProperties.ToggleableState]==androidx.compose.ui.state.ToggleableState.On}
        compose.onNodeWithTag("gmObserverEnabled").assertIsOn()
    }
    @Test fun receiptStillLabelsSelectionAndOwnershipUnknown() {
        val context=RuntimeEnvironment.getApplication()
        val store=GmObserverStore(context)
        assertTrue(store.receive(store.read().generation,true,1,System.currentTimeMillis()))
        compose.setContent{MaterialTheme{Column(Modifier.verticalScroll(rememberScrollState())){GmPageObserverPanel()}}}
        compose.waitUntil(5000){compose.onAllNodesWithText("Action receipts: 1; last receipt",substring=true).fetchSemanticsNodes().isNotEmpty()}
        val snapshot=GmObserverDiagnostics.snapshot(context)
        assertEquals("UNKNOWN",snapshot.getString("selectedState"))
        assertEquals("NOT_ESTABLISHED",snapshot.getString("ownership"))
        assertTrue(snapshot.has("ownAndroidUserId"))
        assertNull(com.openautolink.app.session.SessionManager.instanceOrNull())
    }
}
