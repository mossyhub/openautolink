package com.openautolink.app.cluster

import android.app.Application
import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Looper
import android.view.Display
import android.view.Surface
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import com.openautolink.app.carplay.OalCarPlayClusterOutput
import com.openautolink.app.data.*
import com.openautolink.app.session.SessionManager
import com.openautolink.app.transport.aasdk.*
import com.shilapi.xcertplay.media.CarPlayVideoQualification
import com.shilapi.xcertplay.media.CarPlayVideoSelection
import io.mockk.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/** Actual offer consumers + coordinator, real scoped storage/geometry; only device boundaries mocked. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32], application = Application::class, manifest = Config.NONE)
class ClusterLayoutConsumptionIntegrationTest {
    private val profile = ClusterDisplayProfiles.AEGEAN_ORANGE_239
    private val host = ClusterHostGeometry(7, profile.panel, PixelRect(0,135,2000,810), PixelRect(0,135,2000,810))
    private val layoutScope = ClusterLayoutScope(profile.vehicle, profile.id, profile.role, profile.panel)
    private val custom = SavedClusterLayout(layoutScope, host.visible, PixelRect(200,150,1800,750), PixelRect(650,250,1300,600))
    private val selection = ClusterOutputSelection(ClusterDisplayMode.MAP_VIDEO)
    private class Store : DataStore<Preferences> {
        private val state = MutableStateFlow<Preferences>(emptyPreferences())
        override val data: Flow<Preferences> = state
        override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences =
            transform(state.value).also { state.value = it }
    }
    private lateinit var active: com.openautolink.app.secondary.HostedSecondaryRuntimeFixture
    private data class Offered(val wire: AasdkSecondaryVideoOffer, val mapping: ClusterVideoMapping)
    private suspend fun prepare(manager: SessionManager, session: AasdkSession): Offered {
        val prepared = active.prepare(selection)
        return Offered(prepared.offers.single(), active.mapping())
    }
    private fun await(result: CompletableFuture<Boolean>) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (!result.isDone && System.nanoTime() < deadline) { shadowOf(Looper.getMainLooper()).idle(); Thread.sleep(5) }
        assertTrue(result.get(1, TimeUnit.SECONDS))
    }
    private fun fixture(block: suspend (Context, AppPreferences, SessionManager, AasdkSession) -> Unit) = runBlocking {
        active = com.openautolink.app.secondary.HostedSecondaryRuntimeFixture(host.displayId)
        mockkObject(CarPlayVideoQualification)
        every { CarPlayVideoQualification.select(1280,480,30,false) } returns CarPlayVideoSelection(1280,480,30,"exact.avc")
        try { block(active.context, active.preferences, active.manager, active.session) }
        finally { active.close() }
    }

    private fun assertCustom(mapping: ClusterVideoMapping) {
        assertEquals(custom.output, mapping.qualifiedDisplay.effectiveOutput)
        assertEquals(custom.safe, mapping.qualifiedDisplay.safe)
        assertEquals(profile.id, mapping.qualifiedDisplay.profile.id)
        assertEquals(profile.output, mapping.qualifiedDisplay.profile.output)
        assertEquals(host, mapping.qualifiedDisplay.host)
        assertNotNull(mapping.qualifiedDisplay.validatedLayout)
        assertTrue(custom.safe.contains(mapping.drawableToHost.forward(mapping.safeInDrawable).inward()))
    }

    @Test fun aaProductionOfferSerializesCustomInsetsAndFreezesUntilNextOfferThenReset() = fixture { _, prefs, manager, session ->
        prefs.saveClusterLayout(custom)
        val offer = withContext(Dispatchers.IO) { prepare(manager,session) }
        val config = AasdkSdrConfig(secondaryOffers = arrayOf(offer.wire))
        assertSame(offer.wire, config.secondaryOffers.single())
        assertCustom(offer.mapping)
        assertEquals(listOf(360,80,400,120), listOf(offer.wire.safeLeft,offer.wire.safeTop,offer.wire.safeRight,offer.wire.safeBottom))
        assertEquals(listOf(0,120,1280,600), listOf(offer.wire.cropLeft,offer.wire.cropTop,offer.wire.cropRight,offer.wire.cropBottom))
        assertEquals(offer.wire.safeTop, offer.wire.stableTop)
        assertNull(manager.clusterLayoutSelectionReason.value)
        prefs.saveClusterLayout(custom.copy(safe = PixelRect(700,300,1200,550)))
        assertCustom(offer.mapping)
        await(manager.retireClusterVideoOutput())
        val changed = withContext(Dispatchers.IO) { prepare(manager,session) }
        assertNotEquals(offer.wire.safeLeft,changed.wire.safeLeft)
        await(manager.retireClusterVideoOutput())
        prefs.resetClusterLayout(layoutScope,host.visible)
        val reset = withContext(Dispatchers.IO) { prepare(manager,session) }
        assertNull(reset.mapping.qualifiedDisplay.validatedLayout)
        assertEquals(PixelRect(98,135,1904,745), reset.mapping.qualifiedDisplay.effectiveOutput)
        assertEquals(profile.safe,reset.mapping.qualifiedDisplay.safe)
        assertEquals(ClusterLayoutFallbackReason.NO_CUSTOM_LAYOUT,manager.clusterLayoutSelectionReason.value)
        assertNull(manager.clusterVideoStatus.value.error)
        verify(exactly = 0) { session.forceReconnect(any()); session.stop() }
    }

    @Test fun carPlayProductionConfigurationReceivesPixelInsetsAndFreezesUntilReset() = fixture { ctx,prefs,_,_ ->
        prefs.saveClusterLayout(custom)
        val output = withContext(Dispatchers.IO) { OalCarPlayClusterOutput.prepare(ctx,selection) }
        try {
            val config = requireNotNull(output.configuration)
            assertEquals(1280,config.display.widthPixels); assertEquals(480,config.display.heightPixels)
            assertEquals(listOf(360,80,400,120), requireNotNull(config.display.safeArea).let { listOf(it.left,it.top,it.right,it.bottom) })
            assertEquals("exact.avc",config.videoSelection.avcDecoderName)
            assertNull(output.snapshot().layoutSelectionReason)
            prefs.resetClusterLayout(layoutScope,host.visible)
            assertEquals(360,config.display.safeArea!!.left)
            assertNull(output.snapshot().output.error)
        } finally { await(output.retire()) }
        val reset = withContext(Dispatchers.IO) { OalCarPlayClusterOutput.prepare(ctx,selection) }
        try {
            assertNotNull(reset.configuration)
            assertNotEquals(360,reset.configuration!!.display.safeArea!!.left)
            assertEquals(ClusterLayoutFallbackReason.NO_CUSTOM_LAYOUT,reset.snapshot().layoutSelectionReason)
            assertNull(reset.snapshot().output.error)
        } finally { await(reset.retire()) }
    }

    @Test fun staleFullCanvasFallsBackInBothProductionConsumersWithoutPrimaryError() = fixture { ctx,prefs,manager,session ->
        prefs.saveClusterLayout(custom.copy(canvas = profile.panel.bounds))
        val offer = withContext(Dispatchers.IO) { prepare(manager,session) }
        assertNull(offer.mapping.qualifiedDisplay.validatedLayout)
        assertEquals(ClusterLayoutFallbackReason.CANVAS_CHANGED,manager.clusterLayoutSelectionReason.value)
        assertNull(manager.clusterVideoStatus.value.error)
        await(manager.retireClusterVideoOutput())
        val output = withContext(Dispatchers.IO) { OalCarPlayClusterOutput.prepare(ctx,selection) }
        try {
            assertNotNull(output.configuration)
            assertEquals(ClusterLayoutFallbackReason.CANVAS_CHANGED,output.snapshot().layoutSelectionReason)
            assertNull(output.snapshot().output.error)
        } finally { await(output.retire()) }
        verify(exactly = 0) { session.forceReconnect(any()); session.stop() }
    }

    @Test fun retirementBetweenReservationAndInstallCancelsOfferAndAcknowledgesExactPreparedResources() = fixture { ctx,prefs,manager,session ->
        prefs.saveClusterLayout(custom)
        val entered = java.util.concurrent.CountDownLatch(1)
        val resume = java.util.concurrent.CountDownLatch(1)
        val pm = ctx.packageManager
        every { pm.setComponentEnabledSetting(any(), android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_ENABLED, any()) } answers {
            entered.countDown()
            check(resume.await(5,TimeUnit.SECONDS))
        }
        val pending = CoroutineScope(currentCoroutineContext()).async(Dispatchers.IO) {
            active.runtime.prepareAndroidAuto(99, active.snapshot(selection))
        }
        try {
            assertTrue(entered.await(5,TimeUnit.SECONDS))
            // beginMapOutput has constructed the real manager/renderer and installed its
            // process binding, but preparation has not published the session output/offer.
            assertNull(active.prepared)
            val retirement = active.runtime.retire("preparation canceled")
            assertSame(retirement,active.runtime.retire("same exact owner"))
            assertFalse(retirement.isDone)
            resume.countDown()
            assertTrue(pending.await().offers.isEmpty())
            await(retirement)
            assertNull(active.prepared)
            assertNull(active.runtime.mapping(active.descriptor.id))
            assertSame(retirement,active.runtime.retire("still exact owner"))
            // Cleanup discharged actual process admission, not merely a fabricated success.
            val successor = withContext(Dispatchers.IO) { prepare(manager,session) }
            assertCustom(successor.mapping)
            verify(exactly = 0) { session.forceReconnect(any()); session.stop() }
        } finally { resume.countDown(); pending.cancelAndJoin() }
    }

    @Test fun actualOfferedCustomAndSafeOnlyLayoutsAttachOnlyToExactHost() = fixture { _,prefs,manager,session ->
        for (intent in listOf(custom,custom.copy(output = PixelRect(98,135,1904,745)))) {
            prefs.saveClusterLayout(intent)
            val offer = withContext(Dispatchers.IO) { prepare(manager,session) }
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
            val snapshot = ClusterMapOutputSnapshot(lease,selection,ClusterProjectionProtocol.ANDROID_AUTO,offer.mapping.qualifiedDisplay)
            val coordinator = ClusterOutputCoordinator(snapshot,bridge)
            val owner = Any(); val surface = mockk<Surface> { every { isValid } returns true }
            assertTrue(coordinator.claim(owner))
            assertTrue(coordinator.attachHost(lease,owner,surface,host).get())
            assertEquals(1,attaches)
            // Output/safe still fit: even a one-pixel stable-only change must reject this frozen custom receipt.
            assertFalse(coordinator.updateBounds(lease,owner,surface,host.copy(stable = PixelRect(0,136,2000,810))).get())
            assertFalse(coordinator.isHostAttached()); assertEquals(1,detaches)
            assertFalse(coordinator.attachHost(lease,owner,surface,host.copy(displayId = 8)).get())
            assertFalse(coordinator.attachHost(lease,owner,surface,host.copy(visible = PixelRect(0,140,2000,810))).get())
            assertEquals(1,attaches)
            assertEquals(intent.safe,snapshot.display.safe)
            assertTrue(coordinator.retire(lease,"test").get())
            await(manager.retireClusterVideoOutput())
        }
    }
}
