package com.openautolink.app.session

import android.app.Application
import android.os.Build
import android.os.Looper
import com.openautolink.app.cluster.*
import com.openautolink.app.secondary.*
import com.openautolink.app.transport.aasdk.*
import com.openautolink.app.audio.AudioPlayer
import com.openautolink.app.audio.AudioFrame
import com.openautolink.app.video.VideoDecoder
import com.openautolink.app.video.VideoFrame
import io.mockk.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import org.robolectric.util.ReflectionHelpers
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/** Actual connection preparation, direct Activity/GPU admission and SessionManager collectors.
 * Firmware provenance is not runtime eligibility; inventory still owns route admission. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32], application = Application::class,
    shadows = [ClusterDirectVideoLifecycleTest.Admission::class, ClusterDirectVideoLifecycleTest.TextureBoundary::class])
@ConscryptMode(ConscryptMode.Mode.OFF)
class SessionClusterVideoIntegrationTest {
    private val selected = ClusterOutputSelection(ClusterDisplayMode.MAP_VIDEO)
    private fun await(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (!condition() && System.nanoTime() < deadline) { shadowOf(Looper.getMainLooper()).idle(); Thread.sleep(5) }
        assertTrue(condition())
    }
    private fun fixture(expectedRetired: Boolean = true, block: (DirectSessionRuntimeFixture) -> Unit) {
        val f = DirectSessionRuntimeFixture()
        try { block(f) } finally { f.close(expectedRetired) }
    }
    @Test fun teardownWaitsForSeparateManagerOwnerBeforeReleasingPointerAndMocks() {
        val f=DirectSessionRuntimeFixture()
        val managerScope=f.field("scope").get(f.manager) as CoroutineScope
        val entered=java.util.concurrent.CountDownLatch(1)
        val release=java.util.concurrent.CountDownLatch(1)
        val worker=java.util.concurrent.Executors.newSingleThreadExecutor()
        val child=managerScope.launch(start=CoroutineStart.UNDISPATCHED) {
            try { awaitCancellation() } finally { withContext(NonCancellable) {
                entered.countDown();check(release.await(3,TimeUnit.SECONDS))
            } }
        }
        val observed=worker.submit {
            try {
                assertTrue(entered.await(2,TimeUnit.SECONDS))
                assertSame(f.runtime,SecondaryOutputRuntime.peekForDiagnostics())
                assertSame(f.preferences,com.openautolink.app.data.AppPreferences.getInstance(f.context))
            } finally { release.countDown() }
        }
        try {
            f.close()
            observed.get(3,TimeUnit.SECONDS)
            assertTrue("Actual manager child must finish before teardown returns",child.isCompleted)
            assertTrue(requireNotNull(managerScope.coroutineContext[Job]).isCompleted)
        } finally { release.countDown();worker.shutdown();worker.awaitTermination(3,TimeUnit.SECONDS) }
    }
    @Test fun unacknowledgedRuntimeCloseRetainsPointerAndMocksUntilRealRetry() {
        val f=DirectSessionRuntimeFixture()
        mockkObject(f.runtime)
        every { f.runtime.close() } returns CompletableFuture.completedFuture(false)
        try {
            assertThrows(AssertionError::class.java) { f.close() }
            assertSame("Failed exact cleanup must retain its process owner",f.runtime,SecondaryOutputRuntime.peekForDiagnostics())
            assertSame(f.preferences,com.openautolink.app.data.AppPreferences.getInstance(f.context))
        } finally {
            val stillOwned=SecondaryOutputRuntime.peekForDiagnostics()===f.runtime
            unmockkObject(f.runtime)
            if(stillOwned) f.close() else assertTrue(f.await(f.runtime.close()))
        }
    }
    @Test fun onlyOptedQualifiedMapGetsOfferAndFrozenSelection() = fixture { f ->
        val offer = f.offer(selected)
        assertNotNull(offer)
        assertEquals(1280, f.mapping().codedSize.width)
        assertEquals(PixelRect(0,0,2000,810), f.mapping().target.output)
        assertEquals(PixelRect(192,71,1788,660), f.mapping().target.safe)
        assertEquals(selected, f.prepared!!.legacySelection)
        assertNull(f.prepared!!.hostedManager)
        assertNull(ClusterBindingState.frozenMapBinding())
        assertTrue(f.runtime.statuses.value.getValue(f.descriptor.id).hostAttached)
        assertEquals(1,f.physical.size)
        verify(exactly = 0) { f.session.forceReconnect(any()) }
    }
    @Test fun offNeverOffersButLegacyProtocolFalseStillUsesDirectMap() = fixture { f ->
        assertNull(f.offer(ClusterOutputSelection(ClusterDisplayMode.OFF)))
        assertNull(f.prepared!!.hostedManager)
        assertTrue(f.bridges.isEmpty())
        assertTrue(f.prepared!!.offers.isEmpty())
        assertNotNull(f.offer(selected.copy(androidAuto = false, nativeIconsWithMap = true)))
        assertNull(f.prepared!!.hostedManager)
        assertNull(ClusterBindingState.frozenMapBinding())
    }
    @Test fun unknownBuiltInWithholdsOnlyOptionalOutput() = fixture { f ->
        ReflectionHelpers.setStaticField(Build::class.java, "PRODUCT", "unknown-vehicle")
        // Route discovery, not guessed AndroidX geometry, withholds unknown built-ins.
        f.inventory.value = SecondaryInventorySnapshot(2,listOf(f.descriptor.copy(
            route = SecondaryRoute.INVENTORY_ONLY,reason = SecondaryReason.UNKNOWN_BUILTIN)))
        assertNull(f.offer(selected))
        assertNull(f.runtime.mapping(f.descriptor.id))
        assertTrue(f.bridges.isEmpty())
        verify(exactly = 0) { f.session.stop(); f.session.forceReconnect(any()) }
    }
    @Test fun changedFirmwareRetainsKnownVehicleGeometry() = fixture { f ->
        ReflectionHelpers.setStaticField(Build::class.java, "ID", "different-build")
        ReflectionHelpers.setStaticField(Build.VERSION::class.java, "INCREMENTAL", "non-numeric")
        assertNotNull(f.offer(selected))
        assertEquals(PixelRect(0,0,2000,810),f.mapping().target.output)
        assertEquals(PixelRect(192,71,1788,660),f.mapping().target.safe)
        assertNull("Direct geometry must not claim a legacy firmware receipt",f.mapping().gmReceipt)
    }
    @Test fun threeConcurrentRetirementsShareOneFutureAndOneGpuRetirement() = fixture { f ->
        assertNotNull(f.offer(selected))
        val hostAck = CompletableFuture<Boolean>()
        f.controlRetirement(hostAck)
        val executor = java.util.concurrent.Executors.newFixedThreadPool(3)
        try {
            val futures = (1..3).map { executor.submit<CompletableFuture<Boolean>> { f.manager.retireClusterVideoOutput() } }
                .map { it.get(3, TimeUnit.SECONDS) }
            assertSame(futures[0], futures[1]); assertSame(futures[0], futures[2])
            f.pump { f.retireCalls.get() == 1 }
            assertFalse(futures[0].isDone)
            hostAck.complete(true)
            assertTrue(f.await(futures[0]))
            assertEquals(1, f.detached.get())
            assertNotNull(f.offer(selected))
        } finally { hostAck.complete(true); executor.shutdown(); executor.awaitTermination(3, TimeUnit.SECONDS) }
    }
    @Test fun falseRetirementKeepsExactFutureAndBlocksSuccessor() = fixture(expectedRetired = false) { f ->
        assertNotNull(f.offer(selected))
        val exact = requireNotNull(f.prepared)
        val owner = requireNotNull(f.owner())
        val snapshot = f.freeze(selected)
        f.controlRetirement(CompletableFuture.completedFuture(false))
        try {
            val failed = f.manager.retireClusterVideoOutput()
            assertFalse(f.await(failed))
            assertSame(failed, f.manager.retireClusterVideoOutput())
            val pending = f.scope.async { f.runtime.prepareAndroidAuto(99,snapshot) }
            f.pump { pending.isCompleted }
            val successor = runBlocking { pending.await() }
            assertTrue(successor.offers.isEmpty())
            assertEquals(SecondaryReason.RETIREMENT_PENDING, f.runtime.statuses.value.getValue(f.descriptor.id).reason)
            assertFalse(f.await(exact.retire("same debt")))
            assertEquals(1,f.retireCalls.get())
            assertSame(owner,f.owner())
            assertFalse(owner.handle!!.owner.isCurrent)
            assertEquals(1,f.physical.size)
            verify(exactly = 0) { f.session.stop(); f.session.forceReconnect(any()) }
        } finally {
            // Retry only the captured real registry/GPU owner. Cached failed session/runtime
            // futures stay false; no process claim, admission latch or reservation is cleared.
            f.retirementAck = null
            assertTrue(f.await(owner.handle!!.retire()))
            assertTrue(f.await(owner.bridge!!.retire(owner.lease)))
        }
    }
    @Test fun realCollectorsKeepMainVideoAndAudioAliveAfterClusterStreamEnds() = fixture { f -> runBlocking {
        val offer = f.offer(selected)!!
        val selection = AasdkSecondaryVideoSelection(offer.channelId, "exact", 1, 3, 0, 9, offer)
        val selectedFlow = MutableStateFlow(mapOf(offer.channelId to selection))
        val cluster = MutableSharedFlow<AasdkSecondaryVideoFrame>(extraBufferCapacity = 4)
        val main = MutableSharedFlow<VideoFrame>(extraBufferCapacity = 4)
        val audio = MutableSharedFlow<AudioFrame>(extraBufferCapacity = 4)
        every { f.session.secondaryVideoSelections } returns selectedFlow
        every { f.session.secondaryVideoFrames } returns cluster
        every { f.session.isCurrentSecondaryVideo(any()) } answers {
            firstArg<AasdkSecondaryVideoFrame>().selection === selectedFlow.value[offer.channelId]
        }
        every { f.session.videoFrames } returns main
        every { f.session.audioFrames } returns audio
        every { f.session.controlMessages } returns emptyFlow()
        every { f.session.evTelemetryToken } returns "cluster-fixture"
        every { f.session.reconnectAttempt } returns MutableStateFlow(0)
        every { f.session.vehicleEnergyForecast } returns MutableStateFlow(null)
        every { f.session.negotiatedCodecType } returns MutableStateFlow(0)
        val decoder = mockk<VideoDecoder>(relaxed = true); val player = mockk<AudioPlayer>(relaxed = true)
        f.field("aasdkSession").set(f.manager, f.session)
        f.field("_videoDecoder").set(f.manager, decoder); f.field("_audioPlayer").set(f.manager, player)
        SessionManager::class.java.getDeclaredMethod("bindSessionCollectors", AasdkSession::class.java)
            .apply { isAccessible = true }.invoke(f.manager, f.session)
        await { cluster.subscriptionCount.value > 0 && main.subscriptionCount.value > 0 && audio.subscriptionCount.value > 0 }
        val au = byteArrayOf(0,0,0,1,7,1,0,0,0,1,8,1,0,0,0,1,5,1)
        cluster.emit(AasdkSecondaryVideoFrame(selection, au, 10, 0))
        await { f.bridges.single().status.value.configurations == 1L }
        selectedFlow.value = emptyMap()
        val primary = VideoFrame(1280,720,20,1,byteArrayOf(1)); val sound = mockk<AudioFrame>()
        main.emit(primary); audio.emit(sound)
        await { runCatching { verify(exactly = 1) { decoder.onFrame(primary); player.onAudioFrame(sound) } }.isSuccess }
        verify(exactly = 0) { f.session.forceReconnect(any()); f.session.stop() }
    } }
}
