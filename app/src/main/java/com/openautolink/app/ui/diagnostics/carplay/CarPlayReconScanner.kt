package com.openautolink.app.ui.diagnostics.carplay

import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.IBinder
import com.openautolink.app.diagnostics.OalLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import kotlin.coroutines.resume

private const val TAG = "CarPlayRecon"

/** Matches for package/process/file name filtering */
private val INTERESTING_PATTERNS = listOf(
    "carplay", "apple", "iap2", "iap", "mfi", "projection",
)

/** System property prefixes to dump */
private val PROP_PREFIXES = listOf(
    "ro.vendor.carplay", "persist.vendor.carplay",
    "ro.vendor.iap", "persist.sys.carplay",
    "ro.vehicle", "ro.boot.carline",
    "ro.build.display", "ro.product.model",
    "ro.hardware", "ro.board.platform",
    "persist.vendor.apple", "persist.sys.projection",
    "ro.vendor.projection", "persist.vendor.projection",
)

/** Filesystem paths to scan (world-readable) */
private val SCAN_DIRS = listOf(
    "/system/etc/init",
    "/vendor/etc/init",
    "/odm/etc/init",
    "/system/etc/permissions",
    "/vendor/etc/permissions",
    "/vendor/etc",
)

/** Lib dirs to scan for interesting .so files */
private val LIB_DIRS = listOf(
    "/system/lib64",
    "/system/lib",
    "/vendor/lib64",
    "/vendor/lib",
    "/odm/lib64",
    "/odm/lib",
)

/** Hardware features to check */
private val FEATURES_TO_CHECK = listOf(
    "com.apple.carplay",
    "com.gm.carplay",
    "android.hardware.usb.accessory",
    "android.hardware.usb.host",
    "android.software.companion_device_setup",
)

data class ReconProbeResult(
    val name: String,
    val status: ProbeStatus,
    val output: String,
)

enum class ProbeStatus { PENDING, RUNNING, DONE, ERROR }

data class ApkEntry(
    val packageName: String,
    val versionName: String?,
    val apkPath: String,
    val apkSizeBytes: Long,
    val readable: Boolean,
    val isSystemApp: Boolean,
    val splitPaths: List<String> = emptyList(),
    val nativeLibs: List<String> = emptyList(),
)

/**
 * Stateless scanner that runs each CarPlay recon probe as a suspend function.
 * All probes are read-only — no modifications to any system state.
 */
object CarPlayReconScanner {

    // --- 1. Package Scan ---
    @SuppressLint("QueryPermissionsNeeded")
    suspend fun scanPackages(context: Context): ReconProbeResult = withContext(Dispatchers.IO) {
        try {
            val pm = context.packageManager
            @Suppress("DEPRECATION")
            val flags = PackageManager.GET_ACTIVITIES or
                    PackageManager.GET_SERVICES or
                    PackageManager.GET_RECEIVERS or
                    PackageManager.GET_PROVIDERS or
                    PackageManager.GET_PERMISSIONS
            val packages = pm.getInstalledPackages(flags)
            val matches = packages.filter { pkg ->
                INTERESTING_PATTERNS.any { pattern ->
                    pkg.packageName.contains(pattern, ignoreCase = true)
                }
            }

            if (matches.isEmpty()) {
                return@withContext ReconProbeResult("Packages", ProbeStatus.DONE,
                    "No packages matching ${INTERESTING_PATTERNS.joinToString()} found.\n" +
                    "Total installed: ${packages.size}")
            }

            val sb = StringBuilder()
            sb.appendLine("Found ${matches.size} matching package(s) (of ${packages.size} total):")
            sb.appendLine()
            for (pkg in matches) {
                sb.appendLine("=== ${pkg.packageName} ===")
                sb.appendLine("  Version: ${pkg.versionName} (${pkg.longVersionCode})")
                pkg.applicationInfo?.let { ai ->
                    sb.appendLine("  sourceDir: ${ai.sourceDir}")
                    sb.appendLine("  nativeLibDir: ${ai.nativeLibraryDir}")
                    sb.appendLine("  dataDir: ${ai.dataDir}")
                    sb.appendLine("  enabled: ${ai.enabled}")
                    // List APK size
                    try {
                        val apkFile = File(ai.sourceDir)
                        if (apkFile.exists()) {
                            sb.appendLine("  APK size: ${formatSize(apkFile.length())}")
                        }
                    } catch (_: Exception) {}
                    // List native libs
                    try {
                        val libDir = File(ai.nativeLibraryDir)
                        if (libDir.exists() && libDir.isDirectory) {
                            val libs = libDir.listFiles()
                            if (libs != null && libs.isNotEmpty()) {
                                sb.appendLine("  Native libs (${libs.size}):")
                                for (lib in libs.sortedBy { it.name }) {
                                    sb.appendLine("    ${lib.name} (${formatSize(lib.length())})")
                                }
                            } else {
                                sb.appendLine("  Native libs: (empty)")
                            }
                        }
                    } catch (_: Exception) {}
                }
                // Activities
                pkg.activities?.let { acts ->
                    sb.appendLine("  Activities (${acts.size}):")
                    for (a in acts) {
                        sb.appendLine("    ${a.name} [exported=${a.exported}]")
                    }
                }
                // Services
                pkg.services?.let { svcs ->
                    sb.appendLine("  Services (${svcs.size}):")
                    for (s in svcs) {
                        sb.appendLine("    ${s.name} [exported=${s.exported}]")
                    }
                }
                // Receivers
                pkg.receivers?.let { rcvs ->
                    sb.appendLine("  Receivers (${rcvs.size}):")
                    for (r in rcvs) {
                        sb.appendLine("    ${r.name} [exported=${r.exported}]")
                    }
                }
                // Providers
                pkg.providers?.let { provs ->
                    sb.appendLine("  Providers (${provs.size}):")
                    for (p in provs) {
                        sb.appendLine("    ${p.name} [exported=${p.exported}] authority=${p.authority}")
                    }
                }
                // Permissions declared
                pkg.permissions?.let { perms ->
                    sb.appendLine("  Permissions declared (${perms.size}):")
                    for (p in perms) {
                        sb.appendLine("    ${p.name} [protection=${p.protectionLevel}]")
                    }
                }
                // Permissions requested
                pkg.requestedPermissions?.let { perms ->
                    sb.appendLine("  Permissions requested (${perms.size}):")
                    for (p in perms) {
                        sb.appendLine("    $p")
                    }
                }
                sb.appendLine()
            }
            ReconProbeResult("Packages", ProbeStatus.DONE, sb.toString())
        } catch (e: Exception) {
            OalLog.e(TAG, "Package scan failed: ${e.message}")
            ReconProbeResult("Packages", ProbeStatus.ERROR, "Error: ${e.message}\n${e.stackTraceToString()}")
        }
    }

