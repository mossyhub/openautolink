package com.openautolink.app.media

import android.app.Application
import android.content.*
import android.os.Looper
import com.openautolink.app.carplay.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.*

@RunWith(RobolectricTestRunner::class)
@Config(application=Application::class,sdk=[32,34],manifest=Config.NONE)
@ConscryptMode(ConscryptMode.Mode.OFF)
@LooperMode(LooperMode.Mode.PAUSED)
class PhoneMediaAvailabilityTest {
    private val app get()=RuntimeEnvironment.getApplication()
    @After fun close() { OalMediaSessionManager.instanceOrNull()?.release() }
    @Test fun callbackAloneAndAllocatedGenerationZeroAreNotReadyAndStaleRetireCannotRemoveSuccessor() {
        val manager=OalMediaSessionManager.getInstance(app);manager.initialize()
        manager.mediaControlCallback=object:OalMediaSessionManager.MediaControlCallback {override fun onCommand(command:GmMediaControlPolicy.Command) {fail("browse must never send a command")}}
        assertFalse(manager.browseState().ready)
        val old=Any();manager.setAndroidAutoMediaReady(old,0);assertFalse(manager.browseState().ready)
        manager.setAndroidAutoMediaReady(old,1);assertTrue(manager.browseState().ready)
        val next=Any();manager.setAndroidAutoMediaReady(next,2);manager.retireAndroidAutoMedia(old)
        assertTrue(manager.browseState().ready);manager.retireAndroidAutoMedia(next);assertFalse(manager.browseState().ready)
    }
    @Test fun controlsAndReadyClaimWithoutAnAppliedLeaseCannotPublishAPlayableCarPlaySource() {
        val manager=OalMediaSessionManager.getInstance(app);manager.initialize()
        val owner=Any()
        manager.installCarPlayControls(owner,object:OalMediaSessionManager.MediaControlCallback {override fun onCommand(command:GmMediaControlPolicy.Command) {}})
        try {
            manager.setCarPlayMediaReady(owner,true)
            assertFalse("an applied exact attempt lease is mandatory",manager.browseState().ready)
        } finally {manager.retireCarPlayControls(owner)}
    }
    @Test fun notificationsAreCoalescedOnMainAfterOuterHostAndSessionLocksAndExactObserverCleanup() {
        val manager=OalMediaSessionManager.getInstance(app);manager.initialize()
        val sessionLock=manager.javaClass.getDeclaredField("sessionLock").apply {isAccessible=true}.get(manager)
        val observer=Any();var calls=0
        manager.observeBrowse(observer) {
            assertEquals(Looper.getMainLooper(),Looper.myLooper())
            assertFalse(Thread.holdsLock(MetadataHostOwnership.process));assertFalse(Thread.holdsLock(sessionLock));calls++
        }
        val owner=Any();assertTrue(MetadataHostOwnership.process.claim(owner))
        try {
            MetadataHostOwnership.process.publish(owner) {
                synchronized(sessionLock) {
                    manager.setAndroidAutoMediaReady(owner,1);manager.setAndroidAutoMediaReady(owner,2)
                    assertEquals(0,calls)
                }
            }
            shadowOf(Looper.getMainLooper()).idle();assertEquals(1,calls)
            manager.setAndroidAutoMediaReady(owner,3);manager.removeBrowseObserver(observer)
            shadowOf(Looper.getMainLooper()).idle();assertEquals(1,calls)
        } finally {MetadataHostOwnership.process.retire(owner) {}}
    }
    @Test fun launcherUsesPublicActionAndFlattenedComponentAndFiniteFailureResults() {
        for(result in OalMediaHostLauncher.Result.entries) {
            var requested:Intent?=null
            val context=object:ContextWrapper(app) {
                override fun startActivity(intent:Intent) {
                    requested=intent
                    when(result) {
                        OalMediaHostLauncher.Result.UNAVAILABLE -> throw ActivityNotFoundException()
                        OalMediaHostLauncher.Result.DENIED -> throw SecurityException()
                        else -> Unit
                    }
                }
            }
            assertEquals(result,OalMediaHostLauncher.launch(context))
            assertEquals("android.car.intent.action.MEDIA_TEMPLATE",requested!!.action)
            assertEquals(ComponentName(app,OalMediaBrowserService::class.java).flattenToString(),requested!!.getStringExtra("android.car.intent.extra.MEDIA_COMPONENT"))
            assertTrue(requested!!.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
        }
    }
}
