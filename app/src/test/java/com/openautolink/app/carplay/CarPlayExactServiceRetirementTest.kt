package com.openautolink.app.carplay

import android.app.Application
import android.os.Looper
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
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
@Config(sdk=[32,34], manifest=Config.NONE, application=Application::class, shadows=[AcknowledgedForegroundShadow::class])
@ConscryptMode(ConscryptMode.Mode.OFF)
class CarPlayExactServiceRetirementTest {
    private fun drain() { shadowOf(Looper.getMainLooper()).idle() }
    private fun removal(lease: CarPlaySessionLease): CompletableFuture<Unit> = CompletableFuture<Unit>().also { result ->
        Thread { try { lease.releaseAndAwait(); result.complete(Unit) } catch (e: Exception) { result.completeExceptionally(e) } }.start()
    }
    private fun eventually(predicate: () -> Boolean) {
        val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
        while (!predicate() && System.nanoTime() < until) { drain(); Thread.sleep(5) }
        assertTrue(predicate())
    }
    @org.robolectric.annotation.Implements(android.app.Service::class)
    class RacingStopShadow : AcknowledgedForegroundShadow() {
        var rejectStop = true
        var failRemoval = false
        var requestedStart = 0
        @org.robolectric.annotation.Implementation
        override fun stopSelfResult(startId: Int): Boolean {
            requestedStart = startId
            return if (rejectStop) false else super.stopSelfResult(startId)
        }
        @org.robolectric.annotation.Implementation(minSdk=24)
        override fun stopForeground(flags: Int) {
            check(!failRemoval) { "fixture-private-message-must-not-be-exported" }
            super.stopForeground(flags)
        }
    }

    @Test @Config(shadows=[RacingStopShadow::class])
    fun newerUndeliveredStartCannotBeStoppedOrAcknowledgedByOldStartId() {
        val app = RuntimeEnvironment.getApplication()
        val first = CarPlaySessionLeases.forContext(app).acquire()
        drain()
        val firstIntent = shadowOf(app).nextStartedService
        val controller = Robolectric.buildService(CarPlaySessionService::class.java).create()
        val service = controller.get()
        val os = org.robolectric.shadow.api.Shadow.extract<RacingStopShadow>(service)
        var second: CarPlaySessionLease? = null
        try {
            service.onStartCommand(firstIntent,0,1); first.awaitReady()
            val done = removal(first)
            eventually { os.requestedStart == 1 }
            assertFalse("Rejected exact stop retains debt",done.isDone)
            second = CarPlaySessionLeases.forContext(app).acquire()
            drain()
            val secondIntent = shadowOf(app).nextStartedService
            first.release()
            assertFalse("Pending successor OS start prevents another stop",shadowOf(service).isStoppedBySelf)
            service.onStartCommand(secondIntent,0,2)
            second.awaitReady()
            done.get(2,TimeUnit.SECONDS)
            assertNotNull("Successor has positively acknowledged protection",shadowOf(service).lastForegroundNotification)
            assertFalse(shadowOf(service).isStoppedBySelf)
            os.rejectStop = false
            val nextDone = removal(second)
            eventually { os.requestedStart == 2 }
            assertFalse(nextDone.isDone)
            controller.destroy()
            nextDone.get(2,TimeUnit.SECONDS)
        } finally { first.release(); second?.release(); controller.destroy() }
    }

