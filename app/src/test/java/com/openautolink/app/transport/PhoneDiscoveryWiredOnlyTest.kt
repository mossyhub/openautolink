package com.openautolink.app.transport

import android.app.Application
import android.content.Context
import android.net.nsd.NsdManager
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import com.openautolink.app.data.AppPreferences
import io.mockk.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32, 34], application = Application::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
class PhoneDiscoveryWiredOnlyTest {
    @get:Rule val temp = org.junit.rules.TemporaryFolder()
    private val job = SupervisorJob()
    private lateinit var discovery: PhoneDiscovery
    @After fun close() = runBlocking {
        if (::discovery.isInitialized) discovery.stop()
        job.cancelAndJoin()
        unmockkAll()
    }
    @Test fun wiredOnlyRejectsRealDiscoveryAndExplicitUdpProbe() = runBlocking {
        val store = PreferenceDataStoreFactory.create(scope = CoroutineScope(job + Dispatchers.IO),
            produceFile = { java.io.File(temp.root, "wired.preferences_pb") })
        val preferences = AppPreferences::class.java.getDeclaredConstructor(DataStore::class.java)
            .apply { isAccessible = true }.newInstance(store)
        preferences.setWiredOnly(true)
        mockkObject(AppPreferences.Companion)
        every { AppPreferences.getInstance(any()) } returns preferences
        val nsd = mockk<NsdManager>(relaxed = true)
        val context = mockk<Context>(relaxed = true)
        every { context.getSystemService(Context.NSD_SERVICE) } returns nsd
        discovery = PhoneDiscovery::class.java.getDeclaredConstructor(Context::class.java)
            .apply { isAccessible = true }.newInstance(context)
        discovery.start()
        verify(exactly = 0) { nsd.discoverServices(any<String>(), any<Int>(), any<NsdManager.DiscoveryListener>()) }
        assertEquals(0, discovery.udpBroadcast("not-an-address", 1))
        discovery.startSweep()
        assertFalse(discovery.isSweeping.value)
        assertTrue(discovery.phones.value.isEmpty())
    }
}
