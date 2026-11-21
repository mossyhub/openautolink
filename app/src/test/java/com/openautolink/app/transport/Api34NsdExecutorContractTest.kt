package com.openautolink.app.transport

import android.app.Application
import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import com.openautolink.app.data.AppPreferences
import com.openautolink.app.transport.bluetooth.AaWirelessBtControl
import io.mockk.*
import java.net.InetAddress
import java.util.concurrent.*
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

/** Real production resolver/executor with delayed framework-shaped terminal submission. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34], application=Application::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
class Api34NsdExecutorContractTest {
    private fun exercise(usableIpv4: Boolean) = runBlocking {
        val policy=ProjectionConnectionPolicy().apply { observe(false) }
        val preferences=mockk<AppPreferences>()
        every { preferences.projectionConnectionPolicy } returns policy
        mockkObject(AppPreferences.Companion)
        every { AppPreferences.getInstance(any()) } returns preferences
        mockkObject(AaWirelessBtControl)
        every { AaWirelessBtControl.readvertiseForNewCompanionAddress(any(),any(),any(),any()) } just Runs
        val nsd=mockk<NsdManager>(relaxed=true)
        val context=mockk<Context>(relaxed=true)
        every { context.getSystemService(Context.NSD_SERVICE) } returns nsd
        val listener=slot<NsdManager.DiscoveryListener>()
        val callback=slot<NsdManager.ServiceInfoCallback>()
        val executor=slot<Executor>()
        every { nsd.discoverServices(any<String>(),any<Int>(),capture(listener)) } just Runs
        every { nsd.registerServiceInfoCallback(any(),capture(executor),capture(callback)) } just Runs
        val unregisterRequested=CountDownLatch(1)
        every { nsd.unregisterServiceInfoCallback(any()) } answers { unregisterRequested.countDown();Unit }
        val discovery=PhoneDiscovery::class.java.getDeclaredConstructor(Context::class.java).apply { isAccessible=true }.newInstance(context)
        val scope=discovery.javaClass.getDeclaredField("sweepScope").apply { isAccessible=true }.get(discovery) as CoroutineScope
        scope.coroutineContext[Job]!!.cancelAndJoin()
        val info=NsdServiceInfo().apply { serviceName="RCA controlled peer";port=5277;host=InetAddress.getByName("192.0.2.2") }
        val framework=Executors.newSingleThreadExecutor { Thread(it,"NsdManager-RCA-framework-shaped-dispatch") }
        try {
            discovery.start()
            listener.captured.onServiceFound(info)
            assertTrue("Actual API34 production registration must run",callback.isCaptured && executor.isCaptured)
            if(usableIpv4) {
                // Unlike the shipped fixture, route update THROUGH the real production executor.
                (executor.captured as ExecutorService).submit { callback.captured.onServiceUpdated(info) }.get(5,TimeUnit.SECONDS)
            } else {
                policy.observe(true)
                discovery.stop()
            }
            assertTrue("Actual production code must request unregister",unregisterRequested.await(5,TimeUnit.SECONDS))
            val terminalDelivered=CountDownLatch(1)
            // Android's contract requires terminal notification on the registered executor.
            // A separate dispatcher models framework submission AFTER unregister returned.
            val dispatch=framework.submit {
                executor.captured.execute {
                    callback.captured.onServiceInfoCallbackUnregistered()
                    terminalDelivered.countDown()
                }
            }
            try { dispatch.get(5,TimeUnit.SECONDS) }
            catch(e:ExecutionException) { throw AssertionError("API34 terminal callback rejected after production unregister: executor lifetime ended before framework acknowledgment",e.cause) }
            assertTrue("Terminal callback must actually execute",terminalDelivered.await(5,TimeUnit.SECONDS))
        } finally {
            discovery.stop()
            if(executor.isCaptured) (executor.captured as ExecutorService).shutdownNow()
            framework.shutdownNow();framework.awaitTermination(5,TimeUnit.SECONDS)
            unmockkAll()
        }
    }
    @Test fun usableIpv4MustLeaveExecutorOpenForAsynchronousUnregisterAcknowledgment() { exercise(true) }
    @Test fun policyStopMustLeaveExecutorOpenForAsynchronousUnregisterAcknowledgment() { exercise(false) }
    @Test fun controlTerminalSubmissionBeforeExecutorShutdownSucceeds() {
        val executor=Executors.newSingleThreadExecutor();val delivered=CountDownLatch(1)
        try { executor.execute { delivered.countDown() };assertTrue(delivered.await(5,TimeUnit.SECONDS)) }
        finally { executor.shutdownNow();executor.awaitTermination(5,TimeUnit.SECONDS) }
    }
}