    @Test fun replacementCreationAndOldReleaseCannotAcknowledgeOldEpochDestruction() {
        val app = RuntimeEnvironment.getApplication()
        val first = CarPlaySessionLeases.forContext(app).acquire()
        drain()
        val oldIntent = shadowOf(app).nextStartedService
        val old = Robolectric.buildService(CarPlaySessionService::class.java).create()
        var next: org.robolectric.android.controller.ServiceController<CarPlaySessionService>? = null
        var successor: CarPlaySessionLease? = null
        try {
            old.get().onStartCommand(oldIntent,0,1); first.awaitReady()
            val done = removal(first)
            eventually { shadowOf(old.get()).isStoppedBySelf }
            successor = CarPlaySessionLeases.forContext(app).acquire()
            drain()
            val nextIntent = shadowOf(app).nextStartedService
            next = Robolectric.buildService(CarPlaySessionService::class.java).create()
            next.get().onStartCommand(nextIntent,0,1); successor.awaitReady()
            first.release(); drain()
            assertFalse("Replacement creation is not old onDestroy",done.isDone)
            old.destroy()
            done.get(2,TimeUnit.SECONDS)
            assertFalse("Old destruction cannot stop the replacement",shadowOf(next.get()).isStoppedBySelf)
            assertTrue(CarPlaySessionLeases.status().baseReady)
        } finally { first.release(); old.destroy(); successor?.release(); next?.destroy() }
    }

    @Test @Config(shadows=[RacingStopShadow::class])
    fun failedForegroundRemovalRetainsRetryableDebt() {
        val app = RuntimeEnvironment.getApplication()
        val lease = CarPlaySessionLeases.forContext(app).acquire()
        drain()
        val intent = shadowOf(app).nextStartedService
        val controller = Robolectric.buildService(CarPlaySessionService::class.java).create()
        val service = controller.get()
        val os = org.robolectric.shadow.api.Shadow.extract<RacingStopShadow>(service)
        try {
            service.onStartCommand(intent,0,3); lease.awaitReady()
            os.failRemoval = true
            val failed = removal(lease)
            eventually { failed.isDone }
            assertTrue(failed.isCompletedExceptionally)
            assertEquals(0,os.requestedStart)
            os.failRemoval = false; os.rejectStop = false
            val retry = removal(lease)
            eventually { os.requestedStart == 3 }
            assertFalse(retry.isDone)
            controller.destroy(); retry.get(2,TimeUnit.SECONDS)
        } finally { os.failRemoval = false; lease.release(); controller.destroy() }
    }

    @Test fun stoppedForegroundCannotBeRepostedFromRetainedTypeDuringDestroyWait() {
        val app = RuntimeEnvironment.getApplication()
        val lease = CarPlaySessionLeases.forContext(app).acquire()
        drain()
        val intent = shadowOf(app).nextStartedService
        val controller = Robolectric.buildService(CarPlaySessionService::class.java).create()
        val receipts = java.util.concurrent.CopyOnWriteArrayList<CarPlayForegroundReceipt>()
        val observation = CarPlayForegroundReceipts.observe { receipts.add(it) }
        try {
            controller.get().onStartCommand(intent,0,1); lease.awaitReady()
            lease.release()
            assertTrue(shadowOf(controller.get()).isForegroundStopped)
            receipts.clear()
            controller.get().refreshNotification()
            assertFalse("Stored mask cannot authorize a removed notification",receipts.any { it.stage == CarPlayForegroundStage.NOTIFICATION })
        } finally { lease.release(); controller.destroy(); observation.close() }
    }