    // --- 2. System Properties ---
    suspend fun scanProperties(): ReconProbeResult = withContext(Dispatchers.IO) {
        try {
            val proc = Runtime.getRuntime().exec(arrayOf("getprop"))
            val reader = BufferedReader(InputStreamReader(proc.inputStream))
            val allProps = reader.readLines()
            proc.waitFor()
            reader.close()

            val filtered = allProps.filter { line ->
                PROP_PREFIXES.any { prefix -> line.contains(prefix, ignoreCase = true) }
            }.sorted()

            val sb = StringBuilder()
            sb.appendLine("Filtered properties (${filtered.size} matches from ${allProps.size} total):")
            sb.appendLine()
            for (line in filtered) {
                sb.appendLine(line)
            }

            // Also dump all props with "carplay" or "apple" or "mfi" anywhere
            val extraMatches = allProps.filter { line ->
                INTERESTING_PATTERNS.any { line.contains(it, ignoreCase = true) }
            }.sorted()
            val newExtras = extraMatches.filter { it !in filtered }
            if (newExtras.isNotEmpty()) {
                sb.appendLine()
                sb.appendLine("Additional matches (keyword search):")
                for (line in newExtras) {
                    sb.appendLine(line)
                }
            }

            ReconProbeResult("Properties", ProbeStatus.DONE, sb.toString())
        } catch (e: Exception) {
            OalLog.e(TAG, "Property scan failed: ${e.message}")
            ReconProbeResult("Properties", ProbeStatus.ERROR, "Error: ${e.message}")
        }
    }

    // --- 3. Filesystem Scan ---
    suspend fun scanFilesystem(): ReconProbeResult = withContext(Dispatchers.IO) {
        val sb = StringBuilder()
        try {
            // Scan init directories for .rc files mentioning carplay/iap/mfi
            for (dir in SCAN_DIRS) {
                sb.appendLine("--- $dir ---")
                try {
                    val dirFile = File(dir)
                    if (!dirFile.exists()) {
                        sb.appendLine("  (does not exist)")
                        sb.appendLine()
                        continue
                    }
                    val files = dirFile.listFiles()
                    if (files == null || files.isEmpty()) {
                        sb.appendLine("  (empty or not readable)")
                        sb.appendLine()
                        continue
                    }
                    // List all files
                    sb.appendLine("  ${files.size} file(s):")
                    for (f in files.sortedBy { it.name }) {
                        sb.appendLine("    ${f.name} (${formatSize(f.length())})")
                    }
                    // Check .rc and .xml files for interesting content
                    val textFiles = files.filter { it.name.endsWith(".rc") || it.name.endsWith(".xml") }
                    for (f in textFiles) {
                        try {
                            val content = f.readText()
                            if (INTERESTING_PATTERNS.any { content.contains(it, ignoreCase = true) }) {
                                sb.appendLine()
                                sb.appendLine("  *** MATCH in ${f.name} ***")
                                sb.appendLine(content.lines().take(100).joinToString("\n") { "    $it" })
                                if (content.lines().size > 100) sb.appendLine("    ... (truncated)")
                            }
                        } catch (_: Exception) {
                            // Not readable
                        }
                    }
                } catch (e: Exception) {
                    sb.appendLine("  Error: ${e.message}")
                }
                sb.appendLine()
            }

            // Scan lib dirs for matching .so files
            sb.appendLine("--- Native Libraries (filtered) ---")
            for (dir in LIB_DIRS) {
                try {
                    val dirFile = File(dir)
                    if (!dirFile.exists()) continue
                    val files = dirFile.listFiles() ?: continue
                    val matches = files.filter { f ->
                        INTERESTING_PATTERNS.any { f.name.contains(it, ignoreCase = true) }
                    }
                    if (matches.isNotEmpty()) {
                        sb.appendLine("  $dir:")
                        for (f in matches.sortedBy { it.name }) {
                            sb.appendLine("    ${f.name} (${formatSize(f.length())})")
                        }
                    }
                } catch (_: Exception) {}
            }

            ReconProbeResult("Filesystem", ProbeStatus.DONE, sb.toString())
        } catch (e: Exception) {
            OalLog.e(TAG, "Filesystem scan failed: ${e.message}")
            ReconProbeResult("Filesystem", ProbeStatus.ERROR, "Error: ${e.message}")
        }
    }

