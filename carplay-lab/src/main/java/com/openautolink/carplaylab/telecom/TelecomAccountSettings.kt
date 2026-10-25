package com.openautolink.carplaylab.telecom

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.telecom.TelecomManager

/** Fixed public Telecom UI, or a system handler advertising the public accounts action.
 * A candidate/returned launch is not proof the OEM page renders or can enable this account.
 */
object TelecomAccountSettings {
    val exactComponent = ComponentName("com.android.server.telecom", "com.android.server.telecom.settings.EnableAccountPreferenceActivity")
    enum class Availability { EXACT_CANDIDATE, PUBLIC_ACTION_CANDIDATE, UNAVAILABLE }
    enum class Launch { EXACT_CANDIDATE_LAUNCHED, PUBLIC_ACTION_CANDIDATE_LAUNCHED, UNAVAILABLE, SECURITY_EXCEPTION, ACTIVITY_NOT_FOUND, OTHER_RUNTIME }
    data class Candidate(val availability: Availability, val intent: Intent?)
    private fun accessible(context: Context, info: ActivityInfo): Boolean {
        val pm = context.packageManager
        val application = info.applicationInfo ?: return false
        fun enabled(setting: Int, declared: Boolean) = when (setting) {
            PackageManager.COMPONENT_ENABLED_STATE_DEFAULT -> declared
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED -> true
            else -> false
        }
        fun permitted(permission: String?) = permission.isNullOrEmpty() ||
            context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
        return info.exported && enabled(pm.getComponentEnabledSetting(ComponentName(info.packageName, info.name)), info.enabled) &&
            enabled(pm.getApplicationEnabledSetting(info.packageName), application.enabled) &&
            permitted(info.permission) && permitted(application.permission)
    }
    fun inspect(context: Context): Candidate {
        val pm = context.packageManager
        try {
            val info = pm.getActivityInfo(exactComponent, 0)
            if (accessible(context, info)) return Candidate(Availability.EXACT_CANDIDATE, Intent().setComponent(exactComponent))
        } catch (_: PackageManager.NameNotFoundException) { }
          catch (_: RuntimeException) { }
        try {
            val action = Intent(TelecomManager.ACTION_CHANGE_PHONE_ACCOUNTS)
            val info = pm.resolveActivity(action, PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo
            val advertised = info != null && pm.queryIntentActivities(action, PackageManager.MATCH_DEFAULT_ONLY).any {
                it.activityInfo?.let { handler -> handler.packageName == info.packageName && handler.name == info.name } == true
            }
            // Public action plus system origin, not arbitrary apps, generic menus or fragment injection.
            if (info != null && advertised && (info.applicationInfo.flags and (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) != 0 && accessible(context, info)) {
                return Candidate(Availability.PUBLIC_ACTION_CANDIDATE, action.setComponent(ComponentName(info.packageName, info.name)))
            }
        } catch (_: RuntimeException) { }
        return Candidate(Availability.UNAVAILABLE, null)
    }
    fun launch(context: Context): Launch {
        val candidate = inspect(context)
        val intent = candidate.intent ?: return Launch.UNAVAILABLE
        return try {
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            if (candidate.availability == Availability.EXACT_CANDIDATE) Launch.EXACT_CANDIDATE_LAUNCHED else Launch.PUBLIC_ACTION_CANDIDATE_LAUNCHED
        } catch (_: SecurityException) { Launch.SECURITY_EXCEPTION }
          catch (_: android.content.ActivityNotFoundException) { Launch.ACTIVITY_NOT_FOUND }
          catch (_: RuntimeException) { Launch.OTHER_RUNTIME }
    }
}
