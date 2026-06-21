package com.openautolink.companion.diagnostics

import android.content.Context
import android.os.Build
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Result of an upload attempt. */
sealed class UploadResult {
    data class Success(val bytes: Int, val fileCount: Int) : UploadResult()
    data class Failure(val reason: String) : UploadResult()
}

/**
 * Bundles recent companion log files into a zip and POSTs it to the
 * maintainer's upload endpoint. OFF-BY-DEFAULT, maintainer-only (gated by
 * CompanionPrefs.LOG_UPLOAD_ENABLED). Pure java.net + java.util.zip, no deps.
 *
 * Bundles both the companion's own log (oal_companion_*.log) and the captured
 * logcat (logcat_companion_*.log) within the recent window. The server + the
 * Hermes pipeline dedup overlapping uploads by content, so re-uploading across
 * drives never double-counts.
 */
class LogUploader(private val context: Context) {

    companion object {
        private const val TAG = "OAL_Upload"
        private const val DIR_NAME = "openautolink/logs"
        private const val RECENT_WINDOW_MS = 6L * 60 * 60 * 1000   // 6 h
        private const val MAX_ZIP_BYTES = 25L * 1024 * 1024        // 25 MB cap
        private const val CONNECT_TIMEOUT_MS = 15_000
        private const val READ_TIMEOUT_MS = 60_000
    }

    /** Resolve the log dir the same way CompanionFileLogger does. */
    private fun logDir(): File? {
        context.getExternalFilesDir(null)?.let {
            val d = File(it, DIR_NAME)
            if (d.isDirectory) return d
        }
        val internal = File(context.filesDir, DIR_NAME)
        return if (internal.isDirectory) internal else null
    }

    private fun recentFiles(): List<File> {
        val dir = logDir() ?: return emptyList()
        val all = dir.listFiles()?.filter { it.isFile }
            ?.sortedByDescending { it.lastModified() } ?: emptyList()
        val cutoff = System.currentTimeMillis() - RECENT_WINDOW_MS
        val recent = all.filter { it.lastModified() >= cutoff }
        val source = recent.ifEmpty { all.take(2) }
        val picked = mutableListOf<File>()
        var total = 0L
        for (f in source) {
            if (total + f.length() > MAX_ZIP_BYTES) continue
            picked += f
            total += f.length()
        }
        return picked
    }

    private fun zipBytes(files: List<File>): ByteArray {
        val bos = ByteArrayOutputStream()
        ZipOutputStream(BufferedOutputStream(bos)).use { zos ->
            for (f in files) {
                zos.putNextEntry(ZipEntry(f.name))
                f.inputStream().use { it.copyTo(zos) }
                zos.closeEntry()
            }
        }
        return bos.toByteArray()
    }

    private fun deviceLabel(explicit: String): String =
        explicit.ifBlank { "${Build.MANUFACTURER}-${Build.MODEL}".take(64) }

    /**
     * Zip recent logs and POST them. Blocking — call from a background
     * dispatcher (the service uses its IO scope). Returns a [UploadResult].
     */
    fun upload(url: String, token: String, deviceLabelPref: String): UploadResult {
        if (url.isBlank() || token.isBlank()) {
            return UploadResult.Failure("not configured")
        }
        val files = recentFiles()
        if (files.isEmpty()) return UploadResult.Failure("no log files")

        val payload = try {
            zipBytes(files)
        } catch (e: Exception) {
            return UploadResult.Failure("zip failed: ${e.message}")
        }

        val stamp = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(Date())
        val origName = "oal_companion_$stamp.zip"

        return try {
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                setRequestProperty("Content-Type", "application/zip")
                setRequestProperty("X-Upload-Token", token)
                setRequestProperty("X-Device-Label", deviceLabel(deviceLabelPref))
                setRequestProperty("X-Orig-Name", origName)
                setFixedLengthStreamingMode(payload.size)
            }
            conn.outputStream.use { it.write(payload) }
            val code = conn.responseCode
            conn.disconnect()
            if (code in 200..299) {
                CompanionLog.i(TAG, "Uploaded ${files.size} file(s), ${payload.size} bytes -> HTTP $code")
                UploadResult.Success(payload.size, files.size)
            } else {
                CompanionLog.w(TAG, "Upload rejected: HTTP $code")
                UploadResult.Failure("server HTTP $code")
            }
        } catch (e: Exception) {
            CompanionLog.e(TAG, "Upload failed: ${e.message}")
            UploadResult.Failure(e.message ?: "network error")
        }
    }
}
