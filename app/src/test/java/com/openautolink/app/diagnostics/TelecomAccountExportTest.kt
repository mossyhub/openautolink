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
    @Test fun nativeCallReceiptSurvivesBridgeRetirementAndManualZipWithOptionalFileLoggingOff() {
        shadowOf(app).grantPermissions(Manifest.permission.READ_PHONE_NUMBERS)
        experiment.enable()
        val manager = app.getSystemService(android.telecom.TelecomManager::class.java)
        shadowOf(manager).enablePhoneAccount(experiment.handle, true); experiment.refresh()
        val bridge = experiment.authenticatedLink { true }!!
        fun field(id: Int, bytes: ByteArray) = byteArrayOf(0, (bytes.size + 4).toByte(), 0, id.toByte()) + bytes
        val id = "00000000-0000-4000-8000-000000000077"
        bridge.handle(com.shilapi.xcertplay.iap2.wire.Iap2Frame(0x4155,
            field(4, id.toByteArray() + byteArrayOf(0)) + field(2, byteArrayOf(2)) +
                field(3, byteArrayOf(1)) + field(9, byteArrayOf(0)) + field(1, "private caller".toByteArray() + byteArrayOf(0))))
        shadowOf(android.os.Looper.getMainLooper()).idle()
        bridge.close(); experiment.disable(); shadowOf(android.os.Looper.getMainLooper()).idle()
        assertTrue(com.openautolink.carplaylab.telecom.NativeCallDiagnostics.flush())
        val receipt = File(directory, "native_call_controls.log")
        val deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(3)
        while (!receipt.isFile && System.nanoTime() < deadline) Thread.sleep(10)
        assertTrue("finite native-call producer is not exported", receipt.isFile)
        val bytes = receipt.readBytes()
        val text = bytes.toString(Charsets.UTF_8)
        assertTrue(text.contains("action=OBSERVATION reason=OBSERVED status=2"))
        assertFalse(text.contains(id)); assertFalse(text.contains("private caller"))
        assertTrue(bytes.size <= 16384)
        receipt.setLastModified(1)
        LogUploadArchive.create(LogUploadArchive.select(listOf(receipt), System.currentTimeMillis()), app.cacheDir).use { archive ->
            ZipFile(archive.file).use { zip ->
                val entry = zip.getEntry(receipt.name)
                assertNotNull("manual ZIP lost terminal native-call receipt after UI gone", entry)
                assertArrayEquals(bytes, zip.getInputStream(entry).readBytes())
            }
        }
    }
    @Test fun finiteNativeCallSchemasRejectPrivateSuffixUnknownEnumAndInvalidNumericFields() {
        val safe = "media stage=native-call action=REJECT reason=STATE status=255 pending=NONE calls=1 conference=0 queued=false"
        val sanitizer = com.openautolink.carplaylab.telecom.NativeCallDiagnostics
        assertEquals(safe, sanitizer.sanitize(safe))
        assertNotNull(com.openautolink.app.carplay.CarPlayAudioDiagnostics.sanitize(1, safe))
        for (bad in listOf(safe + " uuid=private", safe.replace("STATE", "private"), safe.replace("status=255", "status=256"),
            safe.replace("calls=1", "calls=17"), safe.replace("conference=0", "conference=2"), safe.replace("pending=NONE", "pending=private"))) {
            assertNull(sanitizer.sanitize(bad))
            assertNull(com.openautolink.app.carplay.CarPlayAudioDiagnostics.sanitize(1, bad))
        }
        repeat(100) {
            assertTrue(sanitizer.record(app, safe))
            assertTrue(sanitizer.flush()) // bounded writer backpressure is tested separately from storage bounds
        }
        val stored = File(directory, "native_call_controls.log")
        assertTrue(stored.length() <= 16384); assertTrue(stored.readLines().size <= 64)
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
