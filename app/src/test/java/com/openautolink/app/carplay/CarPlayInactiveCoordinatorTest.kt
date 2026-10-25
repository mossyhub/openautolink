package com.openautolink.app.carplay

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[32,34],manifest=Config.NONE,application=android.app.Application::class)
@org.robolectric.annotation.ConscryptMode(org.robolectric.annotation.ConscryptMode.Mode.OFF)
class CarPlayInactiveCoordinatorTest {
    private fun CountDownLatch.waitFor() { assertTrue("barrier timed out",await(5,TimeUnit.SECONDS)) }
    @Test fun coordinatorCloseRetainsRestoreDebtForAnExplicitCloseRetry() {
        val failed=CountDownLatch(1);val factory=CountDownLatch(1);val runDone=CountDownLatch(1);val restored=CountDownLatch(1)
        val allowRestore=AtomicBoolean()
        val ownership=CarPlayOwnership()
        val owner=CarPlayCoordinator(ownership=ownership,retireAndroidAuto={},mediaLeases=object:CarPlayHostMediaLeaseSource {
            override fun begin(lease:CarPlayHostMediaLease) {}
            override fun finish(lease:CarPlayHostMediaLease) { check(allowRestore.get());restored.countDown() }
        }) { if (it.status.contains("retirement failed")) failed.countDown() }
        try {
            owner.connect(true) { object:CarPlayRuntime {
                override fun run() { factory.countDown();runDone.waitFor() }
                override fun cancel() { runDone.countDown() }
                override fun close() {}
            } }
            factory.waitFor();owner.close();failed.waitFor()
            val worker=owner.javaClass.getDeclaredField("worker").apply { isAccessible=true }.get(owner) as java.util.concurrent.ExecutorService
            if (worker.isShutdown) worker.awaitTermination(5,TimeUnit.SECONDS)
            else worker.submit {}.get(5,TimeUnit.SECONDS)
            assertTrue(ownership.blocksAndroidAuto)
            allowRestore.set(true);owner.close();restored.waitFor()
        } finally { allowRestore.set(true);runDone.countDown();owner.close() }
    }

    @Test fun failedInactiveApplyCannotBootstrapAndNullRuntimeDebtIsRetryable() {
        val app=org.robolectric.RuntimeEnvironment.getApplication()
        val manager=com.openautolink.app.media.OalMediaSessionManager.getInstance(app).also { it.initialize() }
        val field=manager.javaClass.getDeclaredField("mediaSession").apply { isAccessible=true }
        val session=io.mockk.spyk(field.get(manager) as android.support.v4.media.session.MediaSessionCompat);field.set(manager,session)
        val failed=CountDownLatch(1);val retired=CountDownLatch(1)
        val factoryCalls=java.util.concurrent.atomic.AtomicInteger()
        val owner=CarPlayCoordinator(retireAndroidAuto={},mediaLeases=CarPlayHostMediaLeaseSource.forContext(app)) {
            if (it.status.contains("retirement failed")) failed.countDown()
            if (it.selected && !it.busy) retired.countDown()
        }
        try {
            CarPlayPlayingPublicationExperiment.arm(CarPlayHostMediaMode.INACTIVE)
            io.mockk.every { session.setMetadata(any()) } throws IllegalStateException("clear failed")
            owner.connectWithContext(true) { _,_->factoryCalls.incrementAndGet();error("apply must finish before factory") }
            failed.waitFor();assertEquals(0,factoryCalls.get());assertFalse(session.isActive)
            assertFalse(owner.connect(true) { error("partial apply debt") })
            io.mockk.every { session.setMetadata(any()) } answers { callOriginal() }
            owner.stop();retired.waitFor();assertTrue(session.isActive)
        } finally {
            io.mockk.every { session.setMetadata(any()) } answers { callOriginal() }
            owner.close();manager.release();CarPlayPlayingPublicationExperiment.disarm();io.mockk.unmockkAll()
        }
    }

