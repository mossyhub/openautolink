package com.openautolink.app.ui.diagnostics

import android.annotation.SuppressLint
import android.app.Application
import android.content.Context
import android.media.AudioManager
import android.os.Build
import android.util.DisplayMetrics
import android.view.WindowManager
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.openautolink.app.data.AppPreferences
import com.openautolink.app.session.SessionManager
import com.openautolink.app.session.SessionState
import com.openautolink.app.transport.ControlMessage
import com.openautolink.app.video.CodecSelector
import com.openautolink.app.video.VideoStats
import com.openautolink.app.audio.AudioStats
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket

data class CodecInfo(
    val name: String,
    val hwAccelerated: Boolean,
)

data class SystemInfo(
    val androidVersion: String,
    val sdkLevel: Int,
    val device: String,
    val manufacturer: String,
    val model: String,
    val soc: String,
    val displayWidth: Int,
    val displayHeight: Int,
    val displayDpi: Int,
    val h264Decoders: List<CodecInfo>,
    val h265Decoders: List<CodecInfo>,
    val vp9Decoders: List<CodecInfo>,
)

data class NetworkInfo(
    val sessionState: SessionState,
)

data class StreamingStats(
    val videoStats: VideoStats,
    val audioStats: AudioStats,
)

data class LogEntry(
    val timestamp: Long,
    val severity: LogSeverity,
    val tag: String,
    val message: String,
)

enum class LogSeverity { DEBUG, INFO, WARN, ERROR }

data class CarInfo(
    val isActive: Boolean = false,
    val speedKmh: Float? = null,
    val gear: String? = null,
    val parkingBrake: Boolean? = null,
    val nightMode: Boolean? = null,
    val batteryPct: Int? = null,
    val fuelLevelPct: Int? = null,
    val rangeKm: Float? = null,
    val ambientTempC: Float? = null,
    val rpmE3: Int? = null,
    val turnSignal: String? = null,
    val headlight: Int? = null,
    val hazardLights: Boolean? = null,
    val steeringAngleDeg: Float? = null,
    val odometerKm: Float? = null,
    val lowFuel: Boolean? = null,
    val chargePortOpen: Boolean? = null,
    val chargePortConnected: Boolean? = null,
    val ignitionState: Int? = null,
    val evChargeRateW: Float? = null,
    val evBatteryLevelWh: Float? = null,
    val evBatteryCapacityWh: Float? = null,
    // Extended EV properties
    val evChargeState: Int? = null,
    val evChargeTimeRemainingSec: Int? = null,
    val evCurrentBatteryCapacityWh: Float? = null,
    val evBatteryTempC: Float? = null,
    val evChargePercentLimit: Float? = null,
    val evChargeCurrentDrawLimitA: Float? = null,
    val evRegenBrakingLevel: Int? = null,
    val evStoppingMode: Int? = null,
    val distanceDisplayUnits: Int? = null,
    // Property access status — key = field name, value = "subscribed"|"not_exposed"|etc
    val propertyStatus: Map<String, String> = emptyMap(),
)

data class DebugProbeState(
    val portScanResults: List<com.openautolink.app.diagnostics.DeviceDebugProbe.PortScanResult> = emptyList(),
    val portScanInProgress: Boolean = false,
    val debugProps: com.openautolink.app.diagnostics.DeviceDebugProbe.DebugProperties? = null,
    val propsLoading: Boolean = false,
    val adbWifiStatus: String = "",
    val deviceInfo: Map<String, String> = emptyMap(),
    val intentResults: List<Pair<String, Boolean>> = emptyList(),
    // USB devices
    val usbDevices: List<com.openautolink.app.diagnostics.DeviceDebugProbe.UsbDeviceInfo> = emptyList(),
    val sysfsDevices: List<Map<String, String>> = emptyList(),
    val usbScanDone: Boolean = false,
    // GM AAOS recon probes — see recon_dump/gm-aaos-recon.md §10 (local-only; gitignored)
    val gmRecon: com.openautolink.app.diagnostics.GmReconProbe.Result? = null,
    val gmReconRunning: Boolean = false,
    val gmAdbEnableResult: String? = null,
    // Car-property enumeration: what VHAL properties are visible to our UID
    val carPropEnum: com.openautolink.app.diagnostics.CarPropertyEnumerator.Result? = null,
    val carPropEnumRunning: Boolean = false,
)

data class DiagnosticsUiState(
    val system: SystemInfo = SystemInfo(
        androidVersion = "", sdkLevel = 0, device = "", manufacturer = "", model = "", soc = "",
        displayWidth = 0, displayHeight = 0, displayDpi = 0,
        h264Decoders = emptyList(), h265Decoders = emptyList(), vp9Decoders = emptyList(),
    ),
    val network: NetworkInfo = NetworkInfo(
        sessionState = SessionState.IDLE,
    ),
    val streaming: StreamingStats = StreamingStats(
        videoStats = VideoStats(), audioStats = AudioStats(),
    ),
    val car: CarInfo = CarInfo(),
    val logs: List<LogEntry> = emptyList(),
    val logFilter: LogSeverity = LogSeverity.DEBUG,
    val networkProbe: NetworkProbeState = NetworkProbeState(),
    val debugProbe: DebugProbeState = DebugProbeState(),
)

