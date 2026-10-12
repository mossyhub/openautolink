package com.openautolink.app.transport.usb

import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbManager
import com.openautolink.app.diagnostics.OalLog

/**
 * Performs the AOA v2 handshake to switch a USB device into Android Accessory mode.
 *
 * Sequence:
 *   1. GET_PROTOCOL (51) — verify device supports AOA v1+
 *   2. SEND_STRING (52) × 6 — send accessory identity strings
 *   3. START (53) — device re-enumerates as Google Accessory (18D1:2D00/2D01)
 */
object UsbAccessoryMode {

    private const val TAG = "UsbAccessoryMode"

    /**
     * Initiate AOA switch on the given device. After success, the device will
     * disconnect and re-enumerate with Google Accessory VID/PID.
     *
     * @return true if the switch was initiated successfully
     */
    fun switchToAccessory(usbManager: UsbManager, device: UsbDevice): Boolean {
        // Defense in depth: Apple must never receive GET_PROTOCOL/SEND_STRING/START.
        if (UsbDispatchOwnership.route(device.vendorId) == UsbDispatchOwnership.Route.APPLE) return false
        val connection = usbManager.openDevice(device)
        if (connection == null) {
            OalLog.e(TAG, "Failed to open USB device: ${device.deviceName}")
            return false
        }

        try {
            // Step 1: GET_PROTOCOL — check AOA version support
            val version = getProtocol(connection)
            if (version < 1) {
                OalLog.e(TAG, "Device does not support AOA (protocol version: $version)")
                return false
            }
            OalLog.i(TAG, "Device supports AOA v$version")

            // Step 2: SEND_STRING — send accessory identity
            if (!sendString(connection, UsbConstants.ACC_IDX_MANUFACTURER, UsbConstants.MANUFACTURER)) return false
            if (!sendString(connection, UsbConstants.ACC_IDX_MODEL, UsbConstants.MODEL)) return false
            if (!sendString(connection, UsbConstants.ACC_IDX_DESCRIPTION, UsbConstants.DESCRIPTION)) return false
            if (!sendString(connection, UsbConstants.ACC_IDX_VERSION, UsbConstants.VERSION)) return false
            if (!sendString(connection, UsbConstants.ACC_IDX_URI, UsbConstants.URI)) return false
            if (!sendString(connection, UsbConstants.ACC_IDX_SERIAL, UsbConstants.SERIAL)) return false

            // Step 3: START — device will re-enumerate
            val startResult = connection.controlTransfer(
                UsbConstants.USB_TYPE_VENDOR or UsbConstants.USB_DIR_OUT,
                UsbConstants.ACC_REQ_START,
                0, 0,
                null, 0,
                UsbConstants.USB_TIMEOUT_MS
            )
            if (startResult < 0) {
                OalLog.e(TAG, "ACC_REQ_START failed: $startResult")
                return false
            }

            OalLog.i(TAG, "AOA switch initiated — device will re-enumerate as accessory")
            return true
        } finally {
            connection.close()
        }
    }

    private fun getProtocol(connection: UsbDeviceConnection): Int {
        val buffer = ByteArray(2)
        val result = connection.controlTransfer(
            UsbConstants.USB_TYPE_VENDOR or UsbConstants.USB_DIR_IN,
            UsbConstants.ACC_REQ_GET_PROTOCOL,
            0, 0,
            buffer, 2,
            UsbConstants.USB_TIMEOUT_MS
        )
        if (result < 0) {
            OalLog.e(TAG, "GET_PROTOCOL failed: $result")
            return -1
        }
        return (buffer[1].toInt() and 0xFF shl 8) or (buffer[0].toInt() and 0xFF)
    }

    private fun sendString(connection: UsbDeviceConnection, index: Int, value: String): Boolean {
        val data = value.toByteArray(Charsets.UTF_8)
        val result = connection.controlTransfer(
            UsbConstants.USB_TYPE_VENDOR or UsbConstants.USB_DIR_OUT,
            UsbConstants.ACC_REQ_SEND_STRING,
            0, index,
            data, data.size,
            UsbConstants.USB_TIMEOUT_MS
        )
        if (result < 0) {
            OalLog.e(TAG, "SEND_STRING index=$index failed: $result")
            return false
        }
        return true
    }
}
