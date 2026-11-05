package com.openautolink.app.ui.settings.cimigration

import android.app.ActivityOptions
import android.app.Application
import android.graphics.SurfaceTexture
import android.os.Looper
import android.view.*
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import com.openautolink.app.cluster.*
import com.openautolink.app.secondary.*
import com.shilapi.xcertplay.airplay.*
import io.mockk.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.StateFlow
import org.junit.Assert.*
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController


/** Same OS/driver boundary as the passing ClusterDirectVideoRuntimeTest. No AndroidX callbacks. */
internal class DirectSavedDirectionsProtocols(private val compose: ComposeContentTestRule,
    private val app: Application, private val descriptor: SecondaryDisplayDescriptor,
    private val inventory: kotlinx.coroutines.flow.MutableStateFlow<SecondaryInventorySnapshot>) {
    private val job = SupervisorJob()
    private val scope = CoroutineScope(job + Dispatchers.Default)
    private class Driver: ClusterRenderDriver {
        override fun initialize() = Unit
        override fun configure(config: ClusterCodecConfig) = Unit
        override fun submit(frame: ClusterEncodedFrame) = true
        override fun poll() = ClusterRenderProgress(0,0)
        override fun stopDecoder() = Unit
        override fun clearOutput() = Unit
        override fun detach() = true
        override fun parkWindow() = true
        override fun resumeWindow(surface: Surface) = true
    }
    val runtime = SecondaryOutputRuntime(app, inventory,
        { mapping, name -> ClusterProjectionOutput(mapping, ClusterRenderDriverFactory { _,_ -> Driver() }, name) },
        { _,_ -> "qualified.avc" })
    private var activity: ActivityController<ClusterDirectVideoActivity>? = null
    private var view: TextureView? = null
    private var texture: SurfaceTexture? = null
    private var surfaceBoundaryInstalled = false
    private fun pump(predicate: () -> Boolean) = compose.waitUntil(5000) {
        shadowOf(Looper.getMainLooper()).idle()
        val intent = shadowOf(app).nextStartedActivity
        if (intent != null) {
            assertNull("No replacement launch while current owner is live", activity)
            assertEquals(ClusterDirectVideoActivity::class.java.name, intent.component!!.className)
            val controller = ActivityController.of(ClusterDirectVideoActivity(), intent,
                ActivityOptions.makeBasic().setLaunchDisplayId(descriptor.displayId).toBundle())
                .create().start().resume().visible()
            activity = controller
            val v = controller.get().findViewById<ViewGroup>(android.R.id.content).getChildAt(0) as TextureView
            view = v
            val root = controller.get().window.decorView
            root.measure(View.MeasureSpec.makeMeasureSpec(descriptor.width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(descriptor.height, View.MeasureSpec.EXACTLY))
            root.layout(0,0,descriptor.width,descriptor.height); v.layout(0,0,descriptor.width,descriptor.height)
            ClusterDirectVideoLifecycleTest.showOrdinaryWindow(v)
            texture = SurfaceTexture(0)
            ClusterDirectVideoLifecycleTest.TextureBoundary.textures[v] = requireNotNull(texture)
            // NativeGraphics has no real GL producer for this synthetic TextureView texture.
            // Model only its exact OS Surface, never all Surfaces or registry/GPU acknowledgments.
            val surfaceReady = java.util.concurrent.atomic.AtomicBoolean(false)
            every { constructedWith<Surface>(EqMatcher(requireNotNull(texture))).isValid } answers { surfaceReady.get() }
            every { constructedWith<Surface>(EqMatcher(requireNotNull(texture))).release() } answers {
                surfaceReady.set(false)
                callOriginal()
            }
            v.surfaceTextureListener!!.onSurfaceTextureAvailable(requireNotNull(texture),descriptor.width,descriptor.height)
            assertFalse("An invalid exact OS surface cannot complete preparation", predicate())
            surfaceReady.set(true)
            v.surfaceTextureListener!!.onSurfaceTextureSizeChanged(requireNotNull(texture),v.width,v.height)
        }
        predicate()
    }
    private fun destroyActivity() {
        val endingTexture = texture
        texture?.let { view?.surfaceTextureListener?.onSurfaceTextureDestroyed(it) }
        view?.let { ClusterDirectVideoLifecycleTest.TextureBoundary.textures.remove(it) }
        activity?.pause()?.stop()?.destroy()
        texture?.release(); texture = null; view = null; activity = null
        if (surfaceBoundaryInstalled && endingTexture != null)
            io.mockk.verify(exactly = 1) { constructedWith<Surface>(EqMatcher(endingTexture)).release() }
    }
    fun verify(preferences: SecondaryPreferencesSnapshot, hostLoss: Boolean = false,
        diagnostics: () -> List<String> = { emptyList() }) {
        // Instrument before starting the original preparation budget, not while it is running.
        mockkConstructor(Surface::class)
        surfaceBoundaryInstalled = true
        val saved = requireNotNull(preferences.outputs.getValue(descriptor.id).layout)
        // Essential-content rectangles convert inward, never expanding past their normalized box.
        fun pixels(box: SecondaryLayoutBox) = PixelRect(kotlin.math.ceil(box.left*descriptor.width).toInt(),
            kotlin.math.ceil(box.top*descriptor.height).toInt(),kotlin.math.floor(box.right*descriptor.width).toInt(),
            kotlin.math.floor(box.bottom*descriptor.height).toInt())
        val expectedSafe = if (saved.directSafeAutomatic) SecondaryProjectionActivity.directDefaultSafe(descriptor.width,descriptor.height)
            else pixels(saved.directSafe ?: saved.safe)
        fun mapping(): VideoMapping = requireNotNull(runtime.mapping(descriptor.id)).also {
            assertEquals("Main map fills whole output despite legacy outer box", PixelRect(0,0,descriptor.width,descriptor.height),it.target.output)
            assertEquals(expectedSafe,it.target.safe)
            assertNull("No hosted mapping resurrected",ClusterBindingState.frozenMapBinding())
        }
        val pending = scope.async { runtime.prepareAndroidAuto(601, preferences) }
        pump { pending.isCompleted }
        val aa = runBlocking { pending.await() }
        assertEquals("Actual AA offer boundary: selection=${preferences.legacyCluster.selection} " +
            "output=${preferences.outputs[descriptor.id]} inventory=${inventory.value} " +
            "runtime=${runtime.statuses.value} trace=${diagnostics().filter { it.contains("OPTIONAL_") || it.contains("DIRECT_CALLBACK_") || it.contains("DIRECT_WINDOW_") }.takeLast(35)}", 1, aa.offers.size)
        val map = mapping()
        val offer = aa.offers.single()
        assertEquals(2,offer.displayType)
        assertEquals(map.codecCrop.left,offer.cropLeft); assertEquals(map.codecCrop.top,offer.cropTop)
        assertEquals(map.codecCrop.right,offer.cropRight); assertEquals(map.codecCrop.bottom,offer.cropBottom)
        assertEquals(map.phoneSafeInsets.left,offer.safeLeft); assertEquals(map.phoneSafeInsets.top,offer.safeTop)
        assertEquals(map.phoneSafeInsets.right,offer.safeRight); assertEquals(map.phoneSafeInsets.bottom,offer.safeBottom)
        assertEquals(map.stableContentInsets.left,offer.stableLeft); assertEquals(map.stableContentInsets.top,offer.stableTop)
        assertEquals(map.stableContentInsets.right,offer.stableRight); assertEquals(map.stableContentInsets.bottom,offer.stableBottom)
        val retired = aa.retire("Directions fixture explicit reconnect")
        pump { retired.isDone }; assertTrue(retired.get()); destroyActivity()
        val next = scope.async { runtime.prepareAndroidAuto(602, preferences) }
        pump { next.isCompleted }
        val successor = runBlocking { next.await() }
        val nextOffer = successor.offers.single()
        for (field in offer.javaClass.fields.filter { it.type == Int::class.javaPrimitiveType && !java.lang.reflect.Modifier.isStatic(it.modifiers) })
            assertEquals("Explicit reconnect preserves ${field.name}",field.getInt(offer),field.getInt(nextOffer))
        mapping()
        val successorRetired = successor.retire("Directions fixture protocol switch")
        pump { successorRetired.isDone }; assertTrue(successorRetired.get()); destroyActivity()
        val carPlay = scope.async { runtime.prepareCarPlay(603, preferences) }
        pump { carPlay.isCompleted }
        val cp = runBlocking { carPlay.await() }
        val config = requireNotNull(cp.configuration)
        val cpMap = mapping()
        assertEquals(AirPlayInsets(top=cpMap.phoneSafeInsets.top,bottom=cpMap.phoneSafeInsets.bottom,
            left=cpMap.phoneSafeInsets.left,right=cpMap.phoneSafeInsets.right),config.display.safeArea)
        fun wire(cluster: AirPlayDisplayConfig?) = BplistCodec.decode(BplistCodec.encode(AirPlayInfoPlist.build(
            AirPlayConfig("fixture","00:00:00:00:00:00","00:00:00:00:00:00","fixture",
                AirPlayDisplayConfig(1280,720),cluster=cluster,disableAudioOutput=true),
            availableAudioFormats=emptySet()))) as Map<*,*>
        val before = wire(null)["displays"] as List<*>
        val after = wire(config.display)["displays"] as List<*>
        assertEquals("Main /info descriptor stays byte-model identical",before.first(),after.first())
        assertEquals(2,after.size)
        assertEquals(111L,((after[1] as Map<*,*>)["type"] as Number).toLong())
        if (hostLoss) {
            inventory.value = SecondaryInventorySnapshot(inventory.value.revision + 1, emptyList())
            pump { cp.configuration == null }
            assertNull("Revoked exact owner cannot retain an advertised CarPlay configuration",cp.configuration)
            assertNull("Host loss does not install legacy hosted routing",runtime.currentLiveHostedDisplay())
        }
        val cpRetired = cp.retire("Directions fixture done")
        pump { cpRetired.isDone }; assertTrue(cpRetired.get()); destroyActivity()
    }
    fun close() {
        val retired = runtime.close()
        pump { retired.isDone }; assertTrue(retired.get())
        destroyActivity(); job.cancel()
        if (surfaceBoundaryInstalled) { unmockkConstructor(Surface::class); surfaceBoundaryInstalled = false }
    }
}
