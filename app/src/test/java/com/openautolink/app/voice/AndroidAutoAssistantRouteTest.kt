package com.openautolink.app.voice

import com.openautolink.app.session.SessionManager
import com.openautolink.app.transport.aasdk.AasdkSession
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidAutoAssistantRouteTest {
    private val scopes = mutableListOf<CoroutineScope>()

    @After fun cleanup() { scopes.forEach(CoroutineScope::cancel) }

    @Test fun liveAndroidAutoSessionReceivesSearchDownAndUp() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined).also(scopes::add)
        val manager = SessionManager(scope)
        val session = mockk<AasdkSession>(relaxed = true)
        every { session.currentNativeSessionGeneration() } returns 41L
        every { session.sendKeyPress(84, 41L) } returns true
        SessionManager::class.java.getDeclaredMethod("adoptSessionOwnership", AasdkSession::class.java)
            .apply { isAccessible = true }.invoke(manager, session)

        assertTrue(ProjectionVoiceAssistantRouter.request("test"))
        verify(timeout = 3_000, exactly = 1) {
            session.sendKeyPress(84, 41L)
        }
    }
}