    // --- 4. Process Scan ---
    suspend fun scanProcesses(): ReconProbeResult = withContext(Dispatchers.IO) {
        try {
            val sb = StringBuilder()
            val procDir = File("/proc")
            val allProcs = mutableListOf<Pair<String, String>>() // pid, cmdline
            val highlighted = mutableListOf<Pair<String, String>>()

            procDir.listFiles()?.filter { it.name.matches(Regex("\\d+")) }?.forEach { pidDir ->
                try {
                    val cmdline = File(pidDir, "cmdline").readText().replace('\u0000', ' ').trim()
                    if (cmdline.isNotEmpty()) {
                        allProcs.add(pidDir.name to cmdline)
                        if (INTERESTING_PATTERNS.any { cmdline.contains(it, ignoreCase = true) }) {
                            highlighted.add(pidDir.name to cmdline)
                        }
                    }
                } catch (_: Exception) {}
            }

            sb.appendLine("Total processes readable: ${allProcs.size}")
            sb.appendLine()
            if (highlighted.isNotEmpty()) {
                sb.appendLine("*** MATCHING PROCESSES (${highlighted.size}) ***")
                for ((pid, cmd) in highlighted) {
                    sb.appendLine("  PID $pid: $cmd")
                }
                sb.appendLine()
            } else {
                sb.appendLine("No processes matching ${INTERESTING_PATTERNS.joinToString()}.")
                sb.appendLine()
            }

            // Dump full list for reference
            sb.appendLine("All processes:")
            for ((pid, cmd) in allProcs.sortedBy { it.first.toIntOrNull() ?: 0 }) {
                sb.appendLine("  $pid: $cmd")
            }

            ReconProbeResult("Processes", ProbeStatus.DONE, sb.toString())
        } catch (e: Exception) {
            OalLog.e(TAG, "Process scan failed: ${e.message}")
            ReconProbeResult("Processes", ProbeStatus.ERROR, "Error: ${e.message}")
        }
    }

    // --- 5. Service Manager Query ---
    suspend fun scanServices(): ReconProbeResult = withContext(Dispatchers.IO) {
        val sb = StringBuilder()
        try {
            // Try reflection on ServiceManager.listServices()
            try {
                val smClass = Class.forName("android.os.ServiceManager")
                val listMethod = smClass.getMethod("listServices")
                @Suppress("UNCHECKED_CAST")
                val services = listMethod.invoke(null) as? Array<String>
                if (services != null) {
                    val matches = services.filter { svc ->
                        INTERESTING_PATTERNS.any { svc.contains(it, ignoreCase = true) }
                    }
                    sb.appendLine("ServiceManager.listServices(): ${services.size} total")
                    if (matches.isNotEmpty()) {
                        sb.appendLine()
                        sb.appendLine("*** MATCHING SERVICES (${matches.size}) ***")
                        for (svc in matches.sorted()) {
                            sb.appendLine("  $svc")
                        }
                    } else {
                        sb.appendLine("  No matching services found.")
                    }
                    sb.appendLine()
                    sb.appendLine("All services:")
                    for (svc in services.sorted()) {
                        sb.appendLine("  $svc")
                    }
                }
            } catch (e: Exception) {
                sb.appendLine("ServiceManager reflection failed: ${e.message}")
            }

            // Also try "service list" command
            sb.appendLine()
            sb.appendLine("--- service list (shell) ---")
            try {
                val proc = Runtime.getRuntime().exec(arrayOf("service", "list"))
                val output = BufferedReader(InputStreamReader(proc.inputStream)).readLines()
                proc.waitFor()
                val matches = output.filter { line ->
                    INTERESTING_PATTERNS.any { line.contains(it, ignoreCase = true) }
                }
                sb.appendLine("Total: ${output.size} services")
                if (matches.isNotEmpty()) {
                    sb.appendLine("Matches:")
                    for (line in matches) sb.appendLine("  $line")
                }
            } catch (e: Exception) {
                sb.appendLine("  Error: ${e.message}")
            }

            // Try "dumpsys -l"
            sb.appendLine()
            sb.appendLine("--- dumpsys -l ---")
            try {
                val proc = Runtime.getRuntime().exec(arrayOf("dumpsys", "-l"))
                val output = BufferedReader(InputStreamReader(proc.inputStream)).readLines()
                proc.waitFor()
                val matches = output.filter { line ->
                    INTERESTING_PATTERNS.any { line.contains(it, ignoreCase = true) }
                }
                sb.appendLine("Total: ${output.size} entries")
                if (matches.isNotEmpty()) {
                    sb.appendLine("Matches:")
                    for (line in matches) sb.appendLine("  $line")
                }
            } catch (e: Exception) {
                sb.appendLine("  Error: ${e.message}")
            }

            ReconProbeResult("Services", ProbeStatus.DONE, sb.toString())
        } catch (e: Exception) {
            OalLog.e(TAG, "Service scan failed: ${e.message}")
            ReconProbeResult("Services", ProbeStatus.ERROR, "Error: ${e.message}")
        }
    }

