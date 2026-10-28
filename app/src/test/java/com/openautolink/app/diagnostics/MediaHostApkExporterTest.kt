package com.openautolink.app.diagnostics

import android.app.Application
import android.content.Context
import android.content.pm.*
import android.net.Uri
import io.mockk.*
import org.junit.*
import org.junit.Assert.*
import org.junit.rules.TemporaryFolder
import java.io.*
import java.security.MessageDigest
import java.util.zip.ZipInputStream

@org.junit.runner.RunWith(org.robolectric.RobolectricTestRunner::class)
@org.robolectric.annotation.Config(sdk = [32, 34], application = Application::class, shadows = [MediaApkOsShadow::class])
@org.robolectric.annotation.ConscryptMode(org.robolectric.annotation.ConscryptMode.Mode.OFF)
class MediaHostApkExporterTest {
    @get:Rule val temp = TemporaryFolder()
    private fun fixture(flags: Int = ApplicationInfo.FLAG_SYSTEM): Pair<Context, PackageManager> {
        val pm = mockk<PackageManager>(); val context = mockk<Context>()
        every { context.packageManager } returns pm
        val source = temp.newFile("${System.nanoTime()}.apk").apply { writeText("APK-BYTES-canary") }
        val app = ApplicationInfo().apply { packageName = "com.android.car.media"; enabled = true; this.flags = flags; publicSourceDir = source.absolutePath }
        val activity = ActivityInfo().apply { packageName = app.packageName; name = "MediaActivity"; enabled = true; exported = true; applicationInfo = app }
        val resolved = ResolveInfo().apply { activityInfo = activity }
        val signing = mockk<SigningInfo>(); every { signing.apkContentsSigners } returns arrayOf(Signature("1234"))
        val info = PackageInfo().apply { packageName = app.packageName; applicationInfo = app; versionName = "1"; setLongVersionCode(1); signingInfo = signing }
        every { pm.resolveActivity(any(), any<Int>()) } returns resolved
        every { pm.queryIntentActivities(any(), any<Int>()) } returns listOf(resolved)
        every { pm.getPackageInfo("com.android.car.media", PackageManager.GET_SIGNING_CERTIFICATES) } returns info
        every { pm.getComponentEnabledSetting(any()) } returns PackageManager.COMPONENT_ENABLED_STATE_DEFAULT
        every { pm.getApplicationEnabledSetting(any()) } returns PackageManager.COMPONENT_ENABLED_STATE_DEFAULT
        return context to pm
    }
    @Test fun directFixedHostStreamContainsActualBytesAndReceiptNoPaths() {
        val (context, _) = fixture()
        val output = ByteArrayOutputStream()
        val exporter = MediaHostApkExporter(context, destination = { _, _ -> output })
        val sourceInfo = context.packageManager.getPackageInfo("com.android.car.media", PackageManager.GET_SIGNING_CERTIFICATES)
        assertEquals(1, sourceInfo.signingInfo!!.apkContentsSigners.size)
        val fd = android.system.Os.open(sourceInfo.applicationInfo!!.publicSourceDir, android.system.OsConstants.O_RDONLY or android.system.OsConstants.O_NOFOLLOW, 0)
        try { val stat = android.system.Os.fstat(fd); assertTrue("mode=${stat.st_mode} size=${stat.st_size}", android.system.OsConstants.S_ISREG(stat.st_mode)); assertTrue(stat.st_size > 0) } finally { android.system.Os.close(fd) }
        val receipt = exporter.export(Uri.parse("content://private.canary/new"), MediaExportCancellation()) { _, _ -> }
        assertEquals(MediaExportCode.SAVED, receipt.code)
        val entries = mutableMapOf<String, ByteArray>()
        ZipInputStream(output.toByteArray().inputStream()).use { z -> while (true) { val e = z.nextEntry ?: break; entries[e.name] = z.readBytes() } }
        assertEquals(setOf("base.apk", "export-info.json"), entries.keys)
        assertEquals("APK-BYTES-canary", entries.getValue("base.apk").toString(Charsets.UTF_8))
        val metadata = entries.getValue("export-info.json").toString(Charsets.UTF_8)
        val digest = MessageDigest.getInstance("SHA-256").digest(entries.getValue("base.apk")).joinToString("") { "%02x".format(it) }
        assertTrue(metadata.contains(digest)); assertFalse(metadata.contains(temp.root.absolutePath)); assertFalse(metadata.contains("content://"))
        assertTrue(metadata.contains("not_atomic_install_snapshot"))
        assertEquals(512L * 1024 * 1024, MediaHostApkExporter.MAX_INPUT)
        assertEquals(514L * 1024 * 1024, MediaHostApkExporter.MAX_OUTPUT)
    }
    @Test fun sideloadAndUnqueriedDefaultNeverOpenDestination() {
        val (context, pm) = fixture(0); var opened = false
        val exporter = MediaHostApkExporter(context, destination = { _, _ -> opened = true; ByteArrayOutputStream() })
        assertEquals(MediaExportCode.UNAVAILABLE, exporter.export(Uri.EMPTY, MediaExportCancellation()) { _, _ -> }.code)
        assertFalse(opened)
        every { pm.queryIntentActivities(any(), any<Int>()) } returns emptyList()
        assertEquals(MediaExportCode.UNAVAILABLE, exporter.export(Uri.EMPTY, MediaExportCancellation()) { _, _ -> }.code)
    }
    @Test fun cancellationAndOutputCloseFailureCannotReportSaved() {
        val (context, _) = fixture()
        val cancel = MediaExportCancellation(); cancel.revoke()
        assertEquals(MediaExportCode.CANCELED, MediaHostApkExporter(context).export(Uri.EMPTY, cancel) { _, _ -> }.code)
        val output = object : ByteArrayOutputStream() { override fun close() { super.close(); throw IOException("private URI") } }
        assertEquals(MediaExportCode.FAILED, MediaHostApkExporter(context, destination = { _, _ -> output }).export(Uri.EMPTY, MediaExportCancellation()) { _, _ -> }.code)
    }
    @Test fun destinationIsClosedExactlyOnceAndActualOutputLimitInvalidatesArchive() {
        val (context, _) = fixture()
        var closed = 0
        val output = object : ByteArrayOutputStream() { override fun close() { closed++; if (closed > 1) throw IOException("double close"); super.close() } }
        assertEquals(MediaExportCode.SAVED, MediaHostApkExporter(context, destination = { _, _ -> output }).export(Uri.EMPTY, MediaExportCancellation()) { _, _ -> }.code)
        assertEquals(1, closed)
        assertEquals(MediaExportCode.LIMIT, MediaHostApkExporter(context, destination = { _, _ -> ByteArrayOutputStream() }, outputLimit = 8).export(Uri.EMPTY, MediaExportCancellation()) { _, _ -> }.code)
    }
    @Test fun packageChangeAtLastCopyInvalidatesAndInputCapIsExact() {
        val (context, pm) = fixture()
        val output = ByteArrayOutputStream()
        val exporter = MediaHostApkExporter(context, destination = { _, _ -> output })
        val result = exporter.export(Uri.EMPTY, MediaExportCancellation()) { _, _ -> every { pm.resolveActivity(any(), any<Int>()) } returns null }
        assertEquals(MediaExportCode.PACKAGE_CHANGED, result.code)
        assertEquals(MediaExportCode.LIMIT, MediaHostApkExporter(fixture().first, inputLimit = 1).export(Uri.EMPTY, MediaExportCancellation()) { _, _ -> }.code)
    }
}
