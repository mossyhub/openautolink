package com.openautolink.app.carplay

import android.app.Application
import android.content.pm.ServiceInfo
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[32,34], manifest=Config.NONE, application=Application::class,shadows=[AcknowledgedForegroundShadow::class])
@ConscryptMode(ConscryptMode.Mode.OFF)
class CarPlayForegroundLifecycleTest {
    private fun nextStart(app: Application): android.content.Intent? {
        shadowOf(android.os.Looper.getMainLooper()).idle()
        return shadowOf(app).nextStartedService
    }
    private fun visibility(method: String, vararg args: Any): Any? {
        val type = runCatching { Class.forName("com.openautolink.app.carplay.CarPlayForegroundVisibility") }.getOrNull()
        assertNotNull("Exact resumed visibility must exist", type)
        return type!!.methods.single { it.name == method }.invoke(type.getField("INSTANCE").get(null), *args)
    }
    private fun admitted(lease: CarPlaySessionLease): Boolean {
        val method = CarPlaySessionLease::class.java.methods.firstOrNull { it.name == "captureAllowed" }
        assertNotNull("Capture must have ongoing exact-attempt service authority", method)
        return method!!.invoke(lease) as Boolean
    }
    @Test fun visibleGrantedAttemptGetsMicBeforeReadyAndLosesAuthorityOnServiceDeath() {
        val app = RuntimeEnvironment.getApplication()
        shadowOf(app).grantPermissions(android.Manifest.permission.RECORD_AUDIO)
        val activity = Robolectric.buildActivity(android.app.Activity::class.java).setup()
        visibility("resumed", activity.get())
        val lease = CarPlaySessionLeases.forContext(app).acquire()
        val start = nextStart(app)
        val service = Robolectric.buildService(CarPlaySessionService::class.java).create()
        try {
            service.get().onStartCommand(start,0,1)
            lease.awaitReady()
            assertEquals(ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE or
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,
                service.get().foregroundServiceType)
            assertTrue(admitted(lease))
            assertTrue(lease.playbackAllowed())
            visibility("paused",activity.get())
            assertTrue("Established session survives normal pause", admitted(lease))
            assertTrue("Activity pause is not OEM deselection or service loss",lease.playbackAllowed())
            service.destroy()
            assertFalse("Readiness history cannot preserve capture after service death", admitted(lease))
            assertFalse(lease.playbackAllowed())
        } finally { lease.release(); visibility("paused",activity.get()); activity.pause().stop().destroy() }
    }

    @Test fun acquiringUnderAnOwnerMonitorNeverCallsAndroidUnderThatMonitor() {
        val app=RuntimeEnvironment.getApplication()
        val ownerLock=Any()
        val underLock=java.util.concurrent.atomic.AtomicBoolean()
        val context=object : android.content.ContextWrapper(app) {
            override fun startForegroundService(intent: android.content.Intent): android.content.ComponentName? {
                underLock.set(Thread.holdsLock(ownerLock))
                return super.startForegroundService(intent)
            }
        }
        val lease=synchronized(ownerLock) { CarPlaySessionLeases(context).acquire() }
        try {
            shadowOf(android.os.Looper.getMainLooper()).idle()
            assertFalse("No Binder operation while coordinator admission monitor is held",underLock.get())
        } finally {
            lease.release()
            val service=Robolectric.buildService(CarPlaySessionService::class.java).create()
            service.get().onStartCommand(nextStart(app),0,1)
            service.destroy()
        }
    }
    @Test fun delayedOldStartCannotAcknowledgeSuccessorBeforeItsOwnIntent() {
        val app = RuntimeEnvironment.getApplication()
        val first = CarPlaySessionLeases.forContext(app).acquire()
        val oldStart = nextStart(app)
        first.release()
        val second = CarPlaySessionLeases.forContext(app).acquire()
        val newStart = nextStart(app)
        val service = Robolectric.buildService(CarPlaySessionService::class.java).create()
        val ready = java.util.concurrent.CountDownLatch(1)
        val waiter = Thread { try { second.awaitReady(); ready.countDown() } catch (_: Exception) {} }
        try {
            service.get().onStartCommand(oldStart,0,1)
            waiter.start()
            assertFalse("Only the exact delivered attempt may become ready", ready.await(100,java.util.concurrent.TimeUnit.MILLISECONDS))
            service.get().onStartCommand(newStart,0,2)
            assertTrue(ready.await(2,java.util.concurrent.TimeUnit.SECONDS))
        } finally { second.release(); waiter.join(2000); service.destroy() }
    }

