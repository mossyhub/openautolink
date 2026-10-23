package com.openautolink.app.media

import android.app.Application
import android.content.Context
import android.support.v4.media.session.MediaControllerCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

@RunWith(RobolectricTestRunner::class)
@Config(application=Application::class,sdk=[32,34],manifest=Config.NONE)
@ConscryptMode(ConscryptMode.Mode.OFF)
class CarPlayInactivePublicationTest {
    private val app: Context get() = RuntimeEnvironment.getApplication()
    private var browser: org.robolectric.android.controller.ServiceController<OalMediaBrowserService>? = null
    @After fun cleanup() { browser?.destroy();OalMediaBrowserService.mediaSessionToken=null;OalMediaSessionManager.instanceOrNull()?.release() }
    @Test fun coldAttemptCanInitializeMediaSessionFromCoordinatorWorkerWithoutALooper() {
        val worker=java.util.concurrent.Executors.newSingleThreadExecutor()
        val manager=OalMediaSessionManager.getInstance(app)
        val lease=com.openautolink.app.carplay.CarPlayHostMediaLease(com.openautolink.app.carplay.CarPlayHostMediaMode.INACTIVE)
        try {
            val failure=runCatching { worker.submit { manager.beginCarPlayHostMedia(lease) }.get(5,java.util.concurrent.TimeUnit.SECONDS) }.exceptionOrNull()
            assertNull("cold coordinator-worker initialization must be valid: ${failure?.cause?.stackTraceToString()}",failure)
            manager.finishCarPlayHostMedia(lease)
        } finally { worker.shutdownNow() }
    }

    @Test fun inactiveLeaseWithholdsActualHostMediaAndArtButKeepsNavigationAndAllCommands() {
        val manager=OalMediaSessionManager.getInstance(app).also { it.initialize() }
        val session=manager.javaClass.getDeclaredField("mediaSession").apply { isAccessible=true }.get(manager) as MediaSessionCompat
        val lease=com.openautolink.app.carplay.CarPlayHostMediaLease(com.openautolink.app.carplay.CarPlayHostMediaMode.INACTIVE)
        manager.beginCarPlayHostMedia(lease)
        val prefs=io.mockk.mockk<com.openautolink.app.data.AppPreferences>()
        io.mockk.mockkObject(com.openautolink.app.data.AppPreferences.Companion)
        io.mockk.every { com.openautolink.app.data.AppPreferences.getInstance(any()) } returns prefs
        io.mockk.every { prefs.clusterNavigation } returns kotlinx.coroutines.flow.flowOf(false)
        io.mockk.every { prefs.distanceUnits } returns kotlinx.coroutines.flow.flowOf("auto")
        val sent=mutableListOf<Int>()
        val host=com.openautolink.app.carplay.OalCarPlayMetadataHost(app) { sent.add(it);true }
        val native=MediaControllerCompat(app,manager.getSessionToken()!!)
        try {
            assertTrue(host.start())
            for (playing in listOf(true,false,null)) {
                host.publish(com.openautolink.carplaylab.metadata.CarPlayMediaMetadata(title="must not publish",playing=playing),null,byteArrayOf(1,2,3))
                assertEquals("no phone media state may escape inactive boundary",PlaybackStateCompat.STATE_NONE,native.playbackState!!.state)
                assertNotEquals("must not publish",native.metadata?.getString(android.support.v4.media.MediaMetadataCompat.METADATA_KEY_TITLE))
            }
            val implementation=session.javaClass.getDeclaredField("mImpl").apply { isAccessible=true }.get(session)
            val callback=implementation.javaClass.methods.first { it.name=="getCallback" }.apply { isAccessible=true }.invoke(implementation) as MediaSessionCompat.Callback
            callback.onPlay();callback.onPause();callback.onStop();callback.onSkipToNext();callback.onSkipToPrevious()
            assertEquals(listOf(1,2,2,4,5),sent)
            host.close()
            val aa=mutableListOf<GmMediaControlPolicy.Command>()
            manager.mediaControlCallback=object:OalMediaSessionManager.MediaControlCallback {
                override fun onCommand(command:GmMediaControlPolicy.Command) { aa.add(command) }
            }
            callback.onPlay();callback.onStop()
            assertTrue("AA callback remains excluded until full attempt retirement",aa.isEmpty())
            manager.updatePlaybackState(true,999)
            assertFalse("host retirement is NOT attempt retirement",session.isActive)
            assertEquals(PlaybackStateCompat.STATE_NONE,native.playbackState!!.state)
            manager.finishCarPlayHostMedia(lease)
            assertTrue(session.isActive)
        } finally { host.close();manager.finishCarPlayHostMedia(lease);io.mockk.unmockkAll() }
    }

    @Test fun exactAttemptExcludesAaUntilExplicitRestorationOnTheSameToken() {
        val manager=OalMediaSessionManager.getInstance(app).also { it.initialize() }
        val token=manager.getSessionToken()!!
        OalMediaBrowserService.updateSessionToken(token)
        browser=org.robolectric.Robolectric.buildService(OalMediaBrowserService::class.java).create()
        assertEquals(token,browser!!.get().sessionToken)
        val controller=MediaControllerCompat(app,token)
        val art=java.io.ByteArrayOutputStream().also {
            android.graphics.Bitmap.createBitmap(2,2,android.graphics.Bitmap.Config.ARGB_8888)
                .compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)
        }.toByteArray()
        manager.updateMetadata("Old private title","artist",null,null,android.util.Base64.encodeToString(art,android.util.Base64.NO_WRAP))
        val cache=manager.javaClass.getDeclaredField("cachedBitmap").apply { isAccessible=true }
        assertNotNull("fixture must actually install old artwork",cache.get(manager))
        manager.updatePlaybackState(true,123)
        val begin=manager.javaClass.methods.firstOrNull { it.name=="beginCarPlayHostMedia" }
        assertNotNull("exact-attempt inactive boundary must exist before bootstrap",begin)
        val modeType=Class.forName("com.openautolink.app.carplay.CarPlayHostMediaMode")
        val mode=modeType.enumConstants.first { it.toString()=="INACTIVE" }
        val leaseType=Class.forName("com.openautolink.app.carplay.CarPlayHostMediaLease")
        val lease=leaseType.getConstructor(modeType).newInstance(mode)
        begin!!.invoke(manager,lease)
        val session=manager.javaClass.getDeclaredField("mediaSession").apply { isAccessible=true }.get(manager) as MediaSessionCompat
        assertFalse(session.isActive)
        assertNull("manager boundary must clear old bitmap before factory",cache.get(manager))
        assertEquals(token,browser!!.get().sessionToken)
        assertEquals(PlaybackStateCompat.STATE_NONE,controller.playbackState!!.state)
        assertNotEquals("Old private title",controller.metadata?.getString(android.support.v4.media.MediaMetadataCompat.METADATA_KEY_TITLE))
        manager.initialize();manager.resetToIdle();manager.updateMetadata("AA must wait",null,null,null,null);manager.updatePlaybackState(true,999)
        assertFalse(session.isActive)
        assertEquals(PlaybackStateCompat.STATE_NONE,controller.playbackState!!.state)
        assertEquals(token,manager.getSessionToken())
        manager.javaClass.getMethod("finishCarPlayHostMedia",leaseType).invoke(manager,lease)
        assertTrue(session.isActive)
        manager.updateMetadata("Current AA",null,null,null,null);manager.updatePlaybackState(true,456)
        assertEquals(PlaybackStateCompat.STATE_PLAYING,controller.playbackState!!.state)
        assertEquals(token,manager.getSessionToken())
    }
}
