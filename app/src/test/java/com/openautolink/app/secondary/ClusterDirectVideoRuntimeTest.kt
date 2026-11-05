package com.openautolink.app.secondary

import android.app.*
import android.content.*
import android.graphics.SurfaceTexture
import android.hardware.display.DisplayManager
import android.os.Looper
import android.view.*
import com.openautolink.app.cluster.*
import com.openautolink.app.data.*
import com.openautolink.app.transport.aasdk.*
import com.shilapi.xcertplay.airplay.*
import androidx.car.app.CarContext
import androidx.car.app.navigation.NavigationManager
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleRegistry
import com.openautolink.app.carplay.OalCarPlayMetadataHost
import com.openautolink.app.media.OalMediaSessionManager
import com.openautolink.app.navigation.ManeuverState
import com.openautolink.app.navigation.ManeuverType
import io.mockk.*
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.*
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.*
import org.robolectric.shadows.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[32],application=Application::class,shadows=[ClusterDirectVideoLifecycleTest.Admission::class,ClusterDirectVideoLifecycleTest.TextureBoundary::class])
@ConscryptMode(ConscryptMode.Mode.OFF)
class ClusterDirectVideoRuntimeTest {
    private val app=RuntimeEnvironment.getApplication() as Application
    private class Driver:ClusterRenderDriver {
        val inputs=AtomicInteger();val configs=AtomicInteger();val parks=AtomicInteger();val resumes=AtomicInteger()
        var initializeGate:java.util.concurrent.CountDownLatch?=null
        override fun initialize() { initializeGate?.let { check(it.await(3,TimeUnit.SECONDS)) } }
        val configured=java.util.concurrent.CopyOnWriteArrayList<ClusterCodecConfig>()
        var firstConfigGate:java.util.concurrent.CountDownLatch?=null
        val firstConfigEntered=java.util.concurrent.CountDownLatch(1)
        override fun configure(config:ClusterCodecConfig) {
            configured.add(config)
            if(configs.incrementAndGet()==1) {
                firstConfigEntered.countDown()
                firstConfigGate?.let { check(it.await(3,TimeUnit.SECONDS)) }
            }
        }
        override fun submit(frame:ClusterEncodedFrame):Boolean { inputs.incrementAndGet();return true }
        override fun poll()=ClusterRenderProgress(0,0)
        override fun stopDecoder()=Unit
        override fun clearOutput()=Unit
        override fun detach()=true
        var parkGate:java.util.concurrent.CountDownLatch?=null
        val parkEntered=java.util.concurrent.CountDownLatch(1)
        var parkSuccess=true
        override fun parkWindow():Boolean { parkEntered.countDown();parkGate?.await(4,TimeUnit.SECONDS);parks.incrementAndGet();return parkSuccess }
        var resumeGate:java.util.concurrent.CountDownLatch?=null
        val resumeEntered=java.util.concurrent.CountDownLatch(1)
        override fun resumeWindow(surface:Surface):Boolean { resumeEntered.countDown();resumeGate?.await(4,TimeUnit.SECONDS);resumes.incrementAndGet();return true }
    }
    @Test fun recreatedActivityCannotCompletePredecessorRestore()=journey(false,gm=true,offpageStartup=true,manualRestore=true,replaceActivity=true)
    @Test fun selectionReplacementAtTaskLookupBoundaryCannotFrontStaleTask()=journey(false,gm=true,offpageStartup=true,manualRestore=true,replaceSelection=true,replaceAtLookup=true)
    @Test fun carPlaySupersededFreezeCannotPublishAfterLateParkAck()=journey(true,freezePark=4)
    @Test fun sameOutputSelectionReplacementRevokesCapturedManualRestore()=journey(false,gm=true,offpageStartup=true,manualRestore=true,replaceSelection=true)
    @Test fun carPlayFreezeWaitsForParkStartedAfterInitialReady()=journey(true,freezePark=1)
    @Test fun carPlayFreezeRejectsFailedParkAfterInitialReady()=journey(true,freezePark=2)
    @Test fun carPlayFreezeUsesOriginalDeadlineForPendingPark()=journey(true,freezePark=3)
    @Test fun androidAutoProductionPreparationAndIngressSurviveDirectPageReturn()=journey(false)
    @Test fun androidAutoOffpageBeforeInitialAttachAckStillNegotiatesAndDecodesBeforeFirstReturn()=journey(false,gm=true,offpageStartup=true)
    @Test fun carPlayOffpageBeforeInitialAttachAckKeepsConfigurationAndDecoder()=journey(true,offpageStartup=true)
    @Test fun floatingVideoOnlyRestoreUsesNegotiatedStartupTaskAndKeepsPrimaryAudioAndVideo()=journey(false,gm=true,offpageStartup=true,manualRestore=true)
    @Test fun carPlayProductionConfigurationAndIngressSurviveDirectPageReturn()=journey(true)
    @Test fun directGmIdentityNeverEntersAndroidxRetainedHostHandoff()=journey(false,true)
    @Test fun explicitClusterRoleRemainsClusterOnDirectPublicWindow()=journey(false,false,AaSecondaryRole.CLUSTER)
    @Test fun nonGmUnsetRoleDefaultsToClusterInTheActualDirectPhoneOffer()=journey(false,false,null)
    @Test fun directAndroidAutoExplicitNativeChoiceAdmitsRealGuidanceAlongsideOnlyDirectVideo()=journey(false,true,AaSecondaryRole.CLUSTER,true)
    @Test fun directCarPlayExplicitNativeChoiceAdmitsRealGuidanceAlongsideOnlyDirectVideo()=journey(true,true,AaSecondaryRole.CLUSTER,true)
    @Test fun gmAndroidAutoVideoSuppressesNativeDespiteSavedTrueAndKeepsFrames()=journey(false,true,AaSecondaryRole.AUXILIARY,true,true)
    @Test fun gmCarPlayVideoSuppressesNativeDespiteSavedTrueAndKeepsFrames()=journey(true,true,AaSecondaryRole.AUXILIARY,true,true)
    @Test fun carPlayExplicitConfigAndIdrOnlyKeepOneDriverConfigWhileFirstConfigureIsHeld()=journey(true,true,AaSecondaryRole.AUXILIARY,true,true,holdCarPlayConfig=true)
    @Test fun carPlayLegacyInlineParameterSetsRequireTwoDriverConfigsWhileFirstConfigureIsHeld()=journey(true,true,AaSecondaryRole.AUXILIARY,true,true,holdCarPlayConfig=true,carPlayInlineSets=CarPlayInlineSets.LEGACY)
    @Test fun carPlayMatchingInlineParameterSetsAlsoConfigureTwiceWhileFirstConfigureIsHeld()=journey(true,true,AaSecondaryRole.AUXILIARY,true,true,holdCarPlayConfig=true,carPlayInlineSets=CarPlayInlineSets.MATCHING)
    @Test fun gmIconsOnlyAndroidAutoStillPublishesRealSourcedTrip()=nativeOnly(false)
    @Test fun gmIconsOnlyCarPlayStillPublishesRealSourcedTrip()=nativeOnly(true)
    private fun nativeOnly(cp:Boolean) {
        val old=android.os.Build.PRODUCT
        org.robolectric.util.ReflectionHelpers.setStaticField(android.os.Build::class.java,"PRODUCT",ClusterDisplayProfiles.AEGEAN_ORANGE_239.vehicle.product)
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Default)
        var cleanup:(()->Unit)?=null
        try { cleanup=exerciseNativeConsumer(ClusterOutputSelection(ClusterDisplayMode.NATIVE_ICONS,nativeIconsWithMap=true),cp,true,scope,directPresent=false) }
        finally { cleanup?.invoke();scope.cancel();org.robolectric.util.ReflectionHelpers.setStaticField(android.os.Build::class.java,"PRODUCT",old) }
    }
    /** Actual SessionManager/CarPlay host admission plus real AndroidX sourced-Trip collector.
     * Only the external NavigationManager Binder and media singleton are mocked. */
    private fun exerciseNativeConsumer(selection: ClusterOutputSelection, cp: Boolean, withIcons: Boolean,
        scope: CoroutineScope, directPresent:Boolean=true): () -> Unit {
        ClusterNavigationState.clear()
        val preferences = mockk<AppPreferences>()
        mockkObject(AppPreferences.Companion)
        every { AppPreferences.getInstance(any()) } returns preferences
        every { preferences.distanceUnits } returns kotlinx.coroutines.flow.flowOf("auto")
        val media = mockk<OalMediaSessionManager>(relaxed = true)
        mockkObject(OalMediaSessionManager.Companion)
        every { OalMediaSessionManager.getInstance(any()) } returns media
        var host: OalCarPlayMetadataHost? = null
        var native: ClusterManager? = null
        var session: ClusterMainSession? = null
        val cleanup = {
            session?.let { (it.lifecycle as LifecycleRegistry).handleLifecycleEvent(Lifecycle.Event.ON_DESTROY) }
            if (host != null) host!!.close() else native?.release()
            ClusterNavigationState.clear()
            unmockkAll()
        }
        try {
            if (cp) {
                host = OalCarPlayMetadataHost(app, clusterSelection = selection)
                assertTrue(host!!.start())
                native = org.robolectric.util.ReflectionHelpers.getField(host, "cluster")
            } else {
                val manager = com.openautolink.app.session.SessionManager(scope, app)
                manager.javaClass.getDeclaredMethod("prepareLegacyIcons", ClusterOutputSelection::class.java, Boolean::class.javaPrimitiveType)
                    .apply { isAccessible = true }.invoke(manager, selection, false)
                native = org.robolectric.util.ReflectionHelpers.getField(manager, "_clusterManager")
            }
            assertEquals("Actual native manager follows frozen explicit choice", withIcons, native != null)
            assertEquals(if (withIcons) android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_ENABLED else android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                app.packageManager.getComponentEnabledSetting(ComponentName(app, "com.openautolink.app.cluster.OalClusterService")))
            assertNull("Direct video may not also install AndroidX video", ClusterBindingState.frozenMapBinding())
            if(directPresent)assertNull("Preview/map admission remains blocked", ClusterProcessAdmission.acquire(Any()))
            if (withIcons) {
                val navigation = mockk<NavigationManager>(relaxed = true)
                val car = mockk<CarContext>(relaxed = true) {
                    every { resources } returns app.resources
                    every { packageName } returns app.packageName
                    every { getCarService(NavigationManager::class.java) } returns navigation
                }
                val lease = org.robolectric.util.ReflectionHelpers.getField<ClusterBindingRegistry.ManagerLease>(native, "admittedLease")
                session = spyk(ClusterMainSession())
                every { session!!.carContext } returns car
                session!!.onCreateScreen(Intent().putExtra(CLUSTER_BINDING_GENERATION_EXTRA, lease.generation))
                (session!!.lifecycle as LifecycleRegistry).handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
                shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(300))
                verify(exactly = 0) { navigation.navigationStarted() }
                verify(exactly = 0) { navigation.updateTrip(any()) }
                if (cp) {
                    val intake = com.openautolink.carplaylab.metadata.CarPlayMetadataIntake { 0L }.apply { authenticate() }
                    intake.handle(com.shilapi.xcertplay.iap2.message.Iap2Messages.buildRaw(0x5202) { u16(1,7); u8(3,2); string(4,"Turn road") })
                    intake.handle(com.shilapi.xcertplay.iap2.message.Iap2Messages.buildRaw(0x5201) { u8(1,1); u16List(13,listOf(7)); u32(10,350) })
                    host!!.publishRoute(null, intake.routeMetadata, null)
                } else ClusterNavigationState.updateHost(ManeuverState(ManeuverType.TURN_LEFT, 350, "350 m", "Turn road", 60))
                shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(300))
                verify(exactly = 1) { navigation.navigationStarted() }
                verify(atLeast = 1) { navigation.updateTrip(match { !it.isLoading && it.steps.size == 1 }) }
                // An existing guidance owner cannot repurpose its admission as an AndroidX map.
                if(directPresent)assertFalse(native!!.reserveAdmission())
            }
            return cleanup
        } catch (failure: Throwable) { cleanup(); throw failure }
    }

    private enum class CarPlayInlineSets { NONE, LEGACY, MATCHING }
    private fun journey(cp:Boolean,gm:Boolean=false,role:AaSecondaryRole?=AaSecondaryRole.AUXILIARY,withIcons:Boolean=false,gmExclusive:Boolean=false,offpageStartup:Boolean=false,manualRestore:Boolean=false,replaceSelection:Boolean=false,freezePark:Int=0,replaceActivity:Boolean=false,replaceAtLookup:Boolean=false,holdCarPlayConfig:Boolean=false,carPlayInlineSets:CarPlayInlineSets=CarPlayInlineSets.NONE) {
        val oldProduct=android.os.Build.PRODUCT
        if(gmExclusive)org.robolectric.util.ReflectionHelpers.setStaticField(android.os.Build::class.java,"PRODUCT",ClusterDisplayProfiles.AEGEAN_ORANGE_239.vehicle.product)
        System.setProperty("robolectric.createActivityContexts","true")
        val id=ShadowDisplayManager.addDisplay("w1600dp-h600dp-mdpi","Unprofiled target")
        val display=app.getSystemService(DisplayManager::class.java).getDisplay(id)
        shadowOf(display).setFlags(Display.FLAG_PRESENTATION);shadowOf(display).setState(Display.STATE_ON)
        val descriptor=SecondaryDisplayDescriptor(SecondaryOutputId("unprofiled-target"),id,1,"Actual",1600,600,160,if(gm) SecondaryRoute.GM_HOSTED else SecondaryRoute.PUBLIC_ACTIVITY,SecondaryReason.NONE,"Unknown")
        val driver=Driver();val job=SupervisorJob();val scope=CoroutineScope(job+Dispatchers.Default)
        if(holdCarPlayConfig) {
            require(cp)
            driver.firstConfigGate=java.util.concurrent.CountDownLatch(1)
        }
        if(offpageStartup)driver.initializeGate=java.util.concurrent.CountDownLatch(1)
        val factories=AtomicInteger()
        val runtime=SecondaryOutputRuntime(app,MutableStateFlow(SecondaryInventorySnapshot(1,listOf(descriptor))),
            { mapping,name-> factories.incrementAndGet();ClusterProjectionOutput(mapping,ClusterRenderDriverFactory { _,_->driver },name) },{ _,_->"qualified.avc" })
        val previousProcess = org.robolectric.util.ReflectionHelpers.getStaticField<SecondaryOutputRuntime?>(SecondaryOutputRuntime::class.java, "process")
        org.robolectric.util.ReflectionHelpers.setStaticField(SecondaryOutputRuntime::class.java, "process", runtime)
        // Real saved false values from the removed switches must not leave video hidden/broken.
        val selection=ClusterOutputSelection(ClusterDisplayMode.MAP_VIDEO,androidAuto=false,carPlay=false,nativeIconsWithMap=withIcons,directVideoWindow=false)
        val file=java.io.File(app.filesDir,"standard-video-${java.util.UUID.randomUUID()}.preferences_pb")
        var storeJob=SupervisorJob()
        fun stored():AppPreferences {
            val store=androidx.datastore.preferences.core.PreferenceDataStoreFactory.create(
                scope=CoroutineScope(storeJob+Dispatchers.IO),produceFile={file})
            return AppPreferences::class.java.getDeclaredConstructor(androidx.datastore.core.DataStore::class.java)
                .apply { isAccessible=true }.newInstance(store)
        }
        var durable=stored()
        runBlocking {
            durable.setClusterOutputSelection(selection)
            durable.updateSecondaryDisplayPreferences(0,mapOf(descriptor.id to SecondaryOutputPreference(true,role,false,false)))
            storeJob.cancelAndJoin()
        }
        storeJob=SupervisorJob();durable=stored()
        val prefs=runBlocking { durable.getSecondaryDisplayPreferences() }
        assertEquals(selection,prefs.legacyCluster.selection)
        assertFalse(prefs.outputs.getValue(descriptor.id).androidAuto)
        assertFalse(prefs.outputs.getValue(descriptor.id).carPlay)
        var controller:ActivityController<ClusterDirectVideoActivity>?=null
        var view:TextureView?=null;var texture:SurfaceTexture?=null
        fun available() {
            val activity=controller!!.get();val v=view!!
            activity.window.decorView.measure(View.MeasureSpec.makeMeasureSpec(1600,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(600,View.MeasureSpec.EXACTLY))
            activity.window.decorView.layout(0,0,1600,600);v.layout(0,0,1600,600)
            ClusterDirectVideoLifecycleTest.showOrdinaryWindow(v)
            texture=SurfaceTexture(0);ClusterDirectVideoLifecycleTest.TextureBoundary.textures[v]=texture!!
            v.surfaceTextureListener!!.onSurfaceTextureAvailable(texture!!,1600,600)
        }
        val freezeExecutor=java.util.concurrent.Executors.newSingleThreadExecutor()
        val freezeRelease=java.util.concurrent.CountDownLatch(1)
        fun pump(predicate:()->Boolean) {
            val deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(8)
            while(!predicate()&&System.nanoTime()<deadline) {
                shadowOf(Looper.getMainLooper()).idle()
                val intent=shadowOf(app).nextStartedActivity
                if(intent!=null) {
                    assertNull("No runtime relaunch/replacement",controller)
                    assertEquals(ClusterDirectVideoActivity::class.java.name,intent.component!!.className)
                    controller=ActivityController.of(ClusterDirectVideoActivity(),intent,ActivityOptions.makeBasic().setLaunchDisplayId(id).toBundle()).create().start().resume().visible()
                    view=controller!!.get().findViewById<ViewGroup>(android.R.id.content).getChildAt(0) as TextureView
                    if(freezePark>0) {
                        val entered=java.util.concurrent.CountDownLatch(1)
                        freezeExecutor.execute { entered.countDown();freezeRelease.await(5,TimeUnit.SECONDS) }
                        assertTrue(entered.await(2,TimeUnit.SECONDS))
                    }
                    available()
                    if(offpageStartup) {
                        // Vehicle session4: genuine onStop occurs while EGL attach is in flight,
                        // BEFORE stream selection/configuration/any decoded or presented frame.
                        controller!!.pause().stop()
                        driver.initializeGate!!.countDown()
                    }
                }
                Thread.sleep(2)
            }
            val done=predicate()
            val detail=if(done)"" else {
                val rows=org.robolectric.util.ReflectionHelpers.getField<Map<*,*>>(runtime,"owners")
                val owners=rows.values.map { it as SecondaryOutputRuntime.RuntimeOutput }.map {
                    "token=${it.token} revoked=${it.revoked} prepared=${it.preparedAtNanos} constructed=${it.construction.isDone} ready=${it.handle?.ready?.isDone} readyError=${it.handle?.ready?.isCompletedExceptionally} bridge=${it.bridge?.status?.value}"
                }
                " statuses=${runtime.statuses.value} owners=$owners activity=${controller!=null} parks=${driver.parks.get()} resumes=${driver.resumes.get()} factories=${factories.get()}"
            }
            assertTrue("Production runtime outcome timed out$detail",done)
        }
        fun subscribed(session:AasdkSession):Boolean {
            val streams=AasdkSession::class.java.getDeclaredField("secondaryVideo").apply { isAccessible=true }.get(session)
            val rows=org.robolectric.util.ReflectionHelpers.getField<Map<*,*>>(streams,"streams")
            return rows.isNotEmpty() && rows.values.all { row ->
                org.robolectric.util.ReflectionHelpers.getField<kotlinx.coroutines.flow.MutableSharedFlow<*>>(row,"queue").subscriptionCount.value>0
            }
        }
        var completed=false
        var closeGuidance: (() -> Unit)? = null
        try {
            val pending=scope.async(if(freezePark>0)freezeExecutor.asCoroutineDispatcher() else Dispatchers.Default) { if(cp) runtime.prepareCarPlay(10,prefs) else runtime.prepareAndroidAuto(10,prefs) }
            if(freezePark>0) {
                pump {
                    val rows=org.robolectric.util.ReflectionHelpers.getField<Map<*,*>>(runtime,"owners")
                    (rows.values.singleOrNull() as? SecondaryOutputRuntime.RuntimeOutput)?.preparedAtNanos != Long.MAX_VALUE && rows.isNotEmpty()
                }
                driver.parkGate=java.util.concurrent.CountDownLatch(1);driver.parkSuccess=freezePark!=2
                controller!!.pause().stop()
                assertTrue(driver.parkEntered.await(2,TimeUnit.SECONDS))
                freezeRelease.countDown()
                if(freezePark==4) {
                    pending.cancel()
                    val successor=scope.async { runtime.prepareCarPlay(11,prefs.copy(legacyCluster=prefs.legacyCluster.copy(selection=selection.copy(mode=ClusterDisplayMode.OFF)))) }
                    pump { successor.isCompleted }
                    assertNull((runBlocking { successor.await() }).configuration)
                    driver.parkGate!!.countDown();pump { pending.isCompleted }
                    assertTrue("Canceled predecessor cannot publish late optional configuration",pending.isCancelled)
                } else if(freezePark==3) {
                    pump { pending.isCompleted }
                    assertNull("Original deadline excludes pending cleanup",(runBlocking { pending.await() } as PreparedCarPlaySecondaryOutput).configuration)
                } else {
                    // The preparation continuation is free; only the real GPU park is held.
                    val until=System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(100)
                    while(System.nanoTime()<until) { shadowOf(Looper.getMainLooper()).idle();Thread.sleep(2) }
                    assertFalse("CarPlay must not freeze configuration before exact parking ACK",pending.isCompleted)
                    driver.parkGate!!.countDown();pump { pending.isCompleted }
                    val cfg=(runBlocking { pending.await() } as PreparedCarPlaySecondaryOutput).configuration
                    if(freezePark==2)assertNull("False parking cannot freeze optional configuration",cfg) else assertNotNull(cfg)
                }
                driver.parkGate!!.countDown()
                assertNull("No replacement Activity",shadowOf(app).nextStartedActivity)
                return
            }
            pump { pending.isCompleted }
            val prepared=runBlocking { pending.await() }
            val mapping=requireNotNull(runtime.mapping(descriptor.id)) { "Saved false switches suppressed the standard direct-video offer" }
            assertNull("No vehicle/profile prerequisite",mapping.gmReceipt)
            assertEquals(PixelRect(0,0,1600,600),mapping.target.output)
            assertNull("Direct video never installs an AndroidX video binding", ClusterBindingState.frozenMapBinding())
            assertFalse("Physical GM direct windows are not AndroidX hosted maps", SecondaryOutputRuntime.hasOutstandingHostedMapOutput())
            if(manualRestore) {
                mockkObject(AppPreferences.Companion)
                every { AppPreferences.getInstance(any()) } returns durable
                closeGuidance = { unmockkAll() }
            } else closeGuidance = exerciseNativeConsumer(selection, cp, withIcons && !gmExclusive, scope)
            assertEquals("Saved native coexistence intent remains untouched",withIcons,prefs.legacyCluster.selection.nativeIconsWithMap)
            var aaCallback:AasdkSessionCallback?=null
            var aaSession:AasdkSession?=null
            val primaryFrames=AtomicInteger();val audioFrames=AtomicInteger()
            var primaryJob:Job?=null;var audioJob:Job?=null
            var cpListener:com.openautolink.carplaylab.media.CarPlayClusterVideoListener?=null
            if(cp) {
                val configured=prepared as PreparedCarPlaySecondaryOutput
                val cfg=configured.configuration!!;cpListener=configured.listener!!
                val main=AirPlayDisplayConfig(1280,720)
                fun wire(cluster:AirPlayDisplayConfig?):Map<*,*> {
                    val dict=AirPlayInfoPlist.build(AirPlayConfig("fixture","00:00:00:00:00:00","00:00:00:00:00:00","fixture",main,cluster=cluster,disableAudioOutput=true),availableAudioFormats=emptySet())
                    return BplistCodec.decode(BplistCodec.encode(dict)) as Map<*,*>
                }
                val before=wire(null)["displays"] as List<*>;val after=wire(cfg.display)["displays"] as List<*>
                assertEquals(before.first(),after.first());assertEquals(111L,((after[1] as Map<*,*>)["type"] as Number).toLong())
                cpListener!!.onStreamActive(1,true)
                cpListener!!.onCodecConfig(1,byteArrayOf(1,66,0,30,-1,-31,0,2,0x67,0x42,1,0,2,0x68,0x01))
            } else {
                val configured=prepared as PreparedAaSecondaryOutputs
                val effectiveRole = role ?: AaSecondaryRole.CLUSTER // this unset-role fixture is non-GM
                val offer=configured.offers.single();assertEquals(if(effectiveRole==AaSecondaryRole.CLUSTER)1 else 2,offer.displayType)
                assertEquals(mapping.codecCrop.top,offer.cropTop);assertEquals(mapping.phoneSafeInsets.left,offer.safeLeft)
                System.getProperty("direct.receipt.dir")?.let { dir -> java.io.File(dir).mkdirs();
                    java.io.File(dir,"aa-offer-${if(gm) "gm" else "public"}-${offer.displayType}.json").writeText(org.json.JSONObject().also { obj->
                        listOf("channelId","protocolDisplayId","displayType","density","cropLeft","cropTop","cropRight","cropBottom","safeLeft","safeTop","safeRight","safeBottom","stableLeft","stableTop","stableRight","stableBottom").forEach { field->obj.put(field,offer.javaClass.getField(field).getInt(offer)) }
                    }.toString(2)) }
                val session=AasdkSession(scope,app);session.sdrConfig=AasdkSdrConfig(secondaryOffers=configured.offers.toTypedArray());configured.bind(session);configured.collect(scope,session)
                aaSession=session
                if(manualRestore) {
                    primaryJob=scope.launch(start=CoroutineStart.UNDISPATCHED) { session.videoFrames.collect { primaryFrames.incrementAndGet() } }
                    audioJob=scope.launch(start=CoroutineStart.UNDISPATCHED) { session.audioFrames.collect { audioFrames.incrementAndGet() } }
                }
                aaCallback=AasdkSession::class.java.getDeclaredMethod("telemetryCallback").apply { isAccessible=true }.invoke(session) as AasdkSessionCallback
                aaCallback!!.onSecondaryVideoState(offer.channelId,1,3,0,1,true)
                pump { runtime.statuses.value[descriptor.id]?.phoneSelected==true }
            }
            fun packet(key:Boolean):ByteArray {
                if(!key)return byteArrayOf(0,0,0,1,1,1)
                val idr=byteArrayOf(0,0,0,1,5,1)
                // AA needs in-band CSD. CarPlay already supplied avcC above: its ordinary
                // frame must not introduce another configuration while testing page retention.
                if(!cp || carPlayInlineSets==CarPlayInlineSets.LEGACY)
                    return byteArrayOf(0,0,0,1,7,1,0,0,0,1,8,1)+idr
                if(carPlayInlineSets==CarPlayInlineSets.MATCHING)
                    return byteArrayOf(0,0,0,1,0x67,0x42,0,0,0,1,0x68,0x01)+idr
                return idr
            }
            var activeEpoch=1L
            fun send(key:Boolean,pts:Long) {
                if(cp) cpListener!!.onFrame(1,packet(key)) else aaCallback!!.onSecondaryVideoFrame((prepared as PreparedAaSecondaryOutputs).offers.single().channelId,packet(key),pts,activeEpoch,0)
            }
            // Subscription readiness is separate from phone selection; await the actual queue.
            if(!cp) pump { subscribed(aaSession!!) }
            if(holdCarPlayConfig) {
                // Enter the actual driver only after the bridge's exact-source check. Holding
                // here prevents queued-config supersession from hiding the first configure.
                assertTrue("Actual first configure entered",driver.firstConfigEntered.await(2,TimeUnit.SECONDS))
                assertEquals(1,driver.configs.get());assertEquals(0,driver.inputs.get())
                assertArrayEquals(byteArrayOf(0,0,0,1,0x67,0x42),driver.configured.single().sps)
                assertArrayEquals(byteArrayOf(0,0,0,1,0x68,0x01),driver.configured.single().pps)
            }
            send(true,1)
            if(holdCarPlayConfig) {
                assertEquals("First configure still held after real frame admission",1,driver.configs.get())
                assertEquals("No frame may bypass held configure",0,driver.inputs.get())
                driver.firstConfigGate!!.countDown()
            }
            pump { driver.inputs.get()==1 }
            if(holdCarPlayConfig) {
                val expected=if(carPlayInlineSets==CarPlayInlineSets.NONE)1 else 2
                assertEquals("Real driver configurations after held ordering",expected,driver.configs.get())
                if(expected==2) {
                    val config=driver.configured[1]
                    assertArrayEquals(if(carPlayInlineSets==CarPlayInlineSets.LEGACY)byteArrayOf(0,0,0,1,7,1) else byteArrayOf(0,0,0,1,0x67,0x42),config.sps)
                    assertArrayEquals(if(carPlayInlineSets==CarPlayInlineSets.LEGACY)byteArrayOf(0,0,0,1,8,1) else byteArrayOf(0,0,0,1,0x68,0x01),config.pps)
                }
            }
            if(!offpageStartup)controller!!.pause().stop()
            pump { driver.parks.get()==1 }
            assertFalse(view!!.surfaceTextureListener!!.onSurfaceTextureDestroyed(texture!!))
            ClusterDirectVideoLifecycleTest.TextureBoundary.textures.remove(view)
            send(false,2);pump { driver.inputs.get()==2 }
            var restore:java.util.concurrent.CompletableFuture<*>?=null
            var fronts=0
            val stableConfig=aaSession?.sdrConfig
            if(manualRestore) {
                val activity=controller!!.get()
                val task=mockk<ActivityManager.AppTask>()
                every { task.taskInfo } returns ActivityManager.RecentTaskInfo().apply {
                    taskId=activity.taskId;baseActivity=ComponentName(app,ClusterDirectVideoActivity::class.java)
                    baseIntent=Intent(app,ClusterDirectVideoActivity::class.java)
                }
                every { task.moveToFront() } answers { error("No recents/Home fallback") }
                shadowOf(app.getSystemService(ActivityManager::class.java)).setAppTasks(listOf(task))
                ClusterDirectVideoLifecycleTest.Admission.front={ taskId,flags,options ->
                    assertEquals(activity.taskId,taskId)
                    assertEquals(ActivityManager.MOVE_TASK_NO_USER_ACTION,flags);assertNull(options);fronts++
                }
                if(replaceSelection) {
                    val policyEntered=java.util.concurrent.CountDownLatch(1);val policyRelease=java.util.concurrent.CountDownLatch(1)
                    val controlled=mockk<AppPreferences>()
                    every { AppPreferences.getInstance(any()) } returns controlled
                    coEvery { controlled.getSecondaryDisplayPreferences() } coAnswers {
                        policyEntered.countDown();if(!replaceAtLookup)policyRelease.await(3,TimeUnit.SECONDS);prefs
                    }
                    val lookupEntered=java.util.concurrent.CountDownLatch(1)
                    if(replaceAtLookup)every { task.taskInfo } answers { lookupEntered.countDown();policyRelease.await(3,TimeUnit.SECONDS);ActivityManager.RecentTaskInfo().apply {
                        taskId=activity.taskId;baseActivity=ComponentName(app,ClusterDirectVideoActivity::class.java);baseIntent=Intent(app,ClusterDirectVideoActivity::class.java)
                    } }
                    val stale=runtime.restoreDriverDisplayVideo()
                    if(replaceAtLookup) {
                        // Actual taskInfo read is the last slow boundary before write admission.
                        // Original code runs it on Main, so deliver selection from a real worker.
                        val replace=Thread {
                            check(lookupEntered.await(2,TimeUnit.SECONDS))
                            aaCallback!!.onSecondaryVideoState((prepared as PreparedAaSecondaryOutputs).offers.single().channelId,2,3,0,1,true)
                            val rows=org.robolectric.util.ReflectionHelpers.getField<Map<*,*>>(runtime,"owners")
                            runtime.collectSelections(listOf(rows.values.single() as SecondaryOutputRuntime.RuntimeOutput),aaSession!!)
                            policyRelease.countDown()
                        }.apply { start() }
                        pump { stale.isDone || fronts>0 };replace.join(3000)
                    } else pump { policyEntered.count==0L }
                    if(!replaceAtLookup)aaCallback!!.onSecondaryVideoState((prepared as PreparedAaSecondaryOutputs).offers.single().channelId,2,3,0,1,true)
                    val rows=org.robolectric.util.ReflectionHelpers.getField<Map<*,*>>(runtime,"owners")
                    val output=rows.values.single() as SecondaryOutputRuntime.RuntimeOutput
                    runtime.collectSelections(listOf(output),aaSession!!)
                    policyRelease.countDown();pump { stale.isDone || fronts>0 };activeEpoch=2L
                    assertEquals("Captured A may not front B on the reused RuntimeOutput",0,fronts)
                    assertEquals(DriverVideoRestoreResult.BLOCKED,stale.get())
                    every { AppPreferences.getInstance(any()) } returns durable
                }
                // Keep this reflective call byte-identical across API-missing RED and GREEN.
                restore=runtime.javaClass.getDeclaredMethod("restoreDriverDisplayVideo").invoke(runtime) as java.util.concurrent.CompletableFuture<*>
                pump { fronts==1 }
                assertFalse("Task request is not a successful real window attach",restore!!.isDone)
                if(replaceActivity) {
                    val oldRestore=restore!!;val sameIntent=controller!!.get().intent
                    driver.resumeGate=java.util.concurrent.CountDownLatch(1)
                    controller!!.restart().start().resume();available()
                    assertTrue("Actual Activity A attach is awaiting renderer ACK",driver.resumeEntered.await(2,TimeUnit.SECONDS))
                    controller!!.pause().stop().destroy()
                    controller=ActivityController.of(ClusterDirectVideoActivity(),sameIntent,ActivityOptions.makeBasic().setLaunchDisplayId(id).toBundle()).create().start().resume().visible()
                    view=controller!!.get().findViewById<ViewGroup>(android.R.id.content).getChildAt(0) as TextureView
                    available();driver.resumeGate!!.countDown();pump { driver.resumes.get()>=2 && oldRestore.isDone }
                    assertEquals("Successor onReady cannot acknowledge predecessor request",DriverVideoRestoreResult.BLOCKED,oldRestore.get())
                    return
                }
                aaCallback!!.onVideoFrame(byteArrayOf(1),100,1280,720,0)
                aaCallback!!.onAudioFrame(byteArrayOf(1,2),0,48000,2)
                pump { primaryFrames.get()==1 && audioFrames.get()==1 }
            }
            controller!!.restart().start().resume();available();pump { driver.resumes.get()==1 }
            if(manualRestore) {
                pump { restore!!.isDone }
                assertEquals("ATTACHED",restore!!.get().toString());assertEquals(1,fronts)
                assertSame("No frozen SDR replacement/reconnect",stableConfig,aaSession!!.sdrConfig)
                aaCallback!!.onVideoFrame(byteArrayOf(2),101,1280,720,0)
                aaCallback!!.onAudioFrame(byteArrayOf(3,4),0,48000,2)
                pump { primaryFrames.get()==2 && audioFrames.get()==2 }
                primaryJob!!.cancel();audioJob!!.cancel()
                ClusterDirectVideoLifecycleTest.Admission.front=null
            }
            send(replaceSelection,3);pump { driver.inputs.get()==3 }
            assertEquals(if(replaceSelection || (cp && carPlayInlineSets!=CarPlayInlineSets.NONE))2 else 1,driver.configs.get());assertTrue(runtime.statuses.value.getValue(descriptor.id).phoneSelected)
            assertNull(shadowOf(app).nextStartedActivity)
            if(gm && !withIcons && !manualRestore) {
                assertFalse(runtime.retainForExplicitReconnect())
                val owned=(runtime.javaClass.getDeclaredField("owners").apply { isAccessible=true }.get(runtime) as Map<*,*>).values.single() as SecondaryOutputRuntime.RuntimeOutput
                assertNull("Direct-window owners cannot install a null AndroidX handoff that skips HostHandle retirement",owned.handoffAttempt)
                assertNull((prepared as PreparedAaSecondaryOutputs).hostedManager)
                val oldCallback=aaCallback!!
                val ended=prepared.retire("controlled session end / fresh-start model")
                pump { ended.isDone };assertTrue(ended.get())
                view!!.surfaceTextureListener!!.onSurfaceTextureDestroyed(texture!!)
                controller!!.pause().stop().destroy();ClusterDirectVideoLifecycleTest.TextureBoundary.textures.remove(view)
                controller=null;view=null;texture=null
                val restart=scope.async { runtime.prepareAndroidAuto(11,prefs) }
                pump { restart.isCompleted }
                val next=runBlocking { restart.await() };assertEquals(1,next.offers.size);assertEquals(2,factories.get())
                val session=AasdkSession(scope,app);session.sdrConfig=AasdkSdrConfig(secondaryOffers=next.offers.toTypedArray());next.bind(session);next.collect(scope,session)
                val callback=AasdkSession::class.java.getDeclaredMethod("telemetryCallback").apply { isAccessible=true }.invoke(session) as AasdkSessionCallback
                val channel=next.offers.single().channelId
                callback.onSecondaryVideoState(channel,1,3,0,1,true)
                pump { runtime.statuses.value[descriptor.id]?.phoneSelected==true && subscribed(session) }
                callback.onSecondaryVideoFrame(channel,packet(true),4,1,0);pump { driver.inputs.get()==4 }
                oldCallback.onSecondaryVideoFrame(channel,packet(false),5,1,0)
                assertEquals(2,driver.configs.get())
            }
            completed=true
        } finally {
            freezeRelease.countDown();freezeExecutor.shutdownNow();driver.parkGate?.countDown();driver.resumeGate?.countDown()
            driver.initializeGate?.countDown()
            driver.firstConfigGate?.countDown()
            ClusterDirectVideoLifecycleTest.Admission.front=null
            closeGuidance?.invoke()
            val retired=runtime.close();pump { retired.isDone };assertTrue(retired.get())
            texture?.let { view?.surfaceTextureListener?.onSurfaceTextureDestroyed(it) }
            controller?.pause()?.stop()?.destroy()
            // Supersession retires an older reservation asynchronously. Runtime.close of the
            // empty successor alone is NOT its cleanup ACK; await real process admission.
            run {
                var probe:ClusterProcessAdmission.Claim?=null
                try { pump { probe=probe ?: ClusterProcessAdmission.acquire(Any());probe!=null } }
                finally { probe?.let { assertTrue(ClusterProcessAdmission.release(it)) } }
            }
            runBlocking { storeJob.cancelAndJoin() };file.delete()
            job.cancel();ClusterDirectVideoLifecycleTest.TextureBoundary.textures.clear();System.clearProperty("robolectric.createActivityContexts")
            org.robolectric.util.ReflectionHelpers.setStaticField(SecondaryOutputRuntime::class.java, "process", previousProcess)
            org.robolectric.util.ReflectionHelpers.setStaticField(android.os.Build::class.java,"PRODUCT",oldProduct)
        }
    }
}
