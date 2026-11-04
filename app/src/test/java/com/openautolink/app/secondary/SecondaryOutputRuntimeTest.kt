package com.openautolink.app.secondary

import android.app.Activity
import android.app.ActivityManager
import android.app.Application
import android.content.Context
import android.content.Intent
import android.graphics.SurfaceTexture
import android.hardware.display.DisplayManager
import android.os.Looper
import android.view.Display
import android.view.Surface
import androidx.test.core.app.ApplicationProvider
import com.openautolink.app.cluster.*
import com.openautolink.app.data.ClusterOutputPreferences
import com.openautolink.app.transport.aasdk.*
import com.openautolink.app.session.SessionManager
import com.openautolink.app.audio.AudioPlayer
import com.openautolink.app.video.VideoDecoder
import io.mockk.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.annotation.RealObject
import org.robolectric.shadows.ShadowActivity
import org.robolectric.shadows.ShadowActivityManager
import org.robolectric.shadows.ShadowDisplayManager

/** Real runtime, registry, bridge, bounded session ingress and collectors. Only OS display/codec
 * boundaries are shadowed. Software receipts, not physical phone/vehicle acceptance. */
@RunWith(RobolectricTestRunner::class)
@org.robolectric.annotation.ConscryptMode(org.robolectric.annotation.ConscryptMode.Mode.OFF)
@Config(sdk = [32, 34], application = Application::class,
    shadows = [SecondaryOutputRuntimeTest.DisplayPermission::class])
class SecondaryOutputRuntimeTest {
    @Implements(ActivityManager::class)
    class DisplayPermission : ShadowActivityManager() {
        @Implementation fun isActivityStartAllowedOnDisplay(context: Context, id: Int, intent: Intent): Boolean {
            intent.getStringExtra(SecondaryHostRegistry.TOKEN_EXTRA)?.let { placements[it] = id }
            return true
        }
    }
    companion object { private val placements = ConcurrentHashMap<String, Int>() }
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val job = SupervisorJob()
    private val scope = CoroutineScope(job + Dispatchers.Default)
    private val drivers = ConcurrentHashMap<SecondaryOutputId, Driver>()
    private val surfaces = mutableListOf<Pair<Surface, SurfaceTexture>>()
    private val activities = mutableListOf<org.robolectric.android.controller.ActivityController<SecondaryProjectionActivity>>()
    private var sessionManager: SessionManager? = null
    private fun managerField(name: String) = SessionManager::class.java.getDeclaredField(name).apply { isAccessible = true }
    private fun bindCollectors(manager: SessionManager, session: AasdkSession) = SessionManager::class.java
        .getDeclaredMethod("bindSessionCollectors", AasdkSession::class.java).apply { isAccessible = true }.invoke(manager, session)
    private class Driver : ClusterRenderDriver {
        val submitted = AtomicInteger()
        val cleared = AtomicInteger()
        override fun initialize() {}
        override fun configure(config: ClusterCodecConfig) { assertEquals("qualified.avc", config.decoderName) }
        override fun submit(frame: ClusterEncodedFrame): Boolean { submitted.incrementAndGet(); return true }
        override fun poll() = ClusterRenderProgress(0, 0)
        override fun stopDecoder() {}
        var clearGate: CountDownLatch? = null
        val clearEntered = CountDownLatch(1)
        var detachAcknowledged = true
        override fun clearOutput() {
            clearEntered.countDown()
            clearGate?.let { check(it.await(5, TimeUnit.SECONDS)) }
            cleared.incrementAndGet()
        }
        override fun detach() = detachAcknowledged
    }
    private fun descriptor(key: String, width: Int = 800, height: Int = 480): SecondaryDisplayDescriptor {
        val id = ShadowDisplayManager.addDisplay("w${width}dp-h${height}dp-mdpi", key)
        val display = context.getSystemService(DisplayManager::class.java).getDisplay(id)
        shadowOf(display).setFlags(Display.FLAG_PRESENTATION)
        shadowOf(display).setState(Display.STATE_ON)
        shadowOf(Looper.getMainLooper()).idle()
        return SecondaryDisplayDescriptor(SecondaryOutputId(key), id, 1, key, width, height, 160,
            SecondaryRoute.PUBLIC_ACTIVITY, SecondaryReason.NONE, "Unknown")
    }
    private fun prefs(displays: List<SecondaryDisplayDescriptor>, mode: ClusterDisplayMode = ClusterDisplayMode.MAP_VIDEO,
        carPlay: Boolean = false): SecondaryPreferencesSnapshot = SecondaryPreferencesSnapshot(1,
        displays.associate { it.id to SecondaryOutputPreference(true,
            if (it.id.value == "A") AaSecondaryRole.CLUSTER else AaSecondaryRole.AUXILIARY, true, carPlay) },
        ClusterOutputPreferences(ClusterOutputSelection(mode), mode.takeUnless { it == ClusterDisplayMode.OFF }))
    private fun runtime(flow: StateFlow<SecondaryInventorySnapshot>, hold: ((VideoMapping) -> Unit)? = null) =
        SecondaryOutputRuntime(context, flow, { mapping, name ->
            hold?.invoke(mapping)
            val driver = Driver(); drivers[mapping.target.id] = driver
            ClusterProjectionOutput(mapping, ClusterRenderDriverFactory { _, _ -> driver }, name)
        }, { _, _ -> "qualified.avc" })
    private fun pump(predicate: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (!predicate() && System.nanoTime() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            var intent = shadowOf(context as Application).nextStartedActivity
            while (intent != null) {
                val token = intent.getStringExtra(SecondaryHostRegistry.TOKEN_EXTRA)
                if (token != null) {
                    val controller = org.robolectric.android.controller.ActivityController.of(
                        SecondaryProjectionActivity(), intent, android.app.ActivityOptions.makeBasic()
                            .setLaunchDisplayId(requireNotNull(placements[token])).toBundle()).create()
                    activities += controller
                    assertFalse(controller.get().isFinishing)
                    val texture = SurfaceTexture(0); val surface = Surface(texture)
                    surfaces += surface to texture
                    val display = controller.get().display!!
                    val metrics = android.util.DisplayMetrics().also { display.getRealMetrics(it) }
                    SecondaryHostRegistry.present(controller.get(), token, surface,
                        SecondaryHostGeometry(metrics.widthPixels, metrics.heightPixels,
                            PixelRect(0, 0, metrics.widthPixels, metrics.heightPixels)))
                }
                intent = shadowOf(context).nextStartedActivity
            }
            Thread.sleep(2)
        }
        assertTrue("runtime condition timed out", predicate())
    }
    private fun callback(session: AasdkSession): AasdkSessionCallback = AasdkSession::class.java
        .getDeclaredMethod("telemetryCallback").apply { isAccessible = true }.invoke(session) as AasdkSessionCallback
    private fun queuesSubscribed(session: AasdkSession): Boolean {
        val streamField = AasdkSession::class.java.getDeclaredField("secondaryVideo").apply { isAccessible = true }
        val streams = streamField.get(session)
        val map = streams.javaClass.getDeclaredField("streams").apply { isAccessible = true }.get(streams) as Map<*, *>
        return map.isNotEmpty() && map.values.all { stream ->
            val queue = stream!!.javaClass.getDeclaredField("queue").apply { isAccessible = true }.get(stream) as MutableSharedFlow<*>
            queue.subscriptionCount.value > 0
        }
    }
    private fun primaryAndAudioSubscribed(session: AasdkSession): Boolean =
        listOf("_videoFrames", "_audioFrames").all { field ->
            val queue = AasdkSession::class.java.getDeclaredField(field).apply { isAccessible = true }
                .get(session) as MutableSharedFlow<*>
            queue.subscriptionCount.value > 0
        }
    private fun packet(vararg types: Int) = types.flatMap { listOf<Byte>(0, 0, 0, 1, it.toByte(), 1) }.toByteArray()
    private fun cleanup(runtime: SecondaryOutputRuntime) {
        val retired = runtime.close()
        pump { retired.isDone }
        assertTrue(retired.get())
        activities.forEach { it.destroy() }
        surfaces.forEach { (surface, texture) -> surface.release(); texture.release() }
        sessionManager?.let { (managerField("scope").get(it) as CoroutineScope).cancel() }
        job.cancel(); placements.clear()
        unmockkAll()
    }

