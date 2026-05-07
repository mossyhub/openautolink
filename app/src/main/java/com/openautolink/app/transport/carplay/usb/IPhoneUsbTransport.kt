package com.openautolink.app.transport.carplay.usb

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import com.openautolink.app.diagnostics.OalLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

private const val TAG = "iPhoneUsb"

/** Apple USB Vendor ID */
const val APPLE_VENDOR_ID = 0x05AC

/** iAP2 USB interface class (vendor-specific) */
const val IAP2_INTERFACE_CLASS = 0xFF
const val IAP2_INTERFACE_SUBCLASS = 0xF0
const val IAP2_INTERFACE_PROTOCOL = 0x00

/** USB permission action */
private const val ACTION_USB_PERMISSION = "com.openautolink.app.USB_PERMISSION_CARPLAY"

/** Bulk transfer timeout ms */
private const val BULK_TIMEOUT_MS = 1000

/**
 * USB host transport for iPhone iAP2 communication.
 *
 * Finds the iPhone's iAP2 vendor-specific interface, claims it, and
 * provides read/write on the bulk endpoints for iAP2 link-layer frames.
 *
 * Usage:
 *   1. Call [findIPhone] to detect a connected iPhone
 *   2. Call [requestPermission] if permission not yet granted
 *   3. Call [open] to claim the interface and prepare endpoints
 *   4. Use [read] / [write] for bulk transfers
 *   5. Call [close] when done
 */
class IPhoneUsbTransport(private val context: Context) {

    private val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager

    private var device: UsbDevice? = null
    private var connection: UsbDeviceConnection? = null
    private var iap2Interface: UsbInterface? = null
    private var endpointIn: UsbEndpoint? = null
    private var endpointOut: UsbEndpoint? = null

    private val _state = MutableStateFlow(TransportState.DISCONNECTED)
    val state: StateFlow<TransportState> = _state.asStateFlow()

    /** Permission grant channel — suspends until user responds */
    private val permissionChannel = Channel<Boolean>(1)

