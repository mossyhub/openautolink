package com.openautolink.app.cluster

import android.app.Application
import android.os.Build
import android.view.Surface
import com.openautolink.app.carplay.OalCarPlayClusterOutput
import com.openautolink.app.data.*
import com.openautolink.app.secondary.*
import com.openautolink.app.transport.aasdk.*
import com.shilapi.xcertplay.media.CarPlayVideoQualification
import com.shilapi.xcertplay.media.CarPlayVideoSelection
import io.mockk.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch

/** Direct consumers freeze safe-only intent from measured full bounds. Legacy resolver/
 * coordinator geometry remains independently covered, not a direct readiness signal. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32], application = Application::class,
    shadows = [ClusterDirectVideoLifecycleTest.Admission::class, ClusterDirectVideoLifecycleTest.TextureBoundary::class])
@ConscryptMode(ConscryptMode.Mode.OFF)
class ClusterLayoutConsumptionIntegrationTest {
    private val profile = ClusterDisplayProfiles.AEGEAN_ORANGE_239
    private val legacyCanvas = PixelRect(0,135,2000,810)
    private val layoutScope = ClusterLayoutScope(profile.vehicle,profile.id,profile.role,profile.panel)
    private val custom = SavedClusterLayout(layoutScope,legacyCanvas,PixelRect(200,150,1800,750),PixelRect(650,250,1300,600))
    private val selection = ClusterOutputSelection(ClusterDisplayMode.MAP_VIDEO)
    private fun fixture(block: suspend (DirectSessionRuntimeFixture) -> Unit) = runBlocking {
        val f = DirectSessionRuntimeFixture()
        try { block(f) } finally { f.close() }
    }
    private fun layout(f: DirectSessionRuntimeFixture, saved: SavedClusterLayout = custom): SecondaryLayoutPreference {
        fun normalized(rect: PixelRect) = SecondaryLayoutBox(rect.left/2000.0,rect.top/810.0,rect.right/2000.0,rect.bottom/810.0)
        return SecondaryLayoutPreference(SecondaryLayoutScope(f.descriptor.id,Build.PRODUCT,Build.FINGERPRINT,2000,810,160),
            normalized(saved.output),normalized(saved.safe),directSafe = normalized(saved.safe))
    }
    private suspend fun legacy(f: DirectSessionRuntimeFixture): ClusterLayoutResolution =
        ClusterSavedLayoutResolver.resolve(requireNotNull(ClusterProjectionOutput.qualifiedInitialDisplay(f.context)),f.preferences::getClusterLayout)
    private fun assertWire(offer: AasdkSecondaryVideoOffer, mapping: VideoMapping) {
        assertEquals(listOf(mapping.codecCrop.left,mapping.codecCrop.top,mapping.codecCrop.right,mapping.codecCrop.bottom),
            listOf(offer.cropLeft,offer.cropTop,offer.cropRight,offer.cropBottom))
        assertEquals(listOf(mapping.phoneSafeInsets.left,mapping.phoneSafeInsets.top,mapping.phoneSafeInsets.right,mapping.phoneSafeInsets.bottom),
            listOf(offer.safeLeft,offer.safeTop,offer.safeRight,offer.safeBottom))
        assertEquals(listOf(mapping.stableContentInsets.left,mapping.stableContentInsets.top,mapping.stableContentInsets.right,mapping.stableContentInsets.bottom),
            listOf(offer.stableLeft,offer.stableTop,offer.stableRight,offer.stableBottom))
    }
    @Test fun aaProductionOfferSerializesCustomInsetsAndFreezesUntilNextOfferThenReset() = fixture { f ->
        f.preferences.saveClusterLayout(custom)
        val resolved = legacy(f)
        assertNull(resolved.fallbackReason)
        assertEquals(custom.output,resolved.display.effectiveOutput)
        assertEquals(custom.safe,resolved.display.safe)
        assertEquals(profile.id,resolved.display.profile.id)
        assertEquals(profile.output,resolved.display.profile.output)
        val initial = f.prepare(selection,layout(f))
        val wire = initial.offers.single()
        val mapping = f.mapping()
        val config = AasdkSdrConfig(secondaryOffers = arrayOf(wire))
        assertSame(wire,config.secondaryOffers.single())
        assertWire(wire,mapping)
        assertEquals(profile.panel.bounds,mapping.target.output)
        assertEquals(custom.safe,mapping.target.safe)
        assertNull(initial.hostedManager)
        assertNull(ClusterBindingState.frozenMapBinding())
        val changed = custom.copy(safe = PixelRect(700,300,1200,550))
        f.preferences.saveClusterLayout(changed)
        f.freeze(selection,layout(f,changed))
        assertSame(mapping,f.mapping())
        assertEquals(custom.safe,mapping.target.safe)
        assertTrue(f.await(f.manager.retireClusterVideoOutput()))
        val next = f.prepare(selection,layout(f,changed))
        assertNotEquals(wire.safeLeft,next.offers.single().safeLeft)
        assertEquals(changed.safe,f.mapping().target.safe)
        assertWire(next.offers.single(),f.mapping())
        assertTrue(f.await(f.manager.retireClusterVideoOutput()))
        f.preferences.resetClusterLayout(layoutScope,legacyCanvas)
        val reset = f.prepare(selection)
        assertEquals(ClusterLayoutFallbackReason.NO_CUSTOM_LAYOUT,legacy(f).fallbackReason)
        assertEquals(profile.panel.bounds,f.mapping().target.output)
        assertEquals(SecondaryProjectionActivity.directDefaultSafe(2000,810),f.mapping().target.safe)
        assertWire(reset.offers.single(),f.mapping())
        assertNull(f.manager.clusterVideoStatus.value.error)
        verify(exactly = 0) { f.session.forceReconnect(any()); f.session.stop() }
    }

    /** Keep the already-green legacy consumer separate from the direct runtime. */
    @Test fun carPlayProductionConfigurationReceivesPixelInsetsAndFreezesUntilReset() = runBlocking {
        val f = HostedSecondaryRuntimeFixture()
        mockkObject(CarPlayVideoQualification)
        every { CarPlayVideoQualification.select(1280,480,30,false) } returns CarPlayVideoSelection(1280,480,30,"exact.avc")
        fun await(result: CompletableFuture<Boolean>) { assertTrue(HostedSecondaryRuntimeFixture.await(result)) }
        try {
            f.preferences.saveClusterLayout(custom)
            val output = withContext(Dispatchers.IO) { OalCarPlayClusterOutput.prepare(f.context,selection) }
            try {
                val config = requireNotNull(output.configuration)
                assertEquals(1280,config.display.widthPixels); assertEquals(480,config.display.heightPixels)
                assertEquals(listOf(360,80,400,120),requireNotNull(config.display.safeArea).let { listOf(it.left,it.top,it.right,it.bottom) })
                assertEquals("exact.avc",config.videoSelection.avcDecoderName)
                assertNull(output.snapshot().layoutSelectionReason)
                f.preferences.resetClusterLayout(layoutScope,legacyCanvas)
                assertEquals(360,config.display.safeArea!!.left)
                assertNull(output.snapshot().output.error)
            } finally { await(output.retire()) }
            val reset = withContext(Dispatchers.IO) { OalCarPlayClusterOutput.prepare(f.context,selection) }
            try {
                assertNotNull(reset.configuration)
                assertNotEquals(360,reset.configuration!!.display.safeArea!!.left)
                assertEquals(ClusterLayoutFallbackReason.NO_CUSTOM_LAYOUT,reset.snapshot().layoutSelectionReason)
                assertNull(reset.snapshot().output.error)
            } finally { await(reset.retire()) }
        } finally { f.close() }
    }

    @Test fun staleFullCanvasFallsBackInBothProductionConsumersWithoutPrimaryError() = fixture { f ->
        f.preferences.saveClusterLayout(custom.copy(canvas = profile.panel.bounds))
        assertEquals(ClusterLayoutFallbackReason.CANVAS_CHANGED,legacy(f).fallbackReason)
        val stale = layout(f).let { it.copy(scope = it.scope.copy(panelHeight = 811)) }
        val initial = f.prepare(selection,stale)
        assertEquals(profile.panel.bounds,f.mapping().target.output)
        assertEquals(SecondaryProjectionActivity.directDefaultSafe(2000,810),f.mapping().target.safe)
        assertWire(initial.offers.single(),f.mapping())
        assertNull(f.manager.clusterVideoStatus.value.error)
        assertTrue(f.await(f.manager.retireClusterVideoOutput()))
        val frozen = f.freeze(selection,stale)
        val pending = f.scope.async { f.runtime.prepareCarPlay(200,frozen) }
        f.pump { pending.isCompleted }
        val output = pending.await()
        try {
            val config = requireNotNull(output.configuration)
            val mapping = f.mapping()
            assertEquals(profile.panel.bounds,mapping.target.output)
            val automatic = SecondaryProjectionActivity.directDefaultSafe(2000,810)
            assertEquals(automatic.intersect(SecondaryVideoMapping.carPlayDestination(profile.panel.bounds)),mapping.target.safe)
            assertEquals(listOf(mapping.phoneSafeInsets.left,mapping.phoneSafeInsets.top,mapping.phoneSafeInsets.right,mapping.phoneSafeInsets.bottom),
                requireNotNull(config.display.safeArea).let { listOf(it.left,it.top,it.right,it.bottom) })
            assertNull(output.snapshot().output.error)
            assertEquals(ClusterLayoutFallbackReason.CANVAS_CHANGED,legacy(f).fallbackReason)
        } finally { assertTrue(f.await(output.retire("fixture done"))) }
        verify(exactly = 0) { f.session.forceReconnect(any()); f.session.stop() }
    }

    @Test fun retirementBetweenReservationAndInstallCancelsOfferAndAcknowledgesExactPreparedResources() = fixture { f ->
        f.preferences.saveClusterLayout(custom)
        val frozen = f.freeze(selection,layout(f))
        val resume = CountDownLatch(1)
        f.initializeGate = resume
        val pending = f.scope.async { f.runtime.prepareAndroidAuto(99,frozen) }
        try {
            f.pump { f.initialized.get() == 1 }
            val exact = requireNotNull(f.owner())
            assertNull(f.prepared)
            assertFalse(exact.handle!!.ready.isDone)
            val retirement = f.runtime.retire("preparation canceled")
            assertSame(retirement,f.runtime.retire("same exact owner"))
            assertFalse(retirement.isDone)
            resume.countDown()
            f.pump { pending.isCompleted }
            assertTrue(pending.await().offers.isEmpty())
            assertTrue(f.await(retirement))
            assertNull(f.runtime.mapping(f.descriptor.id))
            assertFalse(exact.handle!!.owner.isCurrent)
            assertEquals(1,f.detached.get())
            assertSame(retirement,f.runtime.retire("still exact owner"))
            f.initializeGate = null
            val successor = f.prepare(selection,layout(f))
            assertEquals(1,successor.offers.size)
            assertEquals(custom.safe,f.mapping().target.safe)
            assertNotSame(exact.handle!!.owner,f.owner()!!.handle!!.owner)
            verify(exactly = 0) { f.session.forceReconnect(any()); f.session.stop() }
        } finally { resume.countDown(); pending.cancelAndJoin() }
    }

    @Test fun actualOfferedCustomAndSafeOnlyLayoutsAttachOnlyToExactHost() = fixture { f ->
        for (intent in listOf(custom,custom.copy(output = PixelRect(98,135,1904,745)))) {
            f.preferences.saveClusterLayout(intent)
            val offered = f.prepare(selection,layout(f,intent))
            val mapping = f.mapping()
            assertWire(offered.offers.single(),mapping)
            assertEquals(profile.panel.bounds,mapping.target.output)
            assertEquals(intent.safe,mapping.target.safe)
            val exact = requireNotNull(f.owner())
            val receipt = exact.handle!!.ready.get()
            assertFalse(exact.attach(receipt.copy(owner = SecondaryHostOwner(f.descriptor,999))).get())
            assertFalse(exact.attach(receipt.copy(geometry = receipt.geometry.copy(safe = PixelRect(0,136,2000,810)))).get())
            assertTrue(exact.bridge!!.status.value.hostAttached)
            // Legacy custom output still requires the exact frozen AndroidX host receipt.
            val display = legacy(f).display
            assertEquals(intent.output,display.effectiveOutput)
            var attaches = 0; var detaches = 0
            val bridge = object : ClusterHostOutputBridge {
                override fun attach(lease: ClusterOutputLease,surface: Surface,geometry: ClusterHostGeometry) =
                    CompletableFuture.completedFuture(true).also { attaches++ }
                override fun updateBounds(lease: ClusterOutputLease,surface: Surface,geometry: ClusterHostGeometry) =
                    CompletableFuture.completedFuture(true)
                override fun detach(lease: ClusterOutputLease,surface: Surface) =
                    CompletableFuture.completedFuture(true).also { detaches++ }
            }
            val lease = ClusterOutputLease(30,40)
            val snapshot = ClusterMapOutputSnapshot(lease,selection,ClusterProjectionProtocol.ANDROID_AUTO,display)
            val coordinator = ClusterOutputCoordinator(snapshot,bridge)
            val owner = Any(); val surface = mockk<Surface> { every { isValid } returns true }
            val host = display.host
            assertTrue(coordinator.claim(owner))
            assertTrue(coordinator.attachHost(lease,owner,surface,host).get())
            assertEquals(1,attaches)
            assertFalse(coordinator.updateBounds(lease,owner,surface,host.copy(stable = PixelRect(0,136,2000,810))).get())
            assertFalse(coordinator.isHostAttached()); assertEquals(1,detaches)
            assertFalse(coordinator.attachHost(lease,owner,surface,host.copy(displayId = host.displayId+1)).get())
            assertFalse(coordinator.attachHost(lease,owner,surface,host.copy(visible = PixelRect(0,140,2000,810))).get())
            assertEquals(1,attaches)
            assertEquals(intent.safe,snapshot.display.safe)
            assertTrue(coordinator.retire(lease,"test").get())
            assertTrue(f.await(f.manager.retireClusterVideoOutput()))
        }
    }
}