    @Test fun freezeAtAttemptAdmissionSurvivesExpiryBeforeAaRetiresAndLateCanceledFactory() {
        val entered=CountDownLatch(1);val aaReady=CountDownLatch(1);val factoryEntered=CountDownLatch(1)
        val factoryReady=CountDownLatch(1);val retired=CountDownLatch(1)
        val events=java.util.concurrent.CopyOnWriteArrayList<String>()
        val owner=CarPlayCoordinator(retireAndroidAuto={entered.countDown();aaReady.waitFor()},mediaLeases=object:CarPlayHostMediaLeaseSource {
            override fun begin(lease:CarPlayHostMediaLease) { assertEquals(CarPlayHostMediaMode.INACTIVE,lease.mode);events.add("apply") }
            override fun finish(lease:CarPlayHostMediaLease) { assertEquals(listOf("apply","close"),events.toList());events.add("restore") }
        }) { if (it.selected && !it.busy) retired.countDown() }
        try {
            CarPlayPlayingPublicationExperiment.arm(CarPlayHostMediaMode.INACTIVE)
            owner.connectWithContext(true) { frozen,_ ->
                assertEquals(CarPlayHostMediaMode.INACTIVE,frozen.hostMedia.mode)
                factoryEntered.countDown();factoryReady.waitFor()
                object:CarPlayRuntime {
                    override fun run() { fail("canceled late factory may not run") }
                    override fun cancel() {}
                    override fun close() { events.add("close") }
                }
            }
            entered.waitFor();CarPlayPlayingPublicationExperiment.disarm()
            org.robolectric.shadows.ShadowSystemClock.advanceBy(java.time.Duration.ofMinutes(16))
            aaReady.countDown();factoryEntered.waitFor();owner.stop();factoryReady.countDown();retired.waitFor()
            assertEquals(listOf("apply","close","restore"),events.toList())
        } finally { aaReady.countDown();factoryReady.countDown();owner.close();CarPlayPlayingPublicationExperiment.disarm() }
    }

    @Test fun realManagerKeepsInactiveThroughHostCloseRuntimeProtectionAndRestoreDebtsThenReplaysAa() {
        val app=org.robolectric.RuntimeEnvironment.getApplication()
        val manager=com.openautolink.app.media.OalMediaSessionManager.getInstance(app).also { it.initialize() }
        val field=manager.javaClass.getDeclaredField("mediaSession").apply { isAccessible=true }
        val session=io.mockk.spyk(field.get(manager) as android.support.v4.media.session.MediaSessionCompat);field.set(manager,session)
        val prefs=io.mockk.mockk<com.openautolink.app.data.AppPreferences>()
        io.mockk.mockkObject(com.openautolink.app.data.AppPreferences.Companion)
        io.mockk.every { com.openautolink.app.data.AppPreferences.getInstance(any()) } returns prefs
        io.mockk.every { prefs.clusterNavigation } returns kotlinx.coroutines.flow.flowOf(false)
        io.mockk.every { prefs.distanceUnits } returns kotlinx.coroutines.flow.flowOf("auto")
        val closeAllowed=AtomicBoolean();val releaseAllowed=AtomicBoolean()
        val started=CountDownLatch(1);val stop=CountDownLatch(1);val hostClosed=CountDownLatch(1)
        val retired=CountDownLatch(1);val returned=CountDownLatch(1)
        val failures=java.util.concurrent.LinkedBlockingQueue<Boolean>()
        val lines=java.util.concurrent.CopyOnWriteArrayList<String>()
        val source=CarPlayHostMediaLeaseSource.forContext(app)
        var context:CarPlayAttemptContext?=null
        val ownership=CarPlayOwnership()
        val owner=CarPlayCoordinator(ownership,{},CarPlaySessionLeaseSource {
            object:CarPlaySessionLease { override fun awaitReady() {};override fun release() { check(releaseAllowed.get()) } }
        },source) {
            if (it.status.contains("retirement failed")) failures.offer(true)
            if (it.selected && !it.busy) retired.countDown()
        }
        fun failure() {
            assertNotNull("retirement debt must be visible",failures.poll(5,TimeUnit.SECONDS))
            val worker=owner.javaClass.getDeclaredField("worker").apply { isAccessible=true }.get(owner) as java.util.concurrent.ExecutorService
            worker.submit {}.get(5,TimeUnit.SECONDS)
            failures.clear()
        }
        try {
            CarPlayPlayingPublicationExperiment.arm(CarPlayHostMediaMode.INACTIVE)
            assertTrue(owner.connectWithContext(true) { frozen,_ ->
                context=frozen;frozen.hostMedia.receipt=lines::add
                assertEquals(CarPlayHostMediaMode.INACTIVE,frozen.hostMedia.mode);assertFalse(session.isActive)
                val host=OalCarPlayMetadataHost(app,attemptContext=frozen).also { assertTrue(it.start()) }
                host.publish(com.openautolink.carplaylab.metadata.CarPlayMediaMetadata(playing=true,title="private"),null,null)
                object:CarPlayRuntime {
                    override fun run() { started.countDown();stop.waitFor() }
                    override fun cancel() { stop.countDown() }
                    override fun close() { host.close();hostClosed.countDown();check(closeAllowed.get()) }
                }
            })
            started.waitFor();CarPlayPlayingPublicationExperiment.disarm()
            owner.stop();hostClosed.waitFor();failure()
            assertFalse(session.isActive)
            assertTrue("full retirement must be observed before runtime close",lines.any { "event=pending" in it })
            manager.initialize();manager.resetToIdle();manager.updatePlaybackState(true,123)
            assertFalse(session.isActive);assertTrue(ownership.blocksAndroidAuto)
            closeAllowed.set(true);owner.stop();failure()
            assertFalse(session.isActive)
            io.mockk.every { session.setActive(true) } throws IllegalStateException("restore debt")
            releaseAllowed.set(true);owner.stop();failure()
            assertFalse(owner.connect(true) { error("restore debt not retired") })
            io.mockk.every { session.setActive(true) } answers { callOriginal() }
            owner.stop();retired.waitFor();assertTrue(session.isActive)
            owner.returnToAndroidAuto { returned.countDown() };returned.waitFor()
            assertFalse(ownership.blocksAndroidAuto)
            manager.updateMetadata("Current AA",null,null,null,null);manager.updatePlaybackState(true,456)
            manager.finishCarPlayHostMedia(context!!.hostMedia)
            val native=android.support.v4.media.session.MediaControllerCompat(app,manager.getSessionToken()!!)
            assertEquals(android.support.v4.media.session.PlaybackStateCompat.STATE_PLAYING,native.playbackState!!.state)
        } finally {
            closeAllowed.set(true);releaseAllowed.set(true);stop.countDown()
            io.mockk.every { session.setActive(true) } answers { callOriginal() }
            owner.close();CarPlayPlayingPublicationExperiment.disarm();manager.release();io.mockk.unmockkAll()
        }
    }

