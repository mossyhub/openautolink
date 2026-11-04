package com.openautolink.app.diagnostics.gm

import android.app.Application
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.PermissionInfo
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import org.robolectric.Shadows.shadowOf
import java.io.File

/** Own-service callback fixtures only: NOT forged OEM broadcasts or cross-UID enforcement proof. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[32,34], application=Application::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
class GmPageObserverTest {
    private val context get()=RuntimeEnvironment.getApplication()
    @Before fun reset() { File(context.noBackupFilesDir,"gm-page-observer").deleteRecursively() }
    @Test fun installedManifestHasHonestDiscoveryAndSignatureGate() {
        val pm=context.packageManager
        val component=ComponentName(context,GmPageObserverService::class.java)
        val info=pm.getServiceInfo(component,PackageManager.GET_META_DATA)
        assertTrue(info.enabled);assertTrue(info.exported)
        assertEquals(context.packageName+".permission.OBSERVE_CLUSTER_PAGE",info.permission)
        val permission=pm.getPermissionInfo(info.permission,0)
        assertEquals(context.packageName,permission.packageName)
        assertEquals(PermissionInfo.PROTECTION_SIGNATURE,permission.protectionLevel)
        assertEquals("oal_page_selection_observer",info.metaData.getString("AppType"))
        assertEquals("IPC",info.metaData.getString("SupportedDisplay"))
        assertEquals(context.packageName+":gmPageObserver",info.processName)
        val matches=pm.queryIntentServices(Intent("gm.cluster.action.APP_SELECTED").addCategory("com.gm.intent.category.COREAPP"),PackageManager.GET_META_DATA)
        assertTrue(matches.any{it.serviceInfo.name==GmPageObserverService::class.java.name})
        assertTrue(GmObserverGate.qualified(context))
    }
    @Test fun defaultMonitoringRetainsActionReceiptWhenVendorDecodingFailsClosed() {
        val store=GmObserverStore(context)
        assertTrue(store.read().enabled)
        val controller=Robolectric.buildService(GmPageObserverService::class.java).create()
        val service=controller.get()
        // Intent accessor tripwire: even scalar extra access can unmarshal vendor Parcelables.
        val intent=object:Intent(GmPageObserverService.FULL_MAP_SELECTED) {
            override fun getExtras():android.os.Bundle?=throw android.os.BadParcelableException("unavailable vendor class")
            override fun getIntExtra(name:String?,defaultValue:Int):Int=throw android.os.BadParcelableException("unavailable vendor class")
        }
        assertEquals(android.app.Service.START_NOT_STICKY,service.onStartCommand(intent,0,9))
        await{store.read().received==1L}
        val state=store.read()
        assertTrue(state.lastElapsed>0);assertTrue(state.lastWall>0)
        assertEquals(0L,state.ignored)
        await{shadowOf(service).isStoppedBySelf}
        assertEquals(9,shadowOf(service).stopSelfResultId)
        assertNull(service.onBind(intent));controller.destroy()
    }
    @Test fun unknownAndNullActionsAreBoundedIgnoredAndNeverPersistTheirText() {
        val store=GmObserverStore(context)
        val controller=Robolectric.buildService(GmPageObserverService::class.java).create()
        val service=controller.get()
        service.onStartCommand(null,0,1)
        service.onStartCommand(Intent("SECRET-"+"x".repeat(50000)),0,2)
        await{store.read().ignored==2L}
        assertEquals(0L,store.read().received)
        assertTrue(File(context.noBackupFilesDir,"gm-page-observer/state").length()<4096)
        controller.destroy()
    }
    @Test fun savedOffAndGenerationRejectInflightWorkAndSurviveReopen() {
        val store=GmObserverStore(context)
        val original=store.read().generation
        assertTrue(store.setEnabled(false))
        assertFalse(GmObserverStore(context).read().enabled)
        assertFalse(store.receive(original,true,10,20))
        assertTrue(store.setEnabled(true))
        assertFalse(store.receive(original,true,10,20))
        assertEquals(0L,store.read().received)
        assertTrue(store.receive(store.read().generation,true,10,20))
        assertEquals(1L,store.read().received)
    }
    @Test fun disabledActualComponentAndUnqualifiedPermissionFailClosed() {
        val pm=context.packageManager
        val component=ComponentName(context,GmPageObserverService::class.java)
        pm.setComponentEnabledSetting(component,PackageManager.COMPONENT_ENABLED_STATE_DISABLED,PackageManager.DONT_KILL_APP)
        assertFalse(GmObserverGate.qualified(context))
        val controller=Robolectric.buildService(GmPageObserverService::class.java).create()
        // ServiceController.attach forcibly enables its target. Model a real disable AFTER
        // attachment, then exercise the production worker's actual PackageManager gate.
        pm.setComponentEnabledSetting(component,PackageManager.COMPONENT_ENABLED_STATE_DISABLED,PackageManager.DONT_KILL_APP)
        assertFalse(GmObserverGate.qualified(context))
        controller.get().onStartCommand(Intent(GmPageObserverService.FULL_MAP_SELECTED),0,1)
        await{shadowOf(controller.get()).isStoppedBySelf}
        assertEquals(0L,GmObserverStore(context).read().received)
        controller.destroy()
        pm.setComponentEnabledSetting(component,PackageManager.COMPONENT_ENABLED_STATE_ENABLED,PackageManager.DONT_KILL_APP)
        val bad=pm.getPermissionInfo(context.packageName+".permission.OBSERVE_CLUSTER_PAGE",0)
        bad.protectionLevel=PermissionInfo.PROTECTION_NORMAL
        shadowOf(pm).addPermissionInfo(bad)
        assertFalse(GmObserverGate.qualified(context))
    }
    @Test fun receiptWorkerWaitsBehindOwnSnapshotLockWithoutLosingTheCounter() {
        val store=GmObserverStore(context);val epoch=store.read().generation
        val field=GmObserverStore::class.java.getDeclaredField("processLock").apply{isAccessible=true}
        val lock=field.get(null) as java.util.concurrent.locks.ReentrantLock
        val worker=java.util.concurrent.Executors.newSingleThreadExecutor()
        lock.lock()
        try {
            val future=worker.submit<Boolean>{store.receive(epoch,true,1,1)}
            // A snapshot read may own this same lock. Only the bounded disk worker waits,
            // never the Service callback. Contention must not silently eat its receipt.
            Thread.sleep(100);assertFalse(future.isDone)
            lock.unlock();assertTrue(future.get(2,java.util.concurrent.TimeUnit.SECONDS))
            assertEquals(1L,store.read().received)
        }finally{if(lock.isHeldByCurrentThread)lock.unlock();worker.shutdownNow()}
    }
    @Test fun floodRemainsFixedStorageAndNeverCreatesProjection() {
        val store=GmObserverStore(context)
        val epoch=store.read().generation
        repeat(2000){store.receive(epoch,true,it.toLong()+1,it.toLong()+1)}
        assertEquals(2000L,store.read().received)
        val before=Thread.getAllStackTraces().keys.toSet()
        val controller=Robolectric.buildService(GmPageObserverService::class.java).create()
        val workers=Thread.getAllStackTraces().keys.filter{it !in before && it.name=="OalPageDiagnostic"}
        val producer=java.util.concurrent.Executors.newSingleThreadExecutor()
        try {
            producer.submit {
                repeat(10000){controller.get().onStartCommand(Intent(GmPageObserverService.FULL_MAP_SELECTED),0,it+1)}
            }.get(2,java.util.concurrent.TimeUnit.SECONDS)
            await{shadowOf(controller.get()).isStoppedBySelf}
        }finally{controller.destroy();producer.shutdownNow();workers.forEach{it.join(5000);assertFalse(it.isAlive)}}
        assertTrue(File(context.noBackupFilesDir,"gm-page-observer/state").length()<4096)
        assertNull(com.openautolink.app.session.SessionManager.instanceOrNull())
    }
    private fun await(predicate:()->Boolean) {
        val deadline=System.nanoTime()+5_000_000_000L
        while(!predicate() && System.nanoTime()<deadline){shadowOf(android.os.Looper.getMainLooper()).idle();Thread.sleep(5)}
        assertTrue(predicate())
    }
}
