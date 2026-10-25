package com.openautolink.app.media

import android.app.Application
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Handler

import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import com.openautolink.app.carplay.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.*
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowMediaSession
import org.robolectric.util.ReflectionHelpers.ClassParameter

@RunWith(RobolectricTestRunner::class)
@Config(application=Application::class,sdk=[32,34],manifest=Config.NONE,shadows=[ColdMediaSessionShadow::class])
@ConscryptMode(ConscryptMode.Mode.OFF)
class CarPlayColdInitializationTest {
    @After fun cleanup() {
        ColdMediaSessionShadow.fault=null
        OalMediaSessionManager.instanceOrNull()?.release()
        OalMediaBrowserService.mediaSessionToken=null
        ColdMediaSessionShadow.sessions.clear()
        CarPlayPlayingPublicationExperiment.disarm()
    }

    @Test fun firstCallbackFailureRetainsInstanceAndCompletesInitializationOnRetry() = retry("callback")
    @Test fun firstStateFailureRetainsInstanceAndCompletesInitializationOnRetry() = retry("state")
    @Test fun firstMetadataFailureRetainsInstanceAndCompletesInitializationOnRetry() = retry("metadata")
    @Test fun firstActivationFailureRetainsInstanceAndCompletesInitializationOnRetry() = retry("active")

    @Test fun partialMetadataFailureRetainsPublishedInstanceUntilInitializationCompletes() {
        val manager=OalMediaSessionManager.getInstance(RuntimeEnvironment.getApplication())
        ColdMediaSessionShadow.fault="metadata"
        assertTrue(runCatching { manager.initialize() }.isFailure)
        val field=manager.javaClass.getDeclaredField("mediaSession").apply { isAccessible=true }
        val partial=field.get(manager)
        assertNotNull("cold setter failure must retain the partially constructed session",partial)
        val token=manager.getSessionToken()!!
        OalMediaBrowserService.updateSessionToken(token)
        val browser=org.robolectric.Robolectric.buildService(OalMediaBrowserService::class.java).create()
        try {
            manager.initialize()
            assertSame(partial,field.get(manager))
            assertEquals(1,ColdMediaSessionShadow.sessions.size)
            assertEquals(token,manager.getSessionToken())
            assertEquals(token,browser.get().sessionToken)
            assertTrue((partial as MediaSessionCompat).isActive)
            val platform=Shadow.extract<ColdMediaSessionShadow>(ColdMediaSessionShadow.sessions.single())
            assertNotNull("retry must complete missing metadata rather than early-return",platform.submittedMetadata)
        } finally { browser.destroy() }
    }

    @Test fun constructorFailureBeforeInstanceCanRestoreOnExplicitRetry() {
        val manager=OalMediaSessionManager.getInstance(RuntimeEnvironment.getApplication())
        val lease=CarPlayHostMediaLease(CarPlayHostMediaMode.INACTIVE)
        ColdMediaSessionShadow.fault="construction"
        assertTrue(runCatching { manager.beginCarPlayHostMedia(lease) }.isFailure)
        assertNull(manager.getSessionToken())
        assertTrue(manager.carPlayHostMediaStatus().contains("unacknowledged"))
        val result=runCatching { manager.finishCarPlayHostMedia(lease) }
        assertTrue("null session must construct and acknowledge idle restoration: ${result.exceptionOrNull()}",result.isSuccess)
        assertNotNull(manager.getSessionToken())
        assertEquals("No current CarPlay host-media lease",manager.carPlayHostMediaStatus())
    }

    @Test fun coldNullRuntimeStopRetriesActualCoordinatorRestoration() = coordinatorRetry("stop")
    @Test fun coldNullRuntimeReturnRetriesActualCoordinatorRestoration() = coordinatorRetry("return")
    @Test fun coldNullRuntimeCloseRetriesActualCoordinatorRestoration() = coordinatorRetry("close")

    private fun coordinatorRetry(command:String) {
        val app=RuntimeEnvironment.getApplication()
        val manager=OalMediaSessionManager.getInstance(app)
        val ownership=CarPlayOwnership()
        val failed=java.util.concurrent.CountDownLatch(1)
        val returned=java.util.concurrent.CountDownLatch(1)
        val factoryCalls=java.util.concurrent.atomic.AtomicInteger()
        val finishes=java.util.concurrent.atomic.AtomicInteger()
        val source=CarPlayHostMediaLeaseSource.forContext(app)
        val owner=CarPlayCoordinator(ownership=ownership,retireAndroidAuto={},mediaLeases=object:CarPlayHostMediaLeaseSource {
            override fun begin(lease:CarPlayHostMediaLease) { source.begin(lease) }
            override fun finish(lease:CarPlayHostMediaLease) {
                // First cleanup also fails; no runtime has ever been constructed.
                if (finishes.incrementAndGet()==1) ColdMediaSessionShadow.fault="metadata"
                source.finish(lease)
            }
        }) { if (it.status.contains("retirement failed")) failed.countDown() }
        val worker=owner.javaClass.getDeclaredField("worker").apply { isAccessible=true }.get(owner) as java.util.concurrent.ExecutorService
        try {
            CarPlayPlayingPublicationExperiment.arm(CarPlayHostMediaMode.INACTIVE)
            ColdMediaSessionShadow.fault="metadata"
            assertTrue(owner.connectWithContext(true) { _,_->factoryCalls.incrementAndGet();error("factory must not run") })
            assertTrue(failed.await(5,java.util.concurrent.TimeUnit.SECONDS))
            worker.submit {}.get(5,java.util.concurrent.TimeUnit.SECONDS)
            assertEquals(0,factoryCalls.get())
            assertEquals("no automatic retry loop",1,finishes.get())
            assertTrue(ownership.blocksAndroidAuto)
            assertFalse(owner.connect(true) { error("retained debt must exclude successor") })
            assertTrue(manager.carPlayHostMediaStatus().contains("unacknowledged"))
            val token=manager.getSessionToken()
            when(command) {
                "stop" -> owner.stop()
                "return" -> owner.returnToAndroidAuto { returned.countDown() }
                else -> owner.close()
            }
            if (command=="close") assertTrue(worker.awaitTermination(5,java.util.concurrent.TimeUnit.SECONDS))
            else worker.submit {}.get(5,java.util.concurrent.TimeUnit.SECONDS)
            assertEquals("explicit $command must retire cold initialization debt", "No current CarPlay host-media lease",manager.carPlayHostMediaStatus())
            assertEquals(2,finishes.get())
            assertEquals(token,manager.getSessionToken())
            if (command=="return") {
                assertEquals(0L,returned.count)
                assertFalse(ownership.blocksAndroidAuto)
            }
        } finally { ColdMediaSessionShadow.fault=null;owner.close() }
    }

