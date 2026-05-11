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
    PERMISSION_REQUESTED,
    SWITCHING_TO_ACCESSORY,
    ACCESSORY_DETECTED,
    CONNECTING,
    CONNECTED,
}

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
        _connectionState.value = UsbConnectionState.IDLE
        _status.value = "Waiting for USB device..."

        val filter = IntentFilter().apply {
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
            addAction(ACTION_USB_PERMISSION)
        }
        context.registerReceiver(usbReceiver, filter, Context.RECEIVER_NOT_EXPORTED)

        // Check for already-connected devices
        scanExistingDevices()
    }

    fun stop() {
        if (!isRunning) return
        isRunning = false
        scanJob?.cancel()
        scanJob = null
        try {
            context.unregisterReceiver(usbReceiver)
        } catch (_: IllegalArgumentException) { }
        closePipe()
        _connectionState.value = UsbConnectionState.IDLE
        _status.value = "Stopped"
    }

    private fun scanExistingDevices() {
        val devices = usbManager.deviceList
        OalLog.i(TAG, "Scanning ${devices.size} existing USB devices")
        for ((_, device) in devices) {
            if (UsbConstants.isAccessoryDevice(device.vendorId, device.productId)) {
                OalLog.i(TAG, "Found device already in accessory mode: ${device.deviceName}")
                _connectionState.value = UsbConnectionState.ACCESSORY_DETECTED
                requestPermissionOrConnect(device)
                return
            }
        }
        // No accessory device — check for any phone-like device
        for ((_, device) in devices) {
            if (!isHubOrSystemDevice(device)) {
                OalLog.i(TAG, "Found candidate USB device: ${device.deviceName} " +
                        "VID=${String.format("%04X", device.vendorId)} " +
                        "PID=${String.format("%04X", device.productId)}")
                handleDeviceAttached(device)
                return
            }
        }
    }

    private fun handleDeviceAttached(device: UsbDevice) {
        if (UsbConstants.isAccessoryDevice(device.vendorId, device.productId)) {
            _connectionState.value = UsbConnectionState.ACCESSORY_DETECTED
            _status.value = "Accessory device detected"
            requestPermissionOrConnect(device)
        } else {
            _connectionState.value = UsbConnectionState.DEVICE_DETECTED
            _status.value = "Device detected — requesting permission..."
            requestPermissionOrConnect(device)
        }
    }

    private fun handleDeviceDetached() {
        closePipe()
        _connectionState.value = UsbConnectionState.IDLE
        _status.value = "USB device disconnected"
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
