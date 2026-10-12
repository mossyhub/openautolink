package com.openautolink.app.ui.settings

import com.openautolink.app.data.CarPlaySettings
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

/** Complements the source wiring assertion: editor edits only enter this shared draft on Done. */
class CarPlayCalibrationDraftTest {
    @Test fun cancelDoesNotEraseExistingCrossTabDraftOrApplyLocalDragEdits() = runTest {
        val original = CarPlaySettings(safeTop = 100, safeRight = 400)
        val draft = CarPlaySettingsDraft()
        draft.update { it.copy(fps = 59, widthPhysicalMm = 225) }
        // SafeAreaEditorScreen keeps drag values in remember; Cancel only closes its parent state.
        val localDragRight = 1500
        assertNotEquals(original.safeRight, localDragRight)
        val reopened = draft.preview(original)
        assertEquals(100, reopened.safeTop)
        assertEquals(400, reopened.safeRight)
        assertEquals(59, reopened.fps)
        assertEquals(225, reopened.widthPhysicalMm)
    }

    @Test fun doneOnlyStagesSafeEdgesAndGlobalSaveMergesIntoLatestBeforeSingleRestart() = runTest {
        val draft = CarPlaySettingsDraft()
        var stored = CarPlaySettings(enabled = false, resolution = "1440p", safeRight = 400)
        draft.update { it.copy(fps = 59) }
        draft.update { it.copy(safeTop = 150, safeBottom = 250, safeLeft = 350, safeRight = 1250) }
        assertEquals(400, stored.safeRight)
        assertEquals(1250, draft.preview(stored).safeRight)
        // A different tab/producer writes between Done and the global Save.
        stored = stored.copy(ssid = "new-network", widthPhysicalMm = 225)
        val events = mutableListOf<String>()
        applySettingsAndRestart(
            { draft.commit { merge, password ->
                assertNull(password)
                stored = merge(stored)
                events += "persist"
            } },
            { events += "restart" }, { events += "close" })
        assertEquals(listOf("persist", "restart", "close"), events)
        assertEquals(1250, stored.safeRight)
        assertEquals(150, stored.safeTop)
        assertEquals(250, stored.safeBottom)
        assertEquals(350, stored.safeLeft)
        assertEquals(59, stored.fps)
        assertEquals("new-network", stored.ssid)
        assertEquals(225, stored.widthPhysicalMm)
        assertFalse(stored.enabled)
    }

    @Test fun globalSaveFailureKeepsDoneCalibrationPendingWithoutRestart() = runTest {
        val draft = CarPlaySettingsDraft()
        draft.update { it.copy(safeRight = 1250) }
        val events = mutableListOf<String>()
        applySettingsAndRestart({ draft.commit { _, _ -> throw java.io.IOException() } },
            { events += "restart" }, { events += "close" })
        assertTrue(events.isEmpty())
        assertEquals(1250, draft.preview(CarPlaySettings()).safeRight)
    }
}
