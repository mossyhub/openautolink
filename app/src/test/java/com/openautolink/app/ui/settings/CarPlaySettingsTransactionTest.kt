package com.openautolink.app.ui.settings

import com.openautolink.app.data.CarPlaySettings
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.launch
import org.junit.Assert.*
import org.junit.Test

class CarPlaySettingsTransactionTest {
    @Test fun everyCanonicalResolutionAndFrameRateCanBeSavedFromVideoOrDisplay() = runTest {
        for (resolution in com.openautolink.carplaylab.media.CarPlayResolution.entries) {
            for (fps in com.openautolink.carplaylab.media.CarPlayDisplayPreferences.FRAME_RATES) {
                val saved = CarPlaySettings(enabled = true, resolution = resolution.id, fps = fps)
                val draft = CarPlaySettingsDraft()
                draft.update { it.copy(widthPhysicalMm = 300) }
                var stored = saved
                assertTrue("${resolution.id}/$fps rejected on Display save", draft.commit { merge, password ->
                    assertNull(password)
                    val next = merge(stored)
                    assertNull("${resolution.id}/$fps", carPlayEditError(next))
                    stored = next
                })
                assertEquals(fps, stored.fps)
                assertEquals(resolution.id, stored.resolution)
                assertEquals(300, stored.widthPhysicalMm)
            }
        }
    }

    @Test fun displayWidthMustRoundTripWithoutSilentReset() {
        for (width in com.openautolink.carplaylab.media.CarPlayDisplayPreferences.PHYSICAL_WIDTHS_MM) {
            val settings = CarPlaySettings(widthPhysicalMm = width)
            assertNull(carPlayEditError(settings))
            assertEquals(settings, CarPlaySettings.decode(CarPlaySettings.encode(settings)))
        }
        for (width in listOf(99, 2001)) {
            assertNotNull("Noncanonical width $width would reset on read", carPlayEditError(CarPlaySettings(widthPhysicalMm = width)))
        }
    }

    @Test fun unchangedCarPlayDoesNotRewriteOrValidateSavedProtocolOnAndroidAutoApply() = runTest {
        assertTrue(CarPlaySettingsDraft().commit { _, _ -> fail("Unchanged record was rewritten") })
    }

    @Test fun editsArrivingDuringCommitRemainPendingForNextApply() = runTest {
        val draft = CarPlaySettingsDraft()
        draft.update { it.copy(ssid = "first") }
        var saved = CarPlaySettings()
        assertTrue(draft.commit { merge, _ ->
            draft.update { it.copy(widthPhysicalMm = 350) }
            saved = merge(saved)
        })
        assertEquals(200, saved.widthPhysicalMm)
        assertEquals(350, draft.preview(saved).widthPhysicalMm)
    }

    @Test fun pendingPasswordIsMemoryOnlyPreservesNullAndExplicitClear() = runTest {
        val draft = CarPlaySettingsDraft()
        var saved = "existing"
        assertTrue(draft.commit { _, password -> if (password != null) saved = password })
        assertEquals("existing", saved)
        draft.setPassword("replacement")
        assertFalse(draft.commit { _, _ -> throw java.io.IOException() })
        assertEquals("replacement", draft.pendingPassword())
        assertTrue(draft.commit { _, password -> saved = requireNotNull(password) })
        assertEquals("replacement", saved)
        assertNull(draft.pendingPassword())
        draft.setPassword("")
        assertTrue(draft.commit { _, password -> saved = requireNotNull(password) })
        assertEquals("", saved)
    }

    @Test fun restartAndCloseAwaitCommitAndAreSkippedOnFailure() = runTest {
        val events = mutableListOf<String>()
        val gate = kotlinx.coroutines.CompletableDeferred<Boolean>()
        val job = launch {
            applySettingsAndRestart({ events += "saving"; gate.await() },
                { events += "restart" }, { events += "close" })
        }
        kotlinx.coroutines.yield()
        assertEquals(listOf("saving"), events)
        gate.complete(true)
        job.join()
        assertEquals(listOf("saving", "restart", "close"), events)
        events.clear()
        applySettingsAndRestart({ false }, { events += "restart" }, { events += "close" })
        assertTrue(events.isEmpty())
    }

    @Test fun validationRejectsMalformedEditsButAllowsIncompleteSetupAndDisabledGate() {
        assertNull(carPlayEditError(CarPlaySettings()))
        assertNull(carPlayEditError(CarPlaySettings(enabled = true)))
        assertNotNull(carPlayEditError(CarPlaySettings(peerAddress = "not-an-ip")))
        assertNotNull(carPlayEditError(CarPlaySettings(phoneAddress = "placeholder")))
        assertNull(carPlayEditError(CarPlaySettings(fps = 25)))
        assertNotNull(carPlayEditError(CarPlaySettings(fps = 0)))
        assertNotNull(carPlayEditError(CarPlaySettings(fps = 61)))
        assertNotNull(carPlayEditError(CarPlaySettings(widthPhysicalMm = 0)))
    }

    @Test fun failedCommitRetainsDraftAndNeverAllowsRestart() = runTest {
        val draft = CarPlaySettingsDraft()
        draft.update { it.copy(ssid = "retry-me") }
        var restarted = false
        var closed = false
        val saved = draft.commit { _, _ -> throw java.io.IOException("private details") }
        if (saved) { restarted = true; closed = true }
        assertFalse(saved)
        assertFalse(restarted)
        assertFalse(closed)
        assertEquals("retry-me", draft.preview(CarPlaySettings()).ssid)
    }

    @Test fun cancellationRetainsDraftAndPropagates() = runTest {
        val draft = CarPlaySettingsDraft()
        draft.update { it.copy(ssid = "retry-me") }
        try {
            draft.commit { _, _ -> throw kotlinx.coroutines.CancellationException() }
            fail("Cancellation swallowed")
        } catch (_: kotlinx.coroutines.CancellationException) { }
        assertEquals("retry-me", draft.preview(CarPlaySettings()).ssid)
    }

    @Test fun editsAcrossTabsMergeIntoLatestRecordOnlyAtGlobalApply() = runTest {
        val draft = CarPlaySettingsDraft()
        val original = CarPlaySettings(ssid = "original")
        draft.update { it.copy(ssid = "edited") }
        draft.update { it.copy(resolution = "1080p", fps = 60) }
        draft.update { it.copy(widthPhysicalMm = 300) }
        draft.update { it.copy(autoScale = false) }
        val latest = original.copy(receiverAddress = "12:34:56:78:90:AB")
        assertEquals("original", original.ssid)
        assertEquals("edited", draft.preview(latest).ssid)
        var stored = latest
        assertTrue(draft.commit { merge, password ->
            assertNull(password)
            stored = merge(stored)
        })
        assertEquals("edited", stored.ssid)
        assertEquals("1080p", stored.resolution)
        assertEquals(60, stored.fps)
        assertEquals(300, stored.widthPhysicalMm)
        assertFalse(stored.autoScale)
        assertEquals(latest.receiverAddress, stored.receiverAddress)
        assertEquals(stored, draft.preview(stored))
    }
}
