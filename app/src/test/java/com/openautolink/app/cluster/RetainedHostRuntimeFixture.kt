package com.openautolink.app.cluster

import android.app.*
import android.content.*
import android.graphics.SurfaceTexture
import android.hardware.display.DisplayManager
import android.os.Looper
import android.view.*
import com.openautolink.app.data.*
import com.openautolink.app.secondary.*
import io.mockk.*
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.junit.Assert.*
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.shadows.ShadowDisplayManager
import org.robolectric.util.ReflectionHelpers
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicInteger

/** Historical class name retained for callers. Real registry -> direct Activity -> visible
 * TextureView -> GPU acknowledgement. No AndroidX publication, synthetic ready or lease reset. */
internal class RetainedHostRuntimeFixture(preferences: AppPreferences? = null, val fault: Int = 0,
    private val nativeGraphics: Boolean = false) {
    // Constructor instrumentation is process-wide. Concurrent fixture instances share only
    // its lifetime; each stub below still matches one exact fixture-owned texture.
    private object NativeSurfaces {
        private var users = 0
        @Synchronized fun acquire() { if (users++ == 0) mockkConstructor(Surface::class) }
        @Synchronized fun release() { check(users > 0); if (--users == 0) unmockkConstructor(Surface::class) }
    }
    val profile = ClusterDisplayProfiles.AEGEAN_ORANGE_239
    private val oldProduct = android.os.Build.PRODUCT
    private val oldActivityContexts = System.getProperty("robolectric.createActivityContexts")
    val context = RuntimeEnvironment.getApplication() as Application
    val displays = context.getSystemService(DisplayManager::class.java)
    val displayId = ShadowDisplayManager.addDisplay("w2000dp-h810dp-mdpi", "Direct owner fixture")
    val display = displays.getDisplay(displayId)
    val descriptor = SecondaryDisplayDescriptor(SecondaryOutputId("review-gm"), displayId, 1, "GM", 2000, 810, 160,
        SecondaryRoute.GM_HOSTED, SecondaryReason.NONE, "Qualified driver cluster")
    val inventory = MutableStateFlow(SecondaryInventorySnapshot(1, listOf(descriptor)))
    val selection = ClusterOutputSelection(ClusterDisplayMode.MAP_VIDEO)
    var frozen = SecondaryPreferencesSnapshot(1, mapOf(descriptor.id to SecondaryOutputPreference(true,AaSecondaryRole.CLUSTER,true,true)),
        ClusterOutputPreferences(selection,ClusterDisplayMode.MAP_VIDEO))
    var attachHosts = true
    val scope = CoroutineScope(SupervisorJob()+Dispatchers.IO)
    val gate = CountDownLatch(1)
    val initialized = AtomicInteger()
    val cleared = AtomicInteger()
    val detached = AtomicInteger()
    val bridges = CopyOnWriteArrayList<ClusterProjectionOutput>()
    val hostOwners = mutableListOf<SecondaryHostOwner>()
    val launches = mutableListOf<Intent>()
    val physical = mutableListOf<ActivityController<ClusterDirectVideoActivity>>()
    private val queued = ArrayDeque<Intent>()
    private val textures = mutableListOf<Pair<TextureView,SurfaceTexture>>()
    val runtime: SecondaryOutputRuntime
    init {
        if (nativeGraphics) NativeSurfaces.acquire() // before the original preparation budget
        System.setProperty("robolectric.createActivityContexts", "true")
        ReflectionHelpers.setStaticField(android.os.Build::class.java,"PRODUCT",profile.vehicle.product)
        shadowOf(display).setFlags(Display.FLAG_PRESENTATION)
        shadowOf(display).setState(Display.STATE_ON)
        runtime=SecondaryOutputRuntime(context,inventory,{ mapping,name ->
            val index=bridges.size
            val driver=object:ClusterRenderDriver {
                override fun initialize() {
                    initialized.incrementAndGet()
                    if ((index==1 && fault==1) || (index==0 && fault==3)) check(gate.await(8,TimeUnit.SECONDS))
                    if(index==1 && fault==2) throw IllegalStateException("controlled successor attach failure")
                }
                override fun configure(config:ClusterCodecConfig) {}
                override fun submit(frame:ClusterEncodedFrame)=true
                override fun poll()=ClusterRenderProgress(0,0)
                override fun stopDecoder() {}
                override fun clearOutput() { cleared.incrementAndGet() }
                override fun detach()=true.also { detached.incrementAndGet() }
            }
            ClusterProjectionOutput(mapping,ClusterRenderDriverFactory { _,_->driver },name).also { bridges+=it }
        },{ _,_->"exact.avc" })
    }
    fun ignition(on: Boolean, epoch: Long = inventory.value.revision + 1) {
        shadowOf(display).setState(if(on) Display.STATE_ON else Display.STATE_OFF)
        inventory.value=SecondaryInventorySnapshot(epoch,listOf(descriptor.copy(attachmentEpoch=epoch,
            route=if(on) SecondaryRoute.GM_HOSTED else SecondaryRoute.INVENTORY_ONLY,
            reason=if(on) SecondaryReason.NONE else SecondaryReason.DISCONNECTED)))
    }
    fun owner(): SecondaryOutputRuntime.RuntimeOutput? {
        val lock=ReflectionHelpers.getField<Any>(runtime,"lock")
        return synchronized(lock) {
            ReflectionHelpers.getField<Map<SecondaryOutputId,SecondaryOutputRuntime.RuntimeOutput>>(runtime,"owners")[descriptor.id]
        }
    }
    fun pump(condition:()->Boolean) {
        val until=System.nanoTime()+TimeUnit.SECONDS.toNanos(7)
        while(!condition() && System.nanoTime()<until) {
            shadowOf(Looper.getMainLooper()).idle()
            var intent=shadowOf(context as Application).nextStartedActivity
            while(intent!=null) {
                assertEquals(ClusterDirectVideoActivity::class.java.name,intent.component?.className)
                launches+=intent;queued.addLast(intent)
                intent=shadowOf(context).nextStartedActivity
            }
            if(attachHosts) while(queued.isNotEmpty()) attach(queued.removeFirst())
            Thread.sleep(2)
        }
        assertTrue("Actual direct-runtime boundary timed out: ${runtime.statuses.value}",condition())
    }
    private fun attach(intent:Intent) {
        owner()?.handle?.owner?.let { exact -> if(hostOwners.none { it===exact }) hostOwners+=exact }
        val controller=ActivityController.of(ClusterDirectVideoActivity(),intent,
            ActivityOptions.makeBasic().setLaunchDisplayId(displayId).toBundle()).create().start().resume().visible()
        physical+=controller
        val activity=controller.get()
        if(activity.isFinishing) return // A queued predecessor has no authority after exact epoch revocation.
        assertEquals(displayId,activity.display!!.displayId)
        val view=activity.findViewById<ViewGroup>(android.R.id.content).getChildAt(0) as TextureView
        activity.window.decorView.measure(View.MeasureSpec.makeMeasureSpec(2000,View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(810,View.MeasureSpec.EXACTLY))
        activity.window.decorView.layout(0,0,2000,810);view.layout(0,0,2000,810)
        ClusterDirectVideoLifecycleTest.showOrdinaryWindow(view)
        val texture=SurfaceTexture(0)
        textures+=view to texture
        ClusterDirectVideoLifecycleTest.TextureBoundary.textures[view]=texture
        if (nativeGraphics) {
            // NativeGraphics SurfaceTexture(0) has no real GL producer. Model its exact
            // OS validity, not readiness/registry policy or any other Surface instance.
            val surfaceReady = AtomicBoolean(false)
            every { constructedWith<Surface>(EqMatcher(texture)).isValid } answers { surfaceReady.get() }
            every { constructedWith<Surface>(EqMatcher(texture)).release() } answers {
                surfaceReady.set(false)
                callOriginal()
            }
            view.surfaceTextureListener!!.onSurfaceTextureAvailable(texture,2000,810)
            assertFalse("Invalid OS surface must not acknowledge the exact host", owner()!!.handle!!.ready.isDone)
            surfaceReady.set(true)
            view.surfaceTextureListener!!.onSurfaceTextureSizeChanged(texture,2000,810)
        } else {
            view.surfaceTextureListener!!.onSurfaceTextureAvailable(texture,2000,810)
        }
    }
    fun start(carPlay:Boolean,epoch:Long)=scope.async { if(carPlay)runtime.prepareCarPlay(epoch,frozen) else runtime.prepareAndroidAuto(epoch,frozen) }
    fun prepared(carPlay:Boolean,epoch:Long):Any { val job=start(carPlay,epoch);pump { job.isCompleted };return runBlocking { job.await() } }
    fun retire(prepared:Any)=if(prepared is PreparedAaSecondaryOutputs)prepared.retire("explicit reconnect") else (prepared as PreparedCarPlaySecondaryOutput).retire("explicit reconnect")
    fun offered(prepared:Any)=if(prepared is PreparedAaSecondaryOutputs)prepared.offers.isNotEmpty() else (prepared as PreparedCarPlaySecondaryOutput).configuration!=null
    fun close() {
        gate.countDown()
        try {
            val retiring=runtime.close();pump { retiring.isDone };assertTrue("Exact GPU/host retirement",retiring.get())
        } finally {
            try {
                if (nativeGraphics) textures.forEach { (view,texture) ->
                    view.surfaceTextureListener?.onSurfaceTextureDestroyed(texture)
                }
                physical.forEach { if(!it.get().isDestroyed) it.pause().stop().destroy() }
                textures.forEach { (view,texture) ->
                    ClusterDirectVideoLifecycleTest.TextureBoundary.textures.remove(view)
                    texture.release()
                    if (nativeGraphics) verify(exactly = 1) { constructedWith<Surface>(EqMatcher(texture)).release() }
                }
            } finally {
                try {
                    if (nativeGraphics) NativeSurfaces.release()
                } finally {
                    runBlocking { scope.coroutineContext[Job]!!.cancelAndJoin() }
                    ShadowDisplayManager.removeDisplay(displayId)
                    ReflectionHelpers.setStaticField(android.os.Build::class.java,"PRODUCT",oldProduct)
                    if(oldActivityContexts==null)System.clearProperty("robolectric.createActivityContexts")
                    else System.setProperty("robolectric.createActivityContexts",oldActivityContexts)
                }
            }
        }
    }
}
