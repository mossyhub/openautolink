package com.openautolink.app.diagnostics

import android.content.Context
import android.content.Intent
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.provider.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Probes the device for debug/ADB capabilities and provides
 * developer settings launch intents.
 */
object DeviceDebugProbe {

    private const val TAG = "DeviceDebugProbe"

    data class PortScanResult(
        val port: Int,
        val label: String,
        val open: Boolean,
        val banner: String? = null,
    )

    data class DebugProperties(
        val adbEnabled: Boolean,
        val adbTcpPort: Int?,
        val usbDebugging: Boolean,
        val debuggable: Boolean,
        val secureAdb: Boolean,
        val buildType: String,
        val buildFingerprint: String,
        val allProps: Map<String, String>,
    )

    /**
     * Scan localhost for common ADB and debug-related ports.
     */
    suspend fun scanAdbPorts(): List<PortScanResult> = withContext(Dispatchers.IO) {
        val ports = listOf(
            5555 to "ADB TCP (default)",
            5037 to "ADB server",
            5554 to "Emulator console",
            5556 to "ADB TCP (alt)",
            5557 to "ADB TCP (alt2)",
            7555 to "ADB WiFi (some OEMs)",
            6555 to "OAL Log Server",
        )

        // Also scan a range around 5555 for non-standard ADB ports
        val extraPorts = (5550..5570).map { it to "Port $it" }
        val allPorts = (ports + extraPorts).distinctBy { it.first }.sortedBy { it.first }

        allPorts.map { (port, label) ->
            try {
                val socket = Socket()
                socket.connect(InetSocketAddress("127.0.0.1", port), 500)
                // Try to read a banner (ADB sends "OKAY" or similar)
                val banner = try {
                    socket.soTimeout = 300
                    val buf = ByteArray(64)
                    val n = socket.getInputStream().read(buf)
                    if (n > 0) String(buf, 0, n, Charsets.UTF_8).trim() else null
                } catch (_: Exception) { null }
                socket.close()
                PortScanResult(port, label, open = true, banner = banner)
            } catch (_: Exception) {
                PortScanResult(port, label, open = false)
            }
        }
    }

    /**
     * Read debug-relevant system properties via `getprop`.
     */
    suspend fun getDebugProperties(): DebugProperties = withContext(Dispatchers.IO) {
        val props = mutableMapOf<String, String>()
        val interestingKeys = listOf(
            "ro.debuggable",
            "ro.secure",
            "ro.adb.secure",
            "persist.adb.tcp.port",
            "service.adb.tcp.port",
            "sys.usb.config",
            "sys.usb.state",
            "init.svc.adbd",
            "ro.build.type",
            "ro.build.display.id",
            "ro.build.fingerprint",
            "ro.product.model",
            "ro.product.manufacturer",
            "ro.hardware",
            "persist.sys.usb.config",
            "ro.oem.key1",
            "ro.com.google.gmsversion",
            "ro.build.version.security_patch",
            "ro.adb.enabled",
            // GM-specific
            "persist.gm.adb.enabled",
            "persist.gm.developer.mode",
            "ro.gm.build.type",
            "ro.gm.infotainment.version",
        )

        try {
            val process = Runtime.getRuntime().exec("getprop")
            val reader = BufferedReader(InputStreamReader(process.inputStream))
            reader.forEachLine { line ->
                // Format: [key]: [value]
                val match = Regex("\\[(.+?)]:\\s*\\[(.+?)]").find(line)
                if (match != null) {
                    val (key, value) = match.destructured
                    if (interestingKeys.any { key.contains(it, ignoreCase = true) } ||
                        key.contains("adb", ignoreCase = true) ||
                        key.contains("debug", ignoreCase = true) ||
                        key.contains("developer", ignoreCase = true) ||
                        key.contains("usb", ignoreCase = true)) {
                        props[key] = value
                    }
                }
            }
            reader.close()
            process.waitFor()
        } catch (e: Exception) {
            props["error"] = "getprop failed: ${e.message}"
        }

        DebugProperties(
            adbEnabled = props["init.svc.adbd"] == "running" ||
                    props["ro.adb.enabled"] == "1" ||
                    props["persist.gm.adb.enabled"] == "1",
            adbTcpPort = props["persist.adb.tcp.port"]?.toIntOrNull()
                ?: props["service.adb.tcp.port"]?.toIntOrNull(),
            usbDebugging = props["sys.usb.config"]?.contains("adb") == true ||
                    props["sys.usb.state"]?.contains("adb") == true,
            debuggable = props["ro.debuggable"] == "1",
            secureAdb = props["ro.adb.secure"] == "1",
            buildType = props["ro.build.type"] ?: "unknown",
            buildFingerprint = props["ro.build.fingerprint"] ?: "unknown",
            allProps = props.toSortedMap(),
        )
    }