    private fun hostedDescriptor(): SecondaryDisplayDescriptor {
        org.robolectric.util.ReflectionHelpers.setStaticField(android.os.Build::class.java, "PRODUCT", ClusterDisplayProfiles.AEGEAN_ORANGE_239.vehicle.product)
        return descriptor("GM", 2000, 810).copy(route = SecondaryRoute.GM_HOSTED)
    }
    @Test fun presentationOffAndIconsOnlyWithholdEveryPublicRoleForBothProtocols() {
        val a = descriptor("A", 1280, 480)
        val b = descriptor("B", 1280, 480)
        val runtime = runtime(MutableStateFlow(SecondaryInventorySnapshot(1, listOf(a, b))))
        try {
            for (mode in listOf(ClusterDisplayMode.OFF, ClusterDisplayMode.NATIVE_ICONS)) {
                val frozen = prefs(listOf(a, b), mode, carPlay = true)
                val aa = scope.async { runtime.prepareAndroidAuto(21, frozen) }
                pump { aa.isCompleted }
                val preparedAa = runBlocking { aa.await() }
                assertTrue("$mode must suppress CLUSTER and AUXILIARY offers", preparedAa.offers.isEmpty())
                System.getProperty("handoff.receipt.dir")?.let { directory ->
                    val file = java.io.File(directory, "${mode.name}-sdk${android.os.Build.VERSION.SDK_INT}.json")
                    file.parentFile.mkdirs()
                    file.writeText("""{"mode":"${mode.name}","optionalCount":${preparedAa.offers.size},"offers":[]}""")
                }
                val cp = scope.async { runtime.prepareCarPlay(22, frozen) }
                pump { cp.isCompleted }
                assertNull("$mode must suppress alternate CarPlay", runBlocking { cp.await() }.configuration)
                assertTrue("No video driver allocated", drivers.isEmpty())
                assertTrue("Saved per-output choices preserved", frozen.outputs.values.all { it.enabled && it.androidAuto && it.carPlay })
            }
        } finally { cleanup(runtime) }
    }

