package com.openautolink.app.transport

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import java.net.Inet4Address
import java.net.NetworkInterface

data class NetworkInterfaceInfo(
    val name: String,
    val displayName: String,
    val macAddress: String,
    val ipAddress: String?,
    val isUp: Boolean,
)

/**
 * Discovers USB Ethernet adapters on the AAOS head unit.
 * Filters for Ethernet-type interfaces (non-loopback, non-wireless, non-virtual).
 */
class NetworkInterfaceScanner {

    companion object {
        private const val TAG = "NetworkInterfaceScanner"
        // Patterns for non-Ethernet interfaces to exclude
        private val EXCLUDED_PREFIXES = listOf("lo", "wlan", "p2p", "bt-", "rmnet", "dummy", "tun", "sit")
    }

    private val _interfaces = MutableStateFlow<List<NetworkInterfaceInfo>>(emptyList())
    val interfaces: StateFlow<List<NetworkInterfaceInfo>> = _interfaces

    suspend fun scan(): List<NetworkInterfaceInfo> = withContext(Dispatchers.IO) {
        val result = mutableListOf<NetworkInterfaceInfo>()
        try {
            val netInterfaces = NetworkInterface.getNetworkInterfaces() ?: return@withContext emptyList()
            for (iface in netInterfaces) {
                if (iface.isLoopback) continue
                if (EXCLUDED_PREFIXES.any { iface.name.startsWith(it) }) continue
                // Only include interfaces with hardware addresses (physical NICs)
                val hwAddr = iface.hardwareAddress ?: continue
                val mac = hwAddr.joinToString(":") { "%02x".format(it) }
                val ipv4 = iface.inetAddresses.toList()
                    .filterIsInstance<Inet4Address>()
                    .firstOrNull()
                    ?.hostAddress
                result.add(
                    NetworkInterfaceInfo(
                        name = iface.name,
                        displayName = iface.displayName ?: iface.name,
                        macAddress = mac,
                        ipAddress = ipv4,
                        isUp = iface.isUp,
                    )
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to scan network interfaces", e)
        }
        Log.d(TAG, "Found ${result.size} ethernet interfaces: ${result.map { it.name }}")
        _interfaces.value = result
        result
    }
}
