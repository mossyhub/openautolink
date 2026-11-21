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
    shadows = [SecondaryContentDiagnostics589Test.DisplayPermission::class, ClusterDirectVideoLifecycleTest.TextureBoundary::class])
class SecondaryContentDiagnostics589Test {
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

    @Test fun realPreparationFreezesSavedContentAndReportsDefaultOnlyForAbsentSelection() {
        val selections = com.openautolink.carplaylab.media.CarPlayClusterContent.entries.map { it as com.openautolink.carplaylab.media.CarPlayClusterContent? } + listOf(null)
        selections.forEachIndexed { index, saved ->
            val receipts = java.util.concurrent.CopyOnWriteArrayList<String>()
            mockkObject(com.openautolink.app.diagnostics.DiagnosticLog)
            every { com.openautolink.app.diagnostics.DiagnosticLog.i(any(), any()) } answers { receipts += secondArg<String>() }
            val a = descriptor("content-$index", 1280, 480)
            val runtime = runtime(MutableStateFlow(SecondaryInventorySnapshot(1, listOf(a))))
            try {
                val base = prefs(listOf(a), ClusterDisplayMode.MAP_VIDEO, true)
                val preference = base.outputs.getValue(a.id).copy(carPlayContent = saved)
                val snapshot = base.copy(outputs = mapOf(a.id to preference))
                val preparation = scope.async { runtime.prepareCarPlay(100L + index, snapshot) }
                pump { preparation.isCompleted }
                val prepared = runBlocking { preparation.await() }
                val expected = saved ?: com.openautolink.carplaylab.media.CarPlayClusterContent.DEFAULT
                assertEquals(expected.url, requireNotNull(prepared.configuration).display.initialUrl)
                assertEquals(saved, snapshot.outputs.getValue(a.id).carPlayContent) // no default persistence
                val source = if (saved == null) "default" else "stored"
                assertTrue(receipts.any { "stage=carplay_content_snapshot content=${expected.name} source=$source" in it })
                assertTrue(receipts.any { "stage=carplay_content_prepared content=${expected.name} offered=true" in it })
                assertTrue(receipts.filter { "stage=carplay_content_" in it }.none { "maps:" in it || "?" in it })
            } finally { cleanup(runtime) }
        }
    }
}