data class InterfaceInfo(val name: String, val ip: String)

data class PortScanEntry(
    val host: String,
    val port: Int,
    val label: String,
    val open: Boolean,
    val latencyMs: Long? = null,
    val banner: String? = null,
)

data class NetworkProbeState(
    val interfaces: List<InterfaceInfo> = emptyList(),
    val pingTarget: String = "",
    val pingResult: String? = null,
    val pingInProgress: Boolean = false,
    val tcpListenerActive: Boolean = false,
    val tcpListenerPort: Int = 5288,
    val tcpListenerLog: List<String> = emptyList(),
    // Port scanner
    val portScanRunning: Boolean = false,
    val portScanProgress: String = "",
    val portScanResults: List<PortScanEntry> = emptyList(),
    // Local-only hotspot probe (head-unit-as-AP feasibility test)
    val localHotspotActive: Boolean = false,
    val localHotspotSsid: String? = null,
    val localHotspotPassword: String? = null,
    val localHotspotStatus: String = "",
    // WiFi Direct / P2P probe — does this device support acting as a P2P
    // Group Owner? Useful as a sanity check for future peer-to-peer features;
    // not on the current TCP-over-shared-hotspot path.
    val p2pActive: Boolean = false,
    val p2pSupported: Boolean? = null,
    val p2pSsid: String? = null,
    val p2pPassphrase: String? = null,
    val p2pOwnerIp: String? = null,
    val p2pStatus: String = "",
    val p2pLog: List<String> = emptyList(),
    // Phone discovery via mDNS — Car Hotspot mode
    val phoneDiscoveryActive: Boolean = false,
    val phoneSweepActive: Boolean = false,
    val phoneSweepProgress: String = "",
    val discoveredPhones: List<com.openautolink.app.transport.PhoneDiscovery.DiscoveredPhone> = emptyList(),
)

private data class CombinedInner(
    val logs: List<com.openautolink.app.diagnostics.LocalLogEntry>,
    val filter: LogSeverity,
    val probe: NetworkProbeState,
    val debug: DebugProbeState,
)

class DiagnosticsViewModel(application: Application) : AndroidViewModel(application) {

    private val preferences = AppPreferences.getInstance(application)

    // Shared SessionManager — same instance used by ProjectionViewModel
    private val audioManager = application.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val sessionManager = SessionManager.getInstance(viewModelScope, application, audioManager)

    // Standalone vehicle data forwarder for diagnostics display — independent of session
    private var diagnosticVehicleForwarder: com.openautolink.app.input.VehicleDataForwarder? = null

    private val _system = MutableStateFlow(gatherSystemInfo(application))
    private val _network = MutableStateFlow(DiagnosticsUiState().network)
    private val _streaming = MutableStateFlow(DiagnosticsUiState().streaming)
    private val _car = MutableStateFlow(CarInfo())
    private val _logFilter = MutableStateFlow(LogSeverity.DEBUG)
    private val _networkProbe = MutableStateFlow(NetworkProbeState())
    private val _debugProbe = MutableStateFlow(DebugProbeState())
    private var tcpListenerJob: Job? = null
    private var tcpServerSocket: ServerSocket? = null