    @Test fun iconsOnlyDoesNotInheritHostedVideoOptOut() {
        val gm = hostedDescriptor()
        val runtime = runtime(MutableStateFlow(SecondaryInventorySnapshot(1, listOf(gm))))
        val icons = ClusterOutputSelection(ClusterDisplayMode.NATIVE_ICONS)
        val frozen = SecondaryPreferencesSnapshot(7, mapOf(gm.id to SecondaryOutputPreference(false,
            AaSecondaryRole.AUXILIARY, false, false)), ClusterOutputPreferences(icons, ClusterDisplayMode.MAP_VIDEO))
        try {
            val aa = runBlocking { runtime.prepareAndroidAuto(30, frozen) }
            assertEquals("Native icons are not per-display video consent", icons, aa.legacySelection)
            assertTrue(aa.offers.isEmpty()); assertNull(aa.hostedManager)
            val cp = runBlocking { runtime.prepareCarPlay(31, frozen) }
            assertEquals(icons, cp.legacySelection); assertNull(cp.configuration)
        } finally { cleanup(runtime) }
    }

    @Test fun reconnectWaitsForExactRetirementBeforeAndroidAutoOffer() = heldRetirementReconnect(false)
    @Test fun reconnectWaitsForExactRetirementBeforeCarPlayConfiguration() = heldRetirementReconnect(true)

    private fun heldRetirementReconnect(carPlay: Boolean) {
        val a = if (carPlay) descriptor("A", 1280, 480) else descriptor("A")
        val runtime = runtime(MutableStateFlow(SecondaryInventorySnapshot(1, listOf(a))))
        val gate = CountDownLatch(1)
        try {
            if (carPlay) {
                val first = scope.async { runtime.prepareCarPlay(11, prefs(listOf(a), carPlay = true)) }
                pump { first.isCompleted }
                assertNotNull(runBlocking { first.await() }.configuration)
            } else {
                val first = scope.async { runtime.prepareAndroidAuto(11, prefs(listOf(a))) }
                pump { first.isCompleted }
                assertEquals(1, runBlocking { first.await() }.offers.size)
            }
            val old = drivers.getValue(a.id)
            old.clearGate = gate
            val retired = runtime.retire("explicit reconnect")
            pump { old.clearEntered.count == 0L }
            assertFalse(retired.isDone)
            val second = scope.async {
                if (carPlay) runtime.prepareCarPlay(12, prefs(listOf(a), carPlay = true)).configuration != null
                else runtime.prepareAndroidAuto(12, prefs(listOf(a))).offers.size == 1
            }
            pump { runtime.statuses.value[a.id]?.reason == SecondaryReason.RETIREMENT_PENDING || second.isCompleted }
            assertFalse("Same destination must wait for exact predecessor cleanup, not freeze an empty offer", second.isCompleted)
            assertSame(old, drivers.getValue(a.id))
            gate.countDown()
            pump { second.isCompleted }
            assertTrue("Successful exact cleanup must admit successor during this preparation", runBlocking { second.await() })
            assertTrue(retired.get())
            assertNotSame(old, drivers.getValue(a.id))
            assertTrue(runtime.statuses.value.getValue(a.id).hostAttached)
        } finally { gate.countDown(); cleanup(runtime) }
    }

    @Test fun retirementDeadlineWithholdsOfferAndNextExplicitReconnectRecovers() = withHeldPredecessor { runtime, a, old, gate ->
        val second = scope.async { runtime.prepareAndroidAuto(12, prefs(listOf(a))) }
        pump { second.isCompleted }
        assertTrue(runBlocking { second.await() }.offers.isEmpty())
        assertSame(old, drivers.getValue(a.id))
        gate.countDown()
        pump { old.cleared.get() > 0 }
        val third = scope.async { runtime.prepareAndroidAuto(13, prefs(listOf(a))) }
        pump { third.isCompleted }
        assertEquals(1, runBlocking { third.await() }.offers.size)
        assertNotSame(old, drivers.getValue(a.id))
    }

    @Test fun cancelledConnectionTicketNeverClaimsFreedPredecessorSlot() = withHeldPredecessor { runtime, a, old, gate ->
        val authority = java.util.concurrent.atomic.AtomicBoolean(true)
        val second = scope.async { runtime.prepareAndroidAuto(12, prefs(listOf(a))) { authority.get() } }
        pump { runtime.statuses.value[a.id]?.reason == SecondaryReason.RETIREMENT_PENDING }
        authority.set(false)
        gate.countDown()
        pump { second.isCompleted }
        assertTrue(runBlocking { second.await() }.offers.isEmpty())
        assertSame(old, drivers.getValue(a.id))
        assertEquals(1, activities.size)
    }

