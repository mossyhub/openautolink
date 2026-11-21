package com.openautolink.app.transport

import android.app.Application
import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import com.openautolink.app.data.AppPreferences
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34], application=Application::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
class PhoneDiscoveryNsdLifecycleTest {
    private data class Registration(val executor: ExecutorService, val callback: NsdManager.ServiceInfoCallback)
    private lateinit var policy: ProjectionConnectionPolicy
    private lateinit var discovery: PhoneDiscovery
    private lateinit var nsd: NsdManager
    private val listener=slot<NsdManager.DiscoveryListener>()
    private val registrations=CopyOnWriteArrayList<Registration>()
    private val unregisters=CopyOnWriteArrayList<NsdManager.ServiceInfoCallback>()
    private val legacy=CopyOnWriteArrayList<NsdServiceInfo>()
    private fun info()=NsdServiceInfo().apply { serviceName="controlled NSD peer";port=5277;host=InetAddress.getByName("192.0.2.2") }
    @Before fun setup()=runBlocking {
        policy=ProjectionConnectionPolicy().apply { observe(false) }
        val preferences=mockk<AppPreferences>()
        every { preferences.projectionConnectionPolicy } returns policy
        mockkObject(AppPreferences.Companion)
        every { AppPreferences.getInstance(any()) } returns preferences
        nsd=mockk(relaxed=true)
        every { nsd.discoverServices(any<String>(),any<Int>(),capture(listener)) } just Runs
        every { nsd.registerServiceInfoCallback(any(),any(),any()) } answers {
            registrations.add(Registration(secondArg<Executor>() as ExecutorService,thirdArg()));Unit
        }
        every { nsd.unregisterServiceInfoCallback(any()) } answers { unregisters.add(firstArg());Unit }
        every { nsd.resolveService(any(),any()) } answers { legacy.add(firstArg());Unit }
        val context=mockk<Context>(relaxed=true)
        every { context.getSystemService(Context.NSD_SERVICE) } returns nsd
        discovery=PhoneDiscovery::class.java.getDeclaredConstructor(Context::class.java).apply { isAccessible=true }.newInstance(context)
        val scope=discovery.javaClass.getDeclaredField("sweepScope").apply { isAccessible=true }.get(discovery) as CoroutineScope
        scope.coroutineContext[Job]!!.cancelAndJoin()
        discovery.start();listener.captured.onServiceFound(info())
        assertEquals(1,registrations.size)
    }
    @After fun cleanup() {
        try { discovery.stop() } finally {
            registrations.forEach { it.executor.shutdownNow();it.executor.awaitTermination(5,TimeUnit.SECONDS) }
            unmockkAll()
        }
    }
    private fun acknowledge(r: Registration) {
        r.executor.submit { r.callback.onServiceInfoCallbackUnregistered() }.get(5,TimeUnit.SECONDS)
        assertTrue("Acknowledged executor must terminate",r.executor.awaitTermination(5,TimeUnit.SECONDS))
    }
    @Test fun registrationFailureRetiresExecutorAndFallsBackOnlyWhileAdmitted() {
        val r=registrations.single()
        r.executor.submit { r.callback.onServiceInfoCallbackRegistrationFailed(1) }.get(5,TimeUnit.SECONDS)
        assertTrue(r.executor.awaitTermination(5,TimeUnit.SECONDS))
        assertEquals(1,legacy.size)
        discovery.stop()
        assertTrue("Failed registration owns no unregister request",unregisters.isEmpty())
    }
    @Test fun registrationFailureAfterPolicyRetirementClosesWithoutLegacyFallback() {
        val r=registrations.single();policy.observe(true);discovery.stop()
        assertFalse("Keep terminal delivery possible after stop",r.executor.isShutdown)
        r.executor.submit { r.callback.onServiceInfoCallbackRegistrationFailed(1) }.get(5,TimeUnit.SECONDS)
        assertTrue(r.executor.awaitTermination(5,TimeUnit.SECONDS))
        assertTrue(legacy.isEmpty());assertEquals(1,unregisters.size)
    }
    @Test fun repeatedStopUnregistersOnceAndAcknowledgmentPermitsFreshRegistration() {
        val first=registrations.single();discovery.stop();discovery.stop()
        assertEquals(listOf(first.callback),unregisters)
        assertFalse(first.executor.isShutdown)
        acknowledge(first)
        discovery.start();listener.captured.onServiceFound(info())
        assertEquals(2,registrations.size)
        val second=registrations.last();assertNotSame(first.executor,second.executor)
        assertFalse(second.executor.isShutdown)
        discovery.stop();assertEquals(listOf(first.callback,second.callback),unregisters)
        acknowledge(second)
    }
    @Test fun queuedUpdateAfterPolicyRetirementIsDrainedWithoutPublicationOrRejection() {
        val r=registrations.single();val entered=CountDownLatch(1);val release=CountDownLatch(1)
        r.executor.execute { entered.countDown();check(release.await(5,TimeUnit.SECONDS)) }
        assertTrue(entered.await(5,TimeUnit.SECONDS))
        try {
            val queued=r.executor.submit { r.callback.onServiceUpdated(info()) }
            val before=discovery.phones.value;policy.observe(true);discovery.stop()
            assertFalse(r.executor.isShutdown)
            release.countDown();queued.get(5,TimeUnit.SECONDS)
            assertEquals(before,discovery.phones.value)
            assertTrue(legacy.isEmpty());assertEquals(1,unregisters.size)
            acknowledge(r)
        } finally { release.countDown() }
    }
}
