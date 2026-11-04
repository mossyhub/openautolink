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
import org.robolectric.util.ReflectionHelpers
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/** Actual runtime/prepared-owner admission and SessionManager collectors; only OS/transport
 * boundaries are mocked. Firmware provenance no longer acts as runtime eligibility. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32], application = Application::class, manifest = Config.NONE)
class SessionClusterVideoIntegrationTest {
    private val selected = ClusterOutputSelection(ClusterDisplayMode.MAP_VIDEO)
    private fun await(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (!condition() && System.nanoTime() < deadline) { shadowOf(Looper.getMainLooper()).idle(); Thread.sleep(5) }
        assertTrue(condition())
    }
    private fun fixture(expectedRetired: Boolean = true, block: (HostedSecondaryRuntimeFixture) -> Unit) {
        val f = HostedSecondaryRuntimeFixture(2)
        try { block(f) } finally { f.close(expectedRetired) }
    }
    @Test fun onlyOptedQualifiedMapGetsOfferAndFrozenSelection() = fixture { f ->
        val offer = f.offer(selected)
        assertNotNull(offer)
        assertEquals(1280, f.mapping().codedSize.width)
        assertEquals(PixelRect(0,135,2000,810), f.mapping().qualifiedDisplay.host.visible)
        assertEquals(selected, f.prepared!!.legacySelection)
        assertNotNull(f.prepared!!.hostedManager)
        assertFalse(f.runtime.statuses.value.getValue(f.descriptor.id).hostAttached)
        verify(exactly = 0) { f.session.forceReconnect(any()) }
    }
    @Test fun offAndProtocolDisabledMapNeverAllocateManagerOrOffer() = fixture { f ->
        assertNull(f.offer(ClusterOutputSelection(ClusterDisplayMode.OFF)))
        assertNull(f.prepared!!.hostedManager)
        assertNull(f.offer(selected.copy(androidAuto = false, nativeIconsWithMap = true)))
        assertNull(f.prepared!!.hostedManager)
        assertTrue(f.prepared!!.offers.isEmpty())
    }
    @Test fun unknownVehicleWithholdsOnlyOptionalOutput() = fixture { f ->
        ReflectionHelpers.setStaticField(Build::class.java, "PRODUCT", "unknown-vehicle")
        assertNull(f.offer(selected))
        assertNull(f.runtime.mapping(f.descriptor.id))
        verify(exactly = 0) { f.session.stop(); f.session.forceReconnect(any()) }
    }
    @Test fun changedFirmwareRetainsKnownVehicleGeometry() = fixture { f ->
        ReflectionHelpers.setStaticField(Build::class.java, "ID", "different-build")
        ReflectionHelpers.setStaticField(Build.VERSION::class.java, "INCREMENTAL", "non-numeric")
        assertNotNull(f.offer(selected))
        assertEquals(ClusterDisplayProfiles.AEGEAN_ORANGE_239.id, f.mapping().qualifiedDisplay.profile.id)
    }
    @Test fun threeConcurrentRetirementsShareOneFutureAndOneHostEnd() {
        mockkConstructor(ClusterManager::class)
        val hostAck = CompletableFuture<Boolean>()
        val calls = java.util.concurrent.atomic.AtomicInteger()
        every { anyConstructed<ClusterManager>().endMapOutput(any(), any(), any()) } answers {
            calls.incrementAndGet()
            (callOriginal() as CompletableFuture<Boolean>).thenCompose { hostAck }
        }
        fixture { f ->
            assertNotNull(f.offer(selected))
            val executor = java.util.concurrent.Executors.newFixedThreadPool(3)
            try {
                val futures = (1..3).map { executor.submit<CompletableFuture<Boolean>> { f.manager.retireClusterVideoOutput() } }
                    .map { it.get(3, TimeUnit.SECONDS) }
                assertSame(futures[0], futures[1]); assertSame(futures[0], futures[2])
                await { calls.get() == 1 }
                assertFalse(futures[0].isDone)
                hostAck.complete(true)
                assertTrue(HostedSecondaryRuntimeFixture.await(futures[0]))
                assertEquals(1, calls.get())
                assertNotNull(f.offer(selected))
            } finally { hostAck.complete(true); executor.shutdown(); executor.awaitTermination(3, TimeUnit.SECONDS) }
        }
    }
    @Test fun falseRetirementKeepsExactFutureAndBlocksSuccessor() {
        mockkConstructor(ClusterManager::class)
        var actualHost: ClusterManager? = null
        var actualLease: ClusterOutputLease? = null
        var allowCleanup = false
        every { anyConstructed<ClusterManager>().endMapOutput(any(), any(), any()) } answers {
            actualLease = firstArg()
            if (allowCleanup) callOriginal() else CompletableFuture.completedFuture(false)
        }
        fixture(expectedRetired = false) { f ->
            assertNotNull(f.offer(selected))
            actualHost = f.prepared!!.hostedManager
            val exact = f.prepared
            try {
                val failed = f.manager.retireClusterVideoOutput()
                assertFalse(HostedSecondaryRuntimeFixture.await(failed))
                assertSame(failed, f.manager.retireClusterVideoOutput())
                val successor = runBlocking(Dispatchers.IO) { f.runtime.prepareAndroidAuto(99, f.snapshot(selected)) }
                assertTrue(successor.offers.isEmpty())
                assertEquals(SecondaryReason.RETIREMENT_PENDING, f.runtime.statuses.value.getValue(f.descriptor.id).reason)
                assertFalse(HostedSecondaryRuntimeFixture.await(exact!!.retire("same debt")))
                verify(exactly = 1) { anyConstructed<ClusterManager>().endMapOutput(any(), any(), any()) }
                verify(exactly = 0) { f.session.stop(); f.session.forceReconnect(any()) }
            } finally {
                // No fence clearing: retire the exact real hosted resources through their actual API.
                allowCleanup = true
                assertTrue(HostedSecondaryRuntimeFixture.await(actualHost!!.endMapOutput(actualLease!!, selected, "fixture cleanup")))
                assertTrue(HostedSecondaryRuntimeFixture.await(f.bridge!!.retire(actualLease!!)))
                actualHost!!.release()
            }
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
        await { f.runtime.hostedProjectionStatus()?.configurations == 1L }
        selectedFlow.value = emptyMap()
        val primary = VideoFrame(1280,720,20,1,byteArrayOf(1)); val sound = mockk<AudioFrame>()
        main.emit(primary); audio.emit(sound)
        await { runCatching { verify(exactly = 1) { decoder.onFrame(primary); player.onAudioFrame(sound) } }.isSuccess }
        verify(exactly = 0) { f.session.forceReconnect(any()); f.session.stop() }
    } }
}
