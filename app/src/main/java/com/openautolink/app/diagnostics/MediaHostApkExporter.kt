package com.openautolink.app.diagnostics

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.system.Os
import android.system.OsConstants
import android.system.StructStat
import java.io.*
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.FutureTask
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.CancellationException
import org.json.JSONArray
import org.json.JSONObject

internal enum class MediaExportCode { SAVED, UNAVAILABLE, PACKAGE_CHANGED, LIMIT, FAILED, CANCELED, INTERRUPTED }
internal data class MediaExportReceipt(val code: MediaExportCode, val bytes: Long = 0, val files: Int = 0)
private class ExportStopped : IOException()
private class ExportFailure(val code: MediaExportCode) : IOException()

/** Revocation precedes asynchronous exact-handle closure. Caller stays busy until awaitClose returns. */
internal class MediaExportCancellation {
    private val lock = Any()
    private var revoked = false; private var committed = false
    private var output: Closeable? = null
    private var closeTask: FutureTask<Unit>? = null
    val signal = CancellationSignal()
    fun check() = synchronized(lock) { if (revoked) throw ExportStopped() }
    fun attach(value: Closeable) = synchronized(lock) { check(); output = value }
    fun detach() = synchronized(lock) { output = null }
    fun revoke(): Boolean = synchronized(lock) {
        if (committed) return@synchronized false
        if (revoked) return@synchronized true
        revoked = true
        val exact = output
        val task = FutureTask<Unit> { try { signal.cancel() } catch (_: Exception) { }; try { exact?.close() } catch (_: Exception) { }; Unit }
        closeTask = task
        Thread(task, "oal-media-export-close").start()
        true
    }
    fun awaitClose() { val task = synchronized(lock) { closeTask }; task?.get() }
    fun commit() = synchronized(lock) { check(); committed = true }
}

