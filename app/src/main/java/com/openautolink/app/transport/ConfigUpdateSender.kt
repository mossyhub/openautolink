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

    suspend fun sendConfigUpdate(config: Map<String, String>) {
        _configUpdates.emit(config)
    }
}
