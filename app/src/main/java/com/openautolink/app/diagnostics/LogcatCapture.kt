package com.openautolink.app.diagnostics

import android.os.Process
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
 * Captures full logcat output for this process and writes it to a file.
 *
 * This captures ALL log lines (native C++ `__android_log_print`, Kotlin `Log.*`,
 * system framework messages) — not just DiagnosticLog entries. Useful for
 * debugging native-side issues where JNI log bridging isn't available.
 *
 * Runs `logcat --pid=<our PID> -v threadtime` in a background thread and
 * pipes stdout to a file alongside the DiagnosticLog file.
 */
class LogcatCapture {

    companion object {
        private const val TAG = "LogcatCapture"
    }

    @Volatile
    var isActive: Boolean = false
        private set

    @Volatile
    var filePath: String? = null
        private set

    private var process: java.lang.Process? = null
    private var readerThread: Thread? = null

    /**
     * Start capturing logcat to a file in the given directory.
     * Returns the file path on success, null on failure.
     */
    fun start(logDir: File): String? {
        if (isActive) return filePath

        val fileName = "logcat_${SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(Date())}.log"
        val file = File(logDir, fileName)

        return try {
            // Drain what is already buffered BEFORE clearing.
            //
            // The buffer holds whatever happened before this capture started —
            // including the tail of a previous run that crashed or was killed,
            // which is exactly the evidence that is otherwise unreachable
            // without adb. Clearing first threw it away every time.
            runCatching {
                val dump = Runtime.getRuntime().exec(arrayOf(
                    "logcat", "-d", "--pid=${Process.myPid()}", "-v", "threadtime"
                ))
                dump.inputStream.bufferedReader().use { r ->
                    FileOutputStream(file, true).bufferedWriter().use { w ->
                        w.write("--- buffered before capture started ---\n")
                        r.copyTo(w)
                        w.write("--- live capture begins ---\n")
                    }
                }
                dump.waitFor()
            }

            // Now clear, so the live capture below does not duplicate it.
            Runtime.getRuntime().exec(arrayOf("logcat", "-c")).waitFor()

            val pid = Process.myPid()
            val proc = Runtime.getRuntime().exec(arrayOf(
                "logcat",
                "--pid=$pid",
                "-v", "threadtime"
            ))
            process = proc
            filePath = file.absolutePath
            isActive = true

            // Reader thread — pipes logcat stdout to file
            readerThread = Thread({
                try {
                    val reader = BufferedReader(InputStreamReader(proc.inputStream))
                    val writer = BufferedWriter(
                        OutputStreamWriter(FileOutputStream(file, true), Charsets.UTF_8),
                        8192
                    )
                    writer.use { w ->
                        reader.use { r ->
                            var line = r.readLine()
                            while (line != null && isActive) {
                                w.write(line)
                                w.newLine()
                                w.flush()
                                // Tee only the already-owned live --pid stream. Do not start a
                                // second logcat, drain historical dumps, or acquire permissions.
                                runCatching { com.openautolink.app.diagnostics.live.LiveLogSink.logcat(line!!) }
                                line = r.readLine()
                            }
                        }
                    }
                } catch (_: Exception) {
                    // Process destroyed or IO error — expected on stop
                }
            }, "logcat-capture").apply {
                isDaemon = true
                start()
            }

            OalLog.i(TAG, "Logcat capture started: ${file.absolutePath}")
            file.absolutePath
        } catch (e: Exception) {
            OalLog.e(TAG, "Failed to start logcat capture: ${e.message}")
            isActive = false
            null
        }
    }

    /**
     * Stop capturing and close the file.
     */
    fun stop() {
        if (!isActive) return
        isActive = false

        try {
            process?.destroy()
        } catch (_: Exception) { }
        process = null

        try {
            readerThread?.interrupt()
        } catch (_: Exception) { }
        readerThread = null

        OalLog.i(TAG, "Logcat capture stopped: $filePath")
        filePath = null
    }
}
