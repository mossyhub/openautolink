package com.openautolink.app.carplay

import org.junit.Assert.*
import org.junit.Test

class UsbPairingDiagnosticsTest {
    @Test fun finitePairingReceiptSurvivesProductionUploadArchive() {
        val folder = java.nio.file.Files.createTempDirectory(java.nio.file.Path.of(System.getProperty("java.io.tmpdir")), "pairing-export").toFile()
        try {
            val receipt = requireNotNull(CarPlayAudioDiagnostics.sanitize(9, "CarPlay USB stage=failed reason=PASSWORD_PROTECTED"))
            val log = java.io.File(folder, "oal_pairing.log").apply { writeText(receipt + "\n") }
            val plan = com.openautolink.app.diagnostics.LogUploadArchive.select(listOf(log), log.lastModified())
            com.openautolink.app.diagnostics.LogUploadArchive.create(plan, folder).use { archive ->
                java.util.zip.ZipFile(archive.file).use { zip ->
                    assertEquals(receipt + "\n", zip.getInputStream(zip.getEntry(log.name)).bufferedReader().readText())
                }
            }
        } finally { folder.deleteRecursively() }
    }
    @Test fun pairingStagesAndFiniteErrorsSurviveExportWithoutPrivateSuffixes() {
        val receipts = listOf(
            "CarPlay USB pairing-error code=InvalidPairRecord",
            "CarPlay USB pairing-error code=MissingPairRecord",
            "CarPlay USB pairing-error code=InvalidHostID",
            "CarPlay USB pairing stage=pair-requested",
            "CarPlay USB pairing stage=trust-pending",
            "CarPlay USB stage=failed reason=PASSWORD_PROTECTED",
            "CarPlay USB stage=failed reason=USER_DENIED",
            "CarPlay USB stage=failed reason=INVALID_RECORD",
            "CarPlay USB stage=failed reason=TRANSPORT_LOSS",
            "CarPlay USB stage=failed reason=TRUST_PENDING_TIMEOUT",
            "CarPlay USB stage=retry result=blocked",
            "CarPlay USB stage=usb-permission result=denied",
        )
        receipts.forEach {
            assertNotNull(it, CarPlayAudioDiagnostics.sanitize(9, it))
            assertNull(CarPlayAudioDiagnostics.sanitize(9, "$it HostID=secret"))
            assertNull(CarPlayAudioDiagnostics.sanitize(9, "$it\n"))
        }
        assertNull(CarPlayAudioDiagnostics.sanitize(9, "CarPlay USB pairing stage=private"))
        assertNull(CarPlayAudioDiagnostics.sanitize(9, "CarPlay USB stage=failed reason=PasswordProtected-secret"))
    }
}
