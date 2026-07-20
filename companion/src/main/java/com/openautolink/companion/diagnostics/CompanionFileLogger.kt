package com.openautolink.companion.diagnostics

import android.content.Context
import android.os.StatFs
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.File
import java.io.FileOutputStream
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * File logger for the companion app. Writes two files per session:
 *
 * 1. `oal_companion_<timestamp>.log` — our own CompanionLog lines
 * 2. `logcat_companion_<timestamp>.log` — full logcat capture filtered to OAL tags
 *
 * Files go to `<external_files_dir>/openautolink/logs/` which is app-private
 * storage that doesn't need MANAGE_EXTERNAL_STORAGE and survives app updates.
 * On modern Android this maps to something like:
 *   /storage/emulated/0/Android/data/com.openautolink.companion/files/openautolink/logs/
 *
 * Thread-safe: log writes are synchronized, logcat runs on a coroutine.
 */
class CompanionFileLogger(private val context: Context) {

    companion object {
        private const val TAG = "OAL_FileLog"
        private const val DIR_NAME = "openautolink/logs"
        private const val MAX_FILE_SIZE_BYTES = 50L * 1024 * 1024 // 50 MB
        private const val MIN_FREE_SPACE_BYTES = 10L * 1024 * 1024 // 10 MB
    }

    private val _isActive = MutableStateFlow(false)
    val isActive: StateFlow<Boolean> = _isActive.asStateFlow()

    private val _currentPath = MutableStateFlow<String?>(null)
    val currentPath: StateFlow<String?> = _currentPath.asStateFlow()

    private var writer: BufferedWriter? = null
    private val lock = Any()
    private val timeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    private val fileNameFormat = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US)

    private var logcatScope: CoroutineScope? = null
    private var logcatProcess: Process? = null
    private var logcatWriter: BufferedWriter? = null

    /**
     * Start file logging. Returns the log file path or null on failure.
     */
    fun start(): String? {
        synchronized(lock) {
            if (_isActive.value) return _currentPath.value

            val dir = resolveLogDir() ?: run {
                Log.w(TAG, "No writable storage for file logging")
                return null
            }

            val timestamp = fileNameFormat.format(Date())
            val logFile = File(dir, "oal_companion_$timestamp.log")
            val logcatFile = File(dir, "logcat_companion_$timestamp.log")

            return try {
                val fos = FileOutputStream(logFile, true)
                writer = BufferedWriter(OutputStreamWriter(fos, Charsets.UTF_8), 8192)
                _currentPath.value = logFile.absolutePath
                _isActive.value = true

                // Start logcat capture
                startLogcatCapture(logcatFile)

                Log.i(TAG, "File logging started: ${logFile.absolutePath}")
                logFile.absolutePath
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start file logging: ${e.message}")
                null
            }
        }
    }

    /**
     * Stop file logging, flush and close files, kill logcat.
     */
    fun stop() {
        synchronized(lock) {
            if (!_isActive.value) return
            _isActive.value = false

            try {
                writer?.flush()
                writer?.close()
            } catch (_: Exception) { }
            writer = null

            stopLogcatCapture()

            Log.i(TAG, "File logging stopped: ${_currentPath.value}")
            _currentPath.value = null
        }
    }

    /**
     * Write a single log line. Called from [CompanionLog].
     */
    fun writeLog(level: Char, tag: String, message: String) {
        if (!_isActive.value) return
        synchronized(lock) {
            val w = writer ?: return
            try {
                val path = _currentPath.value ?: return
                if (File(path).length() >= MAX_FILE_SIZE_BYTES) {
                    Log.w(TAG, "Log file reached ${MAX_FILE_SIZE_BYTES / 1024 / 1024}MB limit")
                    stop()
                    return
                }

                w.write(timeFormat.format(Date()))
                w.write(" ")
                w.write(level.toString())
                w.write("/")
                w.write(tag)
                w.write(": ")
                w.write(message)
                w.newLine()
                w.flush()
            } catch (e: Exception) {
                _isActive.value = false
                try { w.close() } catch (_: Exception) { }
                writer = null
            }
        }
    }

    // ── Logcat capture ─────────────────────────────────────────────────

    private fun startLogcatCapture(logcatFile: File) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        logcatScope = scope

        // Maintainer verbose-capture toggle: when ON, capture ALL logcat
        // (every tag, every process) so Google's AA app (gearhead) teardown
        // reason is recorded — needed to diagnose why it drops mid-session.
        // When OFF (default), filter to OAL/WiFi tags for a compact log.
        val verbose = try {
            context.getSharedPreferences(
                com.openautolink.companion.CompanionPrefs.NAME, Context.MODE_PRIVATE
            ).getBoolean(
                com.openautolink.companion.CompanionPrefs.LOG_VERBOSE_CAPTURE,
                com.openautolink.companion.CompanionPrefs.DEFAULT_LOG_VERBOSE_CAPTURE
            )
        } catch (_: Exception) { false }

        val logcatCmd = if (verbose) {
            // Unfiltered: all tags at debug+ across the whole device. Larger,
            // but the only way to see gearhead's own logs.
            arrayOf("logcat", "-v", "threadtime", "*:D")
        } else {
            arrayOf(
                "logcat", "-v", "threadtime",
                // Filter to our tags + system WiFi/connectivity
                "OAL_Service:V", "OAL_TcpAdv:V",
                "OAL_Proxy:V", "OAL_BtAutoStart:V", "OAL_WifiJob:V",
                "OAL_WifiRx:V", "OAL_Trigger:V", "OAL_FileLog:V",
                "WifiStateMachine:I", "ConnectivityService:I",
                "NetworkAgent:I", "WifiManager:I",
                "*:S" // silence everything else
            )
        }

        scope.launch {
            try {
                // Clear logcat buffer first, then stream new lines
                Runtime.getRuntime().exec(arrayOf("logcat", "-c")).waitFor()
                Log.i(TAG, "Logcat capture starting (verbose=$verbose)")

                val process = Runtime.getRuntime().exec(logcatCmd)
                logcatProcess = process

                val lcWriter = BufferedWriter(
                    OutputStreamWriter(FileOutputStream(logcatFile, true), Charsets.UTF_8),
                    8192
                )
                logcatWriter = lcWriter

                val reader = BufferedReader(InputStreamReader(process.inputStream))
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    lcWriter.write(line)
                    lcWriter.newLine()
                    // Flush periodically (every line is fine for debug logs)
                    lcWriter.flush()
                }
            } catch (_: Exception) {
                // Process killed or scope cancelled — expected on stop
            } finally {
                try { logcatWriter?.close() } catch (_: Exception) { }
                logcatWriter = null
            }
        }
    }

    private fun stopLogcatCapture() {
        logcatProcess?.destroy()
        logcatProcess = null
        logcatScope?.cancel()
        logcatScope = null
        try { logcatWriter?.close() } catch (_: Exception) { }
        logcatWriter = null
    }

    // ── Storage resolution ─────────────────────────────────────────────

    private fun resolveLogDir(): File? {
        val primary = context.getExternalFilesDir(null)
        if (primary != null) {
            val logDir = File(primary, DIR_NAME)
            if (ensureDir(logDir)) return logDir
        }
        // Fallback to internal storage
        val internal = File(context.filesDir, DIR_NAME)
        if (ensureDir(internal)) return internal
        return null
    }

    private fun ensureDir(dir: File): Boolean {
        if (!dir.exists() && !dir.mkdirs()) return false
        return try {
            val stat = StatFs(dir.absolutePath)
            stat.availableBytes > MIN_FREE_SPACE_BYTES
        } catch (_: Exception) {
            false
        }
    }
}