/** Internal, fixed package only. No package/path input and no staging or upload reachability. Blocking IO. */
internal class MediaHostApkExporter(private val context: Context,
    private val destination: (Uri, CancellationSignal) -> OutputStream = { uri, signal ->
        val fd = context.contentResolver.openFileDescriptor(uri, "w", signal) ?: throw IOException()
        ParcelFileDescriptor.AutoCloseOutputStream(fd)
    }, private val inputLimit: Long = MAX_INPUT, private val outputLimit: Long = MAX_OUTPUT) {
    private data class Source(val path: String, val split: String, val entry: String)
    private data class Identity(val component: String, val version: Long, val versionName: String,
        val updated: Long, val origin: String, val signers: List<String>, val sources: List<Source>)
    private class Opened(val source: Source, val stream: FileInputStream, val stat: StructStat) : Closeable {
        override fun close() = stream.close()
    }
    private fun unavailable(): Nothing = throw ExportFailure(MediaExportCode.UNAVAILABLE)
    private fun select(): Identity {
        val pm = context.packageManager
        val intent = Intent(MediaIntegrationInspection.MEDIA_ACTION)
        val resolved = pm.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo ?: unavailable()
        if (resolved.packageName != PACKAGE || resolved.name.isNullOrBlank()) unavailable()
        val queried = pm.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY).mapNotNull { it.activityInfo }
            .firstOrNull { it.packageName == PACKAGE && it.name == resolved.name } ?: unavailable()
        fun effective(manifest: Boolean, override: Int): Boolean = when (override) {
            PackageManager.COMPONENT_ENABLED_STATE_DEFAULT -> manifest
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED -> true
            else -> false
        }
        fun accessible(a: android.content.pm.ActivityInfo): Boolean = a.exported &&
            effective(a.enabled, pm.getComponentEnabledSetting(ComponentName(PACKAGE, a.name))) &&
            effective(a.applicationInfo.enabled, pm.getApplicationEnabledSetting(PACKAGE)) &&
            (a.permission.isNullOrBlank() || context.checkSelfPermission(a.permission) == PackageManager.PERMISSION_GRANTED)
        if (!accessible(resolved) || !accessible(queried)) unavailable()
        val info = pm.getPackageInfo(PACKAGE, PackageManager.GET_SIGNING_CERTIFICATES)
        if (info.packageName != PACKAGE) unavailable()
        val app = info.applicationInfo ?: unavailable()
        if (app.packageName != PACKAGE || !effective(app.enabled, pm.getApplicationEnabledSetting(PACKAGE))) unavailable()
        val origin = when {
            app.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP != 0 -> "UPDATED_SYSTEM"
            app.flags and ApplicationInfo.FLAG_SYSTEM != 0 -> "SYSTEM"
            else -> unavailable()
        }
        val certs = info.signingInfo?.apkContentsSigners ?: unavailable()
        if (certs.isEmpty() || certs.size > 8) unavailable()
        val signers = certs.map { hex(MessageDigest.getInstance("SHA-256").digest(it.toByteArray())) }.distinct().sorted()
        val base = app.publicSourceDir ?: unavailable()
        val paths = app.splitPublicSourceDirs.orEmpty()
        val names = info.splitNames.orEmpty()
        if (names.size != paths.size || paths.size > 63 || names.distinct().size != names.size || names.any { it.isBlank() || it.codePointCount(0, it.length) > 240 }) unavailable()
        val sources = listOf(Source(base, "", "base.apk")) + names.zip(paths).sortedBy { it.first }.mapIndexed { index, (name, path) ->
            Source(path, name, "split-${(index + 1).toString().padStart(2, '0')}.apk")
        }
        if (sources.any { !File(it.path).isAbsolute || !it.path.endsWith(".apk") } ||
            sources.map { File(it.path).canonicalPath }.distinct().size != sources.size) unavailable()
        val version = info.versionName.orEmpty()
        if (version.codePointCount(0, version.length) > 80) unavailable()
        return Identity(resolved.name, info.longVersionCode, version, info.lastUpdateTime, origin, signers, sources)
    }
    fun export(uri: Uri, cancel: MediaExportCancellation, progress: (Long, Int) -> Unit): MediaExportReceipt {
        val opened = mutableListOf<Opened>()
        var bytes = 0L; var files = 0
        val exportId = UUID.randomUUID().toString(); val started = System.currentTimeMillis()
        var result: MediaExportReceipt
        try {
            cancel.check()
            val identity = select()
            var total = 0L
            for (source in identity.sources) {
                cancel.check()
                val fd = Os.open(source.path, OsConstants.O_RDONLY or OsConstants.O_NOFOLLOW or OsConstants.O_CLOEXEC, 0)
                val stream = FileInputStream(fd)
                try {
                    val stat = Os.fstat(fd)
                    if (!OsConstants.S_ISREG(stat.st_mode) || stat.st_size <= 0) unavailable()
                    if (stat.st_size > inputLimit - total) throw ExportFailure(MediaExportCode.LIMIT)
                    total += stat.st_size
                    opened += Opened(source, stream, stat)
                } catch (e: Exception) { stream.close(); throw e }
            }
            cancel.check()
            if (select() != identity) throw ExportFailure(MediaExportCode.PACKAGE_CHANGED)
            val destinationStream = destination(uri, cancel.signal)
            val closed = java.util.concurrent.atomic.AtomicBoolean()
            val raw = object : FilterOutputStream(destinationStream) {
                override fun write(b: Int) = out.write(b)
                override fun write(b: ByteArray, off: Int, len: Int) = out.write(b, off, len)
                override fun close() { if (closed.compareAndSet(false, true)) out.close() }
            }
            try {
                cancel.attach(raw)
                val limited = object : FilterOutputStream(raw) {
                    var count = 0L
                    override fun write(b: Int) { cancel.check(); if (count >= outputLimit) throw ExportFailure(MediaExportCode.LIMIT); out.write(b); count++ }
                    override fun write(b: ByteArray, off: Int, len: Int) { cancel.check(); if (len.toLong() > outputLimit - count) throw ExportFailure(MediaExportCode.LIMIT); out.write(b, off, len); count += len }
                }
                ZipOutputStream(limited).use { zip ->
                    val buffer = ByteArray(64 * 1024)
                    val receipts = JSONArray()
                    for (source in opened) {
                        cancel.check()
                        zip.putNextEntry(ZipEntry(source.source.entry))
                        val digest = MessageDigest.getInstance("SHA-256")
                        var remaining = source.stat.st_size; var written = 0L
                        while (remaining > 0) {
                            cancel.check()
                            val read = source.stream.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                            if (read <= 0) throw ExportFailure(MediaExportCode.PACKAGE_CHANGED)
                            if (read.toLong() > inputLimit - bytes) throw ExportFailure(MediaExportCode.LIMIT)
                            cancel.check(); zip.write(buffer, 0, read); digest.update(buffer, 0, read)
                            remaining -= read; written += read; bytes += read
                            progress(bytes, files)
                        }
                        if (source.stream.read() != -1 || !same(source.stat, Os.fstat(source.stream.fd)) ||
                            !same(source.stat, Os.lstat(source.source.path))) throw ExportFailure(MediaExportCode.PACKAGE_CHANGED)
                        cancel.check(); zip.closeEntry(); files++
                        receipts.put(JSONObject().put("entry", source.source.entry).put("split", MediaIntegrationInspection.safe(source.source.split, 240))
                            .put("bytes", written).put("sha256", hex(digest.digest())))
                    }
                    cancel.check()
                    if (try { select() != identity } catch (_: Exception) { true }) throw ExportFailure(MediaExportCode.PACKAGE_CHANGED)
                    val metadata = JSONObject().put("schema", 1).put("exportId", exportId).put("start_ms", started).put("end_ms", System.currentTimeMillis())
                        .put("package", PACKAGE).put("versionCode", identity.version).put("versionName", MediaIntegrationInspection.safe(identity.versionName, 80))
                        .put("lastUpdateTime", identity.updated).put("origin", identity.origin).put("signers_sha256", JSONArray(identity.signers))
                        .put("consistency", "public_pm_pre_post_and_fd_checks_not_atomic_install_snapshot").put("entries", receipts)
                        .toString().toByteArray(Charsets.UTF_8)
                    if (metadata.size > 64 * 1024) throw ExportFailure(MediaExportCode.LIMIT)
                    cancel.check(); zip.putNextEntry(ZipEntry("export-info.json")); zip.write(metadata); zip.closeEntry()
                    cancel.check(); zip.finish()
                }
            } finally { try { raw.close() } finally { cancel.detach() } }
            cancel.check()
            // Source closure also belongs to success, before commit.
            opened.forEach { it.close() }; opened.clear()
            cancel.commit()
            result = MediaExportReceipt(MediaExportCode.SAVED, bytes, files)
        } catch (e: CancellationException) { throw e }
        catch (_: ExportStopped) { result = MediaExportReceipt(MediaExportCode.CANCELED, bytes, files) }
        catch (e: ExportFailure) { result = MediaExportReceipt(e.code, bytes, files) }
        catch (_: Exception) { result = MediaExportReceipt(MediaExportCode.FAILED, bytes, files) }
        finally { opened.forEach { try { it.close() } catch (_: Exception) { } }; cancel.awaitClose() }
        return result
    }
    companion object {
        const val PACKAGE = "com.android.car.media"
        const val MAX_INPUT = 512L * 1024 * 1024
        const val MAX_OUTPUT = 514L * 1024 * 1024
        private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }
        private fun same(a: StructStat, b: StructStat) = a.st_dev == b.st_dev && a.st_ino == b.st_ino && a.st_size == b.st_size &&
            a.st_mode == b.st_mode && a.st_mtim.tv_sec == b.st_mtim.tv_sec && a.st_mtim.tv_nsec == b.st_mtim.tv_nsec
    }
}
