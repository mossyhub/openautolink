package com.openautolink.app.diagnostics

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
    data class Success(val storedBytes: Int, val fileCount: Int) : UploadResult()
    data class Failure(val reason: String) : UploadResult()
}

/**
 * Bundles recent OAL log files into a zip and POSTs it to the maintainer's
 * upload endpoint.
 *
 * This is an OFF-BY-DEFAULT, maintainer-only feature (gated by
 * [AppPreferences.logUploadEnabled]); callers must pass a non-blank url +
 * token. No new dependencies: pure java.net + java.util.zip.
 *
 * Because the app cannot reliably know when a drive session ends, "recent"
 * means every log file modified within [RECENT_WINDOW_MS]; the server +
 * Hermes pipeline dedup overlapping uploads by content, so re-tapping Upload
 * across consecutive drives never double-counts.
 */
class LogUploader(private val context: Context) {

    companion object {
        private const val TAG = "LogUploader"
        private const val DIR_NAME = "openautolink/logs"
        private const val RECENT_WINDOW_MS = 6L * 60 * 60 * 1000   // 6 h
        private const val MAX_ZIP_BYTES = 25L * 1024 * 1024        // 25 MB cap
        private const val CONNECT_TIMEOUT_MS = 15_000
        private const val READ_TIMEOUT_MS = 60_000
    }

    /** Collect log dirs across all external volumes (USB stick + primary). */
    private fun logDirs(): List<File> {
        val out = mutableListOf<File>()
        context.getExternalFilesDirs(null).forEach { base ->
            if (base != null) {
                val d = File(base, DIR_NAME)
                if (d.isDirectory) out += d
            }
        }
        return out
    }

    private fun allLogFiles(): List<File> =
        logDirs().flatMap { it.listFiles()?.toList() ?: emptyList() }
            .filter { it.isFile }

    /** Files modified within the recent window, newest first, size-capped.
     *  Falls back to the single newest file if nothing is in the window. */
    private fun recentFiles(): List<File> {
        val cutoff = System.currentTimeMillis() - RECENT_WINDOW_MS
        val recent = allLogFiles()
            .filter { it.lastModified() >= cutoff }
            .sortedByDescending { it.lastModified() }
        val picked = mutableListOf<File>()
        var total = 0L
        for (f in recent) {
            if (total + f.length() > MAX_ZIP_BYTES) continue
            picked += f
            total += f.length()
        }
        if (picked.isEmpty()) {
            allLogFiles().maxByOrNull { it.lastModified() }?.let { picked += it }
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
     * Zip recent logs and POST them. Blocking network call — invoke from
     * Dispatchers.IO. Returns a [UploadResult].
     */
    fun upload(url: String, token: String, deviceLabelPref: String): UploadResult {
        if (url.isBlank() || token.isBlank()) {
            return UploadResult.Failure("upload not configured")
        }
        val files = recentFiles()
        if (files.isEmpty()) return UploadResult.Failure("no log files to upload")

        val payload = try {
            zipBytes(files)
        } catch (e: Exception) {
            return UploadResult.Failure("zip failed: ${e.message}")
        }

        val stamp = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(Date())
        val origName = "oal_car_$stamp.zip"

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
                OalLog.i(TAG, "Uploaded ${files.size} file(s), ${payload.size} bytes -> HTTP $code")
                UploadResult.Success(payload.size, files.size)
            } else {
                OalLog.w(TAG, "Upload rejected: HTTP $code")
                UploadResult.Failure("server HTTP $code")
            }
        } catch (e: Exception) {
            OalLog.e(TAG, "Upload failed: ${e.message}")
            UploadResult.Failure(e.message ?: "network error")
        }
    }
}
