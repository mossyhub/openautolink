package com.openautolink.app.ui.diagnostics

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import com.openautolink.app.carplay.CarPlayPlayingPublicationExperiment
import com.openautolink.app.ui.testing.createOalComposeRule
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

@RunWith(RobolectricTestRunner::class)
@Config(application=Application::class,sdk=[32],qualifiers="w800dp-h600dp-mdpi")
@ConscryptMode(ConscryptMode.Mode.OFF)
class CarPlayPlayingControlsTest {
    @get:Rule val compose=createOalComposeRule()
    @Test fun renderedArmFeedsActualHostAndDisarmOnlyRestoresTheNextOwner() {
        CarPlayPlayingPublicationExperiment.disarm()
        val app=org.robolectric.RuntimeEnvironment.getApplication()
        val manager=com.openautolink.app.media.OalMediaSessionManager.getInstance(app).also { it.initialize() }
        val token=manager.getSessionToken()!!
        val native=android.support.v4.media.session.MediaControllerCompat(app,token)
        val prefs=io.mockk.mockk<com.openautolink.app.data.AppPreferences>()
        io.mockk.mockkObject(com.openautolink.app.data.AppPreferences.Companion)
        io.mockk.every { com.openautolink.app.data.AppPreferences.getInstance(any()) } returns prefs
        io.mockk.every { prefs.clusterNavigation } returns kotlinx.coroutines.flow.flowOf(false)
        io.mockk.every { prefs.distanceUnits } returns kotlinx.coroutines.flow.flowOf("auto")
        var eligible=true
        val controller=MicrophoneDiagnosticController(FakeMicrophoneBackend(),eligibility={eligible},freshPark={true})
        var host:com.openautolink.app.carplay.OalCarPlayMetadataHost?=null
        try {
            compose.setContent { MaterialTheme { MicrophoneDiagnosticPanel(controller) } }
            compose.onNodeWithTag("cp-playing-consent").performScrollTo().performClick()
            compose.onNodeWithTag("cp-playing-arm").performScrollTo().performClick()
            compose.runOnIdle {
                host=com.openautolink.app.carplay.OalCarPlayMetadataHost(app).also { assertTrue(it.start()) }
                eligible=false;controller.refresh()
                host!!.publish(com.openautolink.carplaylab.metadata.CarPlayMediaMetadata(playing=true),null,null)
                assertEquals(android.support.v4.media.session.PlaybackStateCompat.STATE_NONE,native.playbackState!!.state)
            }
            compose.onNodeWithTag("cp-playing-arm").assertIsNotEnabled()
            compose.onNodeWithTag("cp-playing-disarm").performScrollTo().performClick()
            compose.runOnIdle {
                assertFalse(CarPlayPlayingPublicationExperiment.snapshot())
                host!!.publish(com.openautolink.carplaylab.metadata.CarPlayMediaMetadata(playing=true,title="same owner"),null,null)
                assertEquals(android.support.v4.media.session.PlaybackStateCompat.STATE_NONE,native.playbackState!!.state)
                host!!.close()
                host=com.openautolink.app.carplay.OalCarPlayMetadataHost(app).also { assertTrue(it.start()) }
                host!!.publish(com.openautolink.carplaylab.metadata.CarPlayMediaMetadata(playing=true),null,null)
                assertEquals(android.support.v4.media.session.PlaybackStateCompat.STATE_PLAYING,native.playbackState!!.state)
                assertEquals(token,manager.getSessionToken())
            }
        } finally { host?.close();manager.release();CarPlayPlayingPublicationExperiment.disarm();io.mockk.unmockkAll() }
    }

    @Test fun renderedControlDefaultsOffRechecksEligibilityAndExpiresForFutureOwnersOnly() {
        CarPlayPlayingPublicationExperiment.disarm()
        var park=true; var eligible=true
        val controller=MicrophoneDiagnosticController(FakeMicrophoneBackend(),automotive=false,
            eligibility={eligible},freshPark={park})
        try {
            compose.setContent { MaterialTheme { MicrophoneDiagnosticPanel(controller) } }
            compose.onNodeWithTag("cp-playing-arm").performScrollTo().assertIsNotEnabled()
            compose.onNodeWithText("Test: suppress native Playing status").assertExists()
            compose.onNodeWithTag("cp-playing-status").assertTextContains("OFF", substring=true)
            compose.onNodeWithTag("cp-playing-consent").performScrollTo().performClick()
            compose.onNodeWithTag("cp-playing-arm").performScrollTo().assertIsEnabled()
            compose.runOnIdle { park=false }
            compose.onNodeWithTag("cp-playing-arm").performClick()
            compose.runOnIdle { assertFalse(CarPlayPlayingPublicationExperiment.snapshot()); park=true;controller.refresh() }
            compose.onNodeWithTag("cp-playing-arm").performClick()
            compose.runOnIdle { assertTrue(CarPlayPlayingPublicationExperiment.snapshot()) }
            compose.onNodeWithTag("cp-playing-status").assertTextContains("Armed", substring=true)
            compose.runOnIdle { controller.updateForeground(false);controller.updateForeground(true) }
            compose.onNodeWithTag("cp-playing-consent").assertIsOff()
            compose.onNodeWithTag("cp-playing-arm").assertIsNotEnabled()
            compose.runOnIdle {
                org.robolectric.shadows.ShadowSystemClock.advanceBy(java.time.Duration.ofMinutes(15))
                controller.refresh()
            }
            compose.onNodeWithTag("cp-playing-status").assertTextContains("Expired", substring=true)
            compose.runOnIdle { assertFalse(CarPlayPlayingPublicationExperiment.snapshot());eligible=false;controller.refresh() }
            compose.onNodeWithTag("cp-playing-consent").performScrollTo().performClick()
            compose.onNodeWithTag("cp-playing-arm").performScrollTo().assertIsNotEnabled()
            compose.onNodeWithTag("cp-playing-reason").assertTextContains("Unavailable", substring=true)
            compose.onNodeWithTag("cp-playing-disarm").performScrollTo().performClick()
            compose.onNodeWithTag("cp-playing-status").assertTextContains("OFF", substring=true)
        } finally { CarPlayPlayingPublicationExperiment.disarm() }
    }
}