    @Test fun finalizerReceiptsIdentifyFailedStageAndRetryWithoutReleasingSuccessorAdmission() {
        val phase = java.util.concurrent.atomic.AtomicInteger(0)
        val started = java.util.concurrent.CountDownLatch(1)
        val canceled = java.util.concurrent.CountDownLatch(1)
        val receipts = java.util.concurrent.CopyOnWriteArrayList<CarPlayForegroundReceipt>()
        val observation = CarPlayForegroundReceipts.observe { receipts.add(it) }
        val owner = CarPlayCoordinator(CarPlayOwnership(),{},CarPlaySessionLeaseSource {
            object : CarPlaySessionLease {
                override fun releaseAndAwait() { check(phase.get() > 1) { "private-protection-failure" } }
            }
        },object : CarPlayHostMediaLeaseSource {
            override fun begin(lease: CarPlayHostMediaLease) {}
            override fun finish(lease: CarPlayHostMediaLease) { check(phase.get() > 2) { "private-media-failure" } }
        }) {}
        fun has(stage: String, status: CarPlayForegroundStatus) = receipts.any { it.stage.name == stage && it.status == status }
        try {
            assertTrue(owner.connect(true) { object : CarPlayRuntime {
                override fun run() { started.countDown(); check(canceled.await(5,TimeUnit.SECONDS)) }
                override fun cancel() { canceled.countDown() }
                override fun close() { check(phase.get() > 0) { "private-runtime-failure" } }
            } })
            assertTrue(started.await(3,TimeUnit.SECONDS))
            owner.stop()
            eventually { has("RUNTIME_CLOSE",CarPlayForegroundStatus.FAILED) }
            assertFalse(has("PROTECTION_RELEASE",CarPlayForegroundStatus.REQUESTED))
            assertFalse(owner.connect(true) { error("unretired runtime") })
            phase.set(1); owner.stop()
            eventually { has("PROTECTION_RELEASE",CarPlayForegroundStatus.FAILED) }
            assertFalse(has("MEDIA_FINISH",CarPlayForegroundStatus.REQUESTED))
            assertFalse(owner.connect(true) { error("unretired protection") })
            phase.set(2); owner.stop()
            eventually { has("MEDIA_FINISH",CarPlayForegroundStatus.FAILED) }
            assertFalse(has("OWNERSHIP_RELEASE",CarPlayForegroundStatus.ACKNOWLEDGED))
            phase.set(3); owner.stop()
            eventually { has("OWNERSHIP_RELEASE",CarPlayForegroundStatus.ACKNOWLEDGED) }
            for (stage in listOf("RUNTIME_CLOSE","PROTECTION_RELEASE","MEDIA_FINISH","OWNERSHIP_RELEASE")) {
                assertTrue("Missing $stage acknowledgement",has(stage,CarPlayForegroundStatus.ACKNOWLEDGED))
            }
            val lines = org.robolectric.shadows.ShadowLog.getLogsForTag("OalCarPlayRetirement").map { it.msg }
            assertTrue("Finite receipts reach actual logcat sink",lines.any { it.contains("stage=MEDIA_FINISH status=FAILED") })
            assertFalse(lines.any { it.contains("private-") || it.contains("Exception") })
        } finally {
            phase.set(3); canceled.countDown()
            val returned = java.util.concurrent.CountDownLatch(1)
            owner.returnToAndroidAuto { returned.countDown() }
            eventually { returned.count == 0L }
            owner.close(); observation.close()
        }
    }

    @Test fun retainedTypeStillRequestsExactStopButOnlyDestroyAcknowledgesRemoval() {
        val app = RuntimeEnvironment.getApplication()
        val lease = CarPlaySessionLeases.forContext(app).acquire()
        drain()
        val intent = shadowOf(app).nextStartedService
        val controller = Robolectric.buildService(CarPlaySessionService::class.java).create()
        val service = controller.get()
        val receipts = java.util.concurrent.CopyOnWriteArrayList<CarPlayForegroundReceipt>()
        val observation = CarPlayForegroundReceipts.observe { receipts.add(it) }
        try {
            service.onStartCommand(intent,0,17)
            lease.awaitReady()
            val done = removal(lease)
            eventually { shadowOf(service).isForegroundStopped }
            assertEquals("Actual Android 12L stored type survives foreground removal", CarPlayForegroundPolicy.BASE, service.foregroundServiceType)
            assertTrue("Retained type must not prevent exact stopSelfResult", shadowOf(service).isStoppedBySelf)
            assertEquals(17, shadowOf(service).stopSelfResultId)
            assertFalse("A posted/accepted stop is not destruction", done.isDone)
            assertFalse(receipts.any { it.stage == CarPlayForegroundStage.RETIREMENT && it.status == CarPlayForegroundStatus.ACKNOWLEDGED })
            controller.destroy()
            done.get(2,TimeUnit.SECONDS)
            assertTrue(receipts.any { it.stage == CarPlayForegroundStage.RETIREMENT && it.status == CarPlayForegroundStatus.ACKNOWLEDGED })
        } finally { lease.release(); controller.destroy(); observation.close() }
    }
}
