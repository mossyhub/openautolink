package com.openautolink.carplaylab.telecom

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.provider.Settings

/** Sound UI only, using the same admission/exception policy as TelecomAccountSettings.
 * A returned launch proves neither page usability nor an OAL-scoped ringtone control.
 */
object CarPlaySoundSettings {
    val exactComponent = ComponentName("com.android.car.settings", "com.android.car.settings.common.CarSettingActivities\$SoundSettingsActivity")
    private fun candidate(context: Context): Pair<Intent, Boolean>? {
        val pm = context.packageManager
        fun accessible(info: android.content.pm.ActivityInfo): Boolean {
            val application = info.applicationInfo ?: return false
            fun enabled(setting: Int, declared: Boolean) = when (setting) {
                PackageManager.COMPONENT_ENABLED_STATE_DEFAULT -> declared
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED -> true
                else -> false
            }
            fun permitted(permission: String?) = permission.isNullOrEmpty() || context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
            return info.exported && enabled(pm.getComponentEnabledSetting(ComponentName(info.packageName, info.name)), info.enabled) &&
                enabled(pm.getApplicationEnabledSetting(info.packageName), application.enabled) && permitted(info.permission) && permitted(application.permission)
        }
        try {
            if (accessible(pm.getActivityInfo(exactComponent, 0))) return Intent().setComponent(exactComponent) to true
        } catch (_: PackageManager.NameNotFoundException) { } catch (_: RuntimeException) { }
        try {
            val action = Intent(Settings.ACTION_SOUND_SETTINGS)
            val info = pm.resolveActivity(action, PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo
            val advertised = info != null && pm.queryIntentActivities(action, PackageManager.MATCH_DEFAULT_ONLY).any {
                it.activityInfo?.let { entry -> entry.packageName == info.packageName && entry.name == info.name } == true
            }
            if (info != null && advertised && (info.applicationInfo.flags and (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) != 0 && accessible(info))
                return action.setComponent(ComponentName(info.packageName, info.name)) to false
        } catch (_: RuntimeException) { }
        return null
    }
    fun launch(context: Context): TelecomAccountSettings.Launch {
        val target = candidate(context) ?: return TelecomAccountSettings.Launch.UNAVAILABLE
        return try {
            context.startActivity(target.first.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            if (target.second) TelecomAccountSettings.Launch.EXACT_CANDIDATE_LAUNCHED else TelecomAccountSettings.Launch.PUBLIC_ACTION_CANDIDATE_LAUNCHED
        } catch (_: SecurityException) { TelecomAccountSettings.Launch.SECURITY_EXCEPTION }
        catch (_: android.content.ActivityNotFoundException) { TelecomAccountSettings.Launch.ACTIVITY_NOT_FOUND }
        catch (_: RuntimeException) { TelecomAccountSettings.Launch.OTHER_RUNTIME }
    }
}
