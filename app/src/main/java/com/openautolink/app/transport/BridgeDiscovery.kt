package com.openautolink.app.transport

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

data class DiscoveredBridge(
    val serviceName: String,
    val host: String,
    val port: Int,
    val displayName: String,
    val videoPort: Int?,
    val audioPort: Int?,
)

/**
 * Discovers OpenAutoLink bridges on the local network via mDNS/NSD.
 * Bridge advertises _openautolink._tcp with TXT records: version, name, video_port, audio_port.
 */
class BridgeDiscovery(context: Context) {

    private val nsdManager = context.getSystemService(Context.NSD_SERVICE) as NsdManager

    private val _discoveredBridges = MutableStateFlow<List<DiscoveredBridge>>(emptyList())
    val discoveredBridges: StateFlow<List<DiscoveredBridge>> = _discoveredBridges

    private val _isDiscovering = MutableStateFlow(false)
    val isDiscovering: StateFlow<Boolean> = _isDiscovering

    private var discoveryListener: NsdManager.DiscoveryListener? = null

    companion object {
        private const val TAG = "BridgeDiscovery"
        const val SERVICE_TYPE = "_openautolink._tcp."
    }

    fun startDiscovery() {
        if (_isDiscovering.value) return
        _discoveredBridges.value = emptyList()

        discoveryListener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {
                Log.d(TAG, "mDNS discovery started for $serviceType")
                _isDiscovering.value = true
            }

            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                Log.d(TAG, "Service found: ${serviceInfo.serviceName}")
                @Suppress("DEPRECATION") // resolveService deprecated API 34, but min SDK 32
                nsdManager.resolveService(serviceInfo, object : NsdManager.ResolveListener {
                    override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                        Log.w(TAG, "Resolve failed for ${serviceInfo.serviceName}: error $errorCode")
                    }

                    override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                        val hostAddress = serviceInfo.host?.hostAddress ?: return
                        val attrs = serviceInfo.attributes
                        val bridge = DiscoveredBridge(
                            serviceName = serviceInfo.serviceName,
                            host = hostAddress,
                            port = serviceInfo.port,
                            displayName = attrs["name"]?.let { String(it) } ?: serviceInfo.serviceName,
                            videoPort = attrs["video_port"]?.let { String(it).toIntOrNull() },
                            audioPort = attrs["audio_port"]?.let { String(it).toIntOrNull() },
                        )
                        Log.d(TAG, "Resolved bridge: $bridge")
                        // Add or replace by host (avoid duplicates from re-announcements)
                        _discoveredBridges.value = _discoveredBridges.value
                            .filter { it.host != bridge.host } + bridge
                    }
                })
            }

            override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                Log.d(TAG, "Service lost: ${serviceInfo.serviceName}")
                _discoveredBridges.value = _discoveredBridges.value.filter {
                    it.serviceName != serviceInfo.serviceName
                }
            }

            override fun onDiscoveryStopped(serviceType: String) {
                Log.d(TAG, "mDNS discovery stopped")
                _isDiscovering.value = false
            }

            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.e(TAG, "mDNS discovery start failed: error $errorCode")
                _isDiscovering.value = false
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.e(TAG, "mDNS discovery stop failed: error $errorCode")
                _isDiscovering.value = false
            }
        }

        nsdManager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discoveryListener)
    }

    fun stopDiscovery() {
        discoveryListener?.let {
            try {
                nsdManager.stopServiceDiscovery(it)
            } catch (_: IllegalArgumentException) {
                // Already stopped or never started
            }
            discoveryListener = null
        }
        _isDiscovering.value = false
    }
}