    @Test fun supersededPreparationDoesNotClaimOrRetireSuccessor() = withHeldPredecessor { runtime, a, old, gate ->
        val second = scope.async { runtime.prepareAndroidAuto(12, prefs(listOf(a))) }
        pump { runtime.statuses.value[a.id]?.reason == SecondaryReason.RETIREMENT_PENDING }
        val third = scope.async { runtime.prepareAndroidAuto(13, prefs(listOf(a))) }
        val generation = SecondaryOutputRuntime::class.java.getDeclaredField("generation").apply { isAccessible = true }
        pump { generation.getLong(runtime) >= 4L }
        gate.countDown()
        pump { second.isCompleted && third.isCompleted }
        assertTrue(runBlocking { second.await() }.offers.isEmpty())
        assertEquals(1, runBlocking { third.await() }.offers.size)
        assertNotSame(old, drivers.getValue(a.id))
        assertEquals(2, activities.size)
        assertTrue(runtime.statuses.value.getValue(a.id).hostAttached)
    }

    private fun withHeldPredecessor(action: (SecondaryOutputRuntime, SecondaryDisplayDescriptor, Driver, CountDownLatch) -> Unit) {
        val a = descriptor("A")
        val runtime = runtime(MutableStateFlow(SecondaryInventorySnapshot(1, listOf(a))))
        val gate = CountDownLatch(1)
        try {
            val first = scope.async { runtime.prepareAndroidAuto(11, prefs(listOf(a))) }
            pump { first.isCompleted }
            assertEquals(1, runBlocking { first.await() }.offers.size)
            val old = drivers.getValue(a.id)
            old.clearGate = gate
            val retired = runtime.retire("explicit reconnect")
            pump { old.clearEntered.count == 0L }
            assertFalse(retired.isDone)
            action(runtime, a, old, gate)
        } finally { gate.countDown(); cleanup(runtime) }
    }

    /** A negative exact cleanup receipt is not a released slot. No native resource is fabricated. */
    @Test fun failedExactPredecessorReceiptNeverAdmitsOptionalOffer() {
        val a = descriptor("A")
        val runtime = runtime(MutableStateFlow(SecondaryInventorySnapshot(1, listOf(a))))
        val old = runtime.RuntimeOutput(a, 0, 11)
        old.construction.complete(Unit)
        old.retirement = java.util.concurrent.CompletableFuture.completedFuture(false)
        val ownersField = SecondaryOutputRuntime::class.java.getDeclaredField("owners").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val owners = ownersField.get(runtime) as MutableMap<SecondaryOutputId, SecondaryOutputRuntime.RuntimeOutput>
        owners[a.id] = old
        try {
            val next = scope.async { runtime.prepareAndroidAuto(12, prefs(listOf(a))) }
            pump { next.isCompleted }
            assertTrue(runBlocking { next.await() }.offers.isEmpty())
            assertSame(old, owners[a.id])
            assertEquals(SecondaryReason.RETIREMENT_PENDING, runtime.statuses.value.getValue(a.id).reason)
            assertTrue(drivers.isEmpty())
            assertTrue(activities.isEmpty())
        } finally { cleanup(runtime) }
    }