    // --- 6. USB Device Enumeration ---
    suspend fun scanUsbDevices(context: Context): ReconProbeResult = withContext(Dispatchers.IO) {
        try {
            val usbManager = context.getSystemService(Context.USB_SERVICE) as? UsbManager
            if (usbManager == null) {
                return@withContext ReconProbeResult("USB Devices", ProbeStatus.DONE,
                    "UsbManager not available.")
            }

            val devices = usbManager.deviceList
            if (devices.isEmpty()) {
                return@withContext ReconProbeResult("USB Devices", ProbeStatus.DONE,
                    "No USB devices connected.\n(Connect an iPhone and re-scan)")
            }

            val sb = StringBuilder()
            sb.appendLine("${devices.size} USB device(s) connected:")
            sb.appendLine()
            for ((name, device) in devices) {
                sb.appendLine("=== $name ===")
                sb.appendLine("  Vendor ID:  0x${"%04X".format(device.vendorId)} (${device.vendorId})")
                sb.appendLine("  Product ID: 0x${"%04X".format(device.productId)} (${device.productId})")
                sb.appendLine("  Device class/sub/proto: ${device.deviceClass}/${device.deviceSubclass}/${device.deviceProtocol}")
                sb.appendLine("  Manufacturer: ${device.manufacturerName ?: "n/a"}")
                sb.appendLine("  Product:      ${device.productName ?: "n/a"}")
                sb.appendLine("  Serial:       ${device.serialNumber ?: "n/a"}")
                sb.appendLine("  Interfaces (${device.interfaceCount}):")
                for (i in 0 until device.interfaceCount) {
                    val iface = device.getInterface(i)
                    val isVendor = iface.interfaceClass == 0xFF
                    val marker = if (isVendor) " <<<< VENDOR SPECIFIC (iAP2?)" else ""
                    sb.appendLine("    [$i] class=${iface.interfaceClass} sub=${iface.interfaceSubclass} proto=${iface.interfaceProtocol} endpoints=${iface.endpointCount}$marker")
                    for (e in 0 until iface.endpointCount) {
                        val ep = iface.getEndpoint(e)
                        sb.appendLine("      EP $e: addr=0x${"%02X".format(ep.address)} dir=${if (ep.direction == 0) "OUT" else "IN"} type=${ep.type} maxPacket=${ep.maxPacketSize}")
                    }
                }

                // Flag Apple vendor ID (0x05AC)
                if (device.vendorId == 0x05AC) {
                    sb.appendLine("  *** APPLE DEVICE DETECTED (VID 0x05AC) ***")
                }
                sb.appendLine()
            }

            ReconProbeResult("USB Devices", ProbeStatus.DONE, sb.toString())
        } catch (e: Exception) {
            OalLog.e(TAG, "USB scan failed: ${e.message}")
            ReconProbeResult("USB Devices", ProbeStatus.ERROR, "Error: ${e.message}")
        }
    }

