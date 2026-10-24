package com.openautolink.app.ui.diagnostics

import android.Manifest
import android.app.AppOpsManager
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioManager
import android.os.Process

/** Observations only. Never changes Bluetooth, audio mode, SCO, routing or privacy. */
internal class ParkedCapturePolicy(context: Context) {
    private val app=context.applicationContext
    fun allowed():Boolean = runCatching {
        val audio=app.getSystemService(AudioManager::class.java) ?: return false
        val ops=app.getSystemService(AppOpsManager::class.java) ?: return false
        val op=ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_RECORD_AUDIO,Process.myUid(),app.packageName)
        app.checkSelfPermission(Manifest.permission.RECORD_AUDIO)==PackageManager.PERMISSION_GRANTED &&
            (op==AppOpsManager.MODE_ALLOWED || op==AppOpsManager.MODE_FOREGROUND) &&
            audio.mode==AudioManager.MODE_NORMAL && !audio.isMicrophoneMute &&
            audio.activeRecordingConfigurations.none { it.isClientSilenced }
    }.getOrDefault(false)
}
