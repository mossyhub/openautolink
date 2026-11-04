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
 * callback invalidates exact epoch -> AndroidX session arrives. Real runtime/manager,
 * actual registered AndroidX callbacks; only platform display/GPU boundaries controlled. */
@RunWith(RobolectricTestRunner::class)
@org.robolectric.annotation.ConscryptMode(org.robolectric.annotation.ConscryptMode.Mode.OFF)
@Config(sdk=[32,34], application=android.app.Application::class, manifest=Config.NONE)
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
            f.pump { ClusterBindingState.frozenMapBinding()!=null }
            val predecessor=ClusterBindingState.frozenMapBinding()!!
            // Physical display callback, same ON/public identity, only attachment epoch changes.
            Thread.sleep(51) // vehicle: public launch -> unchanged-facts callback
            f.ignition(true)
            f.pump { ClusterBindingState.frozenMapBinding()?.generation?.let { it!=predecessor.generation } == true || next.isCompleted }
            if(!next.isCompleted) Thread.sleep(548) // vehicle: revoke -> real host Session arrival
            f.attachHosts=true
            f.pump { next.isCompleted }
            val prepared=runBlocking { next.await() } as PreparedAaSecondaryOutputs
            assertEquals("Automatic preparation must recover before service discovery",listOf(3),prepared.offers.map { it.channelId })
            assertNotEquals("Never reuse invalidated binding",predecessor.generation,f.binding!!.generation)
            assertTrue(f.runtime.statuses.value.getValue(f.descriptor.id).hostAttached)
            assertTrue("No new preparation deadline",TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-start)<1900)
        } finally { f.close() }
    }
    @Test fun secondCallbackDoesNotCreateUnboundedHostLaunches() {
        val f=RetainedHostRuntimeFixture()
        try {
            f.attachHosts=false;val next=f.start(false,302)
            f.pump { ClusterBindingState.frozenMapBinding()!=null }
            val first=ClusterBindingState.frozenMapBinding()!!;f.ignition(true)
            f.pump { ClusterBindingState.frozenMapBinding()?.generation?.let { it!=first.generation } == true || next.isCompleted }
            f.ignition(true);f.pump { next.isCompleted }
            assertTrue((runBlocking { next.await() } as PreparedAaSecondaryOutputs).offers.isEmpty())
            verify(atMost=2) { f.context.startActivity(any(),any()) }
        } finally { f.close() }
    }
    @Test fun identityChangeNeverRetries() {
        val f=RetainedHostRuntimeFixture()
        try {
            f.attachHosts=false;val next=f.start(false,303)
            f.pump { ClusterBindingState.frozenMapBinding()!=null }
            f.inventory.value=SecondaryInventorySnapshot(2,listOf(f.descriptor.copy(attachmentEpoch=2,name="replacement")))
            f.attachHosts=true;f.pump { next.isCompleted }
            assertTrue((runBlocking { next.await() } as PreparedAaSecondaryOutputs).offers.isEmpty())
            verify(exactly=1) { f.context.startActivity(any(),any()) }
        } finally { f.close() }
    }
    @Test fun cancelledTicketAtRetirementBoundaryNeverLaunchesSuccessor() {
        val f=RetainedHostRuntimeFixture();val authority=java.util.concurrent.atomic.AtomicBoolean(true)
        every { com.openautolink.app.diagnostics.DiagnosticLog.i(any(),any()) } answers {
            if(secondArg<String>().contains("stage=preoffer_epoch_retry_wait")) authority.set(false)
        }
        try {
            f.attachHosts=false
            val next=f.scope.async { f.runtime.prepareAndroidAuto(305,f.frozen) { authority.get() } }
            f.pump { ClusterBindingState.frozenMapBinding()!=null };f.ignition(true)
            f.pump { next.isCompleted }
            assertFalse(authority.get())
            assertTrue(runBlocking { next.await() }.offers.isEmpty())
            verify(exactly=1) { f.context.startActivity(any(),any()) }
        } finally { f.close() }
    }
    @Test fun privatePhysicalReadbackCannotAuthorizeEpochRetry() {
        val f=RetainedHostRuntimeFixture()
        try {
            f.attachHosts=false;val next=f.start(false,306)
            f.pump { ClusterBindingState.frozenMapBinding()!=null }
            every { f.display.flags } returns android.view.Display.FLAG_PRIVATE
            f.ignition(true);f.pump { next.isCompleted }
            assertTrue((runBlocking { next.await() } as PreparedAaSecondaryOutputs).offers.isEmpty())
            verify(exactly=1) { f.context.startActivity(any(),any()) }
        } finally { f.close() }
    }
    @Test fun offPhysicalReadbackCannotAuthorizeEpochRetry() {
        val f=RetainedHostRuntimeFixture()
        try {
            f.attachHosts=false;val next=f.start(false,307)
            f.pump { ClusterBindingState.frozenMapBinding()!=null };f.ignition(false)
            f.pump { next.isCompleted }
            assertTrue((runBlocking { next.await() } as PreparedAaSecondaryOutputs).offers.isEmpty())
            verify(exactly=1) { f.context.startActivity(any(),any()) }
        } finally { f.close() }
    }
    @Test fun alreadyFrozenOfferIsRevokedNotAutomaticallyReoffered() {
        val f=RetainedHostRuntimeFixture()
        try {
            val prepared=f.prepared(false,304) as PreparedAaSecondaryOutputs
            assertEquals(1,prepared.offers.size);f.ignition(true)
            f.pump { f.runtime.statuses.value[f.descriptor.id]?.advertised==false }
            Thread.sleep(100)
            verify(exactly=1) { f.context.startActivity(any(),any()) }
        } finally { f.close() }
    }
}
