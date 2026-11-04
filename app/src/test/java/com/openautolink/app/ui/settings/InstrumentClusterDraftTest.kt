package com.openautolink.app.ui.settings

import com.openautolink.app.cluster.ClusterDisplayMode.*
import com.openautolink.app.cluster.ClusterOutputSelection
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class InstrumentClusterDraftTest {
    @Test fun masterOffOnRestoresMapChoiceWithoutWritingUntilCommit() = runTest {
        val draft = ClusterSettingsDraft()
        var saved = ClusterOutputSelection()
        draft.chooseDisplay(MAP_VIDEO)
        draft.setEnabled(false)
        assertEquals(OFF, draft.preview(saved).mode)
        draft.setEnabled(true)
        assertEquals(MAP_VIDEO, draft.preview(saved).mode)
        assertEquals(NATIVE_ICONS, saved.mode)
        assertTrue(draft.commit { merge -> saved = merge(saved) })
        assertEquals(MAP_VIDEO, saved.mode)
        assertFalse(saved.nativeIconsWithMap)
    }

    @Test fun failureRetainsAllEditsAndNewerEditsDuringCommitSurvive() = runTest {
        val draft = ClusterSettingsDraft()
        draft.chooseDisplay(MAP_VIDEO)
        draft.update { it.copy(carPlay = false) }
        assertFalse(draft.commit { throw java.io.IOException() })
        assertFalse(draft.preview(ClusterOutputSelection()).carPlay)
        var saved = ClusterOutputSelection()
        assertTrue(draft.commit { merge ->
            draft.update { it.copy(androidAuto = false) }
            saved = merge(saved)
        })
        assertTrue(saved.androidAuto)
        assertFalse(draft.preview(saved).androidAuto)
        assertFalse(saved.carPlay)
    }

    @Test fun freshOffMigrationRestoresNativeAndPreservesSavedProtocolFlags() {
        val draft = ClusterSettingsDraft()
        draft.observe(ClusterOutputSelection(mode = OFF, androidAuto = false))
        draft.setEnabled(true)
        val restored = draft.preview(ClusterOutputSelection(mode = OFF, androidAuto = false))
        assertEquals(NATIVE_ICONS, restored.mode)
        assertFalse(restored.androidAuto)
    }

    @Test fun turningOffSavedMapThenOnKeepsMapAndDoesNotReenableDisabledProtocol() {
        val saved = ClusterOutputSelection(mode = MAP_VIDEO, androidAuto = false)
        val draft = ClusterSettingsDraft()
        draft.observe(saved)
        draft.setEnabled(false)
        draft.setEnabled(true)
        assertEquals(saved, draft.preview(saved))
    }
}