    private val permissionReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            if (intent.action == ACTION_USB_PERMISSION) {
                val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                OalLog.i(TAG, "USB permission ${if (granted) "GRANTED" else "DENIED"}")
                permissionChannel.trySend(granted)
            }
        }
    }

    /**
     * Find a connected iPhone by scanning USB devices for Apple VID
     * and an iAP2 vendor-specific interface.
     */
    fun findIPhone(): UsbDevice? {
        val devices = usbManager.deviceList
        for ((_, dev) in devices) {
            if (dev.vendorId != APPLE_VENDOR_ID) continue

            // Look for the iAP2 interface (class 0xFF, subclass 0xF0)
            for (i in 0 until dev.interfaceCount) {
                val iface = dev.getInterface(i)
                if (iface.interfaceClass == IAP2_INTERFACE_CLASS &&
                    iface.interfaceSubclass == IAP2_INTERFACE_SUBCLASS) {
                    OalLog.i(TAG, "Found iPhone: ${dev.productName} (${dev.deviceName}) " +
                            "iAP2 interface #$i endpoints=${iface.endpointCount}")
                    device = dev
                    return dev
                }
            }

            // Some iPhones use class 0xFF without subclass 0xF0
            // Fall back to any vendor-specific interface with 2 bulk endpoints
            for (i in 0 until dev.interfaceCount) {
                val iface = dev.getInterface(i)
                if (iface.interfaceClass == IAP2_INTERFACE_CLASS &&
                    iface.endpointCount >= 2) {
                    val hasIn = (0 until iface.endpointCount).any {
                        iface.getEndpoint(it).direction == UsbConstants.USB_DIR_IN
                    }
                    val hasOut = (0 until iface.endpointCount).any {
                        iface.getEndpoint(it).direction == UsbConstants.USB_DIR_OUT
                    }
                    if (hasIn && hasOut) {
                        OalLog.i(TAG, "Found iPhone (fallback): ${dev.productName} " +
                                "interface #$i class=0xFF endpoints=${iface.endpointCount}")
                        device = dev
                        return dev
                    }
                }
            }
        }
        OalLog.i(TAG, "No iPhone found (${devices.size} USB devices)")
        return null
    }

    /** Check if we have USB permission for the iPhone. */
    fun hasPermission(): Boolean {
        val dev = device ?: return false
        return usbManager.hasPermission(dev)
    }

    /**
     * Request USB permission. Suspends until user grants or denies.
     * Returns true if granted.
     */
    suspend fun requestPermission(): Boolean = withContext(Dispatchers.Main) {
        val dev = device ?: return@withContext false
        if (usbManager.hasPermission(dev)) return@withContext true

        val filter = IntentFilter(ACTION_USB_PERMISSION)
        context.registerReceiver(permissionReceiver, filter, Context.RECEIVER_NOT_EXPORTED)

        val pendingIntent = PendingIntent.getBroadcast(
            context, 0,
            Intent(ACTION_USB_PERMISSION),
            PendingIntent.FLAG_MUTABLE,
        )
        usbManager.requestPermission(dev, pendingIntent)
        OalLog.i(TAG, "USB permission requested for ${dev.productName}")

        // Wait for user response
        val granted = permissionChannel.receive()
        try { context.unregisterReceiver(permissionReceiver) } catch (_: Exception) {}
        granted
    }

    /**
     * Open the USB connection: claim the iAP2 interface and find bulk endpoints.
     */
    suspend fun open(): Boolean = withContext(Dispatchers.IO) {
        val dev = device ?: run {
            OalLog.e(TAG, "No iPhone device set")
            return@withContext false
        }

        if (!usbManager.hasPermission(dev)) {
            OalLog.e(TAG, "No USB permission")
            return@withContext false
        }

        val conn = usbManager.openDevice(dev) ?: run {
            OalLog.e(TAG, "Failed to open USB device")
            return@withContext false
        }

        // Find the iAP2 interface
        var iface: UsbInterface? = null
        for (i in 0 until dev.interfaceCount) {
            val candidate = dev.getInterface(i)
            if (candidate.interfaceClass == IAP2_INTERFACE_CLASS) {
                iface = candidate
                break
            }
        }
        if (iface == null) {
            OalLog.e(TAG, "No iAP2 interface found")
            conn.close()
            return@withContext false
        }

        if (!conn.claimInterface(iface, true)) {
            OalLog.e(TAG, "Failed to claim iAP2 interface")
            conn.close()
            return@withContext false
        }

        // Find bulk IN and OUT endpoints
        var epIn: UsbEndpoint? = null
        var epOut: UsbEndpoint? = null
        for (i in 0 until iface.endpointCount) {
            val ep = iface.getEndpoint(i)
            if (ep.type != UsbConstants.USB_ENDPOINT_XFER_BULK) continue
            when (ep.direction) {
                UsbConstants.USB_DIR_IN -> if (epIn == null) epIn = ep
                UsbConstants.USB_DIR_OUT -> if (epOut == null) epOut = ep
            }
        }

        if (epIn == null || epOut == null) {
            OalLog.e(TAG, "Missing bulk endpoints (in=$epIn out=$epOut)")
            conn.releaseInterface(iface)
            conn.close()
            return@withContext false
        }

        connection = conn
        iap2Interface = iface
        endpointIn = epIn
        endpointOut = epOut
        _state.value = TransportState.CONNECTED

        OalLog.i(TAG, "iPhone USB opened: " +
                "epIn=0x${"%02X".format(epIn.address)} (maxPacket=${epIn.maxPacketSize}) " +
                "epOut=0x${"%02X".format(epOut.address)} (maxPacket=${epOut.maxPacketSize})")
        true
    }

    /**
     * Write data to the iPhone via bulk OUT endpoint.
     * Returns the number of bytes written, or -1 on error.
     */
    fun write(data: ByteArray, offset: Int = 0, length: Int = data.size): Int {
        val conn = connection ?: return -1
        val ep = endpointOut ?: return -1
        return if (offset == 0 && length == data.size) {
            conn.bulkTransfer(ep, data, length, BULK_TIMEOUT_MS)
        } else {
            conn.bulkTransfer(ep, data.copyOfRange(offset, offset + length), length, BULK_TIMEOUT_MS)
        }
    }

    /**
     * Read data from the iPhone via bulk IN endpoint.
     * Returns the number of bytes read, or -1 on error/timeout.
     */
    fun read(buffer: ByteArray, timeoutMs: Int = BULK_TIMEOUT_MS): Int {
        val conn = connection ?: return -1
        val ep = endpointIn ?: return -1
        return conn.bulkTransfer(ep, buffer, buffer.size, timeoutMs)
    }

    /**
     * Close the USB connection and release the interface.
     */
    fun close() {
        val conn = connection
        val iface = iap2Interface
        if (conn != null && iface != null) {
            conn.releaseInterface(iface)
        }
        conn?.close()
        connection = null
        iap2Interface = null
        endpointIn = null
        endpointOut = null
        _state.value = TransportState.DISCONNECTED
        OalLog.i(TAG, "iPhone USB closed")
    }

    /** Get the max packet size for the OUT endpoint. */
    val maxPacketSize: Int get() = endpointOut?.maxPacketSize ?: 512

    enum class TransportState {
        DISCONNECTED,
        CONNECTED,
        ERROR,
    }
}
