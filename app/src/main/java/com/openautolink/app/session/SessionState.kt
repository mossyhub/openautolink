package com.openautolink.app.session

import com.openautolink.app.transport.ConnectionState

/**
 * Session states — maps the full lifecycle from idle to streaming.
 */
enum class SessionState {
    IDLE,              // No connection attempt, app just launched
    CONNECTING,        // Attempting TCP to bridge relay
    LISTENING,         // Relay connected, waiting for phone to connect via bridge
    PHONE_CONNECTED,   // Phone AA session active, video/audio about to start
    STREAMING,         // Video and/or audio actively flowing
    ERROR              // Unrecoverable error (shows message, user can retry)
}

/**
 * Maps transport ConnectionState to session-level state.
 */
fun ConnectionState.toSessionState(): SessionState = when (this) {
    ConnectionState.DISCONNECTED -> SessionState.IDLE
    ConnectionState.CONNECTING -> SessionState.CONNECTING
    ConnectionState.LISTENING -> SessionState.LISTENING
    ConnectionState.PHONE_CONNECTED -> SessionState.PHONE_CONNECTED
    ConnectionState.STREAMING -> SessionState.STREAMING
}
