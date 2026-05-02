package com.openautolink.app.ui.diagnostics.carplay

import android.app.Application
import android.os.Environment
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.openautolink.app.diagnostics.OalLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

private const val TAG = "CarPlayRecon"
private const val RECON_DIR = "openautolink/recon"

data class CarPlayReconUiState(
    val scanRunning: Boolean = false,
    val probes: List<ReconProbeResult> = emptyList(),
    val fullReport: String = "",
    val allApks: List<ApkEntry> = emptyList(),
    val apkCatalogScanning: Boolean = false,
    val saveStatus: String = "",
    val saveProgress: String = "",
)

class CarPlayReconViewModel(application: Application) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(CarPlayReconUiState())
    val uiState: StateFlow<CarPlayReconUiState> = _uiState.asStateFlow()

    private var scanJob: Job? = null
    private var saveJob: Job? = null

    fun runFullScan() {
        if (_uiState.value.scanRunning) return
        scanJob?.cancel()
        scanJob = viewModelScope.launch {
            val context = getApplication<Application>()
            _uiState.update { it.copy(scanRunning = true, probes = emptyList(), fullReport = "") }
            OalLog.i(TAG, "Starting full CarPlay recon scan")

            val results = mutableListOf<ReconProbeResult>()
            val probes: List<Pair<String, suspend () -> ReconProbeResult>> = listOf(
                "Packages" to { CarPlayReconScanner.scanPackages(context) },
                "Properties" to { CarPlayReconScanner.scanProperties() },
                "Filesystem" to { CarPlayReconScanner.scanFilesystem() },
                "Processes" to { CarPlayReconScanner.scanProcesses() },
                "Services" to { CarPlayReconScanner.scanServices() },
                "USB Devices" to { CarPlayReconScanner.scanUsbDevices(context) },
                "Service Binds" to { CarPlayReconScanner.attemptServiceBinds(context) },
                "Intents" to { CarPlayReconScanner.probeIntents(context) },
                "Features" to { CarPlayReconScanner.scanFeatures(context) },
                "APK Paths" to { CarPlayReconScanner.scanApkPaths(context) },
                "I²C MFi Chip" to { CarPlayReconScanner.probeI2cMfiChip() },
            )

            // Show all probes as pending
            _uiState.update {
                it.copy(probes = probes.map { (name, _) ->
                    ReconProbeResult(name, ProbeStatus.PENDING, "")
                })
            }

            for ((name, probe) in probes) {
                // Mark current probe as running
                _uiState.update { state ->
                    state.copy(probes = state.probes.map { p ->
                        if (p.name == name) p.copy(status = ProbeStatus.RUNNING) else p
                    })
                }

                val result = try {
                    probe()
                } catch (e: Exception) {
                    ReconProbeResult(name, ProbeStatus.ERROR, "Unexpected error: ${e.message}")
                }
                results.add(result)

                // Update probe result
                _uiState.update { state ->
                    state.copy(probes = state.probes.map { p ->
                        if (p.name == name) result else p
                    })
                }
            }

            // Build full report
            val header = CarPlayReconScanner.buildReportHeader(context)
            val report = buildString {
                append(header)
                for (result in results) {
                    appendLine("--- ${result.name.uppercase()} ---")
                    appendLine(result.output)
                    appendLine()
                }
            }

            _uiState.update { it.copy(scanRunning = false, fullReport = report) }
            OalLog.i(TAG, "Full scan complete (${results.size} probes, report ${report.length} chars)")
        }
    }

    fun scanAllApks() {
        viewModelScope.launch {
            _uiState.update { it.copy(apkCatalogScanning = true) }
            val apks = CarPlayReconScanner.scanAllApks(getApplication())
            _uiState.update { it.copy(allApks = apks, apkCatalogScanning = false) }
            OalLog.i(TAG, "APK catalog: ${apks.size} packages, ${apks.count { it.readable }} readable")
        }
    }

    /**
     * Save the full report + all readable APKs to USB drive (or fallback storage).
     * Uses the same removable-storage-first pattern as FileLogWriter.
     */
    fun saveToUsb() {
        if (saveJob?.isActive == true) return
        saveJob = viewModelScope.launch(Dispatchers.IO) {
            val context = getApplication<Application>()
            val reconDir = resolveReconDir(context)
            if (reconDir == null) {
                _uiState.update { it.copy(saveStatus = "No writable storage found (USB or internal)") }
                OalLog.e(TAG, "No writable storage found")
                return@launch
            }

            val isRemovable = try {
                Environment.isExternalStorageRemovable(reconDir)
            } catch (_: Exception) { false }
            val storageLabel = if (isRemovable) "USB drive" else "internal storage"
            _uiState.update { it.copy(saveStatus = "Saving to $storageLabel: ${reconDir.absolutePath}") }
            OalLog.i(TAG, "Saving recon to: ${reconDir.absolutePath} ($storageLabel)")

            // 1. Save text report
            val report = _uiState.value.fullReport
            if (report.isNotEmpty()) {
                try {
                    val reportFile = File(reconDir, "recon-report.txt")
                    writeTextSynced(reportFile, report)
                    _uiState.update { it.copy(saveProgress = "Report saved (${formatSizeVm(reportFile.length())})") }
                    OalLog.i(TAG, "Report saved: ${reportFile.absolutePath}")
                } catch (e: Exception) {
                    _uiState.update { it.copy(saveProgress = "Report save failed: ${e.message}") }
                    OalLog.e(TAG, "Report save failed: ${e.message}")
                }
            }

            // 2. Save APK catalog
            val apks = _uiState.value.allApks
            if (apks.isNotEmpty()) {
                try {
                    val catalogFile = File(reconDir, "apk-catalog.txt")
                    writeTextSynced(catalogFile, buildApkCatalogText(apks))
                    OalLog.i(TAG, "APK catalog saved: ${catalogFile.absolutePath}")
                } catch (e: Exception) {
                    OalLog.e(TAG, "Catalog save failed: ${e.message}")
                }
            }

            // 3. Copy all readable APKs
            val readable = apks.filter { it.readable }
            if (readable.isEmpty()) {
                // If no catalog was scanned, scan now
                val freshApks = CarPlayReconScanner.scanAllApks(context)
                _uiState.update { it.copy(allApks = freshApks) }
                copyApks(freshApks.filter { it.readable }, reconDir)
            } else {
                copyApks(readable, reconDir)
            }

            // Final flush of the directory itself so the FAT/exFAT directory entries
            // are committed before we tell the user it's safe to unplug.
            try { java.io.FileOutputStream(File(reconDir, ".sync")).use { it.fd.sync() } } catch (_: Exception) {}
            try { File(reconDir, ".sync").delete() } catch (_: Exception) {}

            val totalFiles = reconDir.listFiles()?.size ?: 0
            val totalSize = reconDir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
            val ejectMsg = if (isRemovable) "  ⚠ EJECT before unplugging: Settings → Storage → eject the drive." else ""
            _uiState.update {
                it.copy(
                    saveStatus = "Done! $totalFiles files saved to $storageLabel (${formatSizeVm(totalSize)}).$ejectMsg",
                    saveProgress = "Path: ${reconDir.absolutePath}",
                )
            }
            OalLog.i(TAG, "Recon save complete: $totalFiles files, ${formatSizeVm(totalSize)} at ${reconDir.absolutePath}")
        }
    }

    /** Write text and force a kernel sync to physical media. */
    private fun writeTextSynced(file: File, text: String) {
        java.io.FileOutputStream(file).use { fos ->
            fos.write(text.toByteArray(Charsets.UTF_8))
            fos.flush()
            try { fos.fd.sync() } catch (_: Exception) {}
        }
    }

    private suspend fun copyApks(apks: List<ApkEntry>, reconDir: File) {
        val apkDir = File(reconDir, "apks")
        apkDir.mkdirs()
        var copied = 0
        var errors = 0

        for (apk in apks) {
            try {
                _uiState.update {
                    it.copy(saveProgress = "Copying APK ${copied + 1}/${apks.size}: ${apk.packageName}")
                }
                val srcFile = File(apk.apkPath)
                val safeName = apk.packageName.replace('.', '_')
                val destFile = File(apkDir, "$safeName.apk")
                FileInputStream(srcFile).use { input ->
                    FileOutputStream(destFile).use { output ->
                        val buf = ByteArray(65536)
                        while (true) {
                            val n = input.read(buf)
                            if (n <= 0) break
                            output.write(buf, 0, n)
                        }
                        output.flush()
                        // CRITICAL: force kernel page cache to physical USB media.
                        // Without this, large files end up truncated when the drive is unplugged.
                        try { output.fd.sync() } catch (_: Exception) {}
                    }
                }
                // Sanity check: did we write the full file?
                if (destFile.length() != srcFile.length()) {
                    errors++
                    OalLog.e(TAG, "Size mismatch for ${apk.packageName}: src=${srcFile.length()} dst=${destFile.length()}")
                    continue
                }
                copied++

                // Copy native libs if present
                if (apk.nativeLibs.isNotEmpty()) {
                    val libSrcDir = File(apk.apkPath).parentFile?.let { File(it, "lib") }
                        ?: continue
                    // Get the actual native lib dir from PackageManager
                    try {
                        val pm = getApplication<Application>().packageManager
                        val ai = pm.getApplicationInfo(apk.packageName, 0)
                        val nativeDir = File(ai.nativeLibraryDir)
                        if (nativeDir.exists() && nativeDir.isDirectory) {
                            val libDestDir = File(apkDir, "${safeName}_libs")
                            libDestDir.mkdirs()
                            nativeDir.listFiles()?.filter { it.canRead() }?.forEach { lib ->
                                val libDest = File(libDestDir, lib.name)
                                FileInputStream(lib).use { input ->
                                    FileOutputStream(libDest).use { output ->
                                        input.copyTo(output)
                                        output.flush()
                                        try { output.fd.sync() } catch (_: Exception) {}
                                    }
                                }
                            }
                        }
                    } catch (_: Exception) {}
                }

                OalLog.i(TAG, "Copied [$copied/${apks.size}]: ${apk.packageName} (${formatSizeVm(srcFile.length())})")
            } catch (e: Exception) {
                errors++
                OalLog.e(TAG, "Failed to copy ${apk.packageName}: ${e.message}")
            }
        }

        _uiState.update {
            it.copy(saveProgress = "APKs: $copied copied, $errors errors (of ${apks.size})")
        }
    }

    fun buildApkCatalogText(apks: List<ApkEntry>): String = buildString {
        val readable = apks.filter { it.readable }
        val totalSize = readable.sumOf { it.apkSizeBytes }
        appendLine("=== APK Catalog ===")
        appendLine("Total: ${apks.size} packages, ${readable.size} readable")
        appendLine("Total size (readable): ${formatSizeVm(totalSize)}")
        appendLine()
        for (apk in apks) {
            val flag = if (apk.isSystemApp) "[SYS]" else "[USR]"
            val readFlag = if (apk.readable) "✓" else "✗"
            appendLine("$readFlag $flag ${apk.packageName} v${apk.versionName ?: "?"} (${formatSizeVm(apk.apkSizeBytes)})")
            if (apk.nativeLibs.isNotEmpty()) {
                appendLine("       libs: ${apk.nativeLibs.joinToString()}")
            }
            if (apk.splitPaths.isNotEmpty()) {
                appendLine("       splits: ${apk.splitPaths.size}")
            }
        }
    }

    /**
     * Find the best directory for recon output.
     * Prefers removable storage (USB stick) over internal shared storage.
     */
    private fun resolveReconDir(context: android.content.Context): File? {
        val externalDirs = context.getExternalFilesDirs(null)
        for (dir in externalDirs) {
            if (dir == null) continue
            if (Environment.isExternalStorageRemovable(dir)) {
                val reconDir = File(dir, RECON_DIR)
                if (reconDir.exists() || reconDir.mkdirs()) return reconDir
            }
        }
        // Fallback: primary external storage
        val primary = context.getExternalFilesDir(null)
        if (primary != null) {
            val reconDir = File(primary, RECON_DIR)
            if (reconDir.exists() || reconDir.mkdirs()) return reconDir
        }
        return null
    }

    override fun onCleared() {
        super.onCleared()
        scanJob?.cancel()
        saveJob?.cancel()
    }

    companion object {
        fun formatSizeVm(bytes: Long): String = when {
            bytes < 1024 -> "$bytes B"
            bytes < 1024 * 1024 -> "${"%.1f".format(bytes / 1024.0)} KB"
            bytes < 1024 * 1024 * 1024 -> "${"%.1f".format(bytes / (1024.0 * 1024.0))} MB"
            else -> "${"%.2f".format(bytes / (1024.0 * 1024.0 * 1024.0))} GB"
        }
    }
}
