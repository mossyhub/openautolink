package com.openautolink.app.carplay

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[32,34],manifest=Config.NONE,application=android.app.Application::class)
@org.robolectric.annotation.ConscryptMode(org.robolectric.annotation.ConscryptMode.Mode.OFF)
class CarPlayInactiveMetadataHostTest {
    @Test fun exactContextFencesLateHostAfterRestoreAndSuccessorWhileRoutesContinue() {
        val constructor=OalCarPlayMetadataHost::class.java.constructors.firstOrNull {
            it.parameterTypes.size==4 && it.parameterTypes.contains(CarPlayAttemptContext::class.java) }
        assertNotNull("metadata host must retain the exact frozen attempt context",constructor)
        val app=RuntimeEnvironment.getApplication()
        val prefs=io.mockk.mockk<com.openautolink.app.data.AppPreferences>()
        io.mockk.mockkObject(com.openautolink.app.data.AppPreferences.Companion)
        io.mockk.every { com.openautolink.app.data.AppPreferences.getInstance(any()) } returns prefs
        io.mockk.every { prefs.clusterNavigation } returns kotlinx.coroutines.flow.flowOf(false)
        io.mockk.every { prefs.distanceUnits } returns kotlinx.coroutines.flow.flowOf("auto")
        val manager=com.openautolink.app.media.OalMediaSessionManager.getInstance(app).also { it.initialize() }
        val old=CarPlayHostMediaLease(CarPlayHostMediaMode.INACTIVE)
        fun host(lease:CarPlayHostMediaLease)=constructor!!.newInstance(app,{_:String ->},CarPlayAttemptContext(lease),{_:Int ->true}) as OalCarPlayMetadataHost
        var current:OalCarPlayMetadataHost?=null
        try {
            manager.beginCarPlayHostMedia(old);current=host(old);assertTrue(current.start())
            current.publish(com.openautolink.carplaylab.metadata.CarPlayMediaMetadata(playing=true),null,null)
            current.close();manager.finishCarPlayHostMedia(old)
            val next=CarPlayHostMediaLease(CarPlayHostMediaMode.NORMAL)
            manager.beginCarPlayHostMedia(next)
            assertFalse("stale authenticated callback cannot claim the successor's token",host(old).start())
            current=host(next);assertTrue(current.start())
            manager.finishCarPlayHostMedia(old)
            current.publish(com.openautolink.carplaylab.metadata.CarPlayMediaMetadata(playing=true),null,null)
            val native=android.support.v4.media.session.MediaControllerCompat(app,manager.getSessionToken()!!)
            assertEquals(android.support.v4.media.session.PlaybackStateCompat.STATE_PLAYING,native.playbackState!!.state)
            current.close();manager.finishCarPlayHostMedia(next)
        } finally { current?.close();manager.release();io.mockk.unmockkAll() }
    }
    @Test fun authenticatedIntakeRetainsPhoneTruthAndPublishesRoutesWhileNativeMediaIsWithheld() {
        val app=RuntimeEnvironment.getApplication()
        val prefs=io.mockk.mockk<com.openautolink.app.data.AppPreferences>()
        io.mockk.mockkObject(com.openautolink.app.data.AppPreferences.Companion)
        io.mockk.every { com.openautolink.app.data.AppPreferences.getInstance(any()) } returns prefs
        io.mockk.every { prefs.clusterNavigation } returns kotlinx.coroutines.flow.flowOf(false)
        io.mockk.every { prefs.distanceUnits } returns kotlinx.coroutines.flow.flowOf("auto")
        val manager=com.openautolink.app.media.OalMediaSessionManager.getInstance(app).also { it.initialize() }
        val lease=CarPlayHostMediaLease(CarPlayHostMediaMode.INACTIVE)
        val lines=java.util.concurrent.CopyOnWriteArrayList<String>()
        manager.beginCarPlayHostMedia(lease)
        val host=OalCarPlayMetadataHost(app,attemptContext=CarPlayAttemptContext(lease))
        val link=OalCarPlayMetadataLink({0L},lines::add,host)
        fun waitFor(predicate:()->Boolean) {
            val deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(5)
            while(!predicate() && System.nanoTime()<deadline) Thread.sleep(5)
            assertTrue(predicate())
        }
        try {
            link.authenticate()
            link.handle(com.shilapi.xcertplay.iap2.message.Iap2Messages.buildRaw(0x5001) { group(0) { string(1,"Private song") };group(1) { u32(1,1234);u8(0,1) } })
            link.handle(com.shilapi.xcertplay.iap2.message.Iap2Messages.buildRaw(0x5202) { u16(1,7);u8(3,2) })
            link.handle(com.shilapi.xcertplay.iap2.message.Iap2Messages.buildRaw(0x5201) { u8(1,1);u16List(13,listOf(7)) })
            waitFor { lines.any { "carplay-route publication=returned active=true" in it } }
            val intake=link.javaClass.getDeclaredField("intake").apply { isAccessible=true }.get(link) as com.openautolink.carplaylab.metadata.CarPlayMetadataIntake
            assertEquals(true,intake.media?.playing);assertEquals("Private song",intake.media?.title)
            assertTrue(com.openautolink.app.cluster.ClusterNavigationState.isActive)
            assertNotNull(com.openautolink.app.cluster.ClusterNavigationState.state.value)
            val native=android.support.v4.media.session.MediaControllerCompat(app,manager.getSessionToken()!!)
            assertEquals(android.support.v4.media.session.PlaybackStateCompat.STATE_NONE,native.playbackState!!.state)
            link.handle(com.shilapi.xcertplay.iap2.message.Iap2Messages.buildRaw(0x5201) { u8(1,0) })
            waitFor { lines.any { "carplay-route publication=returned active=false" in it } }
            assertFalse(com.openautolink.app.cluster.ClusterNavigationState.isActive)
        } finally { link.close();manager.finishCarPlayHostMedia(lease);manager.release();io.mockk.unmockkAll() }
    }

    @Test fun bothProductionRuntimeAdaptersAcceptFrozenContext() {
        for (type in listOf(OalCarPlayRuntime::class.java,OalUsbCarPlayRuntime::class.java)) {
            assertTrue("${type.simpleName} must carry context to authenticated host",type.declaredFields.any { it.type==CarPlayAttemptContext::class.java })
        }
    }
}
