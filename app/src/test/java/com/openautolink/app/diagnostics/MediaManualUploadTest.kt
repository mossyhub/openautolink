package com.openautolink.app.diagnostics

import android.app.Application
import android.content.Context
import io.mockk.*
import java.io.*
import java.net.*
import java.util.zip.ZipInputStream
import org.junit.*
import org.junit.Assert.*
import org.junit.rules.TemporaryFolder

@org.junit.runner.RunWith(org.robolectric.RobolectricTestRunner::class)
@org.robolectric.annotation.Config(sdk = [32, 34], application = Application::class)
@org.robolectric.annotation.ConscryptMode(org.robolectric.annotation.ConscryptMode.Mode.OFF)
class MediaManualUploadTest {
    @get:Rule val temp = TemporaryFolder()
    @After fun cleanup() { unmockkAll(); MediaDiagnosticHooks.snapshotProvider = MediaSnapshotProvider { MediaOwnSnapshot() } }
    @Test fun panelAbsentFileLoggingOffActualWireContainsOnlyFiniteSummaryAfterDisconnectAndFailure() {
        val base = temp.newFolder(); val cache = temp.newFolder()
        val context = mockk<Context>()
        every { context.getExternalFilesDir(null) } returns base
        every { context.getExternalFilesDirs(null) } returns arrayOf(base)
        every { context.cacheDir } returns cache
        mockkObject(OalLog); every { OalLog.i(any(), any()) } returns Unit
        every { OalLog.w(any(), any()) } returns Unit
        every { OalLog.e(any(), any()) } returns Unit
        val owner = Any(); MediaOutcomeSummary.process.admit(owner)
        MediaOutcomeSummary.process.record(owner, MediaOutcomeSummary.Outcome.CONTROL_RECEIVED)
        MediaOutcomeSummary.process.retire(owner)
        MediaDiagnosticHooks.snapshotProvider = MediaSnapshotProvider { MediaOwnSnapshot(true, true, phonePlaying = MediaTruth.TRUE, submitted = MediaSubmittedState.NONE) }
        val wire = ByteArrayOutputStream(); var code = 503
        val connection = object : HttpURLConnection(URL("https://example.test/")) {
            override fun connect() = Unit
            override fun usingProxy() = false
            override fun disconnect() = Unit
            override fun getResponseCode() = code
            override fun getOutputStream() = wire
        }
        val uploader = LogUploader(context, { connection }, { true }, { true })
        assertTrue(uploader.upload("https://example.test/", "secret-canary", "test") is UploadResult.Failure)
        code = 200; wire.reset()
        assertTrue(uploader.upload("https://example.test/", "secret-canary", "test") is UploadResult.Success)
        val members = mutableMapOf<String,String>()
        ZipInputStream(wire.toByteArray().inputStream()).use { z -> while (true) { val e = z.nextEntry ?: break; members[e.name] = z.readBytes().toString(Charsets.UTF_8) } }
        val summary = members.filterKeys { MediaDiagnosticStore.runName.matches(it) }.values.joinToString()
        assertTrue(summary.contains("phone_playing=TRUE"))
        assertTrue(summary.contains("submitted=NONE"))
        assertTrue(summary.contains("last_outcome=CONTROL_RECEIVED"))
        assertTrue(members.getValue("upload_manifest.log").contains("media_summary_preparation=AVAILABLE"))
        for (canary in listOf("secret-canary", ".apk", "content://", "private.permission")) assertFalse(members.values.any { it.contains(canary) })
        assertFalse(connection.instanceFollowRedirects)
        assertTrue(cache.listFiles()!!.isEmpty())
    }
    @Test fun exactMediaSchemaBoundsAndPriorityDoNotWeakenExistingBudgets() {
        val dir = temp.newFolder(); val now = System.currentTimeMillis()
        val store = MediaDiagnosticStore({ dir }, { now - 7 * 3600000 })
        repeat(3) { store.save(MediaOwnSnapshot(), MediaCoverage()) }
        val regular = File(dir, "regular.log").apply { writeText("regular") }
        val plan = LogUploadArchive.select(dir.listFiles()!!.toList(), now)
        assertEquals(4, plan.selected.size)
        assertTrue(MediaDiagnosticStore.runName.matches(plan.selected.first().name))
        val invalid = File(dir, "media_integration_run_0000000000000000001.log").apply { writeText("privacy.canary") }
        assertEquals("media_invalid_schema", LogUploadArchive.select(listOf(invalid), now).skipped.single().reason)
        assertEquals(25L * 1024 * 1024, LogUploadArchive.MAX_RAW_BYTES)
        assertEquals(16L * 1024 * 1024 - 1, LogUploadArchive.MAX_MEMBER_BYTES)
        assertEquals(64L * 1024, LogUploadArchive.MANIFEST_RESERVE)
    }
}
