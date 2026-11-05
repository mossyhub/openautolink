package com.openautolink.app.ui.settings

import java.io.File
import org.junit.Assert.*
import org.junit.Test

/** Lexical production-binding guards, not rendered UI or persistence execution proof. */
internal object SettingsSaveSourceContract {
    private fun bounded(source: String, start: String, end: String): String {
        val first = source.indexOf(start)
        assertTrue("Missing start boundary: $start", first >= 0)
        val last = source.indexOf(end, first + start.length)
        assertTrue("Missing end boundary: $end", last > first)
        return source.substring(first, last)
    }
    fun assertContract(navigation: String, viewModel: String) {
        val save = bounded(navigation, "internal fun rememberProductionSettingsSaveAction(",
            "/** Exact callback used by the rendered projection Reconnect menu")
        assertTrue("Shared Save must await the complete transaction, even without a layout adapter",
            save.contains("settingsViewModel.commitSettings(commitClusterLayout)"))
        assertFalse("No ordinary-only bypass of secondary/legacy drafts", save.contains("commitCarPlaySettings()"))
        assertTrue(save.contains("applySettingsAndRestart("))
        val reconnect = "projectionViewModel.reconnectAfterSettingsSave(settingsViewModel::saveAndReconnect)"
        assertTrue(save.contains(reconnect))
        assertTrue(save.indexOf("settingsViewModel.commitSettings(commitClusterLayout)") < save.indexOf(reconnect))
        val transaction = bounded(viewModel, "suspend fun commitSettings(", "// Shared, memory-only next-session draft.")
        val boundaries = listOf(
            "if (!stageSecondaryPresentationForSave()) return@withContext false",
            "_clusterSaving.value = true",
            "val carPlaySaved = persistCarPlaySettings()",
            "if (!carPlaySaved) return@withContext false",
            "val secondarySaved = secondaryDraft.commit",
            "preferences.updateSecondaryDisplayPreferences(revision, edits)",
            "if (!secondarySaved)",
            "val saved = clusterDraft.commitPreferences",
            "if (!saved)",
            "if (commitLayoutDraft == null)",
            "return@withContext true",
            "val layoutSaved = try { commitLayoutDraft() }",
            "if (!layoutSaved)",
            "layoutSaved\n",
            "finally { _clusterSaving.value = false }",
        )
        var previous = -1
        for (boundary in boundaries) {
            val index = transaction.indexOf(boundary)
            assertTrue("Missing or reordered persistence/failure boundary: $boundary", index > previous)
            previous = index
        }
        for ((failure, next) in listOf("if (!secondarySaved)" to "secondaryRevision.value++",
            "if (!saved)" to "clusterRevision.value++")) {
            val begin = transaction.indexOf(failure)
            val end = transaction.indexOf(next, begin)
            assertTrue("Missing local failure branch", begin >= 0 && end > begin)
            assertTrue(transaction.substring(begin, end).contains("return@withContext false"))
        }
        assertEquals("Only the post-store absent-adapter branch may return early success", 1,
            Regex("return@withContext true").findAll(transaction).count())
        assertTrue(transaction.contains("receipt.event(stage, \"cancelled\")"))
        assertTrue(transaction.contains("throw cancelled"))
        assertTrue(transaction.contains("catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }"))
        assertTrue(transaction.contains("catch (_: Exception) { false }"))
        assertTrue(navigation.contains("onSaveAndConnect = rememberProductionSettingsSaveAction(settingsViewModel, projectionViewModel, clusterBindings?.commitLayoutDraft, onBack)"))
        assertTrue(navigation.contains("onSaveAndConnect = rememberProductionSettingsSaveAction(settingsViewModel, projectionViewModel, clusterBindings?.commitLayoutDraft) {"))
    }
}

class SettingsSaveSourceContractTest {
    private fun source(path: String) = File("src/main/java/com/openautolink/app/$path").readText()

    @Test fun sharedTransactionContractRejectsSkippedPersistenceEarlyNullSuccessAndUnawaitedLayout() {
        val navigation = source("ui/navigation/AppNavHost.kt")
        val viewModel = source("ui/settings/SettingsViewModel.kt")
        SettingsSaveSourceContract.assertContract(navigation, viewModel)
        val mutations = listOf(
            navigation.replace("settingsViewModel.commitSettings(commitClusterLayout)", "settingsViewModel.commitCarPlaySettings()") to viewModel,
            navigation to viewModel.replace("preferences.updateSecondaryDisplayPreferences(revision, edits)", "true"),
            navigation to viewModel.replace("if (!secondarySaved)", "if (false)"),
            navigation to viewModel.replace("if (commitLayoutDraft == null)", "if (false)"),
            navigation to viewModel.replace("val carPlaySaved = persistCarPlaySettings()", "if (commitLayoutDraft == null) return@withContext true\nval carPlaySaved = persistCarPlaySettings()"),
            navigation to viewModel.replace("val carPlaySaved = persistCarPlaySettings()", "val carPlaySaved = true"),
            navigation to viewModel.replace("if (!carPlaySaved) return@withContext false", ""),
            navigation to viewModel.replace("if (!saved)", "if (false)"),
            navigation to viewModel.replace("finally { _clusterSaving.value = false }", "finally {}"),
            navigation to viewModel.replace("throw cancelled", "false"),
            navigation to viewModel.replace("val layoutSaved = try { commitLayoutDraft() }", "val layoutSaved = try { true }"),
            navigation to viewModel.replace("if (!layoutSaved)", "if (false)"),
        )
        for ((nav, vm) in mutations) {
            assertTrue("Mutation must actually alter source", nav != navigation || vm != viewModel)
            var rejected = false
            try { SettingsSaveSourceContract.assertContract(nav, vm) } catch (_: AssertionError) { rejected = true }
            assertTrue("Unsafe Save source mutation accepted", rejected)
        }
    }
}
