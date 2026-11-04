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
@Config(sdk=[32],application=android.app.Application::class,manifest=Config.NONE)
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
    @Test fun completedCpPreparationIsNotAnAndroidXHostCallbackAcknowledgement() {
        val f=RetainedHostRuntimeFixture()
        try {
            f.attachHosts=false
            val first=f.prepared(true,200)
            assertTrue("CP configuration exists before OS callback delivery",f.offered(first))
            assertNull("Held actual AndroidX callback has not run",f.callback)
            f.attachHosts=true
            f.pump { f.callback!=null && f.runtime.currentLiveHostedDisplay()!=null }
            assertNotNull(f.runtime.currentLiveHostedDisplay())
        } finally { f.close() }
    }
    private fun exercise(cp:Boolean,fault:Int,cancel:Boolean) {
        val f=RetainedHostRuntimeFixture(fault=fault)
        try {
            val first=f.prepared(cp,201);assertTrue(f.offered(first));f.captureManager(first)
            // CP preparation may complete before the artificial OS host delivery. Await the
            // actual callback/host receipt rather than dereferencing a callback not yet delivered.
            f.pump { f.callback!=null && f.runtime.currentLiveHostedDisplay()!=null };f.expand()
            assertTrue(f.runtime.retainForExplicitReconnect())
            val retired=f.retire(first);f.pump { retired.isDone };assertTrue(retired.get())
            val next=f.start(cp,202)
            f.pump { f.bridges.size==2 && f.initialized.get()>=2 }
            val installed=requireNotNull(f.binding).snapshot.lease
            assertEquals(202L,installed.projectionEpoch)
            if(cancel) next.cancel()
            f.pump { next.isCompleted }
            if(!cancel) assertFalse("No optional offer may escape held/failed successor attach",f.offered(runBlocking { next.await() }))
            val cleanup=f.runtime.retire("ordinary full stop after canceled preparation")
            f.gate.countDown();f.pump { cleanup.isDone }
            assertTrue("Actual installed successor lease must retire, not a placeholder lease",cleanup.get())
            f.pump { ClusterBindingState.frozenMapBinding()==null }
            assertTrue("Both generations must positively detach",f.detached.get()>=2)
            val recovery=f.prepared(cp,203)
            assertTrue("A later explicit reconnect must acquire the real process claim and offer again",f.offered(recovery))
            assertEquals(135,(f.runtime.mapping(f.descriptor.id) as ClusterVideoMapping).qualifiedDisplay.host.visible.top)
        } finally { f.close() }
    }
}
