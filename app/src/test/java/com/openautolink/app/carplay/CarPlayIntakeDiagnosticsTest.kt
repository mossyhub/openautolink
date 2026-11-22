package com.openautolink.app.carplay

import org.junit.Assert.*
import org.junit.Test

class CarPlayIntakeDiagnosticsTest {
    @Test fun preciseVehicleContractReceiptsSurviveSanitizerReporterAndRealArchive() {
        val receipts = listOf(
            "carplay-car-data-offer location=true range=true current=true maximum=false",
            "carplay-ev-offer stage=prepared connectors=0,2 dcW=150000 acW=11500 groups=3,4,11,15,21,30",
            "carplay-ev-wire send=1 status=3:180,11:180,15:0,4:-5,21:0,22:20000,23:60000,24:33333,25:0,30:540",
            "carplay-ev-wire send=1 model=26 id=12582922 energy=0,6000",
            "carplay-ev-wire send=1 model=27 id=12582921 consumption=0.00012,0.2",

            "carplay-car-data-request id=65530 bytes=32 result=width parameter=0 length=2 groups=0 ggaMs=0 rmcMs=0",
            "carplay-car-data-request id=41216 bytes=12 result=accepted parameter=0 length=0 groups=7 ggaMs=0 rmcMs=0",
            "carplay-car-data-source property=range reason=source_age status=0 active=true generation=true sourceAgeMs=60000 receiptAgeMs=1000",
            "carplay-car-data-bootstrap id=41216 result=deferred",
            "carplay-car-data-source property=reported_capacity reason=available status=0 active=true generation=true sourceAgeMs=60000 receiptAgeMs=0 changeMode=2 statusReadAgeMs=0",
            "carplay-ev-pilot readiness=ready enabled=true basis=reported frozen=true model=true",
            "carplay-ev-pilot readiness=charging_profile_required enabled=true basis=reported frozen=true model=false",
        )
        val exported = mutableListOf<String>()
        val reporter = CarPlayAudioDiagnosticReporter(1, {1_000_000_000L}, exported::add)
        receipts.forEach { line ->
            assertNotNull(line, CarPlayAudioDiagnostics.sanitize(1,line))
            reporter.record(line)
            for (suffix in listOf(" lat=123", " VIN=private", " key=private", "\nprivate", "\u0000"))
                assertNull(CarPlayAudioDiagnostics.sanitize(1,line+suffix))
        }
        assertEquals(receipts.size,exported.size)
        val badRequest = receipts.single { it.contains("result=width") }.replace("result=width","result=private")
        assertNull(CarPlayAudioDiagnostics.sanitize(1,badRequest))
        val folder = java.nio.file.Files.createTempDirectory(java.nio.file.Path.of(System.getProperty("java.io.tmpdir")),"vehicle-export").toFile()
        try {
            val contents=exported.joinToString("\n",postfix="\n")
            val file=java.io.File(folder,"oal_vehicle.log").apply {writeText(contents)}
            val plan=com.openautolink.app.diagnostics.LogUploadArchive.select(listOf(file),file.lastModified())
            com.openautolink.app.diagnostics.LogUploadArchive.create(plan,folder).use { archive ->
                java.util.zip.ZipFile(archive.file).use { zip ->
                    assertEquals(contents,zip.getInputStream(zip.getEntry(file.name)).bufferedReader().readText())
                }
            }
        } finally { folder.deleteRecursively() }
    }
    @Test fun boundedFullStringCarDataReceiptsReachExistingExportWithoutValues() {
        val line = "carplay-car-data id=65531 reason=transmitted count=1 fields=1 suppressed=0"
        assertNotNull("real intake must reach diagnostic export", CarPlayAudioDiagnostics.sanitize(1,line))
        assertNotNull(CarPlayAudioDiagnostics.sanitize(1,"carplay-appearance reason=transmitted count=1"))
        assertNotNull(CarPlayAudioDiagnostics.sanitize(1,"carplay-enrollment stage=commit accepted=true"))
        assertNotNull(CarPlayAudioDiagnostics.sanitize(1,"carplay-enrollment stage=authorized windowMs=120000 boundary=selected-bootstrap-request; first-client race remains possible"))
        assertNull(CarPlayAudioDiagnostics.sanitize(1,"carplay-enrollment stage=commit accepted=true controller=private"))
        for (bad in listOf(line + " lat=123", line + "\nprivate", line.replace("transmitted","raw"),
            line.replace("65531","99999"), line.replace("fields=1","fields=4096"), "x".repeat(500)))
            assertNull(CarPlayAudioDiagnostics.sanitize(1,bad))
    }
}