    // --- 7. Service Bind Attempts ---
    @SuppressLint("QueryPermissionsNeeded")
    suspend fun attemptServiceBinds(context: Context): ReconProbeResult = withContext(Dispatchers.IO) {
        try {
            val pm = context.packageManager
            @Suppress("DEPRECATION")
            val packages = pm.getInstalledPackages(PackageManager.GET_SERVICES)
            val matchingServices = packages.flatMap { pkg ->
                pkg.services?.filter { svc ->
                    svc.exported && INTERESTING_PATTERNS.any { pattern ->
                        pkg.packageName.contains(pattern, ignoreCase = true) ||
                        svc.name.contains(pattern, ignoreCase = true)
                    }
                }?.map { svc ->
                    ComponentName(pkg.packageName, svc.name)
                } ?: emptyList()
            }

            if (matchingServices.isEmpty()) {
                return@withContext ReconProbeResult("Service Binds", ProbeStatus.DONE,
                    "No exported services matching ${INTERESTING_PATTERNS.joinToString()} to bind to.")
            }

            val sb = StringBuilder()
            sb.appendLine("Attempting to bind ${matchingServices.size} exported service(s):")
            sb.appendLine()

            for (component in matchingServices) {
                sb.appendLine("--- ${component.flattenToShortString()} ---")
                try {
                    val result = withTimeoutOrNull(3000L) {
                        suspendCancellableCoroutine<String> { cont ->
                            val intent = Intent().apply { this.component = component }
                            val conn = object : ServiceConnection {
                                override fun onServiceConnected(name: ComponentName, service: IBinder) {
                                    val desc = try { service.interfaceDescriptor } catch (_: Exception) { "unknown" }
                                    if (cont.isActive) cont.resume("CONNECTED! interface=$desc")
                                    try { context.unbindService(this) } catch (_: Exception) {}
                                }
                                override fun onServiceDisconnected(name: ComponentName) {}
                            }
                            try {
                                val bound = context.bindService(intent, conn, Context.BIND_AUTO_CREATE)
                                if (!bound) {
                                    if (cont.isActive) cont.resume("bindService returned false (service not available)")
                                }
                            } catch (e: SecurityException) {
                                if (cont.isActive) cont.resume("SecurityException: ${e.message}")
                            } catch (e: Exception) {
                                if (cont.isActive) cont.resume("Exception: ${e.message}")
                            }
                            cont.invokeOnCancellation {
                                try { context.unbindService(conn) } catch (_: Exception) {}
                            }
                        }
                    } ?: "TIMEOUT (3s — service may be dead)"
                    sb.appendLine("  Result: $result")
                } catch (e: Exception) {
                    sb.appendLine("  Error: ${e.message}")
                }
                sb.appendLine()
            }

            ReconProbeResult("Service Binds", ProbeStatus.DONE, sb.toString())
        } catch (e: Exception) {
            OalLog.e(TAG, "Bind attempts failed: ${e.message}")
            ReconProbeResult("Service Binds", ProbeStatus.ERROR, "Error: ${e.message}")
        }
    }

    // --- 8. Broadcast / Intent Probe ---
    suspend fun probeIntents(context: Context): ReconProbeResult = withContext(Dispatchers.IO) {
        val sb = StringBuilder()
        try {
            // First, find any activities from matching packages
            val pm = context.packageManager
            @Suppress("DEPRECATION")
            val packages = pm.getInstalledPackages(PackageManager.GET_ACTIVITIES)
            val matchingActivities = packages.flatMap { pkg ->
                pkg.activities?.filter { act ->
                    INTERESTING_PATTERNS.any { pattern ->
                        pkg.packageName.contains(pattern, ignoreCase = true) ||
                        act.name.contains(pattern, ignoreCase = true)
                    }
                }?.map { act ->
                    ComponentName(pkg.packageName, act.name)
                } ?: emptyList()
            }

            // Try starting each activity to capture logcat response
            for (component in matchingActivities) {
                sb.appendLine("--- Activity: ${component.flattenToShortString()} ---")
                try {
                    // Capture logcat before
                    val beforeProc = Runtime.getRuntime().exec(arrayOf("logcat", "-d", "-t", "5"))
                    val beforeLines = BufferedReader(InputStreamReader(beforeProc.inputStream)).readLines()
                    beforeProc.waitFor()

                    // Try resolving (don't actually launch — just check if it resolves)
                    val intent = Intent(Intent.ACTION_MAIN).apply { this.component = component }
                    val resolveInfo = pm.resolveActivity(intent, 0)
                    sb.appendLine("  Resolves: ${resolveInfo != null}")

                    // Capture logcat after
                    val afterProc = Runtime.getRuntime().exec(arrayOf("logcat", "-d", "-t", "30"))
                    val afterLines = BufferedReader(InputStreamReader(afterProc.inputStream)).readLines()
                    afterProc.waitFor()
                    val newLines = afterLines.filter { it !in beforeLines }
                    val relevant = newLines.filter { line ->
                        INTERESTING_PATTERNS.any { line.contains(it, ignoreCase = true) }
                    }
                    if (relevant.isNotEmpty()) {
                        sb.appendLine("  Related logcat:")
                        for (line in relevant.take(20)) {
                            sb.appendLine("    $line")
                        }
                    }
                } catch (e: Exception) {
                    sb.appendLine("  Error: ${e.message}")
                }
                sb.appendLine()
            }

            // Try generic CarPlay broadcasts
            val broadcastActions = listOf(
                "com.gm.carplay.ACTION_CHECK_STATUS",
                "com.apple.carplay.ACTION_STATUS",
                "com.android.car.projection.ACTION_CHECK_STATUS",
            )
            sb.appendLine("--- Broadcast Probes ---")
            for (action in broadcastActions) {
                sb.appendLine("  Sending: $action")
                try {
                    context.sendBroadcast(Intent(action))
                    sb.appendLine("    Sent (no crash)")
                } catch (e: Exception) {
                    sb.appendLine("    Error: ${e.message}")
                }
            }

            // Capture any logcat after broadcasts
            Thread.sleep(500)
            try {
                val proc = Runtime.getRuntime().exec(arrayOf("logcat", "-d", "-t", "30"))
                val output = BufferedReader(InputStreamReader(proc.inputStream)).readLines()
                proc.waitFor()
                val relevant = output.filter { line ->
                    INTERESTING_PATTERNS.any { line.contains(it, ignoreCase = true) }
                }
                if (relevant.isNotEmpty()) {
                    sb.appendLine()
                    sb.appendLine("Logcat after broadcasts (filtered):")
                    for (line in relevant.takeLast(30)) {
                        sb.appendLine("  $line")
                    }
                }
            } catch (_: Exception) {}

            ReconProbeResult("Intents", ProbeStatus.DONE, sb.toString())
        } catch (e: Exception) {
            OalLog.e(TAG, "Intent probe failed: ${e.message}")
            ReconProbeResult("Intents", ProbeStatus.ERROR, "Error: ${e.message}")
        }
    }

