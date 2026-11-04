package com.openautolink.app.cluster

import android.app.*
import android.content.*
import android.graphics.SurfaceTexture
import android.hardware.display.DisplayManager
import android.os.Looper
import android.view.*
import com.openautolink.app.secondary.*
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicInteger
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
@Config(sdk=[32,34], application=Application::class, shadows=[ClusterDirectVideoLifecycleTest.Admission::class, ClusterDirectVideoLifecycleTest.TextureBoundary::class])
@ConscryptMode(ConscryptMode.Mode.OFF)
class ClusterDirectVideoLifecycleTest {
    companion object {
        /** Stock Robolectric attaches a nonfocusable root with mWindowVisibility=GONE.
         * Model only the OS window-visibility delivery, never input focus/display admission. */
        fun showOrdinaryWindow(view:View) {
            val info=org.robolectric.util.ReflectionHelpers.getField<Any>(view,"mAttachInfo")
            org.robolectric.util.ReflectionHelpers.setField(info,"mWindowVisibility",View.VISIBLE)
            view.dispatchWindowVisibilityChanged(View.VISIBLE)
            assertEquals(View.VISIBLE,view.windowVisibility)
        }
    }
    @Implements(ActivityManager::class)
    class Admission: ShadowActivityManager() {
        @Implementation fun isActivityStartAllowedOnDisplay(context:Context,id:Int,intent:Intent)=true
        @Implementation fun moveTaskToFront(taskId:Int,flags:Int) { front?.invoke(taskId,flags,null) }
        @Implementation fun moveTaskToFront(taskId:Int,flags:Int,options:android.os.Bundle?) { front?.invoke(taskId,flags,options) }
        companion object { var front:((Int,Int,android.os.Bundle?)->Unit)?=null }
    }
    @Implements(TextureView::class)
    class TextureBoundary : ShadowView() {
        @RealObject lateinit var view:TextureView
        @Implementation fun isAvailable()=textures.containsKey(view)
        @Implementation fun getSurfaceTexture():SurfaceTexture?=textures[view]
        companion object { val textures=java.util.IdentityHashMap<TextureView,SurfaceTexture>() }
    }
    private val app=RuntimeEnvironment.getApplication() as Application
    private var emergencyCleanup: (() -> Unit)? = null
    private var diagnosticWriterCleanup: (() -> Unit)? = null
    private var diagnostic: () -> String = { "" }
    @org.junit.After fun cleanupFailedSetup() { diagnosticWriterCleanup?.invoke();diagnosticWriterCleanup=null;try { emergencyCleanup?.invoke() } finally { Admission.front=null } }
    private class Driver:ClusterRenderDriver {
        val configured=AtomicInteger();val submitted=AtomicInteger();val parked=AtomicInteger();val resumed=AtomicInteger()
        var parkGate:CountDownLatch?=null;val parkEntered=CountDownLatch(1)
        var initializeGate:CountDownLatch?=null;val initializeEntered=CountDownLatch(1)
        override fun initialize() { initializeEntered.countDown();initializeGate?.let { check(it.await(3,TimeUnit.SECONDS)) } }
        override fun configure(config:ClusterCodecConfig) { configured.incrementAndGet() }
        override fun submit(frame:ClusterEncodedFrame):Boolean { submitted.incrementAndGet();return true }
        override fun poll()=ClusterRenderProgress(0,0)
        override fun stopDecoder()=Unit
        override fun clearOutput()=Unit
        override fun detach()=true
        var parkSuccess=true
        override fun parkWindow():Boolean { parkEntered.countDown();parkGate?.await(3,TimeUnit.SECONDS);parked.incrementAndGet();return parkSuccess }
        override fun resumeWindow(surface:Surface):Boolean { resumed.incrementAndGet();return true }
    }
    private fun await(predicate:()->Boolean) {
        val deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5)
        while(!predicate()&&System.nanoTime()<deadline) { shadowOf(Looper.getMainLooper()).idle();Thread.sleep(2) }
        assertTrue("Actual Activity/renderer outcome timed out ${diagnostic()}",predicate())
    }
    @Test fun startupPageAwayReturnNewSurfaceKeepsOneDecoderAndAcceptsPredictiveFrames() = journey(false)
    @Test fun falseGpuParkingRetainsSurfaceAndProcessClaimUntilTerminalCleanup() = journey(true)
    @Test fun partialActualDrawableUsesItsLocalBufferInsteadOfFabricatingWholeDecor() = journey(false,20)
    @Test fun recreatedActivityWaitsForPredecessorGpuAttachAndCannotBeRetiredByOldAck() = journey(false,0,true)
    @Test fun sameActivityTextureRecreationDoesNotInventPageAway() = journey(false,0,false,true)
    @Test fun ownCallbackDiagnosticsDistinguishActivityTextureAndGpuParking() = journey(false, diagnostics=true)
    @Test fun blockedDiagnosticWriterCannotDelayStopTextureCleanupOrSuspendAcknowledgement() = journey(false, blockedWriter=true)
    @Test fun matchingDecodedAwayReturnFrontsExactOwnTaskOnceThenRealCallbacksResumeFrames() = journey(false, restore=true)
    @Test fun decodedReturnCannotFrontWithFalseGpuCleanup() = journey(true, restore=true)
    @Test fun decodedReturnsRejectMismatchedStaleDisabledAndRevokedOwners() = journey(false,restoreSafety=true)
    @Test fun decodedReturnDisarmDuringPolicyReadCannotFront() = journey(false,restoreSafety=true,disarmDuringRead=true)
    @Test fun newerDriverPageDuringPolicyReadCancelsQueuedAutomaticReturn() = journey(false,restoreSafety=true,newerPageDuringRead=true)
    @Test fun protectedPageRevisionAdvancesBeforeOwnerMonitorRelease()=journey(false,manualSafety=true,lookupPage=4)
    @Test fun protectedPageRevokesWhileActualTaskInfoLookupIsBlocked()=journey(false,manualSafety=true,lookupPage=1)
    @Test fun identicalPageDoesNotRevokeBlockedLookupAndWritesOnce()=journey(false,manualSafety=true,lookupPage=2)
    @Test fun observerQueueCannotDelayAuthoritativePageRevocation()=journey(false,manualSafety=true,lookupPage=3)
    @Test fun manualRestoreHonorsCurrentPolicyStreamTaskCancellationAndFiniteTimeout() = journey(false,manualSafety=true)
    @Test fun manualRestoreCannotCrossFailedGpuCleanup() = journey(true,manualSafety=true)
    @Test fun manualRestorePermissionFailureDoesNotUseHomeOrRelaunch() = journey(false,manualSafety=true,frontDenied=true)
    @Test fun deniedDirectTaskFrontConsumesReturnWithoutRecentsOrActivityFallback() = journey(false,restore=true,frontDenied=true)
    @Test fun directTaskReorderPermissionIsDeclaredByActualApplication() {
        val info=app.packageManager.getPackageInfo(app.packageName,android.content.pm.PackageManager.GET_PERMISSIONS)
        assertEquals(1,info.requestedPermissions.orEmpty().count { it=="android.permission.REORDER_TASKS" })
    }
    private fun journey(failedPark:Boolean,inset:Int=0,recreate:Boolean=false,surfaceOnly:Boolean=false,diagnostics:Boolean=false,blockedWriter:Boolean=false,restore:Boolean=false,restoreSafety:Boolean=false,disarmDuringRead:Boolean=false,frontDenied:Boolean=false,newerPageDuringRead:Boolean=false,manualSafety:Boolean=false,lookupPage:Int=0) {
        var diagnosticDrops:Long?=null
        if (diagnostics) {
            // This sink explicitly allows tryLock/overload loss. Hold its actual persistence
            // worker OUTSIDE the queue lock while producing this bounded callback journey,
            // then release it and assert exact current-Activity records. Do not demand that
            // a racing process-history ring behaves like an acknowledged lossless queue.
            val sink=org.robolectric.util.ReflectionHelpers.getStaticField<BoundedPageDiagnosticSink>(PageReturnDiagnostics::class.java,"sink")
            val persist=org.robolectric.util.ReflectionHelpers.getField<(String)->Unit>(sink,"persist")
            val entered=CountDownLatch(1);val release=CountDownLatch(1)
            // Hold this diagnostic worker only, not the shared FileLogWriter used by renderer
            // initialization. Every real callback still executes the original production sink.
            val controlled:(String)->Unit={ message ->
                if(message.startsWith("TEST_CALLBACK_DIAGNOSTIC_BARRIER")) { entered.countDown();release.await() }
                else persist(message)
            }
            org.robolectric.util.ReflectionHelpers.setField(sink,"persist",controlled)
            diagnosticWriterCleanup={
                org.robolectric.util.ReflectionHelpers.setField(sink,"persist",persist)
                release.countDown()
            }
            var admitted=false
            await { admitted || PageReturnDiagnostics.tryOffer("TEST_CALLBACK_DIAGNOSTIC_BARRIER").also { admitted=it } }
            assertTrue("Actual persistence worker is held outside the diagnostic queue",entered.await(2,TimeUnit.SECONDS))
            com.openautolink.app.diagnostics.DiagnosticLog.clearLocal()
            diagnosticDrops=PageReturnDiagnostics.dropped
        }
        System.setProperty("robolectric.createActivityContexts","true")
        val id=ShadowDisplayManager.addDisplay("w1600dp-h600dp-mdpi","Unprofiled public target")
        val physical=app.getSystemService(DisplayManager::class.java).getDisplay(id)
        shadowOf(physical).setFlags(Display.FLAG_PRESENTATION);shadowOf(physical).setState(Display.STATE_ON)
        val descriptor=SecondaryDisplayDescriptor(SecondaryOutputId("unprofiled"),id,1,"Actual",1600,600,160,SecondaryRoute.PUBLIC_ACTIVITY,SecondaryReason.NONE,"Unknown")
        val driver=Driver();val lease=ClusterOutputLease(1,1)
        if(recreate) driver.initializeGate=CountDownLatch(1)
        val events=java.util.concurrent.CopyOnWriteArrayList<String>()
        val stops=java.util.IdentityHashMap<Activity,Int>()
        val callbacks=object:Application.ActivityLifecycleCallbacks {
            override fun onActivityCreated(a:Activity,b:android.os.Bundle?)=Unit
            override fun onActivityStarted(a:Activity) { events.add("framework_start:${System.identityHashCode(a)}") }
            override fun onActivityResumed(a:Activity)=Unit
            override fun onActivityPaused(a:Activity)=Unit
            override fun onActivityStopped(a:Activity) { stops[a]=(stops[a] ?: 0)+1;events.add("framework_stop:${System.identityHashCode(a)}:${stops[a]}") }
            override fun onActivitySaveInstanceState(a:Activity,b:android.os.Bundle)=Unit
            override fun onActivityDestroyed(a:Activity)=Unit
        }
        app.registerActivityLifecycleCallbacks(callbacks)
        var bridge:ClusterProjectionOutput?=null
        val consumer=object:HostConsumer {
            override fun onReady(receipt:SecondaryHostReceipt):CompletableFuture<Boolean> {
                events.add("ready_submit:${System.identityHashCode(receipt.surface)}")
                val output=bridge ?: run {
                    val size=PixelSize(receipt.geometry.width,receipt.geometry.height)
                    val mapping=RetainedWindowMapping(SecondaryVideoMapping.androidAuto(SecondaryRenderTarget(descriptor.id,1,id,size,size.bounds,receipt.geometry.safe)))
                    ClusterProjectionOutput(mapping,ClusterRenderDriverFactory { _,_->driver }).also { assertTrue(it.bindLease(lease));bridge=it }
                }
                return output.attach(lease,receipt.surface,output.mapping.target).whenComplete { ok,error -> events.add("ready_ack:${System.identityHashCode(receipt.surface)}:$ok:${error != null}") }
            }
            override fun onSuspend(receipt:SecondaryHostReceipt):CompletableFuture<Boolean> {
                events.add("park_submit:${System.identityHashCode(receipt.surface)}")
                return bridge!!.park(lease,receipt.surface).whenComplete { ok,error -> events.add("park_ack:${System.identityHashCode(receipt.surface)}:$ok:${error != null}") }
            }
            override fun onWithdraw(receipt:SecondaryHostReceipt)=bridge!!.retire(lease)
            override fun onRevoke(owner:SecondaryHostOwner)=Unit
        }
        val registry=SecondaryHostRegistry(app,MutableStateFlow(SecondaryInventorySnapshot(1,listOf(descriptor))),consumer)
        val policyEnabled=java.util.concurrent.atomic.AtomicBoolean(restore||restoreSafety||manualSafety)
        val currentRestoreOwner=java.util.concurrent.atomic.AtomicBoolean(restore||restoreSafety||manualSafety)
        val policyReads=AtomicInteger()
        var policyGate:CountDownLatch?=null
        val host=registry.create(app,descriptor,1,retainedWindow=true,restoreAllowed={
            val gate=policyGate;policyReads.incrementAndGet();gate?.await(3,TimeUnit.SECONDS);policyEnabled.get()
        },restoreCurrent={currentRestoreOwner.get()})
        emergencyCleanup = { policyGate?.countDown();driver.parkGate?.countDown();driver.initializeGate?.countDown(); val end=host.retire();await { end.isDone };registry.close();app.unregisterActivityLifecycleCallbacks(callbacks);TextureBoundary.textures.clear();System.clearProperty("robolectric.createActivityContexts") }
        shadowOf(Looper.getMainLooper()).idle()
        val intent=requireNotNull(shadowOf(app).nextStartedActivity)
        var controller=ActivityController.of(ClusterDirectVideoActivity(),intent,ActivityOptions.makeBasic().setLaunchDisplayId(id).toBundle()).create().start().resume().visible()
        var activity=controller.get()
        val task=io.mockk.mockk<ActivityManager.AppTask>()
        var fronts=0
        var legacyRecentsFronts=0
        var frontAttempts=0
        val frontWrites=mutableListOf<Triple<Int,Int,android.os.Bundle?>>()
        Admission.front={ exactTaskId,flags,options ->
            frontAttempts++
            frontWrites.add(Triple(exactTaskId,flags,options))
            if(frontDenied)throw SecurityException("test denies REORDER_TASKS")
            fronts++
        }
        val taskInfo=ActivityManager.RecentTaskInfo().apply {
            taskId=activity.taskId
            baseActivity=ComponentName(app,ClusterDirectVideoActivity::class.java)
            baseIntent=Intent(app,ClusterDirectVideoActivity::class.java)
        }
        io.mockk.every { task.taskInfo } returns taskInfo
        io.mockk.every { task.moveToFront() } answers { legacyRecentsFronts++; fronts++; Unit }
        shadowOf(app.getSystemService(ActivityManager::class.java)).setAppTasks(listOf(task))
        fun page(selected:Boolean,type:String="NATIVE",epoch:Long=0,elapsed:Long=android.os.SystemClock.elapsedRealtime(),vendorView:String="own-domain-view") {
            app.sendBroadcast(Intent(app.packageName+".action.DECODED_GM_PAGE").setPackage(app.packageName)
                .putExtra("selected",if(selected)1 else 0).putExtra("type",type).putExtra("generation",epoch).putExtra("elapsed",elapsed)
                .putExtra("view",vendorView).putExtra("displayType",28).putExtra("width",1600).putExtra("height",600))
            // Drain the production receiver's actual registered Handler before observing Main.
            val worker=runCatching { org.robolectric.util.ReflectionHelpers.getField<android.os.Handler>(registry,"intake") }.getOrNull()
            if(worker!=null)shadowOf(worker.looper).idle()
            shadowOf(Looper.getMainLooper()).idle()
        }
        var view=(activity.findViewById<ViewGroup>(android.R.id.content)).getChildAt(0) as TextureView
        diagnostic = {
            val tokens=SecondaryHostRegistry::class.java.getDeclaredField("tokens").apply { isAccessible=true }.get(null) as Map<*,*>
            val entry=tokens[intent.getStringExtra(SecondaryHostRegistry.TOKEN_EXTRA)]
            val state=entry?.let { e -> listOf("attaching","withdrawing","suspended","terminal","suspension","receipt","pending").associateWith { key -> org.robolectric.util.ReflectionHelpers.getField<Any?>(e,key) } }
            "gmAway=${org.robolectric.util.ReflectionHelpers.getField<Any?>(registry,"gmAway")} task=${activity.taskId} gate=${com.openautolink.app.diagnostics.gm.GmObserverGate.qualified(app)} store=${com.openautolink.app.diagnostics.gm.GmObserverStore(app).read()} parks=${driver.parked.get()} resumes=${driver.resumed.get()} bridge=${bridge?.status?.value} owner=${host.owner.isCurrent} registry=$state events=$events"
        }
        fun available():SurfaceTexture {
            activity.window.decorView.measure(View.MeasureSpec.makeMeasureSpec(1600,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(600,View.MeasureSpec.EXACTLY))
            activity.window.decorView.layout(0,0,1600,600);view.layout(0,inset,1600,600)
            showOrdinaryWindow(view)
            val texture=SurfaceTexture(0);TextureBoundary.textures[view]=texture
            view.surfaceTextureListener!!.onSurfaceTextureAvailable(texture,1600,600)
            val local=IntArray(2).also { view.getLocationInWindow(it) }
            println("ACTUAL_FIXTURE attached=${view.isAttachedToWindow} shown=${view.isShown} visibility=${view.windowVisibility} available=${view.isAvailable} textureMatch=${view.surfaceTexture === texture} view=${view.width}x${view.height} local=${local.toList()} decor=${activity.window.decorView.width}x${activity.window.decorView.height} finishing=${activity.isFinishing} physical=${activity.display?.displayId}")
            return texture
        }
        var texture=available()
        try {
            if(inset>0) assertNotNull("Actual attached valid visible Surface callback must reach consumer for local buffer $inset; ${diagnostic()}",bridge)
            if(recreate) {
                assertTrue(driver.initializeEntered.await(3,TimeUnit.SECONDS))
                val oldView=view;val oldTexture=texture
                controller.pause().stop();assertFalse(oldView.surfaceTextureListener!!.onSurfaceTextureDestroyed(oldTexture));controller.destroy()
                controller=ActivityController.of(ClusterDirectVideoActivity(),intent,ActivityOptions.makeBasic().setLaunchDisplayId(id).toBundle()).create().start().resume().visible()
                activity=controller.get();view=activity.findViewById<ViewGroup>(android.R.id.content).getChildAt(0) as TextureView
                texture=available();driver.initializeGate!!.countDown()
            }
            await { host.ready.isDone };assertFalse("Actual admitted successor must survive predecessor ack: ${diagnostic()}",host.ready.isCompletedExceptionally)
            assertEquals(PixelSize(1600,600-inset),bridge!!.mapping.target.panel)
            assertEquals(PixelRect(0,0,1600,600-inset),bridge!!.mapping.target.output)
            val stream=bridge!!.beginStream(lease,Any()){true}!!
            fun frame(pts:Long,key:Boolean) { assertTrue(bridge!!.frame(lease,stream,ClusterEncodedFrame(stream.epoch,byteArrayOf(if(key)5 else 1),pts,key))) }
            assertTrue(bridge!!.configure(lease,stream,ClusterCodecConfig(bridge!!.mapping.codedSize,byteArrayOf(7),byteArrayOf(8))))
            frame(1,true);await { driver.submitted.get()==1 }
            if(manualSafety && !failedPark) {
                controller.pause().stop();await { driver.parked.get()==1 }
                // Driver entry/counter is not registry retirement acknowledgement. Await the
                // actual exact clean parked lease before testing policy/task admission.
                val clean=host.readyForOffer();await { clean.isDone };assertTrue(clean.get())
                if(lookupPage>0) {
                    page(true)
                    val lookupEntered=CountDownLatch(1);val lookupRelease=CountDownLatch(1)
                    val revokedBeforeRelease=java.util.concurrent.atomic.AtomicBoolean(false)
                    val originalRevision=org.robolectric.util.ReflectionHelpers.getField<Long>(registry,"pageRevision")
                    var service:org.robolectric.android.controller.ServiceController<com.openautolink.app.diagnostics.gm.GmPageObserverService>?=null
                    val queueEntered=CountDownLatch(1);val queueRelease=CountDownLatch(1)
                    if(lookupPage==3) {
                        service=Robolectric.buildService(com.openautolink.app.diagnostics.gm.GmPageObserverService::class.java).create()
                        val sink=org.robolectric.util.ReflectionHelpers.getField<BoundedPageDiagnosticSink>(service.get(),"sink")
                        val persist=org.robolectric.util.ReflectionHelpers.getField<(String)->Unit>(sink,"persist")
                        org.robolectric.util.ReflectionHelpers.setField(sink,"persist",{ value:String -> queueEntered.countDown();queueRelease.await(3,TimeUnit.SECONDS);persist(value) })
                        service.get().onStartCommand(Intent("unknown"),0,1)
                        assertTrue(queueEntered.await(2,TimeUnit.SECONDS))
                    }
                    io.mockk.every { task.taskInfo } answers { lookupEntered.countDown();lookupRelease.await(3,TimeUnit.SECONDS);taskInfo }
                    val monitorRelease=CountDownLatch(1)
                    var monitorHolder:Thread?=null;var receiverDrain:Thread?=null
                    val observer=Thread {
                        try {
                            check(lookupEntered.await(2,TimeUnit.SECONDS))
                            if(lookupPage==4) {
                                val tokens=org.robolectric.util.ReflectionHelpers.getStaticField<Map<*,*>>(SecondaryHostRegistry::class.java,"tokens")
                                val entry=tokens[intent.getStringExtra(SecondaryHostRegistry.TOKEN_EXTRA)]!!
                                val authority=org.robolectric.util.ReflectionHelpers.getField<Any>(entry,"restoreAuthority")
                                val monitor=org.robolectric.util.ReflectionHelpers.getField<Any>(authority,"monitor")
                                val held=CountDownLatch(1)
                                monitorHolder=Thread { synchronized(monitor) { held.countDown();monitorRelease.await(3,TimeUnit.SECONDS) } }.apply { start() }
                                check(held.await(2,TimeUnit.SECONDS))
                            }
                            if(lookupPage==3)service!!.get().onStartCommand(Intent(com.openautolink.app.diagnostics.gm.GmPageObserverService.FULL_MAP_SELECTED),0,2)
                            else app.sendBroadcast(Intent(app.packageName+".action.DECODED_GM_PAGE").setPackage(app.packageName)
                                .putExtra("selected",1).putExtra("type",if(lookupPage==2)"NATIVE" else "UNKNOWN")
                                .putExtra("generation",0L).putExtra("elapsed",android.os.SystemClock.elapsedRealtime())
                                .putExtra("view","own-domain-view").putExtra("displayType",28).putExtra("width",1600).putExtra("height",600))
                            val worker=runCatching { org.robolectric.util.ReflectionHelpers.getField<android.os.Handler>(registry,"intake") }.getOrNull()
                            if(worker!=null) {
                                if(lookupPage==4)receiverDrain=Thread { shadowOf(worker.looper).idle() }.apply { start() }
                                else shadowOf(worker.looper).idle()
                            }
                            val until=System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(300)
                            while(System.nanoTime()<until && org.robolectric.util.ReflectionHelpers.getField<Long>(registry,"pageRevision")==originalRevision)Thread.sleep(2)
                            revokedBeforeRelease.set(org.robolectric.util.ReflectionHelpers.getField<Long>(registry,"pageRevision")!=originalRevision)
                        } finally {
                            monitorRelease.countDown();monitorHolder?.join(2000);receiverDrain?.join(2000)
                            lookupRelease.countDown()
                        }
                    }.apply { start() }
                    try {
                        val pending=host.restoreVideo();await { pending.isDone || fronts>0 }
                        observer.join(3000)
                        if(lookupPage==2) { assertFalse(revokedBeforeRelease.get());assertEquals(1,fronts) }
                        else { assertTrue("Protected production intake must revoke BEFORE task lookup resumes",revokedBeforeRelease.get());assertEquals(0,fronts);assertEquals(DriverVideoRestoreResult.BLOCKED,pending.get()) }
                        pending.cancel(false)
                        if(lookupPage==3)assertEquals("Diagnostic worker remains held",1L,queueRelease.count)
                    } finally { monitorRelease.countDown();lookupRelease.countDown();queueRelease.countDown();observer.join(3000);service?.destroy() }
                    return
                }
                fun blocked() {
                    val result=host.restoreVideo();await { result.isDone }
                    assertEquals(DriverVideoRestoreResult.BLOCKED,result.get());assertEquals(0,fronts)
                }
                policyEnabled.set(false);blocked();policyEnabled.set(true)
                currentRestoreOwner.set(false);blocked();currentRestoreOwner.set(true)
                taskInfo.baseActivity=ComponentName(app,Activity::class.java);blocked()
                taskInfo.baseActivity=ComponentName(app,ClusterDirectVideoActivity::class.java)
                policyGate=CountDownLatch(1)
                var reads=policyReads.get();val cancelled=host.restoreVideo();await { policyReads.get()==reads+1 }
                cancelled.cancel(false);policyGate!!.countDown()
                shadowOf(Looper.getMainLooper()).idle();assertEquals(0,fronts)
                policyGate=CountDownLatch(1);reads=policyReads.get()
                val stale=host.restoreVideo();await { policyReads.get()==reads+1 }
                page(true,"UNKNOWN");assertEquals(DriverVideoRestoreResult.BLOCKED,stale.get())
                policyGate!!.countDown();policyGate=null
                val pending=host.restoreVideo()
                await { frontAttempts==1 }
                if(frontDenied) {
                    await { pending.isDone };assertEquals(DriverVideoRestoreResult.BLOCKED,pending.get())
                    assertEquals(0,fronts)
                } else {
                    assertFalse(pending.isDone)
                    shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(2001))
                    assertEquals(DriverVideoRestoreResult.TIMED_OUT,pending.get());assertEquals(1,fronts)
                }
                assertEquals(0,legacyRecentsFronts);assertNull(shadowOf(app).nextStartedActivity)
                return
            }
            if(restoreSafety) {
                val tokens=SecondaryHostRegistry::class.java.getDeclaredField("tokens").apply{isAccessible=true}.get(null) as Map<*,*>
                val entry=tokens[intent.getStringExtra(SecondaryHostRegistry.TOKEN_EXTRA)]!!
                val ring=org.robolectric.util.ReflectionHelpers.getField<java.util.ArrayDeque<com.openautolink.app.diagnostics.LocalLogEntry>>(com.openautolink.app.diagnostics.DiagnosticLog,"ring")
                fun logged(stage:String)=synchronized(ring){ring.count{it.message.contains("stage=$stage ")}}
                fun away() {
                    page(false)
                    val count=driver.parked.get()
                    controller.pause().stop();await{driver.parked.get()==count+1}
                    assertFalse(view.surfaceTextureListener!!.onSurfaceTextureDestroyed(texture))
                    TextureBoundary.textures.remove(view)
                    await{org.robolectric.util.ReflectionHelpers.getField<Any?>(entry,"suspension")==null &&
                        org.robolectric.util.ReflectionHelpers.getField<Any?>(entry,"receipt")==null}
                }
                fun resumeRealWindow() {
                    val count=driver.resumed.get()
                    controller.restart().start().resume();texture=available()
                    await{driver.resumed.get()==count+1}
                }
                page(true);assertEquals("Initial selected snapshot is not a return",0,fronts)
                away();page(true,"PROJECTION");page(true);assertEquals(0,fronts);resumeRealWindow()
                away();page(true,vendorView="different-logical-view");page(true);assertEquals(0,fronts);resumeRealWindow()
                away();page(true,"UNKNOWN");page(true);assertEquals(0,fronts);resumeRealWindow()
                away();page(true,elapsed=-1);page(true);assertEquals(0,fronts);resumeRealWindow()
                away();page(true,epoch=99);page(true);assertEquals(0,fronts);resumeRealWindow()
                away();currentRestoreOwner.set(false);page(true);currentRestoreOwner.set(true);page(true)
                assertEquals("Revoked active-stream predicate cannot be revived by replay",0,fronts);resumeRealWindow()
                away();policyEnabled.set(false)
                var suppressed=logged("gm_restore_suppressed")
                page(true);await{logged("gm_restore_suppressed")==suppressed+1}
                assertEquals("Latest saved-policy veto is honored",0,fronts);policyEnabled.set(true);page(true);assertEquals(0,fronts);resumeRealWindow()
                away();taskInfo.baseActivity=ComponentName(app,Activity::class.java)
                val attempts=logged("gm_restore_attempt");page(true);await{logged("gm_restore_attempt")==attempts+1}
                assertEquals("Wrong existing task component is never fronted",0,fronts)
                taskInfo.baseActivity=ComponentName(app,ClusterDirectVideoActivity::class.java);page(true);assertEquals(0,fronts);resumeRealWindow()
                // The old exact owner is revoked while its real policy IO is held. Completion
                // must not front it (or any successor), even though all captured facts matched.
                away();policyGate=CountDownLatch(1)
                val reads=policyReads.get();suppressed=logged("gm_restore_suppressed")
                val beforeAttempts=logged("gm_restore_attempt")
                page(true);await{policyReads.get()==reads+1}
                val end=if(newerPageDuringRead) {
                    // A newer real page notification invalidates work already queued by the
                    // prior selected edge, even if policy IO and the exact task remain valid.
                    page(false)
                    CompletableFuture.completedFuture(true)
                } else if(disarmDuringRead) {
                    assertTrue(com.openautolink.app.diagnostics.gm.GmObserverStore(app).setEnabled(false))
                    CompletableFuture.completedFuture(true)
                } else host.retire().also{assertFalse(host.owner.isCurrent)}
                policyGate!!.countDown();await{end.isDone};assertTrue(end.get())
                await{logged("gm_restore_suppressed")==suppressed+1 || logged("gm_restore_attempt")==beforeAttempts+1}
                assertEquals("Disarm/revocation during policy read must veto the real task write",0,fronts)
                assertEquals(suppressed+1,logged("gm_restore_suppressed"))
                page(false);page(true);assertEquals("Stale queued owner never fronts",0,fronts)
                assertEquals("Safety cases retain one configured decoder",1,driver.configured.get())
                return
            }
            if(surfaceOnly) {
                assertEquals(0,stops[activity] ?: 0)
                assertFalse(view.surfaceTextureListener!!.onSurfaceTextureDestroyed(texture))
                val tokens=SecondaryHostRegistry::class.java.getDeclaredField("tokens").apply { isAccessible=true }.get(null) as Map<*,*>
                val entry=tokens[intent.getStringExtra(SecondaryHostRegistry.TOKEN_EXTRA)]!!
                assertFalse("Surface destruction is not Activity-hidden authority: ${diagnostic()}",org.robolectric.util.ReflectionHelpers.getField<Boolean>(entry,"suspended"))
                TextureBoundary.textures.remove(view);texture=available()
                await { driver.resumed.get()==1 };frame(2,false);await { driver.submitted.get()==2 }
                return
            }
            if(failedPark) {
                val oldSurface=host.ready.get().surface
                if(restore) { page(false);assertEquals(0,fronts) }
                driver.parkSuccess=false;controller.pause().stop();await { driver.parked.get()==1 }
                if(manualSafety) {
                    val blocked=host.restoreVideo();await { blocked.isDone }
                    assertEquals(DriverVideoRestoreResult.BLOCKED,blocked.get());assertEquals(0,fronts)
                }
                if(restore) { page(true);page(true);assertEquals("Failed GPU cleanup refuses restore",0,fronts) }
                assertFalse(view.surfaceTextureListener!!.onSurfaceTextureDestroyed(texture))
                assertTrue("False GPU acknowledgement must not release owned Surface",oldSurface.isValid)
                TextureBoundary.textures.remove(view);controller.restart().start().resume();texture=available()
                shadowOf(Looper.getMainLooper()).idle();assertEquals(0,driver.resumed.get())
                assertNull(ClusterProcessAdmission.acquire(Any()))
                val end=host.retire();await { end.isDone };assertTrue(end.get())
                val next=ClusterProcessAdmission.acquire(Any());assertNotNull(next);assertTrue(ClusterProcessAdmission.release(next!!))
                return
            }
            if (blockedWriter) {
                val log=com.openautolink.app.diagnostics.DiagnosticLog
                val previous=log.fileLogWriter
                val entered=CountDownLatch(1);val releaseIo=CountDownLatch(1)
                val writer=com.openautolink.app.diagnostics.FileLogWriter(app)
                val blocked=object:java.io.BufferedWriter(java.io.StringWriter()) {
                    override fun flush() { entered.countDown();check(releaseIo.await(10,TimeUnit.SECONDS)) }
                }
                org.robolectric.util.ReflectionHelpers.setField(writer,"writer",blocked)
                org.robolectric.util.ReflectionHelpers.setField(writer,"isActive",true)
                org.robolectric.util.ReflectionHelpers.setField(writer,"linesSinceFlush",63)
                log.fileLogWriter=writer
                val watchdog=Thread { if (!releaseIo.await(3,TimeUnit.SECONDS)) releaseIo.countDown() }.apply { isDaemon=true;start() }
                val persistence=Thread { log.i("secondary","TEST_WRITER_FLUSH_BARRIER") }.apply { isDaemon=true;start() }
                try {
                    assertTrue("Actual FileLogWriter flush entered",entered.await(2,TimeUnit.SECONDS))
                    driver.parkGate=CountDownLatch(1)
                    controller.pause().stop()
                    assertEquals("Actual onStop must return before storage is released",1L,releaseIo.count)
                    assertTrue("Actual GPU suspend must be submitted despite blocked persistence",driver.parkEntered.await(1,TimeUnit.SECONDS))
                    val debt=SecondaryHostRegistry.suspend(activity,intent.getStringExtra(SecondaryHostRegistry.TOKEN_EXTRA),host.ready.get().surface)
                    assertFalse(debt.isDone)
                    assertFalse(view.surfaceTextureListener!!.onSurfaceTextureDestroyed(texture))
                    assertEquals("Actual Texture destruction callback must return before storage is released",1L,releaseIo.count)
                    assertTrue("GPU still borrows exact Surface until acknowledgement",host.ready.get().surface.isValid)
                    driver.parkGate!!.countDown()
                    await { debt.isDone }
                    assertTrue(debt.getNow(false))
                    assertFalse("Exact release debt runs after real GPU ack",host.ready.get().surface.isValid)
                    assertEquals("Suspend future must complete before storage is released",1L,releaseIo.count)
                    assertTrue(host.owner.isCurrent)
                } finally {
                    driver.parkGate?.countDown();releaseIo.countDown();persistence.join(2000);watchdog.join(2000)
                    log.fileLogWriter=previous
                }
                return
            }
            if(frontDenied) {
                page(false)
                controller.pause().stop();await { driver.parked.get()==1 }
                page(true);await { frontAttempts==1 || legacyRecentsFronts>0 }
                assertEquals("Denied direct call must never use recents/Home fallback",0,legacyRecentsFronts)
                assertEquals(1,frontAttempts);assertEquals(0,fronts)
                val ring=org.robolectric.util.ReflectionHelpers.getField<java.util.ArrayDeque<com.openautolink.app.diagnostics.LocalLogEntry>>(com.openautolink.app.diagnostics.DiagnosticLog,"ring")
                await { synchronized(ring) { ring.any { it.message.contains("stage=gm_restore_attempt ") && it.message.contains("requested=false reason=permission_denied ") } } }
                assertTrue(synchronized(ring) { ring.none { it.message.contains("test denies REORDER_TASKS") } })
                assertEquals(Triple(activity.taskId,ActivityManager.MOVE_TASK_NO_USER_ACTION,null),frontWrites.single())
                page(true);page(false);page(true)
                assertEquals("Consumed parked return has no retry",1,frontAttempts)
                assertEquals(0,driver.resumed.get())
                assertTrue(host.owner.isCurrent)
                assertNull("No replacement Activity or main-display rescue",shadowOf(app).nextStartedActivity)
                return
            }
            val baseParks=driver.parked.get();val baseResumes=driver.resumed.get()
            val baseStops=stops[activity] ?: 0
            repeat(3) { cycle->
                val held = if (cycle == 0) CountDownLatch(1) else null
                driver.parkGate = held
                if(restore) {
                    page(true);assertEquals("Initial/replayed selection never fronts",cycle,fronts)
                    page(false);page(false);assertEquals("Away never fronts",cycle,fronts)
                }
                controller.pause().stop()
                assertEquals("Framework must actually deliver each onStop before asserting GPU cleanup: ${diagnostic()}",baseStops+cycle+1,stops[activity] ?: 0)
                if (held != null) {
                    assertTrue(driver.parkEntered.await(3,TimeUnit.SECONDS))
                    val debt=SecondaryHostRegistry.suspend(activity,intent.getStringExtra(SecondaryHostRegistry.TOKEN_EXTRA),host.ready.get().surface)
                    assertThrows(TimeoutException::class.java) { debt.get(5,TimeUnit.MILLISECONDS) }
                    assertTrue(host.ready.get().surface.isValid)
                } else await { driver.parked.get()==baseParks+cycle+1 }
                assertFalse(view.surfaceTextureListener!!.onSurfaceTextureDestroyed(texture))
                TextureBoundary.textures.remove(view)
                frame((cycle*2+2).toLong(),false)
                if(restore) {
                    held?.countDown()
                    await { driver.parked.get()==baseParks+cycle+1 }
                    await { !host.ready.get().surface.isValid || cycle>0 }
                    page(true);page(true)
                    await { fronts==cycle+1 || legacyRecentsFronts>0 }
                    assertEquals("AppTask routes through recents and fronts default-display Home",0,legacyRecentsFronts)
                    assertEquals("Exact task, no WITH_HOME, no new display/bounds options",Triple(activity.taskId,ActivityManager.MOVE_TASK_NO_USER_ACTION,null),frontWrites.last())
                    assertEquals("Matching return fronts existing own task once",cycle+1,fronts)
                    page(false);page(true);assertEquals("Replayed parked pairs cannot rearm",cycle+1,fronts)
                    assertEquals("Front request itself never resumes GPU",baseResumes+cycle,driver.resumed.get())
                }
                controller.restart().start().resume()
                texture=available()
                if (held != null && !restore) { assertEquals(baseResumes,driver.resumed.get());held.countDown() }
                await { driver.resumed.get()==baseResumes+cycle+1 }
                frame((cycle*2+3).toLong(),false);await { driver.submitted.get()==cycle*2+3 }
                assertTrue(host.owner.isCurrent);assertFalse(activity.isFinishing)
                assertNull("No page-return relaunch",shadowOf(app).nextStartedActivity)
            }
            assertEquals(1,driver.configured.get())
            if (diagnostics) {
                assertEquals("Controlled callback journey admits every real record",diagnosticDrops!!.toLong(),PageReturnDiagnostics.dropped)
                diagnosticWriterCleanup!!.invoke();diagnosticWriterCleanup=null
                com.openautolink.app.diagnostics.DiagnosticLog.i("secondary","DIRECT_CALLBACK_V1 event=texture_available activity=foreign-fixture pid=0 elapsedMs=0 oemPage=unknown")
                // Observe the actual logger ring, not an injected successful callback or decoder.
                val ring=org.robolectric.util.ReflectionHelpers.getField<java.util.ArrayDeque<com.openautolink.app.diagnostics.LocalLogEntry>>(com.openautolink.app.diagnostics.DiagnosticLog,"ring")
                val identity="activity=${System.identityHashCode(activity)} "
                await { synchronized(ring) { ring.count { it.message.contains(identity) && it.message.contains("event=texture_available ") } == 4 && ring.count { it.message.contains(identity) && it.message.contains("stage=host_suspend_ack ") } >= 3 } }
                val messages=synchronized(ring) { ring.map { it.message }.filter { it.contains(identity) } }
                val callbacks=messages.filter { it.startsWith("DIRECT_CALLBACK_V1 ") }
                for (event in listOf("start","resume","pause","stop","texture_available","texture_destroyed")) {
                    assertTrue("Missing own callback diagnostic $event: $messages",callbacks.any { it.contains("event=$event ") })
                }
                assertTrue(callbacks.all { it.contains("pid=") && it.contains("activity=") && it.contains("elapsedMs=") && it.contains("oemPage=unknown") })
                assertEquals(4, callbacks.count { it.contains("event=texture_available ") })
                assertEquals(3, callbacks.count { it.contains("event=texture_destroyed ") })
                repeat(100) { view.surfaceTextureListener!!.onSurfaceTextureUpdated(texture) }
                val afterUpdates=synchronized(ring) { ring.count { it.message.contains(identity) && it.message.startsWith("DIRECT_CALLBACK_V1 ") } }
                assertEquals("No per-frame callback diagnostic",callbacks.size,afterUpdates)
                assertTrue(messages.any { it.contains("stage=host_suspend_ack ") && it.contains("success=true") })
                assertTrue(messages.any { it.contains("stage=host_resume_request ") })
                assertTrue(messages.none { it.contains(intent.getStringExtra(SecondaryHostRegistry.TOKEN_EXTRA)!!) })
            }
            val tokens=SecondaryHostRegistry::class.java.getDeclaredField("tokens").apply { isAccessible=true }.get(null) as Map<*,*>
            val entry=tokens[intent.getStringExtra(SecondaryHostRegistry.TOKEN_EXTRA)]!!
            val terminal=org.robolectric.util.ReflectionHelpers.getField<CompletableFuture<Boolean>>(entry,"gpuRetired")
            assertTrue("Completed page cycles must not retain an unbounded terminal callback history: ${terminal.numberOfDependents}",terminal.numberOfDependents<=1)
        } finally {
            policyGate?.countDown()
            val retirement=host.retire();await { retirement.isDone };assertTrue(retirement.get())
            view.surfaceTextureListener!!.onSurfaceTextureDestroyed(texture)
            controller.pause().stop().destroy();registry.close();TextureBoundary.textures.clear()
            app.unregisterActivityLifecycleCallbacks(callbacks)
            System.clearProperty("robolectric.createActivityContexts")
            if(restore||restoreSafety)java.io.File(app.noBackupFilesDir,"gm-page-observer").deleteRecursively()
            emergencyCleanup=null
        }
    }
}
