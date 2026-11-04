package com.openautolink.app.cluster

import android.app.Application
import android.content.Context
import android.content.Intent
import android.graphics.Rect
import android.os.Looper
import android.view.Surface
import androidx.car.app.*
import com.openautolink.app.data.*
import com.openautolink.app.secondary.*
import io.mockk.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.junit.Assert.*
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.util.ReflectionHelpers
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicInteger

/** Actual runtime/manager/AndroidX callback path; OS Surface/GPU/display boundaries are controlled. */
internal class RetainedHostRuntimeFixture(preferences: AppPreferences? = null, val fault: Int = 0) {
    val profile = ClusterDisplayProfiles.AEGEAN_ORANGE_239
    private val oldProduct = android.os.Build.PRODUCT
    private val callbacks = mutableListOf<SurfaceCallback>()
    private val appManager = mockk<AppManager>(relaxed=true)
    private val packages = mockk<android.content.pm.PackageManager>(relaxed=true)
    val display = mockk<android.view.Display>(relaxed=true)
    val displays = mockk<android.hardware.display.DisplayManager>(relaxed=true)
    val inventory = MutableStateFlow(SecondaryInventorySnapshot(1, listOf(descriptorForInventory())))
    var attachHosts = true
    private fun descriptorForInventory() = SecondaryDisplayDescriptor(SecondaryOutputId("review-gm"),7,1,"GM",2000,810,160,
        SecondaryRoute.GM_HOSTED,SecondaryReason.NONE,"Qualified driver cluster")
    fun ignition(on: Boolean, epoch: Long = inventory.value.revision + 1) {
        every { display.state } returns if (on) android.view.Display.STATE_ON else android.view.Display.STATE_OFF
        inventory.value = SecondaryInventorySnapshot(epoch, listOf(descriptor.copy(attachmentEpoch=epoch,
            route=if(on) SecondaryRoute.GM_HOSTED else SecondaryRoute.INVENTORY_ONLY,
            reason=if(on) SecondaryReason.NONE else SecondaryReason.DISCONNECTED)))
    }
    val selection = ClusterOutputSelection(ClusterDisplayMode.MAP_VIDEO)
    val descriptor = SecondaryDisplayDescriptor(SecondaryOutputId("review-gm"),7,1,"GM",2000,810,160,
        SecondaryRoute.GM_HOSTED,SecondaryReason.NONE,"Qualified driver cluster")
    val frozen = SecondaryPreferencesSnapshot(1,mapOf(descriptor.id to SecondaryOutputPreference(true,AaSecondaryRole.CLUSTER,true,true)),
        ClusterOutputPreferences(selection,ClusterDisplayMode.MAP_VIDEO))
    val scope = CoroutineScope(SupervisorJob()+Dispatchers.IO)
    val gate = CountDownLatch(1)
    val initialized = AtomicInteger()
    val cleared = AtomicInteger()
    val detached = AtomicInteger()
    val bridges = CopyOnWriteArrayList<ClusterProjectionOutput>()
    val physical = mutableListOf<android.app.Activity>()
    var binding: ClusterBindingState.MapBinding? = null
    var callback: SurfaceCallback? = null
    var exactManager: ClusterManager? = null
    val context: Context
    val runtime: SecondaryOutputRuntime
    init {
        ReflectionHelpers.setStaticField(android.os.Build::class.java,"PRODUCT",profile.vehicle.product)
        every { display.displayId } returns 7; every { display.isValid } returns true
        every { display.state } returns android.view.Display.STATE_ON; every { display.flags } returns 0
        every { display.getRealSize(any()) } answers { firstArg<android.graphics.Point>().set(2000,810) }
        every { displays.displays } returns arrayOf(display)
        val am=mockk<android.app.ActivityManager>(relaxed=true) { every { isActivityStartAllowedOnDisplay(any(),7,any()) } returns true }
        context=mockk(relaxed=true)
        every { context.applicationContext } returns context; every { context.packageName } returns RuntimeEnvironment.getApplication().packageName
        every { context.packageManager } returns packages
        every { context.getSystemService(android.hardware.display.DisplayManager::class.java) } returns displays
        every { context.getSystemService(android.app.ActivityManager::class.java) } returns am
        mockkConstructor(CarContext::class)
        every { anyConstructed<CarContext>().packageName } returns context.packageName
        every { anyConstructed<CarContext>().getCarService(AppManager::class.java) } returns appManager
        every { anyConstructed<CarContext>().resources } returns mockk(relaxed=true) { every { getResourceName(any()) } returns "fixture:drawable/map" }
        every { anyConstructed<CarContext>().display } returns mockk { every { displayId } returns 55 }
        every { appManager.setSurfaceCallback(capture(callbacks)) } just Runs
        if(preferences==null) {
            val pref=mockk<AppPreferences>()
            mockkObject(AppPreferences.Companion);every { AppPreferences.getInstance(any()) } returns pref
            coEvery { pref.getClusterLayout(any(),any()) } returns ClusterLayoutRead(null,ClusterLayoutUnavailableReason.NO_CUSTOM_LAYOUT)
        }
        runtime=SecondaryOutputRuntime(context,inventory,{ mapping,name ->
            val index=bridges.size
            val driver=object:ClusterRenderDriver {
                override fun initialize() {
                    initialized.incrementAndGet()
                    if(index==1 && fault==1) check(gate.await(8,TimeUnit.SECONDS))
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
    fun pump(condition:()->Boolean) {
        val until=System.nanoTime()+TimeUnit.SECONDS.toNanos(7)
        while(!condition() && System.nanoTime()<until) { attachNewHost();shadowOf(Looper.getMainLooper()).idle();Thread.sleep(2) }
        assertTrue("Actual retained-runtime boundary timed out",condition())
    }
    private fun attachNewHost() {
        if (!attachHosts) return
        val next=ClusterBindingState.frozenMapBinding() ?: return
        if(binding===next || !next.coordinator.isLive())return
        binding=next
        val activity=mockk<androidx.car.app.activity.CarAppActivity>(relaxed=true) {
            every { getIntent() } returns Intent().putExtra(CLUSTER_BINDING_GENERATION_EXTRA,next.generation)
            every { getDisplay() } returns this@RetainedHostRuntimeFixture.display
        }
        physical+=activity;ClusterPhysicalHostState.record(next.generation,activity)
        val session=ClusterMapSession(next.snapshot,next.coordinator,SessionInfo.DISPLAY_TYPE_MAIN)
        session.onCreateScreen(Intent().putExtra(CLUSTER_BINDING_GENERATION_EXTRA,next.generation))
        callback=callbacks.last()
        val hostSurface=mockk<Surface>(relaxed=true) { every { isValid } returns true }
        val container=mockk<SurfaceContainer> { every { surface } returns hostSurface;every { width } returns 2000;every { height } returns 810 }
        callback!!.onSurfaceAvailable(container);callback!!.onVisibleAreaChanged(Rect(0,135,2000,810));callback!!.onStableAreaChanged(Rect(0,135,2000,810))
    }
    fun start(carPlay:Boolean,epoch:Long)=scope.async { if(carPlay)runtime.prepareCarPlay(epoch,frozen) else runtime.prepareAndroidAuto(epoch,frozen) }
    fun prepared(carPlay:Boolean,epoch:Long):Any { val job=start(carPlay,epoch);pump { job.isCompleted };return runBlocking { job.await() } }
    fun captureManager(prepared:Any) {
        val field=prepared.javaClass.getDeclaredField(if(prepared is PreparedAaSecondaryOutputs)"outputs" else "output").apply { isAccessible=true }
        val raw=field.get(prepared)
        val owner=if(raw is List<*>)raw.single() else raw
        exactManager=(owner as SecondaryOutputRuntime.RuntimeOutput).manager
    }
    fun retire(prepared:Any)=if(prepared is PreparedAaSecondaryOutputs)prepared.retire("explicit reconnect") else (prepared as PreparedCarPlaySecondaryOutput).retire("explicit reconnect")
    fun offered(prepared:Any)=if(prepared is PreparedAaSecondaryOutputs)prepared.offers.isNotEmpty() else (prepared as PreparedCarPlaySecondaryOutput).configuration!=null
    fun expand() { callback!!.onVisibleAreaChanged(Rect(0,0,2000,810));pump { runtime.liveHostedDisplay.value?.host?.visible?.top==0 } }
    fun close() {
        gate.countDown()
        val retiring=runtime.close();pump { retiring.isDone }
        // Public exact-owner cleanup also discharges an intentionally reproduced RED's real lease.
        binding?.let { exact -> exactManager?.endMapOutput(exact.snapshot.lease,selection,"fixture final exact cleanup")?.let { ack->pump { ack.isDone } } }
        physical.forEach { ClusterPhysicalHostState.destroy(it) }
        scope.cancel();ReflectionHelpers.setStaticField(android.os.Build::class.java,"PRODUCT",oldProduct)
    }
}
