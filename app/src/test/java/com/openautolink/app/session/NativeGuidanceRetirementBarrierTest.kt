package com.openautolink.app.session

import android.app.*
import android.content.*
import android.content.pm.PackageManager
import android.graphics.SurfaceTexture
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Looper
import android.view.*
import androidx.car.app.CarContext
import androidx.car.app.navigation.NavigationManager
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleRegistry
import com.openautolink.app.cluster.*
import com.openautolink.app.data.*
import com.openautolink.app.secondary.*
import com.openautolink.app.transport.ControlMessage
import com.openautolink.app.transport.aasdk.AasdkSession
import io.mockk.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.*
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.*
import org.robolectric.shadows.ShadowDisplayManager
import org.robolectric.util.ReflectionHelpers
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Real direct host/process claim, held render retirement, SessionManager native factory,
 * launched binding intent, service-created native session, and sourced AA Trip consumer.
 * Only render hardware, external transport and the AndroidX Binder endpoint are substituted. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[32], application=Application::class, shadows=[ClusterDirectVideoLifecycleTest.Admission::class, ClusterDirectVideoLifecycleTest.TextureBoundary::class])
@ConscryptMode(ConscryptMode.Mode.OFF)
class NativeGuidanceRetirementBarrierTest {
    private val app = RuntimeEnvironment.getApplication() as Application
    private fun pump(predicate: () -> Boolean) {
        val end = System.nanoTime() + TimeUnit.SECONDS.toNanos(8)
        while (!predicate() && System.nanoTime() < end) { shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(10)); Thread.sleep(3) }
        assertTrue("actual lifecycle completion", predicate())
    }
    private class Driver : ClusterRenderDriver {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val detaches = AtomicInteger()
        @Volatile var accepted = true
        override fun initialize() = Unit
        override fun configure(config: ClusterCodecConfig) = Unit
        override fun submit(frame: ClusterEncodedFrame) = true
        override fun poll() = ClusterRenderProgress(0,0)
        override fun stopDecoder() = Unit
        override fun clearOutput() = Unit
        override fun detach(): Boolean { detaches.incrementAndGet(); entered.countDown(); check(release.await(12,TimeUnit.SECONDS)); return accepted }
    }
    @Test fun enabledVideoRowCannotRaceNativeAdmissionBeforeExactGpuRetirement() = journey(true)
    @Test fun disabledVideoRowCannotRaceNativeAdmissionBeforeExactGpuRetirement() = journey(false)

    @Test fun failedExactWithdrawalAckKeepsNativeAdmissionClosed() = journey(true, outcome="failed")
    @Test fun cancelledConnectionCannotInstallNativeAfterLateExactCleanup() = journey(true, outcome="cancelled")
    @Test fun supersededConnectionCannotReplaceNewNativeOwner() = journey(true, outcome="superseded")
    @Test fun carPlayNativeFactoryWaitsForExactPredecessorAndPublishesSourcedTrip() = journey(true, cp=true)

    private fun journey(enabled: Boolean, outcome: String = "success", cp: Boolean = false) {
        shadowOf(app).grantPermissions(android.Manifest.permission.RECORD_AUDIO,android.Manifest.permission.ACCESS_FINE_LOCATION)
        com.openautolink.app.diagnostics.DiagnosticLog.clearLocal()
        listOf(ClusterDirectVideoActivity::class.java, ClusterProjectionOutput::class.java, ClusterVideoRenderer::class.java, ClusterManager::class.java).forEach { Class.forName(it.name) }
        val oldProduct = Build.PRODUCT
        ReflectionHelpers.setStaticField(Build::class.java,"PRODUCT",ClusterDisplayProfiles.AEGEAN_ORANGE_239.vehicle.product)
        System.setProperty("robolectric.createActivityContexts","true")
        val id = ShadowDisplayManager.addDisplay("w1600dp-h600dp-mdpi","Driver target")
        val display = app.getSystemService(DisplayManager::class.java).getDisplay(id)
        shadowOf(display).setFlags(Display.FLAG_PRESENTATION); shadowOf(display).setState(Display.STATE_ON)
        val descriptor = SecondaryDisplayDescriptor(SecondaryOutputId("barrier-target"),id,1,"Actual",1600,600,160,SecondaryRoute.GM_HOSTED,SecondaryReason.NONE,"Driver display")
        val driver = Driver()
        val job = SupervisorJob(); val scope = CoroutineScope(job + Dispatchers.Default)
        val rejectAck = java.util.concurrent.atomic.AtomicBoolean(false)
        var bridge:ClusterProjectionOutput?=null
        val runtime = SecondaryOutputRuntime(app, MutableStateFlow(SecondaryInventorySnapshot(1,listOf(descriptor))),
            { mapping,name -> spyk(ClusterProjectionOutput(mapping,ClusterRenderDriverFactory { _,_ -> driver },name)).also { actual ->
                bridge=actual
                every { actual.retire(any()) } answers {
                    @Suppress("UNCHECKED_CAST")
                    val real = callOriginal() as java.util.concurrent.CompletableFuture<Boolean>
                    real.thenApply { it && !rejectAck.get() }
                }
            } },{ _,_ -> "qualified.avc" })
        val previous = ReflectionHelpers.getStaticField<SecondaryOutputRuntime?>(SecondaryOutputRuntime::class.java,"process")
        ReflectionHelpers.setStaticField(SecondaryOutputRuntime::class.java,"process",runtime)
        val file = java.io.File(app.filesDir,"barrier-${java.util.UUID.randomUUID()}.preferences_pb")
        val storeJob = SupervisorJob()
        val store = androidx.datastore.preferences.core.PreferenceDataStoreFactory.create(scope=CoroutineScope(storeJob+Dispatchers.IO),produceFile={file})
        val prefs = AppPreferences::class.java.getDeclaredConstructor(androidx.datastore.core.DataStore::class.java).apply { isAccessible=true }.newInstance(store)
        mockkObject(AppPreferences.Companion)
        every { AppPreferences.getInstance(any()) } returns prefs
        val manager = SessionManager(scope, app)
        val session = mockk<AasdkSession>(relaxed=true)
        val controls = MutableSharedFlow<ControlMessage>(extraBufferCapacity=8)
        every { session.controlMessages } returns controls
        every { session.videoFrames } returns MutableSharedFlow()
        every { session.audioFrames } returns MutableSharedFlow()
        every { session.secondaryVideoFrames } returns emptyFlow()
        every { session.secondaryVideoSelections } returns MutableStateFlow(emptyMap())
        every { session.reconnectAttempt } returns MutableStateFlow(0)
        every { session.vehicleEnergyForecast } returns MutableStateFlow(null)
        every { session.negotiatedCodecType } returns MutableStateFlow(0)
        every { session.evTelemetryToken } returns "native-barrier"
        fun field(name:String)=SessionManager::class.java.getDeclaredField(name).apply { isAccessible=true }
        field("aasdkSession").set(manager,session)
        val component=ComponentName(app,"com.openautolink.app.cluster.OalClusterService")
        var controller:ActivityController<ClusterDirectVideoActivity>?=null
        var view:TextureView?=null
        var texture:SurfaceTexture?=null
        var native:ClusterMainSession?=null
        var successor:Deferred<Any>?=null
        var staleSuccessor:Deferred<Any>?=null
        var oldPrepared:PreparedAaSecondaryOutputs?=null
        var exactOutput:SecondaryOutputRuntime.RuntimeOutput?=null
        var cpHost:com.openautolink.app.carplay.OalCarPlayMetadataHost?=null
        try {
            runBlocking {
                prefs.setClusterOutputSelection(ClusterOutputSelection(ClusterDisplayMode.MAP_VIDEO,nativeIconsWithMap=true))
                prefs.updateSecondaryDisplayPreferences(0,mapOf(descriptor.id to SecondaryOutputPreference(true,AaSecondaryRole.AUXILIARY,false,false)))
            }
            val predecessor=scope.async { manager.prepareAndroidAutoSecondaryOutputs(app) }
            pump {
                val intent=shadowOf(app).nextStartedActivity
                if(intent!=null) {
                    assertEquals(ClusterDirectVideoActivity::class.java.name,intent.component!!.className)
                    controller=ActivityController.of(ClusterDirectVideoActivity(),intent,ActivityOptions.makeBasic().setLaunchDisplayId(id).toBundle()).create().start().resume().visible()
                    val activity=controller!!.get()
                    view=activity.findViewById<ViewGroup>(android.R.id.content).getChildAt(0) as TextureView
                    activity.window.decorView.measure(View.MeasureSpec.makeMeasureSpec(1600,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(600,View.MeasureSpec.EXACTLY))
                    activity.window.decorView.layout(0,0,1600,600);view!!.layout(0,0,1600,600)
                    ClusterDirectVideoLifecycleTest.showOrdinaryWindow(view!!)
                    texture=SurfaceTexture(0);ClusterDirectVideoLifecycleTest.TextureBoundary.textures[view!!]=texture!!
                    view!!.surfaceTextureListener!!.onSurfaceTextureAvailable(texture!!,1600,600)
                }
                predecessor.isCompleted
            }
            oldPrepared=runBlocking { predecessor.await() }
            assertEquals("real predecessor offer: ${runtime.statuses.value}",1,oldPrepared!!.offers.size)
            assertNull("GM Video remains native suppressed despite stored coexistence true",field("_clusterManager").get(manager))
            assertEquals(PackageManager.COMPONENT_ENABLED_STATE_DISABLED,app.packageManager.getComponentEnabledSetting(component))
            assertNull("actual direct process claim held",ClusterProcessAdmission.acquire(Any()))
            exactOutput=(runtime.javaClass.getDeclaredField("owners").apply { isAccessible=true }.get(runtime) as Map<*,*>).values.single() as SecondaryOutputRuntime.RuntimeOutput
            runBlocking {
                prefs.setClusterOutputSelection(ClusterOutputSelection(ClusterDisplayMode.NATIVE_ICONS,false,false,nativeIconsWithMap=false))
                val frozen=prefs.getSecondaryDisplayPreferences()
                prefs.updateSecondaryDisplayPreferences(frozen.revision,mapOf(descriptor.id to frozen.outputs.getValue(descriptor.id).copy(enabled=enabled)))
            }
            if(cp) manager.retireClusterVideoOutput() // actual projection-source switch boundary
            successor=scope.async { if(cp) runtime.prepareCarPlay(2,prefs.getSecondaryDisplayPreferences()) else manager.prepareAndroidAutoSecondaryOutputs(app) }
            pump { driver.entered.count == 0L }
            pump { successor!!.isCompleted || com.openautolink.app.diagnostics.DiagnosticLog.localLogs.value.any { it.message.contains("stage=native_retirement_wait") && it.message.contains("session=2 ") } }
            assertFalse("native successor must remain suspended until exact predecessor GPU withdrawal ACK",successor!!.isCompleted)
            assertNull("no constructed/inert native manager before exact predecessor retirement",field("_clusterManager").get(manager))
            assertEquals(PackageManager.COMPONENT_ENABLED_STATE_DISABLED,app.packageManager.getComponentEnabledSetting(component))
            if(outcome=="cancelled") {
                manager.retireClusterVideoOutput()
                driver.release.countDown()
                pump { successor!!.isCompleted }
                assertTrue(runCatching { runBlocking { successor!!.await() } }.exceptionOrNull() is CancellationException)
                assertNull(field("_clusterManager").get(manager))
                assertEquals(PackageManager.COMPONENT_ENABLED_STATE_DISABLED,app.packageManager.getComponentEnabledSetting(component))
                return
            }
            if(outcome=="superseded") {
                staleSuccessor=successor
                successor=scope.async { manager.prepareAndroidAutoSecondaryOutputs(app) }
                pump { com.openautolink.app.diagnostics.DiagnosticLog.localLogs.value.any { it.message.contains("stage=native_retirement_wait") && it.message.contains("session=3 ") } }
            }
            rejectAck.set(outcome=="failed")
            driver.release.countDown()
            pump { successor!!.isCompleted }
            val result=runBlocking { successor!!.await() }
            if(outcome=="failed") {
                assertEquals(ClusterDisplayMode.OFF,(result as PreparedAaSecondaryOutputs).legacySelection.mode)
                assertNull(field("_clusterManager").get(manager))
                assertEquals(ClusterDisplayMode.NATIVE_ICONS,runBlocking { prefs.getSecondaryDisplayPreferences() }.legacyCluster.selection.mode)
                assertEquals(PackageManager.COMPONENT_ENABLED_STATE_DISABLED,app.packageManager.getComponentEnabledSetting(component))
                assertTrue("failed exact runtime owner remains cleanup debt",(runtime.javaClass.getDeclaredField("owners").apply { isAccessible=true }.get(runtime) as Map<*,*>).values.contains(exactOutput))
                assertNull("negative withdrawal ACK retains the exact process claim",ClusterProcessAdmission.acquire(Any()))
                return
            }
            if(cp) {
                val prepared=result as PreparedCarPlaySecondaryOutput
                assertNull(prepared.configuration)
                val media=mockk<com.openautolink.app.media.OalMediaSessionManager>(relaxed=true)
                mockkObject(com.openautolink.app.media.OalMediaSessionManager.Companion)
                every { com.openautolink.app.media.OalMediaSessionManager.getInstance(any()) } returns media
                cpHost=com.openautolink.app.carplay.OalCarPlayMetadataHost(app,clusterSelection=prepared.legacySelection)
                assertTrue(cpHost!!.start())
                val intake=com.openautolink.carplaylab.metadata.CarPlayMetadataIntake { 0L }.apply { authenticate() }
                intake.handle(com.shilapi.xcertplay.iap2.message.Iap2Messages.buildRaw(0x5202) { u16(1,7);u8(3,2);string(4,"Test road") })
                intake.handle(com.shilapi.xcertplay.iap2.message.Iap2Messages.buildRaw(0x5201) { u8(1,1);u16List(13,listOf(7));u32(10,100) })
                cpHost!!.publishRoute(null,intake.routeMetadata,null)
                pump { app.packageManager.getComponentEnabledSetting(component)==PackageManager.COMPONENT_ENABLED_STATE_ENABLED }
            } else assertTrue((result as PreparedAaSecondaryOutputs).offers.isEmpty())
            assertEquals(1,driver.detaches.get())
            assertEquals(PackageManager.COMPONENT_ENABLED_STATE_ENABLED,app.packageManager.getComponentEnabledSetting(component))
            val admitted=if(cp) ReflectionHelpers.getField<ClusterManager>(cpHost,"cluster") else field("_clusterManager").get(manager) as ClusterManager
            assertNotNull(ReflectionHelpers.getField<Any?>(admitted,"admittedLease"))
            var intent:Intent?=null
            pump { intent=intent ?: shadowOf(app).nextStartedActivity;intent!=null }
            assertNotNull("actual native binding launch",intent)
            assertEquals("androidx.car.app.activity.CarAppActivity",intent!!.component!!.className)
            val navigation=mockk<NavigationManager>(relaxed=true)
            val trips=java.util.concurrent.CopyOnWriteArrayList<androidx.car.app.navigation.model.Trip>()
            every { navigation.updateTrip(capture(trips)) } just Runs
            val car=mockk<CarContext>(relaxed=true) {
                every { resources } returns app.resources
                every { packageName } returns app.packageName
                every { getCarService(NavigationManager::class.java) } returns navigation
            }
            // Host creation follows the successful production binding launch, not a manual bypass.
            native=spyk(OalClusterService().onCreateSession() as ClusterMainSession)
            every { native!!.carContext } returns car
            native!!.onCreateScreen(intent)
            (native!!.lifecycle as LifecycleRegistry).handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
            SessionManager::class.java.getDeclaredMethod("bindSessionCollectors",AasdkSession::class.java).apply { isAccessible=true }.invoke(manager,session)
            pump { controls.subscriptionCount.value>0 }
            if(!cp) {
                assertTrue(trips.isEmpty())
                assertTrue(controls.tryEmit(ControlMessage.NavState("turn_left",100,"Test road",60)))
            }
            pump { trips.any { !it.isLoading } }
            assertEquals(1,trips.last { !it.isLoading }.steps.size)
            if(!cp) assertEquals(androidx.car.app.navigation.model.Maneuver.TYPE_TURN_NORMAL_LEFT,trips.last { !it.isLoading }.steps.single().maneuver!!.type)
            staleSuccessor?.let {
                assertTrue(runCatching { runBlocking { it.await() } }.exceptionOrNull() is CancellationException)
                assertSame(admitted,field("_clusterManager").get(manager))
            }
            verify(exactly=1) { navigation.navigationStarted() }
            verify(exactly=0) { session.stop();session.forceReconnect(any()) }
        } finally {
            driver.release.countDown()
            successor?.let { runBlocking { it.join() } }
            staleSuccessor?.let { runBlocking { it.join() } }
            rejectAck.set(false)
            exactOutput?.let { exact ->
                // Retry the same real host handle after an injected negative ACK; never clear a fence.
                val ack=exact.handle!!.retire();pump { ack.isDone };assertTrue(ack.get())
                val gpu=bridge!!.retire(exact.lease);pump { gpu.isDone };assertTrue(gpu.get())
            }
            native?.let { (it.lifecycle as LifecycleRegistry).handleLifecycleEvent(Lifecycle.Event.ON_DESTROY) }
            cpHost?.close()
            val end=manager.retireClusterVideoOutput();pump { end.isDone };assertTrue(end.get())
            val closed=runtime.close();pump { closed.isDone };assertTrue(closed.get())
            texture?.let { view?.surfaceTextureListener?.onSurfaceTextureDestroyed(it) }
            controller?.pause()?.stop()?.destroy()
            ClusterDirectVideoLifecycleTest.TextureBoundary.textures.clear()
            runBlocking {
                (field("scope").get(manager) as CoroutineScope).coroutineContext[Job]!!.cancelAndJoin()
                job.cancelAndJoin();storeJob.cancelAndJoin()
            }
            var probe:ClusterProcessAdmission.Claim?=null
            try { pump { probe=probe ?: ClusterProcessAdmission.acquire(Any());probe!=null } }
            finally { probe?.let { assertTrue(ClusterProcessAdmission.release(it)) } }
            ClusterNavigationState.clear();unmockkAll();file.delete()
            ReflectionHelpers.setStaticField(SecondaryOutputRuntime::class.java,"process",previous)
            ReflectionHelpers.setStaticField(Build::class.java,"PRODUCT",oldProduct)
            System.clearProperty("robolectric.createActivityContexts")
        }
    }
}
