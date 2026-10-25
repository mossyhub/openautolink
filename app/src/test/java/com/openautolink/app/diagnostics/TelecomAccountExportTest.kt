package com.openautolink.app.diagnostics

import android.Manifest
import com.openautolink.carplaylab.telecom.ManagedTelecomExperiment
import com.openautolink.carplaylab.telecom.TelecomAccountDiagnostics
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File
import java.util.zip.ZipFile

@RunWith(RobolectricTestRunner::class)
@org.robolectric.annotation.ConscryptMode(org.robolectric.annotation.ConscryptMode.Mode.OFF)
@Config(sdk = [32, 34], application = android.app.Application::class)
class TelecomAccountExportTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private val experiment get() = ManagedTelecomExperiment.get(app)
    private val directory get() = File(app.getExternalFilesDir(null), "openautolink/logs")
    private val file get() = File(directory, TelecomAccountDiagnostics.FILE_NAME)
    @Before fun reset() { com.openautolink.app.ui.testing.resetNativeTelecomTestProcess(app) }
    @After fun cleanup() { com.openautolink.app.ui.testing.resetNativeTelecomTestProcess(app) }
    private fun manualZipMatchesSource() {
        val bytes = file.readBytes()
        val text = bytes.toString(Charsets.UTF_8)
        assertFalse(text.contains(experiment.handle.id)); assertFalse(text.contains(app.packageName))
        assertFalse(text.contains("sensitive"))
        assertTrue(bytes.size <= TelecomAccountDiagnostics.MAX_BYTES)
        file.setLastModified(1) // bounded terminal receipt survives generic six-hour retention
        LogUploadArchive.create(LogUploadArchive.select(directory.listFiles()!!.toList(), System.currentTimeMillis()), app.cacheDir).use { archive ->
            ZipFile(archive.file).use { zip ->
                val entry = zip.getEntry(file.name)
                assertNotNull("manual archive omitted terminal receipt", entry)
                assertArrayEquals(bytes, zip.getInputStream(entry).readBytes())
            }
        }
    }
    private fun assertSanitizedOwnRead(outcome: TelecomAccountDiagnostics.Outcome) {
        val line = file.readLines().single { it.contains("stage=FIRST_OWN_READ ") }
        val generation = line.substringAfter("generation=").toLong()
        assertEquals(TelecomAccountDiagnostics.sanitize(TelecomAccountDiagnostics.Stage.FIRST_OWN_READ.ordinal, outcome.ordinal, generation), line)
    }
    @Test fun deniedAndReturnedOwnReadReceiptsSurviveManualZipWithoutFileLogging() {
        shadowOf(app).denyPermissions(Manifest.permission.READ_PHONE_NUMBERS)
        experiment.enable()
        assertTrue("account producer did not persist receipt", file.isFile)
        assertSanitizedOwnRead(TelecomAccountDiagnostics.Outcome.READ_PERMISSION_REQUIRED)
        manualZipMatchesSource()
        shadowOf(app).grantPermissions(Manifest.permission.READ_PHONE_NUMBERS)
        experiment.enable()
        assertTrue(file.readText().contains("stage=REGISTER outcome=RETURNED"))
        assertSanitizedOwnRead(TelecomAccountDiagnostics.Outcome.ACCOUNT_DISABLED)
        manualZipMatchesSource()
    }
    @Test fun inspectionProducerExportsSameFiniteFeatureAndGrantValues() {
        shadowOf(app).denyPermissions(Manifest.permission.READ_PHONE_NUMBERS)
        val census = experiment.census()
        fun number(value: Boolean?) = if (value == null) -1 else if (value) 1 else 0
        val generation = file.readLines().single { it.contains("stage=CENSUS_OWN_READ ") }.substringAfter("generation=")
        val expected = "telecom_inspect_v1 sdk=${census.sdk} connectionService=${number(census.connectionServiceFeature)} telecomFeature=${number(census.telecomFeature)} automotive=${number(census.automotiveFeature)} telecomPresent=${number(census.telecomPresent)} voiceCapable=${number(census.voiceCapable)} readGrant=0 ownOutcomeCode=${census.ownReadOutcome.ordinal} settingsCode=${census.settingsAvailability.ordinal} generation=$generation"
        assertTrue("Inspect feature producer did not persist same finite values", file.readLines().contains(expected))
        manualZipMatchesSource()
    }
    @Test fun numericSanitizerRejectsUnknownCodesAndStoreRemainsBoundedAcrossRefreshes() {
        assertNull(TelecomAccountDiagnostics.sanitize(-1, 0, 1))
        assertNull(TelecomAccountDiagnostics.sanitize(0, 1000, 1))
        assertNull(TelecomAccountDiagnostics.sanitize(0, 0, Long.MAX_VALUE))
        shadowOf(app).denyPermissions(Manifest.permission.READ_PHONE_NUMBERS)
        repeat(100) { experiment.refresh() }
        assertTrue(file.length() <= TelecomAccountDiagnostics.MAX_BYTES)
        assertTrue(file.readLines().size <= TelecomAccountDiagnostics.Stage.entries.size + 1)
        manualZipMatchesSource()
    }
}
