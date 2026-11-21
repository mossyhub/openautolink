package com.openautolink.app.secondary

import android.app.Application
import android.app.ActivityOptions
import android.content.Context
import android.content.Intent
import android.hardware.display.DisplayManager
import android.os.Looper
import android.view.Display
import android.view.Surface
import com.openautolink.app.cluster.ClusterDirectVideoActivity
import com.openautolink.app.cluster.PixelRect
import com.openautolink.app.drivecard.DriveCardView
import com.openautolink.app.input.ProcessVehicleDataRuntime
import com.openautolink.app.transport.ControlMessage
import io.mockk.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.cancel
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.*
import org.robolectric.shadows.ShadowDisplayManager
import java.util.concurrent.CompletableFuture

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[32,34],application=Application::class,shadows=[SecondaryHostRegistryTest.PermissionShadow::class])
@ConscryptMode(ConscryptMode.Mode.OFF)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DriveCardHostLifecycleTest {
    private val context get()=RuntimeEnvironment.getApplication() as Application
    private fun exercise(retained:Boolean, enabled:Boolean, carPlay:Boolean, stopFirst:Boolean=false, wide:Boolean=false, replaceReceipt:Boolean=false, recreateActivity:Boolean=false, liveUnits:Boolean=false) {
        val previousContexts=System.getProperty("robolectric.createActivityContexts")
        System.setProperty("robolectric.createActivityContexts","true")
        val id=ShadowDisplayManager.addDisplay(if(wide)"w2000dp-h810dp-mdpi" else "w1280dp-h480dp-mdpi","Drive Card fixture")
        val display=context.getSystemService(DisplayManager::class.java).getDisplay(id)
        shadowOf(display).setFlags(Display.FLAG_PRESENTATION);shadowOf(display).setState(Display.STATE_ON)
        val facts=PublicDisplayFacts.read(context,id)!!
        val descriptor=SecondaryDisplayDescriptor(SecondaryOutputId("drivecard-$id"),id,1,"Fixture",facts.width,facts.height,facts.densityDpi,
            if(retained)SecondaryRoute.GM_HOSTED else SecondaryRoute.PUBLIC_ACTIVITY,SecondaryReason.NONE,"Unknown")
        val vehicles=MutableStateFlow(ControlMessage.VehicleData())
        mockkObject(ProcessVehicleDataRuntime)
        every {ProcessVehicleDataRuntime.latestVehicleData()} returns vehicles
        every {ProcessVehicleDataRuntime.isActive()} returns true
        SecondaryHostRegistryTest.PermissionShadow.allowed=true
        val attach=CompletableFuture<Boolean>()
        var attachments=0
        var withdrawals=0
        val consumer=object:HostConsumer {
            override fun onReady(receipt:SecondaryHostReceipt):CompletableFuture<Boolean> {attachments++;return attach}
            override fun onRevoke(owner:SecondaryHostOwner) {}
            override fun onWithdraw(receipt:SecondaryHostReceipt):CompletableFuture<Boolean> {withdrawals++;return CompletableFuture.completedFuture(true)}
        }
        val registry=SecondaryHostRegistry(context,MutableStateFlow(SecondaryInventorySnapshot(1,listOf(descriptor))),consumer)
        val handle=registry.create(context,descriptor,1,retainedWindow=retained,driveCardEnabled=enabled,driveCardCarPlay=carPlay)
        shadowOf(Looper.getMainLooper()).idle()
        val intent=requireNotNull(shadowOf(context).nextStartedActivity).putExtra("driveCardEnabled",true)
        var activity:SecondaryProjectionActivity=if(retained)ClusterDirectVideoActivity() else SecondaryProjectionActivity()
        var controller=ActivityController.of(activity,intent,ActivityOptions.makeBasic().setLaunchDisplayId(id).toBundle())
        var stopped=false
        try {
            controller.create().start().resume()
            assertFalse(activity.isFinishing)
            val token=intent.getStringExtra(SecondaryHostRegistry.TOKEN_EXTRA)
            val surface=mockk<Surface> {every {isValid} returns true}
            SecondaryHostRegistry.present(activity,token,surface,SecondaryHostGeometry(facts.width,facts.height,PixelRect(0,0,facts.width,facts.height)))
            assertNull(activity.findViewById<android.view.View>(android.R.id.content).findViewWithTag<android.view.View>(DriveCardView.TAG))
            attach.complete(true);shadowOf(Looper.getMainLooper()).idle()
            var root=activity.findViewById<android.view.ViewGroup>(android.R.id.content)
            var overlay=root.findViewWithTag<DriveCardView>(DriveCardView.TAG)
            var activeLease:com.openautolink.app.drivecard.DriveCardPreview.Lease?=null
            assertEquals(enabled,overlay!=null)
            assertEquals("Off preserves the sole existing video canvas",if(enabled)2 else 1,root.childCount)
            assertEquals(if(enabled)1 else 0,vehicles.subscriptionCount.value)
            if(enabled) {
                assertFalse(overlay!!.isClickable);assertFalse(overlay.isFocusable)
                renderRoot(root,overlay,facts.width,facts.height,carPlay)
                if(liveUnits) {
                    verifyLiveUnits(root,overlay,vehicles,facts.width,facts.height,carPlay)
                    assertEquals("Unit changes cannot renegotiate/reattach video",1,attachments)
                    assertEquals("Unit changes cannot retire the video surface",0,withdrawals)
                    assertSame(overlay,root.findViewWithTag<DriveCardView>(DriveCardView.TAG))
                }
                val saved=overlay.layout
                val lease=requireNotNull(com.openautolink.app.drivecard.DriveCardPreview.start(descriptor))
                val deadline=lease.deadlineElapsedMs
                val edited=saved.move(-.03,-.02)
                assertTrue(lease.update(edited));assertEquals(edited,overlay.layout)
                assertEquals("Edits must not renew preview",deadline,lease.deadlineElapsedMs)
                lease.stop();assertEquals(saved,overlay.layout)
                val successor=requireNotNull(com.openautolink.app.drivecard.DriveCardPreview.start(descriptor))
                assertFalse("Stopped predecessor cannot mutate successor",lease.update(edited))
                lease.stop();assertTrue(successor.isActive)
                assertTrue(successor.update(edited))
                shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(30))
                assertFalse(successor.isActive);assertEquals(saved,overlay.layout)
                assertFalse(successor.update(edited))
                assertNull(com.openautolink.app.drivecard.DriveCardPreview.start(descriptor.copy(id=SecondaryOutputId("wrong-output"))))
                val stopped=requireNotNull(com.openautolink.app.drivecard.DriveCardPreview.start(descriptor))
                assertTrue(stopped.update(edited));activeLease=stopped
                if(replaceReceipt) {
                    val previous=overlay
                    val replacement=mockk<Surface> {every {isValid} returns true}
                    SecondaryHostRegistry.present(activity,token,replacement,SecondaryHostGeometry(facts.width,facts.height,PixelRect(0,0,facts.width,facts.height)))
                    shadowOf(Looper.getMainLooper()).idle()
                    val next=requireNotNull(root.findViewWithTag<DriveCardView>(DriveCardView.TAG))
                    assertNotSame(previous,next)
                    assertFalse(stopped.isActive);assertFalse(stopped.update(edited))
                    val nextLease=requireNotNull(com.openautolink.app.drivecard.DriveCardPreview.start(descriptor))
                    assertTrue(nextLease.update(edited))
                    stopped.stop();assertTrue(nextLease.isActive);assertEquals(edited,next.layout)
                    nextLease.stop();assertEquals(saved,next.layout)
                    activeLease=nextLease;overlay=next
                }
            } else assertNull(com.openautolink.app.drivecard.DriveCardPreview.start(descriptor))
            if(recreateActivity) {
                val predecessor=activity
                val oldLease=requireNotNull(com.openautolink.app.drivecard.DriveCardPreview.start(descriptor))
                val changed=com.openautolink.app.drivecard.DriveCardLayout.DEFAULT.move(-.02,-.01)
                assertTrue(oldLease.update(changed))
                controller.pause().stop().destroy()
                assertFalse(oldLease.isActive)
                activity=ClusterDirectVideoActivity()
                controller=ActivityController.of(activity,intent,ActivityOptions.makeBasic().setLaunchDisplayId(id).toBundle())
                controller.create().start().resume()
                val nextSurface=mockk<Surface> {every {isValid} returns true}
                SecondaryHostRegistry.present(activity,token,nextSurface,SecondaryHostGeometry(facts.width,facts.height,PixelRect(0,0,facts.width,facts.height)))
                shadowOf(Looper.getMainLooper()).idle()
                root=activity.findViewById(android.R.id.content)
                val next=requireNotNull(root.findViewWithTag<DriveCardView>(DriveCardView.TAG))
                val nextLease=requireNotNull(com.openautolink.app.drivecard.DriveCardPreview.start(descriptor))
                assertTrue(nextLease.update(changed))
                org.robolectric.util.ReflectionHelpers.callInstanceMethod<Any>(predecessor,"onStop")
                oldLease.stop();assertFalse(oldLease.update(changed))
                assertTrue(nextLease.isActive);assertEquals(changed,next.layout)
                assertNull(com.openautolink.app.drivecard.DriveCardPreview.start(descriptor.copy(attachmentEpoch=descriptor.attachmentEpoch+1)))
                activeLease=nextLease;overlay=next
            }
            if(stopFirst) {controller.pause().stop();stopped=true}
            else if(retained) SecondaryHostRegistry.suspend(activity,token) else handle.retire()
            shadowOf(Looper.getMainLooper()).idle()
            assertNull(root.findViewWithTag<android.view.View>(DriveCardView.TAG))
            assertEquals(0,vehicles.subscriptionCount.value)
            assertNull(com.openautolink.app.drivecard.DriveCardPreview.start(descriptor))
            activeLease?.let {assertFalse(it.isActive);assertFalse(it.update(com.openautolink.app.drivecard.DriveCardLayout.DEFAULT.move(-.01,0.0)))}
            assertTrue(handle.retire().get())
        } finally {
            attach.complete(true);handle.retire();registry.close()
            if(stopped)controller.destroy() else controller.pause().stop().destroy()
            unmockkObject(ProcessVehicleDataRuntime)
            if(previousContexts==null)System.clearProperty("robolectric.createActivityContexts") else System.setProperty("robolectric.createActivityContexts",previousContexts)
        }
    }
    private fun verifyLiveUnits(root:android.view.ViewGroup,view:DriveCardView,
        vehicles:MutableStateFlow<ControlMessage.VehicleData>,w:Int,h:Int,carPlay:Boolean) {
        val prefs=com.openautolink.app.data.AppPreferences.getInstance(context)
        val previous=kotlinx.coroutines.runBlocking(kotlinx.coroutines.Dispatchers.IO) {prefs.distanceUnits.first()}
        val oldLocale=java.util.Locale.getDefault()
        fun preference(value:String) {
            val worker=kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob()+kotlinx.coroutines.Dispatchers.IO)
            val task=worker.launch {prefs.setDistanceUnits(value)}
            val deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(5)
            while(System.nanoTime()<deadline && (!task.isCompleted || org.robolectric.util.ReflectionHelpers.getField<String>(view,"unitPreference")!=value)) {
                shadowOf(Looper.getMainLooper()).idle();Thread.sleep(5)
            }
            assertTrue(task.isCompleted)
            assertEquals(value,org.robolectric.util.ReflectionHelpers.getField<String>(view,"unitPreference"))
            worker.cancel()
        }
        fun metadata(key:String,property:Int,at:Long)=com.openautolink.app.transport.VehiclePropertyObservation(
            at*1_000_000,android.os.SystemClock.elapsedRealtime(),0,registrationGeneration=1,propertyId=property,subscriptionActive=true,statusReadElapsedMs=android.os.SystemClock.elapsedRealtime())
        fun sample(energy:Float,at:Long,unit:Int)=ControlMessage.VehicleData(speedKmh=90f,evBatteryLevelWh=energy,
            chargePortConnected=false,evChargeState=3,evChargeRateW=0f,distanceDisplayUnits=unit,vhalRegistrationGeneration=1,
            evObservationMetadata=mapOf("PERF_VEHICLE_SPEED" to metadata("PERF_VEHICLE_SPEED",0x11600207,at),
                "EV_BATTERY_LEVEL" to metadata("EV_BATTERY_LEVEL",0x11600309,at),
                "DISTANCE_DISPLAY_UNITS" to metadata("DISTANCE_DISPLAY_UNITS",0x11400600,at)))
        fun state()=org.robolectric.util.ReflectionHelpers.getField<com.openautolink.app.drivecard.DriveCardState>(view,"state")
        fun capture(name:String) {
            val dir=System.getProperty("drivecard.render.dir") ?: return
            val image=android.graphics.Bitmap.createBitmap(w,h,android.graphics.Bitmap.Config.ARGB_8888)
            root.draw(android.graphics.Canvas(image))
            java.io.File(dir,"actual-live-units-${if(carPlay)"cp" else "aa"}-${w}x${h}-$name.png").outputStream().use{assertTrue(image.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it))}
            image.recycle()
        }
        try {
            preference("auto");java.util.Locale.setDefault(java.util.Locale.FRANCE)
            vehicles.value=sample(50_000f,android.os.SystemClock.elapsedRealtime(),0x24)
            shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(10))
            vehicles.value=sample(49_950f,android.os.SystemClock.elapsedRealtime(),0x24)
            shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(10))
            vehicles.value=sample(49_900f,android.os.SystemClock.elapsedRealtime(),0x24)
            shadowOf(Looper.getMainLooper()).idle()
            val before=state();assertEquals(5.0,requireNotNull(before.recentKmPerKwh),.001);assertTrue(before.trace.isNotEmpty())
            capture("auto-mile-non-us")
            fun unchanged() {assertEquals(before.recentKmPerKwh,state().recentKmPerKwh);assertEquals(before.trace,state().trace);assertEquals(1,vehicles.subscriptionCount.value)}
            preference("metric");unchanged();capture("explicit-metric-mile-car")
            preference("auto");java.util.Locale.setDefault(java.util.Locale.US)
            vehicles.value=vehicles.value.copy(distanceDisplayUnits=0x23)
            shadowOf(Looper.getMainLooper()).idle();unchanged();capture("auto-metric-us")
            preference("imperial");unchanged();capture("explicit-imperial-metric-car")
            preference("auto");unchanged()
        } finally {java.util.Locale.setDefault(oldLocale);preference(previous)}
    }
    private fun renderRoot(root:android.view.ViewGroup,overlay:DriveCardView,w:Int,h:Int,carPlay:Boolean) {
        val dir=System.getProperty("drivecard.render.dir") ?: return
        java.io.File(dir).mkdirs()
        root.measure(android.view.View.MeasureSpec.makeMeasureSpec(w,android.view.View.MeasureSpec.EXACTLY),android.view.View.MeasureSpec.makeMeasureSpec(h,android.view.View.MeasureSpec.EXACTLY));root.layout(0,0,w,h)
        root.setBackgroundColor(android.graphics.Color.rgb(45,65,80)) // neutral video fixture, not a map
        if(root.getChildAt(0) !is android.view.TextureView) root.getChildAt(0).setBackgroundColor(android.graphics.Color.rgb(45,65,80))
        val privateFont=System.getProperty("drivecard.fixture.font")?.let { java.io.File(it) }
        if(privateFont?.isFile == true)org.robolectric.util.ReflectionHelpers.setField(overlay,"renderer",com.openautolink.app.drivecard.DriveCardRenderer(android.graphics.Typeface.createFromFile(privateFont)))
        val states=listOf(
            "ready" to com.openautolink.app.drivecard.DriveCardState(nowKmPerKwh=5.2,recentKmPerKwh=4.8,powerKw=18.0,trace=listOf(3.0,4.0,3.8,5.2,4.9,6.1),arrival=com.openautolink.app.drivecard.DriveCardArrival(63,true),availability=com.openautolink.app.drivecard.DriveCardAvailability.READY),
            "learning" to com.openautolink.app.drivecard.DriveCardState(availability=com.openautolink.app.drivecard.DriveCardAvailability.LEARNING),
            "regen" to com.openautolink.app.drivecard.DriveCardState(powerKw=-12.0,availability=com.openautolink.app.drivecard.DriveCardAvailability.READY),
            "power-only" to com.openautolink.app.drivecard.DriveCardState(powerKw=0.0,availability=com.openautolink.app.drivecard.DriveCardAvailability.READY),
            "unavailable" to com.openautolink.app.drivecard.DriveCardState(),
            "charging" to com.openautolink.app.drivecard.DriveCardState(availability=com.openautolink.app.drivecard.DriveCardAvailability.CHARGING))
        for((name,state) in states) {
            org.robolectric.util.ReflectionHelpers.setField(overlay,"state",state)
            val image=android.graphics.Bitmap.createBitmap(w,h,android.graphics.Bitmap.Config.ARGB_8888)
            root.draw(android.graphics.Canvas(image))
            java.io.File(dir,"actual-view-${if(carPlay)"cp" else "aa"}-${w}x${h}-$name-sdk${android.os.Build.VERSION.SDK_INT}.png").outputStream().use{assertTrue(image.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it))}
            image.recycle()
        }
    }
    @Test fun wideAaLiveUnitsSwitchWithoutResettingSiHistory()=exercise(true,true,false,wide=true,liveUnits=true)
    @Test fun compactCarPlayLiveUnitsSwitchWithoutResettingSiHistory()=exercise(true,true,true,liveUnits=true)
    @Test fun aaRecreatedActivityRejectsDelayedPredecessorStop()=exercise(true,true,false,recreateActivity=true)
    @Test fun carPlayRecreatedActivityRejectsDelayedPredecessorStop()=exercise(true,true,true,recreateActivity=true)
    @Test fun aaReplacementReceiptRevokesOnlyPredecessorPreview()=exercise(true,true,false,replaceReceipt=true)
    @Test fun carPlayReplacementReceiptRevokesOnlyPredecessorPreview()=exercise(true,true,true,replaceReceipt=true)
    @Test fun wideAaRealViewRendersInOwnedActivity()=exercise(false,true,false,wide=true)
    @Test fun wideCarPlayRealViewRendersInOwnedActivity()=exercise(false,true,true,wide=true)
    @Test fun stoppedSurfaceClearsViewAndCollectors()=exercise(false,true,false,true)
    @Test fun stoppedRetainedTextureClearsViewAndCollectors()=exercise(true,true,true,true)
    @Test fun aaSurfaceOverlayUsesAdmittedTokenAndRetiresCollectors()=exercise(false,true,false)
    @Test fun carPlaySurfaceOverlayUsesAdmittedTokenAndRetiresCollectors()=exercise(false,true,true)
    @Test fun aaRetainedTextureOverlayHidesOnSuspension()=exercise(true,true,false)
    @Test fun carPlayRetainedTextureOverlayHidesOnSuspension()=exercise(true,true,true)
    @Test fun offIgnoresForgedIntentAndPreservesSurfaceTree()=exercise(false,false,false)
    @Test fun offIgnoresForgedIntentAndPreservesTextureTree()=exercise(true,false,true)
}
