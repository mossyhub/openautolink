package com.openautolink.app.carplay

import android.content.Intent
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test
import io.mockk.mockk

class UsbCarPlayVpnConsentTest {
    @Test fun explicitGrantCompletesOnlyExactPendingGeneration() {
        val executor = Executors.newSingleThreadExecutor()
        try {
            val result = executor.submit<Boolean> { UsbCarPlayVpnConsent.await(mockk<Intent>()) { false } }
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
            while (UsbCarPlayVpnConsent.request.value == null && System.nanoTime() < deadline) Thread.sleep(10)
            val request = requireNotNull(UsbCarPlayVpnConsent.request.value)
            UsbCarPlayVpnConsent.complete(request.generation - 1, true)
            assertFalse(result.isDone)
            UsbCarPlayVpnConsent.complete(request.generation, true)
            assertTrue(result.get(3, TimeUnit.SECONDS))
            assertNull(UsbCarPlayVpnConsent.request.value)
        } finally { executor.shutdownNow() }
    }

    @Test fun cancellationDoesNotApproveConsent() {
        assertFalse(UsbCarPlayVpnConsent.await(mockk<Intent>()) { true })
        assertNull(UsbCarPlayVpnConsent.request.value)
    }

    @Test fun denialDoesNotApproveConsent() {
        val executor = Executors.newSingleThreadExecutor()
        try {
            val result = executor.submit<Boolean> { UsbCarPlayVpnConsent.await(mockk<Intent>()) { false } }
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
            while (UsbCarPlayVpnConsent.request.value == null && System.nanoTime() < deadline) Thread.sleep(10)
            val request = requireNotNull(UsbCarPlayVpnConsent.request.value)
            UsbCarPlayVpnConsent.complete(request.generation, false)
            assertFalse(result.get(3, TimeUnit.SECONDS))
        } finally { executor.shutdownNow() }
    }
}
