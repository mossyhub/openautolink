package com.openautolink.app.transport.bluetooth

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import com.openautolink.app.data.AppPreferences
import com.openautolink.app.diagnostics.OalLog
import kotlinx.coroutines.CoroutineScope
import org.json.JSONObject
import java.util.UUID

/**
 * Settings-scope RFCOMM side channel, separate from gearhead's WPP handshake UUID.
 * Payload: a four-byte big-endian length followed by UTF-8 JSON containing ssid and bssid.
 * Response: DataOutputStream.writeUTF("OK" or "ERR").
 */
class WppConfigBtServer(context: Context, parentScope: CoroutineScope) {
    private val receiver = WppConfigReceiver(
        parentScope = parentScope,
        listen = {
            check(ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) ==
                PackageManager.PERMISSION_GRANTED) { "BLUETOOTH_CONNECT not granted" }
            @Suppress("DEPRECATION")
            val adapter = BluetoothAdapter.getDefaultAdapter()
            check(adapter != null && adapter.isEnabled) { "Bluetooth unavailable or disabled" }
            @SuppressLint("MissingPermission")
            val socket = adapter.listenUsingRfcommWithServiceRecord(SDP_NAME, CONFIG_UUID)
            object : WppConfigListener {
                @SuppressLint("MissingPermission")
                override fun accept(): WppConfigClient {
                    val client = socket.accept()
                    return object : WppConfigClient {
                        override val input get() = client.inputStream
                        override val output get() = client.outputStream
                        override fun close() = client.close()
                    }
                }
                override fun close() = socket.close()
            }
        },
        decode = { payload ->
            val json = JSONObject(payload)
            WppConfigValues(json.optString("ssid", ""), json.optString("bssid", "").trim())
        },
        persist = { config, commit ->
            val preferences = AppPreferences.getInstance(context)
            preferences.setWppConfig(config.ssid, config.bssid, commit)
        },
        onEvent = { event ->
            when (event) {
                WppConfigEvent.LISTENER_UP -> OalLog.i(TAG, "WPP config RFCOMM listener up")
                WppConfigEvent.LISTENER_FAILED -> OalLog.w(TAG, "WPP config listener failed")
                WppConfigEvent.APPLIED -> OalLog.i(TAG, "WPP config applied")
                WppConfigEvent.REJECTED -> OalLog.w(TAG, "WPP config rejected")
                WppConfigEvent.STOPPED -> OalLog.i(TAG, "WPP config listener stopped")
            }
        },
    )

    val status = receiver.status
    val appliedConfig = receiver.appliedConfig
    fun start() = receiver.start()
    fun stop() = receiver.stop()

    companion object {
        private const val TAG = "WppConfigBt"
        private const val SDP_NAME = "OpenAutoLink WPP Config"
        val CONFIG_UUID: UUID = UUID.fromString("8a0d7f20-8f8d-4b1f-9f0d-2f8a4fd8d8a1")
    }
}
