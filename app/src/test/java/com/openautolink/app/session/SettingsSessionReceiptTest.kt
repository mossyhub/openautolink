package com.openautolink.app.session

import com.openautolink.app.transport.aasdk.AasdkSession
import io.mockk.mockk
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

/** Real session ownership boundary; native work is not opened. */
class SettingsSessionReceiptTest {
    @Test fun replacementSettingsOwnerAdvancesReceiptButReadoptionDoesNot() {
        val parent = CoroutineScope(SupervisorJob())
        val manager = SessionManager(parent)
        val adopt = SessionManager::class.java.getDeclaredMethod("adoptSessionOwnership", AasdkSession::class.java).apply { isAccessible = true }
        val a = mockk<AasdkSession>(relaxed = true)
        val b = mockk<AasdkSession>(relaxed = true)
        try {
            assertEquals(0L, manager.settingsSessionEpoch.value)
            adopt.invoke(manager, a)
            val first = manager.settingsSessionEpoch.value
            assertTrue(first > 0L)
            adopt.invoke(manager, a)
            assertEquals(first, manager.settingsSessionEpoch.value)
            adopt.invoke(manager, b)
            assertTrue(manager.settingsSessionEpoch.value > first)
        } finally {
            parent.cancel()
            (SessionManager::class.java.getDeclaredField("scope").apply { isAccessible = true }.get(manager) as CoroutineScope).cancel()
        }
    }
}
