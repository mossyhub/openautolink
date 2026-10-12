package com.openautolink.app.ui.settings

import java.io.File
import org.junit.Assert.*
import org.junit.Test

/** Wiring/privacy guards, not a rendered AAOS picker receipt. */
class CarPlayCredentialImportUiTest {
    private fun source(name: String): String {
        val root = generateSequence(File(requireNotNull(System.getProperty("user.dir")))) { it.parentFile }
            .first { File(it, "app/src/main").isDirectory }
        return File(root, "app/src/main/java/com/openautolink/app/ui/settings/$name.kt").readText()
    }
    @Test fun explicitTwoDocumentImportDoesNotUseGlobalApplyOrExposeCredentialValues() {
        val ui = source("CarPlaySettings")
        assertTrue(ui.contains("ActivityResultContracts.OpenDocument()"))
        assertTrue(ui.contains("Import authentication identity"))
        assertTrue(ui.contains("verticalScroll(rememberScrollState())"))
        assertTrue(ui.contains("Import selected pair"))
        assertTrue(ui.contains("viewModel.importCarPlayCredentials("))
        assertFalse(ui.contains("fresh provisioning is unavailable"))
        assertFalse(ui.contains("trusted installer to provision"))
        assertFalse(ui.contains("rememberSaveable"))
        assertFalse(ui.contains("commitCarPlaySettings"))
        val vm = source("SettingsViewModel")
        assertTrue(vm.contains("contentResolver"))
        assertTrue(vm.contains("openInputStream"))
        assertTrue(vm.contains("noBackupFilesDir"))
        assertTrue(vm.contains("CarPlayCredentialImporter"))
        val importer = source("CarPlayCredentialImporter")
        assertFalse(importer.contains("assets"))
        assertFalse(importer.contains("Log."))
    }
}
