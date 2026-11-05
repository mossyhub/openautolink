package com.openautolink.app.carplay

import android.content.Context
import android.hardware.usb.UsbDevice
import android.view.Surface
import com.openautolink.app.cluster.*
import com.openautolink.app.data.AppPreferences
import com.openautolink.app.media.OalMediaSessionManager
import com.openautolink.carplaylab.media.CarPlayClusterVideoConfiguration
import com.openautolink.carplaylab.media.CarPlayDisplayPreferences
import com.openautolink.carplaylab.media.ProjectionReceiver
import com.shilapi.xcertplay.airplay.AirPlayInsets
import com.shilapi.xcertplay.media.CarPlayVideoSelection
import io.mockk.*
import java.util.concurrent.CompletableFuture
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

class CarPlayClusterAppTest {
    @After fun cleanup() = unmockkAll()
    private val selection = ClusterOutputSelection(ClusterDisplayMode.MAP_VIDEO)
    private fun config() = CarPlayClusterVideoConfiguration.create(AirPlayInsets(0,0,0,0), AirPlayInsets(0,0,0,0),
        CarPlayVideoSelection(1280,480,30,"qualified-avc"))
    private class Fixture {
        val manager = mockk<ClusterManager>(relaxed = true)
        val bridge = mockk<ClusterProjectionOutput>(relaxed = true)
        val lease = ClusterOutputLease(30,40)
        val sources = mutableListOf<ClusterProjectionStream>()
        val predicates = mutableListOf<() -> Boolean>()
        init {
            every { bridge.status } returns MutableStateFlow(ClusterProjectionStatus())
            val mapping = mockk<ClusterVideoMapping>()
            every { mapping.codedSize } returns PixelSize(1280,480)
            every { bridge.mapping } returns mapping
            every { bridge.beginStream(lease, any(), any()) } answers {
                val predicate = thirdArg<() -> Boolean>(); predicates += predicate
                assertTrue("source must be current at actual common admission", predicate())
                ClusterProjectionStream(secondArg(), sources.size.toLong()+1, predicate).also { sources += it }
            }
            every { bridge.configure(any(),any(),any()) } returns true
            every { bridge.annexB(any(),any(),any(),any()) } returns true
            every { bridge.endStream(any(),any()) } returns true
        }
    }
    private fun output(f: Fixture, clock: () -> Long = { 7L }) = OalCarPlayClusterOutput(selection,config(),f.manager,f.bridge,f.lease,clock)

