package com.openautolink.app.transport

import android.app.Application
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import com.openautolink.app.data.AppPreferences
import com.openautolink.app.transport.aasdk.AasdkSession
import io.mockk.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Real AasdkSession -> real WPP worker; only live-interface observation is held. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32, 34], application = Application::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
class WiredOnlyQueuedWppTest {
    @get:Rule val temp = org.junit.rules.TemporaryFolder()
    @Test fun queuedInterfaceReturnCannotBindAfterWiredOnlySave() = runBlocking {
        val job = SupervisorJob()
        val scope = CoroutineScope(job + Dispatchers.IO)
        val store = PreferenceDataStoreFactory.create(scope = scope,
            produceFile = { java.io.File(temp.root, "wired.preferences_pb") })
        val preferences = AppPreferences::class.java.getDeclaredConstructor(DataStore::class.java)
            .apply { isAccessible = true }.newInstance(store)
        preferences.projectionConnectionPolicy.state.first { it.loaded }
        mockkObject(AppPreferences.Companion, WppInterfacePolicy)
        every { AppPreferences.getInstance(any()) } returns preferences
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        every { WppInterfacePolicy.liveIpv4(any()) } answers {
            entered.countDown()
            check(release.await(10, TimeUnit.SECONDS))
            "127.0.0.1"
        }
        val session = AasdkSession(scope, RuntimeEnvironment.getApplication())
        session.transportMode = "wpp"
        try {
            session.start()
            assertTrue("Actual WPP interface wait was entered", entered.await(5, TimeUnit.SECONDS))
            // Keep the real bind path, but avoid the build host ADB forward on port 5277.
            val server = session.javaClass.getDeclaredField("_wppServer").apply { isAccessible = true }.get(session)
            server.javaClass.getDeclaredField("port").apply { isAccessible = true }.setInt(server, 0)
            preferences.setWiredOnly(true)
            release.countDown()
            assertFalse("A queued pre-Save WPP worker must not bind a listener", withTimeout(5000) { session.awaitWppTransportReady() })
        } finally {
            release.countDown()
            session.cancelPendingWppTransportStart("fixture retirement")
            job.cancelAndJoin()
            unmockkAll()
        }
    }
}
