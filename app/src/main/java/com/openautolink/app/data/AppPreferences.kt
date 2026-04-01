package com.openautolink.app.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

/**
 * App preferences backed by DataStore.
 */
class AppPreferences private constructor(private val dataStore: DataStore<Preferences>) {

    companion object {
        @Volatile
        private var instance: AppPreferences? = null

        fun getInstance(context: Context): AppPreferences {
            return instance ?: synchronized(this) {
                instance ?: AppPreferences(context.applicationContext.dataStore).also {
                    instance = it
                }
            }
        }

        val BRIDGE_HOST = stringPreferencesKey("bridge_host")
        val BRIDGE_PORT = intPreferencesKey("bridge_port")
        val VIDEO_CODEC = stringPreferencesKey("video_codec")
        val VIDEO_FPS = intPreferencesKey("video_fps")
        val DISPLAY_MODE = stringPreferencesKey("display_mode")
        val MIC_SOURCE = stringPreferencesKey("mic_source")
        val SELF_UPDATE_ENABLED = stringPreferencesKey("self_update_enabled")
        val UPDATE_MANIFEST_URL = stringPreferencesKey("update_manifest_url")
        val NETWORK_INTERFACE = stringPreferencesKey("network_interface")

        const val DEFAULT_BRIDGE_HOST = "192.168.0.100"
        const val DEFAULT_BRIDGE_PORT = 5288
        const val DEFAULT_VIDEO_CODEC = "h264"
        const val DEFAULT_VIDEO_FPS = 60
        const val DEFAULT_DISPLAY_MODE = "system_ui_visible"
        const val DEFAULT_MIC_SOURCE = "car"
        const val DEFAULT_SELF_UPDATE_ENABLED = "off"
        const val DEFAULT_UPDATE_MANIFEST_URL = "https://mossyhub.github.io/openautolink/update.json"
        const val DEFAULT_NETWORK_INTERFACE = "" // empty = auto-select first available
    }

    val bridgeHost: Flow<String> = dataStore.data.map { prefs ->
        prefs[BRIDGE_HOST] ?: DEFAULT_BRIDGE_HOST
    }

    val bridgePort: Flow<Int> = dataStore.data.map { prefs ->
        prefs[BRIDGE_PORT] ?: DEFAULT_BRIDGE_PORT
    }

    val videoCodec: Flow<String> = dataStore.data.map { prefs ->
        prefs[VIDEO_CODEC] ?: DEFAULT_VIDEO_CODEC
    }

    val videoFps: Flow<Int> = dataStore.data.map { prefs ->
        prefs[VIDEO_FPS] ?: DEFAULT_VIDEO_FPS
    }

    val displayMode: Flow<String> = dataStore.data.map { prefs ->
        prefs[DISPLAY_MODE] ?: DEFAULT_DISPLAY_MODE
    }

    val micSource: Flow<String> = dataStore.data.map { prefs ->
        prefs[MIC_SOURCE] ?: DEFAULT_MIC_SOURCE
    }

    val selfUpdateEnabled: Flow<String> = dataStore.data.map { prefs ->
        prefs[SELF_UPDATE_ENABLED] ?: DEFAULT_SELF_UPDATE_ENABLED
    }

    val updateManifestUrl: Flow<String> = dataStore.data.map { prefs ->
        prefs[UPDATE_MANIFEST_URL] ?: DEFAULT_UPDATE_MANIFEST_URL
    }

    val networkInterface: Flow<String> = dataStore.data.map { prefs ->
        prefs[NETWORK_INTERFACE] ?: DEFAULT_NETWORK_INTERFACE
    }

    suspend fun setBridgeHost(host: String) {
        dataStore.edit { it[BRIDGE_HOST] = host }
    }

    suspend fun setBridgePort(port: Int) {
        dataStore.edit { it[BRIDGE_PORT] = port }
    }

    suspend fun setVideoCodec(codec: String) {
        dataStore.edit { it[VIDEO_CODEC] = codec }
    }

    suspend fun setVideoFps(fps: Int) {
        dataStore.edit { it[VIDEO_FPS] = fps }
    }

    suspend fun setDisplayMode(mode: String) {
        dataStore.edit { it[DISPLAY_MODE] = mode }
    }

    suspend fun setMicSource(source: String) {
        dataStore.edit { it[MIC_SOURCE] = source }
    }

    suspend fun setSelfUpdateEnabled(enabled: String) {
        dataStore.edit { it[SELF_UPDATE_ENABLED] = enabled }
    }

    suspend fun setUpdateManifestUrl(url: String) {
        dataStore.edit { it[UPDATE_MANIFEST_URL] = url }
    }

    suspend fun setNetworkInterface(interfaceName: String) {
        dataStore.edit { it[NETWORK_INTERFACE] = interfaceName }
    }
}
