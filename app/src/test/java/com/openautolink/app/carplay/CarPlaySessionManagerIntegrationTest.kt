package com.openautolink.app.carplay

import com.openautolink.app.session.SessionManager
import com.openautolink.app.session.SessionState
import com.openautolink.app.transport.aasdk.AasdkSession
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Executes real SessionManager lifecycle; only the JNI/native session boundary is mocked.
 * No Bluetooth hardware, Surface decode, iPhone trust or vehicle network proof is implied.
 */
class CarPlaySessionManagerIntegrationTest {
    @Test fun reservedCarPlayOwnerRejectsRealAaStartAndColdReconnect() = runBlocking {
        val scope = CoroutineScope(SupervisorJob())
        val manager = SessionManager(scope)
        val ownership = CarPlayOwnership.process
        val token = ownership.reserve(true)!!
        try {
            manager.start()
            manager.reconnect()
            assertNull(manager.videoDecoder)
            assertEquals(SessionState.IDLE, manager.sessionState.value)
        } finally {
            ownership.acknowledgeAaRetired(token)
            ownership.acknowledgeCarPlayRetired(token)
            assertTrue(ownership.release(token))
            scope.cancel()
            cancelManagerScope(manager)
        }
    }

    @Test fun realSessionStopMustReturnBeforeReceiverFactoryRuns() {
        val scope = CoroutineScope(SupervisorJob())
        val manager = SessionManager(scope)
        val nativeEntered = CountDownLatch(1)
        val nativeRelease = CountDownLatch(1)
        val started = CountDownLatch(1)
        val returned = CountDownLatch(1)
        val native = mockk<AasdkSession>(relaxed = true)
        every { native.stop() } answers {
            nativeEntered.countDown()
            check(nativeRelease.await(5, TimeUnit.SECONDS))
        }
        SessionManager::class.java.getDeclaredField("aasdkSession").apply { isAccessible = true }.set(manager, native)
        val owner = CarPlayOwnership.process
        val coordinator = CarPlayCoordinator(owner, { runBlocking { manager.stop() } }) { }
        try {
            assertTrue(coordinator.connect(true) { object : CarPlayRuntime {
                override fun run() { started.countDown() }
                override fun cancel() {}
                override fun close() {}
            } })
            assertTrue(nativeEntered.await(5, TimeUnit.SECONDS))
            assertTrue(owner.blocksAndroidAuto)
            assertFalse(started.await(50, TimeUnit.MILLISECONDS))
            nativeRelease.countDown()
            assertTrue(started.await(5, TimeUnit.SECONDS))
            coordinator.returnToAndroidAuto { returned.countDown() }
            assertTrue(returned.await(5, TimeUnit.SECONDS))
            assertFalse(owner.blocksAndroidAuto)
        } finally {
            nativeRelease.countDown()
            coordinator.close()
            scope.cancel()
            cancelManagerScope(manager)
        }
    }
    private fun cancelManagerScope(manager: SessionManager) {
        (SessionManager::class.java.getDeclaredField("scope").apply { isAccessible = true }.get(manager) as CoroutineScope).cancel()
    }
}
