package com.openautolink.app.ui.settings

import com.openautolink.app.data.ClusterOutputPreferences
import com.openautolink.app.secondary.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@org.junit.runner.RunWith(org.robolectric.RobolectricTestRunner::class)
@org.robolectric.annotation.Config(sdk = [32, 34], application = android.app.Application::class)
@org.robolectric.annotation.ConscryptMode(org.robolectric.annotation.ConscryptMode.Mode.OFF)
class SecondaryDisplaysDraftTest {
    private val a = SecondaryOutputId("a")
    private val b = SecondaryOutputId("b")
    private fun saved() = SecondaryPreferencesSnapshot(7, emptyMap(), ClusterOutputPreferences(com.openautolink.app.cluster.ClusterOutputSelection(com.openautolink.app.cluster.ClusterDisplayMode.OFF), null))

    @Test fun independentEditsAreMemoryOnlyUntilAwaitedRevisionedCommit() = runTest {
        val draft = SecondaryDisplaysDraft()
        val original = saved()
        draft.update(original, a) { it.copy(enabled = true, androidAuto = true, aaRole = AaSecondaryRole.CLUSTER) }
        draft.update(original, b) { it.copy(enabled = true, androidAuto = true, aaRole = AaSecondaryRole.AUXILIARY) }
        assertTrue(original.outputs.isEmpty())
        var writes = 0
        assertTrue(draft.commit { revision, edits ->
            writes++
            assertEquals(7L, revision)
            assertEquals(AaSecondaryRole.CLUSTER, edits[a]?.aaRole)
            assertEquals(AaSecondaryRole.AUXILIARY, edits[b]?.aaRole)
        })
        assertTrue(draft.commit { _, _ -> fail("Already acknowledged") })
        assertEquals(1, writes)
    }

    @Test fun failedOrConcurrentSaveRetainsDraftOriginalRevisionAndNeverRestarts() = runTest {
        val draft = SecondaryDisplaysDraft()
        draft.update(saved(), a) { it.copy(enabled = true) }
        assertTrue(draft.preview(saved().copy(revision = 8)).outputs.getValue(a).enabled)
        var restarts = 0
        applySettingsAndRestart({ draft.commit { revision, _ ->
            assertEquals(7L, revision)
            throw java.util.ConcurrentModificationException()
        } }, { restarts++ }, { fail("Failed save closed settings") })
        assertEquals(0, restarts)
        assertTrue(draft.preview(saved()).outputs.getValue(a).enabled)
        assertFalse(draft.commit { _, _ -> throw java.io.IOException() })
        assertTrue(draft.preview(saved()).outputs.getValue(a).enabled)
    }

    @Test fun carPlayLimitNeverRewritesConflictingIntentAndMainIsNotPartOfSelection() {
        val selected = SecondaryOutputPreference(true, AaSecondaryRole.CLUSTER, true, true)
        val unused = SecondaryOutputPreference(false, AaSecondaryRole.AUXILIARY, false, false)
        val original = saved().copy(outputs = mapOf(a to selected, b to unused))
        val draft = SecondaryDisplaysDraft()
        draft.update(original, b) { it.copy(enabled = true) }
        assertEquals(selected, draft.preview(original).outputs[a])
        assertEquals(unused.copy(enabled = true), draft.preview(original).outputs[b])
        assertTrue(selected.carPlay)
        assertTrue(selected.androidAuto)
    }
}