    @Test fun permissionRevocationWaitsForExactCaptureRetirementBeforeDemotion() {
        val app = RuntimeEnvironment.getApplication()
        shadowOf(app).grantPermissions(android.Manifest.permission.RECORD_AUDIO)
        val activity = Robolectric.buildActivity(android.app.Activity::class.java).setup()
        CarPlayForegroundVisibility.resumed(activity.get())
        val lease = CarPlaySessionLeases.forContext(app).acquire()
        val start = nextStart(app)
        val service = Robolectric.buildService(CarPlaySessionService::class.java).create()
        val entered = java.util.concurrent.CountDownLatch(1)
        val released = java.util.concurrent.CountDownLatch(1)
        try {
            service.get().onStartCommand(start,0,1)
            lease.awaitReady()
            val retire = CarPlaySessionLease::class.java.methods.firstOrNull { it.name == "onCaptureRetirement" }
            assertNotNull("Exact recorder retirement hook must exist",retire)
            retire!!.invoke(lease, { entered.countDown(); released.await(2,java.util.concurrent.TimeUnit.SECONDS); Unit })
            shadowOf(app).denyPermissions(android.Manifest.permission.RECORD_AUDIO)
            CarPlaySessionLeases.reconcile(app)
            assertFalse(lease.captureAllowed())
            assertTrue("Microphone permission does not gate MEDIA playback",lease.playbackAllowed())
            assertTrue(entered.await(2,java.util.concurrent.TimeUnit.SECONDS))
            assertEquals(CarPlayForegroundPolicy.BASE or CarPlayForegroundPolicy.MIC,service.get().foregroundServiceType)
            released.countDown()
            repeat(100) {
                shadowOf(android.os.Looper.getMainLooper()).idle()
                if (service.get().foregroundServiceType == CarPlayForegroundPolicy.BASE) return@repeat
                Thread.sleep(5)
            }
            assertEquals("Only recorder retirement acknowledges microphone purpose removal",CarPlayForegroundPolicy.BASE,service.get().foregroundServiceType)
        } finally { released.countDown(); lease.release(); service.destroy(); CarPlayForegroundVisibility.paused(activity.get()); activity.pause().stop().destroy() }
    }

    @Test fun serviceDeathBeforeInitialIntentFailsPendingAttemptAndAcknowledgesRetirement() {
        val app = RuntimeEnvironment.getApplication()
        val lease = CarPlaySessionLeases.forContext(app).acquire()
        val start = nextStart(app)
        val service = Robolectric.buildService(CarPlaySessionService::class.java).create()
        service.destroy()
        val done = java.util.concurrent.CompletableFuture<Boolean>()
        Thread {
            try { lease.awaitReady(); done.complete(false) }
            catch (_: Exception) { done.complete(true) }
        }.start()
        try {
            assertTrue("Pending starts belong to the dead service epoch",done.get(2,java.util.concurrent.TimeUnit.SECONDS))
            val removed = java.util.concurrent.CompletableFuture<Unit>()
            Thread { try { lease.releaseAndAwait(); removed.complete(Unit) } catch (e:Exception) { removed.completeExceptionally(e) } }.start()
            repeat(20) { shadowOf(android.os.Looper.getMainLooper()).idle(); Thread.sleep(5) }
            removed.get(2,java.util.concurrent.TimeUnit.SECONDS)
            val replacement = Robolectric.buildService(CarPlaySessionService::class.java).create()
            try { replacement.get().onStartCommand(start,0,1); assertFalse(lease.captureAllowed()) }
            finally { replacement.destroy() }
        } finally { lease.release() }
    }

    @Test fun finiteForegroundReceiptApiExistsWithoutFreeTextOrOwnerIdentifiers() {
        val type = runCatching { Class.forName("com.openautolink.app.carplay.CarPlayForegroundReceipt") }.getOrNull()
        assertNotNull("Foreground outcomes must have typed finite hooks",type)
        assertEquals(setOf("stage","status","mask"),type!!.declaredFields.filterNot { it.isSynthetic || java.lang.reflect.Modifier.isStatic(it.modifiers) }.map { it.name }.toSet())
        assertTrue(type.declaredFields.filterNot { it.isSynthetic || java.lang.reflect.Modifier.isStatic(it.modifiers) }.all { it.type.isEnum })
    }

    @Test fun deniedMicrophoneStillAcknowledgesConnectionAndPlayback() {
        val app = RuntimeEnvironment.getApplication()
        shadowOf(app).denyPermissions(android.Manifest.permission.RECORD_AUDIO)
        val lease = CarPlaySessionLeases.forContext(app).acquire()
        assertFalse("No protected service admission before its real acknowledgement",lease.playbackAllowed())
        val start = nextStart(app)
        val service = Robolectric.buildService(CarPlaySessionService::class.java).create()
        try {
            service.get().onStartCommand(start,0,1)
            lease.awaitReady()
            assertEquals(ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE or
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK,service.get().foregroundServiceType)
            assertTrue(lease.playbackAllowed())
        } finally { lease.release(); service.destroy() }
    }
}
