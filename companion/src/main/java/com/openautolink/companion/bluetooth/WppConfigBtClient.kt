package com.openautolink.companion.bluetooth

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import com.openautolink.companion.diagnostics.CompanionLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.UUID

object WppConfigBtClient {
    private const val TAG = "WppConfigBt"
    private val CONFIG_UUID: UUID = UUID.fromString("8a0d7f20-8f8d-4b1f-9f0d-2f8a4fd8d8a1")

    @SuppressLint("MissingPermission")
    suspend fun sendToTargetCars(
        context: Context,
        targetMacs: Set<String>,
        ssid: String,
        bssid: String,
    ): Result<Int> = withContext(Dispatchers.IO) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return@withContext Result.failure(IllegalStateException("BLUETOOTH_CONNECT not granted"))
        }
        @Suppress("DEPRECATION")
        val adapter = BluetoothAdapter.getDefaultAdapter()
            ?: return@withContext Result.failure(IllegalStateException("Bluetooth unavailable"))
        if (!adapter.isEnabled) {
            return@withContext Result.failure(IllegalStateException("Bluetooth disabled"))
        }

        val normalizedTargets = targetMacs.map { it.trim().lowercase() }.toSet()
        val candidates = adapter.bondedDevices
            ?.filter { it.address.trim().lowercase() in normalizedTargets }
            ?.sortedBy { it.address }
            .orEmpty()

        val active = candidates.filter { isConnectedDevice(it) }
        val targets = if (active.isNotEmpty()) active else candidates
        if (targets.isEmpty()) {
            return@withContext Result.failure(IllegalStateException("No selected car Bluetooth devices are paired"))
        }
        if (active.isEmpty()) {
            CompanionLog.w(TAG, "No active ACL link detected for target car device(s); using paired device list anyway")
        }

        var sent = 0
        targets.forEach { device ->
            if (sendToDevice(device, ssid, bssid)) {
                sent += 1
            }
        }
        if (sent == 0) Result.failure(IllegalStateException("No selected car device acknowledged the WPP config update"))
        else Result.success(sent)
    }

    @SuppressLint("MissingPermission")
    private fun isConnectedDevice(device: BluetoothDevice): Boolean {
        return try {
            val method = device.javaClass.getMethod("isConnected")
            (method.invoke(device) as? Boolean) == true
        } catch (_: Exception) {
            false
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun sendToDevice(
        device: BluetoothDevice,
        ssid: String,
        bssid: String,
    ): Boolean {
        val payloadBytes = JSONObject()
            .put("ssid", ssid)
            .put("bssid", bssid)
            .toString()
            .toByteArray(Charsets.UTF_8)
        CompanionLog.i(TAG, "Opening WPP BT socket to ${device.address}")
        val socket = try {
            device.createRfcommSocketToServiceRecord(CONFIG_UUID)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            CompanionLog.w(TAG, "Socket create failed for ${device.address}: ${e.message}")
            return false
        }
        return try {
            val acknowledged = WppConfigBtExchange.send(object : WppConfigBtTransport {
                override fun connect() = socket.connect()
                override val inputStream get() = socket.inputStream
                override val outputStream get() = socket.outputStream
                override fun close() = socket.close()
            }, payloadBytes)
            if (acknowledged) {
                CompanionLog.i(TAG, "Confirmed WPP update from ${device.address}")
            } else {
                CompanionLog.w(TAG, "WPP update rejected or timed out for ${device.address}")
            }
            acknowledged
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            CompanionLog.w(TAG, "Send failed for ${device.address}: ${e.message}")
            false
        }
    }
}
