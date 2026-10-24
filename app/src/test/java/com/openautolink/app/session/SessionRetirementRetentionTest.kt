package com.openautolink.app.session

import com.openautolink.app.transport.aasdk.AasdkSession
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/** Real lifecycle execution; mock only the native session boundary. No device capture. */
class SessionRetirementRetentionTest {
    @Test fun lateNativeCallbackCannotReAdoptAfterDiagnosticRetirement() = runBlocking {
        val parent=CoroutineScope(SupervisorJob());val manager=SessionManager(parent)
        val stale=mockk<AasdkSession>(relaxed=true)
        val prepare=SessionManager::class.java.getDeclaredMethod("prepareNativeSessionStart",AasdkSession::class.java).apply { isAccessible=true }
        try {
            manager.stop()
            assertEquals("native entry needs a still-live projection claim",false,prepare.invoke(manager,stale))
        } finally {manager.stop();parent.cancel();(field("scope").get(manager) as CoroutineScope).cancel()}
    }

    @Test fun diagnosticLeaseRejectsRealAaStartBeforeComponents() = runBlocking {
        val parent = CoroutineScope(SupervisorJob())
        val manager = SessionManager(parent)
        val lease = com.shilapi.xcertplay.media.MicrophoneCaptureFence.tryDiagnostic()!!
        try {
            manager.start()
            assertNull("AA must reserve capture claim before component allocation", manager.videoDecoder)
        } finally {
            lease.release(); manager.stop(); parent.cancel()
            (field("scope").get(manager) as CoroutineScope).cancel()
        }
    }

    @Test fun failedStopRetainsExactNativeOwnerEvenWhenUiIsIdle() = runBlocking {
        val parent = CoroutineScope(SupervisorJob())
        val manager = SessionManager(parent)
        val native = mockk<AasdkSession>(relaxed = true)
        every { native.stop() } throws IllegalStateException("injected stop failure")
        field("aasdkSession").set(manager, native)
        try {
            val failure = runCatching { manager.stop() }.exceptionOrNull()
            assertTrue(failure is IllegalStateException)
            assertEquals(SessionState.IDLE, manager.sessionState.value)
            assertNull(field("aasdkSession").get(manager))
            assertSame("IDLE must not discard unacknowledged native retirement", native,
                field("retiringAasdkSession").get(manager))
        } finally {
            parent.cancel()
            (field("scope").get(manager) as CoroutineScope).cancel()
        }
    }

    @Test fun explicitStopRetriesTheRetainedExactOwnerBeforeAcknowledgingRetirement() = runBlocking {
        val parent = CoroutineScope(SupervisorJob())
        val manager = SessionManager(parent)
        val native = mockk<AasdkSession>(relaxed = true)
        var calls = 0
        every { native.stop() } answers {
            calls++
            if (calls == 1) throw IllegalStateException("injected first stop failure")
        }
        field("aasdkSession").set(manager, native)
        try {
            assertTrue(runCatching { manager.stop() }.isFailure)
            manager.stop()
            assertEquals("A second Stop must not acknowledge an empty active slot", 2, calls)
            assertNull(field("aasdkSession").get(manager))
            assertNull(field("retiringAasdkSession").get(manager))
        } finally {
            parent.cancel()
            (field("scope").get(manager) as CoroutineScope).cancel()
        }
    }

    @Test fun failedRetirementRejectsAReplacementBeforeColdStartAllocatesComponents() = runBlocking {
        val parent = CoroutineScope(SupervisorJob())
        val manager = SessionManager(parent)
        val native = mockk<AasdkSession>(relaxed = true)
        every { native.stop() } throws IllegalStateException("injected stop failure")
        field("aasdkSession").set(manager, native)
        try {
            assertTrue(runCatching { manager.stop() }.isFailure)
            manager.start()
            assertNull("Failed retirement must reject start before decoder allocation", manager.videoDecoder)
            assertSame(native, field("retiringAasdkSession").get(manager))
            assertNull(field("aasdkSession").get(manager))
        } finally {
            parent.cancel()
            (field("scope").get(manager) as CoroutineScope).cancel()
        }
    }

    @Test fun lateNativeStartCannotAdoptDifferentOwnerWhileExactRetirementIsPending() = runBlocking {
        val parent = CoroutineScope(SupervisorJob())
        val manager = SessionManager(parent)
        val retiring = mockk<AasdkSession>(relaxed = true)
        val stale = mockk<AasdkSession>(relaxed = true)
        every { retiring.stop() } throws IllegalStateException("injected stop failure")
        field("aasdkSession").set(manager, retiring)
        val prepare = SessionManager::class.java.getDeclaredMethod("prepareNativeSessionStart", AasdkSession::class.java)
            .apply { isAccessible = true }
        try {
            assertTrue(runCatching { manager.stop() }.isFailure)
            assertEquals(false, prepare.invoke(manager, retiring))
            assertEquals("A different late native generation must not overwrite retained ownership", false,
                prepare.invoke(manager, stale))
            assertSame(retiring, field("retiringAasdkSession").get(manager))
            assertNull(field("aasdkSession").get(manager))
        } finally {
            parent.cancel()
            (field("scope").get(manager) as CoroutineScope).cancel()
        }
    }

    private fun field(name: String) = SessionManager::class.java.getDeclaredField(name).apply { isAccessible = true }
}