    @Test fun missingHostedSurfaceIsBoundedlyWithheldInsteadOfOfferedToPhone() {
        val gm = hostedDescriptor()
        val runtime = runtime(MutableStateFlow(SecondaryInventorySnapshot(1, listOf(gm))))
        try {
            val pending = scope.async { runtime.prepareAndroidAuto(1, prefs(listOf(gm))) }
            pump { pending.isCompleted }
            val prepared = runBlocking { pending.await() }
            assertTrue("Unattached GM output must not enter service discovery",prepared.offers.isEmpty())
            assertFalse(runtime.statuses.value.getValue(gm.id).hostAttached)
            assertEquals(SecondaryReason.HOST_UNAVAILABLE,runtime.statuses.value.getValue(gm.id).reason)
        } finally { cleanup(runtime) }
    }
    @Test fun hostedOfferWaitsForActualAcceptedSurfaceAndDoesNotNeedPhoneFrames() {
        val gm = hostedDescriptor()
        val runtime = runtime(MutableStateFlow(SecondaryInventorySnapshot(1, listOf(gm))))
        val callbacks = mutableListOf<androidx.car.app.SurfaceCallback>()
        val app = mockk<androidx.car.app.AppManager>(relaxed = true)
        mockkConstructor(androidx.car.app.CarContext::class)
        every { anyConstructed<androidx.car.app.CarContext>().packageName } returns "com.openautolink.app"
        every { anyConstructed<androidx.car.app.CarContext>().resources } returns mockk(relaxed = true) {
            every { getResourceName(any()) } returns "com.openautolink.app:drawable/test"
        }
        every { anyConstructed<androidx.car.app.CarContext>().display } returns mockk { every { displayId } returns 55 }
        every { anyConstructed<androidx.car.app.CarContext>().getCarService(androidx.car.app.AppManager::class.java) } returns app
        every { app.setSurfaceCallback(capture(callbacks)) } just Runs
        var hostGeneration = Long.MIN_VALUE
        // Prepare mocked OS boundaries before starting the real bounded preparation clock.
        val host = mockk<androidx.car.app.activity.CarAppActivity>(relaxed = true) {
            every { getIntent() } answers { Intent().putExtra(CLUSTER_BINDING_GENERATION_EXTRA,hostGeneration) }
            every { getDisplay() } returns context.getSystemService(DisplayManager::class.java).getDisplay(gm.displayId)
        }
        val texture = SurfaceTexture(0); val surface = Surface(texture); surfaces += surface to texture
        val container = mockk<androidx.car.app.SurfaceContainer> {
            every { getSurface() } returns surface; every { getWidth() } returns 2000; every { getHeight() } returns 810
        }
        try {
            val pending = scope.async { runtime.prepareAndroidAuto(1, prefs(listOf(gm))) }
            pump { ClusterBindingState.frozenMapBinding() != null }
            val binding = requireNotNull(ClusterBindingState.frozenMapBinding())
            hostGeneration = binding.generation
            assertFalse("Preparation cannot complete before actual surface",pending.isCompleted)
            ClusterBootstrapWindowProtector.onActivityPreCreated(host,null)
            val session = ClusterMapSession(binding.snapshot,binding.coordinator,androidx.car.app.SessionInfo.DISPLAY_TYPE_MAIN)
            session.onCreateScreen(Intent().putExtra(CLUSTER_BINDING_GENERATION_EXTRA,binding.generation))
            callbacks.single().onSurfaceAvailable(container)
            callbacks.single().onVisibleAreaChanged(android.graphics.Rect(0,135,2000,810))
            callbacks.single().onStableAreaChanged(android.graphics.Rect(0,135,2000,810))
            pump { pending.isCompleted }
            val prepared = runBlocking { pending.await() }
            assertEquals(listOf(3),prepared.offers.map { it.channelId })
            assertTrue(binding.coordinator.isHostAttached())
            assertEquals(0L,runtime.statuses.value.getValue(gm.id).received)
        } finally {
            ClusterBootstrapWindowProtector.onActivityDestroyed(host)
            cleanup(runtime)
        }
    }
    @Test fun independentClusterAndAuxIngressRemovalAndSameObjectNativeRestartKeepPrimaryAndAudioAlive() {
        val a = descriptor("A"); val b = descriptor("B")
        val flow = MutableStateFlow(SecondaryInventorySnapshot(1, listOf(b, a)))
        val runtime = runtime(flow)
        val collectorRelease = CountDownLatch(1)
        try {
            val preparation = scope.async { runtime.prepareAndroidAuto(1, prefs(listOf(a, b))) }
            pump { preparation.isCompleted }
            val prepared = runBlocking { preparation.await() }
            assertEquals(listOf(19, 20), prepared.offers.map { it.channelId })
            assertEquals(listOf(2, 3), prepared.offers.map { it.protocolDisplayId })
            assertEquals(listOf(1, 2), prepared.offers.map { it.displayType })
            assertFalse(prepared.offers.any { it.channelId == 2 || it.protocolDisplayId == 0 })
            val session = AasdkSession(scope, context)
            session.sdrConfig = AasdkSdrConfig(secondaryOffers = prepared.offers.toTypedArray())
            prepared.bind(session)
            val primary = AtomicInteger(); val audio = AtomicInteger()
            val manager = SessionManager(scope, context).also { sessionManager = it }
            val decoder = mockk<VideoDecoder>(relaxed = true)
            val player = mockk<AudioPlayer>(relaxed = true)
            every { decoder.onFrame(any()) } answers { primary.incrementAndGet(); Unit }
            every { player.onAudioFrame(any()) } answers { audio.incrementAndGet(); Unit }
            managerField("aasdkSession").set(manager, session)
            managerField("preparedSecondaryOutputs").set(manager, prepared)
            managerField("secondaryRuntime\$delegate").set(manager, lazyOf(runtime))
            managerField("_videoDecoder").set(manager, decoder)
            managerField("_audioPlayer").set(manager, player)
            // Deliberately hold the real dedicated collector dispatchers. Optional ingress
            // subscription does not acknowledge either primary/video or audio subscription.
            val collectorEntered = CountDownLatch(2)
            val heldCollectors = listOf("videoDispatcher", "audioDispatcher").map { field ->
                scope.launch(managerField(field).get(manager) as CoroutineDispatcher) {
                    collectorEntered.countDown()
                    collectorRelease.await()
                }
            }
            assertTrue(collectorEntered.await(5, TimeUnit.SECONDS))
            bindCollectors(manager, session)
            val first = callback(session)
            pump { queuesSubscribed(session) }
            first.onSecondaryVideoState(19, 10, 3, 0, 1, true)
            first.onSecondaryVideoState(20, 20, 3, 0, 2, true)
            first.onSecondaryVideoFrame(20, packet(7, 8, 5), 1, 20, 0)
            first.onSecondaryVideoFrame(19, packet(7, 8, 5), 1, 10, 0)
            pump { drivers[a.id]?.submitted?.get() == 1 && drivers[b.id]?.submitted?.get() == 1 }
            assertTrue(runtime.statuses.value.getValue(a.id).phoneSelected)
            assertFalse(primaryAndAudioSubscribed(session))
            // Production SharedFlows have no replay. These synthetic early frames truthfully
            // disappear despite ready secondary collectors; no renderer/cleanup fix is involved.
            first.onVideoFrame(byteArrayOf(1), 0, 1280, 720, 0)
            first.onAudioFrame(byteArrayOf(1, 2), 0, 48000, 2)
            assertEquals(0, primary.get()); assertEquals(0, audio.get())
            collectorRelease.countDown()
            pump { heldCollectors.all { it.isCompleted } && primaryAndAudioSubscribed(session) }
            assertEquals(0, primary.get()); assertEquals(0, audio.get())
            flow.value = SecondaryInventorySnapshot(2, listOf(b))
            pump { runtime.statuses.value[a.id]?.phoneSelected == false }
            first.onSecondaryVideoFrame(19, packet(5), 2, 10, 0)
            first.onSecondaryVideoFrame(20, packet(5), 2, 20, 0)
            first.onVideoFrame(byteArrayOf(1), 2, 1280, 720, 0)
            first.onAudioFrame(byteArrayOf(1, 2), 0, 48000, 2)
            pump { drivers[b.id]?.submitted?.get() == 2 && primary.get() == 1 && audio.get() == 1 }
            assertEquals(1, drivers.getValue(a.id).submitted.get())
            flow.value = SecondaryInventorySnapshot(3, listOf(a.copy(attachmentEpoch = 2), b))
            session.beginEvTelemetryGeneration()
            prepared.bind(session); bindCollectors(manager, session)
            val second = callback(session)
            pump { queuesSubscribed(session) }
            second.onSecondaryVideoState(20, 1, 3, 0, 3, true)
            first.onSecondaryVideoState(20, 100, 3, 0, 99, true)
            first.onSecondaryVideoFrame(20, packet(7, 8, 5), 3, 100, 0)
            second.onSecondaryVideoFrame(20, packet(7, 8, 5), 4, 1, 0)
            pump { drivers[b.id]?.submitted?.get() == 3 }
            assertEquals(3, session.secondaryVideoSelections.value.getValue(20).mediaSessionId)
            assertFalse(runtime.statuses.value.getValue(a.id).phoneSelected)
            assertEquals(2, activities.size)
        } finally { collectorRelease.countDown(); cleanup(runtime) }
    }

