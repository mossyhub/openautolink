package com.openautolink.app.transport

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Mediator between SettingsViewModel (produces config changes) and
 * SessionManager (sends config_update to the bridge over TCP).
 *
 * SettingsViewModel calls [sendConfigUpdate] when a bridge-relevant setting changes.
 * SessionManager collects [configUpdates] and forwards them to the bridge.
 */
object ConfigUpdateSender {

    private val _configUpdates = MutableSharedFlow<Map<String, String>>(extraBufferCapacity = 8)
    val configUpdates: SharedFlow<Map<String, String>> = _configUpdates.asSharedFlow()

    private val _restartRequests = MutableSharedFlow<ControlMessage.RestartServices>(extraBufferCapacity = 4)
    val restartRequests: SharedFlow<ControlMessage.RestartServices> = _restartRequests.asSharedFlow()

    private val _controlMessages = MutableSharedFlow<ControlMessage>(extraBufferCapacity = 8)
    val controlMessages: SharedFlow<ControlMessage> = _controlMessages.asSharedFlow()

    suspend fun sendConfigUpdate(config: Map<String, String>) {
        _configUpdates.emit(config)
    }

    /** Send config_update followed by restart_services. The bridge saves config then restarts. */
    suspend fun sendConfigAndRestart(
        config: Map<String, String>,
        restartWireless: Boolean = false,
        restartBluetooth: Boolean = false
    ) {
        _configUpdates.emit(config)
        _restartRequests.emit(ControlMessage.RestartServices(restartWireless, restartBluetooth))
    }

    /** Restart bridge services without config changes. */
    suspend fun sendRestart(restartWireless: Boolean = false, restartBluetooth: Boolean = false) {
        _restartRequests.emit(ControlMessage.RestartServices(restartWireless, restartBluetooth))
    }

    /** Send a control message directly to the bridge. */
    suspend fun sendControlMessage(message: ControlMessage) {
        _controlMessages.emit(message)
    }
}
