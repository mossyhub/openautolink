package com.openautolink.app.transport

import android.app.Application
import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import com.openautolink.app.data.AppPreferences
import com.openautolink.app.session.SessionManager
import com.openautolink.app.session.SessionState
import com.openautolink.app.transport.aasdk.AasdkSession
import com.openautolink.app.transport.bluetooth.AaWirelessBtControl
import com.openautolink.app.transport.bluetooth.AaWirelessBtServer
import io.mockk.*
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32, 34], application = Application::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
class WiredOnlyOwnerAdmissionTest {
    @get:Rule val temp = org.junit.rules.TemporaryFolder()
    private val job = SupervisorJob()
    private val scope = CoroutineScope(job + Dispatchers.IO)
    private suspend fun setup(): AppPreferences {
        val store = PreferenceDataStoreFactory.create(scope = scope,
            produceFile = { java.io.File(temp.root, "wired.preferences_pb") })
        val preferences = AppPreferences::class.java.getDeclaredConstructor(DataStore::class.java)
            .apply { isAccessible = true }.newInstance(store)
        preferences.setWiredOnly(true)
        mockkObject(AppPreferences.Companion)
        every { AppPreferences.getInstance(any()) } returns preferences
        return preferences
    }
    @After fun close() = runBlocking { job.cancelAndJoin(); unmockkAll() }

    @Test fun actualAaSessionAndManagerRejectWirelessStartAndRecovery() = runBlocking {
        setup()
        val app = RuntimeEnvironment.getApplication()
        val manager = SessionManager(scope, app)
        for (mode in listOf("wpp", "hotspot")) {
            manager.start(directTransport = mode)
            assertEquals(SessionState.IDLE, manager.sessionState.value)
            val session = AasdkSession(scope, app)
            session.transportMode = mode
            session.start()
            session.forceReconnect("held old wireless recovery")
            session.dialCompanion("127.0.0.1")
            for (name in listOf("_tcpConnector", "_wppServer", "_usbConnectionManager")) {
                assertNull(name, session.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(session))
            }
        }
    }

    @Test fun advertiserRetirementRetainsFailedExactOwnerAndRetriesWithoutAdmission() = runBlocking {
        val preferences = setup()
        val contextField = AaWirelessBtControl.javaClass.getDeclaredField("appContext").apply { isAccessible = true }
        val serverField = AaWirelessBtControl.javaClass.getDeclaredField("btServer").apply { isAccessible = true }
        val previousContext = contextField.get(AaWirelessBtControl)
        assertNull(serverField.get(AaWirelessBtControl))
        val server = mockk<AaWirelessBtServer>(relaxed = true)
        contextField.set(AaWirelessBtControl, RuntimeEnvironment.getApplication())
        serverField.set(AaWirelessBtControl, server)
        var closeFails = true
        coEvery { server.stopAndAwait() } coAnswers { if (closeFails) error("held close failed") }
        try {
            assertNull(AaWirelessBtControl.beginHandshake())
            try {
                AaWirelessBtControl.retireForWiredOnly()
                fail("close failure must propagate")
            } catch (_: IllegalStateException) { }
            preferences.setWiredOnly(false)
            assertFalse("Failed retirement cannot let OFF reopen admission", preferences.projectionConnectionPolicy.wirelessAllowed)
            closeFails = false
            AaWirelessBtControl.retireForWiredOnly()
            preferences.projectionConnectionPolicy.retired(preferences.projectionConnectionPolicy.state.value.revision)
            assertTrue(preferences.projectionConnectionPolicy.wirelessAllowed)
            coVerify(exactly = 2) { server.stopAndAwait() }
        } finally {
            closeFails = false
            // Exact retained retirement is acknowledged even if an earlier assertion fails.
            if (!preferences.projectionConnectionPolicy.wirelessAllowed) AaWirelessBtControl.retireForWiredOnly()
            contextField.set(AaWirelessBtControl, previousContext)
        }
    }
}