    @Test @Config(sdk = [32])
    fun qualifiedHostedGmAndDirectPublicAuxHaveIndependentOffersAndDecodeOwners() = hostedRuntimeFixture(false)

    @Test @Config(sdk = [32])
    fun qualifiedHostedReconnectWaitsForExactGpuRetirement() = hostedRuntimeFixture(true)

    private fun hostedRuntimeFixture(reconnect: Boolean) {
        val aux = descriptor("B")
        val originalProduct = android.os.Build.PRODUCT
        val originalId = android.os.Build.ID
        val originalRevision = android.os.Build.VERSION.INCREMENTAL
        val profile = ClusterDisplayProfiles.AEGEAN_ORANGE_239
        org.robolectric.util.ReflectionHelpers.setStaticField(android.os.Build::class.java, "PRODUCT", profile.vehicle.product)
        org.robolectric.util.ReflectionHelpers.setStaticField(android.os.Build::class.java, "ID", profile.vehicle.buildFamily)
        org.robolectric.util.ReflectionHelpers.setStaticField(android.os.Build.VERSION::class.java, "INCREMENTAL", profile.vehicle.buildRevision.toString())
        val realDisplays = context.getSystemService(DisplayManager::class.java)
        val gmDisplay = mockk<Display>(relaxed = true)
        every { gmDisplay.displayId } returns 239
        every { gmDisplay.isValid } returns true
        every { gmDisplay.state } returns Display.STATE_ON
        every { gmDisplay.flags } returns 0
        every { gmDisplay.deviceProductInfo } returns null
        every { gmDisplay.getRealSize(any()) } answers { firstArg<android.graphics.Point>().set(2000, 810) }
        val displays = mockk<DisplayManager>(relaxed = true)
        every { displays.displays } returns (realDisplays.displays + gmDisplay)
        every { displays.getDisplay(any()) } answers { if (firstArg<Int>() == 239) gmDisplay else realDisplays.getDisplay(firstArg()) }
        every { displays.registerDisplayListener(any(), any()) } answers { realDisplays.registerDisplayListener(firstArg(), secondArg()) }
        every { displays.unregisterDisplayListener(any()) } answers { realDisplays.unregisterDisplayListener(firstArg()) }
        val wrapped = object : android.content.ContextWrapper(context) {
            override fun getApplicationContext(): Context = this
            override fun getSystemService(name: String): Any? = if (name == Context.DISPLAY_SERVICE) displays else super.getSystemService(name)
        }
        val gm = SecondaryDisplayDescriptor(SecondaryOutputId("GM"), 239, 1, "Qualified GM", 2000, 810, 160,
            SecondaryRoute.GM_HOSTED, SecondaryReason.NONE, "Qualified driver cluster")
        val flow = MutableStateFlow(SecondaryInventorySnapshot(1, listOf(aux, gm)))
        val runtime = SecondaryOutputRuntime(wrapped, flow, { mapping, name ->
            val driver = Driver()
            drivers[if (mapping.gmReceipt != null) gm.id else mapping.target.id] = driver
            ClusterProjectionOutput(mapping, ClusterRenderDriverFactory { _, _ -> driver }, name)
        }, { _, _ -> "qualified.avc" })
        try {
            val frozen = prefs(listOf(gm, aux)).copy(outputs = mapOf(
                gm.id to SecondaryOutputPreference(true, AaSecondaryRole.CLUSTER, true, true),
                aux.id to SecondaryOutputPreference(true, AaSecondaryRole.AUXILIARY, true, true)))
            val preparation = scope.async { runtime.prepareAndroidAuto(1, frozen) }
            // Supply this fixture's actual acknowledged host before freezing its phone offer.
            // The paired Activity/virtual-context regression covers the real session handoff.
            val ownerField = SecondaryOutputRuntime::class.java.getDeclaredField("owners").apply { isAccessible = true }
            fun ownerMap() = ownerField.get(runtime) as Map<SecondaryOutputId, SecondaryOutputRuntime.RuntimeOutput>
            pump { ownerMap()[gm.id]?.leaseBound == true }
            val gmOwner = ownerMap().getValue(gm.id)
            val coordinator = gmOwner.manager!!.mapOutputCoordinator(gmOwner.lease, gmOwner.selection)!!
            val claim = Any(); assertTrue(coordinator.claim(claim))
            val texture = SurfaceTexture(0); val surface = Surface(texture); surfaces += surface to texture
            val attached = coordinator.attachHost(gmOwner.lease, claim, surface, gmOwner.bridge!!.mapping.gmReceipt!!.host)
            pump { attached.isDone }; assertTrue(attached.get())
            pump { preparation.isCompleted }
            val prepared = runBlocking { preparation.await() }
            assertEquals("hosted/public preparation statuses=${runtime.statuses.value}; drivers=${drivers.keys}",
                listOf(3, 19), prepared.offers.map { it.channelId })
            assertEquals(listOf(1, 2), prepared.offers.map { it.protocolDisplayId })
            assertEquals(listOf(1, 2), prepared.offers.map { it.displayType })
            if (reconnect) {
                val oldDriver = drivers.getValue(gm.id)
                val gate = CountDownLatch(1)
                oldDriver.clearGate = gate
                try {
                    val retired = runtime.retire("explicit hosted reconnect")
                    pump { oldDriver.clearEntered.count == 0L }
                    assertFalse(retired.isDone)
                    val next = scope.async { runtime.prepareAndroidAuto(2, frozen) }
                    pump { runtime.statuses.value[gm.id]?.reason == SecondaryReason.RETIREMENT_PENDING || next.isCompleted }
                    assertFalse("Hosted reconnect must not freeze optionalCount0 while predecessor is held", next.isCompleted)
                    assertSame(gmOwner, ownerMap()[gm.id])
                    gate.countDown()
                    pump { ownerMap()[gm.id]?.let { it !== gmOwner && it.leaseBound } == true }
                    val successor = ownerMap().getValue(gm.id)
                    assertEquals(2L, successor.projectionEpoch)
                    val nextCoordinator = successor.manager!!.mapOutputCoordinator(successor.lease, successor.selection)!!
                    val nextClaim = Any(); assertTrue(nextCoordinator.claim(nextClaim))
                    val nextTexture = SurfaceTexture(0); val nextSurface = Surface(nextTexture)
                    surfaces += nextSurface to nextTexture
                    val nextAttached = nextCoordinator.attachHost(successor.lease, nextClaim, nextSurface, successor.bridge!!.mapping.gmReceipt!!.host)
                    pump { nextAttached.isDone }; assertTrue(nextAttached.get())
                    pump { next.isCompleted }
                    assertTrue(retired.get())
                    assertEquals(listOf(3, 19), runBlocking { next.await() }.offers.map { it.channelId })
                    assertTrue(nextCoordinator.isHostAttached())
                    assertEquals(2, activities.size) // one public host per exact attempt; no forced GM page
                } finally { gate.countDown() }
                return
            }
            assertEquals(1, activities.size) // ONLY the public output uses a direct Activity.
            val session = AasdkSession(scope, context)
            session.sdrConfig = AasdkSdrConfig(secondaryOffers = prepared.offers.toTypedArray())
            prepared.bind(session); prepared.collect(scope, session)
            val primary = AtomicInteger(); val audio = AtomicInteger()
            scope.launch(start = CoroutineStart.UNDISPATCHED) { session.videoFrames.collect { primary.incrementAndGet() } }
            scope.launch(start = CoroutineStart.UNDISPATCHED) { session.audioFrames.collect { audio.incrementAndGet() } }
            val callback = callback(session); pump { queuesSubscribed(session) }
            callback.onSecondaryVideoState(3, 5, 3, 0, 1, true)
            callback.onSecondaryVideoState(19, 7, 3, 0, 2, true)
            callback.onSecondaryVideoFrame(3, packet(7, 8, 5), 1, 5, 0)
            callback.onSecondaryVideoFrame(19, packet(7, 8, 5), 1, 7, 0)
            callback.onVideoFrame(byteArrayOf(1), 1, 1280, 720, 0)
            callback.onAudioFrame(byteArrayOf(1, 2), 0, 48000, 2)
            pump { drivers[gm.id]?.submitted?.get() == 1 && drivers[aux.id]?.submitted?.get() == 1 && primary.get() == 1 && audio.get() == 1 }
            flow.value = SecondaryInventorySnapshot(2, listOf(aux))
            pump { runtime.statuses.value[gm.id]?.phoneSelected == false }
            callback.onSecondaryVideoFrame(3, packet(5), 2, 5, 0)
            callback.onSecondaryVideoFrame(19, packet(5), 2, 7, 0)
            pump { drivers[aux.id]?.submitted?.get() == 2 }
            assertEquals(1, drivers.getValue(gm.id).submitted.get())
        } finally {
            cleanup(runtime)
            org.robolectric.util.ReflectionHelpers.setStaticField(android.os.Build::class.java, "PRODUCT", originalProduct)
            org.robolectric.util.ReflectionHelpers.setStaticField(android.os.Build::class.java, "ID", originalId)
            org.robolectric.util.ReflectionHelpers.setStaticField(android.os.Build.VERSION::class.java, "INCREMENTAL", originalRevision)
        }
    }

