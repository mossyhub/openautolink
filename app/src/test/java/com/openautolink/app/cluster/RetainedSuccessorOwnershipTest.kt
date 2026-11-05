package com.openautolink.app.cluster

import io.mockk.*
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@org.robolectric.annotation.ConscryptMode(org.robolectric.annotation.ConscryptMode.Mode.OFF)
@Config(sdk=[32],application=android.app.Application::class,shadows=[ClusterDirectVideoLifecycleTest.Admission::class,ClusterDirectVideoLifecycleTest.TextureBoundary::class])
class RetainedSuccessorOwnershipTest {
    @Before fun setup() {
        mockkObject(com.openautolink.app.diagnostics.DiagnosticLog)
        every { com.openautolink.app.diagnostics.DiagnosticLog.i(any(),any()) } just Runs
        every { com.openautolink.app.diagnostics.DiagnosticLog.w(any(),any()) } just Runs
    }
    @After fun after() { unmockkAll() }
    @Test fun aaHeldSuccessorTimeoutRetiresInstalledLeaseAndRecovers()=exercise(false,1,false)
    @Test fun cpHeldSuccessorTimeoutRetiresInstalledLeaseAndRecovers()=exercise(true,1,false)
    @Test fun aaCanceledSuccessorRetiresInstalledLeaseAndRecovers()=exercise(false,1,true)
    @Test fun cpCanceledSuccessorRetiresInstalledLeaseAndRecovers()=exercise(true,1,true)
    @Test fun aaFalseSuccessorAttachRetiresInstalledLeaseAndRecovers()=exercise(false,2,false)
    @Test fun cpFalseSuccessorAttachRetiresInstalledLeaseAndRecovers()=exercise(true,2,false)
    @Test fun carPlayPreparationWaitsForActualDirectTextureAndGpuAcknowledgement() {
        val f=RetainedHostRuntimeFixture(fault=3)
        try {
            f.attachHosts=false
            val first=f.start(true,200)
            f.pump { f.launches.size==1 }
            assertFalse("No configuration before OS Texture callback",first.isCompleted)
            assertTrue(f.bridges.isEmpty())
            f.attachHosts=true
            f.pump { f.initialized.get()==1 }
            assertFalse("Texture callback is not the GPU acknowledgement",first.isCompleted)
            assertFalse(f.owner()!!.handle!!.ready.isDone)
            f.gate.countDown();f.pump { first.isCompleted }
            assertTrue(f.offered(runBlocking { first.await() }))
            assertTrue(f.runtime.statuses.value.getValue(f.descriptor.id).hostAttached)
            assertNull("Direct video never acquires AndroidX map authority",ClusterBindingState.frozenMapBinding())
        } finally { f.close() }
    }
    private fun exercise(cp:Boolean,fault:Int,cancel:Boolean) {
        val f=RetainedHostRuntimeFixture(fault=fault)
        try {
            val first=f.prepared(cp,201);assertTrue(f.offered(first))
            assertTrue(f.runtime.statuses.value.getValue(f.descriptor.id).hostAttached)
            val predecessor=f.owner()!!.handle!!.owner
            assertFalse("Direct owners cannot hand off an AndroidX manager",f.runtime.retainForExplicitReconnect())
            val retired=f.retire(first);f.pump { retired.isDone };assertTrue(retired.get())
            val next=f.start(cp,202)
            f.pump { f.bridges.size==2 && f.initialized.get()>=2 }
            val installed=f.hostOwners.last()
            assertEquals(202L,installed.projectionEpoch)
            if(cancel) next.cancel()
            f.pump { next.isCompleted }
            if(!cancel) assertFalse("No optional offer may escape held/failed successor attach",f.offered(runBlocking { next.await() }))
            val cleanup=f.runtime.retire("ordinary full stop after canceled preparation")
            f.gate.countDown();f.pump { cleanup.isDone }
            assertTrue("Actual installed successor lease must retire, not a placeholder lease",cleanup.get())
            assertFalse(predecessor.isCurrent)
            assertFalse(installed.isCurrent)
            assertNull(ClusterBindingState.frozenMapBinding())
            assertTrue("Both generations must positively detach",f.detached.get()>=2)
            val recovery=f.prepared(cp,203)
            assertTrue("A later explicit reconnect must acquire the real process claim and offer again",f.offered(recovery))
            assertEquals(203L,f.owner()!!.handle!!.owner.projectionEpoch)
            assertNotSame(installed,f.owner()!!.handle!!.owner)
            assertEquals(PixelRect(0,0,2000,810),f.runtime.mapping(f.descriptor.id)!!.target.output)
        } finally { f.close() }
    }
}
