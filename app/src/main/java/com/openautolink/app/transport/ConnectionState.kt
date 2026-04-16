package com.openautolink.app.transport

import android.net.Network

/**
 * Transport connection states — tracks the relay + aasdk lifecycle.
 */
enum class ConnectionState {
    DISCONNECTED,
    CONNECTING,       // Outbound TCP to bridge relay in progress
    LISTENING,        // Relay socket connected, waiting for phone via bridge
    PHONE_CONNECTED,  // aasdk JNI reports phone AA session active
    STREAMING         // First video frame received, actively streaming
}

/**
 * Resolves the Android [Network] to bind sockets to for bridge communication.
 */
fun interface NetworkResolver {
    fun resolve(): Network?
}
