package com.openautolink.app.diagnostics

import android.app.Application
import android.os.Build
import android.os.SystemClock
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** Process singleton, not an Android Service. No auto-arm/FGS/wake/boot or network behavior. */
internal object BluetoothReconnectService {
    @Volatile var process: BluetoothReconnectProcess? = null
        private set

    fun initialize(app: Application, scope: CoroutineScope) {
        if (process != null) return
        val base = app.getExternalFilesDir(null) ?: return // same actual manual uploader directory
        val recorder = BluetoothReconnectRecorder(File(base, "openautolink/logs")).apply {
            clock = { SystemClock.elapsedRealtime() }
        }
        process = BluetoothReconnectProcess(app, recorder, scope, Build.VERSION.SDK_INT)
        // Inventory old bounded files only. Never restores consent or registers Bluetooth.
        scope.launch(Dispatchers.IO) { recorder.recoverPreviousRuns() }
    }

    fun session(key: Any, state: String) { process?.markSession(key, state) }
    fun flush(): Boolean = process?.recorder?.flush(2000) ?: true
    fun close() { process?.stop(); process?.recorder?.close(); process = null }
}
