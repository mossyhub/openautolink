package com.openautolink.app.media

import android.app.Application
import android.support.v4.media.session.PlaybackStateCompat
import com.openautolink.app.carplay.*
import com.openautolink.carplaylab.metadata.CarPlayMediaMetadata
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

@RunWith(RobolectricTestRunner::class)
@Config(application=Application::class,sdk=[32,34],manifest=Config.NONE,shadows=[ColdMediaSessionShadow::class])
@ConscryptMode(ConscryptMode.Mode.OFF)
class PhoneMediaSnapshotTest {
    @After fun close() { ColdMediaSessionShadow.fault=null; OalMediaSessionManager.instanceOrNull()?.release();ColdMediaSessionShadow.sessions.clear() }
    @Test fun failedStateSetterKeepsSuccessfulSubmissionSeparateFromPhoneTruth() {
        val manager=OalMediaSessionManager.getInstance(RuntimeEnvironment.getApplication());manager.initialize()
        manager.updatePlaybackState(false,123)
        ColdMediaSessionShadow.fault="state"
        assertTrue(runCatching { manager.updatePlaybackState(true,456) }.isFailure)
        val state=manager.diagnosticSnapshot()
        assertEquals(true,state.phonePlaying)
        assertEquals(PlaybackStateCompat.STATE_PAUSED,state.submittedState)
        assertEquals(123,state.submittedPosition)
    }
    @Test fun inactiveWithholdingStillRetainsPhonePlayingTruth() {
        val manager=OalMediaSessionManager.getInstance(RuntimeEnvironment.getApplication());manager.initialize()
        val lease=CarPlayHostMediaLease(CarPlayHostMediaMode.INACTIVE);manager.beginCarPlayHostMedia(lease)
        val owner=Any();assertTrue(MetadataHostOwnership.process.claim(owner))
        try {
            assertTrue(manager.updateCarPlay(owner,CarPlayMediaMetadata(playing=true),null,true))
            val state=manager.diagnosticSnapshot()
            assertEquals(true,state.phonePlaying)
            assertEquals(PlaybackStateCompat.STATE_NONE,state.submittedState)
            assertFalse(state.sessionActive)
        } finally { MetadataHostOwnership.process.retire(owner) {};manager.finishCarPlayHostMedia(lease) }
    }
}