    @Test fun applyAfterAaBeforeFactoryAndNullRuntimeRestoreFailureRetainsDebtUntilStopRetry() {
        val constructor=CarPlayCoordinator::class.java.constructors.firstOrNull {
            it.parameterTypes.contentEquals(arrayOf(CarPlayOwnership::class.java,kotlin.jvm.functions.Function0::class.java,
                CarPlaySessionLeaseSource::class.java,CarPlayHostMediaLeaseSource::class.java,kotlin.jvm.functions.Function1::class.java)) }
        assertNotNull("coordinator needs exact-attempt host-media lease source",constructor)
        val aaEntered=CountDownLatch(1);val aaRelease=CountDownLatch(1)
        val applied=CountDownLatch(1);val failed=CountDownLatch(1);val retired=CountDownLatch(1)
        val allowRestore=AtomicBoolean();val protectionReleased=AtomicBoolean()
        var exact:CarPlayHostMediaLease?=null
        val source=object:CarPlayHostMediaLeaseSource {
            override fun begin(lease:CarPlayHostMediaLease) { exact=lease;applied.countDown() }
            override fun finish(lease:CarPlayHostMediaLease) {
                assertSame(exact,lease);assertTrue(protectionReleased.get());check(allowRestore.get())
            }
        }
        val ownership=CarPlayOwnership()
        val owner=constructor!!.newInstance(ownership,{aaEntered.countDown();aaRelease.waitFor()},CarPlaySessionLeaseSource {
            object:CarPlaySessionLease { override fun awaitReady() {};override fun release() { protectionReleased.set(true) } }
        },source,{ state:CarPlayUiState ->
            if (state.status.contains("retirement failed")) failed.countDown()
            if (state.selected && !state.busy) retired.countDown()
        }) as CarPlayCoordinator
        try {
            assertTrue(owner.connect(true) { assertEquals(0L,applied.count);throw IllegalStateException("factory failed before runtime exists") })
            aaEntered.waitFor();assertEquals("live AA cannot be deactivated",1L,applied.count)
            aaRelease.countDown();failed.waitFor()
            assertFalse(owner.connect(true) { error("unfinished restore") })
            assertTrue(ownership.blocksAndroidAuto)
            allowRestore.set(true);owner.stop();retired.waitFor()
        } finally { aaRelease.countDown();allowRestore.set(true);owner.close() }
    }
}
