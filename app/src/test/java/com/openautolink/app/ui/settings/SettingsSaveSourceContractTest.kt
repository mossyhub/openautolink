package com.openautolink.app.ui.settings

import java.io.File
import org.junit.Assert.*
import org.junit.Test

/** Lexical production-binding guards, not rendered UI or persistence execution proof. */
internal object SettingsSaveSourceContract {
    fun assertContract(navigation: String, viewModel: String) {
        val save = navigation.substringAfter("internal fun rememberProductionSettingsSaveAction(")
            .substringBefore("/** Production route:")
        assertTrue("Shared Save must await the complete transaction, even without a layout adapter",
            save.contains("settingsViewModel.commitSettings(commitClusterLayout)"))
        assertFalse("No ordinary-only bypass of secondary/legacy drafts", save.contains("commitCarPlaySettings()"))
        assertTrue(save.contains("applySettingsAndRestart("))
        val reconnect = "projectionViewModel.reconnectAfterSettingsSave(settingsViewModel::saveAndReconnect)"
        assertTrue(save.contains(reconnect))
        assertTrue(save.indexOf("settingsViewModel.commitSettings(commitClusterLayout)") < save.indexOf(reconnect))
        val transaction = viewModel.substringAfter("suspend fun commitSettings(").substringBefore("// Shared, memory-only next-session draft.")
        val boundaries = listOf(
            "if (!persistCarPlaySettings()) return false",
            "val secondarySaved = secondaryDraft.commit",
            "preferences.updateSecondaryDisplayPreferences(revision, edits)",
            "if (!secondarySaved)",
            "val saved = clusterDraft.commitPreferences",
            "if (!saved)",
            "if (commitLayoutDraft == null) return true",
            "val layoutSaved = try { commitLayoutDraft() }",
            "if (!layoutSaved)",
            "layoutSaved\n",
        )
        var previous = -1
        for (boundary in boundaries) {
            val index = transaction.indexOf(boundary)
            assertTrue("Missing or reordered persistence/failure boundary: $boundary", index > previous)
            previous = index
        }
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
            navigation to viewModel.replace("if (commitLayoutDraft == null) return true", ""),
            navigation to viewModel.replace("if (!persistCarPlaySettings()) return false", "if (commitLayoutDraft == null) return true\nif (!persistCarPlaySettings()) return false"),
            navigation to viewModel.replace("val layoutSaved = try { commitLayoutDraft() }", "val layoutSaved = try { true }"),
            navigation to viewModel.replace("if (!layoutSaved)", "if (false)"),
        )
        for ((nav, vm) in mutations) {
            var rejected = false
            try { SettingsSaveSourceContract.assertContract(nav, vm) } catch (_: AssertionError) { rejected = true }
            assertTrue("Unsafe Save source mutation accepted", rejected)
        }
    }
}
