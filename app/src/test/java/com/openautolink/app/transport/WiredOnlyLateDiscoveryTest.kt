package com.openautolink.app.transport

import android.app.Application
import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import com.openautolink.app.data.AppPreferences
import io.mockk.*
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32, 34], application = Application::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
class WiredOnlyLateDiscoveryTest {
    @get:Rule val temp = org.junit.rules.TemporaryFolder()

    @Test fun stopDoesNotSnapshotAKeySetWhoseLastCleanupCanRetireDuringSizeRead() = runBlocking {
        val policy = ProjectionConnectionPolicy().apply { observe(false) }
        val preferences = mockk<AppPreferences>()
        every { preferences.projectionConnectionPolicy } returns policy
        mockkObject(AppPreferences.Companion)
        every { AppPreferences.getInstance(any()) } returns preferences
        val discovery = PhoneDiscovery::class.java.getDeclaredConstructor(Context::class.java)
            .apply { isAccessible = true }.newInstance(mockk<Context>(relaxed = true))
        val ownerScope = discovery.javaClass.getDeclaredField("sweepScope").apply { isAccessible = true }
            .get(discovery) as CoroutineScope
        ownerScope.coroutineContext[Job]!!.cancelAndJoin()
        val key = Any()
        val retired = java.util.concurrent.atomic.AtomicInteger()
        // Force the actual CHM KeySet.size -> iterator.next race without scheduler timing.
        val cleanups = object : java.util.concurrent.ConcurrentHashMap<Any, () -> Unit>() {
            override val size: Int
                get() {
                    val observed = super.size
                    if (observed == 1) remove(key)?.invoke()
                    return observed
                }
        }
        cleanups[key] = { retired.incrementAndGet(); Unit }
        discovery.javaClass.getDeclaredField("resolutionCleanups").apply { isAccessible = true }
            .set(discovery, cleanups)
        try {
            discovery.stop()
            discovery.stop()
            assertEquals("The exact cleanup runs once, even when another stop already took it", 1, retired.get())
            assertTrue(cleanups.isEmpty())
        } finally {
            discovery.stop()
            unmockkAll()
        }
    }

    @Test fun oldNsdCallbacksCannotPublishOrFallbackAfterOnOff() = runBlocking {
        val job = SupervisorJob()
        val store = PreferenceDataStoreFactory.create(scope = CoroutineScope(job + Dispatchers.IO),
            produceFile = { java.io.File(temp.root, "wired.preferences_pb") })
        val preferences = AppPreferences::class.java.getDeclaredConstructor(DataStore::class.java)
            .apply { isAccessible = true }.newInstance(store)
        preferences.setWiredOnly(false)
        mockkObject(AppPreferences.Companion)
        every { AppPreferences.getInstance(any()) } returns preferences
        val nsd = mockk<NsdManager>(relaxed = true)
        val context = mockk<Context>(relaxed = true)
        every { context.getSystemService(Context.NSD_SERVICE) } returns nsd
        val listener = slot<NsdManager.DiscoveryListener>()
        every { nsd.discoverServices(any<String>(), any<Int>(), capture(listener)) } just Runs
        val legacy = slot<NsdManager.ResolveListener>()
        every { nsd.resolveService(any(), capture(legacy)) } just Runs
        val modern = slot<NsdManager.ServiceInfoCallback>()
        val executor = slot<java.util.concurrent.Executor>()
        if (Build.VERSION.SDK_INT >= 34) every { nsd.registerServiceInfoCallback(any(), capture(executor), capture(modern)) } just Runs
        val discovery = PhoneDiscovery::class.java.getDeclaredConstructor(Context::class.java)
            .apply { isAccessible = true }.newInstance(context)
        val info = NsdServiceInfo().apply {
            serviceName = "held phone"
            port = 5277
            host = java.net.InetAddress.getByName("192.0.2.2")
        }
        try {
            discovery.start()
            listener.captured.onServiceFound(info)
            if (Build.VERSION.SDK_INT >= 34) assertTrue(modern.isCaptured) else assertTrue(legacy.isCaptured)
            preferences.setWiredOnly(true)
            discovery.stop()
            if (Build.VERSION.SDK_INT >= 34) {
                verify(exactly = 1) { nsd.unregisterServiceInfoCallback(modern.captured) }
                assertTrue((executor.captured as java.util.concurrent.ExecutorService).isShutdown)
            }
            val beforeLateCallback = discovery.phones.value
            preferences.setWiredOnly(false)
            preferences.projectionConnectionPolicy.retired(preferences.projectionConnectionPolicy.state.value.revision)
            listener.captured.onServiceFound(info)
            if (Build.VERSION.SDK_INT >= 34) {
                modern.captured.onServiceUpdated(info)
                modern.captured.onServiceInfoCallbackRegistrationFailed(1)
                verify(exactly = 0) { nsd.resolveService(any(), any()) }
                verify(exactly = 1) { nsd.registerServiceInfoCallback(any(), any(), any()) }
            } else {
                legacy.captured.onServiceResolved(info)
                verify(exactly = 1) { nsd.resolveService(any(), any()) }
            }
            assertEquals("Old callbacks must not publish a new address", beforeLateCallback, discovery.phones.value)
        } finally {
            discovery.stop()
            val scope = discovery.javaClass.getDeclaredField("sweepScope").apply { isAccessible = true }.get(discovery) as CoroutineScope
            scope.coroutineContext[Job]!!.cancelAndJoin()
            job.cancelAndJoin()
            unmockkAll()
        }
    }
}