    private fun retry(stage:String) {
        val app=RuntimeEnvironment.getApplication()
        val manager=OalMediaSessionManager.getInstance(app)
        val lease=CarPlayHostMediaLease(CarPlayHostMediaMode.INACTIVE)
        ColdMediaSessionShadow.fault=stage
        assertTrue("fixture must throw at the actual cold setter",runCatching { manager.beginCarPlayHostMedia(lease) }.isFailure)
        assertNull("fault is one-shot",ColdMediaSessionShadow.fault)
        val field=manager.javaClass.getDeclaredField("mediaSession").apply { isAccessible=true }
        val retained=field.get(manager) as MediaSessionCompat?
        // Exercise recovery even on the broken tree before asserting retained-instance safety.
        val restore=runCatching { manager.finishCarPlayHostMedia(lease) }
        assertTrue("explicit finish must recover after cold $stage fault clears: ${restore.exceptionOrNull()}",restore.isSuccess)
        assertNotNull("a partially constructed process session must not be lost",retained)
        assertSame(retained,field.get(manager))
        assertEquals(1,ColdMediaSessionShadow.sessions.size)
        val token=manager.getSessionToken()!!
        OalMediaBrowserService.updateSessionToken(token)
        val browser=org.robolectric.Robolectric.buildService(OalMediaBrowserService::class.java).create()
        try {
            assertEquals(token,browser.get().sessionToken)
            assertTrue(retained!!.isActive)
            // Framework controller shadows do not mirror session submissions; inspect the
            // exact platform session's successfully submitted state/metadata instead.
            val platform=Shadow.extract<ColdMediaSessionShadow>(ColdMediaSessionShadow.sessions.single())
            assertEquals(PlaybackStateCompat.STATE_NONE,platform.submittedState!!.state)
            assertEquals("OpenAutoLink",platform.submittedMetadata!!.getString(MediaMetadata.METADATA_KEY_TITLE))
            val impl=retained.javaClass.getDeclaredField("mImpl").apply { isAccessible=true }.get(retained)
            assertNotNull(impl.javaClass.methods.first { it.name=="getCallback" }.apply { isAccessible=true }.invoke(impl))
            manager.initialize()
            assertSame(retained,field.get(manager))
            assertEquals(token,browser.get().sessionToken)
        } finally { browser.destroy() }
    }
}

@Implements(MediaSession::class)
class ColdMediaSessionShadow:ShadowMediaSession() {
    @RealObject lateinit var actual:MediaSession
    var submittedState:PlaybackState?=null
    var submittedMetadata:MediaMetadata?=null
    private var callbackCalls=0
    companion object {
        @JvmField var fault:String?=null
        val sessions=java.util.Collections.newSetFromMap(java.util.IdentityHashMap<MediaSession,Boolean>())
    }
    private fun hit(stage:String) {
        sessions.add(actual)
        if (fault==stage) { fault=null;throw IllegalStateException("one-shot cold $stage") }
    }
    @Implementation fun setCallback(callback:MediaSession.Callback?,handler:Handler?) {
        // Compat installs its own initial callback inside its constructor, before the
        // manager can retain the returned instance. Fail the manager's first callback.
        callbackCalls++
        if (callbackCalls==1) hit("construction")
        if (callbackCalls>1) hit("callback")
        Shadow.directlyOn<Any,MediaSession>(actual,MediaSession::class.java,"setCallback",ClassParameter.from(MediaSession.Callback::class.java,callback),ClassParameter.from(Handler::class.java,handler))
    }
    @Implementation fun setPlaybackState(state:PlaybackState?) {
        hit("state")
        Shadow.directlyOn<Any,MediaSession>(actual,MediaSession::class.java,"setPlaybackState",ClassParameter.from(PlaybackState::class.java,state))
        submittedState=state
    }
    @Implementation fun setMetadata(metadata:MediaMetadata?) {
        hit("metadata")
        Shadow.directlyOn<Any,MediaSession>(actual,MediaSession::class.java,"setMetadata",ClassParameter.from(MediaMetadata::class.java,metadata))
        submittedMetadata=metadata
    }
    @Implementation fun setActive(active:Boolean) {
        hit("active")
        Shadow.directlyOn<Any,MediaSession>(actual,MediaSession::class.java,"setActive",ClassParameter.from(Boolean::class.javaPrimitiveType!!,active))
    }
}