    // --- 9. Hardware Feature Check ---
    suspend fun scanFeatures(context: Context): ReconProbeResult = withContext(Dispatchers.IO) {
        try {
            val pm = context.packageManager
            val sb = StringBuilder()

            sb.appendLine("Hardware Feature Checks:")
            for (feature in FEATURES_TO_CHECK) {
                val has = pm.hasSystemFeature(feature)
                sb.appendLine("  $feature: ${if (has) "YES ✓" else "NO"}")
            }

            // Also dump all system features filtered by interesting patterns
            val allFeatures = pm.systemAvailableFeatures
            val matchingFeatures = allFeatures.filter { fi ->
                fi.name != null && INTERESTING_PATTERNS.any { fi.name.contains(it, ignoreCase = true) }
            }
            if (matchingFeatures.isNotEmpty()) {
                sb.appendLine()
                sb.appendLine("Matching system features:")
                for (fi in matchingFeatures) {
                    sb.appendLine("  ${fi.name} (version=${fi.version})")
                }
            }

            // Dump permission XML files
            sb.appendLine()
            sb.appendLine("--- Permission XMLs ---")
            for (dir in listOf("/system/etc/permissions", "/vendor/etc/permissions")) {
                val dirFile = File(dir)
                if (!dirFile.exists()) continue
                val xmlFiles = dirFile.listFiles()?.filter { it.name.endsWith(".xml") } ?: continue
                for (f in xmlFiles) {
                    try {
                        val content = f.readText()
                        if (INTERESTING_PATTERNS.any { content.contains(it, ignoreCase = true) }) {
                            sb.appendLine()
                            sb.appendLine("*** MATCH in $dir/${f.name} ***")
                            sb.appendLine(content)
                        }
                    } catch (_: Exception) {}
                }
            }

            ReconProbeResult("Features", ProbeStatus.DONE, sb.toString())
        } catch (e: Exception) {
            OalLog.e(TAG, "Feature scan failed: ${e.message}")
            ReconProbeResult("Features", ProbeStatus.ERROR, "Error: ${e.message}")
        }
    }

    // --- 10. APK Extraction Info ---
    @SuppressLint("QueryPermissionsNeeded")
    suspend fun scanApkPaths(context: Context): ReconProbeResult = withContext(Dispatchers.IO) {
        try {
            val pm = context.packageManager
            @Suppress("DEPRECATION")
            val packages = pm.getInstalledPackages(0)
            val matches = packages.filter { pkg ->
                INTERESTING_PATTERNS.any { pattern ->
                    pkg.packageName.contains(pattern, ignoreCase = true)
                }
            }

            if (matches.isEmpty()) {
                return@withContext ReconProbeResult("APK Paths", ProbeStatus.DONE,
                    "No matching packages to extract.")
            }

            val sb = StringBuilder()
            sb.appendLine("APK paths for matching packages:")
            sb.appendLine("(These can be extracted via TCP or copied to app storage)")
            sb.appendLine()

            for (pkg in matches) {
                val ai = pkg.applicationInfo ?: continue
                sb.appendLine("=== ${pkg.packageName} ===")
                sb.appendLine("  APK: ${ai.sourceDir}")

                // Check if readable
                val apkFile = File(ai.sourceDir)
                val readable = apkFile.canRead()
                sb.appendLine("  Readable: $readable")
                if (readable) {
                    sb.appendLine("  Size: ${formatSize(apkFile.length())}")
                }

                // Check split APKs
                ai.splitSourceDirs?.let { splits ->
                    sb.appendLine("  Split APKs (${splits.size}):")
                    for (split in splits) {
                        val sf = File(split)
                        sb.appendLine("    $split (${if (sf.canRead()) formatSize(sf.length()) else "not readable"})")
                    }
                }

                // Check native libs dir
                val libDir = File(ai.nativeLibraryDir)
                if (libDir.exists() && libDir.isDirectory) {
                    val libs = libDir.listFiles()
                    if (libs != null && libs.isNotEmpty()) {
                        sb.appendLine("  Native libs:")
                        for (lib in libs.sortedBy { it.name }) {
                            sb.appendLine("    ${lib.name} (${formatSize(lib.length())}) readable=${lib.canRead()}")
                        }
                    }
                }

                // Run strings on the APK to find error messages / IPC hints
                // (we just check if the APK is readable — actual extraction happens via TCP)
                if (readable) {
                    sb.appendLine("  *** APK IS READABLE — can extract via TCP ***")
                }
                sb.appendLine()
            }

            ReconProbeResult("APK Paths", ProbeStatus.DONE, sb.toString())
        } catch (e: Exception) {
            OalLog.e(TAG, "APK path scan failed: ${e.message}")
            ReconProbeResult("APK Paths", ProbeStatus.ERROR, "Error: ${e.message}")
        }
    }

