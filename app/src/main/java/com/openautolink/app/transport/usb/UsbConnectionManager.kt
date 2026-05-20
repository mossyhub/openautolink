package com.openautolink.app.transport.usb

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import com.openautolink.app.diagnostics.OalLog
import com.openautolink.app.transport.aasdk.AasdkTransportPipe
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * USB connection state machine.
 */
enum class UsbConnectionState {
    IDLE,
    DEVICE_DETECTED,
    AWAITING_USER_SELECTION,
    PERMISSION_REQUESTED,
    SWITCHING_TO_ACCESSORY,
    ACCESSORY_DETECTED,
    CONNECTING,
    CONNECTED,
}

/**
 * A user-visible USB device candidate shown in the picker.
 */
data class UsbDeviceCandidate(
    val deviceName: String,        // stable id from UsbDevice.deviceName (e.g. /dev/bus/usb/001/002)
    val vendorId: Int,
    val productId: Int,
    val friendlyName: String,      // manufacturer + product, falling back to VID:PID
    val isAccessoryMode: Boolean,  // true if already in Google Accessory mode
)

/**
 * Manages the full USB transport lifecycle:
 *   1. Monitors USB attach/detach events
 *   2. Requests USB permission from the user
 *   3. Performs AOA v2 handshake to switch phone to accessory mode
 *   4. Opens bulk endpoints and creates AasdkTransportPipe
 *   5. Delivers the pipe to the session via [onTransportReady]
 *
 * Mirrors the lifecycle pattern of TcpConnector.
 */