    /**
     * Try to check if ADB over WiFi can be enabled via Settings.Global.
     * On most production devices this requires elevated permissions,
     * but it's worth checking the current value.
     */
    fun getAdbWifiStatus(context: Context): String {
        return try {
            val adbEnabled = Settings.Global.getInt(
                context.contentResolver, Settings.Global.ADB_ENABLED, 0
            )
            val adbWifi = try {
                Settings.Global.getInt(
                    context.contentResolver, "adb_wifi_enabled", -1
                )
            } catch (_: Exception) { -1 }
            buildString {
                append("ADB_ENABLED=${adbEnabled}")
                if (adbWifi != -1) append(", adb_wifi_enabled=$adbWifi")
            }
        } catch (e: Exception) {
            "Cannot read: ${e.message}"
        }
    }

    /**
     * Attempt to enable ADB over TCP. Tries multiple approaches:
     * 1. Settings.Global.putInt (needs WRITE_SECURE_SETTINGS)
     * 2. setprop + restart adbd via Runtime.exec (needs shell/root)
     *
     * Returns a log of what was tried and what worked/failed.
     */
    suspend fun tryEnableAdbTcp(context: Context, port: Int = 5555): List<String> = withContext(Dispatchers.IO) {
        val log = mutableListOf<String>()

        // Method 1: Try Settings.Global
        try {
            Settings.Global.putInt(context.contentResolver, Settings.Global.ADB_ENABLED, 1)
            log.add("Settings.Global ADB_ENABLED=1: OK")
        } catch (e: Exception) {
            log.add("Settings.Global ADB_ENABLED: ${e.message}")
        }

        try {
            Settings.Global.putInt(context.contentResolver, "adb_wifi_enabled", 1)
            log.add("Settings.Global adb_wifi_enabled=1: OK")
        } catch (e: Exception) {
            log.add("Settings.Global adb_wifi_enabled: ${e.message}")
        }

        // Method 2: Try setprop
        try {
            val p = Runtime.getRuntime().exec(arrayOf("setprop", "service.adb.tcp.port", port.toString()))
            val exit = p.waitFor()
            log.add("setprop service.adb.tcp.port=$port: exit=$exit")
        } catch (e: Exception) {
            log.add("setprop: ${e.message}")
        }

        // Method 3: Try to restart adbd
        try {
            val p = Runtime.getRuntime().exec(arrayOf("setprop", "ctl.restart", "adbd"))
            val exit = p.waitFor()
            log.add("setprop ctl.restart adbd: exit=$exit")
        } catch (e: Exception) {
            log.add("ctl.restart adbd: ${e.message}")
        }

        // Method 4: Try stop/start via shell
        try {
            val p = Runtime.getRuntime().exec(arrayOf("sh", "-c", "stop adbd; start adbd"))
            val exit = p.waitFor()
            log.add("stop/start adbd: exit=$exit")
        } catch (e: Exception) {
            log.add("stop/start adbd: ${e.message}")
        }

        // Check result
        try {
            val p = Runtime.getRuntime().exec(arrayOf("getprop", "service.adb.tcp.port"))
            val result = p.inputStream.bufferedReader().readText().trim()
            p.waitFor()
            log.add("service.adb.tcp.port is now: ${result.ifEmpty { "(empty)" }}")
        } catch (e: Exception) {
            log.add("getprop check: ${e.message}")
        }

        // Quick TCP check on the port
        try {
            val socket = Socket()
            socket.connect(InetSocketAddress("127.0.0.1", port), 1000)
            socket.close()
            log.add("TCP 127.0.0.1:$port is OPEN after enable attempt")
        } catch (_: Exception) {
            log.add("TCP 127.0.0.1:$port still closed")
        }

        log
    }

    /**
     * Returns a list of Intents to try for opening developer settings.
     * Each pair is (description, intent).
     */
    fun getDeveloperSettingsIntents(): List<Pair<String, Intent>> = listOf(
        // Standard Android Developer Options
        "Android Developer Options" to Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS),

