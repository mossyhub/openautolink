package com.openautolink.app.transport.aasdk

import com.openautolink.app.session.SessionManager
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

/** Real native callback adapter and manager; no coroutine delivery is allowed. */
class SelectedVideoGeometryTest {
    private fun callback(session: AasdkSession) = session.javaClass
        .getDeclaredMethod("telemetryCallback").apply { isAccessible = true }
        .invoke(session) as AasdkSessionCallback

    private fun selected(callback: AasdkSessionCallback, w: Int, h: Int) {
        val method = AasdkSessionCallback::class.java.methods.firstOrNull {
            it.name == "onVideoGeometrySelected" && it.parameterCount == 2
        }
        assertNotNull("Native VideoStart must publish selected logical geometry", method)
        method!!.invoke(callback, w, h)
    }

    private fun geometry(manager: SessionManager): Pair<Int, Int>? {
        val method = manager.javaClass.methods.firstOrNull { it.name == "getActiveVideoGeometry" }
        assertNotNull("Input needs an atomic selected geometry snapshot", method)
        @Suppress("UNCHECKED_CAST")
        return method!!.invoke(manager) as Pair<Int, Int>?
    }

    private fun adopt(manager: SessionManager, session: AasdkSession) {
        manager.javaClass.getDeclaredMethod("adoptSessionOwnership", AasdkSession::class.java)
            .apply { isAccessible = true }.invoke(manager, session)
    }

    @Test fun lowerTierIsReadySynchronouslyBeforeAnyCoroutineRuns() {
        val queued = mutableListOf<Runnable>()
        val dispatcher = object : CoroutineDispatcher() {
            override fun dispatch(context: kotlin.coroutines.CoroutineContext, block: Runnable) { queued.add(block) }
        }
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        try {
            val session = AasdkSession(scope, io.mockk.mockk(relaxed = true))
            val manager = SessionManager(scope)
            adopt(manager, session)
            selected(callback(session), 1280, 720)
            assertEquals(1280 to 720, geometry(manager))
        } finally { scope.cancel() }
    }

    @Test fun oldNativeCallbackCannotRestoreGeometryAfterResetOrReplacement() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val session = AasdkSession(scope, io.mockk.mockk(relaxed = true))
            val manager = SessionManager(scope)
            adopt(manager, session)
            val retired = callback(session)
            selected(retired, 2560, 1440)
            assertEquals(2560 to 1440, geometry(manager))
            session.beginEvTelemetryGeneration()
            assertNull(geometry(manager))
            selected(retired, 2560, 1440)
            assertNull(geometry(manager))
            selected(callback(session), 1280, 720)
            assertEquals(1280 to 720, geometry(manager))
            val replacement = AasdkSession(scope, io.mockk.mockk(relaxed = true))
            adopt(manager, replacement)
            selected(callback(session), 2560, 1440)
            assertNull(geometry(manager))
            selected(callback(replacement), 720, 1280)
            assertEquals(720 to 1280, geometry(manager))
            manager.javaClass.getDeclaredMethod("revokeSessionOwnershipLocked")
                .apply { isAccessible = true }.invoke(manager)
            assertNull(geometry(manager))
        } finally { scope.cancel() }
    }

    @Test fun stoppedNativeCallbackCannotRestoreGeometry() {
        val queued = mutableListOf<Runnable>()
        val dispatcher = object : CoroutineDispatcher() {
            override fun dispatch(context: kotlin.coroutines.CoroutineContext, block: Runnable) { queued.add(block) }
        }
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        try {
            val session = AasdkSession(scope, io.mockk.mockk(relaxed = true))
            val manager = SessionManager(scope)
            adopt(manager, session)
            val old = callback(session)
            selected(old, 1280, 720)
            old.onSessionStopped("test")
            assertNull(geometry(manager))
            selected(old, 2560, 1440)
            assertNull(geometry(manager))
        } finally { scope.cancel() }
    }

    @Test fun everyNativeCreateRotatesSelectionGeneration() {
        val root = generateSequence(java.io.File(System.getProperty("user.dir"))) { it.parentFile }
            .first { java.io.File(it, "settings.gradle.kts").exists() }
        val source = java.io.File(root, "app/src/main/java/com/openautolink/app/transport/aasdk/AasdkSession.kt").readText()
        val calls = Regex(Regex.escape("nativeSessionGeneration.set(AasdkNative.nativeCreateSession())")).findAll(source).toList()
        assertEquals(2, calls.size)
        calls.forEach { call ->
            assertTrue("Every native start must invalidate prior selected geometry", source.substring((call.range.first - 100).coerceAtLeast(0), call.range.first).contains("resetSelectedVideoGeometry()"))
        }
    }

    @Test fun invalidSelectionClosesInputUntilAnotherValidStart() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val session = AasdkSession(scope, io.mockk.mockk(relaxed = true))
            val manager = SessionManager(scope)
            adopt(manager, session)
            selected(callback(session), 1280, 720)
            selected(callback(session), 0, 0)
            assertNull(geometry(manager))
        } finally { scope.cancel() }
    }
}