class UsbConnectionManager(
    private val context: Context,
    private val scope: CoroutineScope,
    private val onTransportReady: (AasdkTransportPipe) -> Unit,
) {
    companion object {
        private const val TAG = "UsbConnectionManager"
        private const val ACTION_USB_PERMISSION = "com.openautolink.app.USB_PERMISSION"
        private const val AOA_SWITCH_SETTLE_MS = 2000L
        private const val ACCESSORY_SCAN_INTERVAL_MS = 500L
        private const val ACCESSORY_SCAN_MAX_ATTEMPTS = 20

        private val _status = MutableStateFlow("Idle")
        val status: StateFlow<String> = _status.asStateFlow()

        private val _connectionState = MutableStateFlow(UsbConnectionState.IDLE)
        val connectionState: StateFlow<UsbConnectionState> = _connectionState.asStateFlow()

        private val _availableDevices = MutableStateFlow<List<UsbDeviceCandidate>>(emptyList())
        val availableDevices: StateFlow<List<UsbDeviceCandidate>> = _availableDevices.asStateFlow()

        @Volatile
        private var activeInstance: UsbConnectionManager? = null

        /** Called from the UI when the user picks a device from the picker. */
        fun selectDevice(deviceName: String) {
            activeInstance?.onUserSelectedDevice(deviceName)
        }
    }

    private val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager

    @Volatile
    private var isRunning = false

    @Volatile
    private var currentPipe: UsbTransportPipe? = null

    @Volatile
    private var currentConnection: UsbDeviceConnection? = null

    private var scanJob: Job? = null

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            when (intent.action) {
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                    @Suppress("DEPRECATION")
                    val device = intent.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE)
                    if (device != null && isRunning) {
                        OalLog.i(TAG, "USB device attached: ${device.deviceName} " +
                                "VID=${String.format("%04X", device.vendorId)} " +
                                "PID=${String.format("%04X", device.productId)}")
                        handleDeviceAttached(device)
                    }
                }
                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    OalLog.i(TAG, "USB device detached")
                    handleDeviceDetached()
                }
                ACTION_USB_PERMISSION -> {
                    val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                    @Suppress("DEPRECATION")
                    val device = intent.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE)
                    if (granted && device != null) {
                        OalLog.i(TAG, "USB permission granted for: ${device.deviceName}")
                        onPermissionGranted(device)
                    } else {
                        OalLog.w(TAG, "USB permission denied")
                        _status.value = "USB permission denied"
                        _connectionState.value = UsbConnectionState.IDLE
                    }
                }
            }
        }
    }

    fun start() {
        if (isRunning) return
        isRunning = true
        activeInstance = this
        _connectionState.value = UsbConnectionState.IDLE
        _status.value = "Waiting for USB device..."

        val filter = IntentFilter().apply {
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
            addAction(ACTION_USB_PERMISSION)
        }
        context.registerReceiver(usbReceiver, filter, Context.RECEIVER_NOT_EXPORTED)

        // Enumerate currently-attached devices and either auto-connect to an
        // already-switched accessory or publish the candidate list for the UI.
        scanExistingDevices()
    }

    fun stop() {
        if (!isRunning) return
        isRunning = false
        if (activeInstance === this) activeInstance = null
        scanJob?.cancel()
        scanJob = null
        try {
            context.unregisterReceiver(usbReceiver)
        } catch (_: IllegalArgumentException) { }
        closePipe()
        _availableDevices.value = emptyList()
        _connectionState.value = UsbConnectionState.IDLE
        _status.value = "Stopped"
    }

    private fun scanExistingDevices() {
        val devices = usbManager.deviceList
        OalLog.i(TAG, "Scanning ${devices.size} existing USB devices")

        // Auto-handle a device already in accessory mode — this is the return
        // path after we triggered an AOA switch on a previous selection, so
        // there's no point re-prompting the user. Permission is still requested
        // (it persists across the re-enumeration on most cars but not all).
        for ((_, device) in devices) {
            if (UsbConstants.isAccessoryDevice(device.vendorId, device.productId)) {
                OalLog.i(TAG, "Found device already in accessory mode: ${device.deviceName}")
                _connectionState.value = UsbConnectionState.ACCESSORY_DETECTED
                requestPermissionOrConnect(device)
                return
            }
        }

        publishCandidates()
    }

    private fun handleDeviceAttached(device: UsbDevice) {
        if (UsbConstants.isAccessoryDevice(device.vendorId, device.productId)) {
            // Accessory came back after our AOA switch — connect without prompting again.
            _connectionState.value = UsbConnectionState.ACCESSORY_DETECTED
            _status.value = "Accessory device detected"
            requestPermissionOrConnect(device)
        } else {
            // Newly-attached non-accessory device: refresh the picker list.
            // Do NOT auto-request permission — we only ever prompt for the
            // device the user explicitly selects.
            publishCandidates()
        }
    }

    private fun handleDeviceDetached() {
        closePipe()
        _connectionState.value = UsbConnectionState.IDLE
        _status.value = "USB device disconnected"
        publishCandidates()
    }

    /**
     * Re-enumerates attached USB devices and publishes the user-visible
     * candidate list. Hubs and mass-storage devices are filtered out.
     */
    private fun publishCandidates() {
        val list = usbManager.deviceList.values
            .filterNot { isHubOrSystemDevice(it) }
            .map { d ->
                UsbDeviceCandidate(
                    deviceName = d.deviceName,
                    vendorId = d.vendorId,
                    productId = d.productId,
                    friendlyName = friendlyNameFor(d),
                    isAccessoryMode = UsbConstants.isAccessoryDevice(d.vendorId, d.productId),
                )
            }
            .sortedBy { it.friendlyName.lowercase() }
        _availableDevices.value = list
        if (_connectionState.value == UsbConnectionState.IDLE && list.isNotEmpty()) {
            _connectionState.value = UsbConnectionState.AWAITING_USER_SELECTION
            _status.value = "Select a USB device to connect"
        } else if (list.isEmpty() && _connectionState.value == UsbConnectionState.AWAITING_USER_SELECTION) {
            _connectionState.value = UsbConnectionState.IDLE
            _status.value = "Waiting for USB device..."
        }
    }

    private fun friendlyNameFor(device: UsbDevice): String {
        val mfg = try { device.manufacturerName } catch (_: SecurityException) { null }
        val prod = try { device.productName } catch (_: SecurityException) { null }
        val composed = listOfNotNull(mfg?.trim(), prod?.trim())
            .filter { it.isNotEmpty() }
            .joinToString(" ")
        if (composed.isNotEmpty()) return composed
        return "USB Device %04X:%04X".format(device.vendorId, device.productId)
    }

    /**
     * Called when the user taps a device in the picker. Looks the device up
     * by its stable [UsbDevice.deviceName] and starts the permission/AOA flow.
     */
    private fun onUserSelectedDevice(deviceName: String) {
        if (!isRunning) return
        val device = usbManager.deviceList[deviceName]
        if (device == null) {
            OalLog.w(TAG, "User selected device $deviceName but it is no longer attached")
            publishCandidates()
            return
        }
        OalLog.i(TAG, "User selected USB device: ${device.deviceName} " +
                "VID=${String.format("%04X", device.vendorId)} " +
                "PID=${String.format("%04X", device.productId)}")
        _connectionState.value = UsbConnectionState.DEVICE_DETECTED
        _status.value = "Requesting permission for ${friendlyNameFor(device)}..."
        requestPermissionOrConnect(device)
    }

    private fun requestPermissionOrConnect(device: UsbDevice) {
        if (usbManager.hasPermission(device)) {
            onPermissionGranted(device)
        } else {
            _connectionState.value = UsbConnectionState.PERMISSION_REQUESTED
            _status.value = "Requesting USB permission..."
            val permissionIntent = PendingIntent.getBroadcast(
                context, 0,
                Intent(ACTION_USB_PERMISSION),
                PendingIntent.FLAG_MUTABLE
            )
            usbManager.requestPermission(device, permissionIntent)
        }
    }

    private fun onPermissionGranted(device: UsbDevice) {
        if (UsbConstants.isAccessoryDevice(device.vendorId, device.productId)) {
            // Already in accessory mode — open endpoints
            scope.launch(Dispatchers.IO) {
                connectToAccessory(device)
            }
        } else {
            // Need AOA switch first
            _connectionState.value = UsbConnectionState.SWITCHING_TO_ACCESSORY
            _status.value = "Switching to accessory mode..."
            scope.launch(Dispatchers.IO) {
                performAoaSwitch(device)
            }
        }
    }

    private suspend fun performAoaSwitch(device: UsbDevice) {
        val success = UsbAccessoryMode.switchToAccessory(usbManager, device)
        if (!success) {
            OalLog.e(TAG, "AOA switch failed")
            _status.value = "AOA switch failed"
            _connectionState.value = UsbConnectionState.IDLE
            return
        }

        // Wait for the device to re-enumerate as a Google Accessory
        _status.value = "Waiting for accessory re-enumeration..."
        delay(AOA_SWITCH_SETTLE_MS)

        // Poll for the accessory device
        var attempts = 0
        while (isRunning && attempts < ACCESSORY_SCAN_MAX_ATTEMPTS) {
            val devices = usbManager.deviceList
            for ((_, d) in devices) {
                if (UsbConstants.isAccessoryDevice(d.vendorId, d.productId)) {
                    OalLog.i(TAG, "Accessory device found after AOA switch: ${d.deviceName}")
                    _connectionState.value = UsbConnectionState.ACCESSORY_DETECTED
                    requestPermissionOrConnect(d)
                    return
                }
            }
            attempts++
            delay(ACCESSORY_SCAN_INTERVAL_MS)
        }
        OalLog.e(TAG, "Accessory device not found after AOA switch ($attempts attempts)")
        _status.value = "Accessory not found after switch"
        _connectionState.value = UsbConnectionState.IDLE
    }

    private fun connectToAccessory(device: UsbDevice) {
        _connectionState.value = UsbConnectionState.CONNECTING
        _status.value = "Opening USB endpoints..."

        val connection = usbManager.openDevice(device)
        if (connection == null) {
            OalLog.e(TAG, "Failed to open accessory device")
            _status.value = "Failed to open device"
            _connectionState.value = UsbConnectionState.IDLE
            return
        }

        // Find the bulk interface and endpoints
        val (iface, epIn, epOut) = findBulkEndpoints(device)
        if (iface == null || epIn == null || epOut == null) {
            OalLog.e(TAG, "No bulk endpoints found on accessory device")
            connection.close()
            _status.value = "No bulk endpoints"
            _connectionState.value = UsbConnectionState.IDLE
            return
        }

        if (!connection.claimInterface(iface, true)) {
            OalLog.e(TAG, "Failed to claim USB interface")
            connection.close()
            _status.value = "Failed to claim interface"
            _connectionState.value = UsbConnectionState.IDLE
            return
        }

        OalLog.i(TAG, "USB endpoints opened — IN: ${epIn.address} OUT: ${epOut.address} " +
                "maxPacket: ${epIn.maxPacketSize}/${epOut.maxPacketSize}")

        currentConnection = connection
        val pipe = UsbTransportPipe(connection, epIn, epOut)
        currentPipe = pipe

        val transportPipe = AasdkTransportPipe(pipe.toInputStream(), pipe.toOutputStream())

        _connectionState.value = UsbConnectionState.CONNECTED
        _status.value = "Connected via USB"

        onTransportReady(transportPipe)
    }

    private data class BulkEndpoints(
        val iface: UsbInterface?,
        val endpointIn: UsbEndpoint?,
        val endpointOut: UsbEndpoint?,
    )

    private fun findBulkEndpoints(device: UsbDevice): BulkEndpoints {
        for (i in 0 until device.interfaceCount) {
            val iface = device.getInterface(i)
            var epIn: UsbEndpoint? = null
            var epOut: UsbEndpoint? = null
            for (j in 0 until iface.endpointCount) {
                val ep = iface.getEndpoint(j)
                if (ep.type == android.hardware.usb.UsbConstants.USB_ENDPOINT_XFER_BULK) {
                    if (ep.direction == android.hardware.usb.UsbConstants.USB_DIR_IN) {
                        epIn = ep
                    } else {
                        epOut = ep
                    }
                }
            }
            if (epIn != null && epOut != null) {
                return BulkEndpoints(iface, epIn, epOut)
            }
        }
        return BulkEndpoints(null, null, null)
    }

    private fun closePipe() {
        currentPipe?.close()
        currentPipe = null
        currentConnection = null
    }

    private fun isHubOrSystemDevice(device: UsbDevice): Boolean {
        // Skip USB hubs (class 9) and mass storage (class 8)
        return device.deviceClass == 9 || device.deviceClass == 8
    }
}