        // AAOS CarDeveloperOptions — verified present on GM Aegean firmware via APK recon.
        // Activity is exported with intent-filter priority 1, no permission required.
        "AAOS CarDeveloperOptions (direct)" to Intent().apply {
            setClassName(
                "com.android.car.developeroptions",
                "com.android.car.developeroptions.CarDevelopmentSettingsDashboardActivity"
            )
        },

        // Direct component for Developer Options
        "Developer Options (component)" to Intent().apply {
            setClassName("com.android.settings", "com.android.settings.DevelopmentSettings")
        },

        // Another common path
        "Dev Options (SubSettings)" to Intent().apply {
            setClassName(
                "com.android.settings",
                "com.android.settings.SubSettings"
            )
            putExtra(
                ":android:show_fragment",
                "com.android.settings.development.DevelopmentSettingsDashboardFragment"
            )
        },

        // GM-specific developer settings
        "GM Developer Settings" to Intent().apply {
            setClassName(
                "com.gm.settings",
                "com.gm.settings.DeveloperSettingsActivity"
            )
        },

        // Another GM path
        "GM System Settings" to Intent().apply {
            setClassName(
                "com.gm.settings",
                "com.gm.settings.SystemSettingsActivity"
            )
        },

        // Generic settings main
        "Android Settings (main)" to Intent(Settings.ACTION_SETTINGS),

        // About phone (to tap build number)
        "About (tap build number 7x)" to Intent(Settings.ACTION_DEVICE_INFO_SETTINGS),

        // Wireless debugging (Android 11+)
        "Wireless Debugging" to Intent("android.settings.WIRELESS_DEBUGGING_SETTINGS"),