    val uiState: StateFlow<DiagnosticsUiState> = combine(
        _system,
        _network,
        _streaming,
        _car,
        combine(
            com.openautolink.app.diagnostics.DiagnosticLog.localLogs,
            _logFilter,
            _networkProbe,
            _debugProbe,
        ) { logs, filter, probe, debug ->
            CombinedInner(logs, filter, probe, debug)
        },
    ) { system, network, streaming, car, inner ->
        // Map LocalLogEntry → LogEntry for UI
        val logs = inner.logs.map { entry ->
            LogEntry(
                timestamp = entry.timestamp,
                severity = when (entry.level) {
                    com.openautolink.app.diagnostics.DiagnosticLevel.DEBUG -> LogSeverity.DEBUG
                    com.openautolink.app.diagnostics.DiagnosticLevel.INFO -> LogSeverity.INFO
                    com.openautolink.app.diagnostics.DiagnosticLevel.WARN -> LogSeverity.WARN
                    com.openautolink.app.diagnostics.DiagnosticLevel.ERROR -> LogSeverity.ERROR
                },
                tag = entry.tag,
                message = entry.message,
            )
        }
        val filtered = if (inner.filter == LogSeverity.DEBUG) logs
        else logs.filter { it.severity >= inner.filter }
        DiagnosticsUiState(
            system = system,
            network = network,
            streaming = streaming,
            car = car,
            logs = filtered,
            logFilter = inner.filter,
            networkProbe = inner.probe,
            debugProbe = inner.debug,
        )
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5000),
        DiagnosticsUiState(system = _system.value)
    )

    init {
        // Start local log capture while diagnostics is open
        com.openautolink.app.diagnostics.DiagnosticLog.startLocalCapture()

        // Observe session state for network tab
        viewModelScope.launch {
            sessionManager.sessionState.collect { state ->
                _network.value = NetworkInfo(sessionState = state)
            }
        }

        // Observe video/audio stats — cancel stale collectors on state change
        viewModelScope.launch {
            sessionManager.sessionState.collectLatest { state ->
                if (state == SessionState.STREAMING) {
                    coroutineScope {
                        sessionManager.videoStats?.let { flow ->
                            launch { flow.collect { stats -> _streaming.value = _streaming.value.copy(videoStats = stats) } }
                        }
                        sessionManager.audioStats?.let { flow ->
                            launch { flow.collect { stats -> _streaming.value = _streaming.value.copy(audioStats = stats) } }
                        }
                    }
                }
            }
        }

        // Observe vehicle data for car tab — standalone instance, no session required
        // (matches app_v1 pattern where VehiclePropertyMonitor was independent)
        val forwarder = com.openautolink.app.input.VehicleDataForwarderImpl(
            application,
            sendMessage = { /* no-op sender — diagnostics only, no bridge forwarding */ }
        )
        diagnosticVehicleForwarder = forwarder
        forwarder.start()

        viewModelScope.launch {
            forwarder.latestVehicleData.collect { vd ->
                _car.value = CarInfo(
                    isActive = forwarder.isActive,
                    speedKmh = vd.speedKmh,
                    gear = vd.gear,
                    parkingBrake = vd.parkingBrake,
                    nightMode = vd.nightMode,
                    batteryPct = vd.batteryPct,
                    fuelLevelPct = vd.fuelLevelPct,
                    rangeKm = vd.rangeKm,
                    ambientTempC = vd.ambientTempC,
                    rpmE3 = vd.rpmE3,
                    turnSignal = vd.turnSignal,
                    headlight = vd.headlight,
                    hazardLights = vd.hazardLights,
                    steeringAngleDeg = vd.steeringAngleDeg,
                    odometerKm = vd.odometerKm,
                    lowFuel = vd.lowFuel,
                    chargePortOpen = vd.chargePortOpen,
                    chargePortConnected = vd.chargePortConnected,
                    ignitionState = vd.ignitionState,
                    evChargeRateW = vd.evChargeRateW,
                    evBatteryLevelWh = vd.evBatteryLevelWh,
                    evBatteryCapacityWh = vd.evBatteryCapacityWh,
                    evChargeState = vd.evChargeState,
                    evChargeTimeRemainingSec = vd.evChargeTimeRemainingSec,
                    evCurrentBatteryCapacityWh = vd.evCurrentBatteryCapacityWh,
                    evBatteryTempC = vd.evBatteryTempC,
                    evChargePercentLimit = vd.evChargePercentLimit,
                    evChargeCurrentDrawLimitA = vd.evChargeCurrentDrawLimitA,
                    evRegenBrakingLevel = vd.evRegenBrakingLevel,
                    evStoppingMode = vd.evStoppingMode,
                    distanceDisplayUnits = vd.distanceDisplayUnits,
                    propertyStatus = forwarder.propertyStatus,
                )
            }
        }

        com.openautolink.app.diagnostics.DiagnosticLog.i("Diagnostics", "Diagnostics screen opened")

        // Populate network interfaces on open
        refreshInterfaces()

        // Pre-populate debug probe info
        _debugProbe.value = _debugProbe.value.copy(
            adbWifiStatus = com.openautolink.app.diagnostics.DeviceDebugProbe.getAdbWifiStatus(application),
            deviceInfo = com.openautolink.app.diagnostics.DeviceDebugProbe.getDeviceInfo(),
        )
    }

    // ── Network Probe ─────────────────────────────────────────────────

    fun refreshInterfaces() {
        viewModelScope.launch(Dispatchers.IO) {
            val ifaces = mutableListOf<InterfaceInfo>()
            try {
                for (ni in NetworkInterface.getNetworkInterfaces()) {
                    for (addr in ni.inetAddresses) {
                        if (!addr.isLoopbackAddress && addr is Inet4Address) {
                            ifaces.add(InterfaceInfo(ni.name, addr.hostAddress ?: "?"))
                        }
                    }
                }
            } catch (_: Exception) {}
            _networkProbe.value = _networkProbe.value.copy(interfaces = ifaces)
        }
    }

    fun setPingTarget(target: String) {
        _networkProbe.value = _networkProbe.value.copy(pingTarget = target)
    }

    fun runPing() {
        val target = _networkProbe.value.pingTarget.trim()
        if (target.isEmpty()) return
        _networkProbe.value = _networkProbe.value.copy(pingInProgress = true, pingResult = null)
        viewModelScope.launch(Dispatchers.IO) {
            val result = try {
                val addr = java.net.InetAddress.getByName(target)
                val start = System.currentTimeMillis()
                val reachable = addr.isReachable(3000)
                val elapsed = System.currentTimeMillis() - start
                if (reachable) "✓ Reachable in ${elapsed}ms" else "✗ Unreachable (3s timeout)"
            } catch (e: Exception) {
                "✗ Error: ${e.message}"
            }
            _networkProbe.value = _networkProbe.value.copy(pingInProgress = false, pingResult = result)
        }
    }

    fun runTcpConnect(target: String, port: Int = 5288) {
        _networkProbe.value = _networkProbe.value.copy(pingInProgress = true, pingResult = null)
        viewModelScope.launch(Dispatchers.IO) {
            val result = try {
                val socket = Socket()
                val start = System.currentTimeMillis()
                socket.connect(InetSocketAddress(target.trim(), port), 3000)
                val elapsed = System.currentTimeMillis() - start
                socket.close()
                "✓ TCP:$port open in ${elapsed}ms"
            } catch (e: Exception) {
                "✗ TCP:$port failed: ${e.message}"
            }
            _networkProbe.value = _networkProbe.value.copy(pingInProgress = false, pingResult = result)
        }
    }

    fun toggleTcpListener() {
        if (_networkProbe.value.tcpListenerActive) {
            stopTcpListener()
        } else {
            startTcpListener()
        }
    }

    private fun startTcpListener() {
        val port = _networkProbe.value.tcpListenerPort
        _networkProbe.value = _networkProbe.value.copy(
            tcpListenerActive = true,
            tcpListenerLog = listOf("Starting listener on port $port...")
        )
        tcpListenerJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                val server = ServerSocket(port).apply { reuseAddress = true; soTimeout = 0 }
                tcpServerSocket = server
                addTcpLog("Listening on port $port — connect from phone to test")
                while (true) {
                    val client = server.accept()
                    val remote = client.inetAddress?.hostAddress ?: "unknown"
                    addTcpLog("✓ Connection from $remote")
                    // Read a few bytes to confirm data flow
                    try {
                        client.soTimeout = 2000
                        val buf = ByteArray(256)
                        val n = client.getInputStream().read(buf)
                        if (n > 0) {
                            addTcpLog("  Received $n bytes from $remote")
                        } else {
                            addTcpLog("  Connection closed by $remote (no data)")
                        }
                    } catch (_: Exception) {
                        addTcpLog("  No data within 2s (connect-only test OK)")
                    } finally {
                        client.close()
                    }
                }
            } catch (e: Exception) {
                if (_networkProbe.value.tcpListenerActive) {
                    addTcpLog("✗ Listener error: ${e.message}")
                }
            } finally {
                _networkProbe.value = _networkProbe.value.copy(tcpListenerActive = false)
            }
        }
    }

    private fun stopTcpListener() {
        tcpListenerJob?.cancel()
        tcpListenerJob = null
        try { tcpServerSocket?.close() } catch (_: Exception) {}
        tcpServerSocket = null
        addTcpLog("Listener stopped")
        _networkProbe.value = _networkProbe.value.copy(tcpListenerActive = false)
    }

    private fun addTcpLog(msg: String) {
        val current = _networkProbe.value.tcpListenerLog
        _networkProbe.value = _networkProbe.value.copy(
            tcpListenerLog = (current + msg).takeLast(20)
        )
    }

    // ── Port Scanner ────────────────────────────────────────────────

    private var portScanJob: Job? = null

    fun startPortScan() {
        if (_networkProbe.value.portScanRunning) return
        _networkProbe.value = _networkProbe.value.copy(
            portScanRunning = true,
            portScanProgress = "Starting scan...",
            portScanResults = emptyList(),
        )

        portScanJob = viewModelScope.launch(Dispatchers.IO) {
            val results = mutableListOf<PortScanEntry>()

            // Build target list: localhost + all interface gateways + any manually entered IP
            val targets = mutableListOf<Pair<String, String>>() // (ip, label)
            targets.add("127.0.0.1" to "localhost")

            // Add all interface IPs (for scanning services bound to specific interfaces)
            val ifaces = _networkProbe.value.interfaces
            for (iface in ifaces) {
                if (iface.ip != "127.0.0.1") {
                    targets.add(iface.ip to "self (${iface.name})")
                    // Guess gateway: replace last octet with .1
                    val gateway = iface.ip.substringBeforeLast('.') + ".1"
                    if (gateway != iface.ip) {
                        targets.add(gateway to "gateway (${iface.name})")
                    }
                }
            }

            // If user has a ping target entered, scan that too
            val userTarget = _networkProbe.value.pingTarget.trim()
            if (userTarget.isNotEmpty() && targets.none { it.first == userTarget }) {
                targets.add(userTarget to "manual")
            }

            // Ports to scan — common services + ADB + debug ports
            val ports = listOf(
                21 to "FTP",
                22 to "SSH",
                23 to "Telnet",
                53 to "DNS",
                80 to "HTTP",
                443 to "HTTPS",
                554 to "RTSP",
                3389 to "RDP",
                4000 to "Debug",
                4200 to "Debug",
                4500 to "IPSec",
                5000 to "HTTP-alt",
                5037 to "ADB server",
                5040 to "ADB-alt",
                5228 to "GCM",
                5288 to "OAL AA",
                5432 to "PostgreSQL",
                5555 to "ADB TCP",
                5556 to "ADB TCP-alt",
                5557 to "ADB TCP-alt2",
                5558 to "ADB TCP-alt3",
                5559 to "ADB TCP-alt4",
                5900 to "VNC",
                6000 to "X11",
                6555 to "OAL LogSrv",
                7555 to "ADB WiFi",
                8080 to "HTTP-proxy",
                8443 to "HTTPS-alt",
                8888 to "HTTP-alt2",
                9000 to "Debug",
                9090 to "Debug2",
                9222 to "Chrome DevTools",
                27042 to "Frida",
                62078 to "iproxy",
            )

            val total = targets.size * ports.size
            var done = 0

            for ((host, hostLabel) in targets) {
                // Parallelize ports per host using coroutines
                val perHost = mutableListOf<PortScanEntry>()
                ports.chunked(20).forEach { batch ->
                    val deferred = batch.map { (port, label) ->
                        async(Dispatchers.IO) {
                            scanPort(host, hostLabel, port, label)
                        }
                    }
                    perHost.addAll(deferred.map { it.await() })
                    done += batch.size
                    _networkProbe.value = _networkProbe.value.copy(
                        portScanProgress = "Scanning $host... ($done/$total)"
                    )
                }
                results.addAll(perHost)
                // Update results incrementally so user sees progress
                _networkProbe.value = _networkProbe.value.copy(
                    portScanResults = results.toList(),
                )
            }

            val openCount = results.count { it.open }
            _networkProbe.value = _networkProbe.value.copy(
                portScanRunning = false,
                portScanProgress = "Done — $openCount open ports found across ${targets.size} hosts",
                portScanResults = results.toList(),
            )
        }
    }

    fun stopPortScan() {
        portScanJob?.cancel()
        portScanJob = null
        _networkProbe.value = _networkProbe.value.copy(
            portScanRunning = false,
            portScanProgress = "Scan cancelled",
        )
    }

    private fun scanPort(host: String, hostLabel: String, port: Int, label: String): PortScanEntry {
        return try {
            val socket = Socket()
            val start = System.currentTimeMillis()
            socket.connect(InetSocketAddress(host, port), 300)
            val elapsed = System.currentTimeMillis() - start
            // Try reading a banner
            val banner = try {
                socket.soTimeout = 200
                val buf = ByteArray(128)
                val n = socket.getInputStream().read(buf)
                if (n > 0) {
                    String(buf, 0, n, Charsets.UTF_8)
                        .replace("\r", "")
                        .replace("\n", " ")
                        .take(80)
                        .trim()
                } else null
            } catch (_: Exception) { null }
            socket.close()
            PortScanEntry(host, port, "$label ($hostLabel)", open = true, latencyMs = elapsed, banner = banner)
        } catch (_: Exception) {
            PortScanEntry(host, port, "$label ($hostLabel)", open = false)
        }
    }

    // ── Log Filter ──────────────────────────────────────────────────

    fun setLogFilter(severity: LogSeverity) {
        _logFilter.value = severity
    }

    fun clearLogs() {
        com.openautolink.app.diagnostics.DiagnosticLog.clearLocal()
    }

    // ── Debug Probe ─────────────────────────────────────────────────

    fun scanAdbPorts() {
        _debugProbe.value = _debugProbe.value.copy(portScanInProgress = true)
        viewModelScope.launch {
            val results = com.openautolink.app.diagnostics.DeviceDebugProbe.scanAdbPorts()
            _debugProbe.value = _debugProbe.value.copy(
                portScanResults = results,
                portScanInProgress = false,
            )
        }
    }

    fun loadDebugProperties() {
        _debugProbe.value = _debugProbe.value.copy(propsLoading = true)
        viewModelScope.launch {
            val props = com.openautolink.app.diagnostics.DeviceDebugProbe.getDebugProperties()
            _debugProbe.value = _debugProbe.value.copy(
                debugProps = props,
                propsLoading = false,
            )
        }
    }

    fun tryLaunchDevSettings(description: String, intent: android.content.Intent) {
        val context = getApplication<Application>()
        val success = com.openautolink.app.diagnostics.DeviceDebugProbe.tryLaunchIntent(context, intent)
        val current = _debugProbe.value.intentResults
        _debugProbe.value = _debugProbe.value.copy(
            intentResults = current + (description to success)
        )
    }

    fun probeAllDevSettingsIntents() {
        val context = getApplication<Application>()
        val intents = com.openautolink.app.diagnostics.DeviceDebugProbe.getDeveloperSettingsIntents()
        val results = intents.map { (desc, intent) ->
            val canResolve = try {
                intent.resolveActivity(context.packageManager) != null
            } catch (_: Exception) { false }
            desc to canResolve
        }
        _debugProbe.value = _debugProbe.value.copy(intentResults = results)
    }

    // ── USB Scanner ─────────────────────────────────────────────────

    fun scanUsbDevices() {
        val context = getApplication<Application>()
        // UsbManager enumeration (Android API — instant)
        val devices = com.openautolink.app.diagnostics.DeviceDebugProbe.enumerateUsbDevices(context)
        _debugProbe.value = _debugProbe.value.copy(usbDevices = devices, usbScanDone = true)

        // sysfs scan (background — may show more devices)
        viewModelScope.launch {
            val sysfs = com.openautolink.app.diagnostics.DeviceDebugProbe.scanSysfsUsbDevices()
            _debugProbe.value = _debugProbe.value.copy(sysfsDevices = sysfs)
        }
    }

    // ── GM AAOS Recon Probes ────────────────────────────────────────
    // Pen-test the four enable paths documented in recon_dump/gm-aaos-recon.md §10 (local-only; gitignored).

    fun runGmReconProbes() {
        if (_debugProbe.value.gmReconRunning) return
        _debugProbe.value = _debugProbe.value.copy(gmReconRunning = true)
        viewModelScope.launch {
            val result = com.openautolink.app.diagnostics.GmReconProbe.run(getApplication())
            _debugProbe.value = _debugProbe.value.copy(
                gmRecon = result,
                gmReconRunning = false,
            )
        }
    }

    fun callRdmsAdbEnable(enable: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            val (ok, detail) = com.openautolink.app.diagnostics.GmReconProbe.callRdmsAdbEnable(enable)
            val label = if (ok) "✓" else "✗"
            _debugProbe.value = _debugProbe.value.copy(
                gmAdbEnableResult = "$label RDMSADBEnable(${if (enable) "true" else "false"}) — $detail",
            )
        }
    }

    fun clearGmAdbEnableResult() {
        _debugProbe.value = _debugProbe.value.copy(gmAdbEnableResult = null)
    }

    fun execAdboverBcs(arg: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val (ok, detail) = com.openautolink.app.diagnostics.GmReconProbe.execAdboverBcs(arg)
            val label = if (ok) "✓" else "✗"
            _debugProbe.value = _debugProbe.value.copy(
                gmAdbEnableResult = "$label /system/bin/ADBoverBCS.sh $arg — $detail",
            )
        }
    }

    fun enumerateCarProperties() {
        if (_debugProbe.value.carPropEnumRunning) return
        _debugProbe.value = _debugProbe.value.copy(carPropEnumRunning = true)
        viewModelScope.launch {
            val result = com.openautolink.app.diagnostics.CarPropertyEnumerator.enumerate(getApplication())
            _debugProbe.value = _debugProbe.value.copy(
                carPropEnum = result,
                carPropEnumRunning = false,
            )
        }
    }

    // ── Local-Only Hotspot Probe ────────────────────────────────────
    // Tests whether AAOS allows a normal app to start a SoftAP. If this works
    // and the SSID is stable across sessions, the "phones-as-clients-of-car-AP"
    // architecture becomes viable as a multi-phone solution.

    private var localHotspotReservation: android.net.wifi.WifiManager.LocalOnlyHotspotReservation? = null

    fun toggleLocalHotspot() {
        if (_networkProbe.value.localHotspotActive) stopLocalHotspot() else startLocalHotspot()
    }

    private fun startLocalHotspot() {
        val app = getApplication<Application>()
        val wifi = app.getSystemService(Context.WIFI_SERVICE) as? android.net.wifi.WifiManager
        if (wifi == null) {
            _networkProbe.value = _networkProbe.value.copy(localHotspotStatus = "✗ WifiManager unavailable")
            return
        }
        _networkProbe.value = _networkProbe.value.copy(
            localHotspotStatus = "Starting local-only hotspot…",
            localHotspotSsid = null,
            localHotspotPassword = null,
        )
        try {
            wifi.startLocalOnlyHotspot(
                object : android.net.wifi.WifiManager.LocalOnlyHotspotCallback() {
                    override fun onStarted(reservation: android.net.wifi.WifiManager.LocalOnlyHotspotReservation) {
                        localHotspotReservation = reservation
                        val (ssid, pass) = extractCredentials(reservation)
                        _networkProbe.value = _networkProbe.value.copy(
                            localHotspotActive = true,
                            localHotspotSsid = ssid,
                            localHotspotPassword = pass,
                            localHotspotStatus = "✓ Hotspot active — connect a phone to test",
                        )
                        com.openautolink.app.diagnostics.DiagnosticLog.i(
                            "HotspotProbe", "Local-only hotspot started ssid=$ssid"
                        )
                        viewModelScope.launch { refreshInterfaces() }
                    }

                    override fun onStopped() {
                        _networkProbe.value = _networkProbe.value.copy(
                            localHotspotActive = false,
                            localHotspotStatus = "Hotspot stopped by system",
                        )
                        localHotspotReservation = null
                    }

                    override fun onFailed(reason: Int) {
                        val msg = when (reason) {
                            ERROR_NO_CHANNEL -> "no channel"
                            ERROR_GENERIC -> "generic error"
                            ERROR_INCOMPATIBLE_MODE -> "incompatible mode (STA+AP not supported?)"
                            ERROR_TETHERING_DISALLOWED -> "tethering disallowed by policy"
                            else -> "reason=$reason"
                        }
                        _networkProbe.value = _networkProbe.value.copy(
                            localHotspotActive = false,
                            localHotspotStatus = "✗ Failed: $msg",
                        )
                        com.openautolink.app.diagnostics.DiagnosticLog.w(
                            "HotspotProbe", "Local-only hotspot failed: $msg"
                        )
                        localHotspotReservation = null
                    }
                },
                null,
            )
        } catch (se: SecurityException) {
            _networkProbe.value = _networkProbe.value.copy(
                localHotspotStatus = "✗ SecurityException: ${se.message} (grant Location permission)"
            )
        } catch (e: Exception) {
            _networkProbe.value = _networkProbe.value.copy(
                localHotspotStatus = "✗ ${e.javaClass.simpleName}: ${e.message}"
            )
        }
    }

    private fun extractCredentials(
        reservation: android.net.wifi.WifiManager.LocalOnlyHotspotReservation
    ): Pair<String?, String?> {
        // SoftApConfiguration is the modern API (Android 11+); WifiConfiguration is legacy.
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val cfg = reservation.softApConfiguration
                cfg.ssid to cfg.passphrase
            } else {
                @Suppress("DEPRECATION")
                val cfg = reservation.wifiConfiguration
                @Suppress("DEPRECATION")
                (cfg?.SSID to cfg?.preSharedKey)
            }
        } catch (_: Exception) {
            null to null
        }
    }

    private fun stopLocalHotspot() {
        try { localHotspotReservation?.close() } catch (_: Exception) {}
        localHotspotReservation = null
        _networkProbe.value = _networkProbe.value.copy(
            localHotspotActive = false,
            localHotspotStatus = "Hotspot stopped",
        )
    }

    // ── WiFi Direct (P2P) Probe ─────────────────────────────────────
    // Tests whether the head unit can act as a WiFi Direct Group Owner.
    // Not used by the current TCP-over-shared-hotspot path; kept as a
    // capability probe for future peer-to-peer features.

    private var p2pManager: android.net.wifi.p2p.WifiP2pManager? = null
    private var p2pChannel: android.net.wifi.p2p.WifiP2pManager.Channel? = null
    private var p2pReceiver: android.content.BroadcastReceiver? = null

    fun toggleP2pProbe() {
        if (_networkProbe.value.p2pActive) stopP2pProbe() else startP2pProbe()
    }

    @SuppressLint("MissingPermission")
    private fun startP2pProbe() {
        val app = getApplication<Application>()
        val mgr = app.getSystemService(Context.WIFI_P2P_SERVICE) as? android.net.wifi.p2p.WifiP2pManager
        if (mgr == null) {
            _networkProbe.value = _networkProbe.value.copy(
                p2pSupported = false,
                p2pStatus = "✗ WIFI_P2P_SERVICE unavailable on this device",
            )
            return
        }
        p2pManager = mgr
        appendP2pLog("Initializing P2P channel…")
        val ch = mgr.initialize(app, app.mainLooper) {
            appendP2pLog("Channel disconnected by framework")
        }
        if (ch == null) {
            _networkProbe.value = _networkProbe.value.copy(
                p2pSupported = false,
                p2pStatus = "✗ initialize() returned null channel",
            )
            return
        }
        p2pChannel = ch
        _networkProbe.value = _networkProbe.value.copy(
            p2pActive = true,
            p2pSupported = true,
            p2pStatus = "Channel initialized — removing any old group…",
            p2pSsid = null,
            p2pPassphrase = null,
            p2pOwnerIp = null,
        )

        // Listen for connection-changed to fetch group info
        val receiver = object : android.content.BroadcastReceiver() {
            override fun onReceive(c: Context, intent: android.content.Intent) {
                if (intent.action == android.net.wifi.p2p.WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION) {
                    appendP2pLog("Broadcast: WIFI_P2P_CONNECTION_CHANGED")
                    requestGroupInfo()
                }
            }
        }
        p2pReceiver = receiver
        androidx.core.content.ContextCompat.registerReceiver(
            app, receiver,
            android.content.IntentFilter(android.net.wifi.p2p.WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION),
            androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED,
        )

        // Tear down any pre-existing group, then create
        mgr.removeGroup(ch, object : android.net.wifi.p2p.WifiP2pManager.ActionListener {
            override fun onSuccess() { appendP2pLog("Old group removed; calling createGroup()"); doCreateGroup() }
            override fun onFailure(reason: Int) { appendP2pLog("removeGroup failed (reason=$reason); calling createGroup() anyway"); doCreateGroup() }
        })
    }

    @SuppressLint("MissingPermission")
    private fun doCreateGroup() {
        val mgr = p2pManager ?: return
        val ch = p2pChannel ?: return
        mgr.createGroup(ch, object : android.net.wifi.p2p.WifiP2pManager.ActionListener {
            override fun onSuccess() {
                appendP2pLog("✓ createGroup() onSuccess — waiting for group info")
                _networkProbe.value = _networkProbe.value.copy(p2pStatus = "✓ Group creation accepted")
                // Some OEMs don't broadcast — poll once after a short delay
                viewModelScope.launch {
                    kotlinx.coroutines.delay(1500)
                    requestGroupInfo()
                }
            }
            override fun onFailure(reason: Int) {
                val msg = when (reason) {
                    android.net.wifi.p2p.WifiP2pManager.P2P_UNSUPPORTED -> "P2P_UNSUPPORTED (device or OEM blocks WiFi Direct)"
                    android.net.wifi.p2p.WifiP2pManager.BUSY -> "BUSY (P2P framework in use)"
                    android.net.wifi.p2p.WifiP2pManager.ERROR -> "ERROR (internal framework error)"
                    else -> "reason=$reason"
                }
                appendP2pLog("✗ createGroup() onFailure: $msg")
                _networkProbe.value = _networkProbe.value.copy(
                    p2pActive = false,
                    p2pStatus = "✗ createGroup failed: $msg",
                )
            }
        })
    }

    @SuppressLint("MissingPermission")
    private fun requestGroupInfo() {
        val mgr = p2pManager ?: return
        val ch = p2pChannel ?: return
        mgr.requestGroupInfo(ch) { group ->
            if (group == null) {
                appendP2pLog("requestGroupInfo: null (no group yet)")
            } else {
                val ssid = group.networkName
                val pass = group.passphrase
                appendP2pLog("✓ Group info: ssid=$ssid")
                _networkProbe.value = _networkProbe.value.copy(
                    p2pSsid = ssid,
                    p2pPassphrase = pass,
                    p2pStatus = "✓ Group active — connect a phone to test",
                )
            }
        }
        mgr.requestConnectionInfo(ch) { info ->
            val ip = info?.groupOwnerAddress?.hostAddress
            if (ip != null) {
                _networkProbe.value = _networkProbe.value.copy(p2pOwnerIp = ip)
                appendP2pLog("Group owner IP: $ip")
            }
        }
    }

    private fun stopP2pProbe() {
        val mgr = p2pManager
        val ch = p2pChannel
        if (mgr != null && ch != null) {
            try {
                mgr.removeGroup(ch, null)
            } catch (_: Exception) {}
        }
        p2pReceiver?.let {
            try { getApplication<Application>().unregisterReceiver(it) } catch (_: Exception) {}
        }
        p2pReceiver = null
        p2pChannel = null
        p2pManager = null
        _networkProbe.value = _networkProbe.value.copy(
            p2pActive = false,
            p2pStatus = "Stopped",
        )
    }

    private fun appendP2pLog(msg: String) {
        val cur = _networkProbe.value.p2pLog
        _networkProbe.value = _networkProbe.value.copy(p2pLog = (cur + msg).takeLast(20))
        com.openautolink.app.diagnostics.DiagnosticLog.i("P2pProbe", msg)
    }

    // ── Phone Discovery (Car Hotspot mode) ──────────────────────────

    private val phoneDiscovery = com.openautolink.app.transport.PhoneDiscovery.getInstance(application)
    private var phoneDiscoveryJob: Job? = null

    fun togglePhoneDiscovery() {
        if (_networkProbe.value.phoneDiscoveryActive) stopPhoneDiscovery()
        else startPhoneDiscovery()
    }

    private fun startPhoneDiscovery() {
        // [phoneDiscovery] is a process-wide singleton shared with projection.
        // Don't clear() it here — that'd nuke projection's discovered list.
        // Just ensure mDNS is running and kick the sweep; the UI will see the
        // current snapshot via the StateFlow.
        phoneDiscovery.start()
        phoneDiscovery.startSweep()
        _networkProbe.value = _networkProbe.value.copy(phoneDiscoveryActive = true)
        phoneDiscoveryJob?.cancel()
        phoneDiscoveryJob = viewModelScope.launch {
            launch {
                phoneDiscovery.phones.collect { list ->
                    _networkProbe.value = _networkProbe.value.copy(discoveredPhones = list)
                }
            }
            launch {
                phoneDiscovery.isSweeping.collect { sweeping ->
                    _networkProbe.value = _networkProbe.value.copy(phoneSweepActive = sweeping)
                }
            }
            launch {
                phoneDiscovery.sweepProgress.collect { progress ->
                    _networkProbe.value = _networkProbe.value.copy(phoneSweepProgress = progress)
                }
            }
        }
    }

    fun rescanPhones() {
        // Re-run the active sweep without stopping mDNS. mDNS keeps running
        // continuously; this only re-fires the active TCP probe.
        phoneDiscovery.startSweep()
    }

    private fun stopPhoneDiscovery() {
        // Note: do NOT call phoneDiscovery.stop() — it's a process-wide
        // singleton shared with the projection screen, and stopping it here
        // would kill projection's mDNS too. We just stop our sweep + drop
        // the local UI subscription; mDNS keeps running for projection.
        phoneDiscovery.stopSweep()
        phoneDiscoveryJob?.cancel()
        phoneDiscoveryJob = null
        _networkProbe.value = _networkProbe.value.copy(
            phoneDiscoveryActive = false,
            phoneSweepActive = false,
        )
    }

    override fun onCleared() {
        super.onCleared()
        stopTcpListener()
        stopLocalHotspot()
        stopP2pProbe()
        stopPhoneDiscovery()
        diagnosticVehicleForwarder?.stop()
        diagnosticVehicleForwarder = null
        com.openautolink.app.diagnostics.DiagnosticLog.stopLocalCapture()
    }

    companion object {
        private fun gatherSystemInfo(app: Application): SystemInfo {
            val wm = app.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val metrics = DisplayMetrics()
            @Suppress("DEPRECATION")
            wm.defaultDisplay.getRealMetrics(metrics)

            val h264Decoders = parseDecoderList(CodecSelector.listDecoders(CodecSelector.MIME_H264))
            val h265Decoders = parseDecoderList(CodecSelector.listDecoders(CodecSelector.MIME_H265))
            val vp9Decoders = parseDecoderList(CodecSelector.listDecoders(CodecSelector.MIME_VP9))

            return SystemInfo(
                androidVersion = Build.VERSION.RELEASE,
                sdkLevel = Build.VERSION.SDK_INT,
                device = Build.DEVICE,
                manufacturer = Build.MANUFACTURER,
                model = Build.MODEL,
                soc = Build.SOC_MODEL,
                displayWidth = metrics.widthPixels,
                displayHeight = metrics.heightPixels,
                displayDpi = metrics.densityDpi,
                h264Decoders = h264Decoders,
                h265Decoders = h265Decoders,
                vp9Decoders = vp9Decoders,
            )
        }

        private fun parseDecoderList(decoders: List<String>): List<CodecInfo> {
            return decoders.map { entry ->
                val hw = entry.endsWith("[HW]")
                val name = entry.removeSuffix(" [HW]").removeSuffix(" [SW]").trim()
                CodecInfo(name = name, hwAccelerated = hw)
            }
        }
    }
}
