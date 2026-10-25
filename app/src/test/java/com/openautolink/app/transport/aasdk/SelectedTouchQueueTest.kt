package com.openautolink.app.transport.aasdk

import com.openautolink.app.session.SessionManager
import com.openautolink.app.transport.ControlMessage
import io.mockk.mockk
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn

/** Captured before the UI coroutine; stale sends must return without entering JNI. */
class SelectedTouchQueueTest {
    private fun callback(s: AasdkSession) = s.javaClass.getDeclaredMethod("telemetryCallback")
        .apply { isAccessible = true }.invoke(s) as AasdkSessionCallback
    private fun select(s: AasdkSession) { callback(s).onVideoGeometrySelected(1280, 720) }
    private fun adopt(m: SessionManager, s: AasdkSession) {
        m.javaClass.getDeclaredMethod("adoptSessionOwnership", AasdkSession::class.java)
            .apply { isAccessible = true }.invoke(m, s)
    }
    private fun capture(m: SessionManager): Any {
        val method = m.javaClass.methods.firstOrNull { it.name == "captureTouchTarget" }
        assertNotNull("Touch must capture exact owner and selection before coroutine enqueue", method)
        return method!!.invoke(m)!!
    }
    private suspend fun send(m: SessionManager, target: Any) {
        val method = m.javaClass.methods.first { it.name == "sendSelectedTouch" }
        suspendCoroutineUninterceptedOrReturn<Any?> { continuation ->
            method.invoke(m, ControlMessage.Touch(action = 0, x = 100f, y = 200f, pointerId = 0, pointers = null), target, continuation)
        }
    }
    @Test fun queuedTouchCannotAdoptSuccessorSession() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val manager = SessionManager(scope)
            val a = AasdkSession(scope, mockk(relaxed = true)); adopt(manager, a); select(a)
            val target = capture(manager)
            val b = AasdkSession(scope, mockk(relaxed = true)); adopt(manager, b); select(b)
            send(manager, target) // A stale call into native would throw on this host JVM.
            assertNotEquals(target, capture(manager))
        } finally { scope.cancel() }
    }
    @Test fun queuedTouchCannotAdoptAnotherSelectionWithIdenticalDimensions() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val manager = SessionManager(scope)
            val a = AasdkSession(scope, mockk(relaxed = true)); adopt(manager, a); select(a)
            val target = capture(manager)
            select(a)
            assertNotEquals("Equal sizes are not selection identity", target, capture(manager))
            send(manager, target)
        } finally { scope.cancel() }
    }
    @Test fun queuedTouchCannotSurviveNativeRetirement() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val manager = SessionManager(scope)
            val a = AasdkSession(scope, mockk(relaxed = true)); adopt(manager, a); select(a)
            val target = capture(manager)
            callback(a).onSessionStopped("test")
            send(manager, target)
        } finally { scope.cancel() }
    }
}
