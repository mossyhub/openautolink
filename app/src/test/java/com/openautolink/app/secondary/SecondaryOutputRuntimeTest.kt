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
    shadows = [SecondaryOutputRuntimeTest.DisplayPermission::class, ClusterDirectVideoLifecycleTest.TextureBoundary::class])
class SecondaryOutputRuntimeTest {
    @Implements(ActivityManager::class)
    class DisplayPermission : ShadowActivityManager() {
        @Implementation fun isActivityStartAllowedOnDisplay(context: Context, id: Int, intent: Intent): Boolean {
            intent.getStringExtra(SecondaryHostRegistry.TOKEN_EXTRA)?.let { placements[it] = id }
            return true
        }
    }
    companion object { private val placements = ConcurrentHashMap<String, Int>() }
    private val oldProduct=android.os.Build.PRODUCT
    private var attachHosts=true
    private val queued=java.util.ArrayDeque<Intent>()
    private val textures=mutableListOf<Pair<android.view.TextureView,SurfaceTexture>>()
    @org.junit.Before fun activityContexts() { System.setProperty("robolectric.createActivityContexts","true") }
    @org.junit.After fun finishFixture() { runBlocking { job.cancelAndJoin() };System.clearProperty("robolectric.createActivityContexts");org.robolectric.util.ReflectionHelpers.setStaticField(android.os.Build::class.java,"PRODUCT",oldProduct) }
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val job = SupervisorJob()
    private val scope = CoroutineScope(job + Dispatchers.Default)
    private val drivers = ConcurrentHashMap<SecondaryOutputId, Driver>()
    private val surfaces = mutableListOf<Pair<Surface, SurfaceTexture>>()
    private val activities = mutableListOf<org.robolectric.android.controller.ActivityController<ClusterDirectVideoActivity>>()
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
            while(intent!=null) { queued.addLast(intent);intent=shadowOf(context).nextStartedActivity }
            if(attachHosts) while(queued.isNotEmpty()) {
                val launch=queued.removeFirst();val token=requireNotNull(launch.getStringExtra(SecondaryHostRegistry.TOKEN_EXTRA))
                assertEquals(ClusterDirectVideoActivity::class.java.name,launch.component!!.className)
                val controller=org.robolectric.android.controller.ActivityController.of(
                    ClusterDirectVideoActivity(),launch,android.app.ActivityOptions.makeBasic()
                        .setLaunchDisplayId(requireNotNull(placements[token])).toBundle()).create().start().resume().visible()
                activities+=controller
                val activity=controller.get()
                if(activity.isFinishing) continue
                val metrics=android.util.DisplayMetrics().also { activity.display!!.getRealMetrics(it) }
                val view=activity.findViewById<android.view.ViewGroup>(android.R.id.content).getChildAt(0) as android.view.TextureView
                activity.window.decorView.measure(android.view.View.MeasureSpec.makeMeasureSpec(metrics.widthPixels,android.view.View.MeasureSpec.EXACTLY),
                    android.view.View.MeasureSpec.makeMeasureSpec(metrics.heightPixels,android.view.View.MeasureSpec.EXACTLY))
                activity.window.decorView.layout(0,0,metrics.widthPixels,metrics.heightPixels);view.layout(0,0,metrics.widthPixels,metrics.heightPixels)
                ClusterDirectVideoLifecycleTest.showOrdinaryWindow(view)
                val texture=SurfaceTexture(0);textures+=view to texture
                ClusterDirectVideoLifecycleTest.TextureBoundary.textures[view]=texture
                view.surfaceTextureListener!!.onSurfaceTextureAvailable(texture,metrics.widthPixels,metrics.heightPixels)
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
        activities.forEach { if(!it.get().isDestroyed) it.pause().stop().destroy() };activities.clear()
        textures.forEach { (view,texture) -> ClusterDirectVideoLifecycleTest.TextureBoundary.textures.remove(view);texture.release() };textures.clear()
        surfaces.forEach { (surface, texture) -> surface.release(); texture.release() }
        sessionManager?.let { manager -> runBlocking { (managerField("scope").get(manager) as CoroutineScope).coroutineContext[Job]!!.cancelAndJoin() } }
        sessionManager=null;drivers.clear();placements.clear();queued.clear()
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

    @Test fun androidAutoDensityIsFrozenPerOutputAndResetUsesCalibratedDefault() {
        val a = descriptor("A")
        val originalB = descriptor("B")
        ShadowDisplayManager.changeDisplay(originalB.displayId, "w800dp-h480dp-hdpi")
        val displayB = context.getSystemService(DisplayManager::class.java).getDisplay(originalB.displayId)
        shadowOf(displayB).setFlags(Display.FLAG_PRESENTATION)
        shadowOf(displayB).setState(Display.STATE_ON)
        val metricsB = android.util.DisplayMetrics().also(displayB::getRealMetrics)
        assertEquals(240, metricsB.densityDpi)
        val b = originalB.copy(width = metricsB.widthPixels, height = metricsB.heightPixels, densityDpi = metricsB.densityDpi)
        val runtime = runtime(MutableStateFlow(SecondaryInventorySnapshot(1, listOf(a, b))))
        val initial = prefs(listOf(a, b))
        val automatic = initial.copy(outputs = initial.outputs + (b.id to initial.outputs.getValue(b.id).copy(enabled = false)))
        val custom = automatic.copy(outputs = automatic.outputs +
            (a.id to automatic.outputs.getValue(a.id).copy(aaDensityDpi = 120)))
        try {
            val first = scope.async { runtime.prepareAndroidAuto(71, custom) }
            pump { first.isCompleted }
            val preparedFirst = runBlocking { first.await() }
            assertEquals("density preparation: ${runtime.statuses.value}; " +
                com.openautolink.app.diagnostics.DiagnosticLog.localLogs.value.takeLast(24).joinToString { it.message },
                1, preparedFirst.offers.size)
            val frozen = preparedFirst.offers.single()
            assertEquals(120, frozen.density)
            assertEquals(160, frozen.realDensity)
            val reset = custom.copy(outputs = custom.outputs +
                (a.id to custom.outputs.getValue(a.id).copy(aaDensityDpi = null)))
            val retired = runtime.retire("density explicit reconnect")
            pump { retired.isDone }; assertTrue(retired.get())
            val second = scope.async { runtime.prepareAndroidAuto(72, reset) }
            pump { second.isCompleted }
            val defaultOffer = runBlocking { second.await() }.offers.single()
            assertEquals(149, defaultOffer.density)
            assertEquals(160, defaultOffer.realDensity)
            val other = custom.copy(outputs = custom.outputs +
                (a.id to custom.outputs.getValue(a.id).copy(enabled = false)) +
                (b.id to custom.outputs.getValue(b.id).copy(enabled = true)))
            val retiredAgain = runtime.retire("choose independent display")
            pump { retiredAgain.isDone }; assertTrue(retiredAgain.get())
            val third = scope.async { runtime.prepareAndroidAuto(73, other) }
            pump { third.isCompleted }
            val otherOffer = runBlocking { third.await() }.offers.single()
            assertEquals(149, otherOffer.density)
            assertEquals(240, otherOffer.realDensity)
            assertEquals(120, other.outputs.getValue(a.id).aaDensityDpi)
            assertEquals(120, frozen.density)
        } finally { cleanup(runtime) }
    }

    @Test fun measuredDirectDrawableKeepsPhysical220AndCalibratedAaOfferWithoutChangingCarPlay() {
        val original = descriptor("Measured", 2000, 810)
        val live = context.getSystemService(DisplayManager::class.java).getDisplay(original.displayId)
        shadowOf(live).setDensityDpi(220)
        val metrics = android.util.DisplayMetrics().also(live::getRealMetrics)
        assertEquals(2000, metrics.widthPixels); assertEquals(810, metrics.heightPixels); assertEquals(220, metrics.densityDpi)
        val d = original.copy(densityDpi = metrics.densityDpi)
        var mapped: VideoMapping? = null
        val runtime = runtime(MutableStateFlow(SecondaryInventorySnapshot(1, listOf(d)))) { mapped = it }
        val frozen = prefs(listOf(d))
        try {
            val aa = scope.async { runtime.prepareAndroidAuto(80, frozen) }; pump { aa.isCompleted }
            val offer = runBlocking { aa.await() }.offers.single()
            assertEquals(149, offer.density); assertEquals(220, offer.realDensity)
            assertEquals(PixelRect(0,0,2000,810), requireNotNull(mapped).target.output)
            assertEquals(PixelRect(192,71,1788,660), requireNotNull(mapped).target.safe)
            val retired = runtime.retire("AA to CP control"); pump { retired.isDone }; assertTrue(retired.get())
            val cp = scope.async { runtime.prepareCarPlay(81, frozen) }; pump { cp.isCompleted }
            assertNotNull(runBlocking { cp.await() }.configuration)
            assertEquals(SecondaryProjectionActivity.directDefaultSafe(2000,810), requireNotNull(mapped).target.safe)
            assertEquals(220,d.densityDpi)
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

    @Test fun missingDirectSurfaceIsBoundedlyWithheldInsteadOfOfferedToPhone() {
        attachHosts=false
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
    @Test fun gmDirectOfferWaitsForActualTextureAndGpuWithoutPhoneFrames() {
        val gm=hostedDescriptor()
        val runtime=runtime(MutableStateFlow(SecondaryInventorySnapshot(1,listOf(gm))))
        try {
            attachHosts=false
            val pending=scope.async { runtime.prepareAndroidAuto(1,prefs(listOf(gm))) }
            pump { queued.isNotEmpty() }
            assertFalse(pending.isCompleted);assertTrue(drivers.isEmpty())
            attachHosts=true;pump { pending.isCompleted }
            assertEquals(listOf(3),runBlocking { pending.await() }.offers.map { it.channelId })
            assertTrue(runtime.statuses.value.getValue(gm.id).hostAttached)
            assertEquals(0L,runtime.statuses.value.getValue(gm.id).received)
            assertNull(ClusterBindingState.frozenMapBinding())
        } finally { cleanup(runtime) }
    }
    @Test fun eachSingleRoleIngressRemovalAndNativeRestartKeepPrimaryAndAudioAlive() {
        for(role in AaSecondaryRole.values()) singleRoleIngress(role)
    }
    private fun singleRoleIngress(role:AaSecondaryRole) {
        val a=descriptor("A");val b=descriptor("Z-extra")
        val flow=MutableStateFlow(SecondaryInventorySnapshot(1,listOf(b,a)))
        val runtime=runtime(flow);val collectorRelease=CountDownLatch(1)
        try {
            val frozen=prefs(listOf(a,b)).let { it.copy(outputs=it.outputs+(a.id to it.outputs.getValue(a.id).copy(aaRole=role))) }
            val preparation=scope.async { runtime.prepareAndroidAuto(1,frozen) };pump { preparation.isCompleted }
            val prepared=runBlocking { preparation.await() }
            val offer=prepared.offers.single()
            assertEquals(19,offer.channelId);assertEquals(2,offer.protocolDisplayId)
            assertEquals(if(role==AaSecondaryRole.CLUSTER)1 else 2,offer.displayType)
            assertEquals(SecondaryReason.OUTPUT_LIMIT,runtime.statuses.value.getValue(b.id).reason)
            assertFalse(drivers.containsKey(b.id))
            val session=AasdkSession(scope,context)
            session.sdrConfig=AasdkSdrConfig(secondaryOffers=prepared.offers.toTypedArray());prepared.bind(session)
            val primary=AtomicInteger();val audio=AtomicInteger()
            val manager=SessionManager(scope,context).also { sessionManager=it }
            val decoder=mockk<VideoDecoder>(relaxed=true);val player=mockk<AudioPlayer>(relaxed=true)
            every { decoder.onFrame(any()) } answers { primary.incrementAndGet();Unit }
            every { player.onAudioFrame(any()) } answers { audio.incrementAndGet();Unit }
            managerField("aasdkSession").set(manager,session);managerField("preparedSecondaryOutputs").set(manager,prepared)
            managerField("secondaryRuntime\$delegate").set(manager,lazyOf(runtime))
            managerField("_videoDecoder").set(manager,decoder);managerField("_audioPlayer").set(manager,player)
            val entered=CountDownLatch(2)
            val held=listOf("videoDispatcher","audioDispatcher").map { field -> scope.launch(managerField(field).get(manager) as CoroutineDispatcher) { entered.countDown();collectorRelease.await() } }
            assertTrue(entered.await(5,TimeUnit.SECONDS));bindCollectors(manager,session)
            val first=callback(session);pump { queuesSubscribed(session) }
            first.onSecondaryVideoState(19,10,3,0,1,true)
            first.onSecondaryVideoFrame(19,packet(7,8,5),1,10,0)
            pump { drivers[a.id]?.submitted?.get()==1 }
            assertTrue(runtime.statuses.value.getValue(a.id).phoneSelected);assertFalse(primaryAndAudioSubscribed(session))
            first.onVideoFrame(byteArrayOf(1),0,1280,720,0);first.onAudioFrame(byteArrayOf(1,2),0,48000,2)
            assertEquals(0,primary.get());assertEquals(0,audio.get())
            collectorRelease.countDown();pump { held.all { it.isCompleted } && primaryAndAudioSubscribed(session) }
            assertEquals(0,primary.get());assertEquals(0,audio.get())
            session.beginEvTelemetryGeneration();prepared.bind(session);bindCollectors(manager,session)
            val second=callback(session);pump { queuesSubscribed(session) && primaryAndAudioSubscribed(session) }
            second.onSecondaryVideoState(19,1,3,0,3,true)
            first.onSecondaryVideoState(19,100,3,0,99,true)
            first.onSecondaryVideoFrame(19,packet(7,8,5),2,100,0)
            second.onSecondaryVideoFrame(19,packet(7,8,5),3,1,0)
            pump { drivers[a.id]?.submitted?.get()==2 }
            assertEquals(3,session.secondaryVideoSelections.value.getValue(19).mediaSessionId)
            flow.value=SecondaryInventorySnapshot(2,listOf(b))
            pump { runtime.statuses.value[a.id]?.phoneSelected==false }
            second.onSecondaryVideoFrame(19,packet(5),4,1,0)
            second.onVideoFrame(byteArrayOf(1),4,1280,720,0);second.onAudioFrame(byteArrayOf(1,2),0,48000,2)
            pump { primary.get()==1 && audio.get()==1 }
            assertEquals(2,drivers.getValue(a.id).submitted.get());assertFalse(drivers.containsKey(b.id))
            flow.value=SecondaryInventorySnapshot(3,listOf(a.copy(attachmentEpoch=2),b))
            second.onSecondaryVideoFrame(19,packet(5),5,1,0)
            assertFalse(runtime.statuses.value.getValue(a.id).phoneSelected)
            assertEquals(2,drivers.getValue(a.id).submitted.get());assertEquals(1,activities.size)
        } finally { collectorRelease.countDown();cleanup(runtime) }
    }

    @Test @Config(sdk=[32])
    fun qualifiedGmAndPublicRolesEachUseOneDirectDecodeOwner() = directGmFixture(false)
    @Test @Config(sdk=[32])
    fun qualifiedGmDirectReconnectWaitsForExactGpuRetirement() = directGmFixture(true)
    private fun directGmFixture(reconnect:Boolean) {
        val gm=hostedDescriptor();val aux=descriptor("B")
        val flow=MutableStateFlow(SecondaryInventorySnapshot(1,listOf(gm)))
        val runtime=runtime(flow)
        try {
            for((index,target) in (if(reconnect)listOf(gm) else listOf(gm,aux)).withIndex()) {
                flow.value=SecondaryInventorySnapshot((index+1).toLong(),listOf(target))
                val frozen=prefs(listOf(target)).copy(outputs=mapOf(target.id to SecondaryOutputPreference(true,
                    if(target===gm)AaSecondaryRole.CLUSTER else AaSecondaryRole.AUXILIARY,true,true)))
                val start=scope.async { runtime.prepareAndroidAuto((index+1).toLong(),frozen) };pump { start.isCompleted }
                val prepared=runBlocking { start.await() };val offer=prepared.offers.single()
                assertEquals(if(target===gm)3 else 19,offer.channelId)
                assertEquals(if(target===gm)1 else 2,offer.displayType)
                assertNull(ClusterBindingState.frozenMapBinding())
                if(reconnect) {
                    val old=drivers.getValue(target.id);val gate=CountDownLatch(1);old.clearGate=gate
                    try {
                        val retired=runtime.retire("explicit direct GM reconnect");pump { old.clearEntered.count==0L }
                        assertFalse(retired.isDone)
                        val next=scope.async { runtime.prepareAndroidAuto(2,frozen) }
                        pump { runtime.statuses.value[target.id]?.reason==SecondaryReason.RETIREMENT_PENDING || next.isCompleted }
                        assertFalse(next.isCompleted);assertSame(old,drivers.getValue(target.id))
                        gate.countDown();pump { next.isCompleted };assertTrue(retired.get())
                        assertEquals(listOf(3),runBlocking { next.await() }.offers.map { it.channelId })
                        assertNotSame(old,drivers.getValue(target.id));assertTrue(runtime.statuses.value.getValue(target.id).hostAttached)
                        assertEquals(2,activities.size)
                    } finally { gate.countDown() }
                } else {
                    val session=AasdkSession(scope,context)
                    session.sdrConfig=AasdkSdrConfig(secondaryOffers=prepared.offers.toTypedArray());prepared.bind(session);prepared.collect(scope,session)
                    val primary=AtomicInteger();val audio=AtomicInteger()
                    val collectors=listOf(scope.launch(start=CoroutineStart.UNDISPATCHED) { session.videoFrames.collect { primary.incrementAndGet() } },
                        scope.launch(start=CoroutineStart.UNDISPATCHED) { session.audioFrames.collect { audio.incrementAndGet() } })
                    val callback=callback(session);pump { queuesSubscribed(session) }
                    callback.onSecondaryVideoState(offer.channelId,5,3,0,1,true)
                    callback.onSecondaryVideoFrame(offer.channelId,packet(7,8,5),1,5,0)
                    callback.onVideoFrame(byteArrayOf(1),1,1280,720,0);callback.onAudioFrame(byteArrayOf(1,2),0,48000,2)
                    pump { drivers[target.id]?.submitted?.get()==1 && primary.get()==1 && audio.get()==1 }
                    flow.value=SecondaryInventorySnapshot((index+10).toLong(),emptyList())
                    pump { runtime.statuses.value[target.id]?.phoneSelected==false }
                    callback.onSecondaryVideoFrame(offer.channelId,packet(5),2,5,0)
                    callback.onVideoFrame(byteArrayOf(1),2,1280,720,0);callback.onAudioFrame(byteArrayOf(1,2),0,48000,2)
                    pump { primary.get()==2 && audio.get()==2 }
                    assertEquals(1,drivers.getValue(target.id).submitted.get())
                    val retired=prepared.retire("next single target");pump { retired.isDone };assertTrue(retired.get())
                    runBlocking { collectors.forEach { it.cancelAndJoin() } }
                }
            }
        } finally { cleanup(runtime) }
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

    @Test fun oneOriginalBudgetWithholdsLateTargetWithoutFallbackToExtraOutput() {
        val a = descriptor("A"); val b = descriptor("B")
        val hold = CountDownLatch(1); val entered = CountDownLatch(1)
        val flow = MutableStateFlow(SecondaryInventorySnapshot(1, listOf(a, b)))
        val runtime = runtime(flow) { mapping -> if (mapping.target.id == a.id) {
            entered.countDown(); hold.await(5, TimeUnit.SECONDS)
        } }
        try {
            val started=System.nanoTime()
            val preparation = scope.async { runtime.prepareAndroidAuto(1, prefs(listOf(a, b))) }
            pump { entered.count == 0L && preparation.isCompleted }
            val prepared = runBlocking { preparation.await() }
            assertTrue(prepared.offers.isEmpty())
            assertTrue("No fresh fallback budget",TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-started)<1900)
            assertEquals(SecondaryReason.OUTPUT_LIMIT,runtime.statuses.value.getValue(b.id).reason)
            assertFalse(drivers.containsKey(b.id))
            hold.countDown()
            pump { runtime.statuses.value[a.id]?.hostAttached == false }
            assertFalse(runtime.statuses.value.getValue(a.id).advertised)
        } finally { hold.countDown(); cleanup(runtime) }
    }
}