    @Test fun productionFacadeHasAnExplicitCommonRendererBoundary() {
        assertTrue(OalCarPlayClusterOutput::class.java.declaredMethods.any { it.name == "retire" })
        assertTrue(OalCarPlayClusterOutput::class.java.declaredMethods.any { it.name == "revoke" })
    }
    @Test fun offAndProtocolDisabledNeverProbeDisplayCodecOrConstructManager() {
        val context = mockk<Context>()
        for (intent in listOf(ClusterOutputSelection(ClusterDisplayMode.OFF),selection.copy(carPlay=false))) {
            val output = OalCarPlayClusterOutput.prepare(context,intent)
            assertSame(intent, output.selection)
            assertNull(output.configuration)
            assertFalse(output.snapshot().offerQualified)
            output.onStreamActive(1,true);output.onFrame(1,byteArrayOf(0,0,0,1,0x65))
            assertFalse(output.snapshot().phoneStreamActive)
            assertTrue(output.retire().get())
        }
        verify { context wasNot Called }
    }
    @Test fun mapQualificationFailureIsLocalAndNeverEscapesIntoPrimaryBootstrap() {
        val context = mockk<Context>()
        mockkObject(ClusterProjectionOutput.Companion)
        every { ClusterProjectionOutput.qualifiedInitialDisplay(any()) } throws IllegalStateException("display service lost")
        val output = OalCarPlayClusterOutput.prepare(context,selection)
        assertNull(output.configuration)
        assertNotNull(output.snapshot().output.error)
        assertTrue(output.retire().get())
    }
    @Test fun wireReplacementAndOldEofCannotClearSuccessorOrReplayOldPackets() {
        val f=Fixture();val output=output(f)
        output.onStreamActive(5,true)
        output.onFrame(5,byteArrayOf(0,0,0,1,0x65))
        output.onStreamActive(6,true)
        assertFalse(f.predicates[0]())
        assertTrue(f.predicates[1]())
        output.onStreamActive(5,false); output.onStreamActive(5,true)
        output.onFrame(5,byteArrayOf(0,0,0,1,0x65))
        output.onFrame(6,byteArrayOf(0,0,0,1,0x65))
        assertTrue(output.snapshot().phoneStreamActive)
        assertEquals(2,f.sources.size)
        verify(exactly=1) { f.bridge.annexB(f.lease,f.sources[0],any(),7) }
        verify(exactly=1) { f.bridge.annexB(f.lease,f.sources[1],any(),8) }
        output.revoke();assertFalse(f.predicates[1]())
        output.onFrame(6,byteArrayOf(0,0,0,1,0x65))
        verify(exactly=2) { f.bridge.annexB(any(),any(),any(),any()) }
    }
    @Test fun avccIsConvertedOnceAndMalformedOrOldConfigNeverTouchesDecoder() {
        val f=Fixture(); val output=output(f); output.onStreamActive(1,true)
        // Bounded avcC with one SPS and PPS; renderer receives Annex-B, not a second lab decoder.
        val bytes=byteArrayOf(1,66,0,30,-1,-31,0,2,0x67,1,1,0,2,0x68,2)
        val codec=slot<ClusterCodecConfig>()
        every { f.bridge.configure(f.lease,any(),capture(codec)) } returns true
        output.onCodecConfig(1,bytes)
        assertArrayEquals(byteArrayOf(0,0,0,1,0x67,1),codec.captured.sps)
        assertArrayEquals(byteArrayOf(0,0,0,1,0x68,2),codec.captured.pps)
        assertEquals("qualified-avc", codec.captured.decoderName)
        assertEquals(output.configuration!!.videoSelection.avcDecoderName, codec.captured.decoderName)
        output.onCodecConfig(1,byteArrayOf(1));output.onCodecConfig(0,bytes)
        verify(exactly=1) { f.bridge.configure(any(),any(),any()) }
        output.revoke()
    }
    @Test fun exactHostRetirementPrecedesRendererAndFalseAcknowledgmentRetainsManager() {
        val f=Fixture();val output=output(f)
        val host=CompletableFuture<Boolean>();val renderer=CompletableFuture<Boolean>()
        every { f.manager.endMapOutput(f.lease,selection,any()) } returns host
        every { f.bridge.retire(f.lease) } returns renderer
        val result=output.retire()
        assertSame(result,output.retire())
        assertFalse(result.isDone)
        verify(exactly=0) { f.bridge.retire(any()) }
        host.complete(true);assertFalse(result.isDone)
        renderer.complete(false);assertFalse(result.get())
        verify(exactly=0) { f.manager.release() }
        assertFalse(output.snapshot().phoneStreamActive)
        assertNotNull(output.snapshot().output.error)
    }
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun mapMetadataNeverCreatesCompetingManagerAndPreservesSourceAndMedia() = runTest {
        val prefs=mockk<AppPreferences>()
        mockkObject(AppPreferences.Companion,OalMediaSessionManager.Companion)
        every { AppPreferences.getInstance(any()) } returns prefs
        every { prefs.distanceUnits } returns flowOf("metric")
        val media=mockk<OalMediaSessionManager>(relaxed=true)
        every { OalMediaSessionManager.getInstance(any()) } returns media
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            for (icons in listOf(false,true)) {
                val frozen=selection.copy(nativeIconsWithMap=icons)
                // Legacy non-GM coexistence may create a native guidance manager, never a MAP owner.
                val native=ClusterOutputPolicy.shouldPublishNativeIcons(frozen,
                    ClusterProjectionProtocol.CARPLAY,hasSourcedManeuver=true)
                assertEquals(icons && !ClusterOutputPolicy.isGmVehicle(),native)
                val host=OalCarPlayMetadataHost(mockk(),clusterSelection=frozen)
                var demand: Job? = null
                try {
                    assertTrue(host.start())
                    val manager=host.javaClass.getDeclaredField("cluster").apply { isAccessible=true }.get(host) as ClusterManager?
                    demand=manager?.let { ClusterManager::class.java.getDeclaredField("demandJob").apply { isAccessible=true }.get(it) as Job? }
                    assertEquals("native manager follows the frozen runtime policy",native,manager != null)
                    if (manager != null) {
                        assertNull("metadata must never admit a competing MAP output",
                            ClusterManager::class.java.getDeclaredField("mapOutput").apply { isAccessible=true }.get(manager))
                        assertNotNull(demand)
                        runCurrent()
                        assertTrue(demand!!.isActive)
                    }
                    val nav=com.openautolink.carplaylab.metadata.CarPlayNavigationMetadata(2,0,200,"Road",100,1000)
                    val song=com.openautolink.carplaylab.metadata.CarPlayMediaMetadata(title="Song $icons")
                    val art=byteArrayOf(1)
                    host.publishRoute(song,com.openautolink.carplaylab.metadata.CarPlayRouteMetadata(1,nav),art)
                    assertEquals(CarPlayClusterMetadataAdapter.maneuver(nav),ClusterNavigationState.state.value)
                    assertEquals(200,ClusterNavigationState.state.value?.distanceMeters)
                    assertTrue(ClusterNavigationState.isActive)
                    val owner=host.javaClass.getDeclaredField("owner").apply { isAccessible=true }.get(host)
                    verify(exactly=1) { media.updateCarPlay(owner,song,art,false) }
                    verify(exactly=0) { prefs.clusterNavigation }
                } finally {
                    try { host.close() } finally {
                        // Join the exact process-scope collector before resetMain/unmockkAll,
                        // including assertion or close failures; do not reset global ownership.
                        demand?.cancelAndJoin()
                    }
                }
                assertTrue(demand?.isCompleted != false)
                assertNull(ClusterNavigationState.state.value)
                assertFalse(ClusterNavigationState.isActive)
            }
        } finally { Dispatchers.resetMain() }
    }
    @Test fun retainedMapDebtWithholdsNativeManagerWithoutWithholdingMedia() {
        val prefs=mockk<AppPreferences>()
        mockkObject(AppPreferences.Companion,OalMediaSessionManager.Companion,OalCarPlayClusterOutput.Companion)
        every { AppPreferences.getInstance(any()) } returns prefs
        every { prefs.distanceUnits } returns flowOf("metric")
        every { OalCarPlayClusterOutput.hasOutstandingMapOutput() } returns true
        val media=mockk<OalMediaSessionManager>(relaxed=true)
        every { OalMediaSessionManager.getInstance(any()) } returns media
        val host=OalCarPlayMetadataHost(mockk(),clusterSelection=ClusterOutputSelection(ClusterDisplayMode.NATIVE_ICONS))
        try {
            assertTrue(host.start())
            assertNull(host.javaClass.getDeclaredField("cluster").apply { isAccessible=true }.get(host))
            host.publish(com.openautolink.carplaylab.metadata.CarPlayMediaMetadata(title="Song"),null,null)
            verify { media.updateCarPlay(any(),any(),null,true) }
        } finally { host.close() }
    }
    @Test fun bothActualRuntimeOwnersPassFrozenOutputAndRetireItWithoutPrimaryDependency() {
        // Runtime construction/installation/close bodies, with transport/receiver OS boundaries mocked.
        for (usb in listOf(false,true)) {
            val f=Fixture();val output=output(f)
            every { f.manager.endMapOutput(f.lease,selection,any()) } returns CompletableFuture.completedFuture(true)
            every { f.bridge.retire(f.lease) } returns CompletableFuture.completedFuture(true)
            val context=mockk<Context>(relaxed=true);val surface=mockk<Surface>()
            val runtime:CarPlayRuntime=if(usb) OalUsbCarPlayRuntime(context,mockk<UsbDevice>(relaxed=true),CarPlayDisplayPreferences(),surface,{},
                clusterOutput=output,clusterSelection=selection) else OalCarPlayRuntime(context,
                CarPlayRuntimeConfig("","","",false,"","",0,CarPlayDisplayPreferences()),surface,{},clusterOutput=output,clusterSelection=selection)
            assertSame(output,runtime.javaClass.getDeclaredField("clusterOutput").apply { isAccessible=true }.get(runtime))
            output.onStreamActive(1,true)
            runtime.cancel();assertFalse(output.snapshot().phoneStreamActive)
            runtime.close()
            verify(exactly=1) { f.manager.release() }
        }
    }
}