    // --- 11. All APKs Catalog ---
    @SuppressLint("QueryPermissionsNeeded")
    suspend fun scanAllApks(context: Context): List<ApkEntry> = withContext(Dispatchers.IO) {
        try {
            val pm = context.packageManager
            @Suppress("DEPRECATION")
            val packages = pm.getInstalledPackages(0)
            packages.mapNotNull { pkg ->
                val ai = pkg.applicationInfo ?: return@mapNotNull null
                val apkFile = File(ai.sourceDir)
                val isSystem = (ai.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0
                val splits = ai.splitSourceDirs?.toList() ?: emptyList()
                val libs = try {
                    val libDir = File(ai.nativeLibraryDir)
                    if (libDir.exists() && libDir.isDirectory) {
                        libDir.listFiles()?.map { it.name } ?: emptyList()
                    } else emptyList()
                } catch (_: Exception) { emptyList() }

                ApkEntry(
                    packageName = pkg.packageName,
                    versionName = pkg.versionName,
                    apkPath = ai.sourceDir,
                    apkSizeBytes = if (apkFile.exists()) apkFile.length() else 0,
                    readable = apkFile.canRead(),
                    isSystemApp = isSystem,
                    splitPaths = splits,
                    nativeLibs = libs,
                )
            }.sortedBy { it.packageName }
        } catch (e: Exception) {
            OalLog.e(TAG, "All APK scan failed: ${e.message}")
            emptyList()
        }
    }

    // --- 12. I²C MFi Chip Probe ---
    suspend fun probeI2cMfiChip(): ReconProbeResult = withContext(Dispatchers.IO) {
        val sb = StringBuilder()
        try {
            // Check which I²C device nodes exist and their permissions
            sb.appendLine("--- I²C Device Nodes ---")
            for (i in 0..10) {
                val dev = File("/dev/i2c-$i")
                if (dev.exists()) {
                    val canRead = dev.canRead()
                    val canWrite = dev.canWrite()
                    sb.appendLine("  /dev/i2c-$i: exists, read=$canRead, write=$canWrite")
                }
            }
            sb.appendLine()

            // Try to open /dev/i2c-0 (where MFi chip lives per init.carplay.rc)
            // The Apple MFi Authentication Coprocessor is at I²C address 0x10 (16 decimal)
            // Register 0x00 = Device Version, 0x01 = Firmware Version, 0x02-0x03 = Auth Protocol Major/Minor
            // Ref: Apple MFi Accessory Interface Specification
            val i2cDevices = listOf("/dev/i2c-0", "/dev/i2c-1", "/dev/i2c-2")
            val I2C_SLAVE: Long = 0x0703L // ioctl number for setting slave address

            for (devPath in i2cDevices) {
                val devFile = File(devPath)
                if (!devFile.exists()) continue

                sb.appendLine("--- Probing $devPath for MFi chip (addr 0x10) ---")
                try {
                    // Open the I²C bus
                    val fd = android.system.Os.open(devPath, android.system.OsConstants.O_RDWR, 0)
                    sb.appendLine("  Opened $devPath successfully (fd=$fd)")

                    try {
                        // Set slave address to 0x10 (Apple MFi coprocessor)
                        // ioctl(fd, I2C_SLAVE, 0x10)
                        val ioctlMethod = android.system.Os::class.java.getMethod(
                            "ioctl", java.io.FileDescriptor::class.java, Int::class.java, Long::class.java
                        )
                        // Os.ioctl is hidden API; try reflection
                        sb.appendLine("  Attempting ioctl I2C_SLAVE=0x0703, addr=0x10...")
                    } catch (e: NoSuchMethodException) {
                        sb.appendLine("  Os.ioctl not available via reflection")
                    }

                    // Alternative: use Runtime.exec with a small binary or python
                    // For now, try raw read after open — some kernels allow positional reads
                    try {
                        val buf = ByteArray(6)
                        // Try reading directly — won't work without ioctl I2C_SLAVE first,
                        // but will tell us if SELinux blocks even the open
                        val fis = java.io.FileInputStream(devFile)
                        val n = fis.read(buf)
                        fis.close()
                        if (n > 0) {
                            sb.appendLine("  RAW READ: ${n} bytes: ${buf.take(n).joinToString(" ") { "0x%02X".format(it) }}")
                            sb.appendLine("  *** I²C device is readable from our app! ***")
                        } else {
                            sb.appendLine("  RAW READ: returned $n (no data without ioctl slave addr)")
                        }
                    } catch (e: Exception) {
                        sb.appendLine("  RAW READ failed: ${e.javaClass.simpleName}: ${e.message}")
                        if (e.message?.contains("SELinux") == true || e.message?.contains("Permission denied") == true) {
                            sb.appendLine("  *** BLOCKED by SELinux or permissions ***")
                        }
                    }

                    android.system.Os.close(fd)
                    sb.appendLine("  Closed $devPath")
                } catch (e: android.system.ErrnoException) {
                    sb.appendLine("  Open FAILED: ${e.message} (errno=${e.errno})")
                    when (e.errno) {
                        13 -> sb.appendLine("  *** EACCES (13) — Permission denied (SELinux or DAC) ***")
                        2 -> sb.appendLine("  *** ENOENT (2) — Device node does not exist ***")
                        else -> sb.appendLine("  *** errno ${e.errno} ***")
                    }
                } catch (e: Exception) {
                    sb.appendLine("  Open FAILED: ${e.javaClass.simpleName}: ${e.message}")
                }
                sb.appendLine()
            }

            // Also check SELinux mode
            sb.appendLine("--- SELinux Status ---")
            try {
                val proc = Runtime.getRuntime().exec(arrayOf("getenforce"))
                val result = BufferedReader(InputStreamReader(proc.inputStream)).readLine()
                proc.waitFor()
                sb.appendLine("  getenforce: $result")
            } catch (e: Exception) {
                sb.appendLine("  getenforce failed: ${e.message}")
            }

            // Check our app's SELinux context
            try {
                val proc = Runtime.getRuntime().exec(arrayOf("cat", "/proc/self/attr/current"))
                val result = BufferedReader(InputStreamReader(proc.inputStream)).readLine()
                proc.waitFor()
                sb.appendLine("  Our SELinux context: $result")
            } catch (e: Exception) {
                sb.appendLine("  Context read failed: ${e.message}")
            }

            // Check SELinux context of I²C devices
            try {
                val proc = Runtime.getRuntime().exec(arrayOf("ls", "-laZ", "/dev/i2c-0"))
                val result = BufferedReader(InputStreamReader(proc.inputStream)).readLine()
                proc.waitFor()
                sb.appendLine("  /dev/i2c-0 context: $result")
            } catch (e: Exception) {
                sb.appendLine("  ls -Z failed: ${e.message}")
            }

            // Check if the carplay auth property exists
            sb.appendLine()
            sb.appendLine("--- CarPlay Auth Properties ---")
            try {
                val proc = Runtime.getRuntime().exec(arrayOf("getprop", "persist.carplay.auth.url"))
                val result = BufferedReader(InputStreamReader(proc.inputStream)).readLine()
                proc.waitFor()
                sb.appendLine("  persist.carplay.auth.url: ${if (result.isNullOrEmpty()) "(not set — default i2c:///dev/i2c-0:16)" else result}")
            } catch (_: Exception) {}
            try {
                val proc = Runtime.getRuntime().exec(arrayOf("getprop", "persist.sys.projection.carlife"))
                val result = BufferedReader(InputStreamReader(proc.inputStream)).readLine()
                proc.waitFor()
                sb.appendLine("  persist.sys.projection.carlife: ${result ?: "(not set)"}")
            } catch (_: Exception) {}

            ReconProbeResult("I²C MFi Chip", ProbeStatus.DONE, sb.toString())
        } catch (e: Exception) {
            OalLog.e(TAG, "I²C probe failed: ${e.message}")
            ReconProbeResult("I²C MFi Chip", ProbeStatus.ERROR, "Error: ${e.message}\n$sb")
        }
    }

    /** Build the full report header */
    fun buildReportHeader(context: Context): String {
        val sb = StringBuilder()
        sb.appendLine("=== CarPlay Recon Report ===")
        sb.appendLine("Date: ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US).format(java.util.Date())}")
        sb.appendLine("Device: ${android.os.Build.MODEL} / ${android.os.Build.MANUFACTURER} / SDK ${android.os.Build.VERSION.SDK_INT}")
        sb.appendLine("Board: ${android.os.Build.BOARD} / Hardware: ${android.os.Build.HARDWARE}")
        sb.appendLine("Fingerprint: ${android.os.Build.FINGERPRINT}")
        sb.appendLine()
        return sb.toString()
    }

    private fun formatSize(bytes: Long): String = when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "${"%.1f".format(bytes / 1024.0)} KB"
        else -> "${"%.1f".format(bytes / (1024.0 * 1024.0))} MB"
    }
}
