package com.openautolink.app

import android.app.Application
import android.os.Environment
import android.util.Log
import com.openautolink.app.diagnostics.DiagnosticLog
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Application class that installs a global UncaughtExceptionHandler
 * to capture crash stack traces to a file before the process dies.
 *
 * Crash logs are written to the app's internal files directory:
 *   filesDir/oal-crash.txt (primary)
 *   externalFilesDir/Documents/oal-crash.txt (backup, adb-accessible)
 *
 * On next launch, previous crash logs are loaded into DiagnosticLog
 * so they appear in the diagnostics screen without ADB access.
 */
class OalApplication : Application() {

    companion object {
        private const val CRASH_FILE = "oal-crash.txt"
        private const val NATIVE_CRASH_FILE = "oal-native-crash.txt"
        /** Max crash file size — prevents unbounded growth from crash loops. */
        private const val MAX_CRASH_FILE_SIZE = 64 * 1024L // 64 KB
    }

    override fun onCreate() {
        super.onCreate()
        loadPreviousCrash()
        loadPreviousNativeCrash()
        installCrashHandler()
        installNativeCrashHandler()
        // Start VHAL ignition watcher for the lifetime of the process so the
        // wake-handling code knows real ignition state before deciding to
        // auto-connect. Without this, the "ghost wake" AAOS dispatches during
        // shutdown burns a 45s timeout into a dead WiFi.
        com.openautolink.app.input.IgnitionMonitor.start(this)
    }

    /**
     * Load crash report from previous process into DiagnosticLog.
     * Shows as ERROR entries in the diagnostics Logs tab so crashes
     * can be diagnosed without ADB access (critical for in-car AAOS).
     */
    private fun loadPreviousCrash() {
        try {
            // Try internal storage first (always writable), then external
            val crashFile = File(filesDir, CRASH_FILE).takeIf { it.exists() }
                ?: getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS)
                    ?.let { File(it, CRASH_FILE) }?.takeIf { it.exists() }
                ?: return

            val content = crashFile.readText()
            if (content.isBlank()) return

            DiagnosticLog.e("CRASH", "=== Previous crash report found ===")
            // Split into lines and inject each as a separate log entry
            // so the full stack trace is visible in the diagnostics screen
            for (line in content.lines()) {
                if (line.isNotBlank()) {
                    DiagnosticLog.e("CRASH", line)
                }
            }
            DiagnosticLog.e("CRASH", "=== End of crash report ===")

            Log.i("OAL-App", "Previous crash report loaded into diagnostics (${content.length} chars)")

            // Delete after loading — will be recreated if we crash again
            crashFile.delete()
            // Also clean up the other location
            val other = if (crashFile.parent == filesDir.path) {
                getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS)?.let { File(it, CRASH_FILE) }
            } else {
                File(filesDir, CRASH_FILE)
            }
            other?.delete()
        } catch (e: Throwable) {
            Log.w("OAL-App", "Failed to load previous crash: ${e.message}")
        }
    }

    private fun loadPreviousNativeCrash() {
        try {
            val crashFile = File(filesDir, NATIVE_CRASH_FILE).takeIf { it.exists() } ?: return
            val content = crashFile.readText()
            if (content.isBlank()) return

            DiagnosticLog.e("NATIVE-CRASH", "=== Previous NATIVE crash report found ===")
            for (line in content.lines()) {
                if (line.isNotBlank()) {
                    DiagnosticLog.e("NATIVE-CRASH", line)
                }
            }
            DiagnosticLog.e("NATIVE-CRASH", "=== End of native crash report ===")
            Log.i("OAL-App", "Previous native crash report loaded (${content.length} chars)")
            crashFile.delete()
        } catch (e: Throwable) {
            Log.w("OAL-App", "Failed to load previous native crash: ${e.message}")
        }
    }

    private fun installNativeCrashHandler() {
        try {
            com.openautolink.app.transport.aasdk.AasdkNative.nativeInstallCrashHandler(filesDir.absolutePath)
            Log.i("OAL-App", "Native crash handler installed")
        } catch (e: Throwable) {
            Log.w("OAL-App", "Failed to install native crash handler: ${e.message}")
        }
    }

    private fun installCrashHandler() {
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()

        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                val sw = StringWriter()
                val pw = PrintWriter(sw)
                pw.println("=== OpenAutoLink Crash Report ===")
                pw.println("Time: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date())}")
                pw.println("Thread: ${thread.name}")
                pw.println("Android: ${android.os.Build.VERSION.RELEASE} (SDK ${android.os.Build.VERSION.SDK_INT})")
                pw.println("Device: ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}")
                pw.println("SoC: ${android.os.Build.SOC_MANUFACTURER} ${android.os.Build.SOC_MODEL}")
                pw.println()
                throwable.printStackTrace(pw)
                pw.flush()

                val report = sw.toString()

                // Log to logcat (visible via adb logcat)
                Log.e("OAL-CRASH", report)

                // Write to app's internal files dir (always writable)
                val internalFile = File(filesDir, CRASH_FILE)
                appendCrashReport(internalFile, report)

                // Also write to external Documents dir (adb-accessible backup)
                val dir = getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS)
                if (dir != null) {
                    dir.mkdirs()
                    appendCrashReport(File(dir, CRASH_FILE), report)
                }
            } catch (_: Throwable) {
                // Crash handler must not throw
            }

            // Chain to default handler (shows system crash dialog / kills process)
            defaultHandler?.uncaughtException(thread, throwable)
        }
    }

    /**
     * Append crash report to file, capping total size to prevent unbounded
     * growth from crash loops. If the file would exceed [MAX_CRASH_FILE_SIZE],
     * truncate old content first.
     */
    private fun appendCrashReport(file: File, report: String) {
        try {
            if (file.exists() && file.length() > MAX_CRASH_FILE_SIZE) {
                // Truncate — keep only the new report
                file.writeText(report)
            } else {
                file.appendText("\n$report")
            }
        } catch (_: Throwable) {}
    }
}
