package com.openautolink.app.media

import android.app.Application
import android.content.Context
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaControllerCompat
import android.support.v4.media.session.PlaybackStateCompat
import com.openautolink.app.carplay.*
import com.openautolink.app.data.AppPreferences
import com.openautolink.carplaylab.metadata.CarPlayMediaMetadata
import io.mockk.*
import kotlinx.coroutines.flow.flowOf
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

@RunWith(RobolectricTestRunner::class)
@Config(application=Application::class,sdk=[32],manifest=Config.NONE)
@ConscryptMode(ConscryptMode.Mode.OFF)
class CarPlayPlayingSuppressionTest {
    private val app: Context get() = RuntimeEnvironment.getApplication()
    private val manager by lazy { OalMediaSessionManager.getInstance(app).also { it.initialize() } }
    private fun armIfImplemented() = CarPlayPlayingPublicationExperiment.arm()
    private fun disarmIfImplemented() = CarPlayPlayingPublicationExperiment.disarm()
    private fun session() = manager.javaClass.getDeclaredField("mediaSession").apply { isAccessible=true }
        .get(manager) as android.support.v4.media.session.MediaSessionCompat
    private fun idle() = org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
    private fun host(lines: MutableList<String> = mutableListOf(), sent: MutableList<Int> = mutableListOf()): OalCarPlayMetadataHost {
        val prefs=mockk<AppPreferences>()
        mockkObject(AppPreferences.Companion)
        every { AppPreferences.getInstance(any()) } returns prefs
        every { prefs.clusterNavigation } returns flowOf(false)
        every { prefs.distanceUnits } returns flowOf("auto")
        manager
        return OalCarPlayMetadataHost(app, lines::add) { sent.add(it); true }.also { assertTrue(it.start()) }
    }
    @After fun cleanup() { disarmIfImplemented(); OalMediaSessionManager.instanceOrNull()?.release(); unmockkAll() }
    @Test fun armedActualHostSuppressesEveryPlayingPublicationWithoutChangingPhoneTruthOrSessionIdentity() {
        armIfImplemented()
        val token=manager.getSessionToken()!!
        val controller=MediaControllerCompat(app,token)
        val sessionField=manager.javaClass.getDeclaredField("mediaSession").apply { isAccessible=true }
        val session=spyk(sessionField.get(manager) as android.support.v4.media.session.MediaSessionCompat)
        sessionField.set(manager,session)
        val metadata=mutableListOf<MediaMetadataCompat>()
        every { session.setMetadata(capture(metadata)) } answers { callOriginal() }
        val lines=mutableListOf<String>()
        val h=host(lines)
        try {
            val phone=CarPlayMediaMetadata(title="Phone title", artist="Phone artist", playing=true, elapsedMillis=123)
            val artwork=java.io.ByteArrayOutputStream().also {
                android.graphics.Bitmap.createBitmap(2,2,android.graphics.Bitmap.Config.ARGB_8888)
                    .compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)
            }.toByteArray()
            h.publish(phone,null,artwork)
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            assertEquals("CP PLAYING must be submitted as NONE",PlaybackStateCompat.STATE_NONE,controller.playbackState!!.state)
            assertEquals(true,phone.playing)
            assertEquals("Phone title",metadata.last().getString(MediaMetadataCompat.METADATA_KEY_TITLE))
            assertNotNull(metadata.last().getBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART))
            val receipts=lines.size
            h.publish(phone,null,artwork)
            assertEquals("identical retained snapshot remains deduplicated",receipts,lines.size)
            assertEquals(123L,controller.playbackState!!.position)
            assertEquals(0f,controller.playbackState!!.playbackSpeed)
            repeat(3) { h.publish(phone.copy(title="Next $it"),null,null)
                assertEquals(PlaybackStateCompat.STATE_NONE,controller.playbackState!!.state) }
            h.publish(phone.copy(playing=false),null,null)
            assertEquals(PlaybackStateCompat.STATE_PAUSED,controller.playbackState!!.state)
            h.publish(phone.copy(playing=null),null,null)
            assertEquals(PlaybackStateCompat.STATE_NONE,controller.playbackState!!.state)
            h.publish(phone,null,null)
            assertEquals(PlaybackStateCompat.STATE_NONE,controller.playbackState!!.state)
            assertEquals(token,manager.getSessionToken())
            val field=manager.javaClass.getDeclaredField("mediaSession").apply { isAccessible=true }
            assertTrue((field.get(manager) as android.support.v4.media.session.MediaSessionCompat).isActive)
            assertTrue(lines.any { "stage=playing-publication" in it && "new=none active=true mode=suppress owner=current" in it })
        } finally { h.close() }
    }

    @Test fun allFiveNativeCommandsAndBrowserTokenAreIdenticalOffAndOnAndAaReturnsAfterRetirement() {
        val token=manager.getSessionToken()!!
        OalMediaBrowserService.updateSessionToken(token)
        val browser=org.robolectric.Robolectric.buildService(OalMediaBrowserService::class.java).create()
        val controller=MediaControllerCompat(app,token)
        val aa=mutableListOf<GmMediaControlPolicy.Command>()
        manager.mediaControlCallback=object:OalMediaSessionManager.MediaControlCallback {
            override fun onCommand(command:GmMediaControlPolicy.Command) { aa.add(command) }
        }
        // Robolectric's framework-controller Binder does not dispatch transport here.
        // Invoke the actual callback installed by initialize(), not a replacement callback.
        val implementation=session().javaClass.getDeclaredField("mImpl").apply { isAccessible=true }.get(session())
        val callback=implementation.javaClass.methods.first { it.name=="getCallback" }.apply { isAccessible=true }
            .invoke(implementation) as android.support.v4.media.session.MediaSessionCompat.Callback
        fun commands() {
            callback.onPlay();callback.onPause();callback.onStop();callback.onSkipToNext();callback.onSkipToPrevious();idle()
        }
        try {
            for (armed in listOf(false,true)) {
                if (armed) armIfImplemented() else disarmIfImplemented()
                manager.updatePlaybackState(true,321)
                assertEquals(PlaybackStateCompat.STATE_PLAYING,controller.playbackState!!.state)
                val sent=mutableListOf<Int>(); val h=host(sent=sent)
                try {
                    h.publish(CarPlayMediaMetadata(playing=true),null,null)
                    assertEquals(if(armed) PlaybackStateCompat.STATE_NONE else PlaybackStateCompat.STATE_PLAYING,controller.playbackState!!.state)
                    val actions=PlaybackStateCompat.ACTION_PLAY or PlaybackStateCompat.ACTION_PAUSE or PlaybackStateCompat.ACTION_STOP or
                        PlaybackStateCompat.ACTION_SKIP_TO_NEXT or PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or PlaybackStateCompat.ACTION_PLAY_PAUSE or
                        PlaybackStateCompat.ACTION_PLAY_FROM_MEDIA_ID
                    assertEquals(actions,controller.playbackState!!.actions)
                    commands()
                    assertEquals(listOf(1,2,2,4,5),sent)
                    assertTrue(aa.isEmpty())
                    assertTrue(session().isActive)
                    assertEquals(token,browser.get().sessionToken)
                    assertEquals("oal_media_root",browser.get().onGetRoot("fixture",1,null).rootId)
                } finally { h.close() }
                commands()
                assertEquals(GmMediaControlPolicy.Command.entries.toSet(),aa.toSet())
                aa.clear()
                manager.updatePlaybackState(true,456)
                manager.updateMetadata("AA title","AA artist",null,null,null)
                assertEquals(PlaybackStateCompat.STATE_PLAYING,controller.playbackState!!.state)
                assertTrue(session().isActive)
                assertEquals(token,manager.getSessionToken())
                assertEquals(token,browser.get().sessionToken)
            }
        } finally { browser.destroy();OalMediaBrowserService.mediaSessionToken=null }
    }

    @Test fun armDisarmAndElapsedClockOnlyAffectNewExactOwnersWhileOldPublicationAndCloseAreFenced() {
        disarmIfImplemented()
        val controller=MediaControllerCompat(app,manager.getSessionToken()!!)
        val old=host()
        val phone=CarPlayMediaMetadata(playing=true,title="first")
        var next:OalCarPlayMetadataHost?=null
        try {
            armIfImplemented()
            old.publish(phone,null,null)
            assertEquals(PlaybackStateCompat.STATE_PLAYING,controller.playbackState!!.state)
            old.close()
            next=host()
            disarmIfImplemented()
            org.robolectric.shadows.ShadowSystemClock.advanceBy(java.time.Duration.ofMinutes(16))
            next.publish(phone,null,null)
            assertEquals(PlaybackStateCompat.STATE_NONE,controller.playbackState!!.state)
            old.publish(phone.copy(playing=false),null,null);old.close()
            assertEquals(PlaybackStateCompat.STATE_NONE,controller.playbackState!!.state)
            next.close();next=host()
            next.publish(phone,null,null)
            assertEquals(PlaybackStateCompat.STATE_PLAYING,controller.playbackState!!.state)
        } finally { old.close();next?.close() }
        armIfImplemented()
        org.robolectric.shadows.ShadowSystemClock.advanceBy(java.time.Duration.ofMinutes(15))
        val persistent=host()
        try {
            persistent.publish(phone,null,null)
            assertEquals("elapsed clock must not disable the until-Off process selection",
                PlaybackStateCompat.STATE_NONE,controller.playbackState!!.state)
            disarmIfImplemented()
            persistent.publish(phone.copy(title="still frozen"),null,null)
            assertEquals("explicit Off does not change an existing owner lease",
                PlaybackStateCompat.STATE_NONE,controller.playbackState!!.state)
            persistent.close()
            val normal=host()
            try {
                normal.publish(phone,null,null)
                assertEquals("explicit Off selects NORMAL for a new owner",
                    PlaybackStateCompat.STATE_PLAYING,controller.playbackState!!.state)
            } finally { normal.close() }
        } finally { persistent.close() }
    }

    @Test fun failedHostRetirementKeepsAaAndSuccessorExcludedAndOldFrozenModeUntilRetry() {
        armIfImplemented()
        val token=manager.getSessionToken()!!
        val controller=MediaControllerCompat(app,token)
        val field=manager.javaClass.getDeclaredField("mediaSession").apply { isAccessible=true }
        val session=spyk(session());field.set(manager,session)
        val h=host()
        val blocked=OalCarPlayMetadataHost(app)
        try {
            h.publish(CarPlayMediaMetadata(playing=true),null,null)
            every { session.setMetadata(any()) } throws IllegalStateException("test retirement")
            assertTrue(runCatching { h.close() }.exceptionOrNull() is IllegalStateException)
            assertFalse(blocked.start())
            manager.updatePlaybackState(true,999)
            assertEquals(PlaybackStateCompat.STATE_NONE,controller.playbackState!!.state)
            disarmIfImplemented()
            every { session.setMetadata(any()) } answers { callOriginal() }
            h.publish(CarPlayMediaMetadata(playing=true,title="late before retry"),null,null)
            assertEquals(PlaybackStateCompat.STATE_NONE,controller.playbackState!!.state)
            h.close()
            assertTrue(blocked.start())
            blocked.publish(CarPlayMediaMetadata(playing=false),null,null)
            h.publish(CarPlayMediaMetadata(playing=true,title="late after successor"),null,null);h.close()
            assertEquals(PlaybackStateCompat.STATE_PAUSED,controller.playbackState!!.state)
            blocked.close()
            manager.updatePlaybackState(true,777)
            assertEquals(PlaybackStateCompat.STATE_PLAYING,controller.playbackState!!.state)
            assertEquals(token,manager.getSessionToken());assertTrue(session.isActive)
        } finally { every { session.setMetadata(any()) } answers { callOriginal() };h.close();blocked.close() }
    }

    @Test fun failedIdleSubmissionCannotInventAPreviousSubmittedStateInBoundaryReceipt() {
        disarmIfImplemented()
        val field=manager.javaClass.getDeclaredField("mediaSession").apply { isAccessible=true }
        val session=spyk(session());field.set(manager,session)
        val lines=mutableListOf<String>();val h=host(lines)
        try {
            h.publish(CarPlayMediaMetadata(playing=true),null,null)
            every { session.setPlaybackState(any()) } throws IllegalStateException("test submit")
            assertTrue(runCatching { manager.resetHostToIdle() }.isFailure)
            every { session.setPlaybackState(any()) } answers { callOriginal() }
            h.publish(CarPlayMediaMetadata(playing=false),null,null)
            assertEquals("media stage=playing-publication previous=playing new=paused active=true mode=normal owner=current",lines.last())
        } finally { every { session.setPlaybackState(any()) } answers { callOriginal() };h.close() }
    }

    @Test fun finiteOwnPublicationReceiptsCapAt32AndCarryActualPreviousSubmittedState() {
        armIfImplemented()
        val lines=mutableListOf<String>();val h=host(lines)
        try {
            h.publish(CarPlayMediaMetadata(playing=false),null,null)
            h.publish(CarPlayMediaMetadata(playing=true),null,null)
            assertEquals("media stage=playing-publication previous=paused new=none active=true mode=suppress owner=current",lines.last())
            repeat(100) { h.publish(CarPlayMediaMetadata(playing=true,title="secret $it"),null,null) }
            assertEquals(32,lines.size)
            assertTrue(lines.all { CarPlayAudioDiagnostics.sanitize(1,it)!=null })
            assertTrue(lines.none { "secret" in it || "selected=" in it })
        } finally { h.close() }
    }
}
