package com.openautolink.app.voice

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import com.openautolink.app.diagnostics.DiagnosticLog

/** The car owns selection; OAL only opens the chooser and never changes the assistant itself. */
object AssistantSettingsLauncher {
    private val actions = listOf(Settings.ACTION_VOICE_INPUT_SETTINGS, "android.settings.MANAGE_DEFAULT_APPS_SETTINGS")

    internal fun pickerIntent(isResolvable: (Intent) -> Boolean): Intent? = actions.asSequence()
        .map(::Intent)
        .firstOrNull(isResolvable)

    fun pickerIntent(context: Context): Intent? = pickerIntent { intent ->
        runCatching { context.packageManager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY) != null }
            .getOrDefault(false)
    }

    fun open(context: Context): Boolean {
        val intent = pickerIntent(context) ?: return false
        return runCatching {
            context.startActivity(Intent(intent).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            DiagnosticLog.i("voice", "Opened car digital-assistant settings action=${intent.action}")
        }.onFailure {
            DiagnosticLog.w("voice", "Could not open car digital-assistant settings type=${it.javaClass.simpleName}")
        }.isSuccess
    }
}
