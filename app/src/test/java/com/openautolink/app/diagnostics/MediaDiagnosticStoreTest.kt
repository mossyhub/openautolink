package com.openautolink.app.diagnostics

import org.junit.Assert.*
import org.junit.Test
import org.junit.Rule
import org.junit.rules.TemporaryFolder

@org.junit.runner.RunWith(org.robolectric.RobolectricTestRunner::class)
@org.robolectric.annotation.Config(sdk = [32, 34], application = android.app.Application::class)
@org.robolectric.annotation.ConscryptMode(org.robolectric.annotation.ConscryptMode.Mode.OFF)
class MediaDiagnosticStoreTest {
    @get:Rule val temp = TemporaryFolder()
    @Test fun immutableRunsSurviveRollbackAndRetainOnlyThreeFiniteSummaries() {
        val dir = temp.newFolder()
        var now = 1_800_000_000_000L
        val store = MediaDiagnosticStore({ dir }, { now })
        val runs = (1..5).map { store.save(MediaOwnSnapshot(initialized = true), MediaCoverage())!! }
        assertEquals(3, dir.listFiles()!!.size)
        assertEquals(5, runs.distinct().size)
        now -= 100
        val next = MediaDiagnosticStore({ dir }, { now }).save(MediaOwnSnapshot(), MediaCoverage())!!
        assertTrue(next > runs.last())
        dir.listFiles()!!.forEach {
            assertTrue(MediaDiagnosticStore.runName.matches(it.name))
            assertTrue(it.length() <= MediaDiagnosticStore.MAX_BYTES)
            assertTrue(MediaDiagnosticStore.valid(it))
            assertFalse(it.readText().contains("title="))
        }
        now += MediaDiagnosticStore.TTL_MS + 1000
        store.prune()
        assertEquals(0, dir.listFiles()!!.size)
    }
    @Test fun storedRefreshUsesAssignedRunAndRestartPreservesInspectionCoverageWithoutNames() {
        val dir = temp.newFolder()
        val store = MediaDiagnosticStore({ dir })
        val run = store.save(MediaOwnSnapshot(), MediaCoverage(MediaCoverageStatus.PARTIAL, processed = 4, failed = 1, startWall = 1, capturedWall = 2))!!
        val text = dir.listFiles()!!.single().readText()
        assertTrue(text.contains("inspection_run=$run\n"))
        assertTrue(text.contains("inspection_status=PARTIAL"))
    }
    @Test fun uploadValidationUsesExactBoundedBytesAndSaveRejectsRevokedGeneration() {
        val dir = temp.newFolder(); val store = MediaDiagnosticStore({ dir })
        val first = store.save(MediaOwnSnapshot(), MediaCoverage())!!
        val source = dir.listFiles()!!.single(); val valid = source.readBytes()
        source.appendText("\n".repeat((32768 - source.length()).toInt()))
        assertTrue(MediaDiagnosticStore.valid(source))
        source.appendText("\n"); assertFalse(MediaDiagnosticStore.valid(source))
        source.writeBytes(valid)
        assertNull(store.save(MediaOwnSnapshot(), MediaCoverage(), accepted = { false }))
        assertEquals(first, MediaDiagnosticStore.sequence(dir.listFiles()!!.single().name))
        assertTrue(MediaDiagnosticStore.valid(source))
    }
    @Test fun storageFailureIsFiniteAndDoesNotThrow() {
        val file = temp.newFile()
        val store = MediaDiagnosticStore({ file })
        assertNull(store.save(MediaOwnSnapshot(), MediaCoverage()))
        assertEquals(MediaStoreStatus.UNAVAILABLE, store.status)
    }
    @Test fun outcomeIsBoundedQualifiedAndContainsNoFreeText() {
        val summary = MediaOutcomeSummary()
        val owner = Any()
        summary.admit(owner)
        summary.record(Any(), MediaOutcomeSummary.Outcome.CONTROL_RECEIVED)
        summary.record(owner, MediaOutcomeSummary.Outcome.CONTROL_RECEIVED)
        summary.retire(owner)
        summary.record(owner, MediaOutcomeSummary.Outcome.PLAYBACK_SUBMITTED)
        val state = summary.snapshot()
        assertEquals(1L, state.controlReceived)
        assertEquals(0L, state.playbackSubmitted)
        assertEquals(2L, state.rejected)
        assertEquals(MediaOutcomeSummary.Outcome.CONTROL_RECEIVED, state.last)
        assertEquals(MediaTruth.UNKNOWN, state.beforeProcess)
    }
}
