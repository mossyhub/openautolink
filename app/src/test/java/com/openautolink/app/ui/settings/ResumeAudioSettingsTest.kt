package com.openautolink.app.ui.settings

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import com.openautolink.app.ui.testing.createOalComposeRule
import com.openautolink.app.media.*
import com.openautolink.app.carplay.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[32,34], application=Application::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
class ResumeAudioSettingsTest {
    @get:Rule val compose=createOalComposeRule()
    private val app get()=RuntimeEnvironment.getApplication()
    @After fun cleanup() { OalMediaSessionManager.instanceOrNull()?.release() }

    @Test fun audioOffersExplicitResumeWithoutOpeningUnavailableVehicleAudio() {
        val manager=OalMediaSessionManager.getInstance(app); manager.initialize()
        val lease=CarPlayHostMediaLease(CarPlayHostMediaMode.NORMAL)
        val owner=Any(); val received=mutableListOf<GmMediaControlPolicy.Command>()
        manager.beginCarPlayHostMedia(lease)
        assertTrue(MetadataHostOwnership.process.claim(owner))
        manager.installCarPlayControls(owner,object:OalMediaSessionManager.MediaControlCallback {
            override fun onCommand(command:GmMediaControlPolicy.Command) { received+=command }
        })
        manager.setCarPlayMediaReady(owner,true)
        try {
            val vm=SettingsViewModel(app)
            compose.setContent { MaterialTheme { SettingsScreen(viewModel=vm) } }
            compose.onNodeWithTag("settingsTab_AUDIO").performClick()
            compose.onNodeWithTag("resumeCarPlayAudio").performScrollTo().assertIsEnabled().performClick()
            assertEquals(listOf(GmMediaControlPolicy.Command.PLAY),received)
            assertFalse(PhonePlaybackResumeRecovery.isEnabled())
            // No source-selection launch is required for this fallback.
            assertNull(org.robolectric.Shadows.shadowOf(app).nextStartedActivity)
            manager.setCarPlayMediaReady(owner,false)
            compose.waitForIdle()
            compose.onNodeWithTag("resumeCarPlayAudio").assertIsNotEnabled()
        } finally {
            MetadataHostOwnership.process.retire(owner) { manager.retireCarPlayControls(owner) }
            manager.finishCarPlayHostMedia(lease)
        }
    }

    @Test fun capturedResumeCannotMigrateToReplacementOrUnreadyOwner() {
        val manager=OalMediaSessionManager.getInstance(app); manager.initialize()
        val method=manager.javaClass.methods.find { it.name=="captureCarPlayAudioResume" }
        assertNotNull("explicit resume must capture exact current owner",method)
        val lease=CarPlayHostMediaLease(CarPlayHostMediaMode.NORMAL)
        manager.beginCarPlayHostMedia(lease)
        val owner=Any();var calls=0
        val controls=object:OalMediaSessionManager.MediaControlCallback {
            override fun onCommand(command:GmMediaControlPolicy.Command) { assertEquals(GmMediaControlPolicy.Command.PLAY,command);calls++ }
        }
        assertTrue(MetadataHostOwnership.process.claim(owner))
        try {
            manager.installCarPlayControls(owner,controls)
            assertNull(method!!.invoke(manager))
            manager.setCarPlayMediaReady(owner,true)
            @Suppress("UNCHECKED_CAST") val resume=method.invoke(manager) as ()->Unit
            resume();assertEquals(1,calls)
            manager.setCarPlayMediaReady(owner,false);resume();assertEquals(1,calls)
            manager.setCarPlayMediaReady(owner,true)
            manager.installCarPlayControls(Any(),controls);resume();assertEquals(1,calls)
        } finally {
            MetadataHostOwnership.process.retire(owner) {}
            manager.finishCarPlayHostMedia(lease)
        }
    }
}
