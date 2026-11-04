package com.openautolink.app.cluster

import android.app.Application
import android.graphics.SurfaceTexture
import android.view.Surface
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@org.robolectric.annotation.ConscryptMode(org.robolectric.annotation.ConscryptMode.Mode.OFF)
@Config(sdk = [32], manifest = Config.NONE, application = Application::class)
class ClusterProjectionOutputTest {
    private val lease = ClusterOutputLease(1, 2)
    private fun mapping(): ClusterVideoMapping {
        val profile = ClusterDisplayProfiles.AEGEAN_ORANGE_239
        return ClusterVideoGeometry.androidAuto(ClusterDisplayProfiles.qualify(profile.vehicle,
            listOf(ClusterDisplayBinding(ClusterVehicleRole.DRIVER_CLUSTER, 2)),
            ClusterHostGeometry(2, profile.panel, PixelRect(0,135,2000,810),PixelRect(0,135,2000,810)))!!)
    }
    private class Driver : ClusterRenderDriver {
        val initialized = CountDownLatch(1)
        var holdInitialize: CountDownLatch? = null
        var holdConfigure: CountDownLatch? = null
        val configured = CountDownLatch(1)
        val submissions = AtomicInteger()
        val canDetach = AtomicBoolean(true)
        var failConfig = false
        var onSubmit: (() -> Unit)? = null
        val stalePolls = AtomicInteger()
        val revoked = AtomicBoolean(false)
        override fun toString() = "Driver"
        var failClear = false
        var failClearOnce = false
        var failStopOnce = false
        val clears = AtomicInteger()
        override fun initialize() { initialized.countDown(); holdInitialize?.await(3, TimeUnit.SECONDS) }
        val configureCalls = AtomicInteger()
        @Volatile var decoderName: String? = null
        override fun configure(config: ClusterCodecConfig) { decoderName = config.decoderName; if (configureCalls.incrementAndGet() > 1) revoked.set(false); configured.countDown(); holdConfigure?.await(3, TimeUnit.SECONDS); if (failConfig) error("optional codec failed") }
        override fun submit(frame: ClusterEncodedFrame): Boolean { submissions.incrementAndGet(); onSubmit?.invoke(); return true }
        override fun poll(): ClusterRenderProgress {
            if (revoked.get()) stalePolls.incrementAndGet()
            return ClusterRenderProgress(0, 0)
        }
        override fun stopDecoder() { if (failStopOnce) { failStopOnce = false; error("revocation stop failed") } }
        override fun clearOutput() { clears.incrementAndGet(); if (failClear || failClearOnce) { failClearOnce = false; error("revocation clear failed") } }
        override fun detach() = canDetach.get()
    }
    private fun packet(vararg types: Int): ByteArray = types.flatMap { listOf<Byte>(0,0,0,1,it.toByte(),1) }.toByteArray()
    private fun await(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
        while (!condition() && System.nanoTime() < deadline) Thread.sleep(5)
        assertTrue(condition())
    }
    private fun withOutput(block: (ClusterProjectionOutput, Surface, Driver) -> Unit) {
        val texture = SurfaceTexture(0); val surface = Surface(texture); val driver = Driver()
        val output = ClusterProjectionOutput(mapping(), ClusterRenderDriverFactory { _, _ -> driver })
        assertTrue(output.bindLease(lease))
        try { block(output, surface, driver) }
        finally { driver.holdInitialize?.countDown(); driver.holdConfigure?.countDown(); driver.canDetach.set(true)
            assertTrue(output.retire(lease).get(3,TimeUnit.SECONDS)); surface.release(); texture.release() }
    }
    @Test fun customEqualCanvasUpdateKeepsAndroidAutoAttachmentAndFrameAdmission() = customEqualCanvasUpdate(false)
    @Test fun customEqualCanvasUpdateKeepsCarPlayAttachmentAndFrameAdmission() = customEqualCanvasUpdate(true)
    private fun customEqualCanvasUpdate(carPlay: Boolean) {
        val profile = ClusterDisplayProfiles.AEGEAN_ORANGE_239
        val canvas = PixelRect(0, 135, 2000, 810)
        val host = ClusterHostGeometry(2, profile.panel, canvas, canvas)
        val custom = ClusterValidatedLayout(profile, host, canvas,
            PixelRect(314, 221, 1758, 689), PixelRect(594, 344, 1458, 633))
        val display = ClusterDisplayProfiles.qualify(profile.vehicle,
            listOf(ClusterDisplayBinding(profile.role, 2)), host, custom)!!
        val mapping = if (carPlay) ClusterVideoGeometry.carPlay(display) else ClusterVideoGeometry.androidAuto(display)
        val texture = SurfaceTexture(0); val surface = Surface(texture); val driver = Driver()
        val output = ClusterProjectionOutput(mapping, ClusterRenderDriverFactory { _, _ -> driver })
        assertTrue(output.bindLease(lease))
        try {
            assertTrue(output.attach(lease, surface, host).get(3, TimeUnit.SECONDS))
            assertTrue(output.updateBounds(lease, surface, host.copy(visible = profile.panel.bounds)).get(3, TimeUnit.SECONDS))
            assertTrue(output.status.value.hostAttached)
            assertEquals(custom.output, output.mapping.gmReceipt!!.effectiveOutput)
            assertEquals(custom.safe, output.mapping.gmReceipt!!.safe)
            val stream = output.beginStream(lease, Any()) { true }!!
            assertTrue(output.annexB(lease, stream, packet(7, 8, 5), 1))
            await { driver.submissions.get() == 1 }
            assertFalse(output.updateBounds(lease, surface, host.copy(visible = profile.panel.bounds, stable = profile.panel.bounds)).get(3, TimeUnit.SECONDS))
            assertFalse(output.status.value.hostAttached)
        } finally {
            assertTrue(output.retire(lease).get(3, TimeUnit.SECONDS)); surface.release(); texture.release()
        }
    }

