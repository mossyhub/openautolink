package com.openautolink.app.ui.diagnostics.carplay

import android.app.Application
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
import java.net.ServerSocket
import java.net.Socket

private const val TAG = "CarPlayRecon"

data class CarPlayReconUiState(
    val scanRunning: Boolean = false,
    val probes: List<ReconProbeResult> = emptyList(),
    val fullReport: String = "",
    val tcpTransferActive: Boolean = false,
    val tcpTransferStatus: String = "",
    val apkExtractStatus: String = "",
    val allApks: List<ApkEntry> = emptyList(),
    val apkCatalogScanning: Boolean = false,
    val apkBulkTransferProgress: String = "",
)

class CarPlayReconViewModel(application: Application) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(CarPlayReconUiState())
    val uiState: StateFlow<CarPlayReconUiState> = _uiState.asStateFlow()

    private var scanJob: Job? = null
    private var tcpServerJob: Job? = null

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

    /**
     * Start a TCP server on the given port that serves the recon report
     * and can transfer APK files on request.
     *
     * Protocol:
     * - Client connects → server sends full text report → closes
     * - Or client sends "GET_APK:<package_name>\n" → server sends APK binary
     */
    fun startTcpServer(port: Int = 5289) {
        if (tcpServerJob != null) return
        tcpServerJob = viewModelScope.launch(Dispatchers.IO) {
            _uiState.update { it.copy(tcpTransferActive = true, tcpTransferStatus = "Listening on :$port") }
            OalLog.i(TAG, "Recon TCP server starting on port $port")
            try {
                val server = ServerSocket(port)
                server.soTimeout = 0 // block indefinitely
                while (true) {
                    val client: Socket = server.accept()
                    _uiState.update { it.copy(tcpTransferStatus = "Client connected: ${client.inetAddress}") }
                    OalLog.i(TAG, "Recon TCP client connected: ${client.inetAddress}")
                    launch(Dispatchers.IO) {
                        handleTcpClient(client)
                    }
                }
            } catch (e: Exception) {
                OalLog.e(TAG, "TCP server error: ${e.message}")
                _uiState.update { it.copy(tcpTransferActive = false, tcpTransferStatus = "Error: ${e.message}") }
            }
        }
    }

    fun stopTcpServer() {
        tcpServerJob?.cancel()
        tcpServerJob = null
        _uiState.update { it.copy(tcpTransferActive = false, tcpTransferStatus = "Stopped") }
    }

    fun scanAllApks() {
        viewModelScope.launch {
            _uiState.update { it.copy(apkCatalogScanning = true) }
            val apks = CarPlayReconScanner.scanAllApks(getApplication())
            _uiState.update { it.copy(allApks = apks, apkCatalogScanning = false) }
            OalLog.i(TAG, "APK catalog: ${apks.size} packages, ${apks.count { it.readable }} readable")
        }
    }

    private suspend fun handleTcpClient(client: Socket) {
        try {
            client.soTimeout = 5000
            val input = client.getInputStream()
            val output = client.getOutputStream()

            // Read the first line (if any) within timeout
            val firstLine = try {
                val buf = ByteArray(1024)
                val n = input.read(buf)
                if (n > 0) String(buf, 0, n).trim() else ""
            } catch (_: java.net.SocketTimeoutException) {
                "" // No data — send report by default
            }

            if (firstLine == "GET_ALL_APKS") {
                // Bulk APK transfer mode
                transferAllApks(output)
            } else if (firstLine.startsWith("GET_APK:")) {
                // Single APK transfer mode
                val packageName = firstLine.removePrefix("GET_APK:").trim()
                transferApk(packageName, output)
            } else if (firstLine == "LIST_APKS") {
                // Send APK catalog as text
                val apks = _uiState.value.allApks
                if (apks.isEmpty()) {
                    output.write("No APK catalog available. Run 'Scan All APKs' first.\n".toByteArray())
                } else {
                    val listing = buildApkCatalogText(apks)
                    output.write(listing.toByteArray())
                }
            } else {
                // Default: send the full text report
                val report = _uiState.value.fullReport
                if (report.isEmpty()) {
                    output.write("No report available. Run scan first.\n".toByteArray())
                } else {
                    output.write(report.toByteArray())
                }
            }

            output.flush()
            client.close()
            _uiState.update { it.copy(tcpTransferStatus = "Client served, listening...") }
        } catch (e: Exception) {
            OalLog.e(TAG, "TCP client handler error: ${e.message}")
        } finally {
            try { client.close() } catch (_: Exception) {}
        }
    }

    private fun transferApk(packageName: String, output: java.io.OutputStream) {
        try {
            val context = getApplication<Application>()
            val pm = context.packageManager
            val ai = pm.getApplicationInfo(packageName, 0)
            val apkFile = File(ai.sourceDir)
            if (!apkFile.canRead()) {
                output.write("ERROR: APK not readable: ${ai.sourceDir}\n".toByteArray())
                _uiState.update { it.copy(apkExtractStatus = "Error: APK not readable") }
                return
            }

            val header = "APK:${apkFile.name}:${apkFile.length()}\n"
            output.write(header.toByteArray())
            _uiState.update { it.copy(apkExtractStatus = "Transferring ${apkFile.name} (${apkFile.length()} bytes)") }
            OalLog.i(TAG, "Transferring APK: ${apkFile.absolutePath} (${apkFile.length()} bytes)")

            FileInputStream(apkFile).use { fis ->
                val buf = ByteArray(65536)
                var totalSent = 0L
                while (true) {
                    val n = fis.read(buf)
                    if (n <= 0) break
                    output.write(buf, 0, n)
                    totalSent += n
                }
                _uiState.update { it.copy(apkExtractStatus = "Sent $totalSent bytes of ${apkFile.name}") }
                OalLog.i(TAG, "APK transfer complete: $totalSent bytes")
            }

            // Also transfer native libs if present
            val libDir = File(ai.nativeLibraryDir)
            if (libDir.exists() && libDir.isDirectory) {
                val libs = libDir.listFiles()?.filter { it.canRead() } ?: emptyList()
                for (lib in libs) {
                    val libHeader = "LIB:${lib.name}:${lib.length()}\n"
                    output.write(libHeader.toByteArray())
                    FileInputStream(lib).use { fis ->
                        val buf = ByteArray(65536)
                        while (true) {
                            val n = fis.read(buf)
                            if (n <= 0) break
                            output.write(buf, 0, n)
                        }
                    }
                    OalLog.i(TAG, "Lib transfer: ${lib.name} (${lib.length()} bytes)")
                }
            }

            output.write("DONE\n".toByteArray())
        } catch (e: Exception) {
            OalLog.e(TAG, "APK transfer error: ${e.message}")
            try { output.write("ERROR: ${e.message}\n".toByteArray()) } catch (_: Exception) {}
            _uiState.update { it.copy(apkExtractStatus = "Error: ${e.message}") }
        }
    }

    private fun transferAllApks(output: java.io.OutputStream) {
        val context = getApplication<Application>()
        val pm = context.packageManager
        @Suppress("DEPRECATION")
        val packages = pm.getInstalledPackages(0)
        val readable = packages.filter { pkg ->
            val ai = pkg.applicationInfo ?: return@filter false
            File(ai.sourceDir).canRead()
        }

        // Send manifest first so the client knows what to expect
        val manifest = buildString {
            appendLine("MANIFEST:${readable.size}")
            for (pkg in readable) {
                val ai = pkg.applicationInfo!!
                val size = File(ai.sourceDir).length()
                appendLine("${pkg.packageName}:${size}")
            }
            appendLine("END_MANIFEST")
        }
        output.write(manifest.toByteArray())
        output.flush()

        var transferred = 0
        for (pkg in readable) {
            val ai = pkg.applicationInfo!!
            val apkFile = File(ai.sourceDir)
            try {
                _uiState.update {
                    it.copy(apkBulkTransferProgress = "Sending ${transferred + 1}/${readable.size}: ${pkg.packageName}")
                }
                val header = "APK:${pkg.packageName}:${apkFile.length()}\n"
                output.write(header.toByteArray())
                FileInputStream(apkFile).use { fis ->
                    val buf = ByteArray(65536)
                    while (true) {
                        val n = fis.read(buf)
                        if (n <= 0) break
                        output.write(buf, 0, n)
                    }
                }
                output.flush()
                transferred++
                OalLog.i(TAG, "Bulk transfer [$transferred/${readable.size}]: ${pkg.packageName} (${apkFile.length()} bytes)")
            } catch (e: Exception) {
                OalLog.e(TAG, "Bulk transfer error for ${pkg.packageName}: ${e.message}")
                try {
                    output.write("ERROR:${pkg.packageName}:${e.message}\n".toByteArray())
                } catch (_: Exception) { break }
            }
        }
        try {
            output.write("ALL_DONE:$transferred\n".toByteArray())
        } catch (_: Exception) {}
        _uiState.update { it.copy(apkBulkTransferProgress = "Done: $transferred/${readable.size} APKs sent") }
        OalLog.i(TAG, "Bulk APK transfer complete: $transferred/${readable.size}")
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

    override fun onCleared() {
        super.onCleared()
        scanJob?.cancel()
        tcpServerJob?.cancel()
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