        // Try to open USB preferences
        "USB Preferences" to Intent().apply {
            setClassName(
                "com.android.settings",
                "com.android.settings.connecteddevice.usb.UsbDetailsActivity"
            )
        },
    )

    /**
     * Attempt to launch a specific developer settings intent.
     * Returns true if the activity was found and launched.
     */
    fun tryLaunchIntent(context: Context, intent: Intent): Boolean {
        return try {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (intent.resolveActivity(context.packageManager) != null) {
                context.startActivity(intent)
                true
            } else {
                false
            }
        } catch (e: Exception) {
            OalLog.w(TAG, "Failed to launch intent: ${e.message}")
            false
        }
    }

    /**
     * Get device identification info useful for debugging.
     */
    fun getDeviceInfo(): Map<String, String> = mapOf(
        "Manufacturer" to Build.MANUFACTURER,
        "Model" to Build.MODEL,
        "Device" to Build.DEVICE,
        "Product" to Build.PRODUCT,
        "Board" to Build.BOARD,
        "Hardware" to Build.HARDWARE,
        "SOC" to Build.SOC_MODEL,
        "Android" to "${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})",
        "Build ID" to Build.DISPLAY,
        "Build Type" to Build.TYPE,
        "Fingerprint" to Build.FINGERPRINT,
        "Serial" to try { Build.getSerial() } catch (_: Exception) { "restricted" },
    )

    // ── USB Device Enumeration ───────────────────────────────────────

    data class UsbDeviceInfo(
        val name: String,
        val devicePath: String,
        val vendorId: Int,
        val productId: Int,
        val vendorName: String?,
        val productName: String?,
        val deviceClass: String,
        val interfaceCount: Int,
        val interfaces: List<UsbInterfaceInfo>,
        val serialNumber: String?,
        val hasAdbInterface: Boolean,
    )

    data class UsbInterfaceInfo(
        val id: Int,
        val interfaceClass: String,
        val interfaceSubclass: Int,
        val interfaceProtocol: Int,
        val endpointCount: Int,
        val name: String?,
        val isAdb: Boolean,
    )

    /**
     * Enumerate all USB devices visible to the app via UsbManager.
     * No permissions needed for listing — only for opening connections.
     */
    fun enumerateUsbDevices(context: Context): List<UsbDeviceInfo> {
        val usbManager = context.getSystemService(Context.USB_SERVICE) as? UsbManager
            ?: return emptyList()

        return usbManager.deviceList.values.map { device ->
            val interfaces = (0 until device.interfaceCount).map { i ->
                val iface = device.getInterface(i)
                val cls = usbClassToString(iface.interfaceClass)
                // ADB uses class 0xFF (vendor-specific), subclass 0x42, protocol 0x01
                val isAdb = iface.interfaceClass == UsbConstants.USB_CLASS_VENDOR_SPEC &&
                        iface.interfaceSubclass == 0x42 &&
                        iface.interfaceProtocol == 0x01
                UsbInterfaceInfo(
                    id = iface.id,
                    interfaceClass = cls,
                    interfaceSubclass = iface.interfaceSubclass,
                    interfaceProtocol = iface.interfaceProtocol,
                    endpointCount = iface.endpointCount,
                    name = iface.name,
                    isAdb = isAdb,
                )
            }

            val serial = try { device.serialNumber } catch (_: Exception) { null }

            UsbDeviceInfo(
                name = device.deviceName,
                devicePath = device.deviceName,
                vendorId = device.vendorId,
                productId = device.productId,
                vendorName = device.manufacturerName,
                productName = device.productName,
                deviceClass = usbClassToString(device.deviceClass),
                interfaceCount = device.interfaceCount,
                interfaces = interfaces,
                serialNumber = serial,
                hasAdbInterface = interfaces.any { it.isAdb },
            )
        }.sortedBy { it.devicePath }
    }

    /**
     * Also scan /sys/bus/usb/devices/ for lower-level device info that
     * UsbManager may not expose (e.g., internal hub devices).
     */
    suspend fun scanSysfsUsbDevices(): List<Map<String, String>> = withContext(Dispatchers.IO) {
        val result = mutableListOf<Map<String, String>>()
        try {
            val dir = java.io.File("/sys/bus/usb/devices/")
            if (dir.exists() && dir.isDirectory) {
                dir.listFiles()?.sorted()?.forEach { deviceDir ->
                    if (!deviceDir.isDirectory) return@forEach
                    val props = mutableMapOf<String, String>()
                    props["path"] = deviceDir.name

                    fun readProp(name: String): String? {
                        val f = java.io.File(deviceDir, name)
                        return try { if (f.exists()) f.readText().trim() else null } catch (_: Exception) { null }
                    }

                    readProp("idVendor")?.let { props["idVendor"] = "0x$it" }
                    readProp("idProduct")?.let { props["idProduct"] = "0x$it" }
                    readProp("manufacturer")?.let { props["manufacturer"] = it }
                    readProp("product")?.let { props["product"] = it }
                    readProp("serial")?.let { props["serial"] = it }
                    readProp("bDeviceClass")?.let { props["class"] = it }
                    readProp("bDeviceSubClass")?.let { props["subclass"] = it }
                    readProp("bDeviceProtocol")?.let { props["protocol"] = it }
                    readProp("bNumInterfaces")?.let { props["interfaces"] = it }
                    readProp("speed")?.let { props["speed"] = "${it} Mbps" }
                    readProp("busnum")?.let { props["bus"] = it }
                    readProp("devnum")?.let { props["dev"] = it }
                    readProp("bConfigurationValue")?.let { props["config"] = it }

                    // Only include entries that have at least vendor or product info
                    if (props.size > 1) {
                        result.add(props)
                    }
                }
            }
        } catch (e: Exception) {
            result.add(mapOf("error" to "sysfs scan failed: ${e.message}"))
        }
        result
    }

    private fun usbClassToString(cls: Int): String = when (cls) {
        UsbConstants.USB_CLASS_APP_SPEC -> "App-specific (0xFE)"
        UsbConstants.USB_CLASS_AUDIO -> "Audio"
        UsbConstants.USB_CLASS_CDC_DATA -> "CDC Data"
        UsbConstants.USB_CLASS_COMM -> "Communications"
        UsbConstants.USB_CLASS_CONTENT_SEC -> "Content Security"
        UsbConstants.USB_CLASS_CSCID -> "Smart Card"
        UsbConstants.USB_CLASS_HID -> "HID"
        UsbConstants.USB_CLASS_HUB -> "Hub"
        UsbConstants.USB_CLASS_MASS_STORAGE -> "Mass Storage"
        UsbConstants.USB_CLASS_MISC -> "Misc"
        UsbConstants.USB_CLASS_PER_INTERFACE -> "Per-Interface"
        UsbConstants.USB_CLASS_PHYSICA -> "Physical"
        UsbConstants.USB_CLASS_PRINTER -> "Printer"
        UsbConstants.USB_CLASS_STILL_IMAGE -> "Still Image"
        UsbConstants.USB_CLASS_VENDOR_SPEC -> "Vendor-specific (0xFF)"
        UsbConstants.USB_CLASS_VIDEO -> "Video"
        UsbConstants.USB_CLASS_WIRELESS_CONTROLLER -> "Wireless Controller"
        else -> "Unknown (0x${cls.toString(16)})"
    }
}