    @Test fun carPlayConflictWithholdsAlternateWithoutLaunchingAndPreservesLegacyIntent() {
        val a = descriptor("A"); val b = descriptor("B").copy(route = SecondaryRoute.GM_HOSTED)
        val flow = MutableStateFlow(SecondaryInventorySnapshot(1, listOf(a, b)))
        val runtime = runtime(flow)
        try {
            val frozen = prefs(listOf(a, b), carPlay = true)
            val prepared = runBlocking { runtime.prepareCarPlay(1, frozen) }
            assertNull(prepared.configuration); assertNull(prepared.listener)
            assertEquals(frozen.legacyCluster.selection, prepared.legacySelection)
            assertTrue(runtime.statuses.value.values.all { it.reason == SecondaryReason.CARPLAY_DESTINATION_CONFLICT })
            assertNull(shadowOf(context as Application).nextStartedActivity)
            assertTrue(frozen.outputs.values.all { it.enabled && it.carPlay })
        } finally { cleanup(runtime) }
    }

    @Test fun carPlayVideoPublicDestinationHasIndependentListener() {
        val a = descriptor("A", 1280, 480)
        val flow = MutableStateFlow(SecondaryInventorySnapshot(1, listOf(a)))
        val runtime = runtime(flow)
        try {
            val preparation = scope.async { runtime.prepareCarPlay(1, prefs(listOf(a), ClusterDisplayMode.MAP_VIDEO, true)) }
            pump { preparation.isCompleted }
            val prepared = runBlocking { preparation.await() }
            assertNotNull(prepared.configuration)
            assertEquals(ClusterDisplayMode.MAP_VIDEO, prepared.legacySelection.mode)
            val listener = prepared.listener!!
            listener.onStreamActive(1, true)
            listener.onFrame(1, packet(7, 8, 5))
            pump { drivers[a.id]?.submitted?.get() == 1 }
            prepared.revoke()
            listener.onFrame(1, packet(5))
            assertFalse(runtime.statuses.value.getValue(a.id).phoneSelected)
            assertEquals(1, drivers.getValue(a.id).submitted.get())
        } finally { cleanup(runtime) }
    }

    @Test fun oneJointBudgetExcludesLateHostWithoutSacrificingReadyOutput() {
        val a = descriptor("A"); val b = descriptor("B")
        val hold = CountDownLatch(1); val entered = CountDownLatch(1)
        val flow = MutableStateFlow(SecondaryInventorySnapshot(1, listOf(a, b)))
        val runtime = runtime(flow) { mapping -> if (mapping.target.id == a.id) {
            entered.countDown(); hold.await(5, TimeUnit.SECONDS)
        } }
        try {
            val preparation = scope.async { runtime.prepareAndroidAuto(1, prefs(listOf(a, b))) }
            pump { entered.count == 0L && preparation.isCompleted }
            val prepared = runBlocking { preparation.await() }
            assertEquals(1, prepared.offers.size)
            assertEquals(2, prepared.offers.single().displayType)
            hold.countDown()
            pump { runtime.statuses.value[a.id]?.hostAttached == false }
            assertFalse(runtime.statuses.value.getValue(a.id).advertised)
        } finally { hold.countDown(); cleanup(runtime) }
    }
}
