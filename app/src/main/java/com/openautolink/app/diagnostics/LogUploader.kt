package com.openautolink.app.diagnostics

import android.content.Context
import android.os.Build
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Result of a manual upload, explicitly distinguishing retained EV coverage from other logs. */
sealed class UploadResult {
    data class Success(val storedBytes: Int, val fileCount: Int, val coverage: String) : UploadResult()
    data class Failure(
        val reason: String,
        val code: UploadFailureCode = UploadFailureCode.UNKNOWN,
        val httpStatus: Int? = null,
    ) : UploadResult()
}

/** Stable display codes; never derive user-facing diagnostics from exception/server text. */
enum class UploadFailureCode { DISABLED, NOT_CONFIGURED, HTTPS_REQUIRED, NO_LOGS, SNAPSHOT, HTTP, NETWORK, CANCELED, UNKNOWN }

/** Maintainer credentials must never be sent over cleartext or non-HTTP schemes. */
object UploadEndpointPolicy {
    fun isAllowed(raw: String): Boolean = try {
        val uri = URI(raw.trim())
        uri.scheme.equals("https", ignoreCase = true) &&
            !uri.host.isNullOrBlank() &&
            uri.userInfo == null
    } catch (_: Exception) {
        false
    }
}

/** Manual, maintainer-only uploader; caller owns the logUploadEnabled authorization gate. */
class LogUploader(
    private val context: Context,
    private val openConnection: (URL) -> HttpURLConnection = { it.openConnection() as HttpURLConnection },
    private val flushTelemetry: () -> Boolean = { EvTelemetryRecorder.flushForUpload(timeoutMs = 2000) },
    private val flushBluetooth: () -> Boolean = { BluetoothReconnectService.flush() },
) {
    companion object {
        private const val TAG = "LogUploader"
        private const val DIR_NAME = "openautolink/logs"
        private const val CONNECT_TIMEOUT_MS = 15_000
        private const val READ_TIMEOUT_MS = 60_000
    }

    private fun allLogFiles(): List<File> = context.getExternalFilesDirs(null)
        .filterNotNull().flatMap { base -> File(base, DIR_NAME).listFiles()?.toList().orEmpty() }
        .filter { it.isFile && it.name.endsWith(".log", true) }

    private fun deviceLabel(explicit: String): String =
        explicit.ifBlank { "${Build.MANUFACTURER}-${Build.MODEL}".take(64) }

    /** Blocking disk/network operation: invoke only from Dispatchers.IO on a manual action. */
    fun upload(url: String, token: String, deviceLabelPref: String): UploadResult {
        if (url.isBlank() || token.isBlank()) return UploadResult.Failure("upload not configured", UploadFailureCode.NOT_CONFIGURED)
        if (!UploadEndpointPolicy.isAllowed(url)) return UploadResult.Failure("HTTPS upload URL required", UploadFailureCode.HTTPS_REQUIRED)

        val archive = try {
            val flushed = try { flushTelemetry() } catch (_: Exception) { false }
            val bluetoothFlushed = try { flushBluetooth() } catch (_: Exception) { false }
            check(com.openautolink.app.carplay.HostMediaDiagnosticSink.flush()) { "host media diagnostics pending" }
            check(com.openautolink.carplaylab.telecom.NativeCallDiagnostics.flush()) { "native call diagnostics pending" }
            val plan = LogUploadArchive.select(allLogFiles(), System.currentTimeMillis())
            if (plan.selected.isEmpty()) return UploadResult.Failure(
                "no eligible log files; ${plan.skipped.size} omitted (age/size/member limits)", UploadFailureCode.NO_LOGS
            )
            LogUploadArchive.create(plan, context.cacheDir, flushed, bluetoothFlushed)
        } catch (_: Exception) {
            // Do not leak filesystem paths, credentials, or URL query strings through exception text.
            return UploadResult.Failure("log snapshot failed; source logs retained, retry upload", UploadFailureCode.SNAPSHOT)
        }

        return archive.use { payload ->
            val stamp = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(Date())
            var conn: HttpURLConnection? = null
            try {
                conn = openConnection(URL(url.trim())).apply {
                    requestMethod = "POST"
                    instanceFollowRedirects = false
                    doOutput = true
                    connectTimeout = CONNECT_TIMEOUT_MS
                    readTimeout = READ_TIMEOUT_MS
                    setRequestProperty("Content-Type", "application/zip")
                    setRequestProperty("X-Upload-Token", token)
                    setRequestProperty("X-Device-Label", deviceLabel(deviceLabelPref))
                    setRequestProperty("X-Orig-Name", "oal_car_$stamp.zip")
                    setFixedLengthStreamingMode(payload.file.length())
                }
                conn.outputStream.use { output -> payload.file.inputStream().use { it.copyTo(output, 32 * 1024) } }
                val code = conn.responseCode
                if (code in 200..299) {
                    OalLog.i(TAG, "Uploaded ${payload.plan.selected.size} files; ${payload.summary}")
                    UploadResult.Success(payload.file.length().toInt(), payload.plan.selected.size, payload.summary)
                } else {
                    OalLog.w(TAG, "Upload rejected: HTTP $code; source logs retained")
                    UploadResult.Failure("server HTTP $code; source logs retained", UploadFailureCode.HTTP, code)
                }
            } catch (_: Exception) {
                OalLog.e(TAG, "Upload failed; source logs retained")
                UploadResult.Failure("network upload failed; source logs retained", UploadFailureCode.NETWORK)
            } finally {
                conn?.disconnect()
            }
        }
    }
}