    @Test fun combinedConfigIdrIsParsedBeforeDecodeAndHostAckIsDistinct() = withOutput { output,surface,driver ->
        val stream = output.beginStream(lease,Any()) { true }!!
        assertTrue(output.annexB(lease,stream,packet(7,8,5),10))
        assertFalse(output.status.value.hostAttached)
        assertEquals(0,driver.submissions.get())
        assertTrue(output.attach(lease,surface,mapping().qualifiedDisplay.host).get(3,TimeUnit.SECONDS))
        await { driver.submissions.get() == 1 }
        assertTrue(output.status.value.hostAttached)
        assertEquals(1,output.status.value.configurations)
        assertEquals(1,output.status.value.keyframes)
        assertEquals(0,output.status.value.presented)
    }
    @Test fun lateAttachReadyCannotPublishAfterTerminalRetirement() = withOutput { output,surface,driver ->
        driver.holdInitialize = CountDownLatch(1)
        val attached = output.attach(lease,surface,mapping().qualifiedDisplay.host)
        assertTrue(driver.initialized.await(3,TimeUnit.SECONDS))
        val retired = output.retire(lease)
        assertFalse(retired.isDone)
        driver.holdInitialize!!.countDown()
        assertFalse(attached.get(3,TimeUnit.SECONDS))
        assertTrue(retired.get(3,TimeUnit.SECONDS))
        assertFalse(output.status.value.hostAttached)
    }
    @Test fun failedDetachKeepsExactBorrowAndPreventsReplacement() = withOutput { output,surface,driver ->
        assertTrue(output.attach(lease,surface,mapping().qualifiedDisplay.host).get(3,TimeUnit.SECONDS))
        driver.canDetach.set(false)
        assertFalse(output.updateBounds(lease,surface,mapping().qualifiedDisplay.host).get(3,TimeUnit.SECONDS))
        assertFalse(output.attach(lease,surface,mapping().qualifiedDisplay.host).get(3,TimeUnit.SECONDS))
        assertFalse(output.status.value.hostAttached)
        driver.canDetach.set(true)
        assertTrue(output.detach(lease,surface).get(3,TimeUnit.SECONDS))
    }
    @Test fun revokedSourceIsRejectedAfterRendererQueueBeforeCodecSubmission() = withOutput { output,surface,driver ->
        assertTrue(output.attach(lease,surface,mapping().qualifiedDisplay.host).get(3,TimeUnit.SECONDS))
        val current = AtomicBoolean(true)
        val stream = output.beginStream(lease,Any()) { current.get() }!!
        driver.holdConfigure = CountDownLatch(1)
        assertTrue(output.annexB(lease,stream,packet(7,8,5),10))
        assertTrue(driver.configured.await(3,TimeUnit.SECONDS))
        current.set(false); driver.holdConfigure!!.countDown()
        Thread.sleep(100)
        assertEquals(0,driver.submissions.get())
        assertFalse(output.annexB(lease,stream,packet(5),11))
    }
    @Test fun streamIdentitySeparatesSuccessorFromEqualWireEpoch() = withOutput { output,surface,driver ->
        assertTrue(output.attach(lease,surface,mapping().qualifiedDisplay.host).get(3,TimeUnit.SECONDS))
        val first = output.beginStream(lease,Any()) { true }!!
        val second = output.beginStream(lease,Any()) { true }!!
        assertTrue(second.epoch > first.epoch)
        assertFalse(output.endStream(lease,first))
        assertFalse(output.annexB(lease,first,packet(7,8,5),0))
        assertTrue(output.annexB(lease,second,packet(7,8,5),1))
        await { driver.submissions.get() == 1 }
    }
    @Test fun separateSpsPpsRequireCompleteConfigAndRealIdr() = withOutput { output,surface,driver ->
        val stream = output.beginStream(lease,Any()) { true }!!
        assertFalse(output.annexB(lease,stream,packet(7),0))
        assertTrue(output.annexB(lease,stream,packet(8),0))
        assertTrue(output.annexB(lease,stream,packet(1),1))
        assertTrue(output.attach(lease,surface,mapping().qualifiedDisplay.host).get(3,TimeUnit.SECONDS))
        Thread.sleep(50); assertEquals(0,driver.submissions.get())
        assertTrue(output.annexB(lease,stream,packet(5),2))
        await { driver.submissions.get() == 1 }
    }
    @Test fun asynchronousCodecFailurePublishesLocalUnavailableWithoutNewPacket() = withOutput { output,surface,driver ->
        assertTrue(output.attach(lease,surface,mapping().qualifiedDisplay.host).get(3,TimeUnit.SECONDS))
        driver.failConfig = true
        val source = output.beginStream(lease,Any()) { true }!!
        assertTrue(output.annexB(lease,source,packet(7,8,5),0))
        await { output.status.value.error == "illegal_state" }
        assertFalse(output.status.value.hostAttached)
    }
    @Test fun exactDecoderReceiptIsAppliedToTypedAndInBandConfigurations() {
        val texture=SurfaceTexture(0); val surface=Surface(texture); val driver=Driver()
        val output=ClusterProjectionOutput(mapping(),ClusterRenderDriverFactory { _, _ -> driver },avcDecoderName="qualified.avc.decoder")
        output.bindLease(lease)
        try {
            val source=output.beginStream(lease,Any()) { true }!!
            assertTrue(output.configure(lease,source,ClusterCodecConfig(mapping().codedSize,packet(7),packet(8))))
            assertTrue(output.attach(lease,surface,mapping().qualifiedDisplay.host).get(3,TimeUnit.SECONDS))
            assertTrue(driver.configured.await(3,TimeUnit.SECONDS))
            assertEquals("qualified.avc.decoder",driver.decoderName)
            assertFalse(output.configure(lease,source,ClusterCodecConfig(mapping().codedSize,packet(7),packet(8),"other.decoder")))
            assertTrue(output.annexB(lease,source,packet(7,8,5),1))
            await { driver.configureCalls.get() == 2 }
            assertEquals("qualified.avc.decoder",driver.decoderName)
            assertNull(output.status.value.error)
        } finally { assertTrue(output.retire(lease).get(3,TimeUnit.SECONDS)); surface.release();texture.release() }
    }
    @Test fun successorBeforeQueuedEndCannotPollPreviousDecoder() = withOutput { output,surface,driver ->
        assertTrue(output.attach(lease,surface,mapping().qualifiedDisplay.host).get(3,TimeUnit.SECONDS))
        val first = output.beginStream(lease,Any()) { true }!!
        val submitted = CountDownLatch(1); val release = CountDownLatch(1)
        driver.onSubmit = { submitted.countDown(); assertTrue(release.await(3,TimeUnit.SECONDS)) }
        try {
            assertTrue(output.annexB(lease,first,packet(7,8,5),0))
            assertTrue(submitted.await(3,TimeUnit.SECONDS))
            assertNotNull(output.beginStream(lease,Any()) { true })
            driver.revoked.set(true)
            release.countDown()
            await { driver.clears.get() > 0 }
            assertEquals("old decoder must not poll under successor authority",0,driver.stalePolls.get())
        } finally { release.countDown() }
    }
    @Test fun replacedConfigurationCannotPollPreviousDecoder() = withOutput { output,surface,driver ->
        assertTrue(output.attach(lease,surface,mapping().qualifiedDisplay.host).get(3,TimeUnit.SECONDS))
        val source = output.beginStream(lease,Any()) { true }!!
        driver.holdConfigure = CountDownLatch(1)
        try {
            assertTrue(output.annexB(lease,source,packet(7,8,5),0))
            assertTrue(driver.configured.await(3,TimeUnit.SECONDS))
            driver.revoked.set(true)
            assertTrue(output.configure(lease,source,ClusterCodecConfig(mapping().codedSize,packet(7),packet(8))))
            driver.holdConfigure!!.countDown()
            await { driver.configureCalls.get() == 2 }
            assertEquals(0,driver.stalePolls.get())
            assertEquals(0,driver.submissions.get())
        } finally { driver.holdConfigure!!.countDown() }
    }
    @Test fun revocationCleanupFailureIsObservableWithoutAnotherPacket() = withOutput { output,surface,driver ->
        assertTrue(output.attach(lease,surface,mapping().qualifiedDisplay.host).get(3,TimeUnit.SECONDS))
        val current = AtomicBoolean(true)
        val source = output.beginStream(lease,Any()) { current.get() }!!
        val submitted = CountDownLatch(1); val release = CountDownLatch(1)
        driver.onSubmit = { submitted.countDown(); assertTrue(release.await(3,TimeUnit.SECONDS)) }
        try {
            assertTrue(output.annexB(lease,source,packet(7,8,5),0))
            assertTrue(submitted.await(3,TimeUnit.SECONDS))
            driver.failClearOnce = true; current.set(false); release.countDown()
            await { output.status.value.error == "illegal_state" }
            assertFalse(output.status.value.hostAttached)
            assertTrue(output.detach(lease,surface).get(3,TimeUnit.SECONDS))
        } finally { driver.failClear = false; release.countDown() }
    }
    @Test fun revocationStopFailureIsObservableAndStillClears() = withOutput { output,surface,driver ->
        assertTrue(output.attach(lease,surface,mapping().qualifiedDisplay.host).get(3,TimeUnit.SECONDS))
        val current = AtomicBoolean(true)
        val source = output.beginStream(lease,Any()) { current.get() }!!
        driver.onSubmit = { driver.failStopOnce = true; current.set(false) }
        assertTrue(output.annexB(lease,source,packet(7,8,5),0))
        await { output.status.value.error == "illegal_state" }
        assertFalse(output.status.value.hostAttached)
        await { driver.clears.get() > 0 }
    }
    @Test fun persistentRevocationClearFailureRetainsExactHostDebt() = withOutput { output,surface,driver ->
        assertTrue(output.attach(lease,surface,mapping().qualifiedDisplay.host).get(3,TimeUnit.SECONDS))
        val current = AtomicBoolean(true)
        val source = output.beginStream(lease,Any()) { current.get() }!!
        driver.onSubmit = { driver.failClear = true; current.set(false) }
        try {
            assertTrue(output.annexB(lease,source,packet(7,8,5),0))
            await { output.status.value.error == "illegal_state" }
            assertFalse(output.detach(lease,surface).get(3,TimeUnit.SECONDS))
            assertFalse(output.attach(lease,surface,mapping().qualifiedDisplay.host).get(3,TimeUnit.SECONDS))
        } finally { driver.failClear = false }
        assertTrue(output.detach(lease,surface).get(3,TimeUnit.SECONDS))
    }
    @Test fun reentrantInlineStatusRetirementCannotBeOverwrittenByPacketPublication() = withOutput { output,surface,_ ->
        assertTrue(output.attach(lease,surface,mapping().qualifiedDisplay.host).get(3,TimeUnit.SECONDS))
        val source = output.beginStream(lease,Any()) { true }!!
        val lock = output.javaClass.getDeclaredField("lock").apply { isAccessible = true }.get(output)
        val held = AtomicBoolean()
        val retired = java.util.concurrent.CompletableFuture<Boolean>()
        val observer = CoroutineScope(Dispatchers.Unconfined).launch {
            output.status.collect { status ->
                held.compareAndSet(false,Thread.holdsLock(lock))
                if (status.received > 0 && !retired.isDone) {
                    output.retire(lease).whenComplete { success, _ -> retired.complete(success == true) }
                }
            }
        }
        try {
            assertTrue(output.annexB(lease,source,packet(7,8,5),0))
            assertTrue(retired.get(3,TimeUnit.SECONDS))
            assertFalse(held.get())
            assertFalse(output.status.value.hostAttached)
            assertEquals(1,output.status.value.received)
            assertEquals(1,output.status.value.configurations)
            assertFalse(output.annexB(lease,source,packet(5),1))
            assertFalse(output.status.value.hostAttached)
        } finally { observer.cancel() }
    }

    @Test fun malformedOversizeWrongLeaseAndEndedStreamAreRejected() = withOutput { output,_,_ ->
        val stream = output.beginStream(lease,Any()) { true }!!
        assertFalse(output.annexB(lease,stream,byteArrayOf(1,2),0))
        assertFalse(output.annexB(lease,stream,ByteArray(ClusterEncodedFrame.MAX_BYTES+1),0))
        assertFalse(output.annexB(ClusterOutputLease(1,3),stream,packet(7,8,5),0))
        output.endStream(lease,stream)
        assertFalse(output.annexB(lease,stream,packet(7,8,5),0))
        assertFalse(output.bindLease(lease))
    }
}
