package com.openautolink.app.cluster

import com.openautolink.app.secondary.*
import io.mockk.*
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.TimeUnit

/** Vehicle ordering: ON requalification -> public launch -> unchanged-facts physical
 * callback invalidates exact epoch -> direct Activity arrives. Real runtime/registry,
 * actual visible TextureView callbacks; only platform display/GPU boundaries controlled. */
@RunWith(RobolectricTestRunner::class)
@org.robolectric.annotation.ConscryptMode(org.robolectric.annotation.ConscryptMode.Mode.OFF)
@Config(sdk=[32,34], application=android.app.Application::class, shadows=[ClusterDirectVideoLifecycleTest.Admission::class,ClusterDirectVideoLifecycleTest.TextureBoundary::class])
class PreOfferEpochRecoveryTest {
    @Before fun setup() {
        mockkObject(com.openautolink.app.diagnostics.DiagnosticLog)
        every { com.openautolink.app.diagnostics.DiagnosticLog.i(any(),any()) } answers { println("TRACE " + secondArg<String>()) }
        every { com.openautolink.app.diagnostics.DiagnosticLog.w(any(),any()) } answers { println("TRACE " + secondArg<String>()) }
    }
    @After fun after() { unmockkAll() }
    @Test fun unchangedFactsCallbackBeforeHostRetriesExactEpochWithinOriginalBudget() {
        val f=RetainedHostRuntimeFixture()
        try {
            val initial=f.prepared(false,300);val ack=f.retire(initial);f.pump { ack.isDone };assertTrue(ack.get())
            f.attachHosts=false;f.ignition(false)
            val start=System.nanoTime();val next=f.start(false,301)
            Thread.sleep(758);f.ignition(true)
            f.pump { f.owner()?.handle!=null }
            val predecessor=f.owner()!!.handle!!.owner
            // Physical display callback, same ON/public identity, only attachment epoch changes.
            Thread.sleep(51) // vehicle: public launch -> unchanged-facts callback
            f.ignition(true)
            f.pump { f.owner()?.handle?.owner?.let { it!==predecessor } == true || next.isCompleted }
            if(!next.isCompleted) Thread.sleep(548) // vehicle: revoke -> real host Session arrival
            f.attachHosts=true
            f.pump { next.isCompleted }
            val prepared=runBlocking { next.await() } as PreparedAaSecondaryOutputs
            assertEquals("Automatic preparation must recover before service discovery",listOf(3),prepared.offers.map { it.channelId })
            assertNotSame("Never reuse invalidated direct owner",predecessor,f.owner()!!.handle!!.owner)
            assertTrue(f.runtime.statuses.value.getValue(f.descriptor.id).hostAttached)
            assertTrue("No new preparation deadline",TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-start)<1900)
        } finally { f.close() }
    }
    @Test fun secondCallbackDoesNotCreateUnboundedHostLaunches() {
        val f=RetainedHostRuntimeFixture()
        try {
            f.attachHosts=false;val next=f.start(false,302)
            f.pump { f.owner()?.handle!=null }
            val first=f.owner()!!.handle!!.owner;f.ignition(true)
            f.pump { f.owner()?.handle?.owner?.let { it!==first } == true || next.isCompleted }
            f.ignition(true);f.pump { next.isCompleted }
            assertTrue((runBlocking { next.await() } as PreparedAaSecondaryOutputs).offers.isEmpty())
            assertTrue("At most one exact-epoch retry",f.launches.size<=2)
        } finally { f.close() }
    }
    @Test fun identityChangeNeverRetries() {
        val f=RetainedHostRuntimeFixture()
        try {
            f.attachHosts=false;val next=f.start(false,303)
            // Handle publication precedes Main's guarded launch; invalidate only after that launch.
            f.pump { f.owner()?.handle!=null && f.launches.size==1 }
            assertFalse("Launch alone must not acknowledge the direct host",next.isCompleted)
            f.inventory.value=SecondaryInventorySnapshot(2,listOf(f.descriptor.copy(attachmentEpoch=2,name="replacement")))
            f.attachHosts=true;f.pump { next.isCompleted }
            assertTrue((runBlocking { next.await() } as PreparedAaSecondaryOutputs).offers.isEmpty())
            assertEquals(1,f.launches.size)
        } finally { f.close() }
    }
    @Test fun cancelledTicketAtRetirementBoundaryNeverLaunchesSuccessor() = cancelledTicket(false)
    @Test fun blockedDiagnosticConsumerCannotDelayRetirementBoundaryCancellation() = cancelledTicket(true)
    private fun cancelledTicket(holdConsumer:Boolean) {
        val f=RetainedHostRuntimeFixture();val authority=java.util.concurrent.atomic.AtomicBoolean(true)
        val marker="fixture-diagnostic-hold-${java.util.UUID.randomUUID()}"
        val drainedMarker="fixture-diagnostic-drained-${java.util.UUID.randomUUID()}"
        val entered=java.util.concurrent.CountDownLatch(1)
        val release=java.util.concurrent.CountDownLatch(1)
        val consumerExited=java.util.concurrent.CountDownLatch(1)
        val drained=java.util.concurrent.CountDownLatch(1)
        every { com.openautolink.app.diagnostics.DiagnosticLog.i(any(),any()) } answers {
            val line=secondArg<String>()
            if(line.contains(marker)) {
                entered.countDown()
                try { check(release.await(3,TimeUnit.SECONDS)) } finally { consumerExited.countDown() }
            }
            if(line.contains(drainedMarker))drained.countDown()
            Unit
        }
        mockkObject(PageReturnDiagnostics)
        every { PageReturnDiagnostics.tryOffer(any()) } answers {
            // Cancellation is an ingress decision, not an asynchronous persistence receipt.
            if(firstArg<String>().contains("stage=preoffer_epoch_retry_wait"))authority.set(false)
            callOriginal()
        }
        try {
            if(holdConsumer) {
                assertTrue(PageReturnDiagnostics.tryOffer(marker))
                assertTrue("Actual diagnostic consumer is held",entered.await(1,TimeUnit.SECONDS))
            }
            f.attachHosts=false
            val next=f.scope.async { f.runtime.prepareAndroidAuto(305,f.frozen) { authority.get() } }
            f.pump { f.owner()?.handle!=null && f.launches.size==1 }
            assertFalse("Launch alone must not acknowledge the direct host",next.isCompleted)
            f.ignition(true)
            f.pump { next.isCompleted }
            assertEquals("Cancellation cannot depend on the held diagnostic consumer",1,f.launches.size)
            assertFalse("Ingress cancellation must already be visible",authority.get())
            assertTrue(runBlocking { next.await() }.offers.isEmpty())
            assertEquals(1,f.launches.size)
            if(holdConsumer) {
                assertEquals("No diagnostic persistence was needed for cancellation",1L,release.count)
                assertEquals("Consumer must still be held, not timed out",1L,consumerExited.count)
            }
        } finally {
            release.countDown()
            try {
                try {
                    if(holdConsumer) {
                        assertTrue(PageReturnDiagnostics.tryOffer(drainedMarker))
                        assertTrue("Exact held consumer drained before mock removal",drained.await(2,TimeUnit.SECONDS))
                    }
                } finally { f.close() }
            } finally { unmockkObject(PageReturnDiagnostics) }
        }
    }
    @Test fun privatePhysicalReadbackCannotAuthorizeEpochRetry() {
        val f=RetainedHostRuntimeFixture()
        try {
            f.attachHosts=false;val next=f.start(false,306)
            f.pump { f.owner()?.handle!=null && f.launches.size==1 }
            assertFalse("Launch alone must not acknowledge the direct host",next.isCompleted)
            org.robolectric.Shadows.shadowOf(f.display).setFlags(android.view.Display.FLAG_PRIVATE)
            f.ignition(true);f.pump { next.isCompleted }
            assertTrue((runBlocking { next.await() } as PreparedAaSecondaryOutputs).offers.isEmpty())
            assertEquals(1,f.launches.size)
        } finally { f.close() }
    }
    @Test fun offPhysicalReadbackCannotAuthorizeEpochRetry() {
        val f=RetainedHostRuntimeFixture()
        try {
            f.attachHosts=false;val next=f.start(false,307)
            f.pump { f.owner()?.handle!=null && f.launches.size==1 }
            assertFalse("Launch alone must not acknowledge the direct host",next.isCompleted)
            f.ignition(false)
            f.pump { next.isCompleted }
            assertTrue((runBlocking { next.await() } as PreparedAaSecondaryOutputs).offers.isEmpty())
            assertEquals(1,f.launches.size)
        } finally { f.close() }
    }
    @Test fun alreadyFrozenOfferIsRevokedNotAutomaticallyReoffered() {
        val f=RetainedHostRuntimeFixture()
        try {
            val prepared=f.prepared(false,304) as PreparedAaSecondaryOutputs
            assertEquals(1,prepared.offers.size);f.ignition(true)
            f.pump { f.runtime.statuses.value[f.descriptor.id]?.advertised==false }
            Thread.sleep(100)
            assertEquals(1,f.launches.size)
        } finally { f.close() }
    }
}
